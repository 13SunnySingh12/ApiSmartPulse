package com.apismartpulse.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
  Optional<Project> findByKey(String key);

  boolean existsByKey(String key);
}
