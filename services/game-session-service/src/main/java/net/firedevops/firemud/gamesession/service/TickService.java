package net.firedevops.firemud.gamesession.service;

/** Coordinates tick execution and command queues using Redis. */
public interface TickService {
  /** Add a command to the queue for the next tick. */
  void enqueueCommand(Long sessionId, String command, boolean requiresSoloTick);

  /** Execute a single tick if the lock is acquired. */
  void processTick(Long sessionId);

  /** Retrieve the latest persisted state for monitoring. */
  String queryState(Long sessionId);

  /** Request that new ticks stop starting. */
  void pauseTicks(String reason);

  /** Allow ticks to resume normally. */
  void resumeTicks(String reason);

  /** Request that ticks stop starting for a single game instance. */
  void pauseTicksForGameInstance(Long gameInstanceId, String reason);

  /** Allow ticks to resume normally for a single game instance. */
  void resumeTicksForGameInstance(Long gameInstanceId, String reason);

  /** Whether ticks are currently paused. */
  net.firedevops.firemud.gamesession.v1.TickStatus getTickStatus();
}
