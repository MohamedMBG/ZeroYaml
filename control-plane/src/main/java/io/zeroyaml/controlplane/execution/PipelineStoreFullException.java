package io.zeroyaml.controlplane.execution;

/**
 * Raised when a {@link PipelineStore} cannot bind another pipeline because it
 * holds its maximum number of plans.
 *
 * <p>This is a capacity failure of the Control Plane, not a problem with the
 * request, so it is kept apart from {@link PipelineJobConflictException}: the
 * caller should report the service as unavailable and alert, not reject the
 * delivery as conflicting. It extends {@link IllegalStateException} because the
 * store, not the input, is in the wrong state.</p>
 */
public class PipelineStoreFullException extends IllegalStateException {

	PipelineStoreFullException(int capacity) {
		super("Pipeline store is full at " + capacity + " pipelines");
	}
}
