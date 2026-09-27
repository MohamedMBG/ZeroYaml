package io.zeroyaml.controlplane.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.zeroyaml.controlplane.domain.repository.BranchName;
import io.zeroyaml.controlplane.domain.repository.ConnectionStatus;
import io.zeroyaml.controlplane.domain.repository.InvalidConnectionTransitionException;
import io.zeroyaml.controlplane.domain.repository.RepositoryConnection;
import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;
import io.zeroyaml.controlplane.domain.repository.SecretReference;
import io.zeroyaml.controlplane.domain.repository.WebhookConfiguration;

class RepositoryConnectionServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-27T10:00:00.123456789Z");
	private static final Instant NOW_MICROS = Instant.parse("2026-09-27T10:00:00.123456Z");

	private static final RepositoryIdentity IDENTITY = RepositoryIdentity.github("MohamedMBG", "ZeroYaml");
	private static final BranchName MAIN = new BranchName("main");
	private static final WebhookConfiguration WEBHOOK =
			new WebhookConfiguration(new SecretReference("ZEROYAML_GITHUB_WEBHOOK_SECRET"));

	private final CopyingStore store = new CopyingStore();
	private final RepositoryConnectionService service =
			new RepositoryConnectionService(store, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void connectsRepositoryAsPendingAtMicrosecondPrecision() {
		var connection = service.connect(IDENTITY, MAIN, WEBHOOK);

		assertEquals(ConnectionStatus.PENDING, connection.status());
		assertEquals(NOW_MICROS, connection.connectedAt());
		var stored = service.find(IDENTITY).orElseThrow();
		assertEquals(NOW_MICROS, stored.connectedAt());
		assertEquals(MAIN, stored.defaultBranch());
	}

	@Test
	void rejectsConnectingTheSameRepositoryTwice() {
		service.connect(IDENTITY, MAIN, WEBHOOK);

		assertThrows(DuplicateRepositoryConnectionException.class,
				() -> service.connect(RepositoryIdentity.github("mohamedmbg", "zeroyaml"), MAIN, WEBHOOK));
	}

	@Test
	void recordsLifecycleTransitionsWithTheClockTime() {
		service.connect(IDENTITY, MAIN, WEBHOOK);

		assertEquals(ConnectionStatus.ACTIVE, service.activate(IDENTITY).status());
		assertEquals(ConnectionStatus.SUSPENDED, service.suspend(IDENTITY).status());
		assertEquals(ConnectionStatus.DISCONNECTED, service.disconnect(IDENTITY).status());

		var stored = service.find(IDENTITY).orElseThrow();
		assertEquals(ConnectionStatus.DISCONNECTED, stored.status());
		assertEquals(NOW_MICROS, stored.statusChangedAt());
	}

	@Test
	void recordsDefaultBranchAndWebhookChanges() {
		service.connect(IDENTITY, MAIN, WEBHOOK);
		var trunk = new BranchName("trunk");
		var rotated = new WebhookConfiguration(new SecretReference("ZEROYAML_GITHUB_WEBHOOK_SECRET_V2"));

		service.changeDefaultBranch(IDENTITY, trunk);
		service.reconfigureWebhook(IDENTITY, rotated);

		var stored = service.find(IDENTITY).orElseThrow();
		assertEquals(trunk, stored.defaultBranch());
		assertEquals(rotated, stored.webhook());
	}

	@Test
	void invalidTransitionLeavesTheRecordUnchanged() {
		service.connect(IDENTITY, MAIN, WEBHOOK);

		assertThrows(InvalidConnectionTransitionException.class, () -> service.suspend(IDENTITY));

		assertEquals(ConnectionStatus.PENDING, service.find(IDENTITY).orElseThrow().status());
	}

	@Test
	void changesToDisconnectedConnectionAreRejected() {
		service.connect(IDENTITY, MAIN, WEBHOOK);
		service.disconnect(IDENTITY);

		assertThrows(IllegalStateException.class, () -> service.changeDefaultBranch(IDENTITY, new BranchName("trunk")));
		assertThrows(InvalidConnectionTransitionException.class, () -> service.activate(IDENTITY));
	}

	@Test
	void operationsOnRepositoryThatIsNotConnectedFail() {
		assertTrue(service.find(IDENTITY).isEmpty());

		var notFound = assertThrows(RepositoryConnectionNotFoundException.class, () -> service.activate(IDENTITY));
		assertEquals(IDENTITY, notFound.getIdentity());
		assertThrows(RepositoryConnectionNotFoundException.class,
				() -> service.changeDefaultBranch(IDENTITY, new BranchName("trunk")));
		assertThrows(RepositoryConnectionNotFoundException.class,
				() -> service.reconfigureWebhook(IDENTITY, WEBHOOK));
	}

	@Test
	void returnedConnectionIsASnapshot() {
		var connection = service.connect(IDENTITY, MAIN, WEBHOOK);

		connection.changeDefaultBranch(new BranchName("trunk"));

		var stored = service.find(IDENTITY).orElseThrow();
		assertNotSame(connection, stored);
		assertEquals(MAIN, stored.defaultBranch());
	}

	/**
	 * Store double with the detached-copy and write-nothing-on-failure
	 * semantics of the {@link RepositoryConnectionStore} contract. The PostgreSQL
	 * behavior itself is covered by {@link JdbcRepositoryConnectionStoreTest}.
	 */
	private static final class CopyingStore implements RepositoryConnectionStore {

		private final Map<RepositoryIdentity, RepositoryConnection> connections = new HashMap<>();

		@Override
		public void add(RepositoryConnection connection) {
			if (connections.putIfAbsent(connection.identity(), copy(connection)) != null) {
				throw new DuplicateRepositoryConnectionException(connection.identity());
			}
		}

		@Override
		public Optional<RepositoryConnection> find(RepositoryIdentity identity) {
			return Optional.ofNullable(connections.get(identity)).map(CopyingStore::copy);
		}

		@Override
		public <R> Optional<R> update(RepositoryIdentity identity, Function<RepositoryConnection, R> change) {
			var recorded = connections.get(identity);
			if (recorded == null) {
				return Optional.empty();
			}
			var working = copy(recorded);
			var result = change.apply(working);
			connections.put(identity, copy(working));
			return Optional.of(result);
		}

		private static RepositoryConnection copy(RepositoryConnection connection) {
			return RepositoryConnection.restore(
					connection.identity(),
					connection.defaultBranch(),
					connection.webhook(),
					connection.status(),
					connection.connectedAt(),
					connection.statusChangedAt());
		}
	}
}
