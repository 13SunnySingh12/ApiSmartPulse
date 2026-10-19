package com.apismartpulse.analytics;

import com.apismartpulse.analytics.AnalyticsService.Burndown;
import com.apismartpulse.analytics.AnalyticsService.Velocity;
import com.apismartpulse.analytics.AnalyticsService.Workload;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.ProjectService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{key}/analytics")
@Tag(name = "Analytics")
@SecurityRequirement(name = "bearer")
public class AnalyticsController {
  private final AnalyticsService service;
  private final ProjectService projects;

  public AnalyticsController(AnalyticsService service, ProjectService projects) {
    this.service = service;
    this.projects = projects;
  }

  @GetMapping("/workload")
  public Workload workload(@PathVariable String key) {
    return service.workload(projects.requireVisible(key, CurrentUser.get()).getId());
  }

  @GetMapping("/burndown")
  public Burndown burndown(
      @PathVariable String key,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return service.burndown(projects.requireVisible(key, CurrentUser.get()).getId(), from, to);
  }

  @GetMapping("/velocity")
  public Velocity velocity(@PathVariable String key, @RequestParam(defaultValue = "8") int weeks) {
    return service.velocity(projects.requireVisible(key, CurrentUser.get()).getId(), weeks);
  }
}
