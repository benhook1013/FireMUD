package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;

/** Service for managing automation event queues in Redis. */
public interface AutomationQueueService {
  /**
   * Enqueue a durable work-item pointer for the specified entity.
   *
   * @param workItem durable outbox work item
   */
  void enqueueWorkItem(ScriptWorkItem workItem);

  /**
   * Retrieve and clear all queued work-item pointers for the entity.
   *
   * @return list of queue envelopes in FIFO order
   */
  List<AutomationQueueWorkItemPointer> drainWorkItems(
      String tenantId, String gameInstanceId, String entityId);

  /**
   * Retrieve and clear queued work-item pointers from a bounded set of queue keys.
   *
   * <p>This is a discovery aid only: callers must still claim the durable outbox rows before
   * executing work.
   *
   * @return deduplicated queue envelopes in queue-key/FIFO order
   */
  default List<AutomationQueueWorkItemPointer> drainIndexedWorkItemPointers(
      int maxQueues, int maxPointers) {
    return List.of();
  }

  /**
   * Rebuild the Redis queue projection for a bounded set of pending durable work items.
   *
   * @param maxItems max number of durable rows to inspect
   * @return number of queue pointers published
   */
  int rebuildPendingWorkItemIndex(int maxItems);

  default QueueHealthSnapshot inspectProjectionHealth(int maxQueues, long staleAfterSeconds) {
    return new QueueHealthSnapshot(0, 0, 0L);
  }

  record QueueHealthSnapshot(
      int inspectedQueues, int orphanedEntries, long oldestEntryAgeSeconds) {}
}
