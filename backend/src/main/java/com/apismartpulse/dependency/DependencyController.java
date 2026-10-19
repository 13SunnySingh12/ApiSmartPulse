package com.apismartpulse.dependency;

import com.apismartpulse.ai.PredictionService;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.task.TaskDtos.TaskDetail;
import com.apismartpulse.task.TaskService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks/{taskKey}/blockers")
@Tag(name = "Dependencies")
@SecurityRequirement(name = "bearer")
public class DependencyController {
  private final DependencyService service;
  private final TaskService tasks;
  private final PredictionService predictions;

  public DependencyController(
      DependencyService service, TaskService tasks, PredictionService predictions) {
    this.service = service;
    this.tasks = tasks;
    this.predictions = predictions;
  }

  public record AddBlocker(@NotBlank String blockerKey) {}

  @PostMapping
  public ResponseEntity<TaskDetail> add(
      @PathVariable String taskKey, @Valid @RequestBody AddBlocker req) {
    long id = service.add(taskKey, req.blockerKey().trim().toUpperCase(), CurrentUser.get());
    predictions.refresh(id);
    return ResponseEntity.status(201).body(tasks.detail(id, CurrentUser.get()));
  }

  @DeleteMapping("/{blockerKey}")
  public ResponseEntity<Void> remove(
      @PathVariable String taskKey, @PathVariable String blockerKey) {
    long id = service.remove(taskKey, blockerKey, CurrentUser.get());
    predictions.refresh(id);
    return ResponseEntity.noContent().build();
  }
}
