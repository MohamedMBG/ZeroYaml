package io.zeroyaml.controlplane.domain.repository;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Control Plane aggregate representing one repository connected to ZeroYAML.
 *
 * <p>The aggregate is identified by its {@link RepositoryIdentity}. Equality
 * and hash code use only that identity, so two connection objects for the same
 * repository are equal regardless of their status, branch, or webhook
 * metadata. Collections and future persistence can rely on this to reject
 * duplicate connections.</p>
 *
 * <p>The aggregate does not persist itself, call the provider, or hold secret
 * material. Instances are not thread-safe; callers coordinate concurrent
 * modification.</p>
 */
public final class RepositoryConnection {

	private static final Map<ConnectionStatus, Set<ConnectionStatus>> LEGAL_TRANSITIONS = Map.of(
			ConnectionStatus.PENDING, Set.of(ConnectionStatus.ACTIVE, ConnectionStatus.DISCONNECTED),
			ConnectionStatus.ACTIVE, Set.of(ConnectionStatus.SUSPENDED, ConnectionStatus.DISCONNECTED),
			ConnectionStatus.SUSPENDED, Set.of(ConnectionStatus.ACTIVE, ConnectionStatus.DISCONNECTED),
			ConnectionStatus.DISCONNECTED, Set.of()
	);

	private final RepositoryIdentity identity;
	private final Instant connectedAt;

	private BranchName defaultBranch;
	private WebhookConfiguration webhook;
	private ConnectionStatus status;
	private Instant statusChangedAt;

	private RepositoryConnection(
			RepositoryIdentity identity,
			BranchName defaultBranch,
			WebhookConfiguration webhook,
			Instant connectedAt) {
		this.identity = Objects.requireNonNull(identity, "identity must not be null");
		this.defaultBranch = Objects.requireNonNull(defaultBranch, "defaultBranch must not be null");
		this.webhook = Objects.requireNonNull(webhook, "webhook must not be null");
		this.connectedAt = Objects.requireNonNull(connectedAt, "connectedAt must not be null");
		this.status = ConnectionStatus.PENDING;
		this.statusChangedAt = connectedAt;
	}

	/**
	 * Records a new repository connection in {@link ConnectionStatus#PENDING}.
	 *
	 * @param identity provider-scoped repository identity
	 * @param defaultBranch repository default branch
	 * @param webhook webhook metadata without secret material
	 * @param connectedAt time the connection was recorded
	 * @return a pending connection
	 */
	public static RepositoryConnection connect(
			RepositoryIdentity identity,
			BranchName defaultBranch,
			WebhookConfiguration webhook,
			Instant connectedAt) {
		return new RepositoryConnection(identity, defaultBranch, webhook, connectedAt);
	}

	public RepositoryIdentity identity() {
		return identity;
	}

	public BranchName defaultBranch() {
		return defaultBranch;
	}

	public WebhookConfiguration webhook() {
		return webhook;
	}

	public ConnectionStatus status() {
		return status;
	}

	public Instant connectedAt() {
		return connectedAt;
	}

	public Instant statusChangedAt() {
		return statusChangedAt;
	}

	/**
	 * Marks the connection ready to accept webhook deliveries, either for the
	 * first time or after a suspension.
	 */
	public void activate(Instant activatedAt) {
		transitionTo(ConnectionStatus.ACTIVE, activatedAt);
	}

	/**
	 * Pauses an active connection without discarding its configuration.
	 */
	public void suspend(Instant suspendedAt) {
		transitionTo(ConnectionStatus.SUSPENDED, suspendedAt);
	}

	/**
	 * Permanently disconnects the repository. Reconnecting requires a new
	 * connection.
	 */
	public void disconnect(Instant disconnectedAt) {
		transitionTo(ConnectionStatus.DISCONNECTED, disconnectedAt);
	}

	/**
	 * Records a default branch change reported by the provider.
	 */
	public void changeDefaultBranch(BranchName newDefaultBranch) {
		Objects.requireNonNull(newDefaultBranch, "newDefaultBranch must not be null");
		requireNotDisconnected();
		this.defaultBranch = newDefaultBranch;
	}

	/**
	 * Replaces webhook metadata, for example after rotating the signing secret
	 * to a new reference.
	 */
	public void reconfigureWebhook(WebhookConfiguration newWebhook) {
		Objects.requireNonNull(newWebhook, "newWebhook must not be null");
		requireNotDisconnected();
		this.webhook = newWebhook;
	}

	private void requireNotDisconnected() {
		if (status.isTerminal()) {
			throw new IllegalStateException("a disconnected repository connection cannot be modified");
		}
	}

	private void transitionTo(ConnectionStatus target, Instant transitionAt) {
		Objects.requireNonNull(transitionAt, "transitionAt must not be null");
		if (!LEGAL_TRANSITIONS.get(status).contains(target)) {
			throw new InvalidConnectionTransitionException(status, target);
		}
		if (transitionAt.isBefore(statusChangedAt)) {
			throw new IllegalArgumentException("transition timestamp must not be before the previous status change");
		}
		status = target;
		statusChangedAt = transitionAt;
	}

	@Override
	public boolean equals(Object other) {
		return this == other
				|| other instanceof RepositoryConnection connection && identity.equals(connection.identity);
	}

	@Override
	public int hashCode() {
		return identity.hashCode();
	}

	@Override
	public String toString() {
		return "RepositoryConnection[identity=" + identity.provider() + ":" + identity.fullName()
				+ ", defaultBranch=" + defaultBranch
				+ ", status=" + status
				+ ", webhook=" + webhook + "]";
	}
}
