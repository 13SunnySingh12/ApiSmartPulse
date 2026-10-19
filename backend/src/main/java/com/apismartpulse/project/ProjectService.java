package com.apismartpulse.project;

import com.apismartpulse.auth.AuthService;
import com.apismartpulse.common.ApiException;
import com.apismartpulse.config.AppProperties;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.ProjectDtos.AddMember;
import com.apismartpulse.project.ProjectDtos.CreateProject;
import com.apismartpulse.project.ProjectDtos.MemberView;
import com.apismartpulse.project.ProjectDtos.ProjectPatch;
import com.apismartpulse.project.ProjectDtos.ProjectSummary;
import com.apismartpulse.project.ProjectDtos.ProjectView;
import com.apismartpulse.project.ProjectDtos.UserRef;
import com.apismartpulse.task.TaskEvents;
import com.apismartpulse.user.User;
import com.apismartpulse.user.UserRepository;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects and membership (FR-010..013). Non-members get 404, never 403 (SEC-011). */
@Service
public class ProjectService {
  private static final BigDecimal DEFAULT_CAPACITY = BigDecimal.valueOf(40);

  private final ProjectRepository projects;
  private final UserRepository users;
  private final JdbcClient jdbc;
  private final TaskEvents events;
  private final AppProperties props;
  private final Clock clock;

  public ProjectService(
      ProjectRepository projects,
      UserRepository users,
      JdbcClient jdbc,
      TaskEvents events,
      AppProperties props,
      Clock clock) {
    this.projects = projects;
    this.users = users;
    this.jdbc = jdbc;
    this.events = events;
    this.props = props;
    this.clock = clock;
  }

  // ── access checks ────────────────────────────────────────────────────────────

  public boolean isMember(long projectId, long userId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM project_members WHERE project_id = ? AND user_id = ?)")
        .params(projectId, userId)
        .query(Boolean.class)
        .single();
  }

  /** Project visible to the caller (member, or any project for ADMIN), else 404. */
  public Project requireVisible(String key, CurrentUser user) {
    Project p = projects.findByKey(key).orElseThrow(ApiException::notFound);
    if (!user.isAdmin() && !isMember(p.getId(), user.id())) throw ApiException.notFound();
    return p;
  }

  public Project requireVisible(long projectId, CurrentUser user) {
    Project p = projects.findById(projectId).orElseThrow(ApiException::notFound);
    if (!user.isAdmin() && !isMember(p.getId(), user.id())) throw ApiException.notFound();
    return p;
  }

  /** Visible and not archived (BR-013). */
  public Project requireWritable(String key, CurrentUser user) {
    Project p = requireVisible(key, user);
    if (p.isArchived()) throw ApiException.archived();
    return p;
  }

  public static void requireActive(Project p) {
    if (p.isArchived()) throw ApiException.archived();
  }

  public boolean canManage(Project p, CurrentUser user) {
    return user.isAdmin() || p.getOwnerId() == user.id();
  }

  private Project requireManageable(String key, CurrentUser user) {
    Project p = requireVisible(key, user);
    if (!canManage(p, user))
      throw ApiException.forbidden("Only the project owner or an ADMIN can do that.");
    return p;
  }

  // ── projects ────────────────────────────────────────────────────────────────

  @Transactional
  public ProjectView create(CreateProject req, CurrentUser user) {
    checkDates(req.startDate(), req.targetDate());
    if (projects.existsByKey(req.key())) {
      throw ApiException.conflict(
          "KEY_TAKEN", "Key already used", "Project key " + req.key() + " is taken.");
    }
    Project p = new Project(req.key(), req.name().trim(), user.id());
    p.setDescription(blankToNull(req.description()));
    p.setStartDate(req.startDate());
    p.setTargetDate(req.targetDate());
    p = projects.saveAndFlush(p);
    jdbc.sql("INSERT INTO project_members (project_id, user_id, capacity_hours) VALUES (?, ?, ?)")
        .params(p.getId(), user.id(), DEFAULT_CAPACITY)
        .update();
    return view(p, user);
  }

  public List<ProjectSummary> list(Project.Status status, CurrentUser user) {
    LocalDate today = LocalDate.now(clock.withZone(props.timezone()));
    return jdbc.sql(
            """
            SELECT p.id, p.key, p.name, p.description, p.status, p.target_date,
              (SELECT count(*) FROM tasks t WHERE t.project_id = p.id AND t.status <> 'DONE') open_tasks,
              (SELECT count(*) FROM tasks t JOIN task_predictions tp ON tp.task_id = t.id
                 WHERE t.project_id = p.id AND t.status <> 'DONE' AND tp.status = 'OK'
                   AND tp.risk_level = 'HIGH') at_risk,
              (SELECT count(*) FROM tasks t WHERE t.project_id = p.id AND t.status <> 'DONE'
                 AND t.due_date BETWEEN :today AND :weekEnd) due_week,
              (SELECT count(*) FROM tasks t WHERE t.project_id = p.id) total,
              (SELECT count(*) FROM tasks t WHERE t.project_id = p.id AND t.status = 'DONE') done,
              (SELECT count(*) FROM project_members m WHERE m.project_id = p.id) members,
              (SELECT array_agg(s.name) FROM (SELECT u.name FROM project_members m
                 JOIN users u ON u.id = m.user_id WHERE m.project_id = p.id
                 ORDER BY u.name LIMIT 5) s) member_names
            FROM projects p
            WHERE p.status = :status AND (:admin OR EXISTS
              (SELECT 1 FROM project_members m WHERE m.project_id = p.id AND m.user_id = :uid))
            ORDER BY p.name
            """)
        .param("today", today)
        .param("weekEnd", today.plusDays(6))
        .param("status", status.name())
        .param("admin", user.isAdmin())
        .param("uid", user.id())
        .query(
            (rs, i) ->
                new ProjectSummary(
                    rs.getLong("id"),
                    rs.getString("key"),
                    rs.getString("name"),
                    rs.getString("description"),
                    Project.Status.valueOf(rs.getString("status")),
                    rs.getObject("target_date", LocalDate.class),
                    rs.getLong("open_tasks"),
                    rs.getLong("at_risk"),
                    rs.getLong("due_week"),
                    rs.getLong("total"),
                    rs.getLong("done"),
                    rs.getLong("members"),
                    names(rs.getArray("member_names"))))
        .list();
  }

  public ProjectView get(String key, CurrentUser user) {
    return view(requireVisible(key, user), user);
  }

  @Transactional
  public ProjectView update(String key, ProjectPatch req, CurrentUser user) {
    Project p = requireManageable(key, user);
    requireActive(p);
    if (req.getVersion() != p.getVersion()) throw ApiException.stale(p.getVersion());
    if (req.has("name")) {
      if (req.getName() == null || req.getName().isBlank())
        throw ApiException.validation("name", "must not be blank");
      p.setName(req.getName().trim());
    }
    if (req.has("description")) p.setDescription(blankToNull(req.getDescription()));
    if (req.has("startDate")) p.setStartDate(req.getStartDate());
    if (req.has("targetDate")) p.setTargetDate(req.getTargetDate());
    checkDates(p.getStartDate(), p.getTargetDate());
    projects.flush();
    return view(p, user);
  }

  @Transactional
  public ProjectView setArchived(String key, boolean archived, CurrentUser user) {
    Project p = requireManageable(key, user);
    p.setStatus(archived ? Project.Status.ARCHIVED : Project.Status.ACTIVE);
    projects.flush();
    return view(p, user);
  }

  // ── members ─────────────────────────────────────────────────────────────────

  public List<MemberView> members(String key, CurrentUser user) {
    Project p = requireVisible(key, user);
    return jdbc.sql(
            "SELECT u.id, u.name, u.email, m.capacity_hours, u.active, m.joined_at FROM project_members m"
                + " JOIN users u ON u.id = m.user_id WHERE m.project_id = ? ORDER BY u.name")
        .param(p.getId())
        .query(
            (rs, i) ->
                new MemberView(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBigDecimal(4),
                    rs.getLong(1) == p.getOwnerId(),
                    rs.getBoolean(5),
                    rs.getTimestamp(6).toInstant()))
        .list();
  }

  @Transactional
  public MemberView addMember(String key, AddMember req, CurrentUser user) {
    Project p = requireManageable(key, user);
    requireActive(p);
    User u =
        users
            .findByEmailIgnoreCase(AuthService.normalizeEmail(req.userEmail()))
            .filter(User::isActive)
            .orElseThrow(
                () -> ApiException.notFound("USER_NOT_FOUND", "No active user has that email."));
    if (isMember(p.getId(), u.getId())) {
      throw ApiException.conflict(
          "ALREADY_MEMBER", "Already a member", u.getName() + " is already a member.");
    }
    BigDecimal capacity = Objects.requireNonNullElse(req.capacityHours(), DEFAULT_CAPACITY);
    jdbc.sql("INSERT INTO project_members (project_id, user_id, capacity_hours) VALUES (?, ?, ?)")
        .params(p.getId(), u.getId(), capacity)
        .update();
    return members(key, user).stream()
        .filter(m -> m.userId() == u.getId())
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public MemberView updateMember(String key, long userId, BigDecimal capacity, CurrentUser user) {
    Project p = requireManageable(key, user);
    requireActive(p);
    int n =
        jdbc.sql(
                "UPDATE project_members SET capacity_hours = ? WHERE project_id = ? AND user_id = ?")
            .params(capacity, p.getId(), userId)
            .update();
    if (n == 0) throw ApiException.notFound();
    return members(key, user).stream().filter(m -> m.userId() == userId).findFirst().orElseThrow();
  }

  /** Removing a member unassigns their open tasks (FR-013). */
  @Transactional
  public void removeMember(String key, long userId, CurrentUser user) {
    Project p = requireManageable(key, user);
    requireActive(p);
    if (userId == p.getOwnerId()) {
      throw ApiException.conflict(
          "CANNOT_REMOVE_OWNER", "Owner cannot be removed", "The project owner cannot be removed.");
    }
    int n =
        jdbc.sql("DELETE FROM project_members WHERE project_id = ? AND user_id = ?")
            .params(p.getId(), userId)
            .update();
    if (n == 0) throw ApiException.notFound();
    List<Long> unassigned =
        jdbc.sql(
                "UPDATE tasks SET assignee_id = NULL, updated_at = now(), version = version + 1"
                    + " WHERE project_id = ? AND assignee_id = ? AND status <> 'DONE' RETURNING id")
            .params(p.getId(), userId)
            .query(Long.class)
            .list();
    for (Long taskId : unassigned) {
      events.record(taskId, p.getId(), TaskEvents.Type.ASSIGNEE_CHANGED, userId, null, user.id());
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private ProjectView view(Project p, CurrentUser user) {
    User owner = users.findById(p.getOwnerId()).orElseThrow();
    return new ProjectView(
        p.getId(),
        p.getKey(),
        p.getName(),
        p.getDescription(),
        p.getStatus(),
        p.getStartDate(),
        p.getTargetDate(),
        new UserRef(owner.getId(), owner.displayName()),
        p.getVersion(),
        p.getCreatedAt(),
        p.getArchivedAt(),
        canManage(p, user));
  }

  private static void checkDates(LocalDate start, LocalDate target) {
    if (start != null && target != null && target.isBefore(start)) {
      throw ApiException.validation("targetDate", "must be on or after the start date");
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static List<String> names(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }
}
