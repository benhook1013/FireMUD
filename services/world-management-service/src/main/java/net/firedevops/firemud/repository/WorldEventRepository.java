package net.firedevops.firemud.repository;

import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.entity.WorldEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorldEventRepository extends JpaRepository<WorldEvent, Long> {
  List<WorldEvent> findByProcessedFalseAndExecuteAtBefore(LocalDateTime time);
}
