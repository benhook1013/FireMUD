package net.firedevops.firemud.gamesession.service.impl;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import org.springframework.stereotype.Component;

@Component
public class InMemoryFirstPartyConnectContextRegistry implements FirstPartyConnectContextRegistry {
  private final ConcurrentHashMap<Long, FirstPartyConnectContext> contexts =
      new ConcurrentHashMap<>();

  @Override
  public void register(long sessionId, FirstPartyConnectContext connectContext) {
    contexts.put(sessionId, connectContext);
  }

  @Override
  public Optional<FirstPartyConnectContext> find(long sessionId) {
    return Optional.ofNullable(contexts.get(sessionId));
  }

  @Override
  public void unregister(long sessionId) {
    contexts.remove(sessionId);
  }
}
