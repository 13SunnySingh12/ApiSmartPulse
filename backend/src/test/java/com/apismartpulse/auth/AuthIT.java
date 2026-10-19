package com.apismartpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.IntegrationTest;
import com.apismartpulse.user.Role;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** QA-AUTH-01..07, QA-SEC-01/02/05/06/16, FR-004 last-admin rule. */
class AuthIT extends IntegrationTest {

  private static String email() {
    return "Reg." + UUID.randomUUID().toString().substring(0, 8) + "@Test.Local";
  }

  private Res register(String email, String password) {
    return post(
        "/api/v1/auth/register",
        null,
        Map.of("name", "New Person", "email", email, "password", password));
  }

  private Res login(String email, String password) {
    return post("/api/v1/auth/login", null, Map.of("email", email, "password", password));
  }

  private static String cookie(Res r) {
    String set = r.header("Set-Cookie");
    return set == null ? null : set.split(";")[0];
  }

  private Res refresh(String cookie, boolean header) {
    Map<String, String> h =
        header
            ? Map.of("Cookie", cookie, "X-Requested-With", "XMLHttpRequest")
            : Map.of("Cookie", cookie);
    return send("POST", "/api/v1/auth/refresh", null, null, h);
  }

  @Test
  void qaAuth01_registerCreatesMemberWithBcryptHash() {
    String email = email();
    Res r = register(email, PASSWORD);
    assertThat(r.status()).isEqualTo(201);
    assertThat(r.body().path("user").path("role").asString()).isEqualTo("MEMBER");
    assertThat(r.body().path("user").path("email").asString())
        .isEqualTo(email.toLowerCase()); // BR-001
    String hash =
        jdbc.sql("SELECT password_hash FROM users WHERE email = ?")
            .param(email.toLowerCase())
            .query(String.class)
            .single();
    assertThat(hash).startsWith("$2");
  }

  @Test
  void qaAuth02_duplicateEmailDifferentCaseIs409() {
    String email = email();
    register(email, PASSWORD);
    Res r = register(email.toUpperCase(), PASSWORD);
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.code()).isEqualTo("EMAIL_TAKEN");
  }

  @Test
  void qaAuth03_commonOrWeakPasswordsRejected() {
    for (String pw : new String[] {"password123", "short1", "onlylettersxx", "1234567890123"}) {
      Res r = register(email(), pw);
      assertThat(r.status()).as(pw).isEqualTo(400);
      assertThat(r.body().path("errors").get(0).path("field").asString()).isEqualTo("password");
    }
    String email = email();
    assertThat(register(email, email).status()).isEqualTo(400); // equals email
  }

  @Test
  void qaAuth04_loginReturnsShortJwtAndStrictHttpOnlyCookie() {
    String email = email();
    register(email, PASSWORD);
    Res r = login(email, PASSWORD);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("expiresIn").asInt()).isEqualTo(900);
    assertThat(r.body().path("accessToken").asString()).contains(".");
    String set = r.header("Set-Cookie");
    assertThat(set)
        .contains("asp_refresh=")
        .contains("HttpOnly")
        .contains("SameSite=Strict")
        .contains("Path=/api/v1/auth");
  }

  @Test
  void wrongPasswordIsGeneric401AndUnknownEmailTheSame() {
    String email = email();
    register(email, PASSWORD);
    Res wrong = login(email, "Wrong-password-1");
    Res unknown = login("nobody-" + UUID.randomUUID() + "@test.local", PASSWORD);
    assertThat(wrong.status()).isEqualTo(401);
    assertThat(unknown.status()).isEqualTo(401);
    assertThat(wrong.body().path("detail").asString())
        .isEqualTo(unknown.body().path("detail").asString());
  }

  @Test
  void qaAuth05_refreshRotatesAndRevokesOld() {
    String email = email();
    Res reg = register(email, PASSWORD);
    String c1 = cookie(reg);
    Res r = refresh(c1, true);
    assertThat(r.status()).isEqualTo(200);
    String c2 = cookie(r);
    assertThat(c2).isNotEqualTo(c1);
    long revoked =
        jdbc.sql(
                "SELECT count(*) FROM refresh_tokens t JOIN users u ON u.id = t.user_id WHERE u.email = ? AND t.revoked_at IS NOT NULL")
            .param(email.toLowerCase())
            .query(Long.class)
            .single();
    assertThat(revoked).isEqualTo(1);
  }

  @Test
  void qaAuth06_refreshWithoutCustomHeaderIsRejected() {
    Res reg = register(email(), PASSWORD);
    assertThat(refresh(cookie(reg), false).status()).isEqualTo(401);
  }

  @Test
  void qaAuth07_logoutThenRefreshIs401() {
    Res reg = register(email(), PASSWORD);
    String c = cookie(reg);
    Res out =
        send(
            "POST",
            "/api/v1/auth/logout",
            null,
            null,
            Map.of("Cookie", c, "X-Requested-With", "XMLHttpRequest"));
    assertThat(out.status()).isEqualTo(204);
    assertThat(out.header("Set-Cookie")).contains("Max-Age=0");
    assertThat(refresh(c, true).status()).isEqualTo(401);
  }

  @Test
  void qaSec06_reuseOfRotatedTokenRevokesNewerTokenToo() {
    Res reg = register(email(), PASSWORD);
    String c1 = cookie(reg);
    String c2 = cookie(refresh(c1, true));
    assertThat(refresh(c1, true).status()).isEqualTo(401); // replay
    assertThat(refresh(c2, true).status()).isEqualTo(401); // family revoked
  }

  @Test
  void qaSec05_lockoutAfterFiveFailures() {
    String email = email();
    register(email, PASSWORD);
    for (int i = 0; i < 5; i++)
      assertThat(login(email, "Wrong-password-" + i).status()).isEqualTo(401);
    Res sixth = login(email, PASSWORD);
    assertThat(sixth.status()).isEqualTo(423);
    assertThat(sixth.code()).isEqualTo("ACCOUNT_LOCKED");
    assertThat(sixth.header("Retry-After")).isNotNull();
  }

  @Test
  void loginRateLimitedPerIp() {
    String email = email();
    register(email, PASSWORD);
    int last = 0;
    for (int i = 0; i < 11; i++) last = login(email, PASSWORD).status();
    assertThat(last).isEqualTo(429);
  }

  @Test
  void qaSec01_02_missingOrTamperedTokenIs401() {
    assertThat(get("/api/v1/projects", null).status()).isEqualTo(401);
    TestUser u = user(Role.MEMBER);
    String[] parts = u.token().split("\\.");
    String payload =
        new String(java.util.Base64.getUrlDecoder().decode(parts[1])).replace("MEMBER", "ADMIN");
    String forged =
        parts[0]
            + "."
            + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes())
            + "."
            + parts[2];
    Res r = get("/api/v1/admin/users", forged);
    assertThat(r.status()).isEqualTo(401);
    assertThat(get("/api/v1/projects", u.token()).status()).isEqualTo(200);
  }

  @Test
  void qaSec16_massAssignmentOfRoleIsRejected() {
    TestUser u = user(Role.MEMBER);
    Res r = patch("/api/v1/me", u.token(), Map.of("name", "Renamed", "role", "ADMIN"));
    assertThat(r.status()).isEqualTo(400);
    assertThat(users.findById(u.id()).orElseThrow().getRole()).isEqualTo(Role.MEMBER);
    assertThat(
            patch("/api/v1/me", u.token(), Map.of("name", "Renamed"))
                .body()
                .path("name")
                .asString())
        .isEqualTo("Renamed");
  }

  @Test
  void fr005_changePasswordNeedsCurrentPassword() {
    TestUser u = user(Role.MEMBER);
    assertThat(
            post(
                    "/api/v1/me/password",
                    u.token(),
                    Map.of("currentPassword", "nope-nope-1", "newPassword", "Another-pw-2026"))
                .status())
        .isEqualTo(400);
    assertThat(
            post(
                    "/api/v1/me/password",
                    u.token(),
                    Map.of("currentPassword", PASSWORD, "newPassword", "Another-pw-2026"))
                .status())
        .isEqualTo(204);
  }

  @Test
  void fr004_adminManagesRolesAndLastAdminCannotDemoteSelf() {
    jdbc.sql("UPDATE users SET role = 'MEMBER' WHERE role = 'ADMIN'")
        .update(); // isolate: exactly one admin below
    TestUser admin = user(Role.ADMIN);
    TestUser member = user(Role.MEMBER);
    assertThat(get("/api/v1/admin/users", member.token()).status()).isEqualTo(403);
    Res promote =
        patch("/api/v1/admin/users/" + member.id(), admin.token(), Map.of("role", "MANAGER"));
    assertThat(promote.status()).isEqualTo(200);
    assertThat(promote.body().path("role").asString()).isEqualTo("MANAGER");
    Res demote =
        patch("/api/v1/admin/users/" + admin.id(), admin.token(), Map.of("role", "MEMBER"));
    assertThat(demote.status()).isEqualTo(409);
    assertThat(demote.code()).isEqualTo("LAST_ADMIN");
    // deactivation blocks login and revokes sessions
    assertThat(
            patch("/api/v1/admin/users/" + member.id(), admin.token(), Map.of("active", false))
                .status())
        .isEqualTo(200);
    assertThat(login(member.email(), PASSWORD).status()).isEqualTo(401);
  }
}
