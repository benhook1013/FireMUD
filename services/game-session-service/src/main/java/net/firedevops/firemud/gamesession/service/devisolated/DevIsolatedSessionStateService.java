package net.firedevops.firemud.gamesession.service.devisolated;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** In-memory placeholder that avoids Redis writes in dev-isolated mode. */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public class DevIsolatedSessionStateService implements SessionStateService {
  private static final Logger logger = LoggingUtil.getLogger(DevIsolatedSessionStateService.class);

  @Override
  public void saveState(GameInstanceDto dto) {
    logger.info("Dev-isolated mode enabled; skipping session state save for {}", dto.id());
  }

  @Override
  public void deleteState(Long tenantId, Long sessionId) {
    logger.info(
        "Dev-isolated mode enabled; skipping session state delete for {}:{}", tenantId, sessionId);
  }
}
