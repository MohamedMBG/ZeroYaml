package io.zeroyaml.controlplane.execution;

import java.util.Objects;
import java.util.Optional;

import io.zeroyaml.controlplane.domain.job.JobStatus;

/**
 * The result of reconciling one {@link JobStatusReport}.
 *
 * <p>{@code jobStatus} is the authoritative Job state after reconciliation. It
 * is {@code null} only for {@link JobStatusReportDecision#UNKNOWN_JOB}, where
 * there is no Job to report a state for.</p>
 *
 * @param decision how the report was reconciled
 * @param jobStatus authoritative Job state afterwards, or {@code null} for an unknown Job
 * @param message operator-facing diagnostic without credentials or payloads
 */
public record JobStatusReportOutcome(JobStatusReportDecision decision, JobStatus jobStatus, String message) {

	public JobStatusReportOutcome {
		Objects.requireNonNull(decision, "decision must not be null");
		Objects.requireNonNull(message, "message must not be null");
		if (decision != JobStatusReportDecision.UNKNOWN_JOB) {
			Objects.requireNonNull(jobStatus, "jobStatus must not be null unless the Job is unknown");
		}
	}

	static JobStatusReportOutcome applied(JobStatus jobStatus, String message) {
		return new JobStatusReportOutcome(JobStatusReportDecision.APPLIED, jobStatus, message);
	}

	static JobStatusReportOutcome duplicate(JobStatus jobStatus, String message) {
		return new JobStatusReportOutcome(JobStatusReportDecision.DUPLICATE, jobStatus, message);
	}

	static JobStatusReportOutcome conflict(JobStatus jobStatus, String message) {
		return new JobStatusReportOutcome(JobStatusReportDecision.CONFLICT, jobStatus, message);
	}

	static JobStatusReportOutcome unknownJob(String message) {
		return new JobStatusReportOutcome(JobStatusReportDecision.UNKNOWN_JOB, null, message);
	}

	/**
	 * @return the authoritative Job state, which is absent only for an unknown Job
	 */
	public Optional<JobStatus> status() {
		return Optional.ofNullable(jobStatus);
	}

	/**
	 * @return {@code true} when the report changed the authoritative Job state
	 */
	public boolean isApplied() {
		return decision == JobStatusReportDecision.APPLIED;
	}
}
