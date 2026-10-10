package io.zeroyaml.controlplane.registration;

import java.time.Instant;
import java.util.Objects;

import io.zeroyaml.controlplane.runner.RunnerInfo;
import io.zeroyaml.controlplane.runner.RunnerState;

/**
 * A Runner registration entry held by the Control Plane's in-memory inventory.
 * {@code lastSeenAt} is updated by every acknowledged heartbeat (and by
 * registration itself) and is the only input to {@link RunnerLiveness}.
 *
 * <p>{@code runner} starts as the registration snapshot. Its state and
 * {@code acceptingWork} flag are replaced by each heartbeat that reports them,
 * so they describe the Runner as of {@code lastSeenAt} rather than as of
 * registration.</p>
 */
public record RegisteredRunner(String registrationId, RunnerInfo runner, Instant registeredAt, Instant lastSeenAt) {

	public RegisteredRunner {
		Objects.requireNonNull(registrationId, "registrationId must not be null");
		Objects.requireNonNull(runner, "runner must not be null");
		Objects.requireNonNull(registeredAt, "registeredAt must not be null");
		Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
	}

	/** Returns a copy of this entry with {@code lastSeenAt} updated to {@code instant}. */
	RegisteredRunner seenAt(Instant instant) {
		return new RegisteredRunner(registrationId, runner, registeredAt, instant);
	}

	/**
	 * Returns a copy of this entry seen at {@code instant} and reporting the
	 * given availability.
	 */
	RegisteredRunner reportingAt(Instant instant, RunnerState state, boolean acceptingWork) {
		return new RegisteredRunner(
				registrationId, runner.withAvailability(state, acceptingWork), registeredAt, instant);
	}
}
