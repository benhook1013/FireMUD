package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.redis.AutomationRedisKeys;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link AutomationQueueService}. */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Service dependency is not exposed")
public class AutomationQueueServiceImpl implements AutomationQueueService {
  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;

  private ListOperations<String, Object> listOps;
  private Counter enqueueCounter;
  private Counter drainCounter;

  @PostConstruct
  void init() {
    this.listOps = redisTemplate.opsForList();
    this.enqueueCounter = meterRegistry.counter("automation_queue_enqueued_total");
    this.drainCounter = meterRegistry.counter("automation_queue_drained_total");
  }

  @Override
  @Timed(value = "automation.queue.enqueue")
  public void enqueueEvent(
      String tenantId, String gameInstanceId, String entityId, String eventJson) {
    listOps.rightPush(
        AutomationRedisKeys.automationQueue(tenantId, gameInstanceId, entityId), eventJson);
    enqueueCounter.increment();
  }

  @Override
  @Timed(value = "automation.queue.drain")
  public List<String> drainEvents(String tenantId, String gameInstanceId, String entityId) {
    String key = AutomationRedisKeys.automationQueue(tenantId, gameInstanceId, entityId);
    List<Object> raw = listOps.range(key, 0, -1);
    redisTemplate.delete(key);
    if (raw == null) {
      return Collections.emptyList();
    }
    drainCounter.increment(raw.size());
    return raw.stream().map(Object::toString).toList();
  }
}
