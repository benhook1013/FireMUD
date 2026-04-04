package net.firedevops.firemud.gamesession.service.devisolated;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.logging.GameSessionCommandLogSanitizer;
import net.firedevops.firemud.gamesession.service.CommandService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** CommandService variant that avoids database lookups in dev-isolated mode. */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public class DevIsolatedCommandService implements CommandService {
  private static final Logger logger = LoggingUtil.getLogger(DevIsolatedCommandService.class);

  @Override
  public CommandEnqueueResult enqueue(
      String sessionIdText, String command, boolean requiresSoloTick) {
    logger.info(
        "Dev-isolated mode enabled; acknowledging enqueue for session {} command {}",
        sessionIdText,
        GameSessionCommandLogSanitizer.sanitize(command));
    return CommandEnqueueResult.success();
  }
}
