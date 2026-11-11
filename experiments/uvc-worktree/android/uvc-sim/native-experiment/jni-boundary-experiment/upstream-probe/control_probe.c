/* Project-owned executable harness for UNMODIFIED upstream ctrl-gen.c.
 * There is no real libusb link, USB access, worker/event thread, or JNI here.
 * Only libusb_control_transfer and descriptor lookup are substituted.
 * Expectations are scripted literals, independently checked at the call site.
 */
#include <sys/time.h>
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <libuvc/libuvc_internal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { CAPACITY = 16 };
static unsigned int checks, cases, calls, timeout_zero_calls;
static int native_handle_identity;
static struct sidecar_probe_device_info info = {{9}};
static uvc_device_handle_t device;
static uvc_input_terminal_t camera;
static uvc_processing_unit_t processing;
static uvc_selector_unit_t selector;

struct script {
    const char *name;
    int active, result;
    unsigned int request_type, request, value, index, length;
    unsigned char bytes[CAPACITY];
};
static struct script expected;

static void check(int truth, const char *name) {
    ++checks;
    if (!truth) {
        fprintf(stderr, "PROBE_ASSERTION_FAILED case=%s check=%s\n",
            expected.name == NULL ? "setup" : expected.name, name);
        exit(1);
    }
}

static void plan(const char *name, unsigned int type, unsigned int request,
                 unsigned int value, unsigned int index, unsigned int length,
                 int result, const unsigned char *bytes) {
    check(!expected.active, "previous_call_consumed");
    check(length <= CAPACITY, "script_capacity");
    memset(&expected, 0, sizeof(expected));
    expected.name = name; expected.active = 1; expected.result = result;
    expected.request_type = type; expected.request = request;
    expected.value = value; expected.index = index; expected.length = length;
    if (bytes != NULL) memcpy(expected.bytes, bytes, length);
}

static void finish(void) {
    check(!expected.active, "exactly_one_control_call");
    ++cases;
    printf("PASS %s\n", expected.name);
}

/* The definitions are checked against the actual public declaration, including
 * LIBUSB_CALL. An opaque pointer is compared only, never dereferenced. */
int LIBUSB_CALL libusb_control_transfer(libusb_device_handle *handle,
    uint8_t request_type, uint8_t request, uint16_t value, uint16_t index,
    unsigned char *data, uint16_t length, unsigned int timeout) {
    unsigned int copied;
    check(expected.active, "scripted_control_only");
    check(handle == device.usb_devh, "same_opaque_handle");
    check(request_type == expected.request_type, "scripted_request_type");
    check(request == expected.request, "scripted_request_code");
    check(value == expected.value, "scripted_control_selector");
    check(index == expected.index, "scripted_terminal_and_interface");
    check(length == expected.length, "scripted_payload_length");
    check(data != NULL, "non_null_payload");
    check(timeout == 0, "upstream_passes_zero_timeout");
    ++calls; ++timeout_zero_calls;
    if ((request_type & 0x80) != 0) {
        copied = expected.result > 0 ? (unsigned int)expected.result : 0;
        check(copied <= length, "fake_never_writes_beyond_reported_capacity");
        if (copied != 0) memcpy(data, expected.bytes, copied);
    } else {
        check(memcmp(data, expected.bytes, length) == 0, "encoded_set_bytes");
    }
    expected.active = 0;
    return expected.result;
}

const uvc_input_terminal_t *uvc_get_camera_terminal(uvc_device_handle_t *handle) {
    check(handle == &device, "camera_lookup_same_fixture"); return &camera;
}
const uvc_processing_unit_t *uvc_get_processing_units(uvc_device_handle_t *handle) {
    check(handle == &device, "processing_lookup_same_fixture"); return &processing;
}
const uvc_selector_unit_t *uvc_get_selector_units(uvc_device_handle_t *handle) {
    check(handle == &device, "selector_lookup_same_fixture"); return &selector;
}

static void encoding_cases(void) {
    const unsigned char exposure[] = {0x78, 0x56, 0x34, 0x12};
    const unsigned char signed_get[] = {0xfe, 0xff};
    const unsigned char signed_set[] = {0x2e, 0xfb};
    const unsigned char input[] = {4};
    uint32_t time = 0;
    int16_t brightness = 0;
    uint8_t selected = 0;
    plan("camera_get_u32_le", 0xa1, 0x81, 0x0400, 0x0309, 4, 4, exposure);
    check(uvc_get_exposure_abs(&device, &time, UVC_GET_CUR) == UVC_SUCCESS, "full_get_status");
    check(time == UINT32_C(0x12345678), "decoded_exposure"); finish();
    plan("camera_set_u32_le", 0x21, 0x01, 0x0400, 0x0309, 4, 4, exposure);
    check(uvc_set_exposure_abs(&device, UINT32_C(0x12345678)) == UVC_SUCCESS, "full_set_status"); finish();
    plan("processing_get_signed16", 0xa1, 0x81, 0x0200, 0x0b09, 2, 2, signed_get);
    check(uvc_get_brightness(&device, &brightness, UVC_GET_CUR) == UVC_SUCCESS, "signed_get_status");
    check(brightness == -2, "decoded_negative_brightness_on_msvc"); finish();
    plan("processing_set_signed16", 0x21, 0x01, 0x0200, 0x0b09, 2, 2, signed_set);
    check(uvc_set_brightness(&device, -1234) == UVC_SUCCESS, "signed_set_status"); finish();
    plan("selector_get_forwarded_request", 0xa1, 0x83, 0x0100, 0x0709, 1, 1, input);
    check(uvc_get_input_select(&device, &selected, UVC_GET_MAX) == UVC_SUCCESS, "selector_get_status");
    check(selected == 4, "decoded_selector"); finish();
    plan("selector_set", 0x21, 0x01, 0x0100, 0x0709, 1, 1, input);
    check(uvc_set_input_select(&device, 4) == UVC_SUCCESS, "selector_set_status"); finish();
}

static void return_cases(void) {
    const int errors[] = {-7, -4, -9};
    const char *names[] = {"get_timeout_error", "get_no_device_error", "get_pipe_error"};
    const unsigned char bytes[] = {0x78, 0x56, 0x34, 0x12};
    const uint32_t sentinel = UINT32_C(0xdeadbeef);
    uint32_t time;
    int returned;
    unsigned int i;
    for (i = 0; i < 3; ++i) {
        time = sentinel;
        plan(names[i], 0xa1, 0x81, 0x0400, 0x0309, 4, errors[i], bytes);
        returned = uvc_get_exposure_abs(&device, &time, UVC_GET_CUR);
        check(returned == errors[i], "negative_error_unchanged");
        check(time == sentinel, "error_does_not_update_output"); finish();
    }
    time = sentinel;
    plan("get_positive_short_read", 0xa1, 0x81, 0x0400, 0x0309, 4, 2, bytes);
    check(uvc_get_exposure_abs(&device, &time, UVC_GET_CUR) == 2, "positive_short_return_unchanged");
    check(time == sentinel, "short_get_does_not_update_output"); finish();
    time = sentinel;
    plan("get_zero_short_read_success_alias", 0xa1, 0x81, 0x0400, 0x0309, 4, 0, bytes);
    returned = uvc_get_exposure_abs(&device, &time, UVC_GET_CUR);
    check(returned == UVC_SUCCESS, "observed_zero_aliases_success");
    check(time == sentinel, "zero_get_output_still_sentinel"); finish();
    puts("OBSERVED GET_ZERO_RETURNS_SUCCESS_WITH_UNCHANGED_OUTPUT");
    plan("set_positive_short_write", 0x21, 0x01, 0x0400, 0x0309, 4, 2, bytes);
    check(uvc_set_exposure_abs(&device, UINT32_C(0x12345678)) == 2, "short_set_return_unchanged"); finish();
    plan("set_zero_short_write_success_alias", 0x21, 0x01, 0x0400, 0x0309, 4, 0, bytes);
    check(uvc_set_exposure_abs(&device, UINT32_C(0x12345678)) == UVC_SUCCESS, "observed_zero_set_aliases_success"); finish();
    puts("OBSERVED SET_ZERO_RETURNS_SUCCESS_WITHOUT_FULL_WRITE");
    plan("set_negative_io_error", 0x21, 0x01, 0x0400, 0x0309, 4, -1, bytes);
    check(uvc_set_exposure_abs(&device, UINT32_C(0x12345678)) == -1, "negative_set_error_unchanged"); finish();
}

int main(int argc, char **argv) {
    device.usb_devh = (libusb_device_handle *)&native_handle_identity;
    device.info = &info;
    camera.bTerminalID = 3; processing.bUnitID = 11; selector.bUnitID = 7;
    if (argc == 2 && strcmp(argv[1], "--negative-request") == 0) {
        uint32_t time = 0;
        plan("negative_request_oracle", 0xa1, 0x82, 0x0400, 0x0309, 4, 4, NULL);
        (void)uvc_get_exposure_abs(&device, &time, UVC_GET_CUR);
        fputs("NEGATIVE_CONTROL_NOT_DETECTED\n", stderr); return 2;
    }
    check(argc == 1, "recognized_arguments");
    encoding_cases(); return_cases();
    check(cases == 14 && calls == 14, "fixed_case_and_call_count");
    check(timeout_zero_calls == calls, "all_exercised_calls_zero_timeout");
    printf("UPSTREAM_CONTROL_PROBE_OK cases=%u checks=%u calls=%u timeout_zero_calls=%u\n",
        cases, checks, calls, timeout_zero_calls);
    return 0;
}
