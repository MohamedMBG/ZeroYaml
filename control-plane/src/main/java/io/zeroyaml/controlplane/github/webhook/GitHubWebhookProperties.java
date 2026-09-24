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
 */
@Validated
@ConfigurationProperties(prefix = "zeroyaml.github.webhook")
public class GitHubWebhookProperties {

	/** Upper bound GitHub applies to webhook payloads. */
	static final DataSize GITHUB_PAYLOAD_CAP = DataSize.ofMegabytes(25);

	// Bound from application properties or environment variables at application startup.
	private DataSize maxPayloadSize = DataSize.ofMegabytes(5);

	public DataSize getMaxPayloadSize() {
		return maxPayloadSize;
	}

	public void setMaxPayloadSize(DataSize maxPayloadSize) {
		this.maxPayloadSize = maxPayloadSize;
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
	}
}
