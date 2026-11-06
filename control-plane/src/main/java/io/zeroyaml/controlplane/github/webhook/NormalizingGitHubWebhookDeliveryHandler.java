package io.zeroyaml.controlplane.github.webhook;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.zeroyaml.controlplane.domain.event.RepositoryPushEvent;
import io.zeroyaml.controlplane.orchestration.RepositoryPushEventHandler;

/**
 * Normalizes accepted push deliveries and hands the resulting event to pipeline
 * processing without waiting for it.
 *
 * <p>When pipeline processing cannot take more work, the delivery is refused
 * with {@code 503} so it can be redelivered; nothing about it was recorded.</p>
 */
@Component
class NormalizingGitHubWebhookDeliveryHandler implements GitHubWebhookDeliveryHandler {

	private static final Logger log = LoggerFactory.getLogger(NormalizingGitHubWebhookDeliveryHandler.class);

	private final GitHubPushEventNormalizer normalizer;
	private final RepositoryPushEventHandler pushEvents;

	NormalizingGitHubWebhookDeliveryHandler(GitHubPushEventNormalizer normalizer, RepositoryPushEventHandler pushEvents) {
		this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
		this.pushEvents = Objects.requireNonNull(pushEvents, "pushEvents must not be null");
	}

	@Override
	public void handle(GitHubWebhookDelivery delivery) {
		final java.util.Optional<RepositoryPushEvent> event;
		try {
			event = normalizer.normalize(delivery);
		}
		catch (GitHubPushEventNormalizationException ex) {
			throw new GitHubWebhookRejectedException(
					HttpStatus.BAD_REQUEST, delivery.deliveryId(), delivery.event(), ex.getMessage());
		}

		if (event.isEmpty()) {
			log.info("GitHub push delivery {} has no executable branch commit; no repository event was emitted",
					delivery.deliveryId());
			return;
		}

		var normalized = event.orElseThrow();
		log.info(
				"Normalized GitHub push delivery {} for repository {} on branch {} at commit {}",
				normalized.deliveryId(), normalized.repository().fullName(), normalized.branch().value(),
				normalized.commitSha().value()
		);
		try {
			pushEvents.handle(normalized);
		}
		catch (RejectedExecutionException ex) {
			throw new GitHubWebhookRejectedException(
					HttpStatus.SERVICE_UNAVAILABLE, delivery.deliveryId(), delivery.event(),
					"pipeline processing is at capacity; redeliver the push later");
		}
	}
}
