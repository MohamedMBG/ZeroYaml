package io.zeroyaml.controlplane.domain.job;

/**
 * Raised when a Job is asked to move between states outside the Phase 1
 * lifecycle contract.
 */
public final class InvalidJobTransitionException extends IllegalStateException {

	private final JobStatus currentStatus;
	private final JobStatus requestedStatus;

	public InvalidJobTransitionException(JobStatus currentStatus, JobStatus requestedStatus) {
		super("Job cannot transition from " + currentStatus + " to " + requestedStatus);
		this.currentStatus = currentStatus;
		this.requestedStatus = requestedStatus;
	}

	public JobStatus getCurrentStatus() {
		return currentStatus;
	}

	public JobStatus getRequestedStatus() {
		return requestedStatus;
	}
}
