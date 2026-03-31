package net.firedevops.firemud.gamesession.service;

import java.util.Optional;

public interface FirstPartyConnectContextRegistry {
  void register(long sessionId, FirstPartyConnectContext connectContext);

  Optional<FirstPartyConnectContext> find(long sessionId);

  void unregister(long sessionId);
}
