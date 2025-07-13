package net.firedevops.firemud.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.service.SessionStateService;
import org.slf4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import io.micrometer.core.annotation.Timed;

/** Default Redis-backed implementation of {@link SessionStateService}. */
@Service
@RequiredArgsConstructor
public class SessionStateServiceImpl implements SessionStateService {
  private static final Logger logger = LoggingUtil.getLogger(SessionStateServiceImpl.class);

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
