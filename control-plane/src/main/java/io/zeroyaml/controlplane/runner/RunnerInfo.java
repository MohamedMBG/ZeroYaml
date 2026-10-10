package io.zeroyaml.controlplane.runner;

import java.util.Objects;

/**
 * Protocol-neutral identity and current lifecycle facts reported by a Runner.
 *
 * <p>{@code runnerId} and {@code instanceId} must not be blank. They key the
 * registry and name the Runner a Job is dispatched to, so a Runner that
 * reports an empty identity is rejected at the boundary instead of being
 * stored as an entry nothing can address.</p>
 */
public record RunnerInfo(
		String runnerId,
		String instanceId,
		String runnerVersion,
		String protocolVersion,
		RunnerState state,
		boolean acceptingWork,
		RunnerCapabilities capabilities
) {

	public RunnerInfo {
		requireNonBlank(runnerId, "runnerId");
		requireNonBlank(instanceId, "instanceId");
		Objects.requireNonNull(runnerVersion, "runnerVersion must not be null");
		Objects.requireNonNull(protocolVersion, "protocolVersion must not be null");
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(capabilities, "capabilities must not be null");
	}

	/**
	 * Returns a copy of this Runner with the availability it reported later,
	 * leaving its identity, versions, and capabilities unchanged.
	 */
	public RunnerInfo withAvailability(RunnerState state, boolean acceptingWork) {
		return new RunnerInfo(
				runnerId, instanceId, runnerVersion, protocolVersion, state, acceptingWork, capabilities);
	}

	private static void requireNonBlank(String value, String fieldName) {
		Objects.requireNonNull(value, fieldName + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException("Runner reported a blank " + fieldName);
		}
	}
}
