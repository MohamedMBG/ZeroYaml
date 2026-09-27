package io.zeroyaml.controlplane;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.TestPropertySource;

/**
 * The webhook secret has no default, so the context only starts when one is
 * supplied. The test provides a throwaway value; real deployments read it from
 * the environment.
 *
 * <p>Startup runs the Flyway migrations, so the context is loaded against a
 * disposable PostgreSQL container.</p>
 */
@SpringBootTest
@ImportTestcontainers(PostgresTestContainer.class)
@TestPropertySource(properties = "zeroyaml.github.webhook.secret=context-load-test-secret")
class ControlPlaneApplicationTests {

	@Test
	void contextLoads() {
	}

}
