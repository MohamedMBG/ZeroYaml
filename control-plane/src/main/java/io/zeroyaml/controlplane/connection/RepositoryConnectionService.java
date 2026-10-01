package io.zeroyaml.controlplane.connection;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.zeroyaml.controlplane.domain.repository.BranchName;
import io.zeroyaml.controlplane.domain.repository.RepositoryConnection;
import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;
import io.zeroyaml.controlplane.domain.repository.WebhookConfiguration;

/**
 * Application entry point for connecting repositories and changing their
 * recorded metadata.
 *
 * <p>Every change runs through {@link RepositoryConnectionStore#update}, so it
 * is applied to the current recorded state under exclusive access and a
 * lifecycle rule violation leaves the record unchanged. Returned connections
 * are detached snapshots; modifying one does not change the record.</p>
 *
 * <p>Timestamps come from the service clock and are truncated to microseconds,
 * the precision the store keeps, so a returned snapshot equals what a later
 * {@link #find} reads back.</p>
 */
@Service
public class RepositoryConnectionService {

	private final RepositoryConnectionStore store;
	private final Clock clock;

	@Autowired
	RepositoryConnectionService(RepositoryConnectionStore store) {
		this(store, Clock.systemUTC());
	}

	/**
	 * Accepts an explicit clock so tests can control transition times. The
	 * registration package already exposes a {@link Clock} bean, so a second one
	 * is not registered here; that would make injection by type ambiguous.
	 */
	RepositoryConnectionService(RepositoryConnectionStore store, Clock clock) {
		this.store = Objects.requireNonNull(store, "store must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	/**
	 * Records a new connection in {@code PENDING} status.
	 *
	 * @return the recorded connection
	 * @throws DuplicateRepositoryConnectionException when the repository is already connected
	 */
	public RepositoryConnection connect(
			RepositoryIdentity identity,
			BranchName defaultBranch,
			WebhookConfiguration webhook) {
		var connection = RepositoryConnection.connect(identity, defaultBranch, webhook, now());
		store.add(connection);
		return connection;
	}

	/**
	 * Returns the recorded connection for {@code identity}, or empty when the
	 * repository is not connected.
	 */
	public Optional<RepositoryConnection> find(RepositoryIdentity identity) {
		return store.find(identity);
	}

	/**
	 * Allows webhook deliveries for the repository to trigger work.
	 *
	 * @throws RepositoryConnectionNotFoundException when the repository is not connected
	 * @throws io.zeroyaml.controlplane.domain.repository.InvalidConnectionTransitionException
	 *         when the current status does not allow activation
	 */
	public RepositoryConnection activate(RepositoryIdentity identity) {
		return modify(identity, RepositoryConnection::activate);
	}

	/**
	 * Pauses an active connection.
	 *
	 * @throws RepositoryConnectionNotFoundException when the repository is not connected
	 * @throws io.zeroyaml.controlplane.domain.repository.InvalidConnectionTransitionException
	 *         when the connection is not active
	 */
	public RepositoryConnection suspend(RepositoryIdentity identity) {
		return modify(identity, RepositoryConnection::suspend);
	}

	/**
	 * Permanently disconnects the repository.
	 *
	 * @throws RepositoryConnectionNotFoundException when the repository is not connected
	 * @throws io.zeroyaml.controlplane.domain.repository.InvalidConnectionTransitionException
	 *         when the connection is already disconnected
	 */
	public RepositoryConnection disconnect(RepositoryIdentity identity) {
		return modify(identity, RepositoryConnection::disconnect);
	}

	/**
	 * Records a new default branch.
	 *
	 * @throws RepositoryConnectionNotFoundException when the repository is not connected
	 * @throws IllegalStateException when the connection is disconnected
	 */
	public RepositoryConnection changeDefaultBranch(RepositoryIdentity identity, BranchName defaultBranch) {
		Objects.requireNonNull(defaultBranch, "defaultBranch must not be null");
		return modify(identity, (connection, changedAt) -> connection.changeDefaultBranch(defaultBranch));
	}

	/**
	 * Replaces the webhook metadata, for example after rotating the signing
	 * secret to a new reference.
	 *
	 * @throws RepositoryConnectionNotFoundException when the repository is not connected
	 * @throws IllegalStateException when the connection is disconnected
	 */
	public RepositoryConnection reconfigureWebhook(RepositoryIdentity identity, WebhookConfiguration webhook) {
		Objects.requireNonNull(webhook, "webhook must not be null");
		return modify(identity, (connection, changedAt) -> connection.reconfigureWebhook(webhook));
	}

	private RepositoryConnection modify(
			RepositoryIdentity identity,
			BiConsumer<RepositoryConnection, Instant> change) {
		Objects.requireNonNull(identity, "identity must not be null");

		// The time is read after the store grants exclusive access, so a change
		// that waited for a concurrent update is never stamped earlier than it.
		return store.update(identity, connection -> {
			change.accept(connection, now());
			return connection;
		}).orElseThrow(() -> new RepositoryConnectionNotFoundException(identity));
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}
}
