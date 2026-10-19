package com.apismartpulse.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Business error rendered as RFC 9457 Problem Details by {@link GlobalExceptionHandler}. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final String title;
  private final Map<String, Object> extras = new LinkedHashMap<>();

  public ApiException(HttpStatus status, String code, String title, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
    this.title = title;
  }

  public ApiException with(String property, Object value) {
    extras.put(property, value);
    return this;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String title() {
    return title;
  }

  public Map<String, Object> extras() {
    return extras;
  }

  public static ApiException notFound() {
    return new ApiException(
        HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", "The requested resource was not found.");
  }

  public static ApiException notFound(String code, String detail) {
    return new ApiException(HttpStatus.NOT_FOUND, code, "Not found", detail);
  }

  public static ApiException forbidden(String detail) {
    return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden", detail);
  }

  public static ApiException badRequest(String code, String detail) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, "Bad request", detail);
  }

  public static ApiException validation(String field, String message) {
    return new ApiException(
            HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", field + " " + message)
        .with("errors", java.util.List.of(Map.of("field", field, "message", message)));
  }

  public static ApiException conflict(String code, String title, String detail) {
    return new ApiException(HttpStatus.CONFLICT, code, title, detail);
  }

  public static ApiException archived() {
    return conflict("PROJECT_ARCHIVED", "Project is archived", "Archived projects are read-only.");
  }

  public static ApiException stale(int currentVersion) {
    return conflict(
            "CONFLICT_STALE",
            "Stale version",
            "This item changed since you loaded it. Reload to see the latest version.")
        .with("currentVersion", currentVersion);
  }
}
