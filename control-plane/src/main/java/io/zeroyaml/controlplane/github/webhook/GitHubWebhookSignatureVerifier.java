package io.zeroyaml.controlplane.github.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Authenticates a GitHub webhook delivery by recomputing the
 * {@code X-Hub-Signature-256} HMAC over the exact bytes GitHub sent.
 *
 * <p>This is the trust boundary of the webhook ingress. Until a delivery passes
 * this check its headers and payload are attacker-controlled, so verification
 * runs before the event allow-list and before any downstream hand-off. A
 * delivery that fails is answered {@code 401 Unauthorized} and creates no
 * internal event or job.</p>
 *
 * <p>Only the SHA-256 signature is accepted. The legacy SHA-1
 * {@code X-Hub-Signature} header is ignored, because accepting it would let a
 * caller downgrade the delivery to the weaker digest.</p>
 *
 * <p>The verifier is stateless apart from the configured key and is safe for
 * concurrent use; a {@link Mac} instance is created per verification because
 * {@code Mac} itself is not thread-safe.</p>
 */
@Component
class GitHubWebhookSignatureVerifier {

	/**
	 * Header format GitHub uses. The digest is accepted in either case because
	 * hexadecimal is case-insensitive, and the fixed length of 64 characters keeps
	 * the comparison below operating on two equal-length digests.
	 */
	private static final Pattern SIGNATURE_PATTERN = Pattern.compile("sha256=([0-9a-fA-F]{64})");

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private static final HexFormat HEX = HexFormat.of();

	private final SecretKeySpec secretKey;

	GitHubWebhookSignatureVerifier(GitHubWebhookProperties properties) {
		Objects.requireNonNull(properties, "properties must not be null");
		var secret = properties.getSecret();
		// Repeated from property validation on purpose: an unusable key must fail
		// loudly at startup rather than degrade into a verifier that cannot
		// authenticate anything at request time.
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException(
					"zeroyaml.github.webhook.secret must be configured before webhook signatures can be verified");
		}
		this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
	}

	/**
	 * Verifies one delivery against the configured webhook secret.
	 *
	 * @param delivery delivery whose envelope already passed validation
	 * @throws GitHubWebhookRejectedException with {@link HttpStatus#UNAUTHORIZED}
	 *         when the signature header is missing, malformed, or does not match
	 */
	void verify(GitHubWebhookDelivery delivery) {
		var header = delivery.signature256().orElse(null);
		if (header == null) {
			throw unauthorized(delivery, GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER
					+ " header is missing; configure a secret on the GitHub webhook");
		}

		var matcher = SIGNATURE_PATTERN.matcher(header);
		if (!matcher.matches()) {
			throw unauthorized(delivery, GitHubWebhookEnvelopeReader.SIGNATURE_256_HEADER
					+ " header is malformed; expected sha256=<64 hexadecimal characters>");
		}

		var presented = HEX.parseHex(matcher.group(1));
		var expected = sign(delivery.rawBody());

		// MessageDigest.isEqual examines every byte of two equal-length arrays, so
		// the answer takes the same time whatever the input. A short-circuiting
		// comparison would reveal how many leading bytes of a guess were right,
		// which is enough to forge a signature one byte at a time.
		if (!MessageDigest.isEqual(expected, presented)) {
			throw unauthorized(delivery, "signature does not match the configured webhook secret");
		}
	}

	private byte[] sign(byte[] payload) {
		try {
			var mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(secretKey);
			return mac.doFinal(payload);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			// Every Java platform provides HmacSHA256 and the key is fixed at startup,
			// so this is a broken deployment rather than a bad request: it must surface
			// as a server error instead of looking like a failed signature.
			throw new IllegalStateException("GitHub webhook signatures cannot be computed", ex);
		}
	}

	/** Rejection reasons name the header, never its value, the secret, or the payload. */
	private static GitHubWebhookRejectedException unauthorized(GitHubWebhookDelivery delivery, String reason) {
		return new GitHubWebhookRejectedException(
				HttpStatus.UNAUTHORIZED, delivery.deliveryId(), delivery.event(), reason);
	}
}
