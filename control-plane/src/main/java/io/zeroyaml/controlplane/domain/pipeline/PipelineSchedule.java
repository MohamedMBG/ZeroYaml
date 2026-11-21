package io.zeroyaml.controlplane.domain.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.zeroyaml.controlplane.domain.job.JobStatus;

/**
 * Dependency-aware scheduling decision for the recorded steps of one pipeline
 * run.
 *
 * <p>The schedule is a pure function of the recorded step states: it performs
 * no I/O, keeps no memory of earlier decisions, and returns the same decisions
 * for the same input, so the application layer can compute it again after every
 * change, including after a restart. One decision is returned for each step, in
 * the order the steps were supplied.</p>
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li>A created step is {@link StepDecision.Ready} only when every step it
 *       depends on has succeeded. A step without dependencies is ready at once,
 *       so independent steps are ready together.</li>
 *   <li>A created step is {@link StepDecision.Skip} when at least one of its
 *       dependencies ended without success: it failed, was cancelled, was
 *       skipped, or is itself decided as skipped by this schedule. Skipping is
 *       therefore transitive. A dependency that names no recorded step can never
 *       succeed and is treated the same way.</li>
 *   <li>Any other created step is {@link StepDecision.Blocked} and names the
 *       dependencies it still waits for.</li>
 *   <li>A queued step was already admitted and stays {@link StepDecision.Ready}
 *       until it is dispatched; a running step is
 *       {@link StepDecision.Running}; a terminal step is
 *       {@link StepDecision.Settled}.</li>
 * </ul>
 *
 * <p>A dependency names a step, not a Job. When a run records more than one Job
 * for the same step name, for example after its plan changed, the dependency is
 * satisfied only when every one of them succeeded, and it ended without success
 * as soon as one of them did.</p>
 *
 * <p>Failure of one step never affects a step that does not depend on it,
 * directly or transitively; independent steps keep running.</p>
 */
public final class PipelineSchedule {

	private final List<StepDecision> decisions;

	private PipelineSchedule(List<StepDecision> decisions) {
		this.decisions = List.copyOf(decisions);
	}

	/**
	 * Decides what each recorded step of a run needs next.
	 *
	 * @param steps recorded steps of one run, in step order
	 * @return the schedule, with one decision per supplied step
	 */
	public static PipelineSchedule of(List<StepState> steps) {
		Objects.requireNonNull(steps, "steps must not be null");
		var states = List.copyOf(steps);
		var indexesByName = new HashMap<String, List<Integer>>();
		for (var index = 0; index < states.size(); index++) {
			indexesByName.computeIfAbsent(states.get(index).stepName(), name -> new ArrayList<>()).add(index);
		}

		var skipped = decideSkips(states, indexesByName);
		var decisions = new ArrayList<StepDecision>(states.size());
		for (var index = 0; index < states.size(); index++) {
			decisions.add(decide(states, indexesByName, skipped, index));
		}
		return new PipelineSchedule(decisions);
	}

	/**
	 * Returns one decision per step, in the order the steps were supplied.
	 *
	 * @return the decisions; unmodifiable
	 */
	public List<StepDecision> decisions() {
		return decisions;
	}

	/**
	 * Indicates whether no step can run any more once the {@link StepDecision.Skip}
	 * decisions are recorded: every step is settled or is to be skipped.
	 *
	 * @return {@code true} when the run has nothing left to execute
	 */
	public boolean isComplete() {
		return decisions.stream().allMatch(decision ->
				decision instanceof StepDecision.Settled || decision instanceof StepDecision.Skip);
	}

	/**
	 * Marks every created step that can never run. The loop repeats until nothing
	 * changes, so skipping propagates whatever order the steps are supplied in.
	 * Each pass skips at least one more step or ends, which bounds it by the
	 * number of steps.
	 */
	private static boolean[] decideSkips(List<StepState> states, Map<String, List<Integer>> indexesByName) {
		var skipped = new boolean[states.size()];
		var changed = true;
		while (changed) {
			changed = false;
			for (var index = 0; index < states.size(); index++) {
				var state = states.get(index);
				if (state.status() != JobStatus.CREATED || skipped[index]) {
					continue;
				}
				for (var dependency : state.dependsOn()) {
					if (outcomeOf(dependency, states, indexesByName, skipped) == DependencyOutcome.UNSUCCESSFUL) {
						skipped[index] = true;
						changed = true;
						break;
					}
				}
			}
		}
		return skipped;
	}

	private static StepDecision decide(List<StepState> states, Map<String, List<Integer>> indexesByName,
			boolean[] skipped, int index) {
		var state = states.get(index);
		return switch (state.status()) {
			case CREATED -> {
				var unsuccessful = new ArrayList<String>();
				var pending = new ArrayList<String>();
				for (var dependency : state.dependsOn()) {
					switch (outcomeOf(dependency, states, indexesByName, skipped)) {
						case UNSUCCESSFUL -> unsuccessful.add(dependency);
						case PENDING -> pending.add(dependency);
						case SUCCEEDED -> {
							// Satisfied; nothing to wait for.
						}
					}
				}
				if (skipped[index]) {
					yield new StepDecision.Skip(unsuccessful);
				}
				yield pending.isEmpty() ? new StepDecision.Ready() : new StepDecision.Blocked(pending);
			}
			case QUEUED -> new StepDecision.Ready();
			case RUNNING -> new StepDecision.Running();
			case SUCCEEDED, FAILED, CANCELLED, SKIPPED -> new StepDecision.Settled(state.status());
		};
	}

	private static DependencyOutcome outcomeOf(String dependency, List<StepState> states,
			Map<String, List<Integer>> indexesByName, boolean[] skipped) {
		var indexes = indexesByName.get(dependency);
		if (indexes == null) {
			return DependencyOutcome.UNSUCCESSFUL;
		}
		var allSucceeded = true;
		for (var index : indexes) {
			var status = states.get(index).status();
			if (skipped[index] || status.isTerminal() && status != JobStatus.SUCCEEDED) {
				return DependencyOutcome.UNSUCCESSFUL;
			}
			allSucceeded &= status == JobStatus.SUCCEEDED;
		}
		return allSucceeded ? DependencyOutcome.SUCCEEDED : DependencyOutcome.PENDING;
	}

	private enum DependencyOutcome {
		SUCCEEDED,
		PENDING,
		UNSUCCESSFUL
	}

	/**
	 * Recorded state of one step of a run: its name, the names of the steps it
	 * depends on, and the status of the Job that executes it.
	 *
	 * @param stepName name of the step
	 * @param dependsOn names of the steps that must succeed first; copied defensively
	 * @param status current status of the step's Job
	 */
	public record StepState(String stepName, List<String> dependsOn, JobStatus status) {

		public StepState {
			Objects.requireNonNull(stepName, "stepName must not be null");
			Objects.requireNonNull(dependsOn, "dependsOn must not be null");
			dependsOn = List.copyOf(dependsOn);
			Objects.requireNonNull(status, "status must not be null");
		}
	}

	/** What one step needs next. */
	public sealed interface StepDecision {

		/**
		 * Every dependency succeeded: the step's Job may be dispatched now, or, when
		 * it is already queued, its dispatch is still open.
		 */
		record Ready() implements StepDecision { }

		/**
		 * The step waits for dependencies that have not ended yet.
		 *
		 * @param waitingOn the dependencies still pending, in the step's dependency order
		 */
		record Blocked(List<String> waitingOn) implements StepDecision {

			public Blocked {
				waitingOn = List.copyOf(waitingOn);
			}
		}

		/**
		 * The step can never run and its Job is to be recorded as
		 * {@link JobStatus#SKIPPED}.
		 *
		 * @param unsuccessfulDependencies the dependencies that ended without success or name no
		 *        recorded step, in the step's dependency order
		 */
		record Skip(List<String> unsuccessfulDependencies) implements StepDecision {

			public Skip {
				unsuccessfulDependencies = List.copyOf(unsuccessfulDependencies);
			}
		}

		/** The step's Job is executing. */
		record Running() implements StepDecision { }

		/**
		 * The step's Job reached a terminal state.
		 *
		 * @param status the terminal state
		 */
		record Settled(JobStatus status) implements StepDecision { }
	}
}
