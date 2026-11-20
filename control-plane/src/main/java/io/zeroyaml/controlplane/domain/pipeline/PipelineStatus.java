package io.zeroyaml.controlplane.domain.pipeline;

import java.util.Objects;

/**
 * Lifecycle states of a pipeline run, as defined by execution model v1.
 *
 * <p>A run is {@code PENDING} until the Job of one of its steps starts, and
 * {@code RUNNING} from then until it ends. {@code CANCELLED} mirrors the terminal
 * Job state of the same name, so a step whose Job was cancelled has a pipeline
 * state without a fabricated failure.</p>
 */
public enum PipelineStatus {
	PENDING,
	RUNNING,
	SUCCEEDED,
	FAILED,
	CANCELLED;

	/**
	 * Indicates whether this state ends the pipeline run.
	 *
	 * @return {@code true} for terminal states
	 */
	public boolean isTerminal() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED;
	}

	/**
	 * Indicates whether a pipeline run in this state may move to {@code target}.
	 *
	 * <p>{@code SUCCEEDED} and {@code FAILED} are reachable only from
	 * {@code RUNNING}, because both require a step whose Job started; this mirrors
	 * the Job lifecycle. A run that has not started may still be cancelled, for
	 * example when its first Job is cancelled before it runs. A terminal state
	 * allows no transition, including to itself.</p>
	 *
	 * @param target requested next state
	 * @return {@code true} when the transition is allowed
	 */
	public boolean canTransitionTo(PipelineStatus target) {
		Objects.requireNonNull(target, "target must not be null");
		return switch (this) {
			case PENDING -> target == RUNNING || target == CANCELLED;
			case RUNNING -> target.isTerminal();
			case SUCCEEDED, FAILED, CANCELLED -> false;
		};
	}
}
