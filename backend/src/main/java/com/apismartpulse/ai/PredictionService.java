package com.apismartpulse.ai;

import com.apismartpulse.ai.AiServiceClient.Prediction;
import com.apismartpulse.ai.AiServiceClient.TaskFeatures;
import com.apismartpulse.common.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Predictions for open tasks (FR-050/051/054). Called outside the write transaction: a failure
 * leaves the row PENDING; the retry job fills it later and, after the last attempt, falls back to
 * the Spring heuristic so every open task eventually shows a value.
 */
@Service
public class PredictionService {
  private static final Logger log = LoggerFactory.getLogger(PredictionService.class);
  static final int MAX_ATTEMPTS = 12;
  static final int BATCH = 200;

  private final AiServiceClient ai;
  private final PredictionStore store;
  private volatile String lastModelSignature;

  public PredictionService(AiServiceClient ai, PredictionStore store) {
    this.ai = ai;
    this.store = store;
  }

  /** Best effort, synchronous (3 s timeout). Never throws. */
  public void refresh(long taskId) {
    List<TaskFeatures> features = store.features(List.of(taskId));
    if (features.isEmpty()) return; // DONE or deleted
    try {
      Prediction p = ai.predict(features.get(0));
      store.saveOk(withId(p, taskId));
    } catch (ApiException e) {
      store.markPending(taskId);
      log.debug("Prediction for task {} deferred: {}", taskId, e.code());
    }
  }

  /** Retry job (every 5 min): PENDING rows of open tasks, max 12 attempts, then heuristic. */
  public int retryPending() {
    String signature = store.activeModelSignature();
    if (lastModelSignature != null && !signature.equals(lastModelSignature)) {
      log.info("Active ML models changed; re-scoring open tasks");
      lastModelSignature = signature;
      return rescoreAll();
    }
    lastModelSignature = signature;
    List<Long> ids = store.pendingOpenTasks(BATCH);
    if (ids.isEmpty()) return 0;
    List<TaskFeatures> features = store.features(ids);
    try {
      return save(ai.predictBatch(features).results(), features);
    } catch (ApiException e) {
      List<Long> exhausted = store.recordFailedAttempt(ids, MAX_ATTEMPTS);
      Set<Long> ex = Set.copyOf(exhausted);
      heuristic(features.stream().filter(f -> ex.contains(f.id())).toList());
      return exhausted.size();
    }
  }

  /** Nightly (02:00 APP_TIMEZONE) and after retraining: every open task, pages of 200. */
  public int rescoreAll() {
    int done = 0;
    long after = 0;
    while (true) {
      List<Long> ids = store.openTaskIds(after, BATCH);
      if (ids.isEmpty()) return done;
      after = ids.get(ids.size() - 1);
      List<TaskFeatures> features = store.features(ids);
      try {
        done += save(ai.predictBatch(features).results(), features);
      } catch (ApiException e) {
        done += heuristic(features); // due-date proximity still updates risk without the AI service
      }
    }
  }

  int heuristic(List<TaskFeatures> features) {
    Map<Long, Map<String, Double>> medians = new HashMap<>();
    Double global = features.isEmpty() ? null : store.globalMedian();
    for (TaskFeatures f : features) {
      Map<String, Double> m = medians.computeIfAbsent(f.projectId(), store::medianByPriority);
      store.saveOk(HeuristicPredictor.predict(f, m, global));
    }
    return features.size();
  }

  private int save(List<Prediction> results, List<TaskFeatures> features) {
    Set<Long> requested = features.stream().map(TaskFeatures::id).collect(Collectors.toSet());
    int n = 0;
    for (Prediction p : results) {
      if (p.taskId() != null && requested.contains(p.taskId())) {
        store.saveOk(p);
        n++;
      }
    }
    return n;
  }

  private static Prediction withId(Prediction p, long taskId) {
    return new Prediction(
        taskId,
        p.predictedHours(),
        p.overdueProbability(),
        p.riskLevel(),
        p.suggestedPriority(),
        p.priorityConfidence(),
        p.source(),
        p.modelVersion());
  }
}
