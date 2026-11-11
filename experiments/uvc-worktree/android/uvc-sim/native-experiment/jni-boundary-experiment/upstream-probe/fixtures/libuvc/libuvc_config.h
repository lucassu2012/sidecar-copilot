/* Project-owned probe configuration for the fixed v0.0.9 public header.
 * Not CMake output, not a discovered dependency or enabled JPEG backend.
 */
#ifndef LIBUVC_CONFIG_H
#define LIBUVC_CONFIG_H
#define LIBUVC_VERSION_MAJOR 0
#define LIBUVC_VERSION_MINOR 0
#define LIBUVC_VERSION_PATCH 9
#define LIBUVC_VERSION_STR "0.0.9"
#define LIBUVC_VERSION_INT 9
#define LIBUVC_VERSION_GTE(major, minor, patch) \
    (LIBUVC_VERSION_INT >= (((major) << 16) | ((minor) << 8) | (patch)))
/* LIBUVC_HAS_JPEG is intentionally not defined. */
#endif
