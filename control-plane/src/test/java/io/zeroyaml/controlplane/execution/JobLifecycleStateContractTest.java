package io.zeroyaml.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.zeroyaml.contracts.runner.v1.JobLifecycleState;
import io.zeroyaml.controlplane.domain.job.JobStatus;

/**
 * Proves that the Job lifecycle of execution model v1 and the {@code runner.v1}
 * {@code JobLifecycleState} enum describe the same states, so a state added on
 * one side without the other fails the build. The Runner holds the matching
 * check against the same enum.
 */
class JobLifecycleStateContractTest {

	@ParameterizedTest
	@EnumSource(JobStatus.class)
	void mapsEveryJobStatusToTheWireStateOfTheSameName(JobStatus status) {
		assertEquals("JOB_LIFECYCLE_" + status.name(), GrpcJobExecutionStatusService.toProtoState(status).name());
	}

	@Test
	void reachesEveryDefinedWireState() {
		var defined = EnumSet.allOf(JobLifecycleState.class);
		defined.remove(JobLifecycleState.JOB_LIFECYCLE_STATE_UNSPECIFIED);
		defined.remove(JobLifecycleState.UNRECOGNIZED);

		var mapped = EnumSet.noneOf(JobLifecycleState.class);
		for (var status : JobStatus.values()) {
			mapped.add(GrpcJobExecutionStatusService.toProtoState(status));
		}

		assertEquals(defined, mapped);
	}
}
