package com.apismartpulse.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.IntegrationTest;
import com.apismartpulse.common.Hashing;
import com.apismartpulse.user.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** QA-KEY-01..04, QA-RL-01..03, QA-SEC-11/13, FR-040..044, BR-011, SEC-013. */
class ApiKeyIT extends IntegrationTest {
  @Autowired ApiKeyService service;

  private Res pub(String path, String key) {
    return send("GET", path, null, null, key == null ? Map.of() : Map.of("X-API-Key", key));
  }

  private String createKey(TestUser u, String name) {
    Res r = post("/api/v1/api-keys", u.token(), Map.of("name", name, "expiresInDays", 30));
    assertThat(r.status()).isEqualTo(201);
    return r.body().path("key").asString();
  }

  @Test
  void qaKey01_fullKeyShownOnceAndOnlyHashStored() {
    TestUser u = user(Role.MEMBER);
    Res r = post("/api/v1/api-keys", u.token(), Map.of("name", "ci-script", "expiresInDays", 90));
    String key = r.body().path("key").asString();
    assertThat(key).matches("^asp_[0-9a-f]{8}_[A-Za-z0-9_-]{43}$");
    String list = get("/api/v1/api-keys", u.token()).raw();
    assertThat(list)
        .doesNotContain(key)
        .doesNotContain(Hashing.sha256Hex(key))
        .contains(r.body().path("prefix").asString());
    String stored =
        jdbc.sql("SELECT key_hash FROM api_keys WHERE id = ?")
            .param(r.body().path("id").asLong())
            .query(String.class)
            .single();
    assertThat(stored).isEqualTo(Hashing.sha256Hex(key));
    assertThat(
            post("/api/v1/api-keys", u.token(), Map.of("name", "too-long", "expiresInDays", 366))
                .status())
        .isEqualTo(400);
  }

  @Test
  void qaKey02_sixthActiveKeyIs409() {
    TestUser u = user(Role.MEMBER);
    for (int i = 0; i < 5; i++) createKey(u, "key-" + i);
    Res sixth = post("/api/v1/api-keys", u.token(), Map.of("name", "key-6"));
    assertThat(sixth.status()).isEqualTo(409);
    assertThat(sixth.code()).isEqualTo("KEY_LIMIT");
  }

  @Test
  void qaKey03_qaSec11_expiredAndRevokedKeysAre401() {
    TestUser m = user(Role.MANAGER);
    String project = project(m);
    String key = createKey(m, "lifecycle");
    assertThat(pub("/api/public/v1/projects", key).status()).isEqualTo(200);
    jdbc.sql(
            "UPDATE api_keys SET expires_at = now() - interval '1 minute', created_at = now() - interval '2 days' WHERE prefix = ?")
        .param(key.substring(4, 12))
        .update();
    assertThat(pub("/api/public/v1/projects", key).status()).isEqualTo(401);

    String key2 = createKey(m, "revoke-me");
    assertThat(pub("/api/public/v1/projects/" + project + "/tasks", key2).status()).isEqualTo(200);
    long id = get("/api/v1/api-keys", m.token()).body().get(0).path("id").asLong();
    assertThat(delete("/api/v1/api-keys/" + id, m.token()).status()).isEqualTo(204);
    Res revoked = pub("/api/public/v1/projects", key2);
    assertThat(revoked.status()).isEqualTo(401);
    assertThat(revoked.code()).isEqualTo("INVALID_API_KEY");
  }

  @Test
  void qaKey04_keysAndJwtsAreNotInterchangeable() {
    TestUser u = user(Role.MEMBER);
    String key = createKey(u, "separate");
    assertThat(send("GET", "/api/v1/projects", null, null, Map.of("X-API-Key", key)).status())
        .isEqualTo(401);
    assertThat(get("/api/public/v1/projects", u.token()).status()).isEqualTo(401);
    assertThat(pub("/api/public/v1/projects", null).status()).isEqualTo(401);
    assertThat(pub("/api/public/v1/projects", "asp_00000000_" + "x".repeat(43)).status())
        .isEqualTo(401);
  }

  @Test
  void publicApiIsReadOnlyAndScopedToOwnersMemberships() {
    TestUser m = user(Role.MANAGER);
    TestUser member = user(Role.MEMBER);
    String visible = project(m, member);
    String hidden = project(m);
    String taskKey = task(visible, m, "Public task").path("key").asString();
    String key = createKey(member, "reader");
    assertThat(pub("/api/public/v1/projects", key).raw()).contains(visible).doesNotContain(hidden);
    Res detail = pub("/api/public/v1/tasks/" + taskKey, key);
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.body().has("version")).isFalse();
    assertThat(detail.body().path("prediction").path("status").asString()).isEqualTo("OK");
    assertThat(pub("/api/public/v1/projects/" + hidden + "/tasks", key).status()).isEqualTo(404);
    assertThat(pub("/api/public/v1/projects/" + visible + "/workload", key).status())
        .isEqualTo(200);
    Res write =
        send(
            "POST",
            "/api/public/v1/projects/" + visible + "/tasks",
            null,
            Map.of("title", "Sneaky"),
            Map.of("X-API-Key", key));
    assertThat(write.status()).isIn(404, 405);
    // membership is evaluated per request (SEC-013)
    delete("/api/v1/projects/" + visible + "/members/" + member.id(), m.token());
    assertThat(pub("/api/public/v1/tasks/" + taskKey, key).status()).isEqualTo(404);
  }

  @Test
  void qaRl01_02_03_perKeyLimitWithHeaders() {
    TestUser u = user(Role.MEMBER);
    String k1 = createKey(u, "rl-one");
    String k2 = createKey(u, "rl-two");
    Res first = pub("/api/public/v1/projects", k1);
    assertThat(first.header("X-RateLimit-Limit")).isEqualTo("100");
    assertThat(first.header("X-RateLimit-Remaining")).isEqualTo("99");
    Res last = first;
    for (int i = 0; i < 99; i++) last = pub("/api/public/v1/projects", k1);
    assertThat(last.status()).isEqualTo(200);
    assertThat(last.header("X-RateLimit-Remaining")).isEqualTo("0");
    Res limited = pub("/api/public/v1/projects", k1);
    assertThat(limited.status()).isEqualTo(429);
    assertThat(limited.code()).isEqualTo("RATE_LIMITED");
    assertThat(Integer.parseInt(limited.header("Retry-After"))).isPositive();
    assertThat(pub("/api/public/v1/projects", k2).status()).isEqualTo(200); // independent bucket
  }

  @Test
  void usageCountersAreFlushed() {
    TestUser u = user(Role.MEMBER);
    String key = createKey(u, "usage");
    for (int i = 0; i < 3; i++) pub("/api/public/v1/projects", key);
    service.flushUsage();
    long count =
        jdbc.sql("SELECT request_count FROM api_keys WHERE prefix = ?")
            .param(key.substring(4, 12))
            .query(Long.class)
            .single();
    assertThat(count).isEqualTo(3);
    assertThat(get("/api/v1/api-keys", u.token()).body().get(0).path("lastUsedAt").isNull())
        .isFalse();
  }

  @Test
  void onlyOwnerOrAdminCanRevoke() {
    TestUser a = user(Role.MEMBER);
    TestUser b = user(Role.MEMBER);
    createKey(a, "mine");
    long id = get("/api/v1/api-keys", a.token()).body().get(0).path("id").asLong();
    assertThat(delete("/api/v1/api-keys/" + id, b.token()).status()).isEqualTo(404);
    TestUser admin = user(Role.ADMIN);
    assertThat(get("/api/v1/api-keys?all=true", admin.token()).raw()).contains("\"ownerName\"");
    assertThat(delete("/api/v1/api-keys/" + id, admin.token()).status()).isEqualTo(204);
  }
}
