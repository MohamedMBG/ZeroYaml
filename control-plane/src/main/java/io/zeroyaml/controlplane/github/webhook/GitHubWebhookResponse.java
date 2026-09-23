package io.zeroyaml.controlplane.github.webhook;

/**
 * Body returned for every webhook delivery. GitHub shows it in the webhook's
 * "Recent Deliveries" view, so it states the outcome in operator terms.
 *
 * @param deliveryId delivery identifier, or {@code null} when the header was invalid
 * @param event event name, or {@code null} when the header was invalid
 * @param outcome what the Control Plane did with the delivery
 * @param message operator-facing explanation that never echoes request content
 */
record GitHubWebhookResponse(String deliveryId, String event, Outcome outcome, String message) {

	enum Outcome {
		/** Handed to downstream processing. */
		ACCEPTED,

		/** Well-formed, but no pipeline work is started for it. */
		IGNORED,

		/** The envelope was invalid. */
		REJECTED
	}
}
