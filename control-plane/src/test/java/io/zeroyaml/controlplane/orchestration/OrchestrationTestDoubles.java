package io.zeroyaml.controlplane.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.zeroyaml.controlplane.domain.job.Job;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;
import io.zeroyaml.controlplane.runner.JobDispatchResult;
import io.zeroyaml.controlplane.runner.RunnerClient;
import io.zeroyaml.controlplane.runner.RunnerInfo;
import io.zeroyaml.controlplane.runner.RunnerPingResult;
import io.zeroyaml.controlplane.scheduling.JobDispatchContext;
import io.zeroyaml.controlplane.scheduling.RunnerSelectionOutcome;
import io.zeroyaml.controlplane.scheduling.RunnerSelector;

/** Test doubles for the edges of pipeline orchestration. */
final class OrchestrationTestDoubles {

	static final RunnerAssignment RUNNER = new RunnerAssignment("runner-1", "instance-1");

	private OrchestrationTestDoubles() {
	}

	/** Selects {@link #RUNNER} unless no Runner is available. */
	static final class FixedRunnerSelector implements RunnerSelector {

		volatile boolean available = true;

		@Override
		public RunnerSelectionOutcome select(Job job) {
			return available
					? new RunnerSelectionOutcome.Selected(new JobDispatchContext(job.id(), RUNNER), "selected")
					: new RunnerSelectionOutcome.NoAvailableRunner(job.id(), "no healthy runner is registered");
		}
	}

	/** Records every dispatch and answers with a scripted result, accepting by {@link #RUNNER} by default. */
	static final class RecordingRunnerClient implements RunnerClient {

		final List<Job> dispatched = new CopyOnWriteArrayList<>();
		volatile Function<Job, JobDispatchResult> answer =
				job -> JobDispatchResult.accepted(job.id(), RUNNER, "accepted");

		@Override
		public RunnerPingResult ping(String message) {
			throw new UnsupportedOperationException();
		}

		@Override
		public RunnerInfo getInfo() {
			throw new UnsupportedOperationException();
		}

		@Override
		public JobDispatchResult dispatchJob(Job job) {
			dispatched.add(job);
			return answer.apply(job);
		}

		List<String> dispatchedCommands() {
			var commands = new ArrayList<String>();
			dispatched.forEach(job -> commands.add(String.join(" ", job.execution().command())));
			return commands;
		}
	}

	/** Runs every task on the calling thread, so a test observes its effects deterministically. */
	static final class InlineExecutor extends AbstractExecutorService {

		private volatile boolean shutdown;

		@Override
		public void execute(Runnable command) {
			if (shutdown) {
				throw new RejectedExecutionException("executor is shut down");
			}
			command.run();
		}

		@Override
		public void shutdown() {
			shutdown = true;
		}

		@Override
		public List<Runnable> shutdownNow() {
			shutdown = true;
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return shutdown;
		}

		@Override
		public boolean isTerminated() {
			return shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return true;
		}
	}
}
