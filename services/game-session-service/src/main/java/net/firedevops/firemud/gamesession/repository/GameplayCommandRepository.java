package net.firedevops.firemud.gamesession.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameplayCommandRepository extends JpaRepository<GameplayCommand, Long> {
  Optional<GameplayCommand> findByCommandId(String commandId);

  List<GameplayCommand> findByCommandIdIn(Collection<String> commandIds);

  List<GameplayCommand> findByExecutionOutcomeAndStagedAtIsNullAndAcceptedAtBefore(
      String executionOutcome, Instant acceptedBefore);
}
