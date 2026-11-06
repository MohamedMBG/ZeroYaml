package io.zeroyaml.controlplane.orchestration;

import java.time.Duration;
import java.util.Objects;

/** A repository read refused by the provider's rate limit. */
public class RepositoryRateLimitedException extends RepositorySnapshotUnavailableException {

	private final Duration retryAfter;

	public RepositoryRateLimitedException(String message, Duration retryAfter) {
		super(message);
		this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter must not be null");
		if (retryAfter.isNegative() || retryAfter.isZero()) {
			throw new IllegalArgumentException("retryAfter must be positive");
		}
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
