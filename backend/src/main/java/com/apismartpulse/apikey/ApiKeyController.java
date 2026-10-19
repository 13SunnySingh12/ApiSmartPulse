package com.apismartpulse.apikey;

import com.apismartpulse.apikey.ApiKeyService.CreatedKey;
import com.apismartpulse.apikey.ApiKeyService.KeyView;
import com.apismartpulse.config.CurrentUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API keys")
@SecurityRequirement(name = "bearer")
public class ApiKeyController {
  private final ApiKeyService service;

  public ApiKeyController(ApiKeyService service) {
    this.service = service;
  }

  public record CreateKey(@NotBlank @Size(max = 50) String name, Integer expiresInDays) {}

  @PostMapping
  public ResponseEntity<CreatedKey> create(@Valid @RequestBody CreateKey req) {
    return ResponseEntity.status(201)
        .header("Cache-Control", "no-store")
        .body(service.create(req.name(), req.expiresInDays(), CurrentUser.get()));
  }

  @GetMapping
  public List<KeyView> list(@RequestParam(defaultValue = "false") boolean all) {
    return service.list(all, CurrentUser.get());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> revoke(@PathVariable long id) {
    service.revoke(id, CurrentUser.get());
    return ResponseEntity.noContent().build();
  }
}
