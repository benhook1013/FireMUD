package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueWorkItemPointer;
import net.firedevops.firemud.automationscripting.service.redis.AutomationRedisKeys;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Redis-backed implementation of {@link AutomationQueueService}. */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Service dependency is not exposed")
public class AutomationQueueServiceImpl implements AutomationQueueService {
  private static final int POINTER_SCHEMA_VERSION = 1;
  private static final Collection<String> INDEXABLE_STATUSES =
      List.of("PENDING_EVALUATION", "EVALUATING");

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
  private final ScriptWorkItemRepository workItemRepository;

  private ListOperations<String, Object> listOps;
  private Counter enqueueCounter;
  private Counter drainCounter;
  private Counter rebuildCounter;

  @PostConstruct
  void init() {
    this.listOps = redisTemplate.opsForList();
    this.enqueueCounter = meterRegistry.counter("automation_queue_enqueued_total");
    this.drainCounter = meterRegistry.counter("automation_queue_drained_total");
    this.rebuildCounter = meterRegistry.counter("automation_queue_rebuilt_total");
  }

  @Override
  @Timed(value = "automation.queue.enqueue")
  public void enqueueWorkItem(ScriptWorkItem workItem) {
    AutomationQueueWorkItemPointer pointer =
        new AutomationQueueWorkItemPointer(
            POINTER_SCHEMA_VERSION,
            workItem.getId(),
            workItem.getGameInstanceId(),
            workItem.getScriptPatchVersion(),
            workItem.getScriptEventId());
    listOps.rightPush(
        AutomationRedisKeys.automationQueue(
            workItem.getTenantId(), workItem.getGameInstanceId(), workItem.getEntityId()),
        serialize(pointer));
    enqueueCounter.increment();
  }

  @Override
  @Timed(value = "automation.queue.drain")
  public List<AutomationQueueWorkItemPointer> drainWorkItems(
      String tenantId, String gameInstanceId, String entityId) {
    String key = AutomationRedisKeys.automationQueue(tenantId, gameInstanceId, entityId);
    List<Object> raw = listOps.range(key, 0, -1);
    redisTemplate.delete(key);
    if (raw == null) {
      return Collections.emptyList();
    }
    LinkedHashMap<Long, AutomationQueueWorkItemPointer> deduped = new LinkedHashMap<>();
    raw.stream()
        .map(Object::toString)
        .map(this::deserialize)
        .forEach(pointer -> deduped.putIfAbsent(pointer.outboxWorkItemId(), pointer));
    drainCounter.increment(deduped.size());
    return List.copyOf(deduped.values());
  }

  @Override
  @Timed(value = "automation.queue.rebuild")
  public int rebuildPendingWorkItemIndex(int maxItems) {
    if (maxItems <= 0) {
      throw new IllegalArgumentException("max_items must be positive");
    }
    List<ScriptWorkItem> candidates =
        workItemRepository.findByStatusInOrderByCreatedAtAscIdAsc(
            INDEXABLE_STATUSES, PageRequest.of(0, maxItems));
    int published = 0;
    for (ScriptWorkItem workItem : candidates) {
      if (ensurePointerIndexed(workItem)) {
        published++;
      }
    }
    if (published > 0) {
      rebuildCounter.increment(published);
    }
    return published;
  }

  private String serialize(AutomationQueueWorkItemPointer pointer) {
    try {
      return objectMapper.writeValueAsString(pointer);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize automation queue pointer", ex);
    }
  }

  private AutomationQueueWorkItemPointer deserialize(String payload) {
    try {
      return objectMapper.readValue(payload, AutomationQueueWorkItemPointer.class);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to deserialize automation queue pointer", ex);
    }
  }

  private boolean ensurePointerIndexed(ScriptWorkItem workItem) {
    String queueKey =
        AutomationRedisKeys.automationQueue(
            workItem.getTenantId(), workItem.getGameInstanceId(), workItem.getEntityId());
    List<Object> raw = listOps.range(queueKey, 0, -1);
    if (raw != null
        && raw.stream()
            .map(Object::toString)
            .map(this::deserialize)
            .anyMatch(pointer -> pointer.outboxWorkItemId() == workItem.getId())) {
      return false;
    }
    listOps.rightPush(queueKey, serialize(pointerFor(workItem)));
    return true;
  }

  private static AutomationQueueWorkItemPointer pointerFor(ScriptWorkItem workItem) {
    return new AutomationQueueWorkItemPointer(
        POINTER_SCHEMA_VERSION,
        workItem.getId(),
        workItem.getGameInstanceId(),
        workItem.getScriptPatchVersion(),
        workItem.getScriptEventId());
  }
}
