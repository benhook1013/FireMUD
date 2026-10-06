package net.firedevops.firemud.accountservice.service;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.ratelimit.RateLimitSubjectHash;

/** Trusted, server-derived network context for one Account credential attempt. */
public final class CredentialAttemptSource {
  public enum ConnectionMode {
    FIRST_PARTY_WEB,
    TRUSTED_TCP_PROXY
  }

  private final byte[] canonicalClientAddress;
  private final ConnectionMode connectionMode;

  public CredentialAttemptSource(String canonicalClientIp, ConnectionMode connectionMode) {
    if (!RateLimitSubjectHash.isCanonicalClientAddressLiteral(canonicalClientIp)) {
      throw new IllegalArgumentException("credential source must be a canonical IP literal");
    }
    this.canonicalClientAddress =
        RateLimitSubjectHash.canonicalClientAddressBytes(canonicalClientIp);
    this.connectionMode = Objects.requireNonNull(connectionMode, "connectionMode must not be null");
  }

  public byte[] canonicalClientAddress() {
    return canonicalClientAddress.clone();
  }

  public ConnectionMode connectionMode() {
    return connectionMode;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CredentialAttemptSource that)) {
      return false;
    }
    return connectionMode == that.connectionMode
        && Arrays.equals(canonicalClientAddress, that.canonicalClientAddress);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(canonicalClientAddress) + connectionMode.hashCode();
  }

  @Override
  public String toString() {
    return "CredentialAttemptSource[clientAddress=redacted, connectionMode=" + connectionMode + "]";
  }
}
