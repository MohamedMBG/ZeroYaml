package io.zeroyaml.controlplane.github.webhook;

/**
 * Downstream consumer of GitHub deliveries that passed envelope validation and
 * carry a supported event type.
 *
 * <p>The webhook endpoint is only an ingress adapter: it hands each accepted
 * delivery to this boundary and never decides what the delivery means.
 * Signature verification and event normalization are implemented behind it.</p>
 *
 * <p>Implementations are called on the request thread before GitHub receives
 * its response, so they must return quickly and must not start long-running
 * pipeline work synchronously.</p>
 */
public interface GitHubWebhookDeliveryHandler {

	/**
	 * Takes responsibility for one accepted delivery.
	 *
	 * @param delivery validated delivery with its raw body and GitHub headers
	 */
	void handle(GitHubWebhookDelivery delivery);
}
