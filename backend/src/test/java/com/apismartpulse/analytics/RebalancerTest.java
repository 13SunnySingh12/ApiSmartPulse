package com.apismartpulse.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.apismartpulse.analytics.Rebalancer.Member;
import com.apismartpulse.analytics.Rebalancer.OpenTask;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** BR-009 unit cases. */
class RebalancerTest {
  private static OpenTask t(
      String key, long who, String status, int rank, String due, boolean blocked, double h) {
    return new OpenTask(
        key, key, who, status, rank, due == null ? null : LocalDate.parse(due), blocked, h);
  }

  @Test
  void picksLowestPriorityUnblockedTodoAndLeastLoadedSafeTarget() {
    List<Member> members =
        List.of(
            new Member(1, "Asha", 40, 58),
            new Member(2, "Ravi", 40, 18),
            new Member(3, "Priya", 30, 27));
    List<OpenTask> tasks =
        List.of(
            t("P-1", 1, "TODO", 3, "2026-10-20", false, 10),
            t("P-2", 1, "TODO", 1, "2026-10-21", true, 6), // blocked → skipped
            t("P-3", 1, "IN_PROGRESS", 1, null, false, 20), // not TODO → skipped
            t("P-4", 1, "TODO", 1, "2026-10-25", false, 8), // lowest priority, latest due
            t("P-5", 1, "TODO", 1, "2026-10-22", false, 14));
    var r = Rebalancer.suggest(members, tasks);
    assertThat(r.suggestions()).hasSize(1);
    var s = r.suggestions().get(0);
    assertThat(s.taskKey()).isEqualTo("P-4");
    assertThat(s.toUserId()).isEqualTo(2); // Ravi 45 % → 65 %; Priya 90 % would exceed 100 %
    assertThat(s.fromLoadBefore()).isEqualTo(145);
    assertThat(s.fromLoadAfter()).isEqualTo(125);
    assertThat(s.toLoadAfter()).isEqualTo(65);
  }

  @Test
  void undatedTaskCountsAsLatestDueDate() {
    List<Member> members = List.of(new Member(1, "A", 10, 20), new Member(2, "B", 40, 0));
    var r =
        Rebalancer.suggest(
            members,
            List.of(
                t("P-1", 1, "TODO", 1, "2030-01-01", false, 10),
                t("P-2", 1, "TODO", 1, null, false, 10)));
    assertThat(r.suggestions().get(0).taskKey()).isEqualTo("P-2");
  }

  @Test
  void noSafeMoveWhenNobodyCanAbsorb() {
    List<Member> members = List.of(new Member(1, "A", 10, 30), new Member(2, "B", 10, 9));
    var r = Rebalancer.suggest(members, List.of(t("P-1", 1, "TODO", 1, null, false, 30)));
    assertThat(r.suggestions()).isEmpty();
    assertThat(r.noSafeMove()).containsExactly(1L);
  }

  @Test
  void balancedTeamHasNoSuggestions() {
    var r =
        Rebalancer.suggest(
            List.of(new Member(1, "A", 40, 40)), List.of(t("P-1", 1, "TODO", 1, null, false, 40)));
    assertThat(r.suggestions()).isEmpty();
    assertThat(r.noSafeMove()).isEmpty();
  }
}
