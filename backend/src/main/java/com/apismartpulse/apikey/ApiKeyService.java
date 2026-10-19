package com.apismartpulse.apikey;

import com.apismartpulse.common.ApiException;
import com.apismartpulse.common.Hashing;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API keys (F2, BR-011). Format {@code asp_<8-hex prefix>_<32 random bytes base64url>}; only
 * the prefix and SHA-256 of the full key are stored.
 */
@Service
public class ApiKeyService {
  static final int MAX_ACTIVE = 5;
  private static final Pattern FORMAT = Pattern.compile("^asp_([0-9a-f]{8})_([A-Za-z0-9_-]{43})$");

  public record KeyView(
      long id,
      String name,
      String prefix,
      String status,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      long requestCount,
      Instant revokedAt,
      Long ownerId,
      String ownerName) {}

  public record CreatedKey(
      long id,
      String name,
      String key,
      String prefix,
      Instant expiresAt,
      Instant createdAt,
      String notice) {}

  /** Authenticated key: the caller acts as the key's owner, read-only. */
  public record KeyPrincipal(long keyId, CurrentUser owner) {}

  private final JdbcClient jdbc;
  private final Clock clock;
  private final Map<Long, LongAdder> pendingCounts = new ConcurrentHashMap<>();
  private final Map<Long, Instant> pendingLastUsed = new ConcurrentHashMap<>();

  public ApiKeyService(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public CreatedKey create(String name, Integer expiresInDays, CurrentUser user) {
    String clean = name == null ? "" : name.trim();
    if (clean.isEmpty() || clean.length() > 50)
      throw ApiException.validation("name", "must be 1–50 characters");
    if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > 365)) {
      throw ApiException.validation("expiresInDays", "must be between 1 and 365");
    }
    // Lock the owner row so two concurrent creates cannot both pass the limit check.
    jdbc.sql("SELECT id FROM users WHERE id = ? FOR UPDATE")
        .param(user.id())
        .query(Long.class)
        .single();
    long active =
        jdbc.sql(
                "SELECT count(*) FROM api_keys WHERE user_id = ? AND revoked_at IS NULL"
                    + " AND (expires_at IS NULL OR expires_at > ?)")
            .params(user.id(), java.sql.Timestamp.from(clock.instant()))
            .query(Long.class)
            .single();
    if (active >= MAX_ACTIVE) {
      throw ApiException.conflict(
          "KEY_LIMIT", "Key limit reached", "You can have at most 5 active API keys.");
    }
    Instant now = clock.instant();
    Instant expires = expiresInDays == null ? null : now.plus(Duration.ofDays(expiresInDays));
    String prefix = Hashing.randomHex(4);
    String full = "asp_" + prefix + "_" + Hashing.randomBase64Url(32);
    try {
      long id =
          jdbc.sql(
                  "INSERT INTO api_keys (user_id, name, prefix, key_hash, expires_at, created_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id")
              .params(
                  user.id(),
                  clean,
                  prefix,
                  Hashing.sha256Hex(full),
                  expires == null ? null : java.sql.Timestamp.from(expires),
                  java.sql.Timestamp.from(now))
              .query(Long.class)
              .single();
      return new CreatedKey(
          id, clean, full, prefix, expires, now, "Copy this key now. It will not be shown again.");
    } catch (DuplicateKeyException e) {
      throw ApiException.conflict(
          "KEY_NAME_TAKEN",
          "Name in use",
          "You already have an active key named \"" + clean + "\".");
    }
  }

  public List<KeyView> list(boolean all, CurrentUser user) {
    boolean everyone = all && user.isAdmin();
    return jdbc.sql(
            "SELECT k.id, k.name, k.prefix, k.created_at, k.expires_at, k.last_used_at, k.request_count,"
                + " k.revoked_at, u.id, u.name FROM api_keys k JOIN users u ON u.id = k.user_id"
                + (everyone ? "" : " WHERE k.user_id = :uid")
                + " ORDER BY k.revoked_at NULLS FIRST, k.created_at DESC")
        .param("uid", user.id())
        .query(
            (rs, i) -> {
              Instant expires = ts(rs.getTimestamp(5));
              Instant revoked = ts(rs.getTimestamp(8));
              long id = rs.getLong(1);
              String status =
                  revoked != null
                      ? "REVOKED"
                      : expires != null && !expires.isAfter(clock.instant()) ? "EXPIRED" : "ACTIVE";
              Instant pendingUse = pendingLastUsed.get(id);
              Instant lastUsed = ts(rs.getTimestamp(6));
              if (pendingUse != null && (lastUsed == null || pendingUse.isAfter(lastUsed)))
                lastUsed = pendingUse;
              LongAdder pending = pendingCounts.get(id);
              return new KeyView(
                  id,
                  rs.getString(2),
                  rs.getString(3).trim(),
                  status,
                  ts(rs.getTimestamp(4)),
                  expires,
                  lastUsed,
                  rs.getLong(7) + (pending == null ? 0 : pending.sum()),
                  revoked,
                  everyone ? rs.getLong(9) : null,
                  everyone ? rs.getString(10) : null);
            })
        .list();
  }

  /** Revoked keys can never be re-activated (BR-011). Others' keys look absent unless ADMIN. */
  @Transactional
  public void revoke(long id, CurrentUser user) {
    Long owner =
        jdbc.sql("SELECT user_id FROM api_keys WHERE id = ?")
            .param(id)
            .query(Long.class)
            .optional()
            .orElse(null);
    if (owner == null || (owner != user.id() && !user.isAdmin())) throw ApiException.notFound();
    jdbc.sql("UPDATE api_keys SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")
        .params(java.sql.Timestamp.from(clock.instant()), id)
        .update();
  }

  /** Valid, unexpired, unrevoked key of an active user — checked on every request. */
  public Optional<KeyPrincipal> authenticate(String raw) {
    Matcher m = raw == null ? null : FORMAT.matcher(raw);
    if (m == null || !m.matches()) return Optional.empty();
    record Row(
        long id,
        String hash,
        Instant expires,
        Instant revoked,
        long userId,
        String role,
        boolean active) {}
    Optional<Row> row =
        jdbc.sql(
                "SELECT k.id, k.key_hash, k.expires_at, k.revoked_at, u.id, u.role, u.active"
                    + " FROM api_keys k JOIN users u ON u.id = k.user_id WHERE k.prefix = ?")
            .param(m.group(1))
            .query(
                (rs, i) ->
                    new Row(
                        rs.getLong(1),
                        rs.getString(2),
                        ts(rs.getTimestamp(3)),
                        ts(rs.getTimestamp(4)),
                        rs.getLong(5),
                        rs.getString(6),
                        rs.getBoolean(7)))
            .optional();
    Instant now = clock.instant();
    return row.filter(r -> Hashing.equalsConstantTime(r.hash(), Hashing.sha256Hex(raw)))
        .filter(
            r ->
                r.revoked() == null
                    && (r.expires() == null || r.expires().isAfter(now))
                    && r.active())
        .map(r -> new KeyPrincipal(r.id(), new CurrentUser(r.userId(), Role.valueOf(r.role()))));
  }

  public void recordUse(long keyId) {
    pendingCounts.computeIfAbsent(keyId, k -> new LongAdder()).increment();
    pendingLastUsed.put(keyId, clock.instant());
  }

  /** Usage counters are written in batches every 30 s (§6.3 request_count). */
  @Scheduled(fixedDelay = 30_000)
  public void flushUsage() {
    for (Long id : List.copyOf(pendingCounts.keySet())) {
      LongAdder adder = pendingCounts.remove(id);
      Instant last = pendingLastUsed.remove(id);
      if (adder == null) continue;
      jdbc.sql(
              "UPDATE api_keys SET request_count = request_count + ?,"
                  + " last_used_at = greatest(coalesce(last_used_at, ?), ?) WHERE id = ?")
          .params(adder.sum(), java.sql.Timestamp.from(last), java.sql.Timestamp.from(last), id)
          .update();
    }
  }

  private static Instant ts(java.sql.Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
