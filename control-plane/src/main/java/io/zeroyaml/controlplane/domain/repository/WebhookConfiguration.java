package io.zeroyaml.controlplane.domain.repository;

import java.util.Objects;

/**
 * Webhook metadata for a connected repository.
 *
 * <p>The signing secret is represented only by a {@link SecretReference}; the
 * secret value is never stored here and therefore never appears in
 * {@link #toString()} or logs.</p>
 *
 * @param signingSecret reference to the secret used to verify webhook signatures
 */
public record WebhookConfiguration(SecretReference signingSecret) {

	public WebhookConfiguration {
		Objects.requireNonNull(signingSecret, "signingSecret must not be null");
	}
}
