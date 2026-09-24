package io.zeroyaml.controlplane.domain.repository;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, provider-scoped identity of a connected repository.
 *
 * <p>GitHub resolves owner and repository names case-insensitively, so both
 * values are canonicalized to lower case. Two identities that differ only by
 * letter case are therefore equal, which prevents one repository from being
 * connected twice under different spellings.</p>
 *
 * <p>The identity is based on owner and name. A repository rename or ownership
 * transfer produces a new identity; tracking the provider's numeric repository
 * ID across renames is outside the Phase 2 model.</p>
 *
 * @param provider source-control provider hosting the repository
 * @param owner canonical owner (user or organization) login
 * @param name canonical repository name
 */
public record RepositoryIdentity(RepositoryProvider provider, String owner, String name) {

	/**
	 * GitHub login rules: 1-39 alphanumeric characters or single hyphens, not
	 * starting or ending with a hyphen.
	 */
	private static final Pattern OWNER_PATTERN =
			Pattern.compile("[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){0,38}");

	/**
	 * GitHub repository name rules: 1-100 characters from letters, digits,
	 * {@code .}, {@code _}, and {@code -}.
	 */
	private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9._-]{1,100}");

	public RepositoryIdentity {
		Objects.requireNonNull(provider, "provider must not be null");
		owner = canonicalize(owner, "owner");
		name = canonicalize(name, "name");
		if (!OWNER_PATTERN.matcher(owner).matches()) {
			throw new IllegalArgumentException("owner is not a valid GitHub owner login");
		}
		if (!NAME_PATTERN.matcher(name).matches() || name.equals(".") || name.equals("..")) {
			throw new IllegalArgumentException("name is not a valid GitHub repository name");
		}
	}

	/**
	 * Creates a GitHub repository identity.
	 *
	 * @param owner owner login in any letter case
	 * @param name repository name in any letter case
	 * @return the canonical identity
	 */
	public static RepositoryIdentity github(String owner, String name) {
		return new RepositoryIdentity(RepositoryProvider.GITHUB, owner, name);
	}

	/**
	 * Returns the canonical {@code owner/name} form.
	 */
	public String fullName() {
		return owner + "/" + name;
	}

	private static String canonicalize(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value.toLowerCase(Locale.ROOT);
	}
}
