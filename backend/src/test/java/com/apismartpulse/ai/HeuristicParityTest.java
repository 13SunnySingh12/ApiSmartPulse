package com.apismartpulse.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.ai.AiServiceClient.Prediction;
import com.apismartpulse.ai.AiServiceClient.TaskFeatures;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TR-007 / QA-AI-02: Java heuristic matches the shared fixture also asserted by the Python suite.
 */
class HeuristicParityTest {
  @Test
  void matchesSharedFixture() {
    JsonNode fixture =
        JsonMapper.builder()
            .build()
            .readTree(new File("../ai-service/tests/fixtures/heuristics_parity.json"));
    for (JsonNode c : fixture.path("cases")) {
      JsonNode in = c.path("input");
      TaskFeatures f =
          new TaskFeatures(
              1,
              1,
              in.path("title").asString(),
              in.path("description").isNull() ? null : in.path("description").asString(),
              in.path("priority").asString(),
              List.of(),
              0,
              0,
              null,
              in.path("estimateHours").isNull()
                  ? null
                  : new BigDecimal(in.path("estimateHours").asString()),
              in.path("dueDate").isNull() ? null : LocalDate.parse(in.path("dueDate").asString()),
              in.path("openBlockers").asInt(),
              BigDecimal.ZERO,
              LocalDate.parse(in.path("today").asString()));
      Map<String, Double> medians = new HashMap<>();
      c.path("medianByPriority")
          .properties()
          .forEach(e -> medians.put(e.getKey(), e.getValue().asDouble()));
      Double global = c.path("globalMedian").isNull() ? null : c.path("globalMedian").asDouble();
      Prediction p = HeuristicPredictor.predict(f, medians, global);
      JsonNode exp = c.path("expected");
      String name = c.path("name").asString();
      assertThat(p.predictedHours().doubleValue())
          .as(name)
          .isEqualTo(exp.path("predictedHours").asDouble());
      if (exp.path("overdueProbability").isNull())
        assertThat(p.overdueProbability()).as(name).isNull();
      else
        assertThat(p.overdueProbability().doubleValue())
            .as(name)
            .isEqualTo(exp.path("overdueProbability").asDouble());
      assertThat(p.riskLevel()).as(name).isEqualTo(exp.path("riskLevel").asString());
      assertThat(p.suggestedPriority())
          .as(name)
          .isEqualTo(exp.path("suggestedPriority").asString());
      assertThat(p.priorityConfidence().doubleValue())
          .as(name)
          .isEqualTo(exp.path("priorityConfidence").asDouble());
      assertThat(p.source()).isEqualTo("HEURISTIC");
    }
  }

  @Test
  void br010_riskThresholds() {
    assertThat(HeuristicPredictor.riskLevel(0.329)).isEqualTo("LOW");
    assertThat(HeuristicPredictor.riskLevel(0.33)).isEqualTo("MEDIUM");
    assertThat(HeuristicPredictor.riskLevel(0.66)).isEqualTo("MEDIUM");
    assertThat(HeuristicPredictor.riskLevel(0.661)).isEqualTo("HIGH");
  }
}
