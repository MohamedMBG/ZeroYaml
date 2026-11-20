package io.zeroyaml.controlplane.domain.job;

import static io.zeroyaml.controlplane.domain.job.JobStatus.CANCELLED;
import static io.zeroyaml.controlplane.domain.job.JobStatus.CREATED;
import static io.zeroyaml.controlplane.domain.job.JobStatus.FAILED;
import static io.zeroyaml.controlplane.domain.job.JobStatus.QUEUED;
import static io.zeroyaml.controlplane.domain.job.JobStatus.RUNNING;
import static io.zeroyaml.controlplane.domain.job.JobStatus.SUCCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JobStatusTest {

	/** Execution model v1 transition table, written out independently of the implementation. */
	private static final Map<JobStatus, Set<JobStatus>> ALLOWED = Map.of(
			CREATED, Set.of(QUEUED, CANCELLED),
			QUEUED, Set.of(RUNNING, CANCELLED),
			RUNNING, Set.of(SUCCEEDED, FAILED, CANCELLED),
			SUCCEEDED, Set.of(),
			FAILED, Set.of(),
			CANCELLED, Set.of());

	@ParameterizedTest
	@EnumSource(JobStatus.class)
	void allowsExactlyTheDocumentedTransitions(JobStatus from) {
		for (var to : JobStatus.values()) {
			assertEquals(ALLOWED.get(from).contains(to), from.canTransitionTo(to), from + " -> " + to);
		}
	}

	@Test
	void terminalStatesAreExactlyThoseWithoutTransitions() {
		var terminal = EnumSet.noneOf(JobStatus.class);
		for (var status : JobStatus.values()) {
			if (status.isTerminal()) {
				terminal.add(status);
			}
		}

		assertEquals(EnumSet.of(SUCCEEDED, FAILED, CANCELLED), terminal);
		terminal.forEach(status -> assertEquals(Set.of(), ALLOWED.get(status)));
	}

	@Test
	void rejectsMissingTarget() {
		assertThrows(NullPointerException.class, () -> CREATED.canTransitionTo(null));
	}
}
