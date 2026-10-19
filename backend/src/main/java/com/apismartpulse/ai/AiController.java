package com.apismartpulse.ai;

import com.apismartpulse.ai.AiFeatureService.ModelsStatus;
import com.apismartpulse.ai.AiServiceClient.AskResult;
import com.apismartpulse.ai.AiServiceClient.SimilarResult;
import com.apismartpulse.ai.AiServiceClient.SplitResult;
import com.apismartpulse.ai.AiServiceClient.TrainResult;
import com.apismartpulse.config.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearer")
public class AiController {
  private final AiFeatureService service;

  public AiController(AiFeatureService service) {
    this.service = service;
  }

  public record AskRequest(
      @NotBlank @Size(max = 500) String question, LocalDate from, LocalDate to) {}

  public record DuplicateCheck(
      @NotBlank @Size(min = 3, max = 200) String title, @Size(max = 10000) String description) {}

  @Tag(name = "AI")
  @PostMapping("/projects/{key}/ask")
  @Operation(summary = "Ask your project (API-080); degraded responses still list sources")
  public AskResult ask(@PathVariable String key, @Valid @RequestBody AskRequest req) {
    return service.ask(key, req.question(), req.from(), req.to(), CurrentUser.get());
  }

  @Tag(name = "AI")
  @PostMapping("/projects/{key}/tasks/duplicate-check")
  @Operation(summary = "Likely duplicates of a draft task (API-081)")
  public SimilarResult duplicates(
      @PathVariable String key, @Valid @RequestBody DuplicateCheck req) {
    return service.duplicates(key, req.title(), req.description(), CurrentUser.get());
  }

  @Tag(name = "AI")
  @PostMapping("/tasks/{taskKey}/split-suggestions")
  @Operation(summary = "Suggested subtasks for a large task (API-082); nothing is saved")
  public SplitResult split(@PathVariable String taskKey) {
    return service.split(taskKey, CurrentUser.get());
  }

  @Tag(name = "Admin")
  @PostMapping("/admin/ml/train")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<TrainResult> train() {
    return ResponseEntity.accepted().body(service.train(CurrentUser.get()));
  }

  @Tag(name = "Admin")
  @GetMapping("/admin/ml/models")
  @PreAuthorize("hasRole('ADMIN')")
  public ModelsStatus models() {
    return service.models();
  }

  @Tag(name = "Admin")
  @PostMapping("/admin/ml/reindex")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Map<String, Integer>> reindex() {
    return ResponseEntity.accepted().body(Map.of("queued", service.reindex()));
  }
}
