package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;

/** Account SQL token identity and monotonic fence; contains no credential or registry assertion. */
public record AccountGameplayTokenIdentityFence(
    TokenIdentity identity,
    long tokenIdentityFence,
    State state,
    UUID revocationRequestId,
    String revocationDigest) {
  public AccountGameplayTokenIdentityFence {
    Objects.requireNonNull(identity);
    Objects.requireNonNull(state);
    if (tokenIdentityFence <= 0L)
      throw new IllegalArgumentException("Positive token fence required");
    if (state == State.ACTIVE) {
      if (tokenIdentityFence != 1L || revocationRequestId != null || revocationDigest != null) {
        throw new IllegalArgumentException("Initial active fence is malformed");
      }
    } else {
      requireUuid(revocationRequestId);
      requireDigest(revocationDigest);
      if (tokenIdentityFence != (state == State.PENDING ? 2L : 3L)) {
        throw new IllegalArgumentException("Revocation fence is malformed");
      }
    }
  }

  public enum State {
    ACTIVE,
    PENDING,
    COMMITTED
  }

  /** Exact immutable Account COMMITTED issuance identity, never reconstructed from Redis. */
  public record TokenIdentity(
      UUID accountId,
      UUID operationId,
      UUID issuanceRequestId,
      String tokenSha256,
      UUID tokenJti,
      long notBeforeEpochSecond,
      long tokenGeneration,
      long issuanceFence) {
    public TokenIdentity {
      requireUuid(accountId);
      requireUuid(operationId);
      requireUuid(issuanceRequestId);
      requireUuid(tokenJti);
      requireDigest(tokenSha256);
      if (notBeforeEpochSecond <= 0L || tokenGeneration <= 0L || issuanceFence <= 0L) {
        throw new IllegalArgumentException("Positive exact token identity metadata required");
      }
    }

    @Override
    public String toString() {
      return "TokenIdentity[redacted]";
    }
  }

  private static void requireUuid(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("Canonical UUIDv4 required");
    }
  }

  private static void requireDigest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Canonical SHA-256 required");
    }
  }

  @Override
  public String toString() {
    return "AccountGameplayTokenIdentityFence[redacted]";
  }
}
