package net.firedevops.firemud.service.logonly;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.FeatureFlagDto;
import net.firedevops.firemud.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.service.FeatureFlagService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Feature flag stub used when the service runs without database connectivity. */
@Service
@ConditionalOnProperty(name = "game-session.log-only", havingValue = "true")
public class LogOnlyFeatureFlagService implements FeatureFlagService {
  private static final Logger logger = LoggingUtil.getLogger(LogOnlyFeatureFlagService.class);

  @Override
  public FeatureFlagDto toggleFlag(ToggleFeatureFlagRequest request) {
    logger.info(
        "Log-only mode enabled; acknowledging feature toggle {} for tenant {}",
        request.name(),
        request.tenantId());
    return new FeatureFlagDto(null, request.tenantId(), request.name(), request.enabled());
  }
}
