package net.firedevops.firemud.gamesession.service.devisolated;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.v1.TickStatus;
import net.firedevops.firemud.gamesession.service.TickService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * TickService implementation used when GAME_SESSION_DEV_ISOLATED=true.
 *
 * <p>Provides in-memory no-op behavior so the application can start without Redis.
 */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public class DevIsolatedTickService implements TickService {
  private static final Logger logger = LoggingUtil.getLogger(DevIsolatedTickService.class);

  @Override
  public void enqueueCommand(Long sessionId, String command, boolean requiresSoloTick) {
    logger.info(
        "Dev-isolated mode enabled; recording enqueue request for session {} command {}",
        sessionId,
        command);
  }

  @Override
  public void processTick(Long sessionId) {
    logger.info("Dev-isolated mode enabled; skipping tick processing for session {}", sessionId);
  }

  @Override
  public String queryState(Long sessionId) {
    logger.info("Dev-isolated mode enabled; returning empty state for session {}", sessionId);
    return "{}";
  }

  @Override
  public void pauseTicks(String reason) {
    logger.info("Tick pause requested (dev-isolated): {}", reason);
  }

  @Override
  public void resumeTicks(String reason) {
    logger.info("Tick resume requested (dev-isolated): {}", reason);
  }

  @Override
  public void pauseTicksForGameInstance(Long gameInstanceId, String reason) {
    logger.info(
        "Tick pause requested for game instance {} (dev-isolated): {}", gameInstanceId, reason);
  }

  @Override
  public void resumeTicksForGameInstance(Long gameInstanceId, String reason) {
    logger.info(
        "Tick resume requested for game instance {} (dev-isolated): {}", gameInstanceId, reason);
  }

  @Override
  public TickStatus getTickStatus() {
    return TickStatus.TICK_STATUS_RUNNING;
  }
}
