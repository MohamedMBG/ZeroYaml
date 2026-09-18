package io.zeroyaml.controlplane.domain.job;

/**
 * Runner process selected to execute a Job.
 *
 * @param runnerId stable logical Runner identity
 * @param instanceId process-specific Runner identity
 */
public record RunnerAssignment(String runnerId, String instanceId) {

	public RunnerAssignment {
		runnerId = requireNonBlank(runnerId, "runnerId");
		instanceId = requireNonBlank(instanceId, "instanceId");
	}

	private static String requireNonBlank(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}
}
