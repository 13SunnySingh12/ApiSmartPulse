package com.apismartpulse.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.IntegrationTest;
import com.apismartpulse.user.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** QA-TASK-01..07, QA-SUB-01, QA-AUTHZ-02, QA-SEC-07, FR-020..028, BR-003/004/005. */
class TaskIT extends IntegrationTest {

  @Test
  void qaTask01_concurrentCreatesGetUniqueMonotonicNumbers() throws Exception {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<Res>> futures = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      int n = i;
      futures.add(
          pool.submit(
              () ->
                  post(
                      "/api/v1/projects/" + key + "/tasks",
                      manager.token(),
                      Map.of("title", "Parallel " + n))));
    }
    List<Integer> numbers = new ArrayList<>();
    for (Future<Res> f : futures) {
      Res r = f.get();
      assertThat(r.status()).isEqualTo(201);
      numbers.add(Integer.parseInt(r.body().path("key").asString().split("-")[1]));
    }
    pool.shutdown();
    assertThat(numbers)
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
  }

  @Test
  void fr020_createRecordsEventPredictionAndOutbox() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    JsonNode t =
        task(
            key,
            manager,
            Map.of("title", "Fix login redirect", "priority", "HIGH", "estimateHours", 6));
    assertThat(t.path("key").asString()).isEqualTo(key + "-1");
    assertThat(t.path("prediction").path("status").asString()).isEqualTo("OK");
    assertThat(t.path("prediction").path("source").asString()).isEqualTo("MODEL");
    assertThat(t.path("activity").get(0).path("type").asString()).isEqualTo("CREATED");
    long outbox =
        jdbc.sql("SELECT count(*) FROM ai_sync_queue WHERE entity_type = 'TASK' AND entity_id = ?")
            .param(t.path("id").asLong())
            .query(Long.class)
            .single();
    assertThat(outbox).isEqualTo(1);
  }

  @Test
  void qaTask05_06_boundaryValidation() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    String p = "/api/v1/projects/" + key + "/tasks";
    assertThat(post(p, manager.token(), Map.of("title", "ab")).status()).isEqualTo(400);
    assertThat(post(p, manager.token(), Map.of("title", "abc")).status()).isEqualTo(201);
    assertThat(post(p, manager.token(), Map.of("title", "x".repeat(200))).status()).isEqualTo(201);
    assertThat(post(p, manager.token(), Map.of("title", "x".repeat(201))).status()).isEqualTo(400);
    assertThat(post(p, manager.token(), Map.of("title", "Estimate", "estimateHours", 0.4)).status())
        .isEqualTo(400);
    assertThat(post(p, manager.token(), Map.of("title", "Estimate", "estimateHours", 0.5)).status())
        .isEqualTo(201);
    assertThat(post(p, manager.token(), Map.of("title", "Estimate", "estimateHours", 400)).status())
        .isEqualTo(201);
    assertThat(
            post(p, manager.token(), Map.of("title", "Estimate", "estimateHours", 400.5)).status())
        .isEqualTo(400);
    assertThat(
            post(p, manager.token(), Map.of("title", "Estimate", "estimateHours", 1.25)).status())
        .isEqualTo(400);
    assertThat(
            post(p, manager.token(), Map.of("title", "Past due", "dueDate", "2020-01-01")).status())
        .isEqualTo(400);
  }

  @Test
  void qaTask02_staleVersionConflictKeepsCurrentVersion() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    String tk = task(key, manager, "Versioned").path("key").asString();
    assertThat(
            patch(
                    "/api/v1/tasks/" + tk,
                    manager.token(),
                    Map.of("title", "First edit", "version", 0))
                .status())
        .isEqualTo(200);
    Res stale =
        patch("/api/v1/tasks/" + tk, manager.token(), Map.of("title", "Second edit", "version", 0));
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.code()).isEqualTo("CONFLICT_STALE");
    assertThat(stale.body().path("currentVersion").asInt()).isEqualTo(1);
    assertThat(
            patch("/api/v1/tasks/" + tk, manager.token(), Map.of("title", "No version")).status())
        .isEqualTo(400);
  }

  @Test
  void qaTask03_br004_statusTransitionsAndEvents() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    String tk = task(key, manager, "Lifecycle").path("key").asString();
    Res done =
        post(
            "/api/v1/tasks/" + tk + "/status",
            manager.token(),
            Map.of("status", "DONE", "version", 0, "actualHours", 3));
    assertThat(done.status()).isEqualTo(200);
    assertThat(done.body().path("completedAt").isNull()).isFalse();
    assertThat(done.body().path("actualHours").asDouble()).isEqualTo(3.0);
    assertThat(done.body().path("activity").get(0).path("type").asString())
        .isEqualTo("STATUS_CHANGED");
    Res back =
        post(
            "/api/v1/tasks/" + tk + "/status",
            manager.token(),
            Map.of("status", "TODO", "version", 1));
    assertThat(back.status()).isEqualTo(409);
    assertThat(back.code()).isEqualTo("INVALID_TRANSITION");
    Res reopen =
        post(
            "/api/v1/tasks/" + tk + "/status",
            manager.token(),
            Map.of("status", "IN_PROGRESS", "version", 1));
    assertThat(reopen.status()).isEqualTo(200);
    assertThat(reopen.body().path("completedAt").isNull()).isTrue();
    long events =
        jdbc.sql(
                "SELECT count(*) FROM task_events e JOIN tasks t ON t.id = e.task_id WHERE t.id = ? AND e.event_type = 'STATUS_CHANGED'")
            .param(reopen.body().path("id").asLong())
            .query(Long.class)
            .single();
    assertThat(events).isEqualTo(2);
  }

  @Test
  void br005_assigneeMustBeMemberAndMembersSelfAssignOnly() {
    TestUser manager = user(Role.MANAGER);
    TestUser member = user(Role.MEMBER);
    TestUser other = user(Role.MEMBER);
    TestUser outsider = user(Role.MEMBER);
    String key = project(manager, member, other);
    String p = "/api/v1/projects/" + key + "/tasks";
    assertThat(
            post(p, manager.token(), Map.of("title", "Outsider", "assigneeId", outsider.id()))
                .status())
        .isEqualTo(400);
    assertThat(post(p, member.token(), Map.of("title", "Mine", "assigneeId", member.id())).status())
        .isEqualTo(201);
    assertThat(
            post(p, member.token(), Map.of("title", "Theirs", "assigneeId", other.id())).status())
        .isEqualTo(403);
    String tk =
        task(key, manager, Map.of("title", "Reassign me", "assigneeId", other.id()))
            .path("key")
            .asString();
    assertThat(
            patch(
                    "/api/v1/tasks/" + tk,
                    manager.token(),
                    Map.of("assigneeId", member.id(), "version", 0))
                .status())
        .isEqualTo(200);
  }

  @Test
  void qaAuthz02_memberDeletesOnlyOwnTodoTasks() {
    TestUser manager = user(Role.MANAGER);
    TestUser a = user(Role.MEMBER);
    TestUser b = user(Role.MEMBER);
    String key = project(manager, a, b);
    String ofA = task(key, a, "A's task").path("key").asString();
    assertThat(delete("/api/v1/tasks/" + ofA, b.token()).status()).isEqualTo(403);
    String ofB = task(key, b, "B's task").path("key").asString();
    post(
        "/api/v1/tasks/" + ofB + "/status",
        b.token(),
        Map.of("status", "IN_PROGRESS", "version", 0));
    assertThat(delete("/api/v1/tasks/" + ofB, b.token()).status()).isEqualTo(403);
    assertThat(delete("/api/v1/tasks/" + ofA, a.token()).status()).isEqualTo(204);
    assertThat(delete("/api/v1/tasks/" + ofB, manager.token()).status()).isEqualTo(204);
    assertThat(get("/api/v1/tasks/" + ofB, manager.token()).status()).isEqualTo(404);
  }

  @Test
  void fr021_filtersSortingPagingAndQaSec07() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    task(
        key,
        manager,
        Map.of("title", "Login page broken", "priority", "URGENT", "dueDate", "2030-01-10"));
    task(
        key,
        manager,
        Map.of("title", "Write onboarding docs", "priority", "LOW", "dueDate", "2030-03-01"));
    task(key, manager, Map.of("title", "Export CSV", "priority", "MEDIUM"));
    String base = "/api/v1/projects/" + key + "/tasks";
    assertThat(get(base + "?q=logi", manager.token()).body().path("totalItems").asInt())
        .isEqualTo(1);
    assertThat(
            get(base + "?priority=LOW,URGENT", manager.token()).body().path("totalItems").asInt())
        .isEqualTo(2);
    assertThat(
            get(base + "?dueBefore=2030-02-01", manager.token()).body().path("totalItems").asInt())
        .isEqualTo(1);
    assertThat(
            get(base + "?assigneeId=unassigned", manager.token()).body().path("totalItems").asInt())
        .isEqualTo(3);
    JsonNode sorted = get(base + "?sort=dueDate,asc", manager.token()).body().path("items");
    assertThat(sorted.get(0).path("title").asString()).isEqualTo("Login page broken");
    assertThat(sorted.get(2).path("dueDate").isNull()).isTrue();
    JsonNode page = get(base + "?size=2&page=1", manager.token()).body();
    assertThat(page.path("items").size()).isEqualTo(1);
    assertThat(page.path("totalPages").asInt()).isEqualTo(2);
    assertThat(get(base + "?size=101", manager.token()).status()).isEqualTo(400); // QA-TASK-07
    assertThat(get(base + "?status=OPEN", manager.token()).code()).isEqualTo("BAD_FILTER");
    assertThat(get(base + "?dueAfter=2030-02-01&dueBefore=2030-01-01", manager.token()).code())
        .isEqualTo("BAD_FILTER");
    // QA-SEC-07: injection attempts are inert
    Res inj =
        get(
            base
                + "?q="
                + java.net.URLEncoder.encode(
                    "' OR 1=1 --", java.nio.charset.StandardCharsets.UTF_8),
            manager.token());
    assertThat(inj.status()).isEqualTo(200);
    assertThat(inj.body().path("totalItems").asInt()).isZero();
    assertThat(get(base + "?sort=title;drop%20table%20tasks", manager.token()).code())
        .isEqualTo("BAD_FILTER");
  }

  @Test
  void fr027_labelsUniquePerProjectAndMaxTenPerTask() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      ids.add(
          post(
                  "/api/v1/projects/" + key + "/labels",
                  manager.token(),
                  Map.of("name", "L" + i, "color", "teal"))
              .body()
              .path("id")
              .asLong());
    }
    Res dup =
        post(
            "/api/v1/projects/" + key + "/labels",
            manager.token(),
            Map.of("name", "l0", "color", "rose"));
    assertThat(dup.code()).isEqualTo("LABEL_EXISTS");
    assertThat(
            post(
                    "/api/v1/projects/" + key + "/labels",
                    manager.token(),
                    Map.of("name", "bad", "color", "pink"))
                .status())
        .isEqualTo(400);
    String tk = task(key, manager, "Labelled").path("key").asString();
    assertThat(
            put("/api/v1/tasks/" + tk + "/labels", manager.token(), Map.of("labelIds", ids))
                .status())
        .isEqualTo(400);
    Res ok =
        put(
            "/api/v1/tasks/" + tk + "/labels",
            manager.token(),
            Map.of("labelIds", ids.subList(0, 2)));
    assertThat(ok.body().path("labels").size()).isEqualTo(2);
    assertThat(
            get("/api/v1/projects/" + key + "/tasks?label=L1", manager.token())
                .body()
                .path("totalItems")
                .asInt())
        .isEqualTo(1);
  }

  @Test
  void qaSub01_fr026_subtaskLimitBulkAllOrNothingAndReorder() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    String tk = task(key, manager, "Checklist").path("key").asString();
    for (int i = 0; i < 4; i++) {
      List<String> titles = new ArrayList<>();
      for (int j = 0; j < 10; j++) titles.add("Step " + i + "." + j);
      assertThat(
              post(
                      "/api/v1/tasks/" + tk + "/subtasks/bulk",
                      manager.token(),
                      Map.of("titles", titles))
                  .status())
          .isEqualTo(201);
    }
    for (int i = 0; i < 7; i++)
      post("/api/v1/tasks/" + tk + "/subtasks", manager.token(), Map.of("title", "Extra " + i));
    Res over =
        post(
            "/api/v1/tasks/" + tk + "/subtasks/bulk",
            manager.token(),
            Map.of("titles", List.of("a", "b", "c", "d", "e", "f")));
    assertThat(over.status()).isEqualTo(409);
    assertThat(over.code()).isEqualTo("LIMIT_REACHED");
    JsonNode detail = get("/api/v1/tasks/" + tk, manager.token()).body();
    assertThat(detail.path("subtasks").size()).isEqualTo(47);

    long first = detail.path("subtasks").get(0).path("id").asLong();
    assertThat(
            patch("/api/v1/subtasks/" + first, manager.token(), Map.of("done", true))
                .body()
                .path("subtaskSummary")
                .path("done")
                .asInt())
        .isEqualTo(1);
    List<Long> reversed = new ArrayList<>();
    detail.path("subtasks").forEach(s -> reversed.add(0, s.path("id").asLong()));
    Res reorder =
        put("/api/v1/tasks/" + tk + "/subtasks/order", manager.token(), Map.of("ids", reversed));
    assertThat(reorder.status()).isEqualTo(200);
    assertThat(reorder.body().get(0).path("id").asLong()).isEqualTo(reversed.get(0));
    assertThat(
            put(
                    "/api/v1/tasks/" + tk + "/subtasks/order",
                    manager.token(),
                    Map.of("ids", reversed.subList(0, 3)))
                .status())
        .isEqualTo(400);
  }

  @Test
  void fr028_commentsAuthorEditsAdminDeletesSoftly() {
    TestUser manager = user(Role.MANAGER);
    TestUser member = user(Role.MEMBER);
    String key = project(manager, member);
    String tk = task(key, manager, "Discuss").path("key").asString();
    Res c =
        post(
            "/api/v1/tasks/" + tk + "/comments",
            member.token(),
            Map.of("body", "<img src=x onerror=alert(1)> **bold**"));
    assertThat(c.status()).isEqualTo(201);
    long id = c.body().path("id").asLong();
    assertThat(patch("/api/v1/comments/" + id, manager.token(), Map.of("body", "hijack")).status())
        .isEqualTo(403);
    Res edited = patch("/api/v1/comments/" + id, member.token(), Map.of("body", "edited text"));
    assertThat(edited.status()).isEqualTo(200);
    assertThat(delete("/api/v1/comments/" + id, manager.token()).status()).isEqualTo(403);
    assertThat(delete("/api/v1/comments/" + id, user(Role.ADMIN).token()).status()).isEqualTo(204);
    JsonNode list = get("/api/v1/tasks/" + tk + "/comments", member.token()).body();
    assertThat(list.path("items").get(0).path("deleted").asBoolean()).isTrue();
    assertThat(list.path("items").get(0).path("body").asString()).isEmpty();
    assertThat(
            post(
                    "/api/v1/tasks/" + tk + "/comments",
                    member.token(),
                    Map.of("body", "x".repeat(5001)))
                .status())
        .isEqualTo(400);
  }

  @Test
  void fr053_applyingSuggestionRecordsEvent() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    String tk = task(key, manager, "Suggestion target").path("key").asString();
    Map<String, Object> body =
        new HashMap<>(Map.of("priority", "URGENT", "appliedSuggestion", "PRIORITY", "version", 0));
    Res r = patch("/api/v1/tasks/" + tk, manager.token(), body);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("activity").get(0).path("type").asString())
        .isEqualTo("AI_SUGGESTION_APPLIED");
  }

  @Test
  void idempotencyKeyPreventsDoubleSubmit() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    Map<String, String> h = Map.of("Idempotency-Key", "abc-123");
    Res a =
        send(
            "POST",
            "/api/v1/projects/" + key + "/tasks",
            manager.token(),
            Map.of("title", "Once only"),
            h);
    Res b =
        send(
            "POST",
            "/api/v1/projects/" + key + "/tasks",
            manager.token(),
            Map.of("title", "Once only"),
            h);
    assertThat(a.body().path("key").asString()).isEqualTo(b.body().path("key").asString());
  }
}
