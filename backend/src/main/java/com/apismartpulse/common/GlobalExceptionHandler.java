package com.apismartpulse.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every error to RFC 9457 problem+json with a stable {@code code} (§7.3). */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest req) {
    ResponseEntity.BodyBuilder rb = ResponseEntity.status(e.status());
    Object retry = e.extras().get("retryAfter");
    if (retry != null) rb.header(HttpHeaders.RETRY_AFTER, retry.toString());
    return rb.body(
        Problems.build(
            e.status(), e.code(), e.title(), e.getMessage(), req.getRequestURI(), e.extras()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f ->
                    Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
            .toList();
    return validation(req, errors);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ProblemDetail> invalidParams(
      HandlerMethodValidationException e, HttpServletRequest req) {
    List<Map<String, String>> errors =
        e.getParameterValidationResults().stream()
            .flatMap(
                r ->
                    r.getResolvableErrors().stream()
                        .map(
                            err ->
                                Map.of(
                                    "field",
                                    String.valueOf(r.getMethodParameter().getParameterName()),
                                    "message",
                                    String.valueOf(err.getDefaultMessage()))))
            .toList();
    return validation(req, errors);
  }

  private ResponseEntity<ProblemDetail> validation(
      HttpServletRequest req, List<Map<String, String>> errors) {
    String detail =
        errors.isEmpty()
            ? "Request is invalid."
            : errors.get(0).get("field") + " " + errors.get(0).get("message");
    return respond(
        req,
        HttpStatus.BAD_REQUEST,
        "VALIDATION_FAILED",
        "Validation failed",
        detail,
        Map.of("errors", errors));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> unreadable(HttpServletRequest req) {
    return respond(
        req,
        HttpStatus.BAD_REQUEST,
        "VALIDATION_FAILED",
        "Validation failed",
        "Malformed JSON, wrong value type or unknown field in the request body.",
        Map.of());
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MissingRequestHeaderException.class
  })
  ResponseEntity<ProblemDetail> badParam(Exception e, HttpServletRequest req) {
    return respond(
        req,
        HttpStatus.BAD_REQUEST,
        "BAD_FILTER",
        "Bad request",
        "A request parameter is invalid.",
        Map.of());
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> stale(HttpServletRequest req) {
    return respond(
        req,
        HttpStatus.CONFLICT,
        "CONFLICT_STALE",
        "Stale version",
        "This item changed since you loaded it. Reload to see the latest version.",
        Map.of());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> integrity(
      DataIntegrityViolationException e, HttpServletRequest req) {
    log.warn("Integrity violation: {}", e.getMostSpecificCause().getClass().getSimpleName());
    return respond(
        req,
        HttpStatus.CONFLICT,
        "CONFLICT",
        "Conflict",
        "The change conflicts with existing data. Reload and try again.",
        Map.of());
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> denied(HttpServletRequest req) {
    return respond(
        req,
        HttpStatus.FORBIDDEN,
        "FORBIDDEN",
        "Forbidden",
        "You don't have permission to do that.",
        Map.of());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ProblemDetail> noResource(HttpServletRequest req) {
    return respond(
        req,
        HttpStatus.NOT_FOUND,
        "NOT_FOUND",
        "Not found",
        "The requested resource was not found.",
        Map.of());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ProblemDetail> method(HttpServletRequest req) {
    return respond(
        req,
        HttpStatus.METHOD_NOT_ALLOWED,
        "METHOD_NOT_ALLOWED",
        "Method not allowed",
        "This method is not supported for this resource.",
        Map.of());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> mediaType(HttpServletRequest req) {
    return respond(
        req,
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "Unsupported media type",
        "Send JSON with Content-Type: application/json.",
        Map.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
    log.error("Unexpected error on {} {}", req.getMethod(), req.getRequestURI(), e);
    return respond(
        req,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Something went wrong",
        "An unexpected error occurred. Quote the request ID if you report it.",
        Map.of());
  }

  private static ResponseEntity<ProblemDetail> respond(
      HttpServletRequest req,
      HttpStatus status,
      String code,
      String title,
      String detail,
      Map<String, Object> extras) {
    return ResponseEntity.status(status)
        .body(Problems.build(status, code, title, detail, req.getRequestURI(), extras));
  }
}
