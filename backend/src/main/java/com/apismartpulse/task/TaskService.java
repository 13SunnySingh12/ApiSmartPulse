package com.apismartpulse.task;

import com.apismartpulse.ai.AiOutbox;
import com.apismartpulse.ai.PredictionStore;
import com.apismartpulse.common.ApiException;
import com.apismartpulse.config.AppProperties;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.Project;
import com.apismartpulse.project.ProjectDtos.UserRef;
import com.apismartpulse.project.ProjectService;
import com.apismartpulse.task.Task.Status;
import com.apismartpulse.task.TaskDtos.CreateTask;
import com.apismartpulse.task.TaskDtos.TaskDetail;
import com.apismartpulse.task.TaskDtos.TaskPatch;
import com.apismartpulse.task.TaskDtos.TaskRef;
import com.apismartpulse.task.TaskDtos.TaskSummary;
import com.apismartpulse.task.TaskEvents.Type;
import com.apismartpulse.user.Role;
import com.apismartpulse.user.User;
import com.apismartpulse.user.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tasks (FR-020..025, FR-027 task labels) and the rules BR-003..006, BR-013. */
@Service
public class TaskService {
  /** BR-004 allowed transitions. */
  private static final Map<Status, Set<Status>> TRANSITIONS =
      Map.of(
          Status.TODO, Set.of(Status.IN_PROGRESS, Status.DONE),
          Status.IN_PROGRESS, Set.of(Status.TODO, Status.DONE),
          Status.DONE, Set.of(Status.IN_PROGRESS));

  private final TaskRepository tasks;
  private final ProjectService projects;
  private final UserRepository users;
  private final TaskQueries queries;
  private final TaskEvents events;
  private final AiOutbox outbox;
  private final PredictionStore predictions;
  private final JdbcClient jdbc;
  private final AppProperties props;
  private final Clock clock;
  private final Cache<String, Long> idempotency =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofHours(24)).build();

  public TaskService(
      TaskRepository tasks,
      ProjectService projects,
      UserRepository users,
      TaskQueries queries,
      TaskEvents events,
      AiOutbox outbox,
      PredictionStore predictions,
      JdbcClient jdbc,
      AppProperties props,
      Clock clock) {
    this.tasks = tasks;
    this.projects = projects;
    this.users = users;
    this.queries = queries;
    this.events = events;
    this.outbox = outbox;
    this.predictions = predictions;
    this.jdbc = jdbc;
    this.props = props;
    this.clock = clock;
  }

  public record Located(Task task, Project project) {
    public String key() {
      return project.getKey() + "-" + task.getNumber();
    }
  }

  // ── lookup with object-level authorization (SEC-012) ────────────────────────

  public Located locate(String key, CurrentUser user) {
    TaskKey k = TaskKey.parse(key);
    Project p = projects.requireVisible(k.projectKey(), user);
    Task t =
        tasks.findByProjectIdAndNumber(p.getId(), k.number()).orElseThrow(ApiException::notFound);
    return new Located(t, p);
  }

  public Located locateWritable(String key, CurrentUser user) {
    Located l = locate(key, user);
    ProjectService.requireActive(l.project());
    return l;
  }

  public Located locate(long taskId, CurrentUser user) {
    Task t = tasks.findById(taskId).orElseThrow(ApiException::notFound);
    return new Located(t, projects.requireVisible(t.getProjectId(), user));
  }

  // ── commands ────────────────────────────────────────────────────────────────

  @Transactional
  public long create(String projectKey, CreateTask req, String idempotencyKey, CurrentUser user) {
    String cacheKey =
        idempotencyKey == null ? null : user.id() + ":" + projectKey + ":" + idempotencyKey;
    if (cacheKey != null) {
      Long existing = idempotency.getIfPresent(cacheKey);
      if (existing != null && tasks.existsById(existing)) return existing;
    }
    Project p = projects.requireWritable(projectKey, user);
    String title = checkTitle(req.title());
    if (req.dueDate() != null && req.dueDate().isBefore(today())) {
      throw ApiException.validation("dueDate", "must not be in the past for a new task");
    }
    checkHalfStep("estimateHours", req.estimateHours());
    if (req.assigneeId() != null) checkAssignable(p, req.assigneeId(), null, user);

    int number =
        jdbc.sql(
                "UPDATE projects SET next_task_number = next_task_number + 1 WHERE id = ?"
                    + " RETURNING next_task_number - 1")
            .param(p.getId())
            .query(Integer.class)
            .single();
    Task t = new Task(p.getId(), number, title, user.id());
    t.setDescription(blankToNull(req.description()));
    t.setPriority(Objects.requireNonNullElse(req.priority(), Task.Priority.MEDIUM));
    t.setDueDate(req.dueDate());
    t.setAssigneeId(req.assigneeId());
    t.setEstimateHours(req.estimateHours());
    t = tasks.saveAndFlush(t);
    if (req.labelIds() != null && !req.labelIds().isEmpty()) writeLabels(t, req.labelIds());
    events.record(t.getId(), p.getId(), Type.CREATED, null, Status.TODO, user.id());
    outbox.upsert(AiOutbox.Entity.TASK, t.getId());
    predictions.markPending(t.getId());
    if (cacheKey != null) idempotency.put(cacheKey, t.getId());
    return t.getId();
  }

  @Transactional
  public long update(String key, TaskPatch req, CurrentUser user) {
    Located l = locateWritable(key, user);
    Task t = l.task();
    if (req.getVersion() != t.getVersion()) throw ApiException.stale(t.getVersion());
    boolean textChanged = false;
    Long actor = user.id();
    long pid = l.project().getId();

    if (req.has("title")) {
      String title = checkTitle(req.getTitle());
      textChanged |= !title.equals(t.getTitle());
      t.setTitle(title);
    }
    if (req.has("description")) {
      String d = blankToNull(req.getDescription());
      textChanged |= !Objects.equals(d, t.getDescription());
      t.setDescription(d);
    }
    if (req.has("priority")) {
      if (req.getPriority() == null) throw ApiException.validation("priority", "must not be null");
      if (req.getAppliedSuggestion() == TaskDtos.Suggestion.PRIORITY
          && req.getPriority() != t.getPriority()) {
        events.record(
            t.getId(),
            pid,
            Type.AI_SUGGESTION_APPLIED,
            "priority:" + t.getPriority(),
            "priority:" + req.getPriority(),
            actor);
      }
      t.setPriority(req.getPriority());
    }
    if (req.has("dueDate") && !Objects.equals(req.getDueDate(), t.getDueDate())) {
      events.record(t.getId(), pid, Type.DUE_DATE_CHANGED, t.getDueDate(), req.getDueDate(), actor);
      t.setDueDate(req.getDueDate());
    }
    if (req.has("assigneeId") && !Objects.equals(req.getAssigneeId(), t.getAssigneeId())) {
      checkAssignable(l.project(), req.getAssigneeId(), t.getAssigneeId(), user);
      events.record(
          t.getId(), pid, Type.ASSIGNEE_CHANGED, t.getAssigneeId(), req.getAssigneeId(), actor);
      t.setAssigneeId(req.getAssigneeId());
    }
    if (req.has("estimateHours") && !sameNumber(req.getEstimateHours(), t.getEstimateHours())) {
      checkHalfStep("estimateHours", req.getEstimateHours());
      Type type =
          req.getAppliedSuggestion() == TaskDtos.Suggestion.ESTIMATE
              ? Type.AI_SUGGESTION_APPLIED
              : Type.ESTIMATE_CHANGED;
      String from = (type == Type.AI_SUGGESTION_APPLIED ? "estimate:" : "") + t.getEstimateHours();
      String to = (type == Type.AI_SUGGESTION_APPLIED ? "estimate:" : "") + req.getEstimateHours();
      events.record(t.getId(), pid, type, from, to, actor);
      t.setEstimateHours(req.getEstimateHours());
    }
    if (req.has("actualHours")) {
      checkHalfStep("actualHours", req.getActualHours());
      t.setActualHours(req.getActualHours());
    }
    if (req.has("status")) {
      if (req.getStatus() == null) throw ApiException.validation("status", "must not be null");
      applyStatus(l, req.getStatus(), null, actor);
    }
    if (textChanged) outbox.upsert(AiOutbox.Entity.TASK, t.getId());
    tasks.flush();
    return t.getId();
  }

  @Transactional
  public long changeStatus(
      String key, Status status, int version, BigDecimal actualHours, CurrentUser user) {
    Located l = locateWritable(key, user);
    if (version != l.task().getVersion()) throw ApiException.stale(l.task().getVersion());
    checkHalfStep("actualHours", actualHours);
    applyStatus(l, status, actualHours, user.id());
    tasks.flush();
    return l.task().getId();
  }

  private void applyStatus(Located l, Status to, BigDecimal actualHours, Long actor) {
    Task t = l.task();
    Status from = t.getStatus();
    if (from == to) return;
    if (!TRANSITIONS.get(from).contains(to)) {
      throw ApiException.conflict(
          "INVALID_TRANSITION",
          "Invalid status change",
          "A task cannot move from " + from + " to " + to + ".");
    }
    if (to != Status.TODO) {
      List<TaskRef> blockers = queries.unfinishedBlockers(t.getId());
      if (!blockers.isEmpty()) {
        throw ApiException.conflict(
                "TASK_BLOCKED",
                "Task is blocked",
                l.key() + " cannot move to " + to + " while it is blocked by unfinished tasks.")
            .with(
                "blockers",
                blockers.stream().map(b -> Map.of("key", b.key(), "status", b.status())).toList());
      }
    }
    if (to == Status.IN_PROGRESS) {
      if (t.getStartedAt() == null) t.setStartedAt(clock.instant());
      t.setCompletedAt(null);
    } else if (to == Status.DONE) {
      t.setCompletedAt(clock.instant());
      if (actualHours != null) t.setActualHours(actualHours);
    } else {
      t.setCompletedAt(null);
    }
    t.setStatus(to);
    events.record(t.getId(), l.project().getId(), Type.STATUS_CHANGED, from, to, actor);
  }

  @Transactional
  public void delete(String key, CurrentUser user) {
    Located l = locateWritable(key, user);
    if (!canDelete(l.task(), user)) {
      throw ApiException.forbidden(
          "Members can only delete their own tasks while they are in To Do.");
    }
    List<Long> blocked =
        jdbc.sql("SELECT task_id FROM task_dependencies WHERE blocker_task_id = ?")
            .param(l.task().getId())
            .query(Long.class)
            .list();
    for (Long id : blocked) {
      events.record(id, l.project().getId(), Type.DEPENDENCY_REMOVED, l.key(), null, user.id());
    }
    tasks.delete(l.task()); // FK cascades: subtasks, labels, dependencies, comments, embeddings
  }

  @Transactional
  public long setLabels(String key, List<Long> labelIds, CurrentUser user) {
    Located l = locateWritable(key, user);
    writeLabels(l.task(), labelIds);
    return l.task().getId();
  }

  private void writeLabels(Task t, List<Long> labelIds) {
    List<Long> ids = labelIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.size() > 10)
      throw ApiException.validation("labelIds", "a task can have at most 10 labels");
    if (!ids.isEmpty()) {
      long inProject =
          jdbc.sql("SELECT count(*) FROM labels WHERE project_id = :pid AND id IN (:ids)")
              .param("pid", t.getProjectId())
              .param("ids", ids)
              .query(Long.class)
              .single();
      if (inProject != ids.size())
        throw ApiException.validation("labelIds", "must be labels of this project");
    }
    jdbc.sql("DELETE FROM task_labels WHERE task_id = ?").param(t.getId()).update();
    for (Long id : ids) {
      jdbc.sql("INSERT INTO task_labels (task_id, label_id) VALUES (?, ?)")
          .params(t.getId(), id)
          .update();
    }
  }

  // ── queries ────────────────────────────────────────────────────────────────

  public TaskDetail detail(String key, CurrentUser user) {
    return detail(locate(key, user), user);
  }

  public TaskDetail detail(long taskId, CurrentUser user) {
    return detail(locate(taskId, user), user);
  }

  private TaskDetail detail(Located l, CurrentUser user) {
    Task t = l.task();
    TaskSummary s = queries.summary(t.getId());
    User creator = users.findById(t.getCreatorId()).orElseThrow();
    return new TaskDetail(
        t.getId(),
        s.key(),
        s.projectKey(),
        l.project().isArchived(),
        t.getTitle(),
        t.getDescription(),
        t.getStatus(),
        t.getPriority(),
        t.getDueDate(),
        s.assignee(),
        new UserRef(creator.getId(), creator.displayName()),
        t.getEstimateHours(),
        t.getActualHours(),
        s.labels(),
        s.subtaskSummary(),
        queries.subtasks(t.getId()),
        queries.blockers(t.getId()),
        queries.blocked(t.getId()),
        queries.commentCount(t.getId()),
        s.prediction(),
        events.recent(t.getId(), 50),
        canDelete(t, user),
        t.getStartedAt(),
        t.getCompletedAt(),
        s.version(),
        s.createdAt(),
        s.updatedAt());
  }

  // ── rules ──────────────────────────────────────────────────────────────────

  private boolean canDelete(Task t, CurrentUser user) {
    if (user.role() != Role.MEMBER) return true; // ADMIN anywhere, MANAGER as project member
    return t.getCreatorId() == user.id() && t.getStatus() == Status.TODO;
  }

  /** BR-005 (assignee must be a member) and the "MEMBER: self-assign only" permission. */
  private void checkAssignable(Project p, Long assigneeId, Long previous, CurrentUser user) {
    if (user.role() == Role.MEMBER) {
      boolean selfAssign = assigneeId != null && assigneeId == user.id();
      boolean selfUnassign = assigneeId == null && previous != null && previous == user.id();
      if (!selfAssign && !selfUnassign) {
        throw new ApiException(
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "Forbidden",
            "Members can only assign tasks to themselves.");
      }
    }
    if (assigneeId != null && !projects.isMember(p.getId(), assigneeId)) {
      throw ApiException.validation("assigneeId", "must be a member of this project");
    }
  }

  private static String checkTitle(String title) {
    String t = title == null ? "" : title.trim();
    if (t.length() < 3 || t.length() > 200)
      throw ApiException.validation("title", "must be 3–200 characters");
    return t;
  }

  static void checkHalfStep(String field, BigDecimal hours) {
    if (hours == null) return;
    if (hours.compareTo(new BigDecimal("0.5")) < 0
        || hours.compareTo(new BigDecimal("400")) > 0
        || hours.multiply(BigDecimal.valueOf(2)).stripTrailingZeros().scale() > 0) {
      throw ApiException.validation(field, "must be between 0.5 and 400 in steps of 0.5");
    }
  }

  private static boolean sameNumber(BigDecimal a, BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(props.timezone()));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
