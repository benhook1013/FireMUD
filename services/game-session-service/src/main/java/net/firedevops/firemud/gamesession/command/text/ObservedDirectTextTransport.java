package net.firedevops.firemud.gamesession.command.text;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.service.SessionIdParsing;

/**
 * Preserves the proxy connection identifier observed at transport intake for direct-text
 * correlation only. This metadata is not authenticated ingress, caller identity, or LOGIN/JOIN
 * authority, and it must never be reconstructed from a numeric transport session ID.
 */
public record ObservedDirectTextTransport(String transportSessionId, UUID proxyConnectionId) {
  private static final String TRUSTED_TCP_PROXY_MODE = "trusted_tcp_proxy";

  public ObservedDirectTextTransport {
    SessionIdParsing.require(transportSessionId);
    if (proxyConnectionId == null
        || proxyConnectionId.version() != 4
        || proxyConnectionId.variant() != 2
        || new UUID(0L, 0L).equals(proxyConnectionId)) {
      throw new IllegalArgumentException("Proxy connection UUIDv4 required");
    }
  }

  /** Returns a direct-text observation only for the original canonical proxy UUID in proxy mode. */
  public static Optional<ObservedDirectTextTransport> fromHandshake(
      String connectionMode, String transportSessionId, String originalProxyConnectionId) {
    if (!TRUSTED_TCP_PROXY_MODE.equals(connectionMode)
        || originalProxyConnectionId == null
        || originalProxyConnectionId.isBlank()) {
      return Optional.empty();
    }
    try {
      UUID proxyConnectionId = UUID.fromString(originalProxyConnectionId);
      if (!proxyConnectionId.toString().equals(originalProxyConnectionId)) {
        return Optional.empty();
      }
      return Optional.of(new ObservedDirectTextTransport(transportSessionId, proxyConnectionId));
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }
}
