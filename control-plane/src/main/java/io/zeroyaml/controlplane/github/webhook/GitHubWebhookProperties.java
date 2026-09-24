package io.zeroyaml.controlplane.github.webhook;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized configuration for the GitHub webhook ingress endpoint.
 *
 * <p>The payload limit bounds how much memory one delivery may claim before it
 * is rejected. GitHub itself caps webhook payloads at 25 MB, so a larger limit
 * would never be used and is refused at startup.</p>
 *
 * <p>The secret is the key the endpoint authenticates deliveries with. It has
 * no default and is never written to logs or to a tracked file; supply it
 * through {@code ZEROYAML_GITHUB_WEBHOOK_SECRET} or an approved secret store,
 * and set the same value on the GitHub webhook.</p>
 */
@Validated
@ConfigurationProperties(prefix = "zeroyaml.github.webhook")
public class GitHubWebhookProperties {

	/** Upper bound GitHub applies to webhook payloads. */
	static final DataSize GITHUB_PAYLOAD_CAP = DataSize.ofMegabytes(25);

	/**
	 * Shortest secret accepted. GitHub recommends a high-entropy random value;
	 * this bound only rules out the shortest guessable secrets, which would make
	 * signature verification decorative.
	 */
	static final int MINIMUM_SECRET_LENGTH = 16;

	// Bound from application properties or environment variables at application startup.
	private DataSize maxPayloadSize = DataSize.ofMegabytes(5);

	// Deliberately without a default: a secret shipped in source would authenticate
	// every caller who can read the repository.
	private String secret;

	public DataSize getMaxPayloadSize() {
		return maxPayloadSize;
	}

	public void setMaxPayloadSize(DataSize maxPayloadSize) {
		this.maxPayloadSize = maxPayloadSize;
	}

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	@PostConstruct
	void validate() {
		if (maxPayloadSize == null
				|| maxPayloadSize.toBytes() <= 0
				|| maxPayloadSize.compareTo(GITHUB_PAYLOAD_CAP) > 0) {
			throw new IllegalStateException(
					"zeroyaml.github.webhook.max-payload-size must be greater than zero and at most "
							+ GITHUB_PAYLOAD_CAP.toMegabytes() + "MB, got " + maxPayloadSize
			);
		}
		if (secret == null || secret.isBlank() || secret.length() < MINIMUM_SECRET_LENGTH) {
			// The configured value is never included, because startup failures are logged.
			throw new IllegalStateException(
					"zeroyaml.github.webhook.secret must be set to at least " + MINIMUM_SECRET_LENGTH
							+ " characters; supply it through the ZEROYAML_GITHUB_WEBHOOK_SECRET environment variable"
			);
		}
	}
}
