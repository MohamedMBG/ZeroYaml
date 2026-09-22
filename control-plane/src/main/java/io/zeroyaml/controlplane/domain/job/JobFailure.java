package io.zeroyaml.controlplane.domain.job;

import java.util.OptionalInt;

/**
 * Safe, structured failure information retained for a failed Job.
 *
 * @param code stable failure category
 * @param message human-readable diagnostic without credentials or secrets
 * @param exitCode code the executed command exited with, or {@code null} when it produced none,
 *        for example when it was stopped on timeout or could never be started
 */
public record JobFailure(String code, String message, Integer exitCode) {

	public JobFailure {
		code = requireNonBlank(code, "code");
		message = requireNonBlank(message, "message");
	}

	/**
	 * Creates failure information for a Job that produced no exit code.
	 *
	 * @param code stable failure category
	 * @param message human-readable diagnostic
	 */
	public JobFailure(String code, String message) {
		this(code, message, null);
	}

	/**
	 * Returns the exit code of the failed command.
	 *
	 * <p>An absent value means no command produced a code. It must never be read
	 * as a successful {@code 0}.</p>
	 *
	 * @return the reported exit code, or empty when none was reported
	 */
	public OptionalInt exit() {
		return exitCode == null ? OptionalInt.empty() : OptionalInt.of(exitCode);
	}

	private static String requireNonBlank(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}
}
