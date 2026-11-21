package io.zeroyaml.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.zeroyaml.controlplane.PostgresTestContainer;

/**
 * Proves that {@code V4__add_step_dependencies_and_skipped_jobs.sql} gives Jobs
 * recorded before it the strict step order they were executed in. The migration
 * runs in its own schema, so the shared test database is left untouched.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportTestcontainers(PostgresTestContainer.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StepDependencyMigrationTest {

	private static final String SCHEMA = "step_dependency_migration";

	@Autowired private DataSource dataSource;
	@Autowired private JdbcClient jdbc;

	@BeforeEach
	void migrateToTheVersionBeforeDependencies() {
		dropSchema();
		flyway("3").migrate();
	}

	@AfterEach
	void dropSchema() {
		jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
	}

	@Test
	void eachRecordedStepDependsOnEveryStepRecordedBeforeItInItsRun() {
		var run = insertRun("7c1f8f6e-1a5d-4c55-8f0f-6f1d2f3c4b5a");
		var otherRun = insertRun("9a3b7c2d-4e5f-4a1b-8c9d-0e1f2a3b4c5d");
		var build = insertJob(run, "build", 1, "00000000-0000-0000-0000-000000000001");
		var test = insertJob(run, "test", 2, "00000000-0000-0000-0000-000000000002");
		// An earlier plan recorded the same step again; it must not depend on itself.
		var replannedTest = insertJob(run, "test", 2, "00000000-0000-0000-0000-000000000003");
		var packaging = insertJob(run, "package", 3, "00000000-0000-0000-0000-000000000004");
		var alone = insertJob(otherRun, "build", 1, "00000000-0000-0000-0000-000000000005");
		var direct = insertJob(null, null, null, "00000000-0000-0000-0000-000000000006");

		flyway("4").migrate();

		assertEquals("[]", dependencies(build));
		assertEquals("[\"build\"]", dependencies(test));
		assertEquals("[\"build\"]", dependencies(replannedTest));
		assertEquals("[\"build\", \"test\"]", dependencies(packaging));
		assertEquals("[]", dependencies(alone));
		assertNull(dependencies(direct));
		assertEquals(List.of("CREATED"), jdbc.sql("SELECT DISTINCT status FROM " + SCHEMA + ".job")
				.query(String.class).list());
	}

	private Flyway flyway(String target) {
		return Flyway.configure()
				.dataSource(dataSource)
				.schemas(SCHEMA)
				.locations("classpath:db/migration")
				.target(target)
				.load();
	}

	private long insertRun(String pipelineId) {
		return jdbc.sql("INSERT INTO " + SCHEMA + ".pipeline_run (pipeline_id, repository_location, revision, created_at) "
						+ "VALUES (CAST(:pipelineId AS uuid), 'https://example.test/acme/app.git', 'abc123', now()) RETURNING id")
				.param("pipelineId", pipelineId).query(Long.class).single();
	}

	private UUID insertJob(Long runId, String stepName, Integer position, String id) {
		jdbc.sql("INSERT INTO " + SCHEMA + ".job (id, pipeline_run_id, step_name, step_position, repository_location, "
						+ "revision, command_json, working_directory, status, created_at) "
						+ "VALUES (CAST(:id AS uuid), :runId, :stepName, :position, 'https://example.test/acme/app.git', "
						+ "'abc123', '[\"make\"]'::jsonb, '.', 'CREATED', now())")
				.param("id", id)
				.param("runId", runId, Types.BIGINT)
				.param("stepName", stepName, Types.VARCHAR)
				.param("position", position, Types.INTEGER)
				.update();
		return UUID.fromString(id);
	}

	private String dependencies(UUID id) {
		return jdbc.sql("SELECT step_dependencies::text FROM " + SCHEMA + ".job WHERE id = :id")
				.param("id", id).query((row, number) -> row.getString(1)).list().getFirst();
	}
}
