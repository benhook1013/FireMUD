package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueWorkItemPointer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
class AutomationQueueServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private ListOperations<String, Object> listOps;
  private ScriptWorkItemRepository workItemRepository;
  private SimpleMeterRegistry meterRegistry;
  private AutomationQueueService service;

  @BeforeEach
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(ListOperations.class);
    workItemRepository = mock(ScriptWorkItemRepository.class);
    meterRegistry = new SimpleMeterRegistry();
    when(redisTemplate.opsForList()).thenReturn(listOps);
    service =
        new AutomationQueueServiceImpl(
            redisTemplate, meterRegistry, new ObjectMapper(), workItemRepository);
    ((AutomationQueueServiceImpl) service).init();
  }

  @Test
  void enqueueWorkItemPushesVersionedPointerToRedis() {
    ScriptWorkItem workItem = new ScriptWorkItem();
    workItem.setId(42L);
    workItem.setTenantId("tenant-1");
    workItem.setGameInstanceId("instance-1");
    workItem.setEntityId("entity-1");
    workItem.setScriptPatchVersion("patch-1");
    workItem.setScriptEventId("event-1");

    service.enqueueWorkItem(workItem);

    verify(listOps)
        .rightPush(
            "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1",
            "{\"schemaVersion\":1,\"outboxWorkItemId\":42,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}");
  }

  @Test
  void drainWorkItemsRetrievesDeserializedPointersWithoutDeletingConcurrentEnqueues() {
    when(listOps.leftPop("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1"))
        .thenReturn(
            "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}",
            "{\"schemaVersion\":1,\"outboxWorkItemId\":42,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-2\",\"scriptEventId\":\"event-2\"}",
            (Object) null);

    List<AutomationQueueWorkItemPointer> drained =
        service.drainWorkItems("tenant-1", "instance-1", "entity-1");

    org.assertj.core.api.Assertions.assertThat(drained)
        .containsExactly(
            new AutomationQueueWorkItemPointer(1, 41L, "instance-1", "patch-1", "event-1"),
            new AutomationQueueWorkItemPointer(1, 42L, "instance-1", "patch-2", "event-2"));
  }

  @Test
  void drainWorkItemsDedupesRepeatedPointersByWorkItemId() {
    when(listOps.leftPop("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1"))
        .thenReturn(
            "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}",
            "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}",
            (Object) null);

    List<AutomationQueueWorkItemPointer> drained =
        service.drainWorkItems("tenant-1", "instance-1", "entity-1");

    org.assertj.core.api.Assertions.assertThat(drained)
        .containsExactly(
            new AutomationQueueWorkItemPointer(1, 41L, "instance-1", "patch-1", "event-1"));
  }

  @Test
  void drainWorkItemsStopsAtConfiguredTickPointerBudget() {
    String queueKey = "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1";
    AtomicInteger popCount = new AtomicInteger();
    when(listOps.leftPop(queueKey))
        .thenAnswer(
            invocation -> {
              int index = popCount.getAndIncrement();
              return index < 50 ? pointerJson(41L + index) : null;
            });

    List<AutomationQueueWorkItemPointer> drained =
        service.drainWorkItems("tenant-1", "instance-1", "entity-1");

    assertThat(drained).hasSize(50);
    assertThat(
            drained.stream()
                .map(AutomationQueueWorkItemPointer::outboxWorkItemId)
                .distinct()
                .count())
        .isEqualTo(50L);
    verify(listOps, org.mockito.Mockito.times(50)).leftPop(queueKey);
  }

  @Test
  void rejectsNonPositiveTickPointerBudgetDuringInitialization() {
    AutomationQueueServiceImpl invalidService =
        new AutomationQueueServiceImpl(
            redisTemplate, meterRegistry, new ObjectMapper(), workItemRepository, 0);

    assertThatThrownBy(invalidService::init)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("automation.tick-max-events must be positive");
  }

  @Test
  void drainIndexedWorkItemPointersScansQueueKeysWithoutDeletingDrainedProjections() {
    when(redisTemplate.keys("automation:queue:*"))
        .thenReturn(
            Set.of(
                "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-2",
                "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1"));
    when(listOps.leftPop("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1"))
        .thenReturn(
            "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}",
            "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}",
            (Object) null);
    when(listOps.leftPop("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-2"))
        .thenReturn(
            "{\"schemaVersion\":1,\"outboxWorkItemId\":42,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-2\",\"scriptEventId\":\"event-2\"}",
            (Object) null);

    List<AutomationQueueWorkItemPointer> drained = service.drainIndexedWorkItemPointers(10, 10);

    assertThat(drained)
        .containsExactly(
            new AutomationQueueWorkItemPointer(1, 41L, "instance-1", "patch-1", "event-1"),
            new AutomationQueueWorkItemPointer(1, 42L, "instance-1", "patch-2", "event-2"));
    verify(redisTemplate, never()).delete(Mockito.anyString());
  }

  @Test
  void drainIndexedWorkItemPointersLeavesSuffixWhenPointerBoundIsReached() {
    String queueKey = "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1";
    when(redisTemplate.keys("automation:queue:*")).thenReturn(Set.of(queueKey));
    when(listOps.leftPop(queueKey))
        .thenReturn(
            "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}",
            "{\"schemaVersion\":1,\"outboxWorkItemId\":42,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-2\"}");

    List<AutomationQueueWorkItemPointer> drained = service.drainIndexedWorkItemPointers(10, 1);

    assertThat(drained)
        .containsExactly(
            new AutomationQueueWorkItemPointer(1, 41L, "instance-1", "patch-1", "event-1"));
    verify(listOps).leftPop(queueKey);
  }

  @Test
  void drainIndexedWorkItemPointersCountsMalformedEntriesTowardPointerBound() {
    String queueKey = "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1";
    when(redisTemplate.keys("automation:queue:*")).thenReturn(Set.of(queueKey));
    when(listOps.leftPop(queueKey))
        .thenReturn(
            "not-json",
            "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}",
            "{\"schemaVersion\":1,\"outboxWorkItemId\":42,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-2\"}");

    List<AutomationQueueWorkItemPointer> drained = service.drainIndexedWorkItemPointers(10, 1);

    assertThat(drained).isEmpty();
    assertThat(meterRegistry.get("automation_queue_malformed_entries_total").counter().count())
        .isEqualTo(1.0);
    verify(listOps).leftPop(queueKey);
  }

  @Test
  void rebuildPendingWorkItemIndexRepublishesOnlyMissingPointers() {
    ScriptWorkItem missing = workItem(41L, "entity-1");
    ScriptWorkItem existing = workItem(42L, "entity-2");
    when(workItemRepository.findByStatusInOrderByCreatedAtAscIdAsc(
            List.of("PENDING_EVALUATION"), PageRequest.of(0, 10)))
        .thenReturn(List.of(missing, existing));
    when(listOps.range("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1", 0, -1))
        .thenReturn(List.of());
    when(listOps.range("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-2", 0, -1))
        .thenReturn(
            List.of(
                "{\"schemaVersion\":1,\"outboxWorkItemId\":42,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-42\"}"));

    int rebuilt = service.rebuildPendingWorkItemIndex(10);

    assertThat(rebuilt).isEqualTo(1);
    verify(listOps)
        .rightPush(
            "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1",
            "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-41\"}");
  }

  @Test
  void inspectProjectionHealthCountsStaleAndMissingPointers() {
    ScriptWorkItem fresh = workItem(41L, "entity-1");
    fresh.setCreatedAt(Instant.now().minusSeconds(30));
    fresh.setStatus("PENDING_EVALUATION");
    ScriptWorkItem stale = workItem(42L, "entity-2");
    stale.setCreatedAt(Instant.now().minusSeconds(600));
    stale.setStatus("EVALUATING");
    when(redisTemplate.keys("automation:queue:*"))
        .thenReturn(
            Set.of(
                "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1",
                "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-2"));
    when(listOps.range("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1", 0, -1))
        .thenReturn(
            List.of(
                "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-41\"}",
                "{\"schemaVersion\":1,\"outboxWorkItemId\":99,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-99\"}"));
    when(listOps.range("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-2", 0, -1))
        .thenReturn(
            List.of(
                "{\"schemaVersion\":1,\"outboxWorkItemId\":42,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-42\"}"));
    when(workItemRepository.findAllById(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(fresh, stale));

    AutomationQueueService.QueueHealthSnapshot snapshot = service.inspectProjectionHealth(10, 300);

    assertThat(snapshot.inspectedQueues()).isEqualTo(2);
    assertThat(snapshot.orphanedEntries()).isEqualTo(2);
    assertThat(snapshot.oldestEntryAgeSeconds()).isGreaterThanOrEqualTo(600);
  }

  @Test
  void inspectProjectionHealthResetsMetricsWhenNoQueuesExist() {
    when(redisTemplate.keys("automation:queue:*")).thenReturn(Set.of());

    AutomationQueueService.QueueHealthSnapshot snapshot = service.inspectProjectionHealth(10, 300);

    assertThat(snapshot).isEqualTo(new AutomationQueueService.QueueHealthSnapshot(0, 0, 0L));
  }

  private static ScriptWorkItem workItem(long id, String entityId) {
    ScriptWorkItem workItem = new ScriptWorkItem();
    workItem.setId(id);
    workItem.setTenantId("tenant-1");
    workItem.setGameInstanceId("instance-1");
    workItem.setEntityId(entityId);
    workItem.setScriptPatchVersion("patch-1");
    workItem.setScriptEventId("event-" + id);
    workItem.setCreatedAt(Instant.EPOCH);
    workItem.setStatus("PENDING_EVALUATION");
    return workItem;
  }

  private static String pointerJson(long id) {
    return "{\"schemaVersion\":1,\"outboxWorkItemId\":"
        + id
        + ",\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-"
        + id
        + "\"}";
  }
}
