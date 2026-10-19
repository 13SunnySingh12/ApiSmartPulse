package com.apismartpulse.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id")
  private long userId;

  @Column(name = "token_hash")
  private String tokenHash;

  @Column(name = "family_id")
  private UUID familyId;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by_id")
  private Long replacedById;

  @Column(name = "created_at")
  private Instant createdAt;

  protected RefreshToken() {}

  RefreshToken(long userId, String tokenHash, UUID familyId, Instant expiresAt, Instant now) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.familyId = familyId;
    this.expiresAt = expiresAt;
    this.createdAt = now;
  }

  public Long getId() {
    return id;
  }

  public long getUserId() {
    return userId;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  void revoke(Instant now, Long replacedBy) {
    this.revokedAt = now;
    this.replacedById = replacedBy;
  }
}
