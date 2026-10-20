package com.apismartpulse.auth;

import com.apismartpulse.auth.AuthService.Session;
import com.apismartpulse.config.AppProperties;
import com.apismartpulse.ratelimit.RateLimiters;
import com.apismartpulse.user.UserDtos.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
    name = "app.auth-provider",
    havingValue = "local",
    matchIfMissing = true) // with AUTH_PROVIDER=neon, sign-in happens at Neon Auth
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {
  static final String COOKIE = "asp_refresh";
  static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

  private final AuthService auth;
  private final RateLimiters limiters;
  private final AppProperties props;

  public AuthController(AuthService auth, RateLimiters limiters, AppProperties props) {
    this.auth = auth;
    this.limiters = limiters;
    this.props = props;
  }

  public record RegisterRequest(
      @NotBlank
          @Size(max = 80)
          @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
          String name,
      @NotBlank @Size(max = 254) @Pattern(regexp = EMAIL_PATTERN, message = "must be a valid email")
          String email,
      @NotBlank @Size(max = 128) String password) {}

  public record LoginRequest(
      @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

  public record SessionResponse(
      String accessToken, String tokenType, long expiresIn, UserView user) {}

  @PostMapping("/register")
  @Operation(summary = "Register a MEMBER account and start a session (API-001)")
  public ResponseEntity<SessionResponse> register(
      @Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
    RateLimiters.require(
        limiters.register, http.getRemoteAddr(), "RATE_LIMITED", "Too many registrations.");
    return respond(
        HttpStatus.CREATED, auth.register(req.name(), req.email(), req.password()), http);
  }

  @PostMapping("/login")
  @Operation(summary = "Log in (API-002)")
  public ResponseEntity<SessionResponse> login(
      @Valid @RequestBody LoginRequest req, HttpServletRequest http) {
    RateLimiters.require(
        limiters.login, http.getRemoteAddr(), "RATE_LIMITED", "Too many login attempts.");
    return respond(HttpStatus.OK, auth.login(req.email(), req.password()), http);
  }

  @PostMapping("/refresh")
  @Operation(
      summary = "Rotate the refresh cookie and get a new access token (API-003)",
      description = "Requires the asp_refresh cookie and X-Requested-With: XMLHttpRequest.")
  public ResponseEntity<SessionResponse> refresh(
      @CookieValue(name = COOKIE, required = false) String token,
      @RequestHeader(name = "X-Requested-With", required = false) String requestedWith,
      HttpServletRequest http) {
    if (!"XMLHttpRequest".equals(requestedWith)) throw AuthService.unauthenticated();
    return respond(HttpStatus.OK, auth.refresh(token), http);
  }

  @PostMapping("/logout")
  @Operation(summary = "Revoke the refresh token and clear the cookie (API-004)")
  public ResponseEntity<Void> logout(
      @CookieValue(name = COOKIE, required = false) String token, HttpServletRequest http) {
    auth.logout(token);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, http).toString())
        .build();
  }

  private ResponseEntity<SessionResponse> respond(
      HttpStatus status, Session s, HttpServletRequest http) {
    ResponseCookie c = cookie(s.refreshToken(), Duration.ofDays(props.refreshTtlDays()), http);
    return ResponseEntity.status(status)
        .header(HttpHeaders.SET_COOKIE, c.toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(new SessionResponse(s.accessToken(), "Bearer", s.expiresIn(), s.user()));
  }

  private static ResponseCookie cookie(String value, Duration maxAge, HttpServletRequest http) {
    return ResponseCookie.from(COOKIE, value)
        .httpOnly(true)
        .secure(http.isSecure())
        .sameSite("Strict")
        .path("/api/v1/auth")
        .maxAge(maxAge)
        .build();
  }
}
