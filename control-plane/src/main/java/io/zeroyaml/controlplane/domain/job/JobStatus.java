package io.zeroyaml.controlplane.domain.job;

import java.util.Objects;

/**
 * Lifecycle states owned by the Control Plane, as defined by execution model v1.
 *
 * <p>This enum is the single source of the allowed Job transitions. The
 * {@code runner.v1} contract mirrors these states one to one as
 * {@code JobLifecycleState}, so adding, removing, or renaming a state is a
 * cross-service contract change.</p>
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

	/**
	 * Indicates whether a Job in this state may move to {@code target}.
	 *
	 * <p>Only {@code RUNNING} reaches {@code SUCCEEDED} or {@code FAILED}, because
	 * an outcome is observed only for an execution that started. Any non-terminal
	 * state may be cancelled. A terminal state allows no transition, including to
	 * itself.</p>
	 *
	 * @param target requested next state
	 * @return {@code true} when the transition is allowed
	 */
	public boolean canTransitionTo(JobStatus target) {
		Objects.requireNonNull(target, "target must not be null");
		return switch (this) {
			case CREATED -> target == QUEUED || target == CANCELLED;
			case QUEUED -> target == RUNNING || target == CANCELLED;
			case RUNNING -> target.isTerminal();
			case SUCCEEDED, FAILED, CANCELLED -> false;
		};
	}
}
