package net.firedevops.firemud.gamesession.service;

import java.util.Optional;

public interface FirstPartyConnectContextRegistry {
  void register(long sessionId, FirstPartyConnectContext connectContext);

  /**
   * Returns a retained context only after validating its signature, expiry, and current route.
   * Consumers may trust a present result as fresh at the time of this lookup.
   */
  Optional<FirstPartyConnectContext> find(long sessionId);

  FirstPartyConnectContext requireRetained(FirstPartyConnectContext context);

  void unregister(long sessionId);
}
