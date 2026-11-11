/* Compile only, never linked to or called through a libusb/libuvc library.
 * Real public headers supply these declarations. C11 _Generic checks the
 * function-pointer type without evaluating its address or making a call.
 * This is Windows/MSVC public declaration compatibility, not Android ABI or
 * evidence that the wrap/cancel/event/stop implementations work.
 */
#include <sys/time.h>
#include <libusb.h>
#include <libuvc/libuvc.h>

typedef int (LIBUSB_CALL *wrap_signature)(libusb_context *, intptr_t, libusb_device_handle **);
typedef int (LIBUSB_CALL *cancel_signature)(struct libusb_transfer *);
typedef int (LIBUSB_CALL *events_signature)(libusb_context *, struct timeval *, int *);
typedef int (LIBUSB_CALL *control_signature)(libusb_device_handle *, uint8_t, uint8_t,
    uint16_t, uint16_t, unsigned char *, uint16_t, unsigned int);
typedef uvc_error_t (*uvc_wrap_signature)(int, uvc_context_t *, uvc_device_handle_t **);
typedef void (*uvc_stop_signature)(uvc_device_handle_t *);
typedef uvc_error_t (*stream_stop_signature)(uvc_stream_handle_t *);

_Static_assert(_Generic(&libusb_wrap_sys_device, wrap_signature: 1, default: 0), "libusb_wrap_sys_device signature");
_Static_assert(_Generic(&libusb_cancel_transfer, cancel_signature: 1, default: 0), "libusb_cancel_transfer signature");
_Static_assert(_Generic(&libusb_handle_events_timeout_completed, events_signature: 1, default: 0), "libusb events signature");
_Static_assert(_Generic(&libusb_control_transfer, control_signature: 1, default: 0), "libusb control signature");
_Static_assert(_Generic(&uvc_wrap, uvc_wrap_signature: 1, default: 0), "uvc_wrap signature");
_Static_assert(_Generic(&uvc_stop_streaming, uvc_stop_signature: 1, default: 0), "uvc_stop_streaming signature");
_Static_assert(_Generic(&uvc_stream_stop, stream_stop_signature: 1, default: 0), "uvc_stream_stop signature");

int sidecar_probe_public_signature_count(void) { return 7; }
