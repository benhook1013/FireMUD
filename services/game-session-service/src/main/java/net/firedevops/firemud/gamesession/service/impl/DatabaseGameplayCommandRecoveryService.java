package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.service.GameplayCommandRecoveryService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

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
  private final TickQueueControlService tickQueueControlService;

  @Autowired
  public DatabaseGameplayCommandRecoveryService(
      GameplayCommandRepository gameplayCommandRepository,
      TickQueueControlService tickQueueControlService) {
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.tickQueueControlService = tickQueueControlService;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void convergeAcceptedButUnstagedCommandsOnStartup() {
    int recovered = convergeAcceptedButUnstagedCommands(Instant.now());
    if (recovered > 0) {
      logger.warn(
          "Re-drove {} accepted gameplay command(s) that were not staged before startup",
          recovered);
    }
  }

  @Override
  public int convergeAcceptedButUnstagedCommands(Instant acceptedBefore) {
    var commands =
        gameplayCommandRepository.findByExecutionOutcomeAndStagedAtIsNullAndAcceptedAtBefore(
            "ACCEPTED", acceptedBefore);
    int recovered = 0;
    for (GameplayCommand command : commands) {
      try {
        // TickQueueControlService acquires the queue/tick mutation fences before asking the
        // repository to lock and re-check this exact ACCEPTED row. A concurrent staging or
        // terminal transition therefore wins before Redis is touched, or observes the staged
        // result after this attempt commits.
        tickQueueControlService.enqueueCommand(
            command.getTenantId(),
            command.getGameInstanceId(),
            command.getCommandId(),
            command.getCommandText(),
            command.isRequiresSoloTick());
        recovered++;
      } catch (RuntimeException ex) {
        // Redis loss, an identity/payload conflict, or a raced terminal transition is not proof
        // that this durable command was never materialized. Leave ACCEPTED untouched so the same
        // command identity can be retried on the next startup/recovery pass.
        logger.warn(
            "Unable to re-drive accepted gameplay command commandId={} tenantId={} gameInstanceId={}; leaving it retryable",
            command.getCommandId(),
            command.getTenantId(),
            command.getGameInstanceId(),
            ex);
      }
    }
    return recovered;
  }
}
