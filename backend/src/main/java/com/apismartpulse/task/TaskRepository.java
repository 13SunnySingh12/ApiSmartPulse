package com.apismartpulse.task;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {
  Optional<Task> findByProjectIdAndNumber(long projectId, int number);
}
