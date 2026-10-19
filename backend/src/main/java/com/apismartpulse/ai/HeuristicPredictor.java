package com.apismartpulse.ai;

import com.apismartpulse.ai.AiServiceClient.Prediction;
import com.apismartpulse.ai.AiServiceClient.TaskFeatures;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rule-based fallback (§5.9.5), mirrored exactly by ai-service/app/ml/heuristics.py. Parity is
 * checked against ai-service/tests/fixtures/heuristics_parity.json in both test suites (TR-007).
 */
public final class HeuristicPredictor {
  public static final String VERSION = "heuristic-v1";
  private static final Pattern URGENT = Pattern.compile("\\b(urgent|asap|outage|security)\\b");
  private static final Pattern HIGH = Pattern.compile("\\b(bug|fix|error)\\b");

  private HeuristicPredictor() {}

  /**
   * @param medianByPriority project median actual hours of DONE tasks per priority
   * @param globalMedian median actual hours of all DONE tasks (any project), or null
   */
  public static Prediction predict(
      TaskFeatures t, Map<String, Double> medianByPriority, Double globalMedian) {
    Double median = medianByPriority.get(t.priority());
    double estimate =
        roundHalf(median != null ? median : globalMedian != null ? globalMedian : 4.0);

    String text =
        (t.title() + " " + (t.description() == null ? "" : t.description()))
            .toLowerCase(Locale.ROOT);
    String priority;
    double confidence;
    if (URGENT.matcher(text).find()) {
      priority = "URGENT";
      confidence = 0.6;
    } else if (HIGH.matcher(text).find()) {
      priority = "HIGH";
      confidence = 0.6;
    } else {
      priority = "MEDIUM";
      confidence = 0.4;
    }

    BigDecimal probability = null;
    String risk = "NA";
    if (t.dueDate() != null) {
      double remaining = t.estimateHours() != null ? t.estimateHours().doubleValue() : estimate;
      double p =
          overdueProbability(remaining, workingDays(t.today(), t.dueDate()), t.openBlockers() > 0);
      probability = BigDecimal.valueOf(p).setScale(3, RoundingMode.HALF_UP);
      risk = riskLevel(probability.doubleValue());
    }
    return new Prediction(
        t.id(),
        BigDecimal.valueOf(estimate).setScale(1, RoundingMode.HALF_UP),
        probability,
        risk,
        priority,
        BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP),
        "HEURISTIC",
        VERSION);
  }

  static double overdueProbability(double remainingHours, int workingDays, boolean blocked) {
    double p = workingDays <= 0 ? 1.0 : clamp(remainingHours / (workingDays * 6 * 0.8));
    return clamp(p + (blocked ? 0.15 : 0));
  }

  /** BR-010: LOW < 0.33 ≤ MEDIUM ≤ 0.66 < HIGH. */
  public static String riskLevel(double p) {
    if (p < 0.33) return "LOW";
    if (p <= 0.66) return "MEDIUM";
    return "HIGH";
  }

  /** Weekdays from today through the due date, inclusive. */
  static int workingDays(LocalDate today, LocalDate due) {
    int days = 0;
    for (LocalDate d = today; !d.isAfter(due); d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) days++;
    }
    return days;
  }

  static double roundHalf(double hours) {
    return Math.max(0.5, Math.floor(hours * 2 + 0.5) / 2);
  }

  private static double clamp(double v) {
    return Math.max(0, Math.min(1, v));
  }
}
