package net.firedevops.firemud.service.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.service.tick.ScriptTickService;
import net.firedevops.firemud.service.quota.ScriptQuotaService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link ScriptTickService}. */
@Service
@RequiredArgsConstructor
public class ScriptTickServiceImpl implements ScriptTickService {
  private static final Logger logger = LoggingUtil.getLogger(ScriptTickServiceImpl.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final net.firedevops.firemud.service.quota.ScriptQuotaService quotaService;

  @Value("${automation.tick-duration-ms:1000}")
  private long tickDurationMs;

  private Counter enqueueCounter;
  private Counter redisErrorCounter;
  private Timer tickTimer;
  private RedisScript<Long> stageScript;
  private RedisScript<Long> commitScript;
  private RedisScript<Long> rollbackScript;

  @PostConstruct
  void init() {
    enqueueCounter = meterRegistry.counter("automation_tick_events_enqueued_total");
    redisErrorCounter = meterRegistry.counter("automation_tick_redis_errors_total");
    tickTimer = meterRegistry.timer("automation_tick_duration_ms");
    ResourceScriptSource commitSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_commit.lua"));
    ResourceScriptSource stageSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_stage.lua"));
    ResourceScriptSource rollbackSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_rollback.lua"));
    stageScript = RedisScript.of(stageSrc.getResource(), Long.class);
    commitScript = RedisScript.of(commitSrc.getResource(), Long.class);
    rollbackScript = RedisScript.of(rollbackSrc.getResource(), Long.class);
  }

  @Override
  public void enqueueEvent(Long tenantId, Long scriptId, String eventJson) {
    if (!quotaService.tryAcquire(tenantId, scriptId)) {
      logger.warn("Script quota exceeded for {}:{}", tenantId, scriptId);
      return;
    }
    redisTemplate.opsForList().rightPush(queueKey(tenantId, scriptId), eventJson);
    enqueueCounter.increment();
    logger.debug("Queued script event for {}:{}", tenantId, scriptId);
  }

  @Override
  public void processTick(Long tenantId, Long scriptId) {
    String lockKey = lockKey(tenantId, scriptId);
    Boolean acquired =
        redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMillis(tickDurationMs));
    if (Boolean.FALSE.equals(acquired)) {
      logger.debug("Could not acquire tick lock {}", lockKey);
      return;
    }
    try {
      Long pending = redisTemplate.opsForList().size(pendingKey(tenantId, scriptId));
      if (pending != null && pending > 0) {
        logger.info("Replaying {} pending events for {}:{}", pending, tenantId, scriptId);
        tickTimer.record(
            () -> redisTemplate.execute(commitScript, List.of(pendingKey(tenantId, scriptId))));
      }
      tickTimer.record(
          () ->
              redisTemplate.execute(
                  stageScript,
                  List.of(queueKey(tenantId, scriptId), pendingKey(tenantId, scriptId))));
      tickTimer.record(
          () -> redisTemplate.execute(commitScript, List.of(pendingKey(tenantId, scriptId))));
    } catch (Exception ex) {
      redisErrorCounter.increment();
      logger.error("Script tick failed, rolling back", ex);
      redisTemplate.execute(
          rollbackScript, List.of(pendingKey(tenantId, scriptId), queueKey(tenantId, scriptId)));
    } finally {
      redisTemplate.delete(lockKey);
    }
  }

  private String queueKey(Long tenantId, Long scriptId) {
    return "tick:queue:" + tenantId + ":" + scriptId;
  }

  private String lockKey(Long tenantId, Long scriptId) {
    return "tick:lock:" + tenantId + ":" + scriptId;
  }

  private String pendingKey(Long tenantId, Long scriptId) {
    return "tick:pending:" + tenantId + ":" + scriptId;
  }
}
