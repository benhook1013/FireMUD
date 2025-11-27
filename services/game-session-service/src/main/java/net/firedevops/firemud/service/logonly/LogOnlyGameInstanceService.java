package net.firedevops.firemud.service.logonly;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.dto.StartSessionRequest;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.service.GameInstanceService;
import net.firedevops.firemud.service.logonly.LogOnlyGameInstanceRegistry;
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

  private final LogOnlyGameInstanceRegistry registry;

  public LogOnlyGameInstanceService(LogOnlyGameInstanceRegistry registry) {
    this.registry = registry;
  }

  @Override
  public GameInstanceDto startSession(StartSessionRequest request) {
    long sessionId = registry.nextSessionId();
    GameInstance instance = new GameInstance();
    instance.setId(sessionId);
    instance.setTenantId(request.tenantId());
    instance.setRuntimeVersion(request.runtimeVersion());
    instance.setScriptPatchVersion(request.scriptPatchVersion());
    instance.setOwnerAccountId(request.ownerAccountId());
    instance.setStatus("RUNNING");
    registry.register(instance);
    logger.info(
        "Log-only mode enabled; acknowledging start for tenant {} version {} patch {}; session {}",
        request.tenantId(),
        request.runtimeVersion(),
        request.scriptPatchVersion(),
        sessionId);
    return new GameInstanceDto(
        sessionId,
        request.tenantId(),
        request.runtimeVersion(),
        request.scriptPatchVersion(),
        request.ownerAccountId(),
        instance.getStatus());
  }

  @Override
  public GameInstanceDto stopSession(long sessionId) {
    logger.info("Log-only mode enabled; acknowledging stop for session {}", sessionId);
    registry.updateStatus(sessionId, "STOPPED");
    registry.remove(sessionId);
    return new GameInstanceDto(sessionId, 0L, "log-only", null, 0L, "STOPPED");
  }

  @Override
  public GameInstanceDto restartSession(long sessionId) {
    logger.info("Log-only mode enabled; acknowledging restart for session {}", sessionId);
    registry.updateStatus(sessionId, "RUNNING");
    return new GameInstanceDto(sessionId, 0L, "log-only", null, 0L, "RUNNING");
  }
}
