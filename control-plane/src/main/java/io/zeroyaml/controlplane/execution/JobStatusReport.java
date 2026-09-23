package io.zeroyaml.controlplane.execution;

import java.time.Instant;
import java.util.Objects;

import io.zeroyaml.controlplane.domain.job.JobId;
import io.zeroyaml.controlplane.domain.job.RunnerAssignment;

/**
 * One execution observation reported by the Runner process that runs a Job.
 *
 * <p>A report states what the Runner saw. It never states what the Job state
 * should become: {@link JobExecutionStatusRecorder} reconciles it against the
 * authoritative Job.</p>
 *
 * <p>Every report repeats {@code startedAt}, including a terminal one, so that
 * a lost running report cannot leave a finished Job without a start time.</p>
 *
 * @param jobId Job the report belongs to
 * @param reportedBy Runner process that observed the execution
 * @param state observed execution state
 * @param startedAt when the Runner started executing the Job
 * @param completedAt when execution ended, or {@code null} for {@link JobExecutionState#RUNNING}
 * @param exitCode code reported by the executed command, or {@code null} when it produced none;
 *        an absent code must never be read as a successful {@code 0}
 * @param failureReason why the execution failed, or {@code null} unless the state is
 *        {@link JobExecutionState#FAILED}
 * @param failureMessage operator-facing diagnostic, or {@code null} unless the state is
 *        {@link JobExecutionState#FAILED}; it carries no credentials or command output
 */
public record JobStatusReport(
		JobId jobId,
		RunnerAssignment reportedBy,
		JobExecutionState state,
		Instant startedAt,
		Instant completedAt,
		Integer exitCode,
		JobExecutionFailureReason failureReason,
		String failureMessage
) {

	public JobStatusReport {
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(reportedBy, "reportedBy must not be null");
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(startedAt, "startedAt must not be null");

		if (state.isTerminal()) {
			Objects.requireNonNull(completedAt, "completedAt must not be null for a terminal report");
			if (completedAt.isBefore(startedAt)) {
				throw new IllegalArgumentException("completedAt must not be before startedAt");
			}
		} else if (completedAt != null) {
			throw new IllegalArgumentException("a running report must not carry a completion timestamp");
		}

		if (state == JobExecutionState.FAILED) {
			Objects.requireNonNull(failureReason, "failureReason must not be null for a failed report");
			if (failureMessage == null || failureMessage.isBlank()) {
				throw new IllegalArgumentException("failureMessage must not be blank for a failed report");
			}
		} else if (failureReason != null || failureMessage != null) {
			throw new IllegalArgumentException("only a failed report may carry failure information");
		}
	}

	/**
	 * Creates the report that execution began.
	 *
	 * @param jobId Job that started running
	 * @param reportedBy Runner process executing it
	 * @param startedAt when execution began
	 * @return a running report
	 */
	public static JobStatusReport running(JobId jobId, RunnerAssignment reportedBy, Instant startedAt) {
		return new JobStatusReport(jobId, reportedBy, JobExecutionState.RUNNING, startedAt, null, null, null, null);
	}

	/**
	 * Creates the report that execution completed successfully.
	 *
	 * @param jobId Job that finished
	 * @param reportedBy Runner process that executed it
	 * @param startedAt when execution began
	 * @param completedAt when execution ended
	 * @param exitCode code the command exited with, or {@code null} when none was reported
	 * @return a succeeded report
	 */
	public static JobStatusReport succeeded(
			JobId jobId,
			RunnerAssignment reportedBy,
			Instant startedAt,
			Instant completedAt,
			Integer exitCode
	) {
		return new JobStatusReport(
				jobId, reportedBy, JobExecutionState.SUCCEEDED, startedAt, completedAt, exitCode, null, null);
	}

	/**
	 * Creates the report that execution did not complete successfully.
	 *
	 * @param jobId Job that failed
	 * @param reportedBy Runner process that executed it
	 * @param startedAt when execution began
	 * @param completedAt when execution ended
	 * @param exitCode code the command exited with, or {@code null} when it produced none
	 * @param failureReason why the execution failed
	 * @param failureMessage operator-facing diagnostic
	 * @return a failed report
	 */
	public static JobStatusReport failed(
			JobId jobId,
			RunnerAssignment reportedBy,
			Instant startedAt,
			Instant completedAt,
			Integer exitCode,
			JobExecutionFailureReason failureReason,
			String failureMessage
	) {
		return new JobStatusReport(
				jobId,
				reportedBy,
				JobExecutionState.FAILED,
				startedAt,
				completedAt,
				exitCode,
				failureReason,
				failureMessage
		);
	}
}
