package io.zeroyaml.controlplane.registration;

import java.util.Objects;

/**
 * The result of one call to {@link RunnerRegistry#heartbeat(String, String)}.
 */
public record HeartbeatOutcome(HeartbeatDecision decision, String message) {

	public HeartbeatOutcome {
		Objects.requireNonNull(decision, "decision must not be null");
		Objects.requireNonNull(message, "message must not be null");
	}
}
