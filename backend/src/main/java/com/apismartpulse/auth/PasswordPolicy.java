package com.apismartpulse.auth;

import com.apismartpulse.common.ApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * §2.8 password rules: 10–128 chars, a letter and a digit, not the email, not in the bundled list
 * of the 10 000 most common passwords (SecLists, MIT licence).
 */
@Component
public class PasswordPolicy {
  private final Set<String> common;

  public PasswordPolicy() {
    try (BufferedReader r =
        new BufferedReader(
            new InputStreamReader(
                new ClassPathResource("common-passwords.txt").getInputStream(),
                StandardCharsets.UTF_8))) {
      common = r.lines().map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void check(String field, String password, String email) {
    if (password == null || password.length() < 10 || password.length() > 128) {
      throw ApiException.validation(field, "must be 10–128 characters");
    }
    if (!password.chars().anyMatch(Character::isLetter)
        || !password.chars().anyMatch(Character::isDigit)) {
      throw ApiException.validation(field, "must contain at least one letter and one digit");
    }
    if (email != null && password.equalsIgnoreCase(email)) {
      throw ApiException.validation(field, "must not be the same as your email");
    }
    if (common.contains(password.toLowerCase(Locale.ROOT))) {
      throw ApiException.validation(field, "is too common — choose a less predictable password");
    }
  }
}
