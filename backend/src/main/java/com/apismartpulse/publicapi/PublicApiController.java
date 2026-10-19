package com.apismartpulse.publicapi;

import com.apismartpulse.analytics.AnalyticsService;
import com.apismartpulse.analytics.AnalyticsService.Workload;
import com.apismartpulse.common.PageResponse;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.Project;
import com.apismartpulse.project.ProjectDtos.ProjectSummary;
import com.apismartpulse.project.ProjectDtos.UserRef;
import com.apismartpulse.project.ProjectService;
import com.apismartpulse.task.Task.Priority;
import com.apismartpulse.task.Task.Status;
import com.apismartpulse.task.TaskDtos.LabelView;
import com.apismartpulse.task.TaskDtos.PredictionView;
import com.apismartpulse.task.TaskDtos.SubtaskSummary;
import com.apismartpulse.task.TaskDtos.TaskDetail;
import com.apismartpulse.task.TaskDtos.TaskRef;
import com.apismartpulse.task.TaskDtos.TaskSummary;
import com.apismartpulse.task.TaskFilter;
import com.apismartpulse.task.TaskQueries;
import com.apismartpulse.task.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only integration API (F2). Visibility follows the key owner's current memberships on every
 * request (SEC-013). Responses omit internal fields such as version and creator id.
 */
@RestController
@RequestMapping("/api/public/v1")
@Tag(
    name = "Public API",
    description = "X-API-Key, 100 requests/minute per key; see X-RateLimit-* headers")
@SecurityRequirement(name = "apiKey")
public class PublicApiController {
  private final ProjectService projects;
  private final TaskService tasks;
  private final TaskQueries queries;
  private final AnalyticsService analytics;

  public PublicApiController(
      ProjectService projects, TaskService tasks, TaskQueries queries, AnalyticsService analytics) {
    this.projects = projects;
    this.tasks = tasks;
    this.queries = queries;
    this.analytics = analytics;
  }

  public record PublicProject(
      String key,
      String name,
      String description,
      Project.Status status,
      LocalDate targetDate,
      long openTasks,
      long atRisk) {}

  public record PublicTask(
      String key,
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
      Instant createdAt,
      Instant updatedAt) {
    static PublicTask of(TaskSummary s) {
      return new PublicTask(
          s.key(),
          s.title(),
          s.status(),
          s.priority(),
          s.dueDate(),
          s.assignee(),
          s.estimateHours(),
          s.actualHours(),
          s.labels(),
          s.subtaskSummary(),
          s.blocked(),
          s.prediction(),
          s.createdAt(),
          s.updatedAt());
    }
  }

  public record PublicTaskDetail(
      String key,
      String projectKey,
      String title,
      String description,
      Status status,
      Priority priority,
      LocalDate dueDate,
      UserRef assignee,
      BigDecimal estimateHours,
      BigDecimal actualHours,
      List<LabelView> labels,
      SubtaskSummary subtaskSummary,
      List<TaskRef> blockedBy,
      List<TaskRef> blocks,
      PredictionView prediction,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}

  @GetMapping("/projects")
  @Operation(summary = "Projects visible to the key owner (API-060)")
  public List<PublicProject> projects() {
    List<ProjectSummary> list = projects.list(Project.Status.ACTIVE, CurrentUser.get());
    return list.stream()
        .map(
            p ->
                new PublicProject(
                    p.key(),
                    p.name(),
                    p.description(),
                    p.status(),
                    p.targetDate(),
                    p.openTasks(),
                    p.atRisk()))
        .toList();
  }

  @GetMapping("/projects/{key}/tasks")
  @Operation(summary = "Tasks with the same filters as the app API (API-061)")
  public PageResponse<PublicTask> tasks(
      @PathVariable String key,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String assigneeId,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String risk,
      @RequestParam(required = false) String label,
      @RequestParam(required = false) String dueBefore,
      @RequestParam(required = false) String dueAfter,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    TaskFilter f =
        TaskFilter.parse(
            status, assigneeId, priority, risk, label, dueBefore, dueAfter, q, sort, page, size);
    return queries
        .list(projects.requireVisible(key, CurrentUser.get()).getId(), f)
        .map(PublicTask::of);
  }

  @GetMapping("/tasks/{taskKey}")
  @Operation(summary = "Task detail including prediction (API-062)")
  public PublicTaskDetail task(@PathVariable String taskKey) {
    TaskDetail d = tasks.detail(taskKey, CurrentUser.get());
    return new PublicTaskDetail(
        d.key(),
        d.projectKey(),
        d.title(),
        d.description(),
        d.status(),
        d.priority(),
        d.dueDate(),
        d.assignee(),
        d.estimateHours(),
        d.actualHours(),
        d.labels(),
        d.subtaskSummary(),
        d.blockedBy(),
        d.blocks(),
        d.prediction(),
        d.createdAt(),
        d.updatedAt(),
        d.completedAt());
  }

  @GetMapping("/projects/{key}/workload")
  @Operation(summary = "Workload summary (API-063)")
  public Workload workload(@PathVariable String key) {
    return analytics.workload(projects.requireVisible(key, CurrentUser.get()).getId());
  }
}
