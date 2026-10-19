package com.apismartpulse.auth;

import com.apismartpulse.common.ApiException;
import com.apismartpulse.common.Hashing;
import com.apismartpulse.config.AppProperties;
import com.apismartpulse.config.SecurityConfig;
import com.apismartpulse.user.User;
import com.apismartpulse.user.UserDtos.UserView;
import com.apismartpulse.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-001..003, SEC-001..005. */
@Service
public class AuthService {
  static final int MAX_FAILED_LOGINS = 5;
  static final Duration LOCK_DURATION = Duration.ofMinutes(15);

  private final UserRepository users;
  private final RefreshTokenRepository tokens;
  private final PasswordEncoder encoder;
  private final PasswordPolicy policy;
  private final JwtEncoder jwt;
  private final AppProperties props;
  private final Clock clock;
  // Hash compared for unknown emails so response timing does not reveal which emails exist.
  private final String dummyHash;

  public AuthService(
      UserRepository users,
      RefreshTokenRepository tokens,
      PasswordEncoder encoder,
      PasswordPolicy policy,
      JwtEncoder jwt,
      AppProperties props,
      Clock clock) {
    this.users = users;
    this.tokens = tokens;
    this.encoder = encoder;
    this.policy = policy;
    this.jwt = jwt;
    this.props = props;
    this.clock = clock;
    this.dummyHash = encoder.encode(Hashing.randomHex(16));
  }

  public record Session(String accessToken, long expiresIn, UserView user, String refreshToken) {}

  public static String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  @Transactional
  public Session register(String name, String email, String password) {
    String normalized = normalizeEmail(email);
    policy.check("password", password, normalized);
    if (users.findByEmailIgnoreCase(normalized).isPresent()) {
      encoder.matches(password, dummyHash); // same timing profile as a new registration
      throw ApiException.conflict(
          "EMAIL_TAKEN", "Email already registered", "An account with this email already exists.");
    }
    User user = users.save(new User(normalized, name.trim(), encoder.encode(password)));
    return issue(user, UUID.randomUUID());
  }

  @Transactional(noRollbackFor = ApiException.class)
  public Session login(String email, String password) {
    Instant now = clock.instant();
    User user = users.findByEmailIgnoreCase(normalizeEmail(email)).orElse(null);
    if (user == null) {
      encoder.matches(password, dummyHash);
      throw invalidCredentials();
    }
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
      throw new ApiException(
              HttpStatus.LOCKED,
              "ACCOUNT_LOCKED",
              "Account temporarily locked",
              "Too many failed attempts. Try again later.")
          .with("retryAfter", Duration.between(now, user.getLockedUntil()).toSeconds() + 1);
    }
    boolean ok = encoder.matches(password, user.getPasswordHash());
    if (!ok || !user.isActive()) {
      if (!ok) {
        int failures = user.getFailedLoginCount() + 1;
        if (failures >= MAX_FAILED_LOGINS) {
          user.setLockedUntil(now.plus(LOCK_DURATION));
          failures = 0;
        }
        user.setFailedLoginCount(failures);
      }
      throw invalidCredentials();
    }
    user.setFailedLoginCount(0);
    user.setLockedUntil(null);
    user.setLastLoginAt(now);
    return issue(user, UUID.randomUUID());
  }

  /** Rotates the refresh token; reuse of a rotated token revokes every session of the user. */
  @Transactional(noRollbackFor = ApiException.class)
  public Session refresh(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) throw unauthenticated();
    Instant now = clock.instant();
    RefreshToken current =
        tokens.findForUpdate(Hashing.sha256Hex(rawToken)).orElseThrow(AuthService::unauthenticated);
    if (current.getRevokedAt() != null) {
      tokens.revokeAllForUser(current.getUserId(), now); // theft detection (SEC-003)
      throw unauthenticated();
    }
    if (current.getExpiresAt().isBefore(now)) throw unauthenticated();
    User user = users.findById(current.getUserId()).orElseThrow(AuthService::unauthenticated);
    if (!user.isActive()) throw unauthenticated();
    Issued next = issueTokens(user, current.getFamilyId());
    current.revoke(now, next.token().getId());
    return next.session();
  }

  @Transactional
  public void logout(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) return;
    tokens
        .findForUpdate(Hashing.sha256Hex(rawToken))
        .filter(t -> t.getRevokedAt() == null)
        .ifPresent(t -> t.revoke(clock.instant(), null));
  }

  @Transactional
  public void revokeAllSessions(long userId) {
    tokens.revokeAllForUser(userId, clock.instant());
  }

  private Session issue(User user, UUID family) {
    return issueTokens(user, family).session();
  }

  private record Issued(Session session, RefreshToken token) {}

  private Issued issueTokens(User user, UUID family) {
    Instant now = clock.instant();
    long ttl = props.jwtAccessTtlMinutes() * 60L;
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(SecurityConfig.ISSUER)
            .subject(user.getId().toString())
            .claim("role", user.getRole().name())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(ttl))
            .id(UUID.randomUUID().toString())
            .build();
    String access =
        jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    String refresh = Hashing.randomBase64Url(32);
    RefreshToken saved =
        tokens.save(
            new RefreshToken(
                user.getId(),
                Hashing.sha256Hex(refresh),
                family,
                now.plus(Duration.ofDays(props.refreshTtlDays())),
                now));
    return new Issued(new Session(access, ttl, UserView.of(user), refresh), saved);
  }

  private static ApiException invalidCredentials() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED,
        "INVALID_CREDENTIALS",
        "Login failed",
        "Email or password is incorrect.");
  }

  static ApiException unauthenticated() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Session expired", "Please log in again.");
  }
}
