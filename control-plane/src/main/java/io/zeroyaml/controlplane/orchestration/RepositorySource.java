package io.zeroyaml.controlplane.orchestration;

import java.net.URI;

import io.zeroyaml.controlplane.domain.pipeline.inference.RepositorySnapshot;
import io.zeroyaml.controlplane.domain.repository.CommitSha;
import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;

/**
 * Application-facing access to a connected repository's source provider.
 *
 * <p>The Control Plane reads only what inference needs, the file listing at the
 * repository root, and leaves checking out the source to the Runner.</p>
 */
public interface RepositorySource {

	/**
	 * Returns the location a Runner checks the repository out from. It never
	 * carries credentials, because it is persisted with every Job and sent to the
	 * Runner in the clear.
	 */
	URI location(RepositoryIdentity repository);

	/**
	 * Reads the file paths at the root of {@code repository} at {@code revision}.
	 *
	 * @throws RepositorySnapshotUnavailableException if the listing cannot be read completely
	 */
	RepositorySnapshot rootSnapshot(RepositoryIdentity repository, CommitSha revision);
}
