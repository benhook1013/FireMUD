package net.firedevops.firemud.automationscripting.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueWorkItemPointer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
class AutomationQueueServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private ListOperations<String, Object> listOps;
  private ScriptWorkItemRepository workItemRepository;
  private AutomationQueueService service;

  @BeforeEach
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(ListOperations.class);
    workItemRepository = mock(ScriptWorkItemRepository.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    service =
        new AutomationQueueServiceImpl(
            redisTemplate, new SimpleMeterRegistry(), new ObjectMapper(), workItemRepository);
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
  void drainWorkItemsRetrievesDeserializedPointersAndDeletesQueue() {
    when(listOps.range("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1", 0, -1))
        .thenReturn(
            List.of(
                "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}",
                "{\"schemaVersion\":1,\"outboxWorkItemId\":42,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-2\",\"scriptEventId\":\"event-2\"}"));

    List<AutomationQueueWorkItemPointer> drained =
        service.drainWorkItems("tenant-1", "instance-1", "entity-1");

    org.assertj.core.api.Assertions.assertThat(drained)
        .containsExactly(
            new AutomationQueueWorkItemPointer(1, 41L, "instance-1", "patch-1", "event-1"),
            new AutomationQueueWorkItemPointer(1, 42L, "instance-1", "patch-2", "event-2"));
    verify(redisTemplate).delete("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1");
  }

  @Test
  void drainWorkItemsDedupesRepeatedPointersByWorkItemId() {
    when(listOps.range("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1", 0, -1))
        .thenReturn(
            List.of(
                "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}",
                "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-1\"}"));

    List<AutomationQueueWorkItemPointer> drained =
        service.drainWorkItems("tenant-1", "instance-1", "entity-1");

    org.assertj.core.api.Assertions.assertThat(drained)
        .containsExactly(
            new AutomationQueueWorkItemPointer(1, 41L, "instance-1", "patch-1", "event-1"));
  }

  @Test
  void rebuildPendingWorkItemIndexRepublishesOnlyMissingPointers() {
    ScriptWorkItem missing = workItem(41L, "entity-1");
    ScriptWorkItem existing = workItem(42L, "entity-2");
    when(workItemRepository.findByStatusInOrderByCreatedAtAscIdAsc(
            List.of("PENDING_EVALUATION", "EVALUATING"), PageRequest.of(0, 10)))
        .thenReturn(List.of(missing, existing));
    when(listOps.range("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1", 0, -1))
        .thenReturn(List.of());
    when(listOps.range("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-2", 0, -1))
        .thenReturn(
            List.of(
                "{\"schemaVersion\":1,\"outboxWorkItemId\":42,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-42\"}"));

    int rebuilt = service.rebuildPendingWorkItemIndex(10);

    org.assertj.core.api.Assertions.assertThat(rebuilt).isEqualTo(1);
    verify(listOps)
        .rightPush(
            "automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1",
            "{\"schemaVersion\":1,\"outboxWorkItemId\":41,\"gameInstanceId\":\"instance-1\",\"scriptPatchVersion\":\"patch-1\",\"scriptEventId\":\"event-41\"}");
  }

  private static ScriptWorkItem workItem(long id, String entityId) {
    ScriptWorkItem workItem = new ScriptWorkItem();
    workItem.setId(id);
    workItem.setTenantId("tenant-1");
    workItem.setGameInstanceId("instance-1");
    workItem.setEntityId(entityId);
    workItem.setScriptPatchVersion("patch-1");
    workItem.setScriptEventId("event-" + id);
    return workItem;
  }
}
