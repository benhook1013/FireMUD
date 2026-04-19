package net.firedevops.firemud.gamesession.service.devisolated;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** GameInstanceService implementation for dev-isolated mode that avoids database access. */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public class DevIsolatedGameInstanceService implements GameInstanceService {
  private static final Logger logger = LoggingUtil.getLogger(DevIsolatedGameInstanceService.class);

  private final DevIsolatedGameInstanceRegistry registry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Registry is an injected internal collaborator")
  public DevIsolatedGameInstanceService(DevIsolatedGameInstanceRegistry registry) {
    this.registry = registry;
  }

  @Override
  public GameInstanceDto startSession(StartSessionRequest request, boolean replaceExistingFirst) {
    long sessionId = registry.nextSessionId();
    GameInstance instance = new GameInstance();
    instance.setId(sessionId);
    instance.setTenantId(request.tenantId());
    instance.setRuntimeVersion("launch:" + request.gameTemplateId());
    instance.setGameTemplateId(request.gameTemplateId());
    instance.setOwnerAccountId(request.ownerAccountId());
    instance.setStatus("RUNNING");
    registry.register(instance);
    logger.info(
        "Dev-isolated mode enabled; acknowledging start for tenant {} template {} controlPlaneRequestId {}; session {}",
        request.tenantId(),
        request.gameTemplateId(),
        request.controlPlaneRequestId(),
        sessionId);
    return new GameInstanceDto(
        sessionId,
        request.tenantId(),
        instance.getRuntimeVersion(),
        instance.getScriptPatchVersion(),
        instance.getGameTemplateId(),
        instance.getLaunchDescriptorId(),
        instance.getVersionId(),
        instance.getReleaseBundleId(),
        instance.getVersionStateEpoch(),
        instance.getGenerationConfigRevision(),
        request.ownerAccountId(),
        instance.getStatus());
  }

  @Override
  public GameInstanceDto stopSession(long sessionId) {
    logger.info("Dev-isolated mode enabled; acknowledging stop for session {}", sessionId);
    registry.updateStatus(sessionId, "STOPPED");
    registry.remove(sessionId);
    return new GameInstanceDto(
        sessionId, 0L, "dev-isolated", null, null, null, null, null, null, null, 0L, "STOPPED");
  }

  @Override
  public GameInstanceDto restartSession(long sessionId) {
    logger.info("Dev-isolated mode enabled; acknowledging restart for session {}", sessionId);
    registry.updateStatus(sessionId, "RUNNING");
    return new GameInstanceDto(
        sessionId, 0L, "dev-isolated", null, null, null, null, null, null, null, 0L, "RUNNING");
  }
}
