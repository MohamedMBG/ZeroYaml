package io.zeroyaml.controlplane.registration;

import io.zeroyaml.controlplane.runner.RunnerInfo;

/**
 * The Control Plane's inventory of registered Runners. The Control Plane owns
 * registration state and availability policy; a Runner only reports its own
 * identity and capabilities.
 */
public interface RunnerRegistry {

	/**
	 * Registers runner, or reconciles a duplicate or conflicting request
	 * deterministically. See {@link RegistrationDecision} for the possible outcomes.
	 */
	RegistrationOutcome register(RunnerInfo runner);
}
