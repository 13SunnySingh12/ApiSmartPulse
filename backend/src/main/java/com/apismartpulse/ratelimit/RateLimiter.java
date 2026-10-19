package com.apismartpulse.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;

/**
 * Fixed-window token buckets (interval refill), so the (N+1)th request inside a window is always
 * rejected (FR-044 acceptance). In-memory token buckets keyed by caller (key id, IP, user id).
 * Limits reset on restart — an accepted trade-off for a single local instance (§5.14).
 */
public final class RateLimiter {
  private final long capacity;
  private final Duration period;
  private final Cache<String, Bucket> buckets;

  public RateLimiter(long capacity, Duration period) {
    this.capacity = capacity;
    this.period = period;
    Duration idle = period.compareTo(Duration.ofMinutes(10)) > 0 ? period : Duration.ofMinutes(10);
    this.buckets = Caffeine.newBuilder().maximumSize(10_000).expireAfterAccess(idle).build();
  }

  /** Drops all buckets (tests run many logins from 127.0.0.1). */
  public void clear() {
    buckets.invalidateAll();
  }

  public long capacity() {
    return capacity;
  }

  public ConsumptionProbe consume(String key) {
    return buckets
        .get(
            key,
            k ->
                Bucket.builder()
                    .addLimit(
                        Bandwidth.builder()
                            .capacity(capacity)
                            .refillIntervally(capacity, period)
                            .build())
                    .build())
        .tryConsumeAndReturnRemaining(1);
  }

  /** Seconds until a token is available (when rejected) or until the bucket is full again. */
  public long resetSeconds(ConsumptionProbe probe) {
    if (!probe.isConsumed()) return Math.max(1, ceilSeconds(probe.getNanosToWaitForRefill()));
    return ceilSeconds(probe.getNanosToWaitForReset());
  }

  private static long ceilSeconds(long nanos) {
    return (nanos + 999_999_999L) / 1_000_000_000L;
  }
}
