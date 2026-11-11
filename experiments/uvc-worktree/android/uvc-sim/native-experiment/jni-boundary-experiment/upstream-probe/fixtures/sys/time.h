/* Project-owned Windows host fixture, NOT a POSIX/Android ABI implementation.
 * The actual public libuvc header unconditionally includes <sys/time.h>.
 * Use installed Windows SDK/UCRT declarations, without emulating a clock.
 * No sockets or time functions are called by the control probe.
 */
#ifndef SIDECAR_PROBE_SYS_TIME_H
#define SIDECAR_PROBE_SYS_TIME_H
#include <winsock2.h>
#include <time.h>
#endif
