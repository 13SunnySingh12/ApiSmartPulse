package com.apismartpulse.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.IntegrationTest;
import com.apismartpulse.user.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FR-010..013, QA-SEC-03/04, QA-AUTHZ-01/03, QA-TASK-04, BR-002, BR-013. */
class ProjectIT extends IntegrationTest {

  @Test
  void fr010_managerCreatesProjectMemberCannot() {
    TestUser manager = user(Role.MANAGER);
    TestUser member = user(Role.MEMBER);
    String key = randomKey();
    Res created = post("/api/v1/projects", manager.token(), Map.of("key", key, "name", "Roadmap"));
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.body().path("owner").path("id").asLong()).isEqualTo(manager.id());
    assertThat(
            post("/api/v1/projects", member.token(), Map.of("key", randomKey(), "name", "Nope"))
                .status())
        .isEqualTo(403); // QA-SEC-03
    Res dup = post("/api/v1/projects", manager.token(), Map.of("key", key, "name", "Again"));
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.code()).isEqualTo("KEY_TAKEN");
    assertThat(
            post("/api/v1/projects", manager.token(), Map.of("key", "ab", "name", "Lower"))
                .status())
        .isEqualTo(400);
    // creator is a member with default capacity
    JsonNode members = get("/api/v1/projects/" + key + "/members", manager.token()).body();
    assertThat(members.get(0).path("owner").asBoolean()).isTrue();
    assertThat(members.get(0).path("capacityHours").asDouble()).isEqualTo(40.0);
  }

  @Test
  void fr011_listShowsOnlyMemberProjectsAndArchivedOnRequest() {
    TestUser manager = user(Role.MANAGER);
    TestUser outsider = user(Role.MEMBER);
    String key = project(manager);
    assertThat(get("/api/v1/projects", outsider.token()).body().toString()).doesNotContain(key);
    assertThat(get("/api/v1/projects", manager.token()).body().toString()).contains(key);
    post("/api/v1/projects/" + key + "/archive", manager.token(), null);
    assertThat(get("/api/v1/projects", manager.token()).body().toString()).doesNotContain(key);
    assertThat(get("/api/v1/projects?status=ARCHIVED", manager.token()).body().toString())
        .contains(key);
    assertThat(get("/api/v1/projects?status=BOGUS", manager.token()).status()).isEqualTo(400);
  }

  @Test
  void qaSec04_nonMemberGets404ForProjectAndTask() {
    TestUser manager = user(Role.MANAGER);
    TestUser outsider = user(Role.MEMBER);
    String key = project(manager);
    String taskKey = task(key, manager, "Secret task").path("key").asString();
    assertThat(get("/api/v1/projects/" + key, outsider.token()).status()).isEqualTo(404);
    assertThat(get("/api/v1/tasks/" + taskKey, outsider.token()).status()).isEqualTo(404);
    assertThat(get("/api/v1/projects/" + key + "/tasks", outsider.token()).status()).isEqualTo(404);
    assertThat(get("/api/v1/projects/" + key + "/analytics/workload", outsider.token()).status())
        .isEqualTo(404);
    assertThat(
            post(
                    "/api/v1/projects/" + key + "/ask",
                    outsider.token(),
                    Map.of("question", "What happened?"))
                .status())
        .isEqualTo(404);
    // ADMIN sees everything
    assertThat(get("/api/v1/tasks/" + taskKey, user(Role.ADMIN).token()).status()).isEqualTo(200);
  }

  @Test
  void fr012_ownerUpdatesWithVersionAndKeyIsImmutable() {
    TestUser manager = user(Role.MANAGER);
    TestUser member = user(Role.MEMBER);
    String key = project(manager, member);
    Res ok =
        patch(
            "/api/v1/projects/" + key,
            manager.token(),
            Map.of("name", "Renamed project", "version", 0));
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.body().path("version").asInt()).isEqualTo(1);
    Res stale =
        patch(
            "/api/v1/projects/" + key, manager.token(), Map.of("name", "Stale edit", "version", 0));
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.code()).isEqualTo("CONFLICT_STALE");
    assertThat(
            patch("/api/v1/projects/" + key, manager.token(), Map.of("key", "OTHER", "version", 1))
                .status())
        .isEqualTo(400);
    assertThat(
            patch(
                    "/api/v1/projects/" + key,
                    member.token(),
                    Map.of("name", "Member edit", "version", 1))
                .status())
        .isEqualTo(403);
  }

  @Test
  void qaTask04_archivedProjectIsReadOnlyUntilUnarchived() {
    TestUser manager = user(Role.MANAGER);
    String key = project(manager);
    String taskKey = task(key, manager, "Before archive").path("key").asString();
    assertThat(post("/api/v1/projects/" + key + "/archive", manager.token(), null).status())
        .isEqualTo(200);
    Res create =
        post(
            "/api/v1/projects/" + key + "/tasks",
            manager.token(),
            Map.of("title", "After archive"));
    assertThat(create.status()).isEqualTo(409);
    assertThat(create.code()).isEqualTo("PROJECT_ARCHIVED");
    assertThat(
            patch(
                    "/api/v1/tasks/" + taskKey,
                    manager.token(),
                    Map.of("title", "Edited", "version", 0))
                .code())
        .isEqualTo("PROJECT_ARCHIVED");
    assertThat(
            post("/api/v1/tasks/" + taskKey + "/comments", manager.token(), Map.of("body", "hi"))
                .code())
        .isEqualTo("PROJECT_ARCHIVED");
    assertThat(
            post("/api/v1/tasks/" + taskKey + "/split-suggestions", manager.token(), null).code())
        .isEqualTo("PROJECT_ARCHIVED"); // BR-013
    assertThat(get("/api/v1/tasks/" + taskKey, manager.token()).status()).isEqualTo(200);
    post("/api/v1/projects/" + key + "/unarchive", manager.token(), null);
    assertThat(
            post(
                    "/api/v1/projects/" + key + "/tasks",
                    manager.token(),
                    Map.of("title", "After unarchive"))
                .status())
        .isEqualTo(201);
  }

  @Test
  void fr013_membersCapacityAndRemovalUnassignsOpenTasks() {
    TestUser manager = user(Role.MANAGER);
    TestUser member = user(Role.MEMBER);
    String key = project(manager);
    Res add =
        post(
            "/api/v1/projects/" + key + "/members",
            manager.token(),
            Map.of("userEmail", member.email().toUpperCase(), "capacityHours", 30));
    assertThat(add.status()).isEqualTo(201);
    assertThat(
            post(
                    "/api/v1/projects/" + key + "/members",
                    manager.token(),
                    Map.of("userEmail", member.email()))
                .code())
        .isEqualTo("ALREADY_MEMBER");
    assertThat(
            post(
                    "/api/v1/projects/" + key + "/members",
                    manager.token(),
                    Map.of("userEmail", "ghost@test.local"))
                .code())
        .isEqualTo("USER_NOT_FOUND");
    assertThat(
            patch(
                    "/api/v1/projects/" + key + "/members/" + member.id(),
                    manager.token(),
                    Map.of("capacityHours", 81))
                .status())
        .isEqualTo(400);
    assertThat(
            patch(
                    "/api/v1/projects/" + key + "/members/" + member.id(),
                    manager.token(),
                    Map.of("capacityHours", 20))
                .body()
                .path("capacityHours")
                .asDouble())
        .isEqualTo(20.0);

    String taskKey =
        task(key, manager, Map.of("title", "Assigned work", "assigneeId", member.id()))
            .path("key")
            .asString();
    assertThat(
            delete("/api/v1/projects/" + key + "/members/" + member.id(), manager.token()).status())
        .isEqualTo(204);
    JsonNode t = get("/api/v1/tasks/" + taskKey, manager.token()).body();
    assertThat(t.path("assignee").isNull()).isTrue();
    assertThat(t.path("activity").toString()).contains("ASSIGNEE_CHANGED");
    // QA-AUTHZ-03
    Res owner = delete("/api/v1/projects/" + key + "/members/" + manager.id(), manager.token());
    assertThat(owner.status()).isEqualTo(409);
    assertThat(owner.code()).isEqualTo("CANNOT_REMOVE_OWNER");
  }
}
