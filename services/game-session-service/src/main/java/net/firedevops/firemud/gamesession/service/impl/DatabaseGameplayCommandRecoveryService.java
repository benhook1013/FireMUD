package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.service.GameplayCommandRecoveryService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
    prefix = "firemud.database",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring injects the shared repository singleton for this service seam.")
public class DatabaseGameplayCommandRecoveryService implements GameplayCommandRecoveryService {
  private static final Logger logger =
      LoggingUtil.getLogger(DatabaseGameplayCommandRecoveryService.class);

  private final GameplayCommandRepository gameplayCommandRepository;

  public DatabaseGameplayCommandRecoveryService(
      GameplayCommandRepository gameplayCommandRepository) {
    this.gameplayCommandRepository = gameplayCommandRepository;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void convergeAcceptedButUnstagedCommandsOnStartup() {
    int recovered = convergeAcceptedButUnstagedCommands(Instant.now());
    if (recovered > 0) {
      logger.warn(
          "Converged {} accepted gameplay command(s) that were never staged before startup",
          recovered);
    }
  }

  @Override
  @Transactional
  public int convergeAcceptedButUnstagedCommands(Instant acceptedBefore) {
    var commands =
        gameplayCommandRepository.findByExecutionOutcomeAndStagedAtIsNullAndAcceptedAtBefore(
            "ACCEPTED", acceptedBefore);
    Instant now = Instant.now();
    for (GameplayCommand command : commands) {
      command.setExecutionOutcome("LOST_BEFORE_STAGING");
      command.setGameplayResult("NOT_APPLIED");
      command.setCompletedAt(now);
      command.setLastAttemptAt(now);
      command.setFailureCode("LOST_BEFORE_STAGING");
      command.setFailureMessage("Command was accepted durably but not staged before recovery");
    }
    gameplayCommandRepository.saveAll(commands);
    return commands.size();
  }
}
