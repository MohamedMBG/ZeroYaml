package io.zeroyaml.controlplane.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunnerInfoTest {

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t"})
	void rejectsABlankRunnerId(String runnerId) {
		// An empty runner_id is the protocol default, so a Runner that omits it must not be stored.
		var failure = assertThrows(IllegalArgumentException.class, () -> runner(runnerId, "instance-1"));

		assertTrue(failure.getMessage().contains("runnerId"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t"})
	void rejectsABlankInstanceId(String instanceId) {
		var failure = assertThrows(IllegalArgumentException.class, () -> runner("runner-1", instanceId));

		assertTrue(failure.getMessage().contains("instanceId"));
	}

	@Test
	void rejectsANullIdentity() {
		assertThrows(NullPointerException.class, () -> runner(null, "instance-1"));
		assertThrows(NullPointerException.class, () -> runner("runner-1", null));
	}

	@Test
	void replacesOnlyTheAvailabilityWhenALaterReportArrives() {
		var registered = runner("runner-1", "instance-1");

		var reported = registered.withAvailability(RunnerState.READY, true);

		assertEquals(RunnerState.READY, reported.state());
		assertTrue(reported.acceptingWork());
		assertEquals(registered, reported.withAvailability(registered.state(), registered.acceptingWork()));
	}

	private static RunnerInfo runner(String runnerId, String instanceId) {
		return new RunnerInfo(
				runnerId,
				instanceId,
				"0.1.0",
				"runner.v1",
				RunnerState.STARTING,
				false,
				new RunnerCapabilities("linux", "amd64", true, List.of("docker"), Map.of()));
	}
}
