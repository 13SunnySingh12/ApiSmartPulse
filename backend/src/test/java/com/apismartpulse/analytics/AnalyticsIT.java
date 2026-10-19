package com.apismartpulse.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.IntegrationTest;
import com.apismartpulse.user.Role;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** QA-AN-01..05, FR-070..073, BR-008/009 (hand-computed expectations in comments). */
class AnalyticsIT extends IntegrationTest {
  private static final ZoneId TZ = ZoneId.of("Asia/Kolkata");

  private JsonNode member(JsonNode workload, long userId) {
    for (JsonNode m : workload.path("members")) if (m.path("userId").asLong() == userId) return m;
    throw new AssertionError("member " + userId + " missing");
  }

  @Test
  void qaAn01_workloadValuesAndSuggestion() {
    TestUser lead = user(Role.MANAGER);
    TestUser asha = user(Role.MEMBER);
    TestUser ravi = user(Role.MEMBER);
    String key = project(lead, asha, ravi);
    patch(
        "/api/v1/projects/" + key + "/members/" + asha.id(),
        lead.token(),
        Map.of("capacityHours", 10));
    String t1 =
        task(
                key,
                lead,
                Map.of(
                    "title",
                    "Low priority chore",
                    "priority",
                    "LOW",
                    "estimateHours",
                    8,
                    "assigneeId",
                    asha.id()))
            .path("key")
            .asString();
    task(
        key,
        lead,
        Map.of(
            "title",
            "Important work",
            "priority",
            "HIGH",
            "estimateHours",
            6,
            "assigneeId",
            asha.id()));
    String t3 =
        task(key, lead, Map.of("title", "Half done", "estimateHours", 4, "assigneeId", ravi.id()))
            .path("key")
            .asString();
    post(
        "/api/v1/tasks/" + t3 + "/subtasks/bulk",
        lead.token(),
        Map.of("titles", java.util.List.of("one", "two")));
    long sub =
        get("/api/v1/tasks/" + t3, lead.token()).body().path("subtasks").get(0).path("id").asLong();
    patch("/api/v1/subtasks/" + sub, lead.token(), Map.of("done", true));
    long t4 = task(key, lead, "Unassigned and unestimated").path("id").asLong();
    jdbc.sql("DELETE FROM task_predictions WHERE task_id = ?")
        .param(t4)
        .update(); // forces the assumed 4 h

    JsonNode w = get("/api/v1/projects/" + key + "/analytics/workload", lead.token()).body();
    JsonNode a = member(w, asha.id());
    assertThat(a.path("openHours").asDouble()).isEqualTo(14.0); // 8 + 6
    assertThat(a.path("loadPct").asInt()).isEqualTo(140); // 14 / 10
    assertThat(a.path("status").asString()).isEqualTo("OVER");
    JsonNode r = member(w, ravi.id());
    assertThat(r.path("openHours").asDouble()).isEqualTo(2.0); // 4 × (1 − 1/2)
    assertThat(r.path("loadPct").asInt()).isEqualTo(5);
    assertThat(r.path("status").asString()).isEqualTo("UNDER");
    assertThat(w.path("unassignedHours").asDouble()).isEqualTo(4.0);
    // BR-009: Asha's lowest-priority TODO (8 h) → least-loaded member who stays ≤ 100 % (lead, 0 %)
    JsonNode s = w.path("suggestions").get(0);
    assertThat(s.path("taskKey").asString()).isEqualTo(t1);
    assertThat(s.path("toUserId").asLong()).isEqualTo(lead.id());
    assertThat(s.path("fromLoadAfter").asInt()).isEqualTo(60);
    assertThat(s.path("toLoadAfter").asInt()).isEqualTo(20);
  }

  @Test
  void qaAn02_noSafeMove() {
    TestUser lead = user(Role.MANAGER);
    TestUser c = user(Role.MEMBER);
    String key = project(lead, c);
    patch(
        "/api/v1/projects/" + key + "/members/" + lead.id(),
        lead.token(),
        Map.of("capacityHours", 1));
    patch(
        "/api/v1/projects/" + key + "/members/" + c.id(), lead.token(), Map.of("capacityHours", 1));
    task(key, lead, Map.of("title", "Huge task", "estimateHours", 10, "assigneeId", c.id()));
    JsonNode w = get("/api/v1/projects/" + key + "/analytics/workload", lead.token()).body();
    assertThat(w.path("suggestions").size()).isZero();
    assertThat(w.path("noSafeMove").get(0).path("userId").asLong()).isEqualTo(c.id());
  }

  @Test
  void qaAn03_04_burndownRangeAndValues() {
    TestUser lead = user(Role.MANAGER);
    String key = project(lead);
    String base = "/api/v1/projects/" + key + "/analytics/burndown";
    LocalDate today = LocalDate.now(TZ);
    assertThat(get(base + "?from=" + today.minusDays(181) + "&to=" + today, lead.token()).code())
        .isEqualTo("RANGE_TOO_LARGE");
    assertThat(get(base + "?from=" + today.minusDays(180) + "&to=" + today, lead.token()).status())
        .isEqualTo(200);

    long x = task(key, lead, Map.of("title", "Task X", "estimateHours", 5)).path("id").asLong();
    long y = task(key, lead, Map.of("title", "Task Y", "estimateHours", 3)).path("id").asLong();
    String start = today.minusDays(3).atStartOfDay(TZ).toInstant().toString();
    String doneAt = today.minusDays(1).atStartOfDay(TZ).plusHours(10).toInstant().toString();
    jdbc.sql("UPDATE tasks SET created_at = ?::timestamptz WHERE id IN (?, ?)")
        .params(start, x, y)
        .update();
    jdbc.sql("UPDATE tasks SET status = 'DONE', completed_at = ?::timestamptz WHERE id = ?")
        .params(doneAt, x)
        .update();
    jdbc.sql(
            "INSERT INTO task_events (task_id, project_id, event_type, from_value, to_value, occurred_at)"
                + " SELECT id, project_id, 'STATUS_CHANGED', 'TODO', 'DONE', ?::timestamptz FROM tasks WHERE id = ?")
        .params(doneAt, x)
        .update();

    JsonNode points =
        get(base + "?from=" + today.minusDays(3) + "&to=" + today, lead.token())
            .body()
            .path("points");
    assertThat(points.size()).isEqualTo(4);
    assertThat(points.get(0).path("remainingHours").asDouble()).isEqualTo(8.0);
    assertThat(points.get(1).path("remainingHours").asDouble()).isEqualTo(8.0);
    assertThat(points.get(2).path("remainingHours").asDouble())
        .isEqualTo(3.0); // X done during day −1
    assertThat(points.get(3).path("remainingHours").asDouble()).isEqualTo(3.0);
    assertThat(points.get(0).path("idealHours").asDouble()).isEqualTo(8.0);
    assertThat(points.get(1).path("idealHours").asDouble()).isEqualTo(5.3);
    assertThat(points.get(3).path("idealHours").asDouble()).isEqualTo(0.0);
  }

  @Test
  void qaAn05_velocityBoundsAndTotals() {
    TestUser lead = user(Role.MANAGER);
    String key = project(lead);
    String base = "/api/v1/projects/" + key + "/analytics/velocity";
    assertThat(get(base + "?weeks=0", lead.token()).status()).isEqualTo(400);
    assertThat(get(base + "?weeks=27", lead.token()).status()).isEqualTo(400);
    String t =
        task(key, lead, Map.of("title", "Shipped", "estimateHours", 5)).path("key").asString();
    post(
        "/api/v1/tasks/" + t + "/status",
        lead.token(),
        Map.of("status", "DONE", "version", 0, "actualHours", 6.5));
    JsonNode weeks = get(base + "?weeks=2", lead.token()).body().path("series");
    assertThat(weeks.size()).isEqualTo(2);
    assertThat(weeks.get(1).path("tasksCompleted").asInt()).isEqualTo(1);
    assertThat(weeks.get(1).path("hoursCompleted").asDouble()).isEqualTo(6.5);
  }
}
