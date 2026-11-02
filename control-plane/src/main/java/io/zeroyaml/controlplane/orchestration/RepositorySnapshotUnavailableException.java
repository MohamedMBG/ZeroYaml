package io.zeroyaml.controlplane.orchestration;

/**
 * The repository file listing could not be read completely, so no pipeline can
 * be inferred for the push. The message is operator-facing and never contains
 * credentials or response bodies.
 */
public class RepositorySnapshotUnavailableException extends RuntimeException {

	public RepositorySnapshotUnavailableException(String message) {
		super(message);
	}

	public RepositorySnapshotUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
