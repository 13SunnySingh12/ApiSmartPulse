package com.apismartpulse.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.IntegrationTest;
import com.apismartpulse.user.User;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AUTH_PROVIDER=neon: Neon Auth tokens (EdDSA, JWKS) are verified, mapped to app users, and every
 * other token is rejected. WireMock serves the JWKS; tokens are signed here with a test key.
 */
class NeonAuthIT extends IntegrationTest {
  private static final String PATH = "/neondb/auth";
  private static final KeyPair KEY = keyPair();
  private static final KeyPair OTHER_KEY = keyPair();

  @DynamicPropertySource
  static void neon(DynamicPropertyRegistry r) {
    r.add("app.auth-provider", () -> "neon");
    r.add("app.neon-auth-url", () -> AI.baseUrl() + PATH);
  }

  @BeforeEach
  void jwks() {
    String x = b64(Arrays.copyOfRange(KEY.getPublic().getEncoded(), 12, 44));
    AI.stubFor(
        WireMock.get(urlEqualTo(PATH + "/.well-known/jwks.json"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"keys\":[{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"kid\":\"k1\",\"x\":\""
                            + x
                            + "\"}]}")));
  }

  private static String origin() {
    return AI.baseUrl();
  }

  private String token(UUID sub, String email, boolean verified) {
    return token(sub, email, verified, Map.of());
  }

  private String token(UUID sub, String email, boolean verified, Map<String, Object> overrides) {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", sub.toString());
    claims.put("id", sub.toString());
    claims.put("email", email);
    claims.put("name", "Neon User");
    claims.put("emailVerified", verified);
    claims.put("role", "authenticated");
    claims.put("iss", origin());
    claims.put("aud", origin());
    claims.put("iat", now);
    claims.put("exp", now + 900);
    claims.putAll(overrides);
    return sign(Map.of("alg", "EdDSA", "kid", "k1", "typ", "JWT"), claims, KEY);
  }

  private static String sign(Map<String, Object> header, Map<String, Object> claims, KeyPair key) {
    try {
      String input =
          b64(JSON.writeValueAsBytes(header)) + "." + b64(JSON.writeValueAsBytes(claims));
      Signature s = Signature.getInstance("Ed25519");
      s.initSign(key.getPrivate());
      s.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + b64(s.sign());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static KeyPair keyPair() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String email() {
    return "n" + UUID.randomUUID().toString().substring(0, 10) + "@example.com";
  }

  @Test
  void validTokenProvisionsOneAppUserWithoutLocalPassword() {
    UUID sub = UUID.randomUUID();
    String email = email();
    String token = token(sub, email, false);

    Res me = get("/api/v1/me", token);
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.body().path("email").asString()).isEqualTo(email);
    assertThat(me.body().path("role").asString()).isEqualTo("MEMBER");
    assertThat(get("/api/v1/me", token).body().path("id").asLong())
        .isEqualTo(me.body().path("id").asLong());

    User u = users.findByAuthUserId(sub).orElseThrow();
    assertThat(u.getPasswordHash()).isNull();
    assertThat(
            jdbc.sql("SELECT count(*) FROM users WHERE lower(email) = ?")
                .param(email)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void rejectsForgedExpiredForeignAndLocalTokens() {
    UUID sub = UUID.randomUUID();
    String email = email();
    long past = Instant.now().minusSeconds(3600).getEpochSecond();
    Map<String, Object> base = new LinkedHashMap<>();
    base.put("sub", sub.toString());
    base.put("email", email);
    base.put("iss", origin());
    base.put("aud", origin());
    base.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
    Map<String, Object> header = Map.of("alg", "EdDSA", "kid", "k1", "typ", "JWT");

    String expired = token(sub, email, true, Map.of("exp", past, "iat", past - 900));
    String wrongIssuer = token(sub, email, true, Map.of("iss", "https://evil.example"));
    String wrongAudience = token(sub, email, true, Map.of("aud", "https://evil.example"));
    String otherKey = sign(header, base, OTHER_KEY);
    String unknownKid = sign(Map.of("alg", "EdDSA", "kid", "nope"), base, KEY);
    String tampered = token(sub, email, true).replaceFirst("\\.[^.]+\\.", ".e30.");
    String localToken = user(com.apismartpulse.user.Role.ADMIN).token(); // HS256 local token

    for (String bad :
        new String[] {
          expired, wrongIssuer, wrongAudience, otherKey, unknownKid, tampered, localToken, "x.y.z"
        }) {
      Res r = get("/api/v1/me", bad);
      assertThat(r.status()).as(bad).isEqualTo(401);
      assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
    }
    assertThat(get("/api/v1/me", null).status()).isEqualTo(401);
    assertThat(users.findByAuthUserId(sub)).isEmpty();
  }

  @Test
  void builtInLoginIsDisabled() {
    Res r = post("/api/v1/auth/login", null, Map.of("email", "a@example.com", "password", "x"));
    assertThat(r.status()).isIn(404, 405);
  }

  @Test
  void deactivatedUsersAreRefused() {
    UUID sub = UUID.randomUUID();
    String token = token(sub, email(), false);
    assertThat(get("/api/v1/me", token).status()).isEqualTo(200);
    jdbc.sql("UPDATE users SET active = false WHERE auth_user_id = ?").param(sub).update();
    Res r = get("/api/v1/me", token);
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.code()).isEqualTo("ACCOUNT_DISABLED");
  }

  @Test
  void linksExistingEmailOnlyWhenVerified() {
    TestUser local = user(com.apismartpulse.user.Role.MANAGER);
    Res unverified = get("/api/v1/me", token(UUID.randomUUID(), local.email(), false));
    assertThat(unverified.status()).isEqualTo(409);
    assertThat(unverified.code()).isEqualTo("ACCOUNT_LINK_CONFLICT");

    UUID sub = UUID.randomUUID();
    Res verified = get("/api/v1/me", token(sub, local.email(), true));
    assertThat(verified.status()).isEqualTo(200);
    assertThat(verified.body().path("id").asLong()).isEqualTo(local.id());
    assertThat(verified.body().path("role").asString()).isEqualTo("MANAGER");

    // A second identity cannot take over an already-linked account.
    Res other = get("/api/v1/me", token(UUID.randomUUID(), local.email(), true));
    assertThat(other.status()).isEqualTo(409);
  }

  @Test
  void usersOnlySeeTheirOwnProjectsAndCannotChangeProviderPasswords() {
    String alice = token(UUID.randomUUID(), email(), false);
    String bob = token(UUID.randomUUID(), email(), false);
    long aliceId = get("/api/v1/me", alice).body().path("id").asLong();
    assertThat(get("/api/v1/me", bob).status()).isEqualTo(200);
    jdbc.sql("UPDATE users SET role = 'MANAGER' WHERE id = ?").param(aliceId).update();
    String key = randomKey();
    assertThat(post("/api/v1/projects", alice, Map.of("key", key, "name", "Alice " + key)).status())
        .isEqualTo(201);

    assertThat(get("/api/v1/projects/" + key, alice).status()).isEqualTo(200);
    assertThat(get("/api/v1/projects/" + key, bob).status()).isIn(403, 404);
    assertThat(get("/api/v1/projects", bob).body().toString()).doesNotContain(key);

    Res pw =
        post(
            "/api/v1/me/password",
            alice,
            Map.of("currentPassword", "whatever-1", "newPassword", "Another-pw-2026"));
    assertThat(pw.status()).isEqualTo(409);
    assertThat(pw.code()).isEqualTo("PASSWORD_MANAGED_EXTERNALLY");
  }
}
