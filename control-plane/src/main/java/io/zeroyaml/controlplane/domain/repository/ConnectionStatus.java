package io.zeroyaml.controlplane.domain.repository;

/**
 * Lifecycle status of a {@link RepositoryConnection}.
 */
public enum ConnectionStatus {

	/** Connection recorded, but webhook delivery is not yet confirmed. */
	PENDING,

	/** Webhook deliveries are accepted and may trigger pipelines. */
	ACTIVE,

	/** Temporarily paused; deliveries are ignored until reactivated. */
	SUSPENDED,

	/** Permanently disconnected; a new connection is required to reconnect. */
	DISCONNECTED;

	/**
	 * Returns whether webhook deliveries for the repository may trigger work.
	 */
	public boolean acceptsEvents() {
		return this == ACTIVE;
	}

	/**
	 * Returns whether no further lifecycle transition is allowed.
	 */
	public boolean isTerminal() {
		return this == DISCONNECTED;
	}
}
