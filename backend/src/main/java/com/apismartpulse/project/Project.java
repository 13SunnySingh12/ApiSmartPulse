package com.apismartpulse.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;

/** next_task_number is deliberately unmapped: it is only changed by an atomic SQL UPDATE. */
@Entity
@Table(name = "projects")
public class Project {
  public enum Status {
    ACTIVE,
    ARCHIVED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(updatable = false)
  private String key;

  private String name;
  private String description;

  @Enumerated(EnumType.STRING)
  private Status status = Status.ACTIVE;

  @Column(name = "start_date")
  private LocalDate startDate;

  @Column(name = "target_date")
  private LocalDate targetDate;

  @Column(name = "owner_id")
  private long ownerId;

  @Version private int version;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  protected Project() {}

  public Project(String key, String name, long ownerId) {
    this.key = key;
    this.name = name;
    this.ownerId = ownerId;
  }

  @PrePersist
  void onCreate() {
    createdAt = updatedAt = Instant.now();
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public boolean isArchived() {
    return status == Status.ARCHIVED;
  }

  public Long getId() {
    return id;
  }

  public String getKey() {
    return key;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
    this.archivedAt = status == Status.ARCHIVED ? Instant.now() : null;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public LocalDate getTargetDate() {
    return targetDate;
  }

  public void setTargetDate(LocalDate targetDate) {
    this.targetDate = targetDate;
  }

  public long getOwnerId() {
    return ownerId;
  }

  public int getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
  }
}
