package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
  private Counter inspectionCounter;
  private AtomicLong orphanedEntriesGauge = new AtomicLong();
  private AtomicLong oldestEntryAgeSecondsGauge = new AtomicLong();

  @PostConstruct
  void init() {
    this.listOps = redisTemplate.opsForList();
    this.enqueueCounter = meterRegistry.counter("automation_queue_enqueued_total");
    this.drainCounter = meterRegistry.counter("automation_queue_drained_total");
    this.rebuildCounter = meterRegistry.counter("automation_queue_rebuilt_total");
    this.inspectionCounter = meterRegistry.counter("automation_queue_health_inspections_total");
    Gauge.builder("automation_queue_orphaned_entries_total", orphanedEntriesGauge, AtomicLong::get)
        .register(meterRegistry);
    Gauge.builder(
            "automation_queue_oldest_entry_age_seconds",
            oldestEntryAgeSecondsGauge,
            AtomicLong::get)
        .register(meterRegistry);
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
  @Timed(value = "automation.queue.drain_indexed")
  public List<AutomationQueueWorkItemPointer> drainIndexedWorkItemPointers(
      int maxQueues, int maxPointers) {
    if (maxQueues <= 0) {
      throw new IllegalArgumentException("max_queues must be positive");
    }
    if (maxPointers <= 0) {
      throw new IllegalArgumentException("max_pointers must be positive");
    }
    Set<String> queueKeys = redisTemplate.keys("automation:queue:*");
    if (queueKeys == null || queueKeys.isEmpty()) {
      return List.of();
    }
    LinkedHashMap<Long, AutomationQueueWorkItemPointer> deduped = new LinkedHashMap<>();
    for (String queueKey : queueKeys.stream().sorted().limit(maxQueues).toList()) {
      List<Object> raw = listOps.range(queueKey, 0, -1);
      redisTemplate.delete(queueKey);
      if (raw == null) {
        continue;
      }
      for (Object entry : raw) {
        AutomationQueueWorkItemPointer pointer = deserialize(entry.toString());
        deduped.putIfAbsent(pointer.outboxWorkItemId(), pointer);
        if (deduped.size() >= maxPointers) {
          drainCounter.increment(deduped.size());
          return List.copyOf(deduped.values());
        }
      }
    }
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

  @Override
  @Timed(value = "automation.queue.inspect")
  public QueueHealthSnapshot inspectProjectionHealth(int maxQueues, long staleAfterSeconds) {
    if (maxQueues <= 0) {
      throw new IllegalArgumentException("max_queues must be positive");
    }
    if (staleAfterSeconds <= 0) {
      throw new IllegalArgumentException("stale_after_seconds must be positive");
    }
    Set<String> queueKeys = redisTemplate.keys("automation:queue:*");
    if (queueKeys == null || queueKeys.isEmpty()) {
      orphanedEntriesGauge.set(0L);
      oldestEntryAgeSecondsGauge.set(0L);
      inspectionCounter.increment();
      return new QueueHealthSnapshot(0, 0, 0L);
    }
    List<String> limitedKeys = queueKeys.stream().sorted().limit(maxQueues).toList();
    LinkedHashMap<Long, AutomationQueueWorkItemPointer> pointers = new LinkedHashMap<>();
    for (String queueKey : limitedKeys) {
      List<Object> raw = listOps.range(queueKey, 0, -1);
      if (raw == null) {
        continue;
      }
      raw.stream()
          .map(Object::toString)
          .map(this::deserialize)
          .forEach(pointer -> pointers.putIfAbsent(pointer.outboxWorkItemId(), pointer));
    }
    Map<Long, ScriptWorkItem> itemsById =
        workItemRepository.findAllById(pointers.keySet()).stream()
            .collect(java.util.stream.Collectors.toMap(ScriptWorkItem::getId, item -> item));
    Instant now = Instant.now();
    long orphanedEntries = 0L;
    long oldestEntryAgeSeconds = 0L;
    for (AutomationQueueWorkItemPointer pointer : pointers.values()) {
      ScriptWorkItem item = itemsById.get(pointer.outboxWorkItemId());
      if (item == null) {
        orphanedEntries++;
        continue;
      }
      long ageSeconds =
          Math.max(0L, java.time.Duration.between(item.getCreatedAt(), now).getSeconds());
      oldestEntryAgeSeconds = Math.max(oldestEntryAgeSeconds, ageSeconds);
      if (!INDEXABLE_STATUSES.contains(item.getStatus()) || ageSeconds > staleAfterSeconds) {
        orphanedEntries++;
      }
    }
    orphanedEntriesGauge.set(orphanedEntries);
    oldestEntryAgeSecondsGauge.set(oldestEntryAgeSeconds);
    inspectionCounter.increment();
    return new QueueHealthSnapshot(
        limitedKeys.size(), Math.toIntExact(orphanedEntries), oldestEntryAgeSeconds);
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
