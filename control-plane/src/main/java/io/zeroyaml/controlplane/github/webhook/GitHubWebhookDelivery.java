package io.zeroyaml.controlplane.github.webhook;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * One GitHub webhook delivery exactly as it arrived, after envelope validation.
 *
 * <p>The raw body is kept byte-for-byte because signature verification computes
 * an HMAC over the exact bytes GitHub sent; re-serializing a parsed payload
 * would break it. The body is never parsed here: interpreting the payload
 * belongs to event normalization downstream.</p>
 *
 * <p>Instances are immutable. The body is copied on the way in and out, so no
 * caller can alter what another consumer sees. {@link #toString()} omits the
 * body and the signature, which must never reach logs.</p>
 */
public final class GitHubWebhookDelivery {

	private final String deliveryId;
	private final String event;
	private final String signature256;
	private final String hookId;
	private final String installationTargetType;
	private final String installationTargetId;
	private final byte[] rawBody;

	/**
	 * @param deliveryId value of {@code X-GitHub-Delivery}
	 * @param event value of {@code X-GitHub-Event}
	 * @param signature256 value of {@code X-Hub-Signature-256}, or {@code null} when absent
	 * @param hookId value of {@code X-GitHub-Hook-ID}, or {@code null} when absent
	 * @param installationTargetType value of {@code X-GitHub-Hook-Installation-Target-Type}, or {@code null}
	 * @param installationTargetId value of {@code X-GitHub-Hook-Installation-Target-ID}, or {@code null}
	 * @param rawBody request body exactly as received
	 */
	public GitHubWebhookDelivery(
			String deliveryId,
			String event,
			String signature256,
			String hookId,
			String installationTargetType,
			String installationTargetId,
			byte[] rawBody
	) {
		this.deliveryId = Objects.requireNonNull(deliveryId, "deliveryId must not be null");
		this.event = Objects.requireNonNull(event, "event must not be null");
		this.signature256 = signature256;
		this.hookId = hookId;
		this.installationTargetType = installationTargetType;
		this.installationTargetId = installationTargetId;
		this.rawBody = Objects.requireNonNull(rawBody, "rawBody must not be null").clone();
	}

	/** GitHub's unique identifier of this delivery, stable across redeliveries. */
	public String deliveryId() {
		return deliveryId;
	}

	/** GitHub event name, such as {@code push}. */
	public String event() {
		return event;
	}

	/**
	 * The {@code sha256=} signature header as received. It is untrusted until
	 * {@code GitHubWebhookSignatureVerifier} has checked it, which the ingress
	 * endpoint does before any delivery is handed downstream.
	 */
	public Optional<String> signature256() {
		return Optional.ofNullable(signature256);
	}

	public Optional<String> hookId() {
		return Optional.ofNullable(hookId);
	}

	/** Kind of resource the webhook is installed on, such as {@code repository}. */
	public Optional<String> installationTargetType() {
		return Optional.ofNullable(installationTargetType);
	}

	public Optional<String> installationTargetId() {
		return Optional.ofNullable(installationTargetId);
	}

	/** A copy of the request body exactly as received. */
	public byte[] rawBody() {
		return rawBody.clone();
	}

	public int payloadSize() {
		return rawBody.length;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof GitHubWebhookDelivery that)) {
			return false;
		}
		return deliveryId.equals(that.deliveryId)
				&& event.equals(that.event)
				&& Objects.equals(signature256, that.signature256)
				&& Objects.equals(hookId, that.hookId)
				&& Objects.equals(installationTargetType, that.installationTargetType)
				&& Objects.equals(installationTargetId, that.installationTargetId)
				&& Arrays.equals(rawBody, that.rawBody);
	}

	@Override
	public int hashCode() {
		return Objects.hash(deliveryId, event, Arrays.hashCode(rawBody));
	}

	@Override
	public String toString() {
		return "GitHubWebhookDelivery[deliveryId=" + deliveryId
				+ ", event=" + event
				+ ", hookId=" + hookId
				+ ", payloadSize=" + rawBody.length + "]";
	}
}
