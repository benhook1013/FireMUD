package net.firedevops.firemud.worldmanagement.repository;

import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.worldmanagement.entity.WorldEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface WorldEventRepository extends JpaRepository<WorldEvent, Long> {
  List<WorldEvent> findByProcessedFalseAndExecuteAtBefore(LocalDateTime time);

  @Query(
      "select e from WorldEvent e where e.processed = false and e.executeAt <= :time"
          + " and (e.region is null or e.region.shardId = :shardId)")
  List<WorldEvent> findDueEventsForShard(LocalDateTime time, Integer shardId);
}
