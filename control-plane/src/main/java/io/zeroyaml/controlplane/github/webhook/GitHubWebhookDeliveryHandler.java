package io.zeroyaml.controlplane.github.webhook;

/**
 * Downstream consumer of GitHub deliveries that passed envelope validation and
 * signature verification and carry a supported event type.
 *
 * <p>The webhook endpoint is only an ingress adapter: it authenticates each
 * delivery, hands it to this boundary, and never decides what the delivery
 * means. Implementations may therefore trust that the raw body is the payload
 * GitHub signed, and event normalization is implemented behind them.</p>
 *
 * <p>Implementations are called on the request thread before GitHub receives
 * its response, so they must return quickly and must not start long-running
 * pipeline work synchronously.</p>
 */
public interface GitHubWebhookDeliveryHandler {

	/**
	 * Takes responsibility for one accepted delivery.
	 *
	 * @param delivery verified delivery with its raw body and GitHub headers
	 */
	void handle(GitHubWebhookDelivery delivery);
}
