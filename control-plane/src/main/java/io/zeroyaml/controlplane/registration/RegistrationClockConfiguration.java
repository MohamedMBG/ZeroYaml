package io.zeroyaml.controlplane.registration;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link Clock} the registration package uses to time liveness.
 * Kept as an injectable bean, rather than {@code Clock.systemUTC()} called
 * directly, so heartbeat timeout expiry can be tested deterministically with
 * a fixed or mutable clock instead of a real sleep.
 */
@Configuration
class RegistrationClockConfiguration {

	@Bean
	Clock registrationClock() {
		return Clock.systemUTC();
	}
}
