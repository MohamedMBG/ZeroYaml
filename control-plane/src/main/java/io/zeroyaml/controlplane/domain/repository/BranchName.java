package io.zeroyaml.controlplane.domain.repository;

import java.util.regex.Pattern;

/**
 * Validated Git branch name, such as a repository's default branch.
 *
 * <p>Validation applies the subset of {@code git check-ref-format} rules that
 * protect later use of the value in refs, logs, and Runner commands. Branch
 * names are case-sensitive and are kept exactly as provided.</p>
 *
 * @param value short branch name without the {@code refs/heads/} prefix
 */
public record BranchName(String value) {

	static final int MAX_LENGTH = 255;

	private static final Pattern FORBIDDEN_CHARACTERS = Pattern.compile("[\\x00-\\x20\\x7F~^:?*\\[\\\\]");

	public BranchName {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("branch name must not be blank");
		}
		if (value.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("branch name must not exceed " + MAX_LENGTH + " characters");
		}
		if (FORBIDDEN_CHARACTERS.matcher(value).find()
				|| value.startsWith("-")
				|| value.startsWith(".")
				|| value.startsWith("/")
				|| value.startsWith("refs/")
				|| value.endsWith("/")
				|| value.endsWith(".")
				|| value.endsWith(".lock")
				|| value.contains("..")
				|| value.contains("//")
				|| value.contains("/.")
				|| value.contains("@{")
				|| value.equals("@")) {
			throw new IllegalArgumentException("branch name is not a valid Git branch name");
		}
	}

	@Override
	public String toString() {
		return value;
	}
}
