package io.zeroyaml.controlplane.execution;

/**
 * Why an execution reported by a Runner did not succeed. The value becomes the
 * stable failure code retained on the Job, so operators can group failures
 * without parsing a diagnostic message.
 */
public enum JobExecutionFailureReason {

	/** The command ran to completion and exited with a non-zero code. */
	NON_ZERO_EXIT,

	/** The Runner stopped the execution because it exceeded its time budget. */
	TIMEOUT,

	/** The execution was stopped before it finished, for example during Runner shutdown. */
	CANCELLED,

	/** The Runner could not execute the Job at all, for example because the workspace failed to prepare. */
	EXECUTION_ERROR,

	/**
	 * The Runner reported a reason this Control Plane version does not know.
	 * Reasons evolve additively, so an unrecognized value is still recorded as
	 * a failure: refusing the report would leave the Job without an outcome,
	 * which is worse than an uncategorized failure.
	 */
	UNKNOWN
}
