package io.zeroyaml.controlplane.domain.pipeline;

import static io.zeroyaml.controlplane.domain.pipeline.PipelineStatus.CANCELLED;
import static io.zeroyaml.controlplane.domain.pipeline.PipelineStatus.FAILED;
import static io.zeroyaml.controlplane.domain.pipeline.PipelineStatus.PENDING;
import static io.zeroyaml.controlplane.domain.pipeline.PipelineStatus.RUNNING;
import static io.zeroyaml.controlplane.domain.pipeline.PipelineStatus.SUCCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PipelineStatusTest {

	/** Execution model v1 transition table, written out independently of the implementation. */
	private static final Map<PipelineStatus, Set<PipelineStatus>> ALLOWED = Map.of(
			PENDING, Set.of(RUNNING, CANCELLED),
			RUNNING, Set.of(SUCCEEDED, FAILED, CANCELLED),
			SUCCEEDED, Set.of(),
			FAILED, Set.of(),
			CANCELLED, Set.of());

	@ParameterizedTest
	@EnumSource(PipelineStatus.class)
	void allowsExactlyTheDocumentedTransitions(PipelineStatus from) {
		for (var to : PipelineStatus.values()) {
			assertEquals(ALLOWED.get(from).contains(to), from.canTransitionTo(to), from + " -> " + to);
		}
	}

	@Test
	void terminalStatesAreExactlyThoseWithoutTransitions() {
		var terminal = EnumSet.noneOf(PipelineStatus.class);
		for (var status : PipelineStatus.values()) {
			if (status.isTerminal()) {
				terminal.add(status);
			}
		}

		assertEquals(EnumSet.of(SUCCEEDED, FAILED, CANCELLED), terminal);
		terminal.forEach(status -> assertEquals(Set.of(), ALLOWED.get(status)));
	}

	@Test
	void rejectsMissingTarget() {
		assertThrows(NullPointerException.class, () -> PENDING.canTransitionTo(null));
	}
}
