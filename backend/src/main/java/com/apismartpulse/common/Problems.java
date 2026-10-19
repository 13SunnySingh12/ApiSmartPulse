package com.apismartpulse.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/** Builds and writes Problem Details bodies; also used by servlet filters outside Spring MVC. */
public final class Problems {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Problems() {}

  public static ProblemDetail build(
      HttpStatus status,
      String code,
      String title,
      String detail,
      String instance,
      Map<String, Object> extras) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(
        URI.create(
            "https://apismartpulse.local/problems/"
                + code.toLowerCase(Locale.ROOT).replace('_', '-')));
    pd.setTitle(title);
    if (instance != null) pd.setInstance(URI.create(instance));
    pd.setProperty("code", code);
    pd.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
    extras.forEach(pd::setProperty);
    return pd;
  }

  public static void write(
      HttpServletRequest req,
      HttpServletResponse res,
      HttpStatus status,
      String code,
      String title,
      String detail)
      throws IOException {
    ProblemDetail pd = build(status, code, title, detail, req.getRequestURI(), Map.of());
    // Flatten like Spring MVC does, so filter-written errors have the same shape as controller
    // ones.
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", pd.getType().toString());
    body.put("title", pd.getTitle());
    body.put("status", pd.getStatus());
    body.put("detail", pd.getDetail());
    body.put("instance", pd.getInstance() == null ? null : pd.getInstance().toString());
    if (pd.getProperties() != null) body.putAll(pd.getProperties());
    res.setStatus(status.value());
    res.setContentType("application/problem+json");
    JSON.writeValue(res.getOutputStream(), body);
  }
}
