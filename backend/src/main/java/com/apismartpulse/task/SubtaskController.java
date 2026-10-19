package com.apismartpulse.task;

import com.apismartpulse.ai.PredictionService;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.task.TaskDtos.SubtaskView;
import com.apismartpulse.task.TaskDtos.TaskDetail;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Subtasks")
@SecurityRequirement(name = "bearer")
public class SubtaskController {
  private final SubtaskService service;
  private final TaskService tasks;
  private final PredictionService predictions;

  public SubtaskController(
      SubtaskService service, TaskService tasks, PredictionService predictions) {
    this.service = service;
    this.tasks = tasks;
    this.predictions = predictions;
  }

  public record AddSubtask(@NotBlank @Size(max = 120) String title) {}

  public record BulkAdd(
      @NotNull @Size(min = 1, max = 10) List<@NotBlank @Size(max = 120) String> titles) {}

  public record UpdateSubtask(@Size(min = 1, max = 120) String title, Boolean done) {}

  public record Reorder(@NotNull @Size(max = 50) List<Long> ids) {}

  @PostMapping("/tasks/{taskKey}/subtasks")
  public ResponseEntity<TaskDetail> add(
      @PathVariable String taskKey, @Valid @RequestBody AddSubtask req) {
    return ResponseEntity.status(201)
        .body(after(service.add(taskKey, List.of(req.title()), CurrentUser.get())));
  }

  @PostMapping("/tasks/{taskKey}/subtasks/bulk")
  public ResponseEntity<TaskDetail> bulk(
      @PathVariable String taskKey, @Valid @RequestBody BulkAdd req) {
    return ResponseEntity.status(201)
        .body(after(service.add(taskKey, req.titles(), CurrentUser.get())));
  }

  @PatchMapping("/subtasks/{id}")
  public TaskDetail update(@PathVariable long id, @Valid @RequestBody UpdateSubtask req) {
    return after(service.update(id, req.title(), req.done(), CurrentUser.get()));
  }

  @DeleteMapping("/subtasks/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    long taskId = service.delete(id, CurrentUser.get());
    predictions.refresh(taskId);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/tasks/{taskKey}/subtasks/order")
  public List<SubtaskView> reorder(@PathVariable String taskKey, @Valid @RequestBody Reorder req) {
    return service.reorder(taskKey, req.ids(), CurrentUser.get());
  }

  private TaskDetail after(long taskId) {
    predictions.refresh(taskId);
    return tasks.detail(taskId, CurrentUser.get());
  }
}
