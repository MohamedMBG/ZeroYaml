package io.zeroyaml.controlplane.domain.job;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identifier for one executable Job.
 */
public record JobId(UUID value) {

	public JobId {
		Objects.requireNonNull(value, "value must not be null");
	}

	/**
	 * Creates a new identifier for a Job aggregate.
	 *
	 * @return a randomly generated Job identifier
	 */
	public static JobId newId() {
		return new JobId(UUID.randomUUID());
	}
}
