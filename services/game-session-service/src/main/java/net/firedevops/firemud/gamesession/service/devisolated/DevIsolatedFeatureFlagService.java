package net.firedevops.firemud.gamesession.service.devisolated;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.dto.FeatureFlagDto;
import net.firedevops.firemud.gamesession.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.gamesession.service.FeatureFlagService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Feature flag stub used when the service runs without database connectivity. */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public class DevIsolatedFeatureFlagService implements FeatureFlagService {
  private static final Logger logger = LoggingUtil.getLogger(DevIsolatedFeatureFlagService.class);

  @Override
  public FeatureFlagDto toggleFlag(ToggleFeatureFlagRequest request) {
    logger.info(
        "Dev-isolated mode enabled; acknowledging feature toggle {} for tenant {}",
        request.name(),
        request.tenantId());
    return new FeatureFlagDto(null, request.tenantId(), request.name(), request.enabled());
  }
}
