package io.zeroyaml.controlplane;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL container shared by every test that needs the Control Plane
 * database. Import it with
 * {@code @ImportTestcontainers(PostgresTestContainer.class)}; the
 * {@link ServiceConnection} replaces the configured datasource, so tests never
 * reach a developer database.
 *
 * <p>The image tag matches {@code infra/compose.yaml}, so the tests exercise
 * the same PostgreSQL major version as local development. The container is
 * started once per test JVM and reused by every context that imports it.</p>
 */
public interface PostgresTestContainer {

	@Container
	@ServiceConnection
	PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
}
