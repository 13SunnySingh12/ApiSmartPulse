package com.apismartpulse.user;

import com.apismartpulse.auth.AuthService;
import com.apismartpulse.auth.PasswordPolicy;
import com.apismartpulse.common.ApiException;
import com.apismartpulse.user.UserDtos.AdminUserUpdate;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile (FR-005) and admin user management (FR-004). */
@Service
public class UserService {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final PasswordPolicy policy;
  private final AuthService auth;

  public UserService(
      UserRepository users, PasswordEncoder encoder, PasswordPolicy policy, AuthService auth) {
    this.users = users;
    this.encoder = encoder;
    this.policy = policy;
    this.auth = auth;
  }

  public User get(long id) {
    return users.findById(id).orElseThrow(ApiException::notFound);
  }

  @Transactional
  public User rename(long id, String name) {
    User u = get(id);
    u.setName(name.trim());
    return u;
  }

  @Transactional
  public void changePassword(long id, String current, String next) {
    User u = get(id);
    if (!encoder.matches(current, u.getPasswordHash())) {
      throw ApiException.validation("currentPassword", "is incorrect");
    }
    policy.check("newPassword", next, u.getEmail());
    u.setPasswordHash(encoder.encode(next));
  }

  public List<User> list() {
    return users.findAllByOrderByNameAsc();
  }

  @Transactional
  public User adminUpdate(long actorId, long id, AdminUserUpdate req) {
    User u = get(id);
    boolean demotesAdmin =
        u.getRole() == Role.ADMIN && req.role() != null && req.role() != Role.ADMIN;
    boolean deactivatesAdmin =
        u.getRole() == Role.ADMIN && Boolean.FALSE.equals(req.active()) && u.isActive();
    if ((demotesAdmin || deactivatesAdmin) && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
      throw ApiException.conflict(
          "LAST_ADMIN", "Last admin", "At least one active ADMIN must remain.");
    }
    if (actorId == id && Boolean.FALSE.equals(req.active())) {
      throw ApiException.badRequest("CANNOT_DEACTIVATE_SELF", "You cannot deactivate yourself.");
    }
    boolean changed = false;
    if (req.role() != null && req.role() != u.getRole()) {
      u.setRole(req.role());
      changed = true;
    }
    if (req.active() != null && req.active() != u.isActive()) {
      u.setActive(req.active());
      changed = true;
    }
    if (changed) auth.revokeAllSessions(id); // SEC-005
    return u;
  }
}
