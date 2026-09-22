package io.zeroyaml.controlplane.execution;

/**
 * Execution fact reported by the Runner process that runs a Job.
 *
 * <p>It is deliberately smaller than the Job lifecycle. There is no queued
 * state, because queueing is a Control Plane decision a Runner never observes,
 * and no cancelled state, because a Runner reports a cancelled or timed-out
 * execution as {@link #FAILED} with the matching
 * {@link JobExecutionFailureReason}.</p>
 */
public enum JobExecutionState {
	RUNNING,
	SUCCEEDED,
	FAILED;

	/**
	 * Indicates whether this observation ends the execution.
	 *
	 * @return {@code true} for a reported outcome
	 */
	public boolean isTerminal() {
		return this != RUNNING;
	}
}
