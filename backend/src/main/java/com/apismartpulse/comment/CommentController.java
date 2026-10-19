package com.apismartpulse.comment;

import com.apismartpulse.comment.CommentService.CommentView;
import com.apismartpulse.common.PageResponse;
import com.apismartpulse.config.CurrentUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Comments")
@SecurityRequirement(name = "bearer")
public class CommentController {
  private final CommentService service;

  public CommentController(CommentService service) {
    this.service = service;
  }

  public record CommentBody(@NotBlank @Size(max = 5000) String body) {}

  @GetMapping("/tasks/{taskKey}/comments")
  public PageResponse<CommentView> list(
      @PathVariable String taskKey,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return service.list(taskKey, page, size, CurrentUser.get());
  }

  @PostMapping("/tasks/{taskKey}/comments")
  public ResponseEntity<CommentView> add(
      @PathVariable String taskKey, @Valid @RequestBody CommentBody req) {
    return ResponseEntity.status(201).body(service.add(taskKey, req.body(), CurrentUser.get()));
  }

  @PatchMapping("/comments/{id}")
  public CommentView edit(@PathVariable long id, @Valid @RequestBody CommentBody req) {
    return service.edit(id, req.body(), CurrentUser.get());
  }

  @DeleteMapping("/comments/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    service.delete(id, CurrentUser.get());
    return ResponseEntity.noContent().build();
  }
}
