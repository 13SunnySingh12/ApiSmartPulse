package com.apismartpulse.task;

import com.apismartpulse.common.ApiException;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.Project;
import com.apismartpulse.project.ProjectService;
import com.apismartpulse.task.TaskDtos.LabelView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Project labels (FR-027); name uniqueness comes from the case-insensitive unique index. */
@Service
public class LabelService {
  static final String COLORS = "^(slate|violet|blue|teal|green|amber|orange|rose)$";

  private final ProjectService projects;
  private final JdbcClient jdbc;

  public LabelService(ProjectService projects, JdbcClient jdbc) {
    this.projects = projects;
    this.jdbc = jdbc;
  }

  public record LabelRequest(
      @NotBlank @Size(max = 30) String name,
      @NotBlank @Pattern(regexp = COLORS, message = "must be one of the 8 palette colours")
          String color) {}

  public record LabelPatch(
      @Size(min = 1, max = 30) String name,
      @Pattern(regexp = COLORS, message = "must be one of the 8 palette colours") String color) {}

  public List<LabelView> list(String key) {
    Project p = projects.requireVisible(key, CurrentUser.get());
    return jdbc.sql("SELECT id, name, color FROM labels WHERE project_id = ? ORDER BY lower(name)")
        .param(p.getId())
        .query((rs, i) -> new LabelView(rs.getLong(1), rs.getString(2), rs.getString(3)))
        .list();
  }

  @Transactional
  public LabelView create(String key, LabelRequest req) {
    Project p = projects.requireWritable(key, CurrentUser.get());
    try {
      long id =
          jdbc.sql("INSERT INTO labels (project_id, name, color) VALUES (?, ?, ?) RETURNING id")
              .params(p.getId(), req.name().trim(), req.color())
              .query(Long.class)
              .single();
      return new LabelView(id, req.name().trim(), req.color());
    } catch (DuplicateKeyException e) {
      throw exists(req.name());
    }
  }

  @Transactional
  public LabelView update(long id, LabelPatch req) {
    requireLabel(id);
    try {
      if (req.name() != null && !req.name().isBlank()) {
        jdbc.sql("UPDATE labels SET name = ? WHERE id = ?").params(req.name().trim(), id).update();
      }
      if (req.color() != null)
        jdbc.sql("UPDATE labels SET color = ? WHERE id = ?").params(req.color(), id).update();
    } catch (DuplicateKeyException e) {
      throw exists(req.name());
    }
    return jdbc.sql("SELECT id, name, color FROM labels WHERE id = ?")
        .param(id)
        .query((rs, i) -> new LabelView(rs.getLong(1), rs.getString(2), rs.getString(3)))
        .single();
  }

  @Transactional
  public void delete(long id) {
    requireLabel(id);
    jdbc.sql("DELETE FROM labels WHERE id = ?").param(id).update();
  }

  /** Object-level check: the label's project must be visible and writable for the caller. */
  private void requireLabel(long id) {
    long projectId =
        jdbc.sql("SELECT project_id FROM labels WHERE id = ?")
            .param(id)
            .query(Long.class)
            .optional()
            .orElseThrow(ApiException::notFound);
    ProjectService.requireActive(projects.requireVisible(projectId, CurrentUser.get()));
  }

  private static ApiException exists(String name) {
    return ApiException.conflict(
        "LABEL_EXISTS", "Label exists", "A label named \"" + name + "\" already exists.");
  }
}
