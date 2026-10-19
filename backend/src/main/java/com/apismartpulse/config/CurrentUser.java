package com.apismartpulse.config;

import com.apismartpulse.user.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller, from either an access JWT or a public API key. */
public record CurrentUser(long id, Role role) {

  public boolean isAdmin() {
    return role == Role.ADMIN;
  }

  public static CurrentUser get() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof CurrentUser cu) return cu;
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      return new CurrentUser(
          Long.parseLong(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
    }
    throw new IllegalStateException("No authenticated user");
  }
}
