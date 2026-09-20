package io.zeroyaml.controlplane.runner;

import java.util.Objects;
import java.util.Optional;

import io.zeroyaml.controlplane.domain.job.JobId;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;

/**
 * Acknowledgment returned by a Runner for one dispatched Job. It reports
 * whether the Runner took responsibility for the Job; it carries no execution
 * progress, command output, or exit status.
 *
 * <p>An accepted dispatch names the Runner process that answered, which is the
 * linkage the Job aggregate records when it starts. A rejected dispatch names
 * the reason instead, so a caller can decide between retrying elsewhere and
 * failing the Job.</p>
 */
public record JobDispatchResult(
		JobId jobId,
		JobDispatchOutcome outcome,
		RunnerAssignment assignment,
		JobRejectionReason rejectionReason,
		String message
) {

	public JobDispatchResult {
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(outcome, "outcome must not be null");
		Objects.requireNonNull(message, "message must not be null");
		if (outcome == JobDispatchOutcome.ACCEPTED) {
			Objects.requireNonNull(assignment, "assignment must not be null for an accepted dispatch");
			if (rejectionReason != null) {
				throw new IllegalArgumentException("an accepted dispatch must not carry a rejection reason");
			}
		} else {
			Objects.requireNonNull(rejectionReason, "rejectionReason must not be null for a rejected dispatch");
		}
	}

	/**
	 * Creates the result of a Job the Runner took responsibility for.
	 *
	 * @param jobId dispatched Job identity
	 * @param assignment Runner process that accepted the Job
	 * @param message operator-facing diagnostic reported by the Runner
	 * @return an accepted dispatch result
	 */
	public static JobDispatchResult accepted(JobId jobId, RunnerAssignment assignment, String message) {
		return new JobDispatchResult(jobId, JobDispatchOutcome.ACCEPTED, assignment, null, message);
	}

	/**
	 * Creates the result of a Job the Runner declined.
	 *
	 * @param jobId dispatched Job identity
	 * @param rejectionReason why the Runner declined the Job
	 * @param assignment Runner process that answered, or {@code null} when it did not identify itself
	 * @param message operator-facing diagnostic reported by the Runner
	 * @return a rejected dispatch result
	 */
	public static JobDispatchResult rejected(
			JobId jobId,
			JobRejectionReason rejectionReason,
			RunnerAssignment assignment,
			String message
	) {
		return new JobDispatchResult(jobId, JobDispatchOutcome.REJECTED, assignment, rejectionReason, message);
	}

	public boolean isAccepted() {
		return outcome == JobDispatchOutcome.ACCEPTED;
	}

	/**
	 * @return the Runner process that answered, which is always present for an accepted dispatch
	 */
	public Optional<RunnerAssignment> runnerAssignment() {
		return Optional.ofNullable(assignment);
	}

	/**
	 * @return the rejection reason, which is present exactly when the dispatch was rejected
	 */
	public Optional<JobRejectionReason> rejection() {
		return Optional.ofNullable(rejectionReason);
	}
}
