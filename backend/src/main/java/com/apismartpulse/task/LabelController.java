package com.apismartpulse.task;

import com.apismartpulse.task.LabelService.LabelPatch;
import com.apismartpulse.task.LabelService.LabelRequest;
import com.apismartpulse.task.TaskDtos.LabelView;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Labels")
@SecurityRequirement(name = "bearer")
public class LabelController {
  private final LabelService service;

  public LabelController(LabelService service) {
    this.service = service;
  }

  @GetMapping("/projects/{key}/labels")
  public List<LabelView> list(@PathVariable String key) {
    return service.list(key);
  }

  @PostMapping("/projects/{key}/labels")
  public ResponseEntity<LabelView> create(
      @PathVariable String key, @Valid @RequestBody LabelRequest req) {
    return ResponseEntity.status(201).body(service.create(key, req));
  }

  @PatchMapping("/labels/{id}")
  public LabelView update(@PathVariable long id, @Valid @RequestBody LabelPatch req) {
    return service.update(id, req);
  }

  @DeleteMapping("/labels/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
