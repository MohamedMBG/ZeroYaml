package io.zeroyaml.controlplane.github.webhook;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Reads a GitHub webhook request into a {@link GitHubWebhookDelivery} after
 * checking its envelope: the GitHub headers, the content type, the payload
 * size, and that the body is a JSON object.
 *
 * <p>The body is read at most one byte past the configured limit, so an
 * oversized or unbounded request cannot claim more memory than the limit
 * allows. The payload is otherwise left untouched; parsing it is event
 * normalization, not ingress.</p>
 *
 * <p>Rejection messages never echo header values or payload content, because
 * both are attacker-controlled until the signature is verified.</p>
 */
@Component
class GitHubWebhookEnvelopeReader {

	static final String EVENT_HEADER = "X-GitHub-Event";
	static final String DELIVERY_HEADER = "X-GitHub-Delivery";
	static final String SIGNATURE_256_HEADER = "X-Hub-Signature-256";
	static final String HOOK_ID_HEADER = "X-GitHub-Hook-ID";
	static final String INSTALLATION_TARGET_TYPE_HEADER = "X-GitHub-Hook-Installation-Target-Type";
	static final String INSTALLATION_TARGET_ID_HEADER = "X-GitHub-Hook-Installation-Target-ID";

	/** GitHub event names are lower-case words joined by underscores. */
	private static final Pattern EVENT_PATTERN = Pattern.compile("[a-z][a-z_]{0,63}");

	/** GitHub delivery identifiers are GUIDs; the bound keeps log records small. */
	private static final Pattern DELIVERY_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");

	/** Optional headers longer than this are dropped rather than stored. */
	private static final int MAX_OPTIONAL_HEADER_LENGTH = 256;

	private final GitHubWebhookProperties properties;

	GitHubWebhookEnvelopeReader(GitHubWebhookProperties properties) {
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
	}

	/**
	 * Validates the request envelope and reads its body.
	 *
	 * @throws GitHubWebhookRejectedException when the envelope is invalid
	 * @throws IOException when the body cannot be read from the connection
	 */
	GitHubWebhookDelivery read(HttpServletRequest request) throws IOException {
		var event = request.getHeader(EVENT_HEADER);
		if (event == null || !EVENT_PATTERN.matcher(event).matches()) {
			throw new GitHubWebhookRejectedException(
					HttpStatus.BAD_REQUEST, null, null, EVENT_HEADER + " header is missing or malformed");
		}

		var deliveryId = request.getHeader(DELIVERY_HEADER);
		if (deliveryId == null || !DELIVERY_ID_PATTERN.matcher(deliveryId).matches()) {
			throw new GitHubWebhookRejectedException(
					HttpStatus.BAD_REQUEST, null, event, DELIVERY_HEADER + " header is missing or malformed");
		}

		if (!isJson(request.getContentType())) {
			throw new GitHubWebhookRejectedException(
					HttpStatus.UNSUPPORTED_MEDIA_TYPE, deliveryId, event,
					"Content-Type must be application/json; configure the GitHub webhook content type as application/json");
		}

		var maxBytes = properties.getMaxPayloadSize().toBytes();
		if (request.getContentLengthLong() > maxBytes) {
			throw payloadTooLarge(deliveryId, event, maxBytes);
		}

		var body = readBounded(request.getInputStream(), maxBytes);
		if (body == null) {
			throw payloadTooLarge(deliveryId, event, maxBytes);
		}

		if (!startsWithJsonObject(body)) {
			throw new GitHubWebhookRejectedException(
					HttpStatus.BAD_REQUEST, deliveryId, event, "payload must be a JSON object");
		}

		return new GitHubWebhookDelivery(
				deliveryId,
				event,
				optionalHeader(request, SIGNATURE_256_HEADER),
				optionalHeader(request, HOOK_ID_HEADER),
				optionalHeader(request, INSTALLATION_TARGET_TYPE_HEADER),
				optionalHeader(request, INSTALLATION_TARGET_ID_HEADER),
				body
		);
	}

	private static boolean isJson(String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			var mediaType = MediaType.parseMediaType(contentType);
			return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(mediaType);
		}
		catch (InvalidMediaTypeException ex) {
			return false;
		}
	}

	/**
	 * Reads the whole stream, or returns {@code null} as soon as it proves longer
	 * than {@code maxBytes}. The declared Content-Length is not trusted, because
	 * a chunked request has none.
	 */
	private static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
		var limited = input.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
		return limited.length > maxBytes ? null : limited;
	}

	/**
	 * Checks that the first non-whitespace byte opens a JSON object, which every
	 * GitHub webhook payload is. Full parsing is left to event normalization.
	 */
	private static boolean startsWithJsonObject(byte[] body) {
		for (byte value : body) {
			if (value == ' ' || value == '\t' || value == '\r' || value == '\n') {
				continue;
			}
			return value == '{';
		}
		return false;
	}

	private static String optionalHeader(HttpServletRequest request, String name) {
		var value = request.getHeader(name);
		if (value == null || value.isBlank() || value.length() > MAX_OPTIONAL_HEADER_LENGTH) {
			return null;
		}
		return value;
	}

	private static GitHubWebhookRejectedException payloadTooLarge(String deliveryId, String event, long maxBytes) {
		return new GitHubWebhookRejectedException(
				HttpStatus.CONTENT_TOO_LARGE, deliveryId, event,
				"payload exceeds the " + maxBytes + " byte limit");
	}
}
