package com.apismartpulse.comment;

import com.apismartpulse.ai.AiOutbox;
import com.apismartpulse.common.ApiException;
import com.apismartpulse.common.PageResponse;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.ProjectDtos.UserRef;
import com.apismartpulse.project.ProjectService;
import com.apismartpulse.task.TaskService;
import com.apismartpulse.task.TaskService.Located;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-028: Markdown comments (rendered sanitised by the client), author edits, soft delete. */
@Service
public class CommentService {
  private static final String SELECT =
      "SELECT c.id, c.body, c.created_at, c.updated_at, c.deleted_at, u.id AS uid, u.name, u.active"
          + " FROM comments c JOIN users u ON u.id = c.author_id";

  public record CommentView(
      long id,
      UserRef author,
      String body,
      Instant createdAt,
      Instant updatedAt,
      boolean edited,
      boolean deleted,
      boolean canEdit) {}

  private final TaskService tasks;
  private final AiOutbox outbox;
  private final JdbcClient jdbc;

  public CommentService(TaskService tasks, AiOutbox outbox, JdbcClient jdbc) {
    this.tasks = tasks;
    this.outbox = outbox;
    this.jdbc = jdbc;
  }

  public PageResponse<CommentView> list(String taskKey, int page, int size, CurrentUser user) {
    PageResponse.checkPaging(page, size);
    long taskId = tasks.locate(taskKey, user).task().getId();
    long total =
        jdbc.sql("SELECT count(*) FROM comments WHERE task_id = ?")
            .param(taskId)
            .query(Long.class)
            .single();
    return PageResponse.of(
        jdbc.sql(SELECT + " WHERE c.task_id = ? ORDER BY c.created_at, c.id LIMIT ? OFFSET ?")
            .params(taskId, size, (long) page * size)
            .query((rs, i) -> view(rs, user))
            .list(),
        page,
        size,
        total);
  }

  @Transactional
  public CommentView add(String taskKey, String body, CurrentUser user) {
    Located l = tasks.locateWritable(taskKey, user);
    long id =
        jdbc.sql("INSERT INTO comments (task_id, author_id, body) VALUES (?, ?, ?) RETURNING id")
            .params(l.task().getId(), user.id(), checkBody(body))
            .query(Long.class)
            .single();
    outbox.upsert(AiOutbox.Entity.COMMENT, id);
    return get(id, user);
  }

  @Transactional
  public CommentView edit(long id, String body, CurrentUser user) {
    long author = requireComment(id, user);
    if (author != user.id() && !user.isAdmin())
      throw ApiException.forbidden("Only the author can edit a comment.");
    jdbc.sql("UPDATE comments SET body = ?, updated_at = now() WHERE id = ?")
        .params(checkBody(body), id)
        .update();
    outbox.upsert(AiOutbox.Entity.COMMENT, id);
    return get(id, user);
  }

  @Transactional
  public void delete(long id, CurrentUser user) {
    long author = requireComment(id, user);
    if (author != user.id() && !user.isAdmin()) {
      throw ApiException.forbidden("Only the author or an ADMIN can delete a comment.");
    }
    jdbc.sql("UPDATE comments SET body = '', deleted_at = now() WHERE id = ?").param(id).update();
    outbox.delete(AiOutbox.Entity.COMMENT, id);
  }

  /** Returns the author id after checking the comment's task is visible and writable (SEC-012). */
  private long requireComment(long id, CurrentUser user) {
    record Row(long taskId, long authorId) {}
    Row row =
        jdbc.sql("SELECT task_id, author_id FROM comments WHERE id = ? AND deleted_at IS NULL")
            .param(id)
            .query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2)))
            .optional()
            .orElseThrow(ApiException::notFound);
    ProjectService.requireActive(tasks.locate(row.taskId(), user).project());
    return row.authorId();
  }

  private CommentView get(long id, CurrentUser user) {
    return jdbc.sql(SELECT + " WHERE c.id = ?").param(id).query((rs, i) -> view(rs, user)).single();
  }

  private static CommentView view(ResultSet rs, CurrentUser user) throws SQLException {
    Instant created = rs.getTimestamp("created_at").toInstant();
    Instant updated = rs.getTimestamp("updated_at").toInstant();
    boolean deleted = rs.getTimestamp("deleted_at") != null;
    long authorId = rs.getLong("uid");
    return new CommentView(
        rs.getLong("id"),
        new UserRef(
            authorId, rs.getString("name") + (rs.getBoolean("active") ? "" : " (deactivated)")),
        rs.getString("body"),
        created,
        updated,
        !deleted && updated.isAfter(created.plusSeconds(1)),
        deleted,
        !deleted && (authorId == user.id() || user.isAdmin()));
  }

  private static String checkBody(String body) {
    if (body == null || body.isBlank() || body.length() > 5000) {
      throw ApiException.validation("body", "must be 1–5000 characters");
    }
    return body;
  }
}
