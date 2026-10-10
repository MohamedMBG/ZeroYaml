package io.zeroyaml.controlplane.registration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RunnerRegistrationServerPropertiesTest {

	@Test
	void acceptsTheDefaults() {
		var properties = new RunnerRegistrationServerProperties();

		assertDoesNotThrow(properties::validate);
		assertEquals(Duration.ofMinutes(10), properties.getUnavailableRetention());
	}

	@Test
	void rejectsAnUnavailableRetentionThatIsNotPositive() {
		for (var retention : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
			var properties = new RunnerRegistrationServerProperties();
			properties.setUnavailableRetention(retention);

			var failure = assertThrows(IllegalStateException.class, properties::validate);

			assertTrue(failure.getMessage().contains("zeroyaml.registration.unavailable-retention"));
		}
	}

	@Test
	void rejectsAHeartbeatTimeoutThatIsNotPositive() {
		var properties = new RunnerRegistrationServerProperties();
		properties.setHeartbeatTimeout(Duration.ZERO);

		var failure = assertThrows(IllegalStateException.class, properties::validate);

		assertTrue(failure.getMessage().contains("zeroyaml.registration.heartbeat-timeout"));
	}
}
