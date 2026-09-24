package io.zeroyaml.controlplane.github.webhook;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.zeroyaml.controlplane.github.webhook.GitHubWebhookResponse.Outcome;

/**
 * Ingress endpoint for GitHub webhook deliveries.
 *
 * <p>The controller is an adapter only. It validates the delivery envelope,
 * applies the supported event allow-list, and hands a supported delivery to the
 * {@link GitHubWebhookDeliveryHandler}; it never parses the payload or starts
 * pipeline work itself.</p>
 *
 * <p>Answers:</p>
 * <ul>
 *   <li>{@code 202 Accepted} for a supported event handed downstream;</li>
 *   <li>{@code 200 OK} with outcome {@code IGNORED} for {@code ping} and for any
 *       event outside the allow-list. A well-formed delivery the Control Plane
 *       does not act on is not a failure, and answering it with an error would
 *       mark it red in GitHub's delivery log;</li>
 *   <li>{@code 400}, {@code 413}, or {@code 415} with outcome {@code REJECTED}
 *       for an invalid envelope.</li>
 * </ul>
 */
@RestController
class GitHubWebhookController {

	static final String PATH = "/webhooks/github";

	/** Events handed to downstream processing. */
	static final Set<String> SUPPORTED_EVENTS = Set.of("push");

	/** GitHub sends {@code ping} once when a webhook is created. */
	private static final String PING_EVENT = "ping";

	private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

	private final GitHubWebhookEnvelopeReader envelopeReader;
	private final GitHubWebhookDeliveryHandler deliveryHandler;

	GitHubWebhookController(GitHubWebhookEnvelopeReader envelopeReader, GitHubWebhookDeliveryHandler deliveryHandler) {
		this.envelopeReader = Objects.requireNonNull(envelopeReader, "envelopeReader must not be null");
		this.deliveryHandler = Objects.requireNonNull(deliveryHandler, "deliveryHandler must not be null");
	}

	@PostMapping(PATH)
	ResponseEntity<GitHubWebhookResponse> receive(HttpServletRequest request) throws IOException {
		var delivery = envelopeReader.read(request);

		if (PING_EVENT.equals(delivery.event())) {
			return ignored(delivery, "ping acknowledged; no pipeline work is started");
		}

		if (!SUPPORTED_EVENTS.contains(delivery.event())) {
			return ignored(delivery, "event type is not supported; supported events: " + String.join(", ", SUPPORTED_EVENTS));
		}

		deliveryHandler.handle(delivery);

		log.info(
				"Accepted GitHub delivery {} for event {} ({} payload bytes)",
				delivery.deliveryId(), delivery.event(), delivery.payloadSize()
		);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(new GitHubWebhookResponse(
				delivery.deliveryId(), delivery.event(), Outcome.ACCEPTED, "delivery accepted for processing"));
	}

	@ExceptionHandler(GitHubWebhookRejectedException.class)
	ResponseEntity<GitHubWebhookResponse> rejected(GitHubWebhookRejectedException rejection) {
		log.warn(
				"Rejected GitHub delivery {} for event {} with {}: {}",
				rejection.deliveryId(), rejection.event(), rejection.status().value(), rejection.getMessage()
		);
		return ResponseEntity.status(rejection.status()).body(new GitHubWebhookResponse(
				rejection.deliveryId(), rejection.event(), Outcome.REJECTED, rejection.getMessage()));
	}

	private static ResponseEntity<GitHubWebhookResponse> ignored(GitHubWebhookDelivery delivery, String message) {
		log.info("Ignored GitHub delivery {} for event {}: {}", delivery.deliveryId(), delivery.event(), message);
		return ResponseEntity.ok(new GitHubWebhookResponse(
				delivery.deliveryId(), delivery.event(), Outcome.IGNORED, message));
	}
}
