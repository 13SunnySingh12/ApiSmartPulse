package com.apismartpulse.project;

import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.ProjectDtos.AddMember;
import com.apismartpulse.project.ProjectDtos.CreateProject;
import com.apismartpulse.project.ProjectDtos.MemberView;
import com.apismartpulse.project.ProjectDtos.ProjectPatch;
import com.apismartpulse.project.ProjectDtos.ProjectSummary;
import com.apismartpulse.project.ProjectDtos.ProjectView;
import com.apismartpulse.project.ProjectDtos.UpdateMember;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects")
@SecurityRequirement(name = "bearer")
public class ProjectController {
  private final ProjectService service;

  public ProjectController(ProjectService service) {
    this.service = service;
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
  public ResponseEntity<ProjectView> create(@Valid @RequestBody CreateProject req) {
    ProjectView v = service.create(req, CurrentUser.get());
    return ResponseEntity.created(URI.create("/api/v1/projects/" + v.key())).body(v);
  }

  @GetMapping
  public List<ProjectSummary> list(@RequestParam(defaultValue = "ACTIVE") Project.Status status) {
    return service.list(status, CurrentUser.get());
  }

  @GetMapping("/{key}")
  public ProjectView get(@PathVariable String key) {
    return service.get(key, CurrentUser.get());
  }

  @PatchMapping("/{key}")
  public ProjectView update(@PathVariable String key, @Valid @RequestBody ProjectPatch req) {
    return service.update(key, req, CurrentUser.get());
  }

  @PostMapping("/{key}/archive")
  public ProjectView archive(@PathVariable String key) {
    return service.setArchived(key, true, CurrentUser.get());
  }

  @PostMapping("/{key}/unarchive")
  public ProjectView unarchive(@PathVariable String key) {
    return service.setArchived(key, false, CurrentUser.get());
  }

  @GetMapping("/{key}/members")
  public List<MemberView> members(@PathVariable String key) {
    return service.members(key, CurrentUser.get());
  }

  @PostMapping("/{key}/members")
  public ResponseEntity<MemberView> addMember(
      @PathVariable String key, @Valid @RequestBody AddMember req) {
    return ResponseEntity.status(201).body(service.addMember(key, req, CurrentUser.get()));
  }

  @PatchMapping("/{key}/members/{userId}")
  public MemberView updateMember(
      @PathVariable String key, @PathVariable long userId, @Valid @RequestBody UpdateMember req) {
    return service.updateMember(key, userId, req.capacityHours(), CurrentUser.get());
  }

  @DeleteMapping("/{key}/members/{userId}")
  public ResponseEntity<Void> removeMember(@PathVariable String key, @PathVariable long userId) {
    service.removeMember(key, userId, CurrentUser.get());
    return ResponseEntity.noContent().build();
  }
}
