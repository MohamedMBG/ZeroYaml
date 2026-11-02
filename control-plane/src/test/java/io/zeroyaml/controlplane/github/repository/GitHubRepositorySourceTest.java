package io.zeroyaml.controlplane.github.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import io.zeroyaml.controlplane.domain.repository.CommitSha;
import io.zeroyaml.controlplane.domain.repository.RepositoryIdentity;
import io.zeroyaml.controlplane.orchestration.RepositorySnapshotUnavailableException;
import tools.jackson.databind.json.JsonMapper;

/** Reads root listings from a local stand-in for the GitHub Git Trees API. */
class GitHubRepositorySourceTest {

	private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.github("Acme", "App");
	private static final CommitSha REVISION = new CommitSha("0123456789abcdef0123456789abcdef01234567");
	private static final String TREE_PATH = "/repos/acme/app/git/trees/" + REVISION.value();

	private final AtomicReference<Responder> responder = new AtomicReference<>();
	private final AtomicReference<HttpExchange> lastRequest = new AtomicReference<>();
	private final CountDownLatch release = new CountDownLatch(1);
	private HttpServer server;
	private GitHubRepositorySource source;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			lastRequest.set(exchange);
			try (exchange) {
				responder.get().respond(exchange);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		release.countDown();
		if (source != null) {
			source.close();
		}
		server.stop(0);
	}

	@Test
	void readsTheFilesAtTheRootAndSkipsDirectoriesAndSubmodules() {
		respond(200, """
				{"sha": "abc", "truncated": false, "tree": [
				  {"path": "pom.xml", "type": "blob"},
				  {"path": "mvnw", "type": "blob"},
				  {"path": "src", "type": "tree"},
				  {"path": "vendor", "type": "commit"}
				]}
				""");
		source = source(properties(null));

		var snapshot = source.rootSnapshot(REPOSITORY, REVISION);

		assertEquals(Set.of("pom.xml", "mvnw"), snapshot.paths());
		var request = lastRequest.get();
		assertEquals(TREE_PATH, request.getRequestURI().getPath());
		assertEquals("application/vnd.github+json", request.getRequestHeaders().getFirst("Accept"));
		assertEquals(GitHubRepositorySource.API_VERSION, request.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
		assertNull(request.getRequestHeaders().getFirst("Authorization"));
	}

	@Test
	void sendsTheConfiguredTokenAsABearerCredential() {
		respond(200, "{\"truncated\": false, \"tree\": []}");
		source = source(properties("test-token-value"));

		source.rootSnapshot(REPOSITORY, REVISION);

		assertEquals("Bearer test-token-value", lastRequest.get().getRequestHeaders().getFirst("Authorization"));
	}

	@Test
	void refusesATruncatedTree() {
		respond(200, "{\"truncated\": true, \"tree\": [{\"path\": \"pom.xml\", \"type\": \"blob\"}]}");
		source = source(properties(null));

		var failure = assertThrows(RepositorySnapshotUnavailableException.class,
				() -> source.rootSnapshot(REPOSITORY, REVISION));

		assertTrue(failure.getMessage().contains("truncated"));
	}

	@Test
	void reportsAccessAndMissingTreeFailuresWithoutTheResponseBody() {
		source = source(properties("test-token-value"));

		respond(401, "{\"message\": \"Bad credentials secret-detail\"}");
		var unauthorized = assertThrows(RepositorySnapshotUnavailableException.class,
				() -> source.rootSnapshot(REPOSITORY, REVISION));
		respond(404, "{\"message\": \"Not Found\"}");
		var missing = assertThrows(RepositorySnapshotUnavailableException.class,
				() -> source.rootSnapshot(REPOSITORY, REVISION));

		assertTrue(unauthorized.getMessage().contains("zeroyaml.github.repository.api-token"));
		assertTrue(!unauthorized.getMessage().contains("secret-detail"));
		assertTrue(!unauthorized.getMessage().contains("test-token-value"));
		assertTrue(missing.getMessage().contains("status 404"));
	}

	@Test
	void refusesAResponseThatIsNotATree() {
		source = source(properties(null));

		respond(200, "not json");
		assertThrows(RepositorySnapshotUnavailableException.class, () -> source.rootSnapshot(REPOSITORY, REVISION));
		respond(200, "{\"truncated\": false}");
		assertThrows(RepositorySnapshotUnavailableException.class, () -> source.rootSnapshot(REPOSITORY, REVISION));
		respond(200, "{\"truncated\": false, \"tree\": [{\"path\": \"../escape\", \"type\": \"blob\"}]}");
		assertThrows(RepositorySnapshotUnavailableException.class, () -> source.rootSnapshot(REPOSITORY, REVISION));
	}

	@Test
	void refusesABodyLargerThanTheConfiguredLimit() {
		respond(200, "{\"truncated\": false, \"tree\": [], \"padding\": \"" + "x".repeat(4096) + "\"}");
		var properties = properties(null);
		properties.setMaxTreeResponseSize(DataSize.ofBytes(1024));
		source = source(properties);

		var failure = assertThrows(RepositorySnapshotUnavailableException.class,
				() -> source.rootSnapshot(REPOSITORY, REVISION));

		assertTrue(failure.getMessage().contains("exceeds"));
	}

	@Test
	void givesUpWhenTheApiDoesNotAnswerWithinTheTimeout() {
		responder.set(exchange -> {
			try {
				release.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		var properties = properties(null);
		properties.setApiTimeout(Duration.ofMillis(200));
		source = source(properties);

		var failure = assertThrows(RepositorySnapshotUnavailableException.class,
				() -> source.rootSnapshot(REPOSITORY, REVISION));

		assertTrue(failure.getMessage().contains("did not answer"));
	}

	@Test
	void buildsTheCheckoutLocationFromTheTemplate() {
		var properties = properties(null);
		source = source(properties);
		assertEquals(URI.create("https://github.com/acme/app.git"), source.location(REPOSITORY));

		properties.setLocationTemplate("file:///srv/zeroyaml/sources/{owner}/{name}");
		assertEquals(URI.create("file:///srv/zeroyaml/sources/acme/app"), source.location(REPOSITORY));
	}

	private void respond(int status, String body) {
		responder.set(exchange -> {
			var bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
		});
	}

	private GitHubRepositoryProperties properties(String token) {
		var properties = new GitHubRepositoryProperties();
		properties.setApiBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
		properties.setApiToken(token);
		properties.validate();
		return properties;
	}

	private static GitHubRepositorySource source(GitHubRepositoryProperties properties) {
		return new GitHubRepositorySource(properties, JsonMapper.builder().build());
	}

	@FunctionalInterface
	private interface Responder {

		void respond(HttpExchange exchange) throws IOException;
	}
}
