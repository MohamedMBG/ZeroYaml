package io.zeroyaml.controlplane.github.repository;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import io.zeroyaml.controlplane.domain.pipeline.inference.RepositorySnapshot;
import io.zeroyaml.controlplane.domain.repository.CommitSha;
import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;
import io.zeroyaml.controlplane.orchestration.RepositorySnapshotUnavailableException;
import io.zeroyaml.controlplane.orchestration.RepositoryRateLimitedException;
import io.zeroyaml.controlplane.orchestration.RepositorySource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the root listing of a GitHub repository through the Git Trees API
 * ({@code GET /repos/{owner}/{repo}/git/trees/{sha}}, not recursive).
 *
 * <p>Every call is bounded by {@code api-timeout} for the whole exchange and by
 * {@code max-tree-response-size} for the body. Redirects are not followed, so
 * the token is only ever sent to the configured API host.</p>
 */
@Component
class GitHubRepositorySource implements RepositorySource {

	static final String API_VERSION = "2022-11-28";

	private final GitHubRepositoryProperties properties;
	private final JsonMapper json;
	private final HttpClient http;

	GitHubRepositorySource(GitHubRepositoryProperties properties, JsonMapper json) {
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.json = Objects.requireNonNull(json, "json must not be null");
		this.http = HttpClient.newBuilder()
				.connectTimeout(properties.getApiTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	@Override
	public URI location(RepositoryIdentity repository) {
		Objects.requireNonNull(repository, "repository must not be null");
		// Owner and name are restricted to URI-safe characters by RepositoryIdentity.
		return URI.create(properties.locationFor(repository.owner(), repository.name()));
	}

	@Override
	public RepositorySnapshot rootSnapshot(RepositoryIdentity repository, CommitSha revision) {
		Objects.requireNonNull(repository, "repository must not be null");
		Objects.requireNonNull(revision, "revision must not be null");

		var response = send(treeRequest(repository, revision), repository);
		var status = response.statusCode();
		if (status != 200) {
			if (status == 429 || status == 403 && (response.headers().firstValue("Retry-After").isPresent()
					|| response.headers().firstValue("X-RateLimit-Remaining").filter("0"::equals).isPresent())) {
				throw new RepositoryRateLimitedException(describeStatus(status, repository), retryAfter(response));
			}
			throw new RepositorySnapshotUnavailableException(describeStatus(status, repository));
		}
		return toSnapshot(response.body(), repository);
	}

	private static Duration retryAfter(HttpResponse<?> response) {
		long seconds = 60;
		try {
			var header = response.headers().firstValue("Retry-After");
			if (header.isPresent()) {
				seconds = Math.max(seconds, Long.parseLong(header.get()));
			}
			var reset = response.headers().firstValue("X-RateLimit-Reset");
			if (reset.isPresent()) {
				seconds = Math.max(seconds, Math.subtractExact(Long.parseLong(reset.get()), Instant.now().getEpochSecond()));
			}
		} catch (NumberFormatException | ArithmeticException ex) {
			return Duration.ofDays(2);
		}
		return Duration.ofSeconds(seconds);
	}

	@PreDestroy
	void close() {
		http.close();
	}

	private HttpRequest treeRequest(RepositoryIdentity repository, CommitSha revision) {
		var base = properties.getApiBaseUrl().replaceAll("/+$", "");
		var request = HttpRequest.newBuilder(URI.create(
						base + "/repos/" + repository.owner() + "/" + repository.name() + "/git/trees/" + revision.value()))
				.timeout(properties.getApiTimeout())
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", API_VERSION)
				.header("User-Agent", "zeroyaml-control-plane")
				.GET();
		if (properties.getApiToken() != null) {
			try {
				request.header("Authorization", "Bearer " + properties.getApiToken());
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalStateException("zeroyaml.github.repository.api-token is not a valid HTTP header value");
			}
		}
		return request.build();
	}

	private HttpResponse<byte[]> send(HttpRequest request, RepositoryIdentity repository) {
		var limit = properties.getMaxTreeResponseSize().toBytes();
		var exchange = http.sendAsync(request, info -> new LimitedBodySubscriber(limit));
		try {
			// The request timeout covers only the response headers; this bounds the body too.
			return exchange.get(properties.getApiTimeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			exchange.cancel(true);
			throw notAnswered(repository, ex);
		}
		catch (InterruptedException ex) {
			exchange.cancel(true);
			Thread.currentThread().interrupt();
			throw new RepositorySnapshotUnavailableException(
					"Reading the file listing of " + repository.fullName() + " was interrupted", ex);
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof ResponseTooLargeException) {
				throw new RepositorySnapshotUnavailableException(
						"GitHub API tree response for " + repository.fullName() + " exceeds "
								+ properties.getMaxTreeResponseSize(), ex.getCause());
			}
			// The client's own request or connect timeout can fire before the wait above; both mean the same thing.
			if (ex.getCause() instanceof HttpTimeoutException) {
				throw notAnswered(repository, ex.getCause());
			}
			throw new RepositorySnapshotUnavailableException(
					"GitHub API is unreachable for " + repository.fullName() + ": "
							+ ex.getCause().getClass().getSimpleName(), ex.getCause());
		}
	}

	private RepositorySnapshotUnavailableException notAnswered(RepositoryIdentity repository, Throwable cause) {
		return new RepositorySnapshotUnavailableException(
				"GitHub API did not answer for " + repository.fullName() + " within " + properties.getApiTimeout(), cause);
	}

	private RepositorySnapshot toSnapshot(byte[] body, RepositoryIdentity repository) {
		final JsonNode tree;
		try {
			tree = json.readTree(body);
		}
		catch (JacksonException ex) {
			throw new RepositorySnapshotUnavailableException(
					"GitHub API returned a tree for " + repository.fullName() + " that is not valid JSON", ex);
		}
		if (tree.path("truncated").asBoolean(false)) {
			// Inference on a partial listing could miss a conflicting build file.
			throw new RepositorySnapshotUnavailableException(
					"GitHub API truncated the root tree of " + repository.fullName());
		}
		var entries = tree.path("tree");
		if (!entries.isArray()) {
			throw new RepositorySnapshotUnavailableException(
					"GitHub API returned a tree for " + repository.fullName() + " without a `tree` array");
		}
		var paths = new HashSet<String>();
		for (var entry : entries) {
			// Directories and submodules are not files at the root.
			if ("blob".equals(entry.path("type").asString(""))) {
				paths.add(entry.path("path").asString(""));
			}
		}
		try {
			return new RepositorySnapshot(paths);
		}
		catch (IllegalArgumentException ex) {
			throw new RepositorySnapshotUnavailableException(
					"GitHub API returned an invalid root listing for " + repository.fullName() + ": " + ex.getMessage(), ex);
		}
	}

	private static String describeStatus(int status, RepositoryIdentity repository) {
		return switch (status) {
			case 401 -> "GitHub API refused access to " + repository.fullName() + " (status " + status
					+ "); check zeroyaml.github.repository.api-token and its repository permissions";
			case 403 -> "GitHub API refused access to " + repository.fullName()
					+ " (status 403); check repository permissions and GitHub API rate limits";
			case 429 -> "GitHub API rate limit reached for " + repository.fullName() + " (status 429)";
			case 404, 409, 422 -> "GitHub API found no readable tree for the pushed commit of " + repository.fullName()
					+ " (status " + status + ")";
			default -> "GitHub API answered status " + status + " for the tree of " + repository.fullName();
		};
	}

	static final class ResponseTooLargeException extends IOException {

		ResponseTooLargeException() {
			super("response body exceeds the configured limit");
		}
	}

	/** Collects the body and fails, cancelling the stream, once it exceeds {@code limit} bytes. */
	private static final class LimitedBodySubscriber implements BodySubscriber<byte[]> {

		private final BodySubscriber<byte[]> delegate = BodySubscribers.ofByteArray();
		private final long limit;
		private Flow.Subscription subscription;
		private long received;
		private boolean failed;

		LimitedBodySubscriber(long limit) {
			this.limit = limit;
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return delegate.getBody();
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			delegate.onSubscribe(subscription);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			if (failed) {
				return;
			}
			for (var buffer : buffers) {
				received += buffer.remaining();
			}
			if (received > limit) {
				failed = true;
				subscription.cancel();
				delegate.onError(new ResponseTooLargeException());
				return;
			}
			delegate.onNext(buffers);
		}

		@Override
		public void onError(Throwable throwable) {
			if (!failed) {
				delegate.onError(throwable);
			}
		}

		@Override
		public void onComplete() {
			if (!failed) {
				delegate.onComplete();
			}
		}
	}
}
