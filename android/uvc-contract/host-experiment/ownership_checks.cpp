// Self-authored, controlled host backend; NOT libusb/libuvc/JNI or a device driver.
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

using namespace std::chrono_literals;

struct Failure : std::runtime_error { using std::runtime_error::runtime_error; };
#define REQUIRE(x) do { if (!(x)) throw Failure(std::string("CHECK_FAILED: ") + #x); } while (false)

class Event {
    std::mutex mutex_;
    std::condition_variable changed_;
    bool ready_ = false;
public:
    void signal() { std::lock_guard<std::mutex> lock(mutex_); ready_ = true; changed_.notify_all(); }
    bool wait(std::chrono::milliseconds timeout = 3000ms) {
        std::unique_lock<std::mutex> lock(mutex_);
        return changed_.wait_for(lock, timeout, [&] { return ready_; });
    }
};

class Worker {
    Event finished_;
    std::exception_ptr error_;
    std::thread thread_;
public:
    explicit Worker(std::function<void()> body) : thread_([this, body] {
        try { body(); } catch (...) { error_ = std::current_exception(); }
        finished_.signal();
    }) {}
    Worker(const Worker&) = delete;
    ~Worker() { joinWithoutThrow(); }
    void joinWithoutThrow() {
        if (!thread_.joinable()) return;
        if (!finished_.wait(4000ms)) {
            std::cerr << "HARNESS_THREAD_TIMEOUT: no drain claimed\n" << std::flush;
            std::_Exit(70); // Failure of this isolated test process, never a stop receipt.
        }
        thread_.join();
    }
    void join() { joinWithoutThrow(); if (error_) std::rethrow_exception(error_); }
};

struct LedgerSnapshot {
    std::size_t allocated, destroyed, liveBytes, peakBytes, clearedBytes;
};

class Ledger {
    std::mutex mutex_;
    std::map<std::uint64_t, std::size_t> live_;
    std::uint64_t next_ = 1;
    std::size_t allocated_ = 0, destroyed_ = 0, bytes_ = 0, peak_ = 0, cleared_ = 0;
public:
    static constexpr std::size_t BUDGET = 8U * 1024U * 1024U;
    std::uint64_t reserve(std::size_t bytes) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (bytes > BUDGET - bytes_) throw std::bad_alloc();
        const auto id = next_++;
        live_.emplace(id, bytes); ++allocated_; bytes_ += bytes;
        if (bytes_ > peak_) peak_ = bytes_;
        return id;
    }
    void release(std::uint64_t id, bool actualZeroVerified) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = live_.find(id);
        if (it == live_.end() || !actualZeroVerified) std::abort();
        cleared_ += it->second; bytes_ -= it->second;
        live_.erase(it); ++destroyed_;
    }
    void rollbackUnallocated(std::uint64_t id) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = live_.find(id); if (it == live_.end()) std::abort();
        bytes_ -= it->second; live_.erase(it); --allocated_;
    }
    LedgerSnapshot snapshot() {
        std::lock_guard<std::mutex> lock(mutex_);
        return {allocated_, destroyed_, bytes_, peak_, cleared_};
    }
};

class Block {
    Ledger& ledger_;
    const std::size_t size_;
    const std::uint64_t id_;
    std::unique_ptr<unsigned char[]> data_;
public:
    Block(Ledger& ledger, std::size_t size) : ledger_(ledger), size_(size), id_(ledger.reserve(size)) {
        try { data_.reset(new unsigned char[size_]); }
        catch (...) { ledger_.rollbackUnallocated(id_); throw; }
        std::fill_n(data_.get(), size_, static_cast<unsigned char>(0x5a));
    }
    ~Block() {
        volatile unsigned char* p = data_.get();
        for (std::size_t i = 0; i < size_; ++i) p[i] = 0;
        bool zero = true;
        for (std::size_t i = 0; i < size_; ++i) zero = zero && p[i] == 0;
        data_.reset(); // Return the actual heap storage before returning its budget.
        ledger_.release(id_, zero);
    }
    unsigned char* data() const { return data_.get(); }
};

// A real payload read, outside bookkeeping locks. Wrong policies deliberately
// make this an ASan-detected access to freed heap storage in child processes.
static void touch(unsigned char* data) {
    volatile unsigned char value = data[0];
    REQUIRE(value == 0x5a);
}

struct Binding {
    const std::string session, selection, boot, connectionOwnerToken;
    const std::uint64_t attachmentEpoch, permissionRevision, connectionGeneration, runGeneration;
    Binding(std::uint64_t run = 1, std::string owner = "platform-owner")
        : session("session"), selection("explicit-selection"), boot("host-test-clock"),
          connectionOwnerToken(std::move(owner)), attachmentEpoch(1), permissionRevision(1),
          connectionGeneration(1), runGeneration(run) {}
    bool same(const Binding& b) const {
        return session == b.session && selection == b.selection && boot == b.boot
            && connectionOwnerToken == b.connectionOwnerToken && attachmentEpoch == b.attachmentEpoch
            && permissionRevision == b.permissionRevision && connectionGeneration == b.connectionGeneration
            && runGeneration == b.runGeneration;
    }
};

enum class Channel { Image, Status, Control };
enum class Kind { Open, Submit, Cancel, Control };
enum class BackendResult { Success, Busy, NotFound };
struct OperationKey {
    const Binding binding;
    const std::uint64_t operationId, submissionCycle;
    const Kind kind;
    const Channel channel;
};
struct Operation {
    OperationKey key;
    std::unique_ptr<Block> buffer;
    unsigned apiCalls = 0, callbacks = 0;
    bool terminal = false, callbackExited = false;
    Operation(OperationKey value, Ledger& ledger) : key(std::move(value)), buffer(new Block(ledger, 256)) {}
};

struct Snapshot {
    unsigned nativeCalls = 0, callbacks = 0;
    std::array<unsigned, 3> outstanding{};
    std::array<unsigned, 3> terminalPending{}, callbackExitPending{}, transferBuffers{};
    bool publicationRevoked = false, pumpJoined = false, contextReleased = false, ownerClosed = false;
    std::size_t appOwnedBytes = 0;
    bool allComplete() const {
        return publicationRevoked && !nativeCalls && !callbacks
            && outstanding == std::array<unsigned, 3>{0, 0, 0}
            && terminalPending == std::array<unsigned, 3>{0, 0, 0}
            && callbackExitPending == std::array<unsigned, 3>{0, 0, 0}
            && transferBuffers == std::array<unsigned, 3>{0, 0, 0}
            && pumpJoined && contextReleased && ownerClosed && !appOwnedBytes;
    }
};

class Rig {
    std::mutex mutex_;
    std::condition_variable changed_;
    std::unique_ptr<Block> context_;
    std::unique_ptr<Block> platformMemory_;
    std::vector<std::shared_ptr<Operation>> operations_;
    std::unique_ptr<Block> app_;
    std::size_t appBytes_ = 0;
    unsigned calls_ = 0, callbacks_ = 0, ownerCloseCount_ = 0;
    bool stopping_ = false, closing_ = false, pumpJoined_ = false, contextReleased_ = false, ownerClosed_ = false;
    Event pumpStop_;
    Worker pump_;
    void retireLocked(Operation& op) {
        if (op.terminal && op.callbackExited && op.callbacks == 0 && op.apiCalls == 0) op.buffer.reset();
    }
    bool nativeDoneLocked() const {
        if (calls_ || callbacks_) return false;
        for (const auto& op : operations_)
            if (!op->terminal || !op->callbackExited || op->apiCalls || op->callbacks || op->buffer) return false;
        return true;
    }
    bool ownsLocked(const std::shared_ptr<Operation>& op) const {
        if (!op || !op->key.binding.same(binding)) return false;
        return std::find(operations_.begin(), operations_.end(), op) != operations_.end();
    }
    static Ledger& validateFd(Ledger& ledger, int fd) {
        if (fd < 0) throw Failure("Negative FD rejected before resources or threads");
        return ledger;
    }
public:
    Ledger& ledger;
    const Binding binding;
    const int borrowedFd;
    explicit Rig(Ledger& l, int fd = 0, std::uint64_t run = 1)
        : context_(new Block(validateFd(l, fd), 128)), platformMemory_(new Block(l, 64)),
          pump_([this] { REQUIRE(pumpStop_.wait(10000ms)); }), ledger(l), binding(run), borrowedFd(fd) {}
    ~Rig() { pumpStop_.signal(); pump_.joinWithoutThrow(); }
    std::shared_ptr<Operation> create(std::uint64_t id, Channel channel) {
        std::lock_guard<std::mutex> lock(mutex_);
        REQUIRE(!stopping_ && context_ && !ownerClosed_);
        for (const auto& p : operations_) REQUIRE(p->key.operationId != id);
        auto op = std::make_shared<Operation>(OperationKey{binding, id, 1, Kind::Submit, channel}, ledger);
        operations_.push_back(op); return op;
    }
    class Call {
        Rig& rig_;
        std::shared_ptr<Operation> op_;
        unsigned char* data_;
    public:
        Call(Rig& rig, std::shared_ptr<Operation> op, Kind kind) : rig_(rig), op_(std::move(op)), data_(nullptr) {
            std::lock_guard<std::mutex> lock(rig_.mutex_);
            REQUIRE(rig_.context_ && !rig_.ownerClosed_ && !rig_.closing_);
            REQUIRE(!rig_.stopping_ || (kind == Kind::Cancel && op_));
            if (op_) { REQUIRE(rig_.ownsLocked(op_) && op_->buffer); ++op_->apiCalls; data_ = op_->buffer->data(); }
            else data_ = rig_.context_->data();
            ++rig_.calls_;
        }
        Call(const Call&) = delete;
        ~Call() {
            std::lock_guard<std::mutex> lock(rig_.mutex_);
            --rig_.calls_; if (op_) { --op_->apiCalls; rig_.retireLocked(*op_); }
            rig_.changed_.notify_all();
        }
        void use() { touch(data_); }
    };
    class Callback {
        Rig& rig_;
        std::shared_ptr<Operation> op_;
        unsigned char* data_;
    public:
        Callback(Rig& rig, std::shared_ptr<Operation> op) : rig_(rig), op_(std::move(op)), data_(nullptr) {
            std::lock_guard<std::mutex> lock(rig_.mutex_);
            REQUIRE(rig_.ownsLocked(op_) && op_->buffer && !op_->callbackExited && !rig_.closing_);
            data_ = op_->buffer->data(); op_->terminal = true;
            ++op_->callbacks; ++rig_.callbacks_;
        }
        Callback(const Callback&) = delete;
        ~Callback() {
            std::lock_guard<std::mutex> lock(rig_.mutex_);
            --op_->callbacks; --rig_.callbacks_; op_->callbackExited = true;
            rig_.retireLocked(*op_); rig_.changed_.notify_all();
        }
        void use() { touch(data_); }
    };
    Call call(std::shared_ptr<Operation> op = {}, Kind kind = Kind::Submit) { return Call(*this, std::move(op), kind); }
    Callback callback(std::shared_ptr<Operation> op) { return Callback(*this, std::move(op)); }
    void stop() { std::lock_guard<std::mutex> lock(mutex_); stopping_ = true; changed_.notify_all(); }
    bool canResubmit(const OperationKey& key) {
        std::lock_guard<std::mutex> lock(mutex_);
        return !stopping_ && binding.same(key.binding) && context_ && !ownerClosed_;
    }
    void terminal(const std::shared_ptr<Operation>& op) {
        std::lock_guard<std::mutex> lock(mutex_); REQUIRE(ownsLocked(op)); op->terminal = true; retireLocked(*op);
    }
    void submitResult(const std::shared_ptr<Operation>& op, BackendResult result) {
        std::lock_guard<std::mutex> lock(mutex_); REQUIRE(ownsLocked(op));
        REQUIRE(result == BackendResult::Busy && !op->terminal && op->buffer);
        // BUSY is an attempt result; it cannot retire the preceding accepted cycle.
    }
    void cancelResult(const std::shared_ptr<Operation>& op, BackendResult result) {
        std::lock_guard<std::mutex> lock(mutex_); REQUIRE(ownsLocked(op));
        REQUIRE(result == BackendResult::NotFound && op->terminal);
        // NOT_FOUND does not alter callback or call-stack obligations.
    }
    std::shared_ptr<Operation> reserveResubmit(const std::shared_ptr<Operation>& previous) {
        std::lock_guard<std::mutex> lock(mutex_); REQUIRE(ownsLocked(previous));
        if (stopping_ || closing_) return {};
        REQUIRE(previous->key.channel == Channel::Status && previous->callbacks == 1);
        auto next = std::make_shared<Operation>(OperationKey{binding, previous->key.operationId,
            previous->key.submissionCycle + 1, Kind::Submit, Channel::Status}, ledger);
        for (const auto& p : operations_)
            REQUIRE(p->key.operationId != next->key.operationId || p->key.submissionCycle != next->key.submissionCycle);
        operations_.push_back(next); return next;
    }
    bool waitNative(std::chrono::milliseconds wait) {
        std::unique_lock<std::mutex> lock(mutex_);
        return changed_.wait_for(lock, wait, [&] { return nativeDoneLocked(); });
    }
    bool closeNative() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!stopping_ || !nativeDoneLocked()) return false;
            if (ownerClosed_) return true;
            if (closing_) return false; // Only the winning caller may join the native pump.
            closing_ = true; // Seals every borrowing entry under the same lock.
        }
        pumpStop_.signal(); pump_.join();
        std::lock_guard<std::mutex> lock(mutex_);
        pumpJoined_ = true;
        REQUIRE(nativeDoneLocked());
        context_.reset(); contextReleased_ = true;
        platformMemory_.reset(); ownerClosed_ = true; ++ownerCloseCount_;
        return true;
    }
    void ownApp(std::size_t bytes) {
        std::lock_guard<std::mutex> lock(mutex_); REQUIRE(!app_ && !stopping_ && !closing_ && !ownerClosed_);
        app_.reset(new Block(ledger, bytes)); appBytes_ = bytes;
    }
    void clearApp() { std::lock_guard<std::mutex> lock(mutex_); app_.reset(); appBytes_ = 0; }
    unsigned ownerCloseCount() { std::lock_guard<std::mutex> lock(mutex_); return ownerCloseCount_; }
    bool opAlive(const std::shared_ptr<Operation>& op) { std::lock_guard<std::mutex> lock(mutex_); return !!op->buffer; }
    Snapshot snapshot() {
        std::lock_guard<std::mutex> lock(mutex_);
        Snapshot s; s.nativeCalls = calls_; s.callbacks = callbacks_;
        for (const auto& op : operations_) {
            const auto i = static_cast<std::size_t>(op->key.channel);
            if (!op->terminal) ++s.terminalPending[i];
            if (!op->callbackExited) ++s.callbackExitPending[i];
            if (op->buffer) ++s.transferBuffers[i];
            if (!op->terminal || !op->callbackExited || op->apiCalls || op->callbacks || op->buffer) ++s.outstanding[i];
        }
        s.publicationRevoked = stopping_; s.pumpJoined = pumpJoined_; s.contextReleased = contextReleased_;
        s.ownerClosed = ownerClosed_; s.appOwnedBytes = appBytes_; return s;
    }
    // Isolated, opt-in wrong policies. Not exposed as normal backend operations.
    void wrongFreeTransfer(const std::shared_ptr<Operation>& op) { std::lock_guard<std::mutex> lock(mutex_); op->buffer.reset(); }
    void wrongFreeContext() { std::lock_guard<std::mutex> lock(mutex_); context_.reset(); }
};

static void finish(Rig& rig) {
    rig.stop(); REQUIRE(rig.waitNative(100ms)); REQUIRE(rig.closeNative());
    REQUIRE(rig.closeNative()); REQUIRE(rig.ownerCloseCount() == 1); REQUIRE(rig.snapshot().allComplete());
}

static void beforeSubmitReturn(bool bad, Ledger& ledger) {
    Rig rig(ledger); auto op = rig.create(1, Channel::Image);
    Event entered, callbackDone;
    Worker submit([&] { auto call = rig.call(op); entered.signal(); REQUIRE(callbackDone.wait()); call.use(); });
    REQUIRE(entered.wait());
    { auto callback = rig.callback(op); callback.use(); }
    REQUIRE(rig.snapshot().nativeCalls == 1); REQUIRE(rig.opAlive(op));
    if (bad) rig.wrongFreeTransfer(op);
    callbackDone.signal(); submit.join(); finish(rig);
}

static void terminalBeforeCancelReturn(bool bad, Ledger& ledger) {
    Rig rig(ledger); auto op = rig.create(1, Channel::Image); Event cancelEntered, cancelReturn;
    Worker cancel([&] { auto call = rig.call(op, Kind::Cancel); cancelEntered.signal(); REQUIRE(cancelReturn.wait()); call.use(); });
    REQUIRE(cancelEntered.wait());
    { auto callback = rig.callback(op); callback.use(); }
    rig.stop(); REQUIRE(!rig.waitNative(20ms)); REQUIRE(!rig.closeNative());
    REQUIRE(rig.opAlive(op)); if (bad) rig.wrongFreeTransfer(op);
    cancelReturn.signal(); cancel.join(); finish(rig);
}

static void notFound(bool bad, Ledger& ledger) {
    Rig rig(ledger); auto op = rig.create(1, Channel::Image); Event callbackEntered, callbackReturn;
    Worker callback([&] { auto cb = rig.callback(op); callbackEntered.signal(); REQUIRE(callbackReturn.wait()); cb.use(); });
    REQUIRE(callbackEntered.wait());
    { auto cancel = rig.call(op, Kind::Cancel); cancel.use(); rig.cancelResult(op, BackendResult::NotFound); }
    rig.stop(); REQUIRE(rig.snapshot().callbacks == 1); REQUIRE(!rig.closeNative());
    if (bad) rig.wrongFreeTransfer(op);
    callbackReturn.signal(); callback.join(); finish(rig);
}

static void busy(bool bad, Ledger& ledger) {
    Rig rig(ledger); auto op = rig.create(1, Channel::Image); Event allowCallback;
    unsigned char* backendBorrow = op->buffer->data();
    Worker callback([&] { REQUIRE(allowCallback.wait()); touch(backendBorrow); auto cb = rig.callback(op); cb.use(); });
    { auto secondSubmit = rig.call(op); secondSubmit.use(); rig.submitResult(op, BackendResult::Busy); }
    REQUIRE(rig.snapshot().terminalPending[0] == 1);
    REQUIRE(rig.opAlive(op)); if (bad) rig.wrongFreeTransfer(op);
    allowCallback.signal(); callback.join(); finish(rig);
}

static void controlTimeout(bool bad, Ledger& ledger) {
    Rig rig(ledger); auto op = rig.create(1, Channel::Control); Event entered, returnControl;
    Worker control([&] {
        auto call = rig.call({}, Kind::Control); entered.signal(); REQUIRE(returnControl.wait()); call.use();
        { auto callback = rig.callback(op); callback.use(); }
    });
    REQUIRE(entered.wait()); rig.stop();
    REQUIRE(!rig.waitNative(20ms)); REQUIRE(!rig.closeNative());
    REQUIRE(rig.snapshot().nativeCalls == 1 && rig.snapshot().outstanding[2] == 1);
    if (bad) rig.wrongFreeContext();
    returnControl.signal(); control.join(); finish(rig);
}

static void statusResubmit(bool bad, Ledger& ledger) {
    Rig rig(ledger); auto op = rig.create(1, Channel::Status); Event readyToResubmit, resume;
    Worker status([&] {
        auto callback = rig.callback(op); readyToResubmit.signal(); REQUIRE(resume.wait());
        REQUIRE(!rig.reserveResubmit(op)); callback.use();
    });
    REQUIRE(readyToResubmit.wait()); rig.stop();
    REQUIRE(rig.snapshot().outstanding[0] == 0 && rig.snapshot().outstanding[1] == 1);
    REQUIRE(!rig.closeNative()); if (bad) rig.wrongFreeTransfer(op);
    resume.signal(); status.join(); finish(rig);
}

static void statusAlreadyAdmitted(bool bad, Ledger& ledger) {
    Rig rig(ledger); auto old = rig.create(1, Channel::Status);
    std::shared_ptr<Operation> next; Event admitted, returnOld;
    Worker status([&] {
        auto callback = rig.callback(old);
        next = rig.reserveResubmit(old); REQUIRE(next); admitted.signal();
        REQUIRE(returnOld.wait()); callback.use();
    });
    REQUIRE(admitted.wait()); rig.stop();
    REQUIRE(next->key.submissionCycle == 2 && rig.snapshot().outstanding[1] == 2);
    REQUIRE(!rig.closeNative()); returnOld.signal(); status.join();
    REQUIRE(rig.snapshot().outstanding[1] == 1);
    unsigned char* backendBorrow = next->buffer->data();
    if (bad) rig.wrongFreeTransfer(next);
    Worker completion([&] { touch(backendBorrow); auto callback = rig.callback(next); callback.use(); });
    completion.join(); finish(rig);
}

static void missingCallback(bool bad, Ledger& ledger) {
    Rig rig(ledger); auto op = rig.create(1, Channel::Image); unsigned char* backendBorrow = op->buffer->data();
    rig.terminal(op); rig.stop();
    REQUIRE(!rig.waitNative(20ms)); REQUIRE(!rig.closeNative()); REQUIRE(rig.opAlive(op));
    REQUIRE(ledger.snapshot().liveBytes == 448);
    if (bad) rig.wrongFreeTransfer(op);
    // Explicit test teardown delivery; it does not retrospectively confirm the timed-out observation.
    Worker lateCompletion([&] { touch(backendBorrow); auto cb = rig.callback(op); cb.use(); });
    lateCompletion.join(); finish(rig);
}

static void lateOpen(bool bad, Ledger& ledger) {
    Rig rig(ledger); Event openEntered, openReturn; std::atomic<bool> latePublished{false};
    Worker open([&] {
        auto call = rig.call({}, Kind::Open); openEntered.signal(); REQUIRE(openReturn.wait()); call.use();
        Block lateHandle(ledger, 96); touch(lateHandle.data());
        const OperationKey key{rig.binding, 1, 1, Kind::Open, Channel::Control};
        latePublished.store(rig.canResubmit(key));
        // Returned handle is destroyed by its original owner on this same stack.
    });
    REQUIRE(openEntered.wait()); rig.stop(); REQUIRE(!rig.closeNative());
    if (bad) rig.wrongFreeContext();
    openReturn.signal(); open.join(); REQUIRE(!latePublished.load()); finish(rig);
}

static void fdZero(bool bad, Ledger& ledger) {
    Rig rig(ledger, 0); REQUIRE(rig.borrowedFd == 0);
    const bool wrongFdValid = rig.borrowedFd > 0;
    finish(rig);
    if (bad) REQUIRE(wrongFdValid); // Old FD > 0 policy is rejected with CHECK_FAILED.
}

static void appBuffer(bool bad, Ledger& ledger) {
    Rig rig(ledger); rig.ownApp(640U * 360U * 4U); rig.stop(); REQUIRE(rig.closeNative());
    const auto partial = rig.snapshot();
    REQUIRE(partial.contextReleased && partial.ownerClosed && partial.pumpJoined);
    REQUIRE(!partial.allComplete()); REQUIRE(ledger.snapshot().liveBytes == 640U * 360U * 4U);
    const bool falseReceipt = bad && partial.contextReleased && partial.ownerClosed;
    rig.clearApp(); REQUIRE(rig.snapshot().allComplete());
    REQUIRE(!falseReceipt); // Native-only stop cannot become a composite stop receipt.
}

static void staleBinding(bool bad, Ledger& ledger) {
    Rig rig(ledger, 0, 2); const Binding oldBinding(1);
    const OperationKey stale{oldBinding, 1, 1, Kind::Cancel, Channel::Image};
    REQUIRE(!rig.canResubmit(stale));
    const bool wrongFdIdentity = rig.borrowedFd == 0; // Same integer must not authorize an old generation.
    finish(rig); if (bad) REQUIRE(!wrongFdIdentity);
}

static void budget(bool bad, Ledger& ledger) {
    (void)bad;
    Rig rig(ledger); bool rejected = false;
    try { rig.ownApp(Ledger::BUDGET); } catch (const std::bad_alloc&) { rejected = true; }
    REQUIRE(rejected); REQUIRE(ledger.snapshot().liveBytes == 192);
    rig.ownApp(640U * 360U * 4U); rig.clearApp(); finish(rig);
}

template<class F> static bool rejected(F fn) {
    try { fn(); return false; } catch (const Failure&) { return true; }
}

static void negativeFd(bool bad, Ledger& ledger) {
    (void)bad;
    REQUIRE(rejected([&] { Rig invalid(ledger, -1); }));
    REQUIRE(ledger.snapshot().allocated == 0 && ledger.snapshot().liveBytes == 0);
    Rig valid(ledger, 0); finish(valid);
}

static void bindingEntrypoints(bool bad, Ledger& ledger) {
    (void)bad;
    Rig first(ledger, 0, 1), second(ledger, 0, 2); auto foreign = first.create(1, Channel::Image);
    REQUIRE(rejected([&] { auto call = second.call(foreign); call.use(); }));
    REQUIRE(rejected([&] { auto callback = second.callback(foreign); callback.use(); }));
    REQUIRE(rejected([&] { second.terminal(foreign); }));
    REQUIRE(rejected([&] { second.reserveResubmit(foreign); }));
    REQUIRE(first.opAlive(foreign) && second.snapshot().nativeCalls == 0);
    { auto callback = first.callback(foreign); callback.use(); }
    finish(first); finish(second);
}

static void stoppingBorrowAdmission(bool bad, Ledger& ledger) {
    (void)bad;
    Rig rig(ledger); auto op = rig.create(1, Channel::Image); rig.stop();
    REQUIRE(rejected([&] { auto call = rig.call({}, Kind::Open); call.use(); }));
    REQUIRE(rejected([&] { auto call = rig.call({}, Kind::Control); call.use(); }));
    REQUIRE(rejected([&] { auto call = rig.call(op, Kind::Submit); call.use(); }));
    REQUIRE(rejected([&] { rig.create(2, Channel::Image); }));
    REQUIRE(rejected([&] { rig.ownApp(256); }));
    { auto cancel = rig.call(op, Kind::Cancel); cancel.use(); } // Only tracked cleanup may borrow after stop.
    { auto callback = rig.callback(op); callback.use(); }
    finish(rig);
    REQUIRE(rejected([&] { auto call = rig.call(op, Kind::Cancel); call.use(); }));
    REQUIRE(rejected([&] { rig.ownApp(256); }));
}

static void concurrentClose(bool bad, Ledger& ledger) {
    (void)bad;
    Rig rig(ledger); rig.stop(); Event start; std::vector<std::unique_ptr<Worker>> closers;
    for (unsigned i = 0; i < 8; ++i) closers.emplace_back(new Worker([&] {
        REQUIRE(start.wait()); (void)rig.closeNative();
    }));
    start.signal(); for (auto& closer : closers) closer->join();
    finish(rig); REQUIRE(rig.ownerCloseCount() == 1);
}

int main(int argc, char** argv) {
    if (argc != 3) return 64;
    const std::string mode = argv[1], name = argv[2];
    if (mode != "positive" && mode != "negative") return 64;
    const bool bad = mode == "negative";
    const std::map<std::string, std::function<void(bool, Ledger&)>> scenarios{
        {"callback_before_submit_return", beforeSubmitReturn},
        {"terminal_before_cancel_return", terminalBeforeCancelReturn},
        {"cancel_not_found", notFound}, {"submit_busy", busy},
        {"control_outer_timeout", controlTimeout}, {"status_resubmit_stop", statusResubmit},
        {"status_resubmit_admitted", statusAlreadyAdmitted},
        {"missing_callback", missingCallback}, {"late_open", lateOpen},
        {"fd_zero", fdZero}, {"app_buffer_stop", appBuffer}, {"stale_binding", staleBinding}, {"byte_budget", budget},
        {"negative_fd", negativeFd}, {"binding_entrypoints", bindingEntrypoints},
        {"stopping_borrow_admission", stoppingBorrowAdmission}, {"concurrent_close", concurrentClose}
    };
    auto found = scenarios.find(name); if (found == scenarios.end()) return 64;
    Ledger ledger;
    try {
        if (bad) std::cout << "EXPECTED_FAULT_INJECTION scenario=" << name << '\n' << std::flush;
        found->second(bad, ledger);
        const auto stats = ledger.snapshot();
        REQUIRE(stats.allocated == stats.destroyed && stats.liveBytes == 0);
        REQUIRE(stats.peakBytes <= Ledger::BUDGET && stats.clearedBytes > 0);
        if (bad) throw Failure("NEGATIVE_POLICY_NOT_DETECTED");
        std::cout << "OWNERSHIP_PASS scenario=" << name << " allocated=" << stats.allocated
                  << " destroyed=" << stats.destroyed << " live_bytes=" << stats.liveBytes
                  << " peak_bytes=" << stats.peakBytes << " cleared_bytes=" << stats.clearedBytes << '\n';
        return 0;
    } catch (const Failure& error) {
        std::cerr << error.what() << '\n'; return 2;
    } catch (const std::exception& error) {
        std::cerr << "UNEXPECTED_EXCEPTION: " << error.what() << '\n'; return 3;
    }
}
