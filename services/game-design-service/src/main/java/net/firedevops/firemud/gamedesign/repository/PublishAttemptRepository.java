package net.firedevops.firemud.gamedesign.repository;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublishAttemptRepository extends JpaRepository<PublishAttempt, Long> {
  Optional<PublishAttempt> findByPublishWorkflowId(String publishWorkflowId);
}
