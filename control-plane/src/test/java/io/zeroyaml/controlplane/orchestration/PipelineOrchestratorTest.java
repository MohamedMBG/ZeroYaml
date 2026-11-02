package io.zeroyaml.controlplane.orchestration;

import static io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.RUNNER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.zeroyaml.controlplane.domain.event.RepositoryPushEvent;
import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.job.JobId;
import io.zeroyaml.controlplane.domain.job.JobStatus;
import io.zeroyaml.controlplane.domain.job.RepositoryReference;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;
import io.zeroyaml.controlplane.domain.pipeline.inference.RepositorySnapshot;
import io.zeroyaml.controlplane.domain.repository.BranchName;
import io.zeroyaml.controlplane.domain.repository.CommitSha;
import io.zeroyaml.controlplane.domain.repository.RepositoryConnection;
import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;
import io.zeroyaml.controlplane.domain.repository.SecretReference;
import io.zeroyaml.controlplane.domain.repository.WebhookConfiguration;
import io.zeroyaml.controlplane.execution.ExecutionTestSupport;
import io.zeroyaml.controlplane.execution.JobExecutionFailureReason;
import io.zeroyaml.controlplane.execution.JobExecutionStatusRecorder;
import io.zeroyaml.controlplane.execution.JobStatusReport;
import io.zeroyaml.controlplane.execution.JobStatusReportDecision;
import io.zeroyaml.controlplane.execution.JobStore;
import io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.FixedRunnerSelector;
import io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.InlineExecutor;
import io.zeroyaml.controlplane.orchestration.OrchestrationTestDoubles.RecordingRunnerClient;

/**
 * Drives the first supported scenario through the Control Plane: a push for a
 * connected Maven repository produces ordered Jobs, each step is dispatched to
 * the assigned Runner only after the previous one succeeded, and Runner reports
 * bring every Job to its terminal state. Only the repository provider, Runner
 * selection, and the Runner transport are replaced.
 */
class PipelineOrchestratorTest {

	private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
	private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.github("acme", "app");
	private static final CommitSha REVISION = new CommitSha("0123456789abcdef0123456789abcdef01234567");
	private static final URI LOCATION = URI.create("https://github.com/acme/app.git");
	private static final RepositoryReference SOURCE = new RepositoryReference(LOCATION, REVISION.value());
	private static final String BUILD = "sh mvnw --batch-mode -DskipTests package";
	private static final String TEST = "sh mvnw --batch-mode test";

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final JobStore jobs = ExecutionTestSupport.inMemoryJobStore();
	private final FixedRunnerSelector selector = new FixedRunnerSelector();
	private final RecordingRunnerClient runner = new RecordingRunnerClient();
	private final StubRepositorySource source = new StubRepositorySource();
	private final Map<RepositoryIdentity, RepositoryConnection> connections = new HashMap<>();
	private final InlineExecutor worker = new InlineExecutor();
	private final PipelineOrchestrator orchestrator = new PipelineOrchestrator(
			identity -> Optional.ofNullable(connections.get(identity)),
			source,
			ExecutionTestSupport.jobCreation(jobs, clock),
			new PipelineProgression(jobs, selector, runner, clock),
			worker);
	private final JobExecutionStatusRecorder recorder =
			ExecutionTestSupport.statusRecorder(jobs, orchestrator);

	@Test
	void runsBuildThenTestOnTheAssignedRunnerAndRecordsSuccess() {
		connect(true);
		source.paths = Set.of("pom.xml", "mvnw", "README.md");

		orchestrator.handle(push("delivery-1"));

		assertEquals(List.of(BUILD), runner.dispatchedCommands());
		var build = runner.dispatched.getFirst();
		assertEquals(SOURCE, build.repository());
		assertEquals(JobStatus.QUEUED, job(build.id()).status());
		assertEquals(RUNNER, job(build.id()).runnerAssignment().orElseThrow());

		succeed(build.id());

		assertEquals(List.of(BUILD, TEST), runner.dispatchedCommands());
		var test = runner.dispatched.get(1);
		succeed(test.id());

		assertEquals(JobStatus.SUCCEEDED, job(build.id()).status());
		assertEquals(JobStatus.SUCCEEDED, job(test.id()).status());
		assertEquals(0, job(test.id()).exit().getAsInt());
		assertEquals(2, runner.dispatched.size());
	}

	@Test
	void aFailingCommandFailsItsJobWithTheReasonAndCancelsTheLaterSteps() {
		connect(true);
		source.paths = Set.of("pom.xml", "mvnw");
		orchestrator.handle(push("delivery-1"));
		var build = runner.dispatched.getFirst().id();

		recorder.record(JobStatusReport.running(build, RUNNER, NOW.plusSeconds(1)));
		recorder.record(JobStatusReport.failed(build, RUNNER, NOW.plusSeconds(1), NOW.plusSeconds(2), 1,
				JobExecutionFailureReason.NON_ZERO_EXIT, "job command exited with status 1"));

		var failed = job(build);
		assertEquals(JobStatus.FAILED, failed.status());
		assertEquals("NON_ZERO_EXIT", failed.failure().orElseThrow().code());
		assertEquals("job command exited with status 1", failed.failure().orElseThrow().message());
		assertEquals(1, failed.exit().getAsInt());
		assertEquals(JobStatus.CANCELLED, job(stepJob("test")).status());
		assertEquals(1, runner.dispatched.size());
	}

	@Test
	void usesTheMavenCommandWhenTheRepositoryHasNoWrapper() {
		connect(true);
		source.paths = Set.of("pom.xml");

		orchestrator.handle(push("delivery-1"));

		assertEquals(List.of("mvn --batch-mode -DskipTests package"), runner.dispatchedCommands());
	}

	@Test
	void aRedeliveredPushNeitherRecordsNorDispatchesAgain() {
		connect(true);
		source.paths = Set.of("pom.xml", "mvnw");

		orchestrator.handle(push("delivery-1"));
		orchestrator.handle(push("delivery-1"));

		assertEquals(1, runner.dispatched.size());
		assertEquals(2, jobs.findBySource(SOURCE).size());
	}

	@Test
	void aReportFromAnotherRunnerProcessCannotClaimTheDispatchedJob() {
		connect(true);
		source.paths = Set.of("pom.xml", "mvnw");
		orchestrator.handle(push("delivery-1"));
		var build = runner.dispatched.getFirst().id();

		var outcome = recorder.record(JobStatusReport.succeeded(
				build, new RunnerAssignment("runner-2", "instance-9"), NOW.plusSeconds(1), NOW.plusSeconds(2), 0));

		assertEquals(JobStatusReportDecision.CONFLICT, outcome.decision());
		assertEquals(JobStatus.QUEUED, job(build).status());
		assertEquals(RUNNER, job(build).runnerAssignment().orElseThrow());
		assertEquals(1, runner.dispatched.size());
	}

	@Test
	void ignoresAPushForARepositoryThatIsNotConnectedOrNotActive() {
		source.paths = Set.of("pom.xml");
		orchestrator.handle(push("delivery-1"));

		connect(false);
		orchestrator.handle(push("delivery-2"));

		assertEquals(0, source.reads.get());
		assertTrue(jobs.findBySource(SOURCE).isEmpty());
		assertTrue(runner.dispatched.isEmpty());
	}

	@Test
	void recordsNoJobForAnUnsupportedOrUnreadableRepository() {
		connect(true);
		source.paths = Set.of("package.json");
		orchestrator.handle(push("delivery-1"));

		source.unavailable = true;
		assertDoesNotThrow(() -> orchestrator.handle(push("delivery-2")));

		assertTrue(jobs.findBySource(SOURCE).isEmpty());
		assertTrue(runner.dispatched.isEmpty());
	}

	@Test
	void refusesAPushWhenItCannotTakeMoreWorkButNeverFailsAStatusReport() {
		worker.shutdown();

		assertThrows(RejectedExecutionException.class, () -> orchestrator.handle(push("delivery-1")));
		assertDoesNotThrow(() -> orchestrator.jobCompleted(JobId.newId()));
	}

	private void succeed(JobId id) {
		assertEquals(JobStatusReportDecision.APPLIED,
				recorder.record(JobStatusReport.running(id, RUNNER, NOW.plusSeconds(1))).decision());
		assertEquals(JobStatusReportDecision.APPLIED, recorder.record(
				JobStatusReport.succeeded(id, RUNNER, NOW.plusSeconds(1), NOW.plusSeconds(2), 0)).decision());
	}

	private Job job(JobId id) {
		return jobs.find(id).orElseThrow();
	}

	private JobId stepJob(String stepName) {
		return jobs.findBySource(SOURCE).stream()
				.filter(reference -> reference.stepName().equals(stepName))
				.findFirst().orElseThrow().jobId();
	}

	private void connect(boolean active) {
		var connection = RepositoryConnection.connect(REPOSITORY, new BranchName("main"),
				new WebhookConfiguration(new SecretReference("ZEROYAML_GITHUB_WEBHOOK_SECRET")), NOW.minusSeconds(60));
		if (active) {
			connection.activate(NOW.minusSeconds(30));
		}
		connections.put(REPOSITORY, connection);
	}

	private static RepositoryPushEvent push(String deliveryId) {
		return new RepositoryPushEvent(REPOSITORY, new BranchName("main"), REVISION, deliveryId, NOW);
	}

	private static final class StubRepositorySource implements RepositorySource {

		final AtomicInteger reads = new AtomicInteger();
		volatile Set<String> paths = Set.of();
		volatile boolean unavailable;

		@Override
		public URI location(RepositoryIdentity repository) {
			return URI.create("https://github.com/" + repository.fullName() + ".git");
		}

		@Override
		public RepositorySnapshot rootSnapshot(RepositoryIdentity repository, CommitSha revision) {
			reads.incrementAndGet();
			if (unavailable) {
				throw new RepositorySnapshotUnavailableException("GitHub API answered status 500");
			}
			return new RepositorySnapshot(paths);
		}
	}
}
