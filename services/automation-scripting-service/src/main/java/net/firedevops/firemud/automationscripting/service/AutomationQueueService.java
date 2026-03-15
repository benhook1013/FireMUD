package net.firedevops.firemud.automationscripting.service;

import java.util.List;

/** Service for managing automation event queues in Redis. */
public interface AutomationQueueService {
  /**
   * Enqueue a serialized event for the specified entity.
   *
   * @param tenantId tenant identifier
   * @param entityId target entity identifier
   * @param eventJson serialized event payload
   */
  void enqueueEvent(Long tenantId, Long entityId, String eventJson);

  /**
   * Retrieve and clear all queued events for the entity.
   *
   * @return list of event payloads in FIFO order
   */
  List<String> drainEvents(Long tenantId, Long entityId);
}
