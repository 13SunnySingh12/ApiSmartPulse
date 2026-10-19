package com.apismartpulse.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** BR-009 rebalancing rule — pure so it can be unit-tested with hand-computed cases. */
public final class Rebalancer {
  private Rebalancer() {}

  public record Member(long userId, String name, double capacity, double openHours) {
    int loadPct() {
      return pct(openHours, capacity);
    }
  }

  public record OpenTask(
      String key,
      String title,
      long assigneeId,
      String status,
      int priorityRank,
      LocalDate dueDate,
      boolean blocked,
      double hours) {}

  public record Suggestion(
      String taskKey,
      String taskTitle,
      double hours,
      long fromUserId,
      String fromName,
      long toUserId,
      String toName,
      int fromLoadBefore,
      int fromLoadAfter,
      int toLoadBefore,
      int toLoadAfter) {}

  public record Result(List<Suggestion> suggestions, List<Long> noSafeMove) {}

  /**
   * For each member over 100 %: take their open, unblocked TODO task with the lowest priority (tie:
   * latest due date, undated counts as latest) and suggest the member with the lowest load whose
   * load after the move stays ≤ 100 %.
   */
  public static Result suggest(List<Member> members, List<OpenTask> tasks) {
    List<Suggestion> out = new ArrayList<>();
    List<Long> none = new ArrayList<>();
    Comparator<OpenTask> order =
        Comparator.comparingInt(OpenTask::priorityRank)
            .thenComparing(OpenTask::dueDate, Comparator.nullsFirst(Comparator.reverseOrder()))
            .thenComparing(OpenTask::key);
    for (Member from : members) {
      if (from.loadPct() <= 100) continue;
      Optional<OpenTask> candidate =
          tasks.stream()
              .filter(
                  t -> t.assigneeId() == from.userId() && t.status().equals("TODO") && !t.blocked())
              .min(order);
      Optional<Member> target =
          candidate.flatMap(
              t ->
                  members.stream()
                      .filter(m -> m.userId() != from.userId())
                      .filter(m -> pct(m.openHours() + t.hours(), m.capacity()) <= 100)
                      .min(Comparator.comparingInt(Member::loadPct).thenComparing(Member::name)));
      if (target.isEmpty()) {
        none.add(from.userId());
        continue;
      }
      OpenTask t = candidate.get();
      Member to = target.get();
      out.add(
          new Suggestion(
              t.key(),
              t.title(),
              t.hours(),
              from.userId(),
              from.name(),
              to.userId(),
              to.name(),
              from.loadPct(),
              pct(from.openHours() - t.hours(), from.capacity()),
              to.loadPct(),
              pct(to.openHours() + t.hours(), to.capacity())));
    }
    return new Result(out, none);
  }

  static int pct(double hours, double capacity) {
    return (int) Math.round(hours / capacity * 100);
  }
}
