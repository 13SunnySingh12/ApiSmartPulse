package com.apismartpulse.task;

import com.apismartpulse.project.ProjectDtos.UserRef;
import com.apismartpulse.task.Task.Priority;
import com.apismartpulse.task.Task.Status;
import com.apismartpulse.task.TaskEvents.EventView;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TaskDtos {
  private TaskDtos() {}

  public record LabelView(long id, String name, String color) {}

  public record SubtaskSummary(long done, long total) {}

  public record TaskRef(String key, String title, Status status) {}

  public record SubtaskView(long id, String title, boolean done, int position) {}

  public record PredictionView(
      String status,
      BigDecimal predictedHours,
      BigDecimal overdueProbability,
      String riskLevel,
      String suggestedPriority,
      BigDecimal priorityConfidence,
      String source,
      String modelVersion,
      Instant computedAt) {
    public static final PredictionView PENDING =
        new PredictionView("PENDING", null, null, null, null, null, null, null, null);
  }

  public record TaskSummary(
      long id,
      String key,
      String projectKey,
      String title,
      Status status,
      Priority priority,
      LocalDate dueDate,
      UserRef assignee,
      BigDecimal estimateHours,
      BigDecimal actualHours,
      List<LabelView> labels,
      SubtaskSummary subtaskSummary,
      boolean blocked,
      PredictionView prediction,
      int version,
      Instant createdAt,
      Instant updatedAt) {}

  public record TaskDetail(
      long id,
      String key,
      String projectKey,
      boolean projectArchived,
      String title,
      String description,
      Status status,
      Priority priority,
      LocalDate dueDate,
      UserRef assignee,
      UserRef creator,
      BigDecimal estimateHours,
      BigDecimal actualHours,
      List<LabelView> labels,
      SubtaskSummary subtaskSummary,
      List<SubtaskView> subtasks,
      List<TaskRef> blockedBy,
      List<TaskRef> blocks,
      long commentCount,
      PredictionView prediction,
      List<EventView> activity,
      boolean canDelete,
      Instant startedAt,
      Instant completedAt,
      int version,
      Instant createdAt,
      Instant updatedAt) {}

  public record CreateTask(
      @NotBlank @Size(min = 3, max = 200) String title,
      @Size(max = 10000) String description,
      Priority priority,
      LocalDate dueDate,
      Long assigneeId,
      @DecimalMin("0.5") @DecimalMax("400") BigDecimal estimateHours,
      @Size(max = 10) List<Long> labelIds) {}

  public enum Suggestion {
    PRIORITY,
    ESTIMATE
  }

  /** PATCH body; tracks sent fields so null clears (dueDate, assigneeId, estimate…). */
  public static class TaskPatch {
    private final Set<String> present = new HashSet<>();

    @Size(min = 3, max = 200)
    private String title;

    @Size(max = 10000)
    private String description;

    private Priority priority;
    private LocalDate dueDate;
    private Long assigneeId;

    @DecimalMin("0.5")
    @DecimalMax("400")
    private BigDecimal estimateHours;

    @DecimalMin("0.5")
    @DecimalMax("400")
    private BigDecimal actualHours;

    private Status status;
    private Suggestion appliedSuggestion;
    @NotNull private Integer version;

    public boolean has(String f) {
      return present.contains(f);
    }

    public String getTitle() {
      return title;
    }

    public void setTitle(String v) {
      title = v;
      present.add("title");
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String v) {
      description = v;
      present.add("description");
    }

    public Priority getPriority() {
      return priority;
    }

    public void setPriority(Priority v) {
      priority = v;
      present.add("priority");
    }

    public LocalDate getDueDate() {
      return dueDate;
    }

    public void setDueDate(LocalDate v) {
      dueDate = v;
      present.add("dueDate");
    }

    public Long getAssigneeId() {
      return assigneeId;
    }

    public void setAssigneeId(Long v) {
      assigneeId = v;
      present.add("assigneeId");
    }

    public BigDecimal getEstimateHours() {
      return estimateHours;
    }

    public void setEstimateHours(BigDecimal v) {
      estimateHours = v;
      present.add("estimateHours");
    }

    public BigDecimal getActualHours() {
      return actualHours;
    }

    public void setActualHours(BigDecimal v) {
      actualHours = v;
      present.add("actualHours");
    }

    public Status getStatus() {
      return status;
    }

    public void setStatus(Status v) {
      status = v;
      present.add("status");
    }

    public Suggestion getAppliedSuggestion() {
      return appliedSuggestion;
    }

    public void setAppliedSuggestion(Suggestion v) {
      appliedSuggestion = v;
    }

    public Integer getVersion() {
      return version;
    }

    public void setVersion(Integer v) {
      version = v;
    }
  }

  public record StatusChange(
      @NotNull Status status,
      @NotNull Integer version,
      @DecimalMin("0.5") @DecimalMax("400") BigDecimal actualHours) {}

  public record SetLabels(@NotNull @Size(max = 10) List<Long> labelIds) {}
}
