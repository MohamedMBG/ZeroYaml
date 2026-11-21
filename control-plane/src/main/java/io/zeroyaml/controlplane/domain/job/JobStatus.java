package io.zeroyaml.controlplane.domain.job;

import java.util.Objects;

/**
 * Lifecycle states owned by the Control Plane, as defined by execution model v1.
 *
 * <p>This enum is the single source of the allowed Job transitions. The
 * {@code runner.v1} contract mirrors these states one to one as
 * {@code JobLifecycleState}, so adding, removing, or renaming a state is a
 * cross-service contract change.</p>
 *
 * <p>{@code SKIPPED} records a Job that never ran because one of its pipeline
 * step dependencies ended without success. It is distinct from
 * {@code CANCELLED}, which records a decision to stop a Job, so a run's history
 * shows which Jobs were stopped and which were never eligible to start.</p>
 */
public enum JobStatus {
	CREATED,
	QUEUED,
	RUNNING,
	SUCCEEDED,
	FAILED,
	CANCELLED,
	SKIPPED;

	/**
	 * Indicates whether this state ends the Job lifecycle.
	 *
	 * @return {@code true} for terminal states
	 */
	public boolean isTerminal() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SKIPPED;
	}

	/**
	 * Indicates whether a Job in this state may move to {@code target}.
	 *
	 * <p>Only {@code RUNNING} reaches {@code SUCCEEDED} or {@code FAILED}, because
	 * an outcome is observed only for an execution that started. Any non-terminal
	 * state may be cancelled. Only {@code CREATED} reaches {@code SKIPPED}, because
	 * a Job is queued only after its dependencies succeeded. A terminal state
	 * allows no transition, including to itself.</p>
	 *
	 * @param target requested next state
	 * @return {@code true} when the transition is allowed
	 */
	public boolean canTransitionTo(JobStatus target) {
		Objects.requireNonNull(target, "target must not be null");
		return switch (this) {
			case CREATED -> target == QUEUED || target == CANCELLED || target == SKIPPED;
			case QUEUED -> target == RUNNING || target == CANCELLED;
			case RUNNING -> target == SUCCEEDED || target == FAILED || target == CANCELLED;
			case SUCCEEDED, FAILED, CANCELLED, SKIPPED -> false;
		};
	}
}
