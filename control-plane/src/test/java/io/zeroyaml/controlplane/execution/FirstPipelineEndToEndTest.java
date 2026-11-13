package io.zeroyaml.controlplane.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import io.zeroyaml.controlplane.ControlPlaneApplication;
import io.zeroyaml.controlplane.domain.job.JobId;
import io.zeroyaml.controlplane.registration.RunnerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises the signed push through a real Runner, Docker, logs, and PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "ZEROYAML_E2E_TESTS", matches = "1")
class FirstPipelineEndToEndTest {

	private static final String SECRET = "fixture-webhook-secret-only";

	private static final String KEYSTORE_PASSWORD = "fixture-password";

	private static final String RUNNER_ID = "e2e-runner";

	private static final String INSERT_CONNECTION = """
			INSERT INTO repository_connection (
				provider, owner, name, default_branch, webhook_secret_reference,
				status, connected_at, status_changed_at)
			VALUES ('GITHUB', 'fixture', ?, 'main', 'E2E_SECRET', 'ACTIVE', now(), now())
			""";

	private static final String SELECT_JOBS = """
			SELECT id, step_name, status, runner_id, failure_code, failure_message,
				exit_code, queued_at, started_at, completed_at
			FROM job
			WHERE revision = ?
			ORDER BY step_position
			""";

	private static final String TREE_RESPONSE = """
			{"truncated":false,"tree":[{"type":"blob","path":"pom.xml"},{"type":"blob","path":"mvnw"}]}
			""";

	@Test
	void signedPushRunsSuccessfulAndFailingPipelines(@TempDir Path directory) throws Exception {
		var sources = Files.createDirectories(directory.resolve("sources"));
		var success = repository(sources, "success", false);
		var failure = repository(sources, "failure", true);
		var serverIdentity = identity(directory, "server", "dns:localhost", "serverAuth");
		var runnerIdentity = identity(directory, "runner", "uri:urn:zeroyaml:runner:" + RUNNER_ID, "clientAuth");
		var ports = new Ports(freePort(), freePort(), freePort());
		var runnerOutput = directory.resolve("runner.log");
		var fixtureApi = startFixtureApi(List.of(
				"/repos/fixture/success/git/trees/" + success,
				"/repos/fixture/failure/git/trees/" + failure));
		try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
			postgres.start();
			try (var context = startControlPlane(postgres, fixtureApi, sources, ports, serverIdentity, runnerIdentity)) {
				var runner = startRunner(directory, sources, ports, serverIdentity, runnerIdentity, runnerOutput);
				try {
					var registry = context.getBean(RunnerRegistry.class);
					await("Runner registration", Duration.ofSeconds(60),
							() -> !registry.availableRunners().isEmpty(), runnerOutput);
					await("Runner gRPC listener", Duration.ofSeconds(30),
							() -> listening(ports.runner()), runnerOutput);
					var webhookPort = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
					var jdbc = context.getBean(JdbcTemplate.class);
					var logs = context.getBean(JobLogStore.class);
					for (var name : List.of("success", "failure")) {
						jdbc.update(INSERT_CONNECTION, name);
					}
					verifyScenario(webhookPort, jdbc, logs, success, "success", runnerOutput);
					verifyScenario(webhookPort, jdbc, logs, failure, "failure", runnerOutput);
				} finally {
					stop(runner);
				}
			}
		} finally {
			fixtureApi.stop(0);
		}
	}

	/** Serves only the Git Trees responses for the two fixture revisions, so the test never contacts GitHub. */
	private static HttpServer startFixtureApi(List<String> treePaths) throws IOException {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/repos/fixture/", exchange -> {
			if (!treePaths.contains(exchange.getRequestURI().getPath())) {
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			var body = TREE_RESPONSE.strip().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var response = exchange.getResponseBody()) {
				response.write(body);
			}
		});
		server.start();
		return server;
	}

	private static ConfigurableApplicationContext startControlPlane(PostgreSQLContainer postgres, HttpServer fixtureApi,
			Path sources, Ports ports, Identity serverIdentity, Identity runnerIdentity) {
		return new SpringApplicationBuilder(ControlPlaneApplication.class).run(
				"--server.port=0",
				"--zeroyaml.registration.port=" + ports.registration(),
				"--zeroyaml.runner.host=127.0.0.1",
				"--zeroyaml.runner.port=" + ports.runner(),
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--zeroyaml.github.webhook.secret=" + SECRET,
				"--zeroyaml.github.repository.api-base-url=http://127.0.0.1:" + fixtureApi.getAddress().getPort(),
				"--zeroyaml.github.repository.api-token=",
				"--zeroyaml.github.repository.location-template=" + sources.toUri() + "{owner}/{name}",
				"--zeroyaml.events.job-lifecycle.enabled=false",
				"--zeroyaml.job-logs.read.token=",
				"--zeroyaml.job-logs.port=" + ports.logs(),
				"--zeroyaml.job-logs.certificate=" + serverIdentity.certificate(),
				"--zeroyaml.job-logs.private-key=" + serverIdentity.key(),
				"--zeroyaml.job-logs.client-ca=" + runnerIdentity.certificate());
	}

	private static Process startRunner(Path directory, Path sources, Ports ports, Identity serverIdentity,
			Identity runnerIdentity, Path runnerOutput) throws Exception {
		var runnerDirectory = Path.of("..", "runner").toAbsolutePath().normalize();
		var executable = directory.resolve(isWindows() ? "runner.exe" : "runner");
		command(runnerDirectory, null, "go", "build", "-o", executable.toString(), "./cmd/runner");
		var builder = new ProcessBuilder(executable.toString())
				.directory(runnerDirectory.toFile())
				.redirectErrorStream(true)
				.redirectOutput(runnerOutput.toFile());
		// Developer ZEROYAML_* settings must not leak into the Runner under test.
		builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("ZEROYAML_"));
		builder.environment().putAll(Map.of(
				"ZEROYAML_RUNNER_ID", RUNNER_ID,
				"ZEROYAML_RUNNER_GRPC_ADDRESS", "127.0.0.1:" + ports.runner(),
				"ZEROYAML_CONTROLPLANE_ADDRESS", "127.0.0.1:" + ports.registration(),
				"ZEROYAML_RUNNER_LOCAL_SOURCE_ROOT", sources.toString(),
				"ZEROYAML_RUNNER_JOB_IMAGE", "alpine:3.22",
				"ZEROYAML_JOB_LOGS_ADDRESS", "localhost:" + ports.logs(),
				"ZEROYAML_JOB_LOGS_CERTIFICATE", runnerIdentity.certificate().toString(),
				"ZEROYAML_JOB_LOGS_PRIVATE_KEY", runnerIdentity.key().toString(),
				"ZEROYAML_JOB_LOGS_CA", serverIdentity.certificate().toString()));
		return builder.start();
	}

	private static void stop(Process process) throws InterruptedException {
		process.destroy();
		if (!process.waitFor(15, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			process.waitFor(5, TimeUnit.SECONDS);
		}
	}

	private static void verifyScenario(int port, JdbcTemplate jdbc, JobLogStore logs, String revision, String name,
			Path runnerOutput) throws Exception {
		sendSignedPush(port, revision, name);
		await("pipeline terminal states for " + name, Duration.ofMinutes(3), () -> {
			var states = jdbc.queryForList(
					"SELECT status FROM job WHERE revision = ? ORDER BY step_position", String.class, revision);
			return states.size() == 2
					&& states.stream().allMatch(state -> List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state));
		}, runnerOutput);
		var rows = jdbc.queryForList(SELECT_JOBS, revision);
		var context = diagnostics(rows, runnerOutput);
		assertEquals(List.of("build", "test"), rows.stream().map(row -> row.get("step_name")).toList(), context);
		assertEquals("SUCCEEDED", rows.get(0).get("status"), "build boundary: " + context);
		var expectedTestStatus = name.equals("success") ? "SUCCEEDED" : "FAILED";
		assertEquals(expectedTestStatus, rows.get(1).get("status"), "test boundary: " + context);
		for (var row : rows) {
			var rowContext = diagnostics(List.of(row), runnerOutput);
			assertEquals(RUNNER_ID, row.get("runner_id"), rowContext);
			assertTrue(row.get("queued_at") != null
					&& row.get("started_at") != null
					&& row.get("completed_at") != null, "lifecycle timestamps: " + rowContext);
			var records = logs.snapshot(new JobId((UUID) row.get("id")));
			assertTrue(records.stream().anyMatch(JobLogRecord::endOfOutput),
					"missing final log marker: " + rowContext);
			assertTrue(records.stream()
					.filter(JobLogRecord::endOfOutput)
					.noneMatch(record -> record.truncated() || record.incomplete()),
					"incomplete Job output: " + rowContext);
			var expected = row.get("step_name").equals("build")
					? "fixture---batch-mode -DskipTests package"
					: "fixture---batch-mode test";
			assertTrue(records.stream().anyMatch(record -> record.stream() == JobLogRecord.Stream.STDOUT
					&& new String(record.content(), StandardCharsets.UTF_8).contains(expected)),
					"missing Job output: " + rowContext);
		}
		if (name.equals("failure")) {
			assertEquals("NON_ZERO_EXIT", rows.get(1).get("failure_code"), context);
			assertEquals(7, rows.get(1).get("exit_code"), context);
			assertFalse(((String) rows.get(1).get("failure_message")).isBlank(), context);
		}
	}

	private static void sendSignedPush(int port, String revision, String name) throws Exception {
		var payload = ("{\"ref\":\"refs/heads/main\",\"deleted\":false,\"after\":\"" + revision
				+ "\",\"repository\":{\"owner\":{\"login\":\"fixture\"},\"name\":\"" + name
				+ "\"},\"head_commit\":{\"timestamp\":\"2026-01-01T00:00:00Z\"}}").getBytes(StandardCharsets.UTF_8);
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		var signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/webhooks/github"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.header("X-GitHub-Event", "push")
				.header("X-GitHub-Delivery", UUID.randomUUID().toString())
				.header("X-Hub-Signature-256", signature)
				.POST(HttpRequest.BodyPublishers.ofByteArray(payload))
				.build();
		try (var http = HttpClient.newHttpClient()) {
			var response = http.send(request, HttpResponse.BodyHandlers.ofString());
			assertEquals(202, response.statusCode(), "webhook: " + response.body());
		}
	}

	private static String diagnostics(List<Map<String, Object>> rows, Path runnerOutput) throws IOException {
		return rows + "\nRunner output:\n" + Files.readString(runnerOutput);
	}

	/**
	 * Creates a local repository whose {@code mvnw} stands in for the Maven wrapper. Inference selects
	 * {@code sh mvnw --batch-mode -DskipTests package} for build and {@code sh mvnw --batch-mode test} for
	 * test; the failing fixture exits 7 only for the test arguments, so its build succeeds and its test fails
	 * with a known exit code. The file mode can stay {@code 100644} because the wrapper is run through
	 * {@code sh}.
	 */
	private static String repository(Path sources, String name, boolean fail) throws Exception {
		var path = Files.createDirectories(sources.resolve("fixture").resolve(name));
		command(path, null, "git", "init", "--quiet");
		var pom = "<project/>\n";
		var failTest = fail ? "[ \"$*\" = \"--batch-mode test\" ] && exit 7\n" : "";
		var wrapper = "#!/bin/sh\necho fixture-$*\n" + failTest + "exit 0\n";
		var input = "commit refs/heads/main\n"
				+ "committer Fixture <fixture@example.invalid> 1767225600 +0000\n"
				+ "data 7\nfixture\n"
				+ "M 100644 inline pom.xml\n"
				+ "data " + pom.getBytes(StandardCharsets.UTF_8).length + "\n" + pom
				+ "M 100644 inline mvnw\n"
				+ "data " + wrapper.getBytes(StandardCharsets.UTF_8).length + "\n" + wrapper + "\n";
		command(path, input, "git", "fast-import", "--quiet");
		return command(path, null, "git", "rev-parse", "refs/heads/main").trim();
	}

	private static Identity identity(Path directory, String name, String san, String usage) throws Exception {
		var store = directory.resolve(name + ".p12");
		var keytool = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "keytool.exe" : "keytool");
		command(directory, null, keytool.toString(),
				"-genkeypair",
				"-alias", "test",
				"-keyalg", "EC",
				"-groupname", "secp256r1",
				"-dname", "CN=" + name,
				"-validity", "2",
				"-ext", "SAN=" + san,
				"-ext", "EKU=" + usage,
				"-storetype", "PKCS12",
				"-keystore", store.toString(),
				"-storepass", KEYSTORE_PASSWORD);
		var keys = KeyStore.getInstance("PKCS12");
		try (var input = Files.newInputStream(store)) {
			keys.load(input, KEYSTORE_PASSWORD.toCharArray());
		}
		var certificate = directory.resolve(name + ".pem");
		var key = directory.resolve(name + "-key.pem");
		Files.writeString(certificate, pem("CERTIFICATE", keys.getCertificate("test").getEncoded()));
		Files.writeString(key, pem("PRIVATE KEY", keys.getKey("test", KEYSTORE_PASSWORD.toCharArray()).getEncoded()));
		return new Identity(certificate, key);
	}

	private static String pem(String type, byte[] bytes) {
		var body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
		return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
	}

	private static String command(Path directory, String input, String... command) throws Exception {
		var output = Files.createTempFile(directory, "e2e-command-", ".log");
		var process = new ProcessBuilder(command)
				.directory(directory.toFile())
				.redirectErrorStream(true)
				.redirectOutput(output.toFile())
				.start();
		try (var stdin = process.getOutputStream()) {
			if (input != null) {
				stdin.write(input.getBytes(StandardCharsets.UTF_8));
			}
		}
		if (!process.waitFor(2, TimeUnit.MINUTES)) {
			process.destroyForcibly();
			throw new IllegalStateException(command[0] + " timed out");
		}
		var result = Files.readString(output);
		assertEquals(0, process.exitValue(), command[0] + " failed: " + result);
		return result;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name").startsWith("Windows");
	}

	private static int freePort() throws IOException {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static boolean listening(int port) {
		try (var socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
			return true;
		} catch (IOException ex) {
			return false;
		}
	}

	private static void await(String boundary, Duration timeout, BooleanSupplier condition, Path runnerOutput)
			throws Exception {
		var deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(200);
		}
		throw new AssertionError(boundary + " timed out; Runner output:\n" + Files.readString(runnerOutput));
	}

	private record Identity(Path certificate, Path key) { }

	private record Ports(int runner, int registration, int logs) { }
}
