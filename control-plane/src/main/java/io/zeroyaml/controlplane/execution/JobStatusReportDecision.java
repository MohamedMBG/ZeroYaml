package io.zeroyaml.controlplane.execution;

/**
 * How the Control Plane reconciled one Runner status report against the Job
 * state it owns. Only {@link #APPLIED} changes Job state, which is what makes
 * reporting safe to repeat.
 */
public enum JobStatusReportDecision {

	/** The report moved the Job to a new authoritative state. */
	APPLIED,

	/**
	 * The Job already records this report, or has already moved past it. This
	 * covers a retried report and a report that arrives after a later one.
	 */
	DUPLICATE,

	/** The Control Plane does not know the Job. */
	UNKNOWN_JOB,

	/**
	 * The report contradicts the authoritative state, for example a second and
	 * different terminal outcome, or a report from a process the Job is not
	 * assigned to. The Job keeps the state it already recorded.
	 */
	CONFLICT
}
