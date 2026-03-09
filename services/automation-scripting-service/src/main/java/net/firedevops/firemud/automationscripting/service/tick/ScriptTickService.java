package net.firedevops.firemud.automationscripting.service.tick;

/** Coordinates tick execution for script events using Redis. */
public interface ScriptTickService {
  /** Enqueue a script event for the next tick. */
  void enqueueEvent(Long tenantId, Long scriptId, String eventJson);

  /** Process a single tick if the lock is acquired. */
  void processTick(Long tenantId, Long scriptId);
}
