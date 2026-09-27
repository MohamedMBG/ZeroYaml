package io.zeroyaml.controlplane.connection;

import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;

/**
 * Raised when an operation targets a repository that is not connected.
 */
public final class RepositoryConnectionNotFoundException extends IllegalStateException {

	private final RepositoryIdentity identity;

	public RepositoryConnectionNotFoundException(RepositoryIdentity identity) {
		super("Repository " + identity.provider() + ":" + identity.fullName() + " is not connected");
		this.identity = identity;
	}

	public RepositoryIdentity getIdentity() {
		return identity;
	}
}
