package net.firedevops.firemud.service.logonly;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.service.SessionStateService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** In-memory placeholder that avoids Redis writes in log-only mode. */
@Service
@ConditionalOnProperty(name = "game-session.log-only", havingValue = "true")
public class LogOnlySessionStateService implements SessionStateService {
  private static final Logger logger = LoggingUtil.getLogger(LogOnlySessionStateService.class);

  @Override
  public void saveState(GameInstanceDto dto) {
    logger.info("Log-only mode enabled; skipping session state save for {}", dto.id());
  }

  @Override
  public void deleteState(Long tenantId, Long sessionId) {
    logger.info("Log-only mode enabled; skipping session state delete for {}:{}", tenantId, sessionId);
  }
}
