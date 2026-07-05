package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;

public record FirstPartyConnectContextResolution(
    Optional<FirstPartyConnectContext> connectContext, boolean invalid) {

  public FirstPartyConnectContextResolution {
    connectContext = Objects.requireNonNull(connectContext, "connectContext must not be null");
  }

  public static FirstPartyConnectContextResolution resolve(
      long sessionId,
      SessionContext sessionContext,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry) {
    Objects.requireNonNull(
        firstPartyConnectContextRegistry, "firstPartyConnectContextRegistry must not be null");
    Optional<FirstPartyConnectContext> registryContext =
        firstPartyConnectContextRegistry.find(sessionId);
    if (registryContext.isPresent()) {
      FirstPartyConnectContext connectContext = registryContext.orElseThrow();
      return new FirstPartyConnectContextResolution(
          connectContext.hasCompleteRoutingScope() ? Optional.of(connectContext) : Optional.empty(),
          !connectContext.hasCompleteRoutingScope());
    }
    if (sessionContext == null) {
      return new FirstPartyConnectContextResolution(Optional.empty(), false);
    }
    Optional<FirstPartyConnectContext> persistedContext =
        sessionContext.persistedFirstPartyConnectContext();
    return new FirstPartyConnectContextResolution(
        persistedContext, sessionContext.hasPartialPersistedFirstPartyConnectContext());
  }
}
