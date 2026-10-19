package com.apismartpulse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.apismartpulse.ai.AiServiceClient;
import com.apismartpulse.auth.AuthService;
import com.apismartpulse.ratelimit.RateLimiters;
import com.apismartpulse.user.Role;
import com.apismartpulse.user.User;
import com.apismartpulse.user.UserRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full-stack integration base: real Spring context on a random port, Postgres 16 + pgvector in
 * Testcontainers with the same least-privilege roles as Compose (app runs as asp_app, Flyway as
 * asp_owner), and WireMock standing in for the FastAPI AI service.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.jobs-enabled=false")
public abstract class IntegrationTest {
  protected static final String PASSWORD = "Integration-pw-2026";
  protected static final String APP_DB_PASSWORD = "app-" + UUID.randomUUID();
  protected static final String AI_DB_PASSWORD = "ai-" + UUID.randomUUID();
  private static final String OWNER_DB_PASSWORD = "owner-" + UUID.randomUUID();

  protected static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("apismartpulse")
          .withEnv("DB_OWNER_PASSWORD", OWNER_DB_PASSWORD)
          .withEnv("DB_APP_PASSWORD", APP_DB_PASSWORD)
          .withEnv("DB_AI_PASSWORD", AI_DB_PASSWORD)
          .withCopyFileToContainer(
              MountableFile.forHostPath("../db/init/01-roles.sh", 0755),
              "/docker-entrypoint-initdb.d/01-roles.sh");
  protected static final WireMockServer AI = new WireMockServer(options().dynamicPort());
  protected static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static String passwordHash;

  static {
    DB.start();
    AI.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", () -> "asp_app");
    r.add("spring.datasource.password", () -> APP_DB_PASSWORD);
    r.add("spring.flyway.user", () -> "asp_owner");
    r.add("spring.flyway.password", () -> OWNER_DB_PASSWORD);
    r.add("app.jwt-secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
    r.add("app.internal-service-token", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
    r.add("app.ai-service-url", AI::baseUrl);
  }

  @LocalServerPort protected int port;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected UserRepository users;
  @Autowired protected AuthService auth;
  @Autowired protected RateLimiters limiters;
  @Autowired protected AiServiceClient aiClient;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void resetSharedState() {
    limiters.clearAll();
    aiClient.resetCircuit();
    AI.resetAll();
    AI.stubFor(
        WireMock.post(urlEqualTo("/internal/v1/predict"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"predictedHours\":8.0,\"overdueProbability\":0.2,\"riskLevel\":\"LOW\","
                            + "\"suggestedPriority\":\"MEDIUM\",\"priorityConfidence\":0.5,"
                            + "\"source\":\"MODEL\",\"modelVersion\":\"test-v1\"}")));
    AI.stubFor(
        WireMock.post(anyUrl())
            .atPriority(10)
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  protected record TestUser(long id, String email, String token) {}

  protected TestUser user(Role role) {
    if (passwordHash == null) passwordHash = encoder.encode(PASSWORD);
    String email = "u" + UUID.randomUUID().toString().substring(0, 12) + "@test.local";
    User u = new User(email, "User " + email.substring(1, 6), passwordHash);
    u.setRole(role);
    u = users.save(u);
    return new TestUser(u.getId(), email, auth.login(email, PASSWORD).accessToken());
  }

  protected static String randomKey() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 6; i++) sb.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
    return sb.toString();
  }

  protected String project(TestUser owner, TestUser... members) {
    String key = randomKey();
    Res r = post("/api/v1/projects", owner.token(), Map.of("key", key, "name", "Project " + key));
    if (r.status() != 201) throw new IllegalStateException("project create failed: " + r);
    for (TestUser m : members) {
      post(
          "/api/v1/projects/" + key + "/members",
          owner.token(),
          Map.of("userEmail", m.email(), "capacityHours", 40));
    }
    return key;
  }

  protected JsonNode task(String projectKey, TestUser user, Map<String, Object> body) {
    Res r = post("/api/v1/projects/" + projectKey + "/tasks", user.token(), body);
    if (r.status() != 201) throw new IllegalStateException("task create failed: " + r);
    return r.body();
  }

  protected JsonNode task(String projectKey, TestUser user, String title) {
    return task(projectKey, user, Map.of("title", title));
  }

  // ── HTTP ───────────────────────────────────────────────────────────────────

  protected record Res(int status, JsonNode body, java.net.http.HttpHeaders headers, String raw) {
    public String header(String name) {
      return headers.firstValue(name).orElse(null);
    }

    public String code() {
      return body == null ? null : body.path("code").asString(null);
    }
  }

  protected Res get(String path, String token) {
    return send("GET", path, token, null, Map.of());
  }

  protected Res post(String path, String token, Object body) {
    return send("POST", path, token, body, Map.of());
  }

  protected Res patch(String path, String token, Object body) {
    return send("PATCH", path, token, body, Map.of());
  }

  protected Res put(String path, String token, Object body) {
    return send("PUT", path, token, body, Map.of());
  }

  protected Res delete(String path, String token) {
    return send("DELETE", path, token, null, Map.of());
  }

  protected Res send(
      String method, String path, String token, Object body, Map<String, String> headers) {
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
      if (token != null) b.header("Authorization", "Bearer " + token);
      headers.forEach(b::header);
      if (body != null) {
        b.header("Content-Type", "application/json");
        String s = body instanceof String str ? str : JSON.writeValueAsString(body);
        b.method(method, HttpRequest.BodyPublishers.ofString(s));
      } else {
        b.method(method, HttpRequest.BodyPublishers.noBody());
      }
      HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode json = r.body() == null || r.body().isBlank() ? null : tryParse(r.body());
      return new Res(r.statusCode(), json, r.headers(), r.body());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode tryParse(String s) {
    try {
      return JSON.readTree(s);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
