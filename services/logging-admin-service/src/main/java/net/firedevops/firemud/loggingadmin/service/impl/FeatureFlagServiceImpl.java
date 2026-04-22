package net.firedevops.firemud.loggingadmin.service.impl;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import net.firedevops.firemud.loggingadmin.dto.FeatureFlagDto;
import net.firedevops.firemud.loggingadmin.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.loggingadmin.service.FeatureFlagService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class FeatureFlagServiceImpl implements FeatureFlagService {
  private static final Logger logger = LoggingUtil.getLogger(FeatureFlagServiceImpl.class);

  private final GameSessionClient gameSessionClient;

  public FeatureFlagServiceImpl(GameSessionClient gameSessionClient) {
    this.gameSessionClient = gameSessionClient;
  }

  @Override
  @Timed(value = "loggingadmin.feature.toggle")
  public FeatureFlagDto toggleFlag(ToggleFeatureFlagRequest request) {
    logger.info(
        "Forwarding feature flag {} toggle for tenant {} to game-session",
        request.name(),
        request.tenantId());
    ToggleFeatureFlagResponse response =
        gameSessionClient.toggleFeatureFlag(request.tenantId(), request.name(), request.enabled());
    if (!response.getSuccess()) {
      String message =
          response.hasError()
              ? response.getError().getCode() + ": " + response.getError().getMessage()
              : "Game Session rejected feature flag toggle";
      throw new IllegalStateException(message);
    }
    return new FeatureFlagDto(null, request.tenantId(), request.name(), request.enabled());
  }
}
