package io.zeroyaml.controlplane.domain.job;

/**
 * Minimal lifecycle states owned by the Control Plane.
 */
public enum JobStatus {
	CREATED,
	QUEUED,
	RUNNING,
	SUCCEEDED,
	FAILED,
	CANCELLED;

	/**
	 * Indicates whether this state ends the Job lifecycle.
	 *
	 * @return {@code true} for terminal states
	 */
	public boolean isTerminal() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED;
	}
}
