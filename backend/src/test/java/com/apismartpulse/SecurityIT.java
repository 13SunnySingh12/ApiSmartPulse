package com.apismartpulse;

import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.user.Role;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** QA-SEC-14, SEC-014/021, §8.11 headers, NFR-014 request ids, least-privilege grants (§6.4). */
class SecurityIT extends IntegrationTest {

  @Test
  void securityHeadersAndRequestIdOnEveryResponse() {
    Res r = get("/api/v1/projects", user(Role.MEMBER).token());
    assertThat(r.header("X-Frame-Options")).isEqualTo("DENY");
    assertThat(r.header("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(r.header("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(r.header("Content-Security-Policy")).contains("frame-ancestors 'none'");
    assertThat(r.header("X-Request-Id")).isNotBlank();
    Res echoed =
        send("GET", "/api/v1/projects", null, null, Map.of("X-Request-Id", "abcdef12-test-id"));
    assertThat(echoed.header("X-Request-Id")).isEqualTo("abcdef12-test-id");
    assertThat(echoed.body().path("requestId").asString()).isEqualTo("abcdef12-test-id");
    assertThat(echoed.header("Content-Type")).startsWith("application/problem+json");
  }

  @Test
  void qaSec14_crossOriginRefreshFromDisallowedOriginIsBlocked() {
    Res bad =
        send(
            "POST",
            "/api/v1/auth/refresh",
            null,
            null,
            Map.of(
                "Origin",
                "https://evil.example",
                "X-Requested-With",
                "XMLHttpRequest",
                "Cookie",
                "asp_refresh=x"));
    assertThat(bad.status()).isEqualTo(403);
    Res preflight =
        send(
            "OPTIONS",
            "/api/v1/auth/refresh",
            null,
            null,
            Map.of(
                "Origin",
                "http://localhost:5173",
                "Access-Control-Request-Method",
                "POST",
                "Access-Control-Request-Headers",
                "x-requested-with"));
    assertThat(preflight.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
    assertThat(preflight.header("Access-Control-Allow-Credentials")).isEqualTo("true");
    Res publicPreflight =
        send(
            "OPTIONS",
            "/api/public/v1/projects",
            null,
            null,
            Map.of("Origin", "http://localhost:5173", "Access-Control-Request-Method", "GET"));
    assertThat(publicPreflight.header("Access-Control-Allow-Origin"))
        .isNull(); // no CORS on the public API
  }

  @Test
  void bodiesOver64KbAre413() {
    String big = "{\"title\":\"" + "x".repeat(70_000) + "\"}";
    Res r = post("/api/v1/projects/ABC/tasks", user(Role.MEMBER).token(), big);
    assertThat(r.status()).isEqualTo(413);
    assertThat(r.code()).isEqualTo("PAYLOAD_TOO_LARGE");
  }

  @Test
  void unexpectedPathsAreDeniedByDefault() {
    assertThat(get("/api/v1/does-not-exist", null).status()).isEqualTo(401);
    assertThat(get("/actuator/env", null).status()).isIn(401, 404);
    assertThat(get("/actuator/health", null).status()).isEqualTo(200);
  }

  private Connection connect(String user, String password) throws SQLException {
    return DriverManager.getConnection(DB.getJdbcUrl(), user, password);
  }

  @Test
  void aiRoleCannotReadSecretsAndAppRoleCannotRewriteHistory() throws Exception {
    try (Connection ai = connect("asp_ai", AI_DB_PASSWORD)) {
      ai.createStatement().executeQuery("SELECT id, name FROM users").close();
      assertDenied(ai, "SELECT password_hash FROM users");
      assertDenied(ai, "SELECT * FROM refresh_tokens");
      assertDenied(ai, "SELECT * FROM api_keys");
      assertDenied(ai, "UPDATE tasks SET title = 'x'");
      ai.createStatement().executeQuery("SELECT count(*) FROM task_embeddings").close();
    }
    try (Connection app = connect("asp_app", APP_DB_PASSWORD)) {
      assertDenied(app, "UPDATE task_events SET to_value = 'x'");
      assertDenied(
          app,
          "INSERT INTO ml_model_versions (model_name, version, status) VALUES ('RISK', 'x', 'RUNNING')");
      assertDenied(app, "CREATE TABLE sneaky (id int)");
    }
  }

  private static void assertDenied(Connection c, String sql) {
    try {
      c.createStatement().execute(sql);
      throw new AssertionError("expected permission denied for: " + sql);
    } catch (SQLException e) {
      assertThat(e.getSQLState()).as(sql).isEqualTo("42501");
    }
  }
}
