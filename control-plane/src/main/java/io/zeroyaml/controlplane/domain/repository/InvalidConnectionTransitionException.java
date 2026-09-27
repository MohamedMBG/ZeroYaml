package io.zeroyaml.controlplane.domain.repository;

/**
 * Raised when a repository connection is asked to move between statuses
 * outside its lifecycle contract.
 */
public final class InvalidConnectionTransitionException extends IllegalStateException {

	private final ConnectionStatus currentStatus;
	private final ConnectionStatus requestedStatus;

	public InvalidConnectionTransitionException(ConnectionStatus currentStatus, ConnectionStatus requestedStatus) {
		super("Repository connection cannot transition from " + currentStatus + " to " + requestedStatus);
		this.currentStatus = currentStatus;
		this.requestedStatus = requestedStatus;
	}

	public ConnectionStatus getCurrentStatus() {
		return currentStatus;
	}

	public ConnectionStatus getRequestedStatus() {
		return requestedStatus;
	}
}
