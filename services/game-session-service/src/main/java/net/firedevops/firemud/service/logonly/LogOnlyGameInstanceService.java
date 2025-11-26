package net.firedevops.firemud.service.logonly;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.dto.StartSessionRequest;
import net.firedevops.firemud.service.GameInstanceService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * GameInstanceService implementation for log-only mode that avoids database access.
 */
@Service
@ConditionalOnProperty(name = "game-session.log-only", havingValue = "true")
public class LogOnlyGameInstanceService implements GameInstanceService {
  private static final Logger logger = LoggingUtil.getLogger(LogOnlyGameInstanceService.class);

  @Override
  public GameInstanceDto startSession(StartSessionRequest request) {
    logger.info(
        "Log-only mode enabled; acknowledging start for tenant {} version {} patch {}",
        request.tenantId(),
        request.runtimeVersion(),
        request.scriptPatchVersion());
    return new GameInstanceDto(
        -1L,
        request.tenantId(),
        request.runtimeVersion(),
        request.scriptPatchVersion(),
        request.ownerAccountId(),
        "RUNNING");
  }

  @Override
  public GameInstanceDto stopSession(long sessionId) {
    logger.info("Log-only mode enabled; acknowledging stop for session {}", sessionId);
    return new GameInstanceDto(sessionId, 0L, "log-only", null, 0L, "STOPPED");
  }

  @Override
  public GameInstanceDto restartSession(long sessionId) {
    logger.info("Log-only mode enabled; acknowledging restart for session {}", sessionId);
    return new GameInstanceDto(sessionId, 0L, "log-only", null, 0L, "RUNNING");
  }
}
