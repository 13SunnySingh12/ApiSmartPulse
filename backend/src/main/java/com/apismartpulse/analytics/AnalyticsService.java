package com.apismartpulse.analytics;

import com.apismartpulse.analytics.Rebalancer.Member;
import com.apismartpulse.analytics.Rebalancer.OpenTask;
import com.apismartpulse.analytics.Rebalancer.Suggestion;
import com.apismartpulse.common.ApiException;
import com.apismartpulse.config.AppProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Workload (FR-070/071), burndown (FR-072) and velocity (FR-073) — plain SQL. */
@Service
public class AnalyticsService {
  /** BR-008 remaining hours; 4 h default is "assumed". */
  private static final String REMAINING =
      """
      SELECT t.id, p.key || '-' || t.number AS key, t.title, t.assignee_id, t.status,
        CASE t.priority WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'HIGH' THEN 3 ELSE 4 END AS rank,
        t.due_date,
        EXISTS (SELECT 1 FROM task_dependencies d JOIN tasks b ON b.id = d.blocker_task_id
                WHERE d.task_id = t.id AND b.status <> 'DONE') AS blocked,
        coalesce(t.estimate_hours, tp.predicted_hours, 4) *
          (1 - coalesce((SELECT count(*) FILTER (WHERE s.done)::numeric / nullif(count(*), 0)
                         FROM subtasks s WHERE s.task_id = t.id), 0)) AS hours,
        (t.estimate_hours IS NULL AND tp.predicted_hours IS NULL) AS assumed
      FROM tasks t JOIN projects p ON p.id = t.project_id
      LEFT JOIN task_predictions tp ON tp.task_id = t.id AND tp.status = 'OK'
      WHERE t.project_id = ? AND t.status <> 'DONE'
      """;

  public record MemberLoad(
      long userId,
      String name,
      double capacityHours,
      double openHours,
      double assumedHours,
      int loadPct,
      String status,
      int openTasks) {}

  public record UserRef(long userId, String name) {}

  public record Workload(
      LocalDate asOf,
      List<MemberLoad> members,
      double unassignedHours,
      List<Suggestion> suggestions,
      List<UserRef> noSafeMove) {}

  public record BurndownPoint(LocalDate date, double remainingHours, double idealHours) {}

  public record Burndown(LocalDate from, LocalDate to, List<BurndownPoint> points) {}

  public record VelocityWeek(LocalDate weekStart, long tasksCompleted, double hoursCompleted) {}

  public record Velocity(int weeks, List<VelocityWeek> series) {}

  private record Row(OpenTask task, boolean assumed) {}

  private final JdbcClient jdbc;
  private final AppProperties props;
  private final Clock clock;

  public AnalyticsService(JdbcClient jdbc, AppProperties props, Clock clock) {
    this.jdbc = jdbc;
    this.props = props;
    this.clock = clock;
  }

  public Workload workload(long projectId) {
    List<Row> rows =
        jdbc.sql(REMAINING)
            .param(projectId)
            .query(
                (rs, i) ->
                    new Row(
                        new OpenTask(
                            rs.getString("key"),
                            rs.getString("title"),
                            rs.getObject("assignee_id") == null ? -1 : rs.getLong("assignee_id"),
                            rs.getString("status"),
                            rs.getInt("rank"),
                            rs.getObject("due_date", LocalDate.class),
                            rs.getBoolean("blocked"),
                            round1(rs.getDouble("hours"))),
                        rs.getBoolean("assumed")))
            .list();
    Map<Long, List<Row>> byAssignee =
        rows.stream().collect(Collectors.groupingBy(r -> r.task().assigneeId()));
    List<Member> members =
        jdbc.sql(
                "SELECT u.id, u.name, u.active, m.capacity_hours FROM project_members m"
                    + " JOIN users u ON u.id = m.user_id WHERE m.project_id = ? ORDER BY u.name")
            .param(projectId)
            .query(
                (rs, i) -> {
                  long id = rs.getLong(1);
                  double open =
                      byAssignee.getOrDefault(id, List.of()).stream()
                          .mapToDouble(r -> r.task().hours())
                          .sum();
                  return new Member(
                      id,
                      rs.getString(2) + (rs.getBoolean(3) ? "" : " (deactivated)"),
                      rs.getDouble(4),
                      round1(open));
                })
            .list();
    List<MemberLoad> loads = new ArrayList<>();
    for (Member m : members) {
      List<Row> mine = byAssignee.getOrDefault(m.userId(), List.of());
      int pct = Rebalancer.pct(m.openHours(), m.capacity());
      loads.add(
          new MemberLoad(
              m.userId(),
              m.name(),
              m.capacity(),
              m.openHours(),
              round1(mine.stream().filter(Row::assumed).mapToDouble(r -> r.task().hours()).sum()),
              pct,
              pct <= 80 ? "UNDER" : pct <= 100 ? "HEALTHY" : "OVER",
              mine.size()));
    }
    double unassigned =
        round1(
            byAssignee.getOrDefault(-1L, List.of()).stream()
                .mapToDouble(r -> r.task().hours())
                .sum());
    Rebalancer.Result r = Rebalancer.suggest(members, rows.stream().map(Row::task).toList());
    List<UserRef> none =
        members.stream()
            .filter(m -> r.noSafeMove().contains(m.userId()))
            .map(m -> new UserRef(m.userId(), m.name()))
            .toList();
    return new Workload(today(), loads, unassigned, r.suggestions(), none);
  }

  public Burndown burndown(long projectId, LocalDate from, LocalDate to) {
    LocalDate end = to != null ? to : today();
    LocalDate start = from != null ? from : end.minusDays(29);
    if (end.isBefore(start)) throw ApiException.validation("to", "must be on or after from");
    if (ChronoUnit.DAYS.between(start, end) > 180) {
      throw ApiException.badRequest(
          "RANGE_TOO_LARGE", "The burndown range can be at most 180 days.");
    }
    // Remaining at the end of each day: tasks created by then whose last status change by then
    // was not DONE. Uses current estimates (historical estimate changes are not replayed).
    List<double[]> remaining =
        jdbc.sql(
                """
                WITH days AS (SELECT generate_series(:from::date, :to::date, interval '1 day')::date AS d),
                t AS (SELECT t.id, t.created_at, coalesce(t.estimate_hours, tp.predicted_hours, 4) AS h
                      FROM tasks t LEFT JOIN task_predictions tp ON tp.task_id = t.id AND tp.status = 'OK'
                      WHERE t.project_id = :pid)
                SELECT days.d, coalesce(sum(t.h) FILTER (WHERE t.created_at < ((days.d + 1)::timestamp AT TIME ZONE :tz)
                  AND coalesce((SELECT e.to_value FROM task_events e
                                WHERE e.task_id = t.id AND e.event_type = 'STATUS_CHANGED'
                                  AND e.occurred_at < ((days.d + 1)::timestamp AT TIME ZONE :tz)
                                ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1), 'TODO') <> 'DONE'), 0)
                FROM days LEFT JOIN t ON true GROUP BY days.d ORDER BY days.d
                """)
            .param("from", start)
            .param("to", end)
            .param("pid", projectId)
            .param("tz", props.timezone().getId())
            .query((rs, i) -> new double[] {rs.getDouble(2)})
            .list();
    List<BurndownPoint> points = new ArrayList<>();
    double first = remaining.isEmpty() ? 0 : remaining.get(0)[0];
    int n = remaining.size();
    for (int i = 0; i < n; i++) {
      double ideal = n == 1 ? first : first * (1 - (double) i / (n - 1));
      points.add(new BurndownPoint(start.plusDays(i), round1(remaining.get(i)[0]), round1(ideal)));
    }
    return new Burndown(start, end, points);
  }

  public Velocity velocity(long projectId, int weeks) {
    if (weeks < 1 || weeks > 26) throw ApiException.validation("weeks", "must be between 1 and 26");
    LocalDate thisWeek = today().with(java.time.DayOfWeek.MONDAY);
    LocalDate first = thisWeek.minusWeeks(weeks - 1L);
    List<VelocityWeek> series =
        jdbc.sql(
                """
                WITH w AS (SELECT generate_series(:first::date, :last::date, interval '7 days')::date AS ws)
                SELECT w.ws, count(t.id),
                  coalesce(sum(coalesce(t.actual_hours, t.estimate_hours, tp.predicted_hours, 4)), 0)
                FROM w LEFT JOIN tasks t ON t.project_id = :pid AND t.status = 'DONE'
                  AND t.completed_at >= (w.ws::timestamp AT TIME ZONE :tz)
                  AND t.completed_at < ((w.ws + 7)::timestamp AT TIME ZONE :tz)
                LEFT JOIN task_predictions tp ON tp.task_id = t.id AND tp.status = 'OK'
                GROUP BY w.ws ORDER BY w.ws
                """)
            .param("first", first)
            .param("last", thisWeek)
            .param("pid", projectId)
            .param("tz", props.timezone().getId())
            .query(
                (rs, i) ->
                    new VelocityWeek(
                        rs.getObject(1, LocalDate.class), rs.getLong(2), round1(rs.getDouble(3))))
            .list();
    return new Velocity(weeks, series);
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(props.timezone()));
  }

  private static double round1(double v) {
    return Math.round(v * 10) / 10.0;
  }
}
