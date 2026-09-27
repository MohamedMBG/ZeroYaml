package io.zeroyaml.controlplane.domain.repository;

/**
 * Source-control provider hosting a connected repository.
 *
 * <p>Only GitHub is supported in Phase 2. The provider is part of
 * {@link RepositoryIdentity}, so a later provider cannot collide with an
 * existing GitHub connection that shares the same owner and name.</p>
 */
public enum RepositoryProvider {
	GITHUB
}
