package io.zeroyaml.controlplane.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.zeroyaml.controlplane.PostgresTestContainer;
import io.zeroyaml.controlplane.domain.repository.BranchName;
import io.zeroyaml.controlplane.domain.repository.ConnectionStatus;
import io.zeroyaml.controlplane.domain.repository.InvalidConnectionTransitionException;
import io.zeroyaml.controlplane.domain.repository.RepositoryConnection;
import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;
import io.zeroyaml.controlplane.domain.repository.SecretReference;
import io.zeroyaml.controlplane.domain.repository.WebhookConfiguration;

/**
 * Runs the adapter against a real PostgreSQL schema created by the Flyway
 * migrations.
 *
 * <p>Test-managed transactions are disabled so every store call commits like it
 * does in production. That is required to observe row locking and the unique
 * constraint across concurrent transactions; the table is emptied before each
 * test instead of relying on rollback.</p>
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportTestcontainers(PostgresTestContainer.class)
@Import(JdbcRepositoryConnectionStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcRepositoryConnectionStoreTest {

	private static final Instant CONNECTED_AT = Instant.parse("2026-09-27T10:00:00.123456Z");
	private static final Instant ACTIVATED_AT = Instant.parse("2026-09-27T10:01:00Z");
	private static final Instant SUSPENDED_AT = Instant.parse("2026-09-27T10:02:00Z");

	private static final RepositoryIdentity IDENTITY = RepositoryIdentity.github("MohamedMBG", "ZeroYaml");
	private static final BranchName MAIN = new BranchName("main");
	private static final WebhookConfiguration WEBHOOK =
			new WebhookConfiguration(new SecretReference("ZEROYAML_GITHUB_WEBHOOK_SECRET"));

	@Autowired
	private JdbcRepositoryConnectionStore store;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void emptyTable() {
		jdbc.sql("DELETE FROM repository_connection").update();
	}

	@Test
	void createsAndReadsBackConnection() {
		store.add(RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT));

		var stored = store.find(IDENTITY).orElseThrow();

		assertEquals(IDENTITY, stored.identity());
		assertEquals(MAIN, stored.defaultBranch());
		assertEquals(WEBHOOK, stored.webhook());
		assertEquals(ConnectionStatus.PENDING, stored.status());
		assertEquals(CONNECTED_AT, stored.connectedAt());
		assertEquals(CONNECTED_AT, stored.statusChangedAt());
	}

	@Test
	void findsConnectionByAnySpellingOfItsIdentity() {
		store.add(RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT));

		assertTrue(store.find(RepositoryIdentity.github("mohamedmbg", "ZEROYAML")).isPresent());
	}

	@Test
	void returnsEmptyForRepositoryThatIsNotConnected() {
		assertTrue(store.find(IDENTITY).isEmpty());
	}

	@Test
	void storesOnlySecretReferenceAndCanonicalIdentity() {
		store.add(RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT));

		var row = jdbc.sql("SELECT owner, name, webhook_secret_reference FROM repository_connection")
				.query((result, rowNumber) -> List.of(
						result.getString("owner"),
						result.getString("name"),
						result.getString("webhook_secret_reference")))
				.single();

		assertEquals(List.of("mohamedmbg", "zeroyaml", "ZEROYAML_GITHUB_WEBHOOK_SECRET"), row);
	}

	@Test
	void truncatesTimestampsToStoredMicrosecondPrecision() {
		var nanosecondTime = Instant.parse("2026-09-27T10:00:00.123456789Z");

		store.add(RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, nanosecondTime));

		assertEquals(Instant.parse("2026-09-27T10:00:00.123456Z"), store.find(IDENTITY).orElseThrow().connectedAt());
	}

	@Test
	void rejectsDuplicateIdentityAndKeepsTheFirstConnection() {
		store.add(RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT));
		var sameRepositoryOtherSpelling = RepositoryConnection.connect(
				RepositoryIdentity.github("mohamedmbg", "ZEROYAML"),
				new BranchName("develop"),
				new WebhookConfiguration(new SecretReference("OTHER_SECRET")),
				ACTIVATED_AT);

		var duplicate = assertThrows(DuplicateRepositoryConnectionException.class,
				() -> store.add(sameRepositoryOtherSpelling));

		assertEquals(IDENTITY, duplicate.getIdentity());
		assertEquals(1, rowCount());
		assertEquals(MAIN, store.find(IDENTITY).orElseThrow().defaultBranch());
	}

	@Test
	void concurrentConnectsOfOneRepositoryLetExactlyOneSucceed() throws Exception {
		int attempts = 4;
		var start = new CyclicBarrier(attempts);
		var executor = Executors.newFixedThreadPool(attempts);
		try {
			var outcomes = new ArrayList<Future<Boolean>>();
			for (int attempt = 0; attempt < attempts; attempt++) {
				Callable<Boolean> connect = () -> {
					start.await(10, TimeUnit.SECONDS);
					try {
						store.add(RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT));
						return true;
					}
					catch (DuplicateRepositoryConnectionException duplicate) {
						return false;
					}
				};
				outcomes.add(executor.submit(connect));
			}

			int succeeded = 0;
			for (var outcome : outcomes) {
				if (outcome.get(30, TimeUnit.SECONDS)) {
					succeeded++;
				}
			}

			assertEquals(1, succeeded);
			assertEquals(1, rowCount());
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void updatesStatusBranchAndWebhookMetadata() {
		store.add(RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT));
		var trunk = new BranchName("trunk");
		var rotated = new WebhookConfiguration(new SecretReference("ZEROYAML_GITHUB_WEBHOOK_SECRET_V2"));

		var result = store.update(IDENTITY, connection -> {
			connection.activate(ACTIVATED_AT);
			connection.changeDefaultBranch(trunk);
			connection.reconfigureWebhook(rotated);
			return connection.status();
		});

		assertEquals(ConnectionStatus.ACTIVE, result.orElseThrow());
		var stored = store.find(IDENTITY).orElseThrow();
		assertEquals(ConnectionStatus.ACTIVE, stored.status());
		assertEquals(ACTIVATED_AT, stored.statusChangedAt());
		assertEquals(CONNECTED_AT, stored.connectedAt());
		assertEquals(trunk, stored.defaultBranch());
		assertEquals(rotated, stored.webhook());
	}

	@Test
	void updateStartsFromTheRecordedState() {
		store.add(RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT));
		store.update(IDENTITY, connection -> {
			connection.activate(ACTIVATED_AT);
			return connection;
		});

		store.update(IDENTITY, connection -> {
			connection.suspend(SUSPENDED_AT);
			return connection;
		});

		assertEquals(ConnectionStatus.SUSPENDED, store.find(IDENTITY).orElseThrow().status());
	}

	@Test
	void failedChangeWritesNothing() {
		store.add(RepositoryConnection.connect(IDENTITY, MAIN, WEBHOOK, CONNECTED_AT));

		assertThrows(InvalidConnectionTransitionException.class, () -> store.update(IDENTITY, connection -> {
			connection.changeDefaultBranch(new BranchName("trunk"));
			connection.suspend(SUSPENDED_AT);
			return connection;
		}));

		var stored = store.find(IDENTITY).orElseThrow();
		assertEquals(MAIN, stored.defaultBranch());
		assertEquals(ConnectionStatus.PENDING, stored.status());
	}

	@Test
	void updateOfRepositoryThatIsNotConnectedDoesNotRunTheChange() {
		var invoked = new boolean[] {false};

		var result = store.update(IDENTITY, connection -> {
			invoked[0] = true;
			return connection;
		});

		assertTrue(result.isEmpty());
		assertFalse(invoked[0]);
	}

	@Test
	void schemaRejectsRowsTheDomainCannotProduce() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
						INSERT INTO repository_connection (
						    provider, owner, name, default_branch, webhook_secret_reference,
						    status, connected_at, status_changed_at)
						VALUES ('GITHUB', 'MohamedMBG', 'zeroyaml', 'main', 'SECRET', 'PENDING', now(), now())
						""").update());
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
						INSERT INTO repository_connection (
						    provider, owner, name, default_branch, webhook_secret_reference,
						    status, connected_at, status_changed_at)
						VALUES ('GITHUB', 'mohamedmbg', 'zeroyaml', 'main', 'SECRET', 'UNKNOWN', now(), now())
						""").update());
		assertEquals(0, rowCount());
	}

	private int rowCount() {
		return jdbc.sql("SELECT count(*) FROM repository_connection").query(Integer.class).single();
	}
}
