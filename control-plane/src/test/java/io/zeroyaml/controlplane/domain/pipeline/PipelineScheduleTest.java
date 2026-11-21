package io.zeroyaml.controlplane.domain.pipeline;

import static io.zeroyaml.controlplane.domain.job.JobStatus.CANCELLED;
import static io.zeroyaml.controlplane.domain.job.JobStatus.CREATED;
import static io.zeroyaml.controlplane.domain.job.JobStatus.FAILED;
import static io.zeroyaml.controlplane.domain.job.JobStatus.QUEUED;
import static io.zeroyaml.controlplane.domain.job.JobStatus.RUNNING;
import static io.zeroyaml.controlplane.domain.job.JobStatus.SKIPPED;
import static io.zeroyaml.controlplane.domain.job.JobStatus.SUCCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.zeroyaml.controlplane.domain.job.JobStatus;
import io.zeroyaml.controlplane.domain.pipeline.PipelineSchedule.StepDecision;
import io.zeroyaml.controlplane.domain.pipeline.PipelineSchedule.StepState;

class PipelineScheduleTest {

	private static final StepDecision READY = new StepDecision.Ready();
	private static final StepDecision RUNNING_DECISION = new StepDecision.Running();

	@Test
	void stepsWithoutDependenciesAreReadyTogether() {
		var schedule = PipelineSchedule.of(List.of(
				step("lint", CREATED),
				step("build", CREATED),
				step("docs", CREATED)));

		assertEquals(List.of(READY, READY, READY), schedule.decisions());
		assertFalse(schedule.isComplete());
	}

	@Test
	void aStepWaitsUntilEveryDependencySucceeded() {
		// build -> (unit, integration) -> package: a diamond.
		var waiting = PipelineSchedule.of(diamond(SUCCEEDED, SUCCEEDED, RUNNING, CREATED));
		assertEquals(List.of(settled(SUCCEEDED), settled(SUCCEEDED), RUNNING_DECISION,
				blocked("integration")), waiting.decisions());

		var released = PipelineSchedule.of(diamond(SUCCEEDED, SUCCEEDED, SUCCEEDED, CREATED));
		assertEquals(READY, released.decisions().get(3));
	}

	@Test
	void namesEveryPendingDependencyInDependencyOrder() {
		var schedule = PipelineSchedule.of(diamond(CREATED, CREATED, CREATED, CREATED));

		assertEquals(List.of(READY, blocked("build"), blocked("build"), blocked("unit", "integration")),
				schedule.decisions());
	}

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = { "FAILED", "CANCELLED", "SKIPPED" })
	void aDependencyThatEndedWithoutSuccessSkipsItsDependentsTransitively(JobStatus outcome) {
		var schedule = PipelineSchedule.of(diamond(outcome, CREATED, CREATED, CREATED));

		assertEquals(List.of(settled(outcome), skip("build"), skip("build"), skip("unit", "integration")),
				schedule.decisions());
		assertTrue(schedule.isComplete());
	}

	@Test
	void aFailureSkipsOnlyItsDependentsAndLeavesIndependentStepsRunnable() {
		var schedule = PipelineSchedule.of(List.of(
				step("build", FAILED),
				step("lint", CREATED),
				step("test", CREATED, "build"),
				step("report", CREATED, "lint")));

		assertEquals(List.of(settled(FAILED), READY, skip("build"), blocked("lint")), schedule.decisions());
		assertFalse(schedule.isComplete());
	}

	@Test
	void aStepWithOneFailedAndOnePendingDependencyIsSkippedAtOnce() {
		var schedule = PipelineSchedule.of(diamond(SUCCEEDED, FAILED, RUNNING, CREATED));

		assertEquals(skip("unit"), schedule.decisions().get(3));
	}

	@Test
	void reportsQueuedRunningAndTerminalStepsAsTheyAre() {
		var schedule = PipelineSchedule.of(List.of(
				step("queued", QUEUED),
				step("running", RUNNING),
				step("succeeded", SUCCEEDED),
				step("failed", FAILED),
				step("cancelled", CANCELLED),
				step("skipped", SKIPPED)));

		assertEquals(List.of(READY, RUNNING_DECISION, settled(SUCCEEDED), settled(FAILED), settled(CANCELLED),
				settled(SKIPPED)), schedule.decisions());
		assertFalse(schedule.isComplete());
	}

	@Test
	void aQueuedStepStaysReadyWhateverItsDependenciesShow() {
		// Queuing happens only after the dependencies succeeded, so a queued Job is never revisited.
		var schedule = PipelineSchedule.of(List.of(step("build", FAILED), step("test", QUEUED, "build")));

		assertEquals(READY, schedule.decisions().get(1));
	}

	@Test
	void aDependencyThatNamesNoRecordedStepCanNeverSucceed() {
		var schedule = PipelineSchedule.of(List.of(
				step("build", SUCCEEDED),
				step("test", CREATED, "build", "missing")));

		assertEquals(skip("missing"), schedule.decisions().get(1));
	}

	@Test
	void aStepRecordedTwiceSatisfiesADependencyOnlyWhenEveryRecordSucceeded() {
		var pending = PipelineSchedule.of(List.of(
				step("build", SUCCEEDED),
				step("build", RUNNING),
				step("test", CREATED, "build")));
		assertEquals(blocked("build"), pending.decisions().get(2));

		var failed = PipelineSchedule.of(List.of(
				step("build", SUCCEEDED),
				step("build", FAILED),
				step("test", CREATED, "build")));
		assertEquals(skip("build"), failed.decisions().get(2));

		var succeeded = PipelineSchedule.of(List.of(
				step("build", SUCCEEDED),
				step("build", SUCCEEDED),
				step("test", CREATED, "build")));
		assertEquals(READY, succeeded.decisions().get(2));
	}

	@Test
	void skippingPropagatesWhateverOrderTheStepsAreSuppliedIn() {
		var schedule = PipelineSchedule.of(List.of(
				step("package", CREATED, "test"),
				step("test", CREATED, "build"),
				step("build", FAILED)));

		assertEquals(List.of(skip("test"), skip("build"), settled(FAILED)), schedule.decisions());
	}

	@Test
	void theSameRecordedStateAlwaysGivesTheSameDecisions() {
		var steps = diamond(SUCCEEDED, CREATED, CREATED, CREATED);

		assertEquals(PipelineSchedule.of(steps).decisions(), PipelineSchedule.of(new ArrayList<>(steps)).decisions());
	}

	@Test
	void aRunIsCompleteOnceEveryStepIsSettledOrSkipped() {
		assertTrue(PipelineSchedule.of(diamond(SUCCEEDED, SUCCEEDED, SUCCEEDED, SUCCEEDED)).isComplete());
		assertTrue(PipelineSchedule.of(diamond(SUCCEEDED, FAILED, SUCCEEDED, CREATED)).isComplete());
		assertFalse(PipelineSchedule.of(diamond(SUCCEEDED, FAILED, RUNNING, CREATED)).isComplete());
		assertFalse(PipelineSchedule.of(diamond(SUCCEEDED, SUCCEEDED, QUEUED, CREATED)).isComplete());
		assertTrue(PipelineSchedule.of(List.of()).isComplete());
	}

	@Test
	void copiesItsInputAndRejectsMissingValues() {
		var dependencies = new ArrayList<>(List.of("build"));
		var state = new StepState("test", dependencies, CREATED);
		dependencies.add("lint");

		assertEquals(List.of("build"), state.dependsOn());
		assertThrows(UnsupportedOperationException.class,
				() -> PipelineSchedule.of(List.of(step("build", CREATED))).decisions().add(READY));
		assertThrows(NullPointerException.class, () -> PipelineSchedule.of(null));
		assertThrows(NullPointerException.class, () -> new StepState(null, List.of(), CREATED));
		assertThrows(NullPointerException.class, () -> new StepState("build", null, CREATED));
		assertThrows(NullPointerException.class, () -> new StepState("build", List.of(), null));
	}

	private static List<StepState> diamond(JobStatus build, JobStatus unit, JobStatus integration,
			JobStatus packaging) {
		return List.of(
				step("build", build),
				step("unit", unit, "build"),
				step("integration", integration, "build"),
				step("package", packaging, "unit", "integration"));
	}

	private static StepState step(String name, JobStatus status, String... dependsOn) {
		return new StepState(name, List.of(dependsOn), status);
	}

	private static StepDecision blocked(String... waitingOn) {
		return new StepDecision.Blocked(List.of(waitingOn));
	}

	private static StepDecision skip(String... unsuccessful) {
		return new StepDecision.Skip(List.of(unsuccessful));
	}

	private static StepDecision settled(JobStatus status) {
		return new StepDecision.Settled(status);
	}
}
