package com.apismartpulse.task;

import com.apismartpulse.ai.PredictionService;
import com.apismartpulse.common.PageResponse;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.ProjectService;
import com.apismartpulse.task.TaskDtos.CreateTask;
import com.apismartpulse.task.TaskDtos.PredictionView;
import com.apismartpulse.task.TaskDtos.SetLabels;
import com.apismartpulse.task.TaskDtos.StatusChange;
import com.apismartpulse.task.TaskDtos.TaskDetail;
import com.apismartpulse.task.TaskDtos.TaskPatch;
import com.apismartpulse.task.TaskDtos.TaskSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task endpoints. Predictions are refreshed after the write transaction commits, so an AI outage
 * never blocks a save (FR-051).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tasks")
@SecurityRequirement(name = "bearer")
public class TaskController {
  private final TaskService service;
  private final TaskQueries queries;
  private final ProjectService projects;
  private final PredictionService predictions;

  public TaskController(
      TaskService service,
      TaskQueries queries,
      ProjectService projects,
      PredictionService predictions) {
    this.service = service;
    this.queries = queries;
    this.projects = projects;
    this.predictions = predictions;
  }

  @PostMapping("/projects/{key}/tasks")
  @Operation(summary = "Create a task (API-020)")
  public ResponseEntity<TaskDetail> create(
      @PathVariable String key,
      @Valid @RequestBody CreateTask req,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
    CurrentUser user = CurrentUser.get();
    long id = service.create(key, req, idempotencyKey, user);
    predictions.refresh(id);
    TaskDetail d = service.detail(id, user);
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + d.key())).body(d);
  }

  @GetMapping("/projects/{key}/tasks")
  @Operation(summary = "List and filter tasks (API-021)")
  public PageResponse<TaskSummary> list(
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
    return queries.list(projects.requireVisible(key, CurrentUser.get()).getId(), f);
  }

  @GetMapping("/tasks/{taskKey}")
  public TaskDetail get(@PathVariable String taskKey) {
    return service.detail(taskKey, CurrentUser.get());
  }

  @PatchMapping("/tasks/{taskKey}")
  @Operation(summary = "Update task fields; requires the current version (API-023)")
  public TaskDetail update(@PathVariable String taskKey, @Valid @RequestBody TaskPatch req) {
    CurrentUser user = CurrentUser.get();
    long id = service.update(taskKey, req, user);
    predictions.refresh(id);
    return service.detail(id, user);
  }

  @PostMapping("/tasks/{taskKey}/status")
  @Operation(summary = "Change status, e.g. from the board (API-024)")
  public TaskDetail status(@PathVariable String taskKey, @Valid @RequestBody StatusChange req) {
    CurrentUser user = CurrentUser.get();
    long id = service.changeStatus(taskKey, req.status(), req.version(), req.actualHours(), user);
    predictions.refresh(id);
    return service.detail(id, user);
  }

  @DeleteMapping("/tasks/{taskKey}")
  public ResponseEntity<Void> delete(@PathVariable String taskKey) {
    service.delete(taskKey, CurrentUser.get());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/tasks/{taskKey}/labels")
  public TaskDetail setLabels(@PathVariable String taskKey, @Valid @RequestBody SetLabels req) {
    CurrentUser user = CurrentUser.get();
    long id = service.setLabels(taskKey, req.labelIds(), user);
    predictions.refresh(id);
    return service.detail(id, user);
  }

  @GetMapping("/tasks/{taskKey}/prediction")
  public PredictionView prediction(@PathVariable String taskKey) {
    return service.detail(taskKey, CurrentUser.get()).prediction();
  }

  @PostMapping("/tasks/{taskKey}/prediction/refresh")
  public ResponseEntity<PredictionView> refreshPrediction(@PathVariable String taskKey) {
    CurrentUser user = CurrentUser.get();
    long id = service.locate(taskKey, user).task().getId();
    predictions.refresh(id);
    return ResponseEntity.accepted().body(service.detail(id, user).prediction());
  }
}
