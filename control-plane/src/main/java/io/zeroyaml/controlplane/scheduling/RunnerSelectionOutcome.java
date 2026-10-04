package io.zeroyaml.controlplane.scheduling;

import java.util.Objects;
import java.util.Optional;

import io.zeroyaml.controlplane.domain.job.JobId;

/**
 * Result of one attempt to choose a Runner for a Job. A selected outcome carries
 * the {@link JobDispatchContext}; a blocked outcome carries none and leaves the
 * Job queued, so "no Runner available" is an explicit result rather than an
 * exception or a silent drop.
 */
public record RunnerSelectionOutcome(
		JobId jobId,
		RunnerSelectionDecision decision,
		JobDispatchContext dispatchContext,
		String message
) {

	public RunnerSelectionOutcome {
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(decision, "decision must not be null");
		Objects.requireNonNull(message, "message must not be null");
		if (decision == RunnerSelectionDecision.SELECTED) {
			Objects.requireNonNull(dispatchContext, "dispatchContext must not be null for a selected outcome");
			if (!dispatchContext.jobId().equals(jobId)) {
				throw new IllegalArgumentException("dispatchContext must belong to the same Job");
			}
		} else if (dispatchContext != null) {
			throw new IllegalArgumentException("a blocked outcome must not carry a dispatch context");
		}
	}

	/** Creates the outcome of a Job that has a Runner to be dispatched to. */
	public static RunnerSelectionOutcome selected(JobDispatchContext dispatchContext, String message) {
		Objects.requireNonNull(dispatchContext, "dispatchContext must not be null");
		return new RunnerSelectionOutcome(
				dispatchContext.jobId(), RunnerSelectionDecision.SELECTED, dispatchContext, message);
	}

	/** Creates the outcome of a Job that stays queued because no Runner is healthy. */
	public static RunnerSelectionOutcome blockedNoHealthyRunner(JobId jobId, String message) {
		return new RunnerSelectionOutcome(jobId, RunnerSelectionDecision.BLOCKED_NO_HEALTHY_RUNNER, null, message);
	}

	public boolean isSelected() {
		return decision == RunnerSelectionDecision.SELECTED;
	}

	/**
	 * @return the dispatch context, which is present exactly when a Runner was selected
	 */
	public Optional<JobDispatchContext> selectedDispatch() {
		return Optional.ofNullable(dispatchContext);
	}
}
