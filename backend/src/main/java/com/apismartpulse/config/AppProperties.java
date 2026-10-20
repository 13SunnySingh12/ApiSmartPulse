package com.apismartpulse.config;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All runtime configuration (§5.10). Construction fails fast on weak or placeholder secrets
 * (SEC-034), so a misconfigured service never starts.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    String jwtSecret,
    int jwtAccessTtlMinutes,
    int refreshTtlDays,
    String internalServiceToken,
    String aiServiceUrl,
    List<String> corsAllowedOrigins,
    int publicApiRatePerMinute,
    int aiUserLimitPer10Min,
    double splitMinHours,
    ZoneId timezone,
    String authProvider,
    String neonAuthUrl) {

  public AppProperties {
    requireSecret("JWT_SECRET", jwtSecret);
    requireSecret("INTERNAL_SERVICE_TOKEN", internalServiceToken);
    if (corsAllowedOrigins.stream().anyMatch(o -> o.contains("*"))) {
      throw new IllegalStateException("CORS_ALLOWED_ORIGINS must not contain wildcards");
    }
    if (!"local".equals(authProvider) && !"neon".equals(authProvider)) {
      throw new IllegalStateException("AUTH_PROVIDER must be 'local' or 'neon'");
    }
    boolean secureUrl =
        neonAuthUrl != null
            && (neonAuthUrl.startsWith("https://")
                || neonAuthUrl.startsWith("http://localhost:")
                || neonAuthUrl.startsWith("http://127.0.0.1:")); // loopback for tests only
    if (neonAuth() && !secureUrl) {
      throw new IllegalStateException("NEON_AUTH_URL (https) is required when AUTH_PROVIDER=neon");
    }
  }

  /** Identity comes from Neon Auth (managed Better Auth) instead of the built-in login. */
  public boolean neonAuth() {
    return "neon".equals(authProvider);
  }

  private static void requireSecret(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    String lower = value.toLowerCase();
    if (lower.contains("change-me") || lower.contains("<")) {
      throw new IllegalStateException(name + " still contains a placeholder value");
    }
    if (value.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(name + " must be at least 32 bytes");
    }
  }
}
