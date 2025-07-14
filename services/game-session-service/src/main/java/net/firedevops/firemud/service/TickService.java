package net.firedevops.firemud.service;

/** Coordinates tick execution and command queues using Redis. */
public interface TickService {
  /** Add a command to the queue for the next tick. */
  void enqueueCommand(Long sessionId, String command, boolean requiresSoloTick);

  /** Execute a single tick if the lock is acquired. */
  void processTick(Long sessionId);

  /** Retrieve the latest persisted state for monitoring. */
  String queryState(Long sessionId);
}
