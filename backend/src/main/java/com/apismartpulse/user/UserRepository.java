package com.apismartpulse.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {
  @Query("select u from User u where lower(u.email) = lower(?1)")
  Optional<User> findByEmailIgnoreCase(String email);

  List<User> findAllByOrderByNameAsc();

  long countByRoleAndActiveTrue(Role role);
}
