package com.apismartpulse.task;

import com.apismartpulse.common.ApiException;
import com.apismartpulse.common.PageResponse;
import com.apismartpulse.task.Task.Priority;
import com.apismartpulse.task.Task.Status;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Validated list filters for API-021 / API-061 (SEC-022: everything whitelisted). */
public record TaskFilter(
    List<Status> statuses,
    Long assigneeId,
    boolean unassigned,
    List<Priority> priorities,
    List<String> risks,
    String label,
    LocalDate dueBefore,
    LocalDate dueAfter,
    String tsQuery,
    String orderBy,
    int page,
    int size) {

  private static final Map<String, String> SORT_COLUMNS =
      Map.of(
          "dueDate", "t.due_date",
          "priority",
              "CASE t.priority WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'HIGH' THEN 3 ELSE 4 END",
          "createdAt", "t.created_at",
          "updatedAt", "t.updated_at",
          "risk", "tp.overdue_probability");
  private static final List<String> RISKS = List.of("LOW", "MEDIUM", "HIGH", "NA");
  private static final Pattern WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

  public static TaskFilter parse(
      String status,
      String assigneeId,
      String priority,
      String risk,
      String label,
      String dueBefore,
      String dueAfter,
      String q,
      String sort,
      int page,
      int size) {
    PageResponse.checkPaging(page, size);
    LocalDate before = date("dueBefore", dueBefore);
    LocalDate after = date("dueAfter", dueAfter);
    if (before != null && after != null && after.isAfter(before)) {
      throw ApiException.badRequest("BAD_FILTER", "dueAfter must be on or before dueBefore.");
    }
    Long assignee = null;
    boolean unassigned = false;
    if (assigneeId != null && !assigneeId.isBlank()) {
      if (assigneeId.equals("unassigned")) unassigned = true;
      else {
        try {
          assignee = Long.parseLong(assigneeId);
        } catch (NumberFormatException e) {
          throw ApiException.badRequest(
              "BAD_FILTER", "assigneeId must be a number or 'unassigned'.");
        }
      }
    }
    String tsq = null;
    if (q != null && !q.isBlank()) {
      if (q.length() < 2 || q.length() > 100) {
        throw ApiException.badRequest("BAD_FILTER", "q must be 2–100 characters.");
      }
      // Each word becomes a prefix term; punctuation (and anything SQL-like) is dropped.
      String[] words =
          Arrays.stream(WORD.split(q.toLowerCase()))
              .filter(w -> !w.isEmpty())
              .toArray(String[]::new);
      if (words.length > 0)
        tsq = String.join(" & ", Arrays.stream(words).map(w -> w + ":*").toList());
      else tsq = "";
    }
    return new TaskFilter(
        csv(status, "status", Status::valueOf),
        assignee,
        unassigned,
        csv(priority, "priority", Priority::valueOf),
        csv(
            risk,
            "risk",
            r -> {
              if (!RISKS.contains(r)) throw new IllegalArgumentException();
              return r;
            }),
        label == null || label.isBlank() ? null : label.trim(),
        before,
        after,
        tsq,
        orderBy(sort),
        page,
        size);
  }

  private static String orderBy(String sort) {
    if (sort == null || sort.isBlank()) return "t.number DESC";
    String[] parts = sort.split(",", -1);
    String column = SORT_COLUMNS.get(parts[0]);
    String dir = parts.length > 1 ? parts[1].toLowerCase() : "asc";
    if (column == null || parts.length > 2 || !(dir.equals("asc") || dir.equals("desc"))) {
      throw ApiException.badRequest(
          "BAD_FILTER",
          "sort must be one of dueDate, priority, createdAt, updatedAt, risk with ,asc or ,desc.");
    }
    return column + " " + dir.toUpperCase() + " NULLS LAST, t.number DESC";
  }

  private static LocalDate date(String name, String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest("BAD_FILTER", name + " must be a YYYY-MM-DD date.");
    }
  }

  private static <T> List<T> csv(String value, String name, Function<String, T> parse) {
    if (value == null || value.isBlank()) return List.of();
    try {
      return Arrays.stream(value.split(",")).map(String::trim).map(parse).toList();
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest("BAD_FILTER", "Invalid " + name + " value.");
    }
  }
}
