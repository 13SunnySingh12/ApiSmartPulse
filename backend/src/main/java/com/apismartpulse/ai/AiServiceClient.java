package com.apismartpulse.ai;

import com.apismartpulse.common.ApiException;
import com.apismartpulse.common.RequestIdFilter;
import com.apismartpulse.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way Spring talks to FastAPI (TR-003): connect timeout 1 s, per-call read timeouts
 * (NFR-003) and a simple circuit guard — after 5 consecutive failures, calls are skipped for 60 s.
 */
@Component
public class AiServiceClient {
  private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);
  static final int FAILURE_THRESHOLD = 5;
  static final Duration OPEN_FOR = Duration.ofSeconds(60);

  private final AppProperties props;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(1))
          .build();
  private final Map<Duration, RestClient> clients = new ConcurrentHashMap<>();
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private volatile long openUntilMillis;

  public AiServiceClient(AppProperties props, JsonMapper json, Clock clock) {
    this.props = props;
    this.json = json;
    this.clock = clock;
  }

  // ── contract records (§7.5) ─────────────────────────────────────────────────

  public record TaskFeatures(
      long id,
      long projectId,
      String title,
      String description,
      String priority,
      List<String> labels,
      int subtaskCount,
      int descriptionLength,
      Long assigneeId,
      BigDecimal estimateHours,
      LocalDate dueDate,
      int openBlockers,
      BigDecimal assigneeOpenHours,
      LocalDate today) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Prediction(
      Long taskId,
      BigDecimal predictedHours,
      BigDecimal overdueProbability,
      String riskLevel,
      String suggestedPriority,
      BigDecimal priorityConfidence,
      String source,
      String modelVersion) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BatchResult(List<Prediction> results) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Match(
      long taskId, String taskKey, String title, String status, double similarity) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SimilarResult(List<Match> matches, double threshold) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Source(
      String taskKey,
      String title,
      String status,
      String sourceType,
      String snippet,
      double similarity) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AskResult(
      String answer,
      boolean grounded,
      boolean degraded,
      String reason,
      Integer retryAfter,
      List<Source> sources,
      String provider,
      String model) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Suggestion(String title) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SplitResult(List<Suggestion> suggestions, String provider) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TrainResult(List<Long> runIds) {}

  // ── calls ───────────────────────────────────────────────────────────────────

  public Prediction predict(TaskFeatures task) {
    return post(
        "/internal/v1/predict", Map.of("task", task), Duration.ofSeconds(3), Prediction.class);
  }

  public BatchResult predictBatch(List<TaskFeatures> tasks) {
    return post(
        "/internal/v1/predict/batch",
        Map.of("tasks", tasks),
        Duration.ofSeconds(30),
        BatchResult.class);
  }

  public TrainResult train(long triggeredBy) {
    return post(
        "/internal/v1/train",
        Map.of("triggeredBy", triggeredBy),
        Duration.ofSeconds(5),
        TrainResult.class);
  }

  public void upsertEmbedding(String entityType, long entityId) {
    post(
        "/internal/v1/embeddings/upsert",
        Map.of("entityType", entityType, "entityId", entityId),
        Duration.ofSeconds(10),
        JsonNode.class);
  }

  public void deleteEmbedding(String entityType, long entityId) {
    post(
        "/internal/v1/embeddings/delete",
        Map.of("entityType", entityType, "entityId", entityId),
        Duration.ofSeconds(5),
        JsonNode.class);
  }

  public SimilarResult similar(long projectId, String text, int limit) {
    return post(
        "/internal/v1/similar",
        Map.of("projectId", projectId, "text", text, "limit", limit),
        Duration.ofSeconds(2),
        SimilarResult.class);
  }

  public AskResult ask(long projectId, String question, LocalDate from, LocalDate to) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("projectId", projectId);
    body.put("question", question);
    body.put("from", from);
    body.put("to", to);
    return post("/internal/v1/rag/ask", body, Duration.ofSeconds(20), AskResult.class);
  }

  public SplitResult split(long taskId) {
    return post(
        "/internal/v1/split", Map.of("taskId", taskId), Duration.ofSeconds(20), SplitResult.class);
  }

  /** Closes the circuit (used by tests). */
  public void resetCircuit() {
    consecutiveFailures.set(0);
    openUntilMillis = 0;
  }

  public boolean circuitOpen() {
    return clock.millis() < openUntilMillis;
  }

  private <T> T post(String path, Object body, Duration readTimeout, Class<T> type) {
    if (circuitOpen()) throw unavailable("AI service is temporarily unavailable.");
    try {
      T result =
          client(readTimeout)
              .post()
              .uri(path)
              .header("X-Internal-Token", props.internalServiceToken())
              .header(RequestIdFilter.HEADER, requestId())
              .body(body)
              .retrieve()
              .body(type);
      consecutiveFailures.set(0);
      return result;
    } catch (ResourceAccessException e) {
      recordFailure();
      log.warn("AI service call {} failed: {}", path, e.getClass().getSimpleName());
      throw unavailable("AI service is unavailable right now.");
    } catch (RestClientResponseException e) {
      throw map(path, e);
    }
  }

  private ApiException map(String path, RestClientResponseException e) {
    int status = e.getStatusCode().value();
    String code = null;
    String detail = null;
    try {
      JsonNode node = json.readTree(e.getResponseBodyAsString());
      code = node.path("code").isString() ? node.path("code").asString() : null;
      detail = node.path("detail").isString() ? node.path("detail").asString() : null;
    } catch (RuntimeException ignored) {
      // non-JSON error body
    }
    // A structured error (e.g. AI_WARMING_UP, LLM_DISABLED) is a deliberate answer, not an outage.
    if (status >= 500 && code == null) recordFailure();
    else consecutiveFailures.set(0);
    log.warn("AI service call {} returned {} {}", path, status, code);
    if (status == 429) {
      String retry =
          e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
      return new ApiException(
              HttpStatus.TOO_MANY_REQUESTS,
              "AI_RATE_LIMITED",
              "AI is busy",
              detail != null ? detail : "AI is busy — try again shortly.")
          .with("retryAfter", retry != null ? retry : "30");
    }
    if (status == 502) {
      return new ApiException(
          HttpStatus.BAD_GATEWAY,
          "AI_BAD_OUTPUT",
          "AI returned unusable output",
          "Couldn't generate suggestions. Try again later.");
    }
    if (status == 503 && "AI_WARMING_UP".equals(code)) {
      return new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AI_WARMING_UP",
          "AI is warming up",
          "The AI model is still loading. Try again in a minute.");
    }
    if (status == 409) {
      return ApiException.conflict(
          code != null ? code : "CONFLICT", "Conflict", detail != null ? detail : "Conflict.");
    }
    if (status >= 400 && status < 500) {
      return ApiException.badRequest(
          code != null ? code : "VALIDATION_FAILED",
          detail != null ? detail : "The AI request was invalid.");
    }
    return unavailable(detail != null ? detail : "AI service is unavailable right now.");
  }

  private static String requestId() {
    String id = MDC.get(RequestIdFilter.MDC_KEY);
    return id != null ? id : java.util.UUID.randomUUID().toString();
  }

  private void recordFailure() {
    if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
      openUntilMillis = clock.millis() + OPEN_FOR.toMillis();
      consecutiveFailures.set(0);
      log.warn("AI circuit open for {} s", OPEN_FOR.toSeconds());
    }
  }

  private static ApiException unavailable(String detail) {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "AI unavailable", detail);
  }

  private RestClient client(Duration readTimeout) {
    return clients.computeIfAbsent(
        readTimeout,
        t -> {
          JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(http);
          f.setReadTimeout(t);
          return RestClient.builder().baseUrl(props.aiServiceUrl()).requestFactory(f).build();
        });
  }
}
