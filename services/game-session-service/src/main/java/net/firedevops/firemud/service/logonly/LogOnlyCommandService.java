package net.firedevops.firemud.service.logonly;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.TickService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** CommandService variant that avoids database lookups in log-only mode. */
@Service
@ConditionalOnProperty(name = "game-session.log-only", havingValue = "true")
public class LogOnlyCommandService implements CommandService {
  private static final Logger logger = LoggingUtil.getLogger(LogOnlyCommandService.class);

  private final TickService tickService;

  public LogOnlyCommandService(TickService tickService) {
    this.tickService = tickService;
  }

  @Override
  public CommandEnqueueResult enqueue(String sessionIdText, String command, boolean requiresSoloTick) {
    logger.info(
        "Log-only mode enabled; acknowledging enqueue for session {} command {}",
        sessionIdText,
        command);
    try {
      long sessionId = Long.parseLong(sessionIdText);
      tickService.enqueueCommand(sessionId, command, requiresSoloTick);
    } catch (NumberFormatException ignored) {
      // Fall through; validation is not enforced in log-only mode.
    }
    return CommandEnqueueResult.success();
  }
}
