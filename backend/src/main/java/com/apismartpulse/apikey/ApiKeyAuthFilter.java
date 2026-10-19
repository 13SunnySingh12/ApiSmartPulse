package com.apismartpulse.apikey;

import com.apismartpulse.apikey.ApiKeyService.KeyPrincipal;
import com.apismartpulse.common.Problems;
import com.apismartpulse.ratelimit.RateLimiter;
import com.apismartpulse.ratelimit.RateLimiters;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-API-Key authentication for /api/public/** (FR-043/044). Not a Spring bean on purpose: it is
 * added only to the public filter chain.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-API-Key";

  private final ApiKeyService keys;
  private final RateLimiters limiters;

  public ApiKeyAuthFilter(ApiKeyService keys, RateLimiters limiters) {
    this.keys = keys;
    this.limiters = limiters;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    Optional<KeyPrincipal> principal = keys.authenticate(req.getHeader(HEADER));
    if (principal.isEmpty()) {
      // Slow down key guessing per IP (§8.5) before answering 401.
      ConsumptionProbe probe = limiters.invalidApiKey.consume(req.getRemoteAddr());
      if (!probe.isConsumed()) {
        res.setHeader("Retry-After", String.valueOf(limiters.invalidApiKey.resetSeconds(probe)));
        Problems.write(
            req,
            res,
            HttpStatus.TOO_MANY_REQUESTS,
            "RATE_LIMITED",
            "Too many requests",
            "Too many invalid API key attempts.");
        return;
      }
      Problems.write(
          req,
          res,
          HttpStatus.UNAUTHORIZED,
          "INVALID_API_KEY",
          "Invalid API key",
          "The X-API-Key header is missing, invalid, expired or revoked.");
      return;
    }
    KeyPrincipal key = principal.get();
    RateLimiter limiter = limiters.publicApi;
    ConsumptionProbe probe = limiter.consume("key:" + key.keyId());
    long reset = limiter.resetSeconds(probe);
    res.setHeader("X-RateLimit-Limit", String.valueOf(limiter.capacity()));
    res.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
    res.setHeader("X-RateLimit-Reset", String.valueOf(reset));
    if (!probe.isConsumed()) {
      res.setHeader("Retry-After", String.valueOf(reset));
      Problems.write(
          req,
          res,
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMITED",
          "Rate limit exceeded",
          "This API key allows "
              + limiter.capacity()
              + " requests per minute. Retry after "
              + reset
              + " seconds.");
      return;
    }
    keys.recordUse(key.keyId());
    var auth =
        new UsernamePasswordAuthenticationToken(
            key.owner(), null, List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
    SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      chain.doFilter(req, res);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
