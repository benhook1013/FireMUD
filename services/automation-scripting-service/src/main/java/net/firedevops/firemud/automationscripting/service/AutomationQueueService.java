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
}
