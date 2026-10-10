package io.zeroyaml.controlplane.runner;

/**
 * Why a Runner declined a dispatched Job.
 */
public enum JobRejectionReason {

	/** The Runner does not implement the protocol version used by this Control Plane. */
	UNSUPPORTED_PROTOCOL_VERSION,

	/** The Runner is not accepting work, so the Job may be dispatched elsewhere. */
	RUNNER_UNAVAILABLE,

	/**
	 * The dispatch reached a Runner process other than the one the Control
	 * Plane selected. The answering process did not start the Job.
	 */
	NOT_TARGET_RUNNER,

	/**
	 * The Runner reported a reason this Control Plane version does not know.
	 * Rejection reasons evolve additively, so an unknown value must be treated
	 * as a refusal rather than as an acceptance.
	 */
	UNKNOWN
}
