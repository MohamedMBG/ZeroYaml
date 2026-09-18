package io.zeroyaml.controlplane.domain.job;

/**
 * Safe, structured failure information retained for a failed Job.
 *
 * @param code stable failure category
 * @param message human-readable diagnostic without credentials or secrets
 */
public record JobFailure(String code, String message) {

	public JobFailure {
		code = requireNonBlank(code, "code");
		message = requireNonBlank(message, "message");
	}

	private static String requireNonBlank(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}
}
