package com.apismartpulse.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.IntegrationTest;
import com.apismartpulse.user.Role;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * QA-AI-01/03, FR-051/052/060/061/063/064, outbox (FR-062), AI limits (§5.9.2), error mapping
 * (§7.5).
 */
class AiIT extends IntegrationTest {
  @Autowired PredictionService predictions;
  @Autowired OutboxProcessor outbox;

  private void stub(String path, int status, String body) {
    AI.stubFor(
        WireMock.post(urlEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  @Test
  void qaAi01_aiDownStillCreatesTaskAndRetryJobFillsPrediction() {
    AI.resetAll();
    AI.stubFor(
        WireMock.post(WireMock.anyUrl())
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    JsonNode t = task(key, m, Map.of("title", "Created while AI is down", "estimateHours", 6));
    assertThat(t.path("prediction").path("status").asString()).isEqualTo("PENDING");

    AI.resetAll();
    aiClient.resetCircuit();
    stub(
        "/internal/v1/predict/batch",
        200,
        "{\"results\":[{\"taskId\":"
            + t.path("id").asLong()
            + ",\"predictedHours\":7.5,\"overdueProbability\":0.1,"
            + "\"riskLevel\":\"LOW\",\"suggestedPriority\":\"LOW\",\"priorityConfidence\":0.8,\"source\":\"MODEL\",\"modelVersion\":\"m1\"}]}");
    predictions.retryPending();
    JsonNode after =
        get("/api/v1/tasks/" + t.path("key").asString(), m.token()).body().path("prediction");
    assertThat(after.path("status").asString()).isEqualTo("OK");
    assertThat(after.path("predictedHours").asDouble()).isEqualTo(7.5);
  }

  @Test
  void qaAi03_slowAiTimesOutAt3sAndTaskStaysPending() {
    AI.resetAll();
    AI.stubFor(
        WireMock.post(urlEqualTo("/internal/v1/predict"))
            .willReturn(aResponse().withFixedDelay(5000).withBody("{}")));
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    long start = System.nanoTime();
    JsonNode t = task(key, m, "Slow prediction");
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertThat(t.path("prediction").path("status").asString()).isEqualTo("PENDING");
    assertThat(ms).isLessThan(4800);
  }

  @Test
  void retriesExhaustedFallBackToSpringHeuristic() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    long id =
        task(key, m, Map.of("title", "Security outage fix", "dueDate", "2099-01-01"))
            .path("id")
            .asLong();
    jdbc.sql("UPDATE task_predictions SET status = 'PENDING', attempts = 11 WHERE task_id = ?")
        .param(id)
        .update();
    AI.resetAll();
    AI.stubFor(WireMock.post(WireMock.anyUrl()).willReturn(aResponse().withStatus(500)));
    predictions.retryPending();
    String source =
        jdbc.sql(
                "SELECT source || ':' || suggested_priority FROM task_predictions WHERE task_id = ?")
            .param(id)
            .query(String.class)
            .single();
    assertThat(source).isEqualTo("HEURISTIC:URGENT");
  }

  @Test
  void fr061_askPassesDegradedResponseThroughWithSources() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    stub(
        "/internal/v1/rag/ask",
        200,
        "{\"answer\":null,\"grounded\":false,\"degraded\":true,\"reason\":\"LLM_DISABLED\",\"sources\":[{\"taskKey\":\""
            + key
            + "-1\",\"title\":\"t\",\"status\":\"DONE\",\"sourceType\":\"TASK\",\"snippet\":\"s\",\"similarity\":0.8}],\"provider\":\"none\",\"model\":null}");
    Res r =
        post(
            "/api/v1/projects/" + key + "/ask",
            m.token(),
            Map.of("question", "What slipped last month?"));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("degraded").asBoolean()).isTrue();
    assertThat(r.body().path("reason").asString()).isEqualTo("LLM_DISABLED");
    assertThat(r.body().path("sources").size()).isEqualTo(1);
    assertThat(
            post("/api/v1/projects/" + key + "/ask", m.token(), Map.of("question", "no")).status())
        .isEqualTo(400);
    assertThat(
            post(
                    "/api/v1/projects/" + key + "/ask",
                    m.token(),
                    Map.of("question", "Long range?", "from", "2024-01-01", "to", "2025-06-01"))
                .code())
        .isEqualTo("RANGE_TOO_LARGE");
    AI.verify(
        postRequestedFor(urlEqualTo("/internal/v1/rag/ask"))
            .withHeader("X-Internal-Token", WireMock.matching(".{32,}")));
  }

  @Test
  void perUserAiLimitIs429() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    stub(
        "/internal/v1/rag/ask",
        200,
        "{\"answer\":\"ok\",\"grounded\":true,\"degraded\":false,\"sources\":[]}");
    int last = 0;
    for (int i = 0; i < 6; i++)
      last =
          post("/api/v1/projects/" + key + "/ask", m.token(), Map.of("question", "Question " + i))
              .status();
    assertThat(last).isEqualTo(429);
  }

  @Test
  void warmingUpAndDownAreMappedTo503() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    stub("/internal/v1/similar", 503, "{\"code\":\"AI_WARMING_UP\",\"detail\":\"loading\"}");
    Res warm =
        post(
            "/api/v1/projects/" + key + "/tasks/duplicate-check",
            m.token(),
            Map.of("title", "Fix login"));
    assertThat(warm.status()).isEqualTo(503);
    assertThat(warm.code()).isEqualTo("AI_WARMING_UP");
    stub(
        "/internal/v1/similar",
        200,
        "{\"matches\":[{\"taskId\":1,\"taskKey\":\""
            + key
            + "-1\",\"title\":\"Login\",\"status\":\"TODO\",\"similarity\":0.91}],\"threshold\":0.85}");
    assertThat(
            post(
                    "/api/v1/projects/" + key + "/tasks/duplicate-check",
                    m.token(),
                    Map.of("title", "Fix login"))
                .body()
                .path("matches")
                .size())
        .isEqualTo(1);
  }

  @Test
  void fr064_splitRequiresLargeTaskAndMapsBadOutput() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    String small =
        task(key, m, Map.of("title", "Small task", "estimateHours", 4)).path("key").asString();
    assertThat(post("/api/v1/tasks/" + small + "/split-suggestions", m.token(), null).code())
        .isEqualTo("TASK_TOO_SMALL");
    String big =
        task(key, m, Map.of("title", "Big task", "estimateHours", 20)).path("key").asString();
    stub("/internal/v1/split", 502, "{\"code\":\"AI_BAD_OUTPUT\",\"detail\":\"bad\"}");
    Res bad = post("/api/v1/tasks/" + big + "/split-suggestions", m.token(), null);
    assertThat(bad.status()).isEqualTo(502);
    assertThat(bad.code()).isEqualTo("AI_BAD_OUTPUT");
    stub("/internal/v1/split", 429, "{\"code\":\"AI_RATE_LIMITED\",\"detail\":\"busy\"}");
    AI.stubFor(
        WireMock.post(urlEqualTo("/internal/v1/split"))
            .willReturn(
                aResponse().withStatus(429).withHeader("Retry-After", "12").withBody("{}")));
    Res busy = post("/api/v1/tasks/" + big + "/split-suggestions", m.token(), null);
    assertThat(busy.status()).isEqualTo(429);
    assertThat(busy.header("Retry-After")).isEqualTo("12");
    // BR-012: nothing was saved
    assertThat(get("/api/v1/tasks/" + big, m.token()).body().path("subtasks").size()).isZero();
  }

  @Test
  void fr062_outboxSyncsAndBacksOff() {
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    long id = task(key, m, "Embed me").path("id").asLong();
    jdbc.sql("UPDATE ai_sync_queue SET next_attempt_at = now() - interval '1 second'").update();
    stub("/internal/v1/embeddings/upsert", 500, "{}");
    outbox.processBatch();
    var row =
        jdbc.sql(
                "SELECT status, attempts, last_error FROM ai_sync_queue WHERE entity_type = 'TASK' AND entity_id = ?")
            .param(id)
            .query((rs, i) -> rs.getString(1) + "/" + rs.getInt(2) + "/" + rs.getString(3))
            .single();
    assertThat(row).isEqualTo("PENDING/1/AI_UNAVAILABLE");
    aiClient.resetCircuit();
    stub("/internal/v1/embeddings/upsert", 200, "{\"chunks\":1}");
    jdbc.sql(
            "UPDATE ai_sync_queue SET next_attempt_at = now() - interval '1 second' WHERE entity_id = ?")
        .param(id)
        .update();
    outbox.processBatch();
    String status =
        jdbc.sql("SELECT status FROM ai_sync_queue WHERE entity_type = 'TASK' AND entity_id = ?")
            .param(id)
            .query(String.class)
            .single();
    assertThat(status).isEqualTo("DONE");
  }

  @Test
  void fr052_adminMlEndpoints() {
    TestUser admin = user(Role.ADMIN);
    TestUser member = user(Role.MEMBER);
    assertThat(post("/api/v1/admin/ml/train", member.token(), null).status()).isEqualTo(403);
    stub("/internal/v1/train", 202, "{\"runIds\":[1,2,3]}");
    Res train = post("/api/v1/admin/ml/train", admin.token(), null);
    assertThat(train.status()).isEqualTo(202);
    assertThat(train.body().path("runIds").size()).isEqualTo(3);
    stub("/internal/v1/train", 409, "{\"code\":\"TRAINING_IN_PROGRESS\",\"detail\":\"busy\"}");
    assertThat(post("/api/v1/admin/ml/train", admin.token(), null).code())
        .isEqualTo("TRAINING_IN_PROGRESS");
    Res models = get("/api/v1/admin/ml/models", admin.token());
    assertThat(models.status()).isEqualTo(200);
    assertThat(models.body().path("outbox").has("FAILED")).isTrue();
    assertThat(post("/api/v1/admin/ml/reindex", admin.token(), null).status()).isEqualTo(202);
  }

  @Test
  void circuitOpensAfterFiveFailures() {
    AI.resetAll();
    AI.stubFor(
        WireMock.post(WireMock.anyUrl())
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    TestUser m = user(Role.MANAGER);
    String key = project(m);
    for (int i = 0; i < 5; i++) task(key, m, "Failure " + i);
    assertThat(aiClient.circuitOpen()).isTrue();
    int before = AI.getAllServeEvents().size();
    task(key, m, "Skipped call");
    assertThat(AI.getAllServeEvents().size()).isEqualTo(before);
    AI.verify(
        postRequestedFor(urlEqualTo("/internal/v1/predict"))
            .withHeader("X-Request-Id", WireMock.matching(".+")));
  }
}
