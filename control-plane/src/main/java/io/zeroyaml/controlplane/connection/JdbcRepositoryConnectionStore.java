package io.zeroyaml.controlplane.connection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import io.zeroyaml.controlplane.domain.repository.BranchName;
import io.zeroyaml.controlplane.domain.repository.ConnectionStatus;
import io.zeroyaml.controlplane.domain.repository.RepositoryConnection;
import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;
import io.zeroyaml.controlplane.domain.repository.RepositoryProvider;
import io.zeroyaml.controlplane.domain.repository.SecretReference;
import io.zeroyaml.controlplane.domain.repository.WebhookConfiguration;

/**
 * PostgreSQL-backed {@link RepositoryConnectionStore}.
 *
 * <p>The table is created by the Flyway migration
 * {@code V1__create_repository_connection.sql}. Duplicate identities are
 * rejected by the {@code repository_connection_identity_key} unique constraint
 * through {@code ON CONFLICT DO NOTHING}, so two concurrent connects of the
 * same repository cannot both succeed, and neither sees a constraint error
 * that depends on timing.</p>
 *
 * <p>Timestamps are stored at microsecond precision, the resolution of
 * PostgreSQL {@code timestamptz}. They are truncated before writing, never
 * rounded, so a stored value never moves later than the one the domain
 * produced and the lifecycle ordering survives the round trip.</p>
 *
 * <p>Rows are validated through the domain types when read, so a row edited
 * outside the Control Plane into an invalid state fails loudly instead of
 * loading.</p>
 */
@Repository
class JdbcRepositoryConnectionStore implements RepositoryConnectionStore {

	private static final String SELECT_COLUMNS = """
			SELECT provider, owner, name, default_branch, webhook_secret_reference,
			       status, connected_at, status_changed_at
			FROM repository_connection
			WHERE provider = :provider AND owner = :owner AND name = :name
			""";

	private final JdbcClient jdbc;
	private final TransactionTemplate transactions;

	JdbcRepositoryConnectionStore(JdbcClient jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	@Override
	public void add(RepositoryConnection connection) {
		Objects.requireNonNull(connection, "connection must not be null");

		int inserted = jdbc.sql("""
						INSERT INTO repository_connection (
						    provider, owner, name, default_branch, webhook_secret_reference,
						    status, connected_at, status_changed_at)
						VALUES (
						    :provider, :owner, :name, :defaultBranch, :webhookSecretReference,
						    :status, :connectedAt, :statusChangedAt)
						ON CONFLICT ON CONSTRAINT repository_connection_identity_key DO NOTHING
						""")
				.param("provider", connection.identity().provider().name())
				.param("owner", connection.identity().owner())
				.param("name", connection.identity().name())
				.param("defaultBranch", connection.defaultBranch().value())
				.param("webhookSecretReference", connection.webhook().signingSecret().name())
				.param("status", connection.status().name())
				.param("connectedAt", toColumn(connection.connectedAt()))
				.param("statusChangedAt", toColumn(connection.statusChangedAt()))
				.update();

		if (inserted == 0) {
			throw new DuplicateRepositoryConnectionException(connection.identity());
		}
	}

	@Override
	public Optional<RepositoryConnection> find(RepositoryIdentity identity) {
		Objects.requireNonNull(identity, "identity must not be null");

		return jdbc.sql(SELECT_COLUMNS)
				.params(identityParameters(identity))
				.query(JdbcRepositoryConnectionStore::toConnection)
				.optional();
	}

	@Override
	public <R> Optional<R> update(RepositoryIdentity identity, Function<RepositoryConnection, R> change) {
		Objects.requireNonNull(identity, "identity must not be null");
		Objects.requireNonNull(change, "change must not be null");

		// FOR UPDATE holds the row lock until the transaction ends, which is what
		// serializes concurrent updates of one connection across Control Plane
		// instances. Updates of different connections lock different rows.
		Optional<R> result = transactions.execute(transaction -> {
			var locked = jdbc.sql(SELECT_COLUMNS + "FOR UPDATE")
					.params(identityParameters(identity))
					.query(JdbcRepositoryConnectionStore::toConnection)
					.optional();
			if (locked.isEmpty()) {
				return Optional.<R>empty();
			}

			var connection = locked.get();
			R changeResult = Objects.requireNonNull(change.apply(connection), "change must not return null");

			jdbc.sql("""
							UPDATE repository_connection
							SET default_branch = :defaultBranch,
							    webhook_secret_reference = :webhookSecretReference,
							    status = :status,
							    status_changed_at = :statusChangedAt
							WHERE provider = :provider AND owner = :owner AND name = :name
							""")
					.params(identityParameters(identity))
					.param("defaultBranch", connection.defaultBranch().value())
					.param("webhookSecretReference", connection.webhook().signingSecret().name())
					.param("status", connection.status().name())
					.param("statusChangedAt", toColumn(connection.statusChangedAt()))
					.update();

			return Optional.of(changeResult);
		});

		return Objects.requireNonNull(result, "transaction produced no result");
	}

	private static Map<String, Object> identityParameters(RepositoryIdentity identity) {
		return Map.of(
				"provider", identity.provider().name(),
				"owner", identity.owner(),
				"name", identity.name());
	}

	private static OffsetDateTime toColumn(Instant instant) {
		return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
	}

	private static RepositoryConnection toConnection(ResultSet row, int rowNumber) throws SQLException {
		return RepositoryConnection.restore(
				new RepositoryIdentity(
						RepositoryProvider.valueOf(row.getString("provider")),
						row.getString("owner"),
						row.getString("name")),
				new BranchName(row.getString("default_branch")),
				new WebhookConfiguration(new SecretReference(row.getString("webhook_secret_reference"))),
				ConnectionStatus.valueOf(row.getString("status")),
				row.getObject("connected_at", OffsetDateTime.class).toInstant(),
				row.getObject("status_changed_at", OffsetDateTime.class).toInstant());
	}
}
