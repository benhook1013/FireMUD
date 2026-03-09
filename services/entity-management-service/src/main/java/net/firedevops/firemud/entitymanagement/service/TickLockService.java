package net.firedevops.firemud.entitymanagement.service;

/** Simple service for acquiring per-entity tick locks in Redis. */
public interface TickLockService {
  /**
   * Acquire a tick lock for the entity. Returns true if obtained.
   *
   * @param tenantId tenant identifier used to prefix the Redis key
   * @param entityId unique entity identifier
   * @return {@code true} if the lock was successfully acquired
   */
  boolean acquireLock(Long tenantId, Long entityId);

  /**
   * Release the previously acquired lock.
   *
   * @param tenantId tenant identifier used to prefix the Redis key
   * @param entityId unique entity identifier
   */
  void releaseLock(Long tenantId, Long entityId);
}
