package io.zeroyaml.controlplane.registration;

import io.zeroyaml.controlplane.runner.RunnerInfo;

/**
 * The Control Plane's inventory of registered Runners. The Control Plane owns
 * registration state and availability policy; a Runner only reports its own
 * identity, capabilities, and liveness.
 */
public interface RunnerRegistry {

	/**
	 * Registers runner, or reconciles a duplicate or conflicting request
	 * deterministically. See {@link RegistrationDecision} for the possible outcomes.
	 */
	RegistrationOutcome register(RunnerInfo runner);

	/**
	 * Records a heartbeat for the Runner instance identified by {@code runnerId}
	 * and {@code instanceId}. A {@code runnerId} that is not registered, or that
	 * is registered under a different {@code instanceId}, is reported as
	 * {@link HeartbeatDecision#UNKNOWN_RUNNER} rather than silently accepted.
	 */
	HeartbeatOutcome heartbeat(String runnerId, String instanceId);

	/**
	 * Returns the current liveness judgment for {@code runnerId} based on the
	 * time elapsed since its last acknowledged registration or heartbeat.
	 */
	RunnerLiveness livenessOf(String runnerId);
}
