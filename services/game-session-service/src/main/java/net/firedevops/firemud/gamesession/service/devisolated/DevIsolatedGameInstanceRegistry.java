package net.firedevops.firemud.gamesession.service.devisolated;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import org.springframework.stereotype.Component;

/**
 * Tracks the lightweight GameInstance metadata created while dev-isolated mode is enabled. These
 * instances never hit a real database, but need to be visible to LOGIN handling.
 */
@Component
public final class DevIsolatedGameInstanceRegistry {
  private final AtomicLong nextId = new AtomicLong(1L);
  private final Map<Long, GameInstance> instances = new ConcurrentHashMap<>();

  public long nextSessionId() {
    return nextId.getAndIncrement();
  }

  public void register(GameInstance instance) {
    instances.put(instance.getId(), instance);
  }

  public Optional<GameInstance> findById(long sessionId) {
    return Optional.ofNullable(instances.get(sessionId));
  }

  public void remove(long sessionId) {
    instances.remove(sessionId);
  }

  public void updateStatus(long sessionId, String status) {
    Optional.ofNullable(instances.get(sessionId)).ifPresent(instance -> instance.setStatus(status));
  }
}
