package io.zeroyaml.controlplane.connection;

import java.util.Optional;
import java.util.function.Function;

import io.zeroyaml.controlplane.domain.repository.RepositoryConnection;
import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;

/**
 * Durable record of the repositories connected to this Control Plane.
 *
 * <p>The port keeps {@link RepositoryConnectionService} independent of the
 * storage technology. Every method works on detached copies: a connection
 * returned by the store is not tracked, so changing it has no effect until it
 * goes through {@link #update(RepositoryIdentity, Function)}.</p>
 */
interface RepositoryConnectionStore {

	/**
	 * Records a new connection.
	 *
	 * @param connection connection to record
	 * @throws DuplicateRepositoryConnectionException when a connection with the same identity is
	 *         already recorded; nothing is written in that case
	 */
	void add(RepositoryConnection connection);

	/**
	 * Looks up a connection for reading.
	 *
	 * @param identity identity to look up
	 * @return the connection, or empty when the repository is not connected
	 */
	Optional<RepositoryConnection> find(RepositoryIdentity identity);

	/**
	 * Applies {@code change} to the recorded connection with exclusive access to
	 * it, then records the result. Two concurrent updates of one connection are
	 * serialized, so neither can overwrite the other with stale state. When
	 * {@code change} throws, nothing is written and the exception propagates.
	 *
	 * @param identity connection to change
	 * @param change work to perform on the connection; it must not block, call back into the store,
	 *        or return {@code null}
	 * @param <R> result produced by {@code change}
	 * @return the result of {@code change}, or empty when the repository is not connected
	 */
	<R> Optional<R> update(RepositoryIdentity identity, Function<RepositoryConnection, R> change);
}
