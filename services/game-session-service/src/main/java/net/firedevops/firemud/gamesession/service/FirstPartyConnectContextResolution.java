package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.serializer.SerializationException;

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
    Optional<FirstPartyConnectContext> registryContext;
    try {
      registryContext = firstPartyConnectContextRegistry.find(sessionId);
    } catch (SerializationException | ClassCastException ex) {
      // An unreadable legacy carrier is not absence and cannot authorize persisted-context
      // fallback.
      return new FirstPartyConnectContextResolution(Optional.empty(), true);
    }
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
