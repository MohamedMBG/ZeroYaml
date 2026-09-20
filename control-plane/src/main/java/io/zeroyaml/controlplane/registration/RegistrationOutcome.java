package io.zeroyaml.controlplane.registration;

import java.util.Objects;

/**
 * The result of one call to {@link RunnerRegistry#register(io.zeroyaml.controlplane.runner.RunnerInfo)}.
 * {@code registrationId} is empty when {@code decision} is {@link RegistrationDecision#IDENTITY_CONFLICT},
 * because no registration is created or reused in that case.
 */
public record RegistrationOutcome(RegistrationDecision decision, String registrationId, String message) {

	public RegistrationOutcome {
		Objects.requireNonNull(decision, "decision must not be null");
		Objects.requireNonNull(registrationId, "registrationId must not be null");
		Objects.requireNonNull(message, "message must not be null");
	}
}
