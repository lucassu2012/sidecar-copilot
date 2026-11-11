#include "local_sidecar_copilot_jnihost_JniBackendPort.h"
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <thread>
#include <unordered_map>

// Original host fixture. No USB calls, native upstream, OS FD close/dup, or frame conversion.
namespace {
JavaVM* vm = nullptr;
std::atomic<jlong> next_context{0}, live_contexts{0}, global_refs{0}, buffer_bytes{0}, attached_threads{0};
constexpr int gate_count = 9;
enum GateIndex { open_return, submit_return, control_return, callback_enter, callback_tail,
    cancel_return, close_return, join_return, context_return };
struct Gate { bool armed = false, released = false; jlong entries = 0; };

struct Transfer {
    jlong id = 0, bytes = 0, calls = 0, callbacks = 0;
    int channel = 0, outcome = 0;
    bool queued = false, completed = false, releasing = false;
    std::unique_ptr<unsigned char[]> buffer;
    jobject callback = nullptr;
    jmethodID dispatch = nullptr;
    void clear_buffer() noexcept {
        if (buffer) {
            volatile unsigned char* cursor = buffer.get();
            for (jlong i = 0; i < bytes; ++i) cursor[static_cast<std::size_t>(i)] = 0;
            buffer.reset(); buffer_bytes.fetch_sub(bytes);
        }
    }
    ~Transfer() { clear_buffer(); } // JNI GlobalRefs are explicitly deleted with a live thread JNIEnv.
};

struct Context {
    std::mutex mutex;
    std::condition_variable changed;
    std::array<Gate, gate_count> gates{};
    std::unordered_map<jlong, std::shared_ptr<Transfer>> transfers;
    std::deque<std::shared_ptr<Transfer>> queue;
    std::thread worker;
    jlong id = 0, calls = 0, callbacks = 0, bytes = 0, refs = 0, release_waiters = 0;
    jlong attach_count = 0, detach_count = 0, exceptions = 0, completed_callbacks = 0, freed = 0, zeroed = 0;
    jint borrowed_fd = -1;
    bool admission_closed = false, cancel_completes = true, exit_worker = false, worker_alive = false;
    bool attached = false, device_closed = false, joined = false, released = false;
};

std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Context>> registry;

std::shared_ptr<Context> lookup(jlong id) {
    std::lock_guard<std::mutex> held(registry_mutex);
    auto found = registry.find(id);
    if (found == registry.end()) throw std::runtime_error("unknown/retired native context");
    return found->second; // Shared ownership pins lifetime independently of a recycled integer FD.
}

void throw_java(JNIEnv* env, const char* message) noexcept {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
template<class R, class F> R protect(JNIEnv* env, R fallback, F body) noexcept {
    try { return body(); }
    catch (const std::exception& failure) { throw_java(env, failure.what()); }
    catch (...) { throw_java(env, "native fixture exception"); }
    return fallback;
}
template<class F> void protect_void(JNIEnv* env, F body) noexcept {
    protect<int>(env, 0, [&]() { body(); return 0; });
}

struct Call {
    std::shared_ptr<Context> context;
    std::shared_ptr<Transfer> transfer;
    explicit Call(std::shared_ptr<Context> value) : context(std::move(value)) {
        std::lock_guard<std::mutex> held(context->mutex);
        if (context->released) throw std::runtime_error("context release already committed");
        ++context->calls;
    }
    ~Call() {
        std::lock_guard<std::mutex> held(context->mutex);
        if (transfer) --transfer->calls;
        --context->calls; context->changed.notify_all();
    }
};

void gate_wait(const std::shared_ptr<Context>& context, int index) {
    std::unique_lock<std::mutex> held(context->mutex);
    Gate& gate = context->gates.at(static_cast<std::size_t>(index));
    ++gate.entries; context->changed.notify_all();
    context->changed.wait(held, [&]() { return !gate.armed || gate.released; });
}

bool clear_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    jthrowable exception = env->ExceptionOccurred();
    env->ExceptionClear();
    if (exception != nullptr) env->DeleteLocalRef(exception);
    return true;
}

void worker_main(const std::shared_ptr<Context>& context) noexcept {
    // JNIEnv belongs only to this native thread. It is never stored in Context or shared.
    JNIEnv* env = nullptr;
    bool attached_here = false;
    bool attach_ok = vm != nullptr;
    if (attach_ok) {
        jint result = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
        if (result == JNI_EDETACHED) {
            result = vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
            attached_here = result == JNI_OK;
        }
        attach_ok = result == JNI_OK && env != nullptr;
    }
    {
        std::lock_guard<std::mutex> held(context->mutex);
        context->worker_alive = attach_ok;
        context->attached = attached_here;
        if (attached_here) { ++context->attach_count; attached_threads.fetch_add(1); }
        if (!attach_ok) ++context->exceptions;
        context->changed.notify_all();
    }
    if (!attach_ok) return;
    try {
        for (;;) {
            std::shared_ptr<Transfer> transfer;
            {
                std::unique_lock<std::mutex> held(context->mutex);
                context->changed.wait(held, [&]() { return context->exit_worker || !context->queue.empty(); });
                if (context->exit_worker && context->queue.empty()) break;
                transfer = context->queue.front(); context->queue.pop_front();
                ++transfer->callbacks; ++context->callbacks; context->changed.notify_all();
            }
            gate_wait(context, callback_enter);
            bool exception = false;
            if (env->PushLocalFrame(16) == JNI_OK) {
                jobject local_callback = env->NewLocalRef(transfer->callback);
                if (!clear_exception(env) && local_callback != nullptr) {
                    // No native mutex is held across Java code. Parent adapter owns its own lease.
                    env->CallVoidMethod(local_callback, transfer->dispatch, static_cast<jint>(transfer->outcome));
                    exception = clear_exception(env);
                } else exception = true;
                env->PopLocalFrame(nullptr);
                if (clear_exception(env)) exception = true;
            } else { clear_exception(env); exception = true; }
            if (exception) { std::lock_guard<std::mutex> held(context->mutex); ++context->exceptions; }
            // The Java lease has closed and its local JNI references are gone, but this native
            // callback still borrows transfer/context/GlobalRef until the tail gate returns.
            gate_wait(context, callback_tail);
            {
                std::lock_guard<std::mutex> held(context->mutex);
                transfer->completed = true; --transfer->callbacks; --context->callbacks;
                ++context->completed_callbacks; context->changed.notify_all();
            }
        }
    } catch (...) {
        // Unexpected worker failure retains obligations; never pretend a missing terminal drained.
        std::lock_guard<std::mutex> held(context->mutex); ++context->exceptions;
    }
    if (attached_here) {
        // All local frames and pending exceptions have been handled before detaching this thread.
        clear_exception(env);
        jint result = vm->DetachCurrentThread();
        std::lock_guard<std::mutex> held(context->mutex);
        if (result == JNI_OK) { context->attached = false; ++context->detach_count; attached_threads.fetch_sub(1); }
        else ++context->exceptions;
    }
    {
        std::lock_guard<std::mutex> held(context->mutex);
        context->worker_alive = false; context->changed.notify_all();
    }
}

std::array<jlong, 18> snapshot_locked(const Context& context) {
    return {{context.id, static_cast<jlong>(context.transfers.size()), context.bytes, context.refs,
        context.calls, context.callbacks, context.worker_alive ? 1 : 0, context.attached ? 1 : 0,
        context.attach_count, context.detach_count, context.exceptions, context.completed_callbacks,
        context.freed, context.joined ? 1 : 0, context.released ? 1 : 0, context.borrowed_fd,
        context.release_waiters, context.zeroed}};
}
template<std::size_t N> jlongArray array(JNIEnv* env, const std::array<jlong, N>& values) {
    jlongArray result = env->NewLongArray(static_cast<jsize>(N));
    if (result != nullptr) env->SetLongArrayRegion(result, 0, static_cast<jsize>(N), values.data());
    return result;
}
void check_gate(jint gate) { if (gate < 0 || gate >= gate_count) throw std::runtime_error("invalid gate"); }
void check_timeout(jlong timeout) { if (timeout < 0 || timeout > 60000) throw std::runtime_error("bounded wait required"); }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* incoming, void*) { vm = incoming; return JNI_VERSION_1_8; }

JNIEXPORT jlong JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nCreate
  (JNIEnv* env, jclass, jint fd, jlong mask, jboolean complete_cancel) {
    return protect<jlong>(env, 0, [&]() {
        if (fd < 0) throw std::runtime_error("negative borrowed FD");
        auto context = std::make_shared<Context>();
        context->id = next_context.fetch_add(1) + 1;
        if (context->id <= 0) throw std::runtime_error("native identity exhausted");
        context->borrowed_fd = fd; context->cancel_completes = complete_cancel != JNI_FALSE;
        for (int i = 0; i < gate_count; ++i) context->gates[static_cast<std::size_t>(i)].armed = (mask & (jlong{1} << i)) != 0;
        {
            std::lock_guard<std::mutex> held(registry_mutex); registry.emplace(context->id, context); live_contexts.fetch_add(1);
        }
        try { context->worker = std::thread([context]() { worker_main(context); }); }
        catch (...) {
            std::lock_guard<std::mutex> held(registry_mutex); registry.erase(context->id); live_contexts.fetch_sub(1); throw;
        }
        return context->id;
    });
}
JNIEXPORT void JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nOpen(JNIEnv* env, jclass, jlong id) {
    protect_void(env, [&]() { auto context = lookup(id); Call call(context); gate_wait(context, open_return); });
}
JNIEXPORT jint JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nStart(JNIEnv* env, jclass, jlong id) {
    return protect<jint>(env, 2, [&]() {
        auto context = lookup(id); Call call(context); std::lock_guard<std::mutex> held(context->mutex);
        return static_cast<jint>(context->admission_closed ? 2 : 0);
    });
}

JNIEXPORT jint JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nSubmit
  (JNIEnv* env, jclass, jlong id, jlong transfer_id, jint channel, jlong bytes, jobject callback) {
    return protect<jint>(env, 2, [&]() -> jint {
        auto context = lookup(id); Call call(context);
        if (bytes <= 0 || bytes > std::numeric_limits<jint>::max() || callback == nullptr) return 3;
        auto transfer = std::make_shared<Transfer>();
        transfer->id = transfer_id; transfer->bytes = bytes; transfer->channel = channel;
        transfer->buffer.reset(new (std::nothrow) unsigned char[static_cast<std::size_t>(bytes)]);
        if (!transfer->buffer) return 3;
        buffer_bytes.fetch_add(bytes); std::memset(transfer->buffer.get(), 0x5a, static_cast<std::size_t>(bytes));
        jclass type = env->GetObjectClass(callback);
        if (type == nullptr || env->ExceptionCheck()) { clear_exception(env); if (type) env->DeleteLocalRef(type); return 3; }
        transfer->dispatch = env->GetMethodID(type, "dispatch", "(I)V"); env->DeleteLocalRef(type);
        if (transfer->dispatch == nullptr || env->ExceptionCheck()) { clear_exception(env); return 2; }
        transfer->callback = env->NewGlobalRef(callback);
        if (transfer->callback == nullptr || env->ExceptionCheck()) { clear_exception(env); return 3; }
        global_refs.fetch_add(1);
        jint result = 0;
        try {
            std::lock_guard<std::mutex> held(context->mutex);
            if (context->admission_closed) result = 2;
            else if (context->transfers.count(transfer_id) != 0) result = 1;
            else {
                context->transfers.emplace(transfer_id, transfer);
                transfer->calls = 1; call.transfer = transfer;
                context->bytes += bytes; ++context->refs;
            }
        } catch (...) {
            env->DeleteGlobalRef(transfer->callback); transfer->callback = nullptr; global_refs.fetch_sub(1); throw;
        }
        if (result != 0) {
            env->DeleteGlobalRef(transfer->callback); transfer->callback = nullptr; global_refs.fetch_sub(1); return result;
        }
        gate_wait(context, submit_return);
        return 0;
    });
}

JNIEXPORT jint JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nQueue
  (JNIEnv* env, jclass, jlong id, jlong transfer_id, jint outcome) {
    return protect<jint>(env, 2, [&]() -> jint {
        auto context = lookup(id); std::lock_guard<std::mutex> held(context->mutex);
        auto found = context->transfers.find(transfer_id);
        if (found == context->transfers.end() || found->second->releasing) return 2;
        auto transfer = found->second;
        if (transfer->queued || transfer->completed) return 1;
        context->queue.push_back(transfer); transfer->outcome = outcome; transfer->queued = true;
        context->changed.notify_all(); return 0;
    });
}

JNIEXPORT jint JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nCancel
  (JNIEnv* env, jclass, jlong id, jlong transfer_id) {
    return protect<jint>(env, 2, [&]() -> jint {
        auto context = lookup(id); Call call(context); jint result = 0;
        {
            std::lock_guard<std::mutex> held(context->mutex); context->admission_closed = true;
            auto found = context->transfers.find(transfer_id);
            if (found == context->transfers.end() || found->second->releasing) return 1;
            call.transfer = found->second; ++call.transfer->calls;
            if (call.transfer->queued || call.transfer->completed) result = 1;
            else if (context->cancel_completes) {
                context->queue.push_back(call.transfer); call.transfer->outcome = 1; call.transfer->queued = true;
                context->changed.notify_all();
            }
        }
        gate_wait(context, cancel_return); return result;
    });
}

JNIEXPORT jint JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nControl(JNIEnv* env, jclass, jlong id) {
    return protect<jint>(env, 2, [&]() -> jint {
        auto context = lookup(id); Call call(context);
        { std::lock_guard<std::mutex> held(context->mutex); if (context->admission_closed) return 2; }
        gate_wait(context, control_return); return 0;
    });
}

JNIEXPORT void JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nReleaseTransfer
  (JNIEnv* env, jclass, jlong id, jlong transfer_id) {
    protect_void(env, [&]() {
        auto context = lookup(id); Call call(context); std::shared_ptr<Transfer> transfer;
        {
            std::unique_lock<std::mutex> held(context->mutex);
            auto found = context->transfers.find(transfer_id);
            if (found == context->transfers.end() || found->second->releasing) throw std::runtime_error("duplicate transfer release");
            transfer = found->second; transfer->releasing = true;
            auto ready = [&]() { return transfer->completed && transfer->calls == 0 && transfer->callbacks == 0; };
            if (!ready()) {
                ++context->release_waiters; context->changed.notify_all();
                context->changed.wait(held, ready); --context->release_waiters;
            }
        }
        // The tail and all submit/cancel stacks have returned. No mutex across JNI reference ops.
        env->DeleteGlobalRef(transfer->callback); transfer->callback = nullptr; global_refs.fetch_sub(1);
        transfer->clear_buffer();
        {
            std::lock_guard<std::mutex> held(context->mutex);
            context->transfers.erase(transfer_id); context->bytes -= transfer->bytes; --context->refs;
            ++context->freed; ++context->zeroed; context->changed.notify_all();
        }
    });
}

JNIEXPORT void JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nCloseDevice(JNIEnv* env, jclass, jlong id) {
    protect_void(env, [&]() {
        auto context = lookup(id); Call call(context);
        {
            std::lock_guard<std::mutex> held(context->mutex); context->admission_closed = true;
            if (!context->transfers.empty() || context->callbacks != 0 || context->calls != 1)
                throw std::runtime_error("device close before drain");
        }
        gate_wait(context, close_return);
        std::lock_guard<std::mutex> held(context->mutex); context->device_closed = true;
    });
}

JNIEXPORT void JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nJoin(JNIEnv* env, jclass, jlong id) {
    protect_void(env, [&]() {
        auto context = lookup(id); Call call(context);
        {
            std::lock_guard<std::mutex> held(context->mutex);
            if (!context->device_closed || !context->transfers.empty() || context->callbacks != 0 || context->calls != 1)
                throw std::runtime_error("pump join before drain");
            context->exit_worker = true; context->changed.notify_all();
        }
        if (context->worker.joinable()) context->worker.join(); // Never join while holding the context mutex.
        { std::lock_guard<std::mutex> held(context->mutex); context->joined = true; }
        gate_wait(context, join_return);
    });
}

JNIEXPORT jlongArray JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nReleaseContext(JNIEnv* env, jclass, jlong id) {
    return protect<jlongArray>(env, nullptr, [&]() -> jlongArray {
        auto context = lookup(id); Call call(context);
        {
            std::lock_guard<std::mutex> held(context->mutex);
            if (!context->joined || context->attached || context->worker_alive || !context->transfers.empty()
                    || context->callbacks != 0 || context->calls != 1 || context->refs != 0)
                throw std::runtime_error("context release before join/drain");
        }
        gate_wait(context, context_return);
        std::array<jlong, 18> final_values;
        {
            std::lock_guard<std::mutex> held(context->mutex); final_values = snapshot_locked(*context);
        }
        final_values[4] = 0; final_values[14] = 1; final_values[15] = -1;
        jlongArray result = array(env, final_values);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        {
            std::lock_guard<std::mutex> held(context->mutex); context->released = true; context->borrowed_fd = -1;
        }
        {
            std::lock_guard<std::mutex> held(registry_mutex); registry.erase(id); live_contexts.fetch_sub(1);
        }
        // Call and shared_ptr destructors retire this final native metadata borrow before return.
        return result;
    });
}

JNIEXPORT void JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nSetCancelCompletes
  (JNIEnv* env, jclass, jlong id, jboolean enabled) {
    protect_void(env, [&]() { auto context = lookup(id); std::lock_guard<std::mutex> held(context->mutex); context->cancel_completes = enabled != JNI_FALSE; });
}
JNIEXPORT void JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nArm(JNIEnv* env, jclass, jlong id, jint gate) {
    protect_void(env, [&]() {
        check_gate(gate); auto context = lookup(id); std::lock_guard<std::mutex> held(context->mutex);
        auto& selected = context->gates[static_cast<std::size_t>(gate)]; selected.armed = true; selected.released = false; selected.entries = 0;
    });
}
JNIEXPORT jboolean JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nAwaitGate
  (JNIEnv* env, jclass, jlong id, jint gate, jlong timeout) {
    return protect<jboolean>(env, JNI_FALSE, [&]() -> jboolean {
        check_gate(gate); check_timeout(timeout); auto context = lookup(id); std::unique_lock<std::mutex> held(context->mutex);
        return context->changed.wait_for(held, std::chrono::milliseconds(timeout), [&]() {
            return context->gates[static_cast<std::size_t>(gate)].entries > 0;
        }) ? JNI_TRUE : JNI_FALSE;
    });
}
JNIEXPORT void JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nReleaseGate(JNIEnv* env, jclass, jlong id, jint gate) {
    protect_void(env, [&]() {
        check_gate(gate); auto context = lookup(id); std::lock_guard<std::mutex> held(context->mutex);
        context->gates[static_cast<std::size_t>(gate)].released = true; context->changed.notify_all();
    });
}
JNIEXPORT jboolean JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nAwaitReleaseWaiting
  (JNIEnv* env, jclass, jlong id, jlong timeout) {
    return protect<jboolean>(env, JNI_FALSE, [&]() -> jboolean {
        check_timeout(timeout); auto context = lookup(id); std::unique_lock<std::mutex> held(context->mutex);
        return context->changed.wait_for(held, std::chrono::milliseconds(timeout), [&]() { return context->release_waiters > 0; }) ? JNI_TRUE : JNI_FALSE;
    });
}
JNIEXPORT jlongArray JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nSnapshot(JNIEnv* env, jclass, jlong id) {
    return protect<jlongArray>(env, nullptr, [&]() -> jlongArray {
        auto context = lookup(id); std::array<jlong, 18> values;
        { std::lock_guard<std::mutex> held(context->mutex); values = snapshot_locked(*context); }
        return array(env, values);
    });
}
JNIEXPORT jlongArray JNICALL Java_local_sidecar_copilot_jnihost_JniBackendPort_nGlobalSnapshot(JNIEnv* env, jclass) {
    return array(env, std::array<jlong, 4>{{live_contexts.load(), global_refs.load(), buffer_bytes.load(), attached_threads.load()}});
}
