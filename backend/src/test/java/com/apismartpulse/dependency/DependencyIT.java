package com.apismartpulse.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.IntegrationTest;
import com.apismartpulse.user.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** QA-DEP-01..04, FR-029, BR-006/007, blocker deletion edge case (§2.8). */
class DependencyIT extends IntegrationTest {

  private Res block(TestUser u, String task, String blocker) {
    return post("/api/v1/tasks/" + task + "/blockers", u.token(), Map.of("blockerKey", blocker));
  }

  @Test
  void qaDep01_selfDependencyIs400() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    String a = task(key, m, "Task A").path("key").asString();
    assertThat(block(m, a, a).status()).isEqualTo(400);
  }

  @Test
  void qaDep02_cycleRejectedWithPath() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    String a = task(key, m, "Task A").path("key").asString();
    String b = task(key, m, "Task B").path("key").asString();
    String c = task(key, m, "Task C").path("key").asString();
    assertThat(block(m, b, a).status()).isEqualTo(201); // B blocked by A
    assertThat(block(m, c, b).status()).isEqualTo(201); // C blocked by B
    Res cycle = block(m, a, c); // A blocked by C → A→C→B→A
    assertThat(cycle.status()).isEqualTo(409);
    assertThat(cycle.code()).isEqualTo("DEPENDENCY_CYCLE");
    assertThat(cycle.body().path("cycle").toString())
        .isEqualTo("[\"" + a + "\",\"" + c + "\",\"" + b + "\",\"" + a + "\"]");
  }

  @Test
  void qaDep03_crossProjectBlockerIs400() {
    TestUser m = user(Role.MANAGER);
    String p1 = project(m);
    String p2 = project(m);
    String a = task(p1, m, "Here").path("key").asString();
    String other = task(p2, m, "There").path("key").asString();
    assertThat(block(m, a, other).status()).isEqualTo(400);
  }

  @Test
  void qaDep04_blockedTaskCannotStartUntilBlockerDone() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    String a = task(key, m, "Blocker A").path("key").asString();
    String b = task(key, m, "Blocked B").path("key").asString();
    block(m, b, a);
    Res blocked =
        post(
            "/api/v1/tasks/" + b + "/status",
            m.token(),
            Map.of("status", "IN_PROGRESS", "version", 0));
    assertThat(blocked.status()).isEqualTo(409);
    assertThat(blocked.code()).isEqualTo("TASK_BLOCKED");
    assertThat(blocked.body().path("blockers").get(0).path("key").asString()).isEqualTo(a);
    assertThat(
            get("/api/v1/tasks/" + b, m.token())
                .body()
                .path("blockedBy")
                .get(0)
                .path("key")
                .asString())
        .isEqualTo(a);
    post("/api/v1/tasks/" + a + "/status", m.token(), Map.of("status", "DONE", "version", 0));
    assertThat(
            post(
                    "/api/v1/tasks/" + b + "/status",
                    m.token(),
                    Map.of("status", "IN_PROGRESS", "version", 0))
                .status())
        .isEqualTo(200);
  }

  @Test
  void deletingBlockerRemovesDependencyAndLogsEvent() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    String a = task(key, m, "Doomed blocker").path("key").asString();
    String b = task(key, m, "Survivor").path("key").asString();
    block(m, b, a);
    assertThat(delete("/api/v1/tasks/" + a, m.token()).status()).isEqualTo(204);
    var detail = get("/api/v1/tasks/" + b, m.token()).body();
    assertThat(detail.path("blockedBy").size()).isZero();
    assertThat(detail.path("activity").get(0).path("type").asString())
        .isEqualTo("DEPENDENCY_REMOVED");
    assertThat(
            post(
                    "/api/v1/tasks/" + b + "/status",
                    m.token(),
                    Map.of("status", "IN_PROGRESS", "version", 0))
                .status())
        .isEqualTo(200);
  }

  @Test
  void removeBlocker() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    String a = task(key, m, "Task one").path("key").asString();
    String b = task(key, m, "Task two").path("key").asString();
    block(m, b, a);
    assertThat(delete("/api/v1/tasks/" + b + "/blockers/" + a, m.token()).status()).isEqualTo(204);
    assertThat(delete("/api/v1/tasks/" + b + "/blockers/" + a, m.token()).status()).isEqualTo(404);
  }
}
