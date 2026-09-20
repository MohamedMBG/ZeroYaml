package io.zeroyaml.controlplane.registration;

import java.time.Instant;
import java.util.Objects;

import io.zeroyaml.controlplane.runner.RunnerInfo;

/**
 * A Runner registration entry held by the Control Plane's in-memory inventory.
 */
public record RegisteredRunner(String registrationId, RunnerInfo runner, Instant registeredAt) {

	public RegisteredRunner {
		Objects.requireNonNull(registrationId, "registrationId must not be null");
		Objects.requireNonNull(runner, "runner must not be null");
		Objects.requireNonNull(registeredAt, "registeredAt must not be null");
	}
}
