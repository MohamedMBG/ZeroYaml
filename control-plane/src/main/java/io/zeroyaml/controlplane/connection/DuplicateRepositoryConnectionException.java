package io.zeroyaml.controlplane.connection;

import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;

/**
 * Raised when a repository that is already connected is connected again.
 *
 * <p>Identity comparison is case-insensitive for owner and name, as defined by
 * {@link RepositoryIdentity}, so a different spelling of the same repository
 * is also a duplicate.</p>
 */
public final class DuplicateRepositoryConnectionException extends IllegalStateException {

	private final RepositoryIdentity identity;

	public DuplicateRepositoryConnectionException(RepositoryIdentity identity) {
		super("Repository " + identity.provider() + ":" + identity.fullName() + " is already connected");
		this.identity = identity;
	}

	public RepositoryIdentity getIdentity() {
		return identity;
	}
}
