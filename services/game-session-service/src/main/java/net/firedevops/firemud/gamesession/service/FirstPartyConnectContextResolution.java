package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;

public record FirstPartyConnectContextResolution(
    Optional<FirstPartyConnectContext> connectContext, boolean invalid) {

  public FirstPartyConnectContextResolution {
    connectContext = Objects.requireNonNull(connectContext, "connectContext must not be null");
  }

  /**
   * Resolves live registry or persisted first-party context. Persisted recovery still requires its
   * compact JWS to be valid; losing the registry entry does not extend the signed expiry.
   */
  public static FirstPartyConnectContextResolution resolve(
      long sessionId,
      SessionContext sessionContext,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry) {
    Objects.requireNonNull(
        firstPartyConnectContextRegistry, "firstPartyConnectContextRegistry must not be null");
    try {
      Optional<FirstPartyConnectContext> registryContext =
          firstPartyConnectContextRegistry.find(sessionId);
      Optional<FirstPartyConnectContext> persisted =
          sessionContext == null
              ? Optional.empty()
              : sessionContext.persistedFirstPartyConnectContext();
      if (sessionContext != null && sessionContext.hasPartialPersistedFirstPartyConnectContext()) {
        return new FirstPartyConnectContextResolution(Optional.empty(), true);
      }
      if (registryContext.isPresent()) {
        FirstPartyConnectContext current = registryContext.orElseThrow();
        if (persisted.isPresent() && !current.equals(persisted.orElseThrow())) {
          return new FirstPartyConnectContextResolution(Optional.empty(), true);
        }
        return new FirstPartyConnectContextResolution(Optional.of(current), false);
      }
      if (persisted.isPresent()) {
        return new FirstPartyConnectContextResolution(
            Optional.of(firstPartyConnectContextRegistry.requireRetained(persisted.orElseThrow())),
            false);
      }
      return new FirstPartyConnectContextResolution(Optional.empty(), false);
    } catch (RuntimeException invalidOrUnavailable) {
      return new FirstPartyConnectContextResolution(Optional.empty(), true);
    }
  }
}
