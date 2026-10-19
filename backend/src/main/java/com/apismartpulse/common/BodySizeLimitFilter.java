package com.apismartpulse.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects request bodies over 64 KB with 413 PAYLOAD_TOO_LARGE (SEC-021). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BodySizeLimitFilter extends OncePerRequestFilter {
  static final long MAX_BYTES = 64 * 1024;

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    // ponytail: trusts Content-Length; chunked bodies without it are capped by Tomcat's
    // max-swallow-size. Wrap the input stream if a client ever streams large chunked JSON.
    if (req.getContentLengthLong() > MAX_BYTES) {
      Problems.write(
          req,
          res,
          HttpStatus.PAYLOAD_TOO_LARGE,
          "PAYLOAD_TOO_LARGE",
          "Payload too large",
          "Request bodies are limited to 64 KB.");
      return;
    }
    chain.doFilter(req, res);
  }
}
