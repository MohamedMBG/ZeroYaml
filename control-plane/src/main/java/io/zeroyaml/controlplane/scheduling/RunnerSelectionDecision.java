package io.zeroyaml.controlplane.scheduling;

/**
 * Possible results of choosing a Runner for a queued Job.
 */
public enum RunnerSelectionDecision {
	/** A healthy Runner was chosen and is named in the dispatch context. */
	SELECTED,

	/**
	 * No registered Runner is currently healthy. The Job stays queued and
	 * nothing is dispatched; the caller decides when to ask again.
	 */
	BLOCKED_NO_HEALTHY_RUNNER
}
