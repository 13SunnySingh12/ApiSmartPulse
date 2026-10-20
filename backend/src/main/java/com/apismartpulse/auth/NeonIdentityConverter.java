package com.apismartpulse.auth;

import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.user.User;
import com.apismartpulse.user.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maps a verified Neon Auth token to the app user that owns roles and project memberships. The
 * first request of a new identity creates its app user (role MEMBER). An existing app user with the
 * same email is linked only when the provider says the email is verified; otherwise linking would
 * let anyone who signs up with someone else's address take over that account.
 */
public class NeonIdentityConverter implements Converter<Jwt, AbstractAuthenticationToken> {
  private final UserRepository users;
  private final TransactionTemplate tx;

  public NeonIdentityConverter(UserRepository users, TransactionTemplate tx) {
    this.users = users;
    this.tx = tx;
  }

  /** Rejected identity with a stable code for the 401/403/409 problem response. */
  public static class IdentityRejected extends AuthenticationException {
    public final String code;

    IdentityRejected(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    UUID authUserId;
    try {
      authUserId = UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException e) {
      throw new IdentityRejected("UNAUTHENTICATED", "Token subject is not a Neon Auth user id.");
    }
    User user = users.findByAuthUserId(authUserId).orElseGet(() -> provision(jwt, authUserId));
    if (!user.isActive()) throw new DisabledException("This account is deactivated.");
    return UsernamePasswordAuthenticationToken.authenticated(
        new CurrentUser(user.getId(), user.getRole()),
        jwt,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
  }

  private User provision(Jwt jwt, UUID authUserId) {
    String email = jwt.getClaimAsString("email");
    if (email == null || email.isBlank()) {
      throw new IdentityRejected("UNAUTHENTICATED", "The identity has no email address.");
    }
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    try {
      return tx.execute(
          status -> {
            User existing = users.findByEmailIgnoreCase(normalized).orElse(null);
            if (existing != null) {
              boolean verified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("emailVerified"));
              if (existing.getAuthUserId() != null || !verified) {
                throw new IdentityRejected(
                    "ACCOUNT_LINK_CONFLICT",
                    "An account with this email already exists. Ask an admin to link it.");
              }
              existing.setAuthUserId(authUserId);
              return existing;
            }
            User created = new User(normalized, displayName(jwt, normalized), null);
            created.setAuthUserId(authUserId);
            return users.saveAndFlush(created);
          });
    } catch (DataIntegrityViolationException race) {
      // Another request for the same new identity won the insert.
      return users
          .findByAuthUserId(authUserId)
          .orElseThrow(
              () ->
                  new IdentityRejected(
                      "ACCOUNT_LINK_CONFLICT", "An account with this email already exists."));
    }
  }

  private static String displayName(Jwt jwt, String email) {
    String name = jwt.getClaimAsString("name");
    if (name == null || name.isBlank()) {
      int at = email.indexOf('@');
      name = at > 0 ? email.substring(0, at) : "User";
    }
    name = name.trim();
    return name.length() > 80 ? name.substring(0, 80) : name;
  }
}
