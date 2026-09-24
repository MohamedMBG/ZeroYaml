package io.zeroyaml.controlplane;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * The webhook secret has no default, so the context only starts when one is
 * supplied. The test provides a throwaway value; real deployments read it from
 * the environment.
 */
@SpringBootTest
@TestPropertySource(properties = "zeroyaml.github.webhook.secret=context-load-test-secret")
class ControlPlaneApplicationTests {

	@Test
	void contextLoads() {
	}

}
