package com.apismartpulse.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class UserDtos {
  private UserDtos() {}

  public record UserView(long id, String name, String email, Role role) {
    public static UserView of(User u) {
      return new UserView(u.getId(), u.getName(), u.getEmail(), u.getRole());
    }
  }

  public record AdminUserView(
      long id,
      String name,
      String email,
      Role role,
      boolean active,
      Instant lastLoginAt,
      Instant createdAt) {
    public static AdminUserView of(User u) {
      return new AdminUserView(
          u.getId(),
          u.getName(),
          u.getEmail(),
          u.getRole(),
          u.isActive(),
          u.getLastLoginAt(),
          u.getCreatedAt());
    }
  }

  /** Name is the only self-editable profile field; unknown fields such as role are rejected. */
  public record ProfileUpdate(
      @NotBlank
          @Size(max = 80)
          @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
          String name) {}

  public record PasswordChange(
      @NotBlank String currentPassword, @NotBlank @Size(max = 128) String newPassword) {}

  public record AdminUserUpdate(Role role, Boolean active) {}
}
