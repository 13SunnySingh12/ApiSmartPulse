package com.apismartpulse.user;

import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.user.UserDtos.AdminUserUpdate;
import com.apismartpulse.user.UserDtos.AdminUserView;
import com.apismartpulse.user.UserDtos.PasswordChange;
import com.apismartpulse.user.UserDtos.ProfileUpdate;
import com.apismartpulse.user.UserDtos.UserView;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearer")
public class UserController {
  private final UserService service;

  public UserController(UserService service) {
    this.service = service;
  }

  @Tag(name = "Profile")
  @GetMapping("/me")
  public UserView me() {
    return UserView.of(service.get(CurrentUser.get().id()));
  }

  @Tag(name = "Profile")
  @PatchMapping("/me")
  public UserView updateMe(@Valid @RequestBody ProfileUpdate req) {
    return UserView.of(service.rename(CurrentUser.get().id(), req.name()));
  }

  @Tag(name = "Profile")
  @PostMapping("/me/password")
  public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChange req) {
    service.changePassword(CurrentUser.get().id(), req.currentPassword(), req.newPassword());
    return ResponseEntity.noContent().build();
  }

  @Tag(name = "Admin")
  @GetMapping("/admin/users")
  @PreAuthorize("hasRole('ADMIN')")
  public List<AdminUserView> users() {
    return service.list().stream().map(AdminUserView::of).toList();
  }

  @Tag(name = "Admin")
  @PatchMapping("/admin/users/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public AdminUserView updateUser(@PathVariable long id, @RequestBody AdminUserUpdate req) {
    return AdminUserView.of(service.adminUpdate(CurrentUser.get().id(), id, req));
  }
}
