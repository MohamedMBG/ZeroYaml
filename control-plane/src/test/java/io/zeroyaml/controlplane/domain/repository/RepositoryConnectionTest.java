package io.zeroyaml.controlplane.domain.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

class RepositoryConnectionTest {

	private static final Instant CONNECTED_AT = Instant.parse("2026-09-23T10:00:00Z");
	private static final Instant ACTIVATED_AT = Instant.parse("2026-09-23T10:01:00Z");
	private static final Instant SUSPENDED_AT = Instant.parse("2026-09-23T10:02:00Z");
	private static final Instant DISCONNECTED_AT = Instant.parse("2026-09-23T10:03:00Z");

	private static final RepositoryIdentity IDENTITY = RepositoryIdentity.github("MohamedMBG", "ZeroYaml");
	private static final BranchName MAIN = new BranchName("main");
	private static final WebhookConfiguration WEBHOOK =
			new WebhookConfiguration(new SecretReference("ZEROYAML_GITHUB_WEBHOOK_SECRET"));

	@Test
	void connectsRepositoryInPendingStatus() {
		var connection = RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT);

		assertEquals(IDENTITY, connection.identity());
		assertEquals(MAIN, connection.defaultBranch());
		assertEquals(WEBHOOK, connection.webhook());
		assertEquals(ConnectionStatus.PENDING, connection.status());
		assertEquals(CONNECTED_AT, connection.connectedAt());
		assertEquals(CONNECTED_AT, connection.statusChangedAt());
		assertFalse(connection.status().acceptsEvents());
	}

	@Test
	void rejectsMissingRequiredFields() {
		assertThrows(NullPointerException.class, () -> RepositoryConnection.connect(null, MAIN, WEBHOOK, CONNECTED_AT));
		assertThrows(NullPointerException.class, () -> RepositoryConnection.connect(IDENTITY, null, WEBHOOK, CONNECTED_AT));
		assertThrows(NullPointerException.class, () -> RepositoryConnection.connect(IDENTITY, MAIN, null, CONNECTED_AT));
		assertThrows(NullPointerException.class, () -> RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, null));
	}

	@Test
	void followsActivateSuspendReactivateDisconnectLifecycle() {
		var connection = newConnection();

		connection.activate(ACTIVATED_AT);
		assertEquals(ConnectionStatus.ACTIVE, connection.status());
		assertTrue(connection.status().acceptsEvents());

		connection.suspend(SUSPENDED_AT);
		assertEquals(ConnectionStatus.SUSPENDED, connection.status());
		assertFalse(connection.status().acceptsEvents());

		connection.activate(SUSPENDED_AT);
		assertEquals(ConnectionStatus.ACTIVE, connection.status());

		connection.disconnect(DISCONNECTED_AT);
		assertEquals(ConnectionStatus.DISCONNECTED, connection.status());
		assertEquals(DISCONNECTED_AT, connection.statusChangedAt());
		assertTrue(connection.status().isTerminal());
		assertFalse(connection.status().acceptsEvents());
	}

	@Test
	void allowsDisconnectingPendingConnection() {
		var connection = newConnection();

		connection.disconnect(DISCONNECTED_AT);

		assertEquals(ConnectionStatus.DISCONNECTED, connection.status());
	}

	@Test
	void rejectsIllegalTransitionsPredictably() {
		var connection = newConnection();

		var suspendException = assertThrows(InvalidConnectionTransitionException.class,
				() -> connection.suspend(SUSPENDED_AT));
		assertEquals(ConnectionStatus.PENDING, suspendException.getCurrentStatus());
		assertEquals(ConnectionStatus.SUSPENDED, suspendException.getRequestedStatus());

		connection.activate(ACTIVATED_AT);
		assertThrows(InvalidConnectionTransitionException.class, () -> connection.activate(SUSPENDED_AT));

		connection.disconnect(DISCONNECTED_AT);
		assertThrows(InvalidConnectionTransitionException.class, () -> connection.activate(DISCONNECTED_AT));
		assertThrows(InvalidConnectionTransitionException.class, () -> connection.suspend(DISCONNECTED_AT));
		assertThrows(InvalidConnectionTransitionException.class, () -> connection.disconnect(DISCONNECTED_AT));
		assertEquals(ConnectionStatus.DISCONNECTED, connection.status());
	}

	@Test
	void rejectsTransitionTimestampsThatGoBackwards() {
		var connection = newConnection();
		connection.activate(SUSPENDED_AT);

		assertThrows(IllegalArgumentException.class, () -> connection.suspend(ACTIVATED_AT));
		assertThrows(NullPointerException.class, () -> connection.suspend(null));
		assertEquals(ConnectionStatus.ACTIVE, connection.status());
		assertEquals(SUSPENDED_AT, connection.statusChangedAt());
	}

	@Test
	void recordsDefaultBranchAndWebhookChanges() {
		var connection = newConnection();
		var trunk = new BranchName("trunk");
		var rotated = new WebhookConfiguration(new SecretReference("ZEROYAML_GITHUB_WEBHOOK_SECRET_V2"));

		connection.changeDefaultBranch(trunk);
		connection.reconfigureWebhook(rotated);

		assertEquals(trunk, connection.defaultBranch());
		assertEquals(rotated, connection.webhook());
		assertThrows(NullPointerException.class, () -> connection.changeDefaultBranch(null));
		assertThrows(NullPointerException.class, () -> connection.reconfigureWebhook(null));
	}

	@Test
	void rejectsChangesAfterDisconnect() {
		var connection = newConnection();
		connection.disconnect(DISCONNECTED_AT);

		assertThrows(IllegalStateException.class, () -> connection.changeDefaultBranch(new BranchName("trunk")));
		assertThrows(IllegalStateException.class, () -> connection.reconfigureWebhook(WEBHOOK));
		assertEquals(MAIN, connection.defaultBranch());
	}

	@Test
	void connectionsForSameRepositoryAreDuplicates() {
		var first = newConnection();
		var second = RepositoryConnection.connect(
				RepositoryIdentity.github("mohamedmbg", "zeroyaml"),
				new BranchName("develop"),
				new WebhookConfiguration(new SecretReference("OTHER_SECRET")),
				ACTIVATED_AT);
		second.activate(SUSPENDED_AT);

		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());

		var connections = new HashSet<RepositoryConnection>();
		assertTrue(connections.add(first));
		assertFalse(connections.add(second));
		assertEquals(1, connections.size());
	}

	@Test
	void connectionsForDifferentRepositoriesAreDistinct() {
		var other = RepositoryConnection.connect(
				RepositoryIdentity.github("MohamedMBG", "other-repo"), MAIN, WEBHOOK, CONNECTED_AT);

		assertNotEquals(newConnection(), other);
	}

	@Test
	void restoresRecordedStateAndKeepsEnforcingTheLifecycle() {
		var connection = RepositoryConnection.restore(
				IDENTITY, MAIN, WEBHOOK, ConnectionStatus.SUSPENDED, CONNECTED_AT, SUSPENDED_AT);

		assertEquals(IDENTITY, connection.identity());
		assertEquals(MAIN, connection.defaultBranch());
		assertEquals(WEBHOOK, connection.webhook());
		assertEquals(ConnectionStatus.SUSPENDED, connection.status());
		assertEquals(CONNECTED_AT, connection.connectedAt());
		assertEquals(SUSPENDED_AT, connection.statusChangedAt());

		assertThrows(IllegalArgumentException.class, () -> connection.activate(ACTIVATED_AT));
		connection.activate(DISCONNECTED_AT);
		assertEquals(ConnectionStatus.ACTIVE, connection.status());
	}

	@Test
	void restoredDisconnectedConnectionStaysTerminal() {
		var connection = RepositoryConnection.restore(
				IDENTITY, MAIN, WEBHOOK, ConnectionStatus.DISCONNECTED, CONNECTED_AT, DISCONNECTED_AT);

		assertThrows(InvalidConnectionTransitionException.class, () -> connection.activate(DISCONNECTED_AT));
		assertThrows(IllegalStateException.class, () -> connection.changeDefaultBranch(new BranchName("trunk")));
	}

	@Test
	void rejectsRecordedStateThatTheLifecycleCannotProduce() {
		assertThrows(IllegalArgumentException.class, () -> RepositoryConnection.restore(
				IDENTITY, MAIN, WEBHOOK, ConnectionStatus.ACTIVE, ACTIVATED_AT, CONNECTED_AT));
		assertThrows(NullPointerException.class, () -> RepositoryConnection.restore(
				IDENTITY, MAIN, WEBHOOK, null, CONNECTED_AT, CONNECTED_AT));
		assertThrows(NullPointerException.class, () -> RepositoryConnection.restore(
				IDENTITY, MAIN, WEBHOOK, ConnectionStatus.ACTIVE, CONNECTED_AT, null));
		assertThrows(NullPointerException.class, () -> RepositoryConnection.restore(
				null, MAIN, WEBHOOK, ConnectionStatus.ACTIVE, CONNECTED_AT, CONNECTED_AT));
	}

	@Test
	void stringRepresentationContainsOnlySecretReference() {
		var text = newConnection().toString();

		assertTrue(text.contains("identity=GITHUB:mohamedmbg/zeroyaml"));
		assertTrue(text.contains("signingSecret=SecretReference[name=ZEROYAML_GITHUB_WEBHOOK_SECRET]"));
		assertTrue(text.contains("status=PENDING"));
	}

	private static RepositoryConnection newConnection() {
		return RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT);
	}
}
