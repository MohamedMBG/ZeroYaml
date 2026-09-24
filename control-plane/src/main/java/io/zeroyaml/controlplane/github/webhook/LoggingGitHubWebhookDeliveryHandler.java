package io.zeroyaml.controlplane.github.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records accepted deliveries without acting on them.
 *
 * <p>No downstream processing exists yet, so an accepted delivery only leaves
 * an audit record. The record carries identifiers and the payload size, never
 * the payload or the signature.</p>
 */
// TODO(#17): verify the X-Hub-Signature-256 HMAC before a delivery is trusted.
// TODO(#20): normalize accepted push deliveries into internal repository events.
@Component
class LoggingGitHubWebhookDeliveryHandler implements GitHubWebhookDeliveryHandler {

	private static final Logger log = LoggerFactory.getLogger(LoggingGitHubWebhookDeliveryHandler.class);

	@Override
	public void handle(GitHubWebhookDelivery delivery) {
		log.info(
				"GitHub delivery {} for event {} recorded without further processing ({} payload bytes)",
				delivery.deliveryId(),
				delivery.event(),
				delivery.payloadSize()
		);
	}
}
