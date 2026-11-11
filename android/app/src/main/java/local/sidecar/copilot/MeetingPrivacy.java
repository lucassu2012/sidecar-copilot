package local.sidecar.copilot;

/** Process-local ordering for durable deletion against key creation, read and frame commit.
 * Store ownership is single-process; persisted tombstones remain authoritative after restart.
 */
final class MeetingPrivacy {static final Object LOCK=new Object();private MeetingPrivacy(){}}
