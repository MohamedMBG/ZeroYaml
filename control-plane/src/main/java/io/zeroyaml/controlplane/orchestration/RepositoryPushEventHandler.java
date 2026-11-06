package io.zeroyaml.controlplane.orchestration;

import java.util.concurrent.RejectedExecutionException;

import io.zeroyaml.controlplane.domain.event.RepositoryPushEvent;

/**
 * Accepts a verified, normalized push for asynchronous pipeline processing.
 */
public interface RepositoryPushEventHandler {

	/**
	 * Hands {@code event} over without waiting for its pipeline.
	 *
	 * @throws RejectedExecutionException if no more work can be accepted right now;
	 *         the push was not processed and may be delivered again
	 */
	void handle(RepositoryPushEvent event);
}
