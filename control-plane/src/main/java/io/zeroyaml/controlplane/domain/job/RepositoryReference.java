package io.zeroyaml.controlplane.domain.job;

import java.net.URI;
import java.util.Objects;

/**
 * Provider-neutral source repository context for a Job.
 *
 * @param location repository URI; the URI scheme identifies how it can be resolved
 * @param revision immutable source revision to execute
 */
public record RepositoryReference(URI location, String revision) {

	public RepositoryReference {
		Objects.requireNonNull(location, "location must not be null");
		if (!location.isAbsolute()) {
			throw new IllegalArgumentException("location must be an absolute URI");
		}
		revision = requireNonBlank(revision, "revision");
	}

	private static String requireNonBlank(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}
}
