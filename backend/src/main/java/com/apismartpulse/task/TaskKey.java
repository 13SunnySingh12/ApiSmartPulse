package com.apismartpulse.task;

import com.apismartpulse.common.ApiException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Readable task key such as {@code PULSE-42}. Malformed keys are treated as not found. */
public record TaskKey(String projectKey, int number) {
  private static final Pattern P = Pattern.compile("^([A-Z]{2,6})-(\\d{1,9})$");

  public static TaskKey parse(String key) {
    Matcher m = key == null ? null : P.matcher(key);
    if (m == null || !m.matches()) throw ApiException.notFound();
    return new TaskKey(m.group(1), Integer.parseInt(m.group(2)));
  }

  @Override
  public String toString() {
    return projectKey + "-" + number;
  }
}
