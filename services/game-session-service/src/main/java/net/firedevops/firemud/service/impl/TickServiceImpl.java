package net.firedevops.firemud.service.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.service.TickService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

/** Default Redis-backed implementation of {@link TickService}. */
@Service
@RequiredArgsConstructor
public class TickServiceImpl implements TickService {
  private static final Logger logger = LoggingUtil.getLogger(TickServiceImpl.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;

  @Value("${game.tick-duration-ms:1000}")
  private long tickDurationMs;

  private Counter enqueueCounter;
  private Counter redisErrorCounter;
  private Timer tickTimer;
  private RedisScript<Long> stageScript;
  private RedisScript<Long> commitScript;
  private RedisScript<Long> rollbackScript;

  @PostConstruct
  void init() {
    this.enqueueCounter = meterRegistry.counter("game_session_commands_enqueued_total");
    this.redisErrorCounter = meterRegistry.counter("game_session_redis_errors_total");
    this.tickTimer = meterRegistry.timer("game_session_tick_duration_ms");
    ResourceScriptSource commitSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_commit.lua"));
    ResourceScriptSource stageSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_stage.lua"));
    ResourceScriptSource rollbackSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_rollback.lua"));
    this.stageScript = RedisScript.of(stageSrc.getResource(), Long.class);
    this.commitScript = RedisScript.of(commitSrc.getResource(), Long.class);
    this.rollbackScript = RedisScript.of(rollbackSrc.getResource(), Long.class);
  }

  @Override
  public void enqueueCommand(Long sessionId, String command) {
    redisTemplate.opsForList().rightPush(queueKey(sessionId), command);
    enqueueCounter.increment();
    logger.debug("Queued command for {}", sessionId);
  }

  @Override
  public void processTick(Long sessionId) {
    String lockKey = lockKey(sessionId);
    Boolean acquired =
        redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMillis(tickDurationMs));
    if (Boolean.FALSE.equals(acquired)) {
      logger.debug("Could not acquire tick lock {}", lockKey);
      return;
    }
    try {
      Long pending = redisTemplate.opsForList().size(pendingKey(sessionId));
      if (pending != null && pending > 0) {
        logger.info("Replaying {} pending commands for {}", pending, sessionId);
        tickTimer.record(() -> redisTemplate.execute(commitScript, List.of(pendingKey(sessionId))));
      }
      tickTimer.record(
          () ->
              redisTemplate.execute(
                  stageScript, List.of(queueKey(sessionId), pendingKey(sessionId))));
      tickTimer.record(() -> redisTemplate.execute(commitScript, List.of(pendingKey(sessionId))));
    } catch (Exception ex) {
      redisErrorCounter.increment();
      logger.error("Tick processing failed, rolling back", ex);
      redisTemplate.execute(rollbackScript, List.of(pendingKey(sessionId), queueKey(sessionId)));
    } finally {
      redisTemplate.delete(lockKey);
    }
  }

  @Override
  public String queryState(Long sessionId) {
    Object state = redisTemplate.opsForValue().get(stateKey(sessionId));
    return state != null ? state.toString() : "{}";
  }

  private String queueKey(Long sessionId) {
    return "tick:queue:" + sessionId;
  }

  private String lockKey(Long sessionId) {
    return "tick:lock:" + sessionId;
  }

  private String stateKey(Long sessionId) {
    return "session:" + sessionId;
  }

  private String pendingKey(Long sessionId) {
    return "tick:pending:" + sessionId;
  }
}
