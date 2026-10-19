package com.apismartpulse.ratelimit;

import com.apismartpulse.common.ApiException;
import com.apismartpulse.config.AppProperties;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** The limiters named in §7.4 / §8.5, all using the same Bucket4j mechanism. */
@Component
public class RateLimiters {
  public final RateLimiter publicApi;
  public final RateLimiter login;
  public final RateLimiter register = new RateLimiter(5, Duration.ofHours(1));
  public final RateLimiter invalidApiKey = new RateLimiter(30, Duration.ofMinutes(1));
  public final RateLimiter ai;
  public final RateLimiter duplicateCheck = new RateLimiter(60, Duration.ofMinutes(1));

  /**
   * @param loginPerMinute 10 by default (SEC-004); only the E2E compose override raises it, because
   *     a test run logs in many times from one IP.
   */
  public RateLimiters(
      AppProperties props, @Value("${app.login-rate-per-minute:10}") int loginPerMinute) {
    this.login = new RateLimiter(loginPerMinute, Duration.ofMinutes(1));
    this.publicApi = new RateLimiter(props.publicApiRatePerMinute(), Duration.ofMinutes(1));
    this.ai = new RateLimiter(props.aiUserLimitPer10Min(), Duration.ofMinutes(10));
  }

  public void clearAll() {
    for (RateLimiter l :
        new RateLimiter[] {publicApi, login, register, invalidApiKey, ai, duplicateCheck})
      l.clear();
  }

  /** Consumes one token or throws 429 with Retry-After. */
  public static void require(RateLimiter limiter, String key, String code, String detail) {
    ConsumptionProbe probe = limiter.consume(key);
    if (!probe.isConsumed()) {
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, "Too many requests", detail)
          .with("retryAfter", limiter.resetSeconds(probe));
    }
  }
}
