package net.firedevops.firemud.service.logonly;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.v1.TickStatus;
import net.firedevops.firemud.service.TickService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * TickService implementation used when GAME_SESSION_LOG_ONLY=true.
 *
 * <p>Provides in-memory no-op behavior so the application can start without Redis.</p>
 */
@Service
@ConditionalOnProperty(name = "game-session.log-only", havingValue = "true")
public class LogOnlyTickService implements TickService {
  private static final Logger logger = LoggingUtil.getLogger(LogOnlyTickService.class);

  @Override
  public void enqueueCommand(Long sessionId, String command, boolean requiresSoloTick) {
    logger.info(
        "Log-only mode enabled; recording enqueue request for session {} command {}",
        sessionId,
        command);
  }

  @Override
  public void processTick(Long sessionId) {
    logger.info("Log-only mode enabled; skipping tick processing for session {}", sessionId);
  }

  @Override
  public String queryState(Long sessionId) {
    logger.info("Log-only mode enabled; returning empty state for session {}", sessionId);
    return "{}";
  }

  @Override
  public void pauseTicks(String reason) {
    logger.info("Tick pause requested (log-only): {}", reason);
  }

  @Override
  public void resumeTicks(String reason) {
    logger.info("Tick resume requested (log-only): {}", reason);
  }

  @Override
  public TickStatus getTickStatus() {
    return TickStatus.TICK_STATUS_RUNNING;
  }
}
