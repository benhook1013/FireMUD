package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/** Default Redis-backed implementation of {@link SessionStateService}. */
@Service
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
@RequiredArgsConstructor
public final class SessionStateServiceImpl implements SessionStateService {
  private static final Logger logger = LoggingUtil.getLogger(SessionStateServiceImpl.class);

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and not exposed")
  private final RedisTemplate<String, Object> redisTemplate;

  @Override
  @Timed(value = "gamesession.state.save")
  public void saveState(GameInstanceDto dto) {
    String key = key(dto.tenantId(), dto.id());
    redisTemplate.opsForValue().set(key, dto);
    logger.debug("Saved session state {}", key);
  }

  @Override
  @Timed(value = "gamesession.state.delete")
  public void deleteState(Long tenantId, Long sessionId) {
    String key = key(tenantId, sessionId);
    redisTemplate.delete(key);
    logger.debug("Deleted session state {}", key);
  }

  private String key(Long tenantId, Long sessionId) {
    return "session:" + tenantId + ":" + sessionId;
  }
}
