/* PROJECT-ASSEMBLED CONTROL-PROBE FIXTURE. NOT THE UPSTREAM INTERNAL ABI.
 * Deliberately shadows only libuvc_internal.h for an isolated executable.
 * Never place this directory on a production include path or exchange these
 * structures with a real libuvc library. No pthread, device, stream, event,
 * reference-count, status-transfer, or ownership behavior is implemented.
 *
 * The four endian macros and selector constant below are exact excerpts of
 * the fixed upstream internal header (448d78b9), under libuvc's BSD license.
 * The original header remains in source-evidence/cache; the full versioned
 * notice is source-evidence/licenses/libuvc-LICENSE.txt (at experiment root).
 * Other declarations below are project-owned minimal member-access fixtures.
 */
#ifndef SIDECAR_PROBE_LIBUVC_INTERNAL_H
#define SIDECAR_PROBE_LIBUVC_INTERNAL_H
#include <libusb.h>
#include <libuvc/libuvc.h>

#define DW_TO_UINT32(p) ((uint32_t)(p)[0] | ((uint32_t)(p)[1] << 8) | \
                         ((uint32_t)(p)[2] << 16) | ((uint32_t)(p)[3] << 24))
#define SW_TO_SHORT(p) ((uint16_t)((p)[0] | ((p)[1] << 8)))
#define SHORT_TO_SW(s, p) \
  (p)[0] = (s); \
  (p)[1] = (s) >> 8;
#define INT_TO_DW(i, p) \
  (p)[0] = (i); \
  (p)[1] = (i) >> 8; \
  (p)[2] = (i) >> 16; \
  (p)[3] = (i) >> 24;
#define UVC_SU_INPUT_SELECT_CONTROL 0x01

struct sidecar_probe_control_interface {
    uint8_t bInterfaceNumber;
};
struct sidecar_probe_device_info {
    struct sidecar_probe_control_interface ctrl_if;
};
struct uvc_device_handle {
    libusb_device_handle *usb_devh;
    struct sidecar_probe_device_info *info;
};
#endif
