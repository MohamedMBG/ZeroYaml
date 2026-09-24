package io.zeroyaml.controlplane.domain.repository;

import java.util.regex.Pattern;

/**
 * Name of a secret held outside the domain model, such as an environment
 * variable or a future secret-store entry.
 *
 * <p>The reference never contains secret material. It is resolved to the
 * actual value only at the adapter boundary that needs it, for example webhook
 * signature verification, so the domain object is always safe to log and
 * persist.</p>
 *
 * @param name identifier of the secret, for example {@code ZEROYAML_GITHUB_WEBHOOK_SECRET}
 */
public record SecretReference(String name) {

	static final int MAX_LENGTH = 128;

	/**
	 * Identifier-shaped names only. Rejecting whitespace and arbitrary symbols
	 * keeps the reference usable as an environment variable or store key and
	 * makes it harder to paste a raw secret value by mistake.
	 */
	private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0," + (MAX_LENGTH - 1) + "}");

	public SecretReference {
		if (name == null || !NAME_PATTERN.matcher(name).matches()) {
			throw new IllegalArgumentException("secret reference must be 1-" + MAX_LENGTH
					+ " characters, start with a letter, and contain only letters, digits, underscores, dots, or hyphens");
		}
	}
}
