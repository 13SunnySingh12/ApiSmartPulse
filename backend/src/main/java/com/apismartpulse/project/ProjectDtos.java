package com.apismartpulse.project;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ProjectDtos {
  private ProjectDtos() {}

  public record UserRef(long id, String name) {}

  public record CreateProject(
      @NotBlank @Pattern(regexp = "^[A-Z]{2,6}$", message = "must be 2–6 uppercase letters")
          String key,
      @NotBlank @Size(min = 3, max = 80) String name,
      @Size(max = 2000) String description,
      LocalDate startDate,
      LocalDate targetDate) {}

  /** PATCH body; tracks which fields were sent so null can mean "clear". Key is immutable. */
  public static class ProjectPatch {
    private final Set<String> present = new HashSet<>();

    @Size(min = 3, max = 80)
    private String name;

    @Size(max = 2000)
    private String description;

    private LocalDate startDate;
    private LocalDate targetDate;
    @NotNull private Integer version;

    public boolean has(String field) {
      return present.contains(field);
    }

    public String getName() {
      return name;
    }

    public void setName(String v) {
      name = v;
      present.add("name");
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String v) {
      description = v;
      present.add("description");
    }

    public LocalDate getStartDate() {
      return startDate;
    }

    public void setStartDate(LocalDate v) {
      startDate = v;
      present.add("startDate");
    }

    public LocalDate getTargetDate() {
      return targetDate;
    }

    public void setTargetDate(LocalDate v) {
      targetDate = v;
      present.add("targetDate");
    }

    public Integer getVersion() {
      return version;
    }

    public void setVersion(Integer v) {
      version = v;
    }
  }

  public record ProjectView(
      long id,
      String key,
      String name,
      String description,
      Project.Status status,
      LocalDate startDate,
      LocalDate targetDate,
      UserRef owner,
      int version,
      Instant createdAt,
      Instant archivedAt,
      boolean canManage) {}

  public record ProjectSummary(
      long id,
      String key,
      String name,
      String description,
      Project.Status status,
      LocalDate targetDate,
      long openTasks,
      long atRisk,
      long dueThisWeek,
      long totalTasks,
      long doneTasks,
      long memberCount,
      List<String> memberNames) {}

  public record MemberView(
      long userId,
      String name,
      String email,
      BigDecimal capacityHours,
      boolean owner,
      boolean active,
      Instant joinedAt) {}

  public record AddMember(
      @NotBlank @Size(max = 254) String userEmail,
      @DecimalMin("1") @DecimalMax("80") BigDecimal capacityHours) {}

  public record UpdateMember(
      @NotNull @DecimalMin("1") @DecimalMax("80") BigDecimal capacityHours) {}
}
