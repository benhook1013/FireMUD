package net.firedevops.firemud.service.devisolated;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.TickService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** CommandService variant that avoids database lookups in dev-isolated mode. */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public class DevIsolatedCommandService implements CommandService {
  private static final Logger logger = LoggingUtil.getLogger(DevIsolatedCommandService.class);

  private final TickService tickService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "TickService is an injected internal collaborator")
  public DevIsolatedCommandService(TickService tickService) {
    this.tickService = tickService;
  }

  @Override
  public CommandEnqueueResult enqueue(
      String sessionIdText, String command, boolean requiresSoloTick) {
    logger.info(
        "Dev-isolated mode enabled; acknowledging enqueue for session {} command {}",
        sessionIdText,
        command);
    try {
      long sessionId = Long.parseLong(sessionIdText);
      tickService.enqueueCommand(sessionId, command, requiresSoloTick);
    } catch (NumberFormatException ignored) {
      // Fall through; validation is not enforced in dev-isolated mode.
    }
    return CommandEnqueueResult.success();
  }
}
