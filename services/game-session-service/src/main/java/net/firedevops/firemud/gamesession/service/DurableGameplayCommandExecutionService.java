package net.firedevops.firemud.gamesession.service;

import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.TickEffect;

public interface DurableGameplayCommandExecutionService {
  Optional<DurableGameplayCommandExecutionResult> execute(
      TickEffect effect, GameplayCommand command);

  record DurableGameplayCommandExecutionResult(
      String effectStatus,
      String commandExecutionOutcome,
      String gameplayResult,
      String failureCode,
      String failureMessage) {}
}
