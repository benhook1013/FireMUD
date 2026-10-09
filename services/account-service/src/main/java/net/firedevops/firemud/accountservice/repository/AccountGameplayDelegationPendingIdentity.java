package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable pending-issuance identity data; this value is not signing or authorization evidence.
 */
public record AccountGameplayDelegationPendingIdentity(
    UUID operationId,
    UUID requestId,
    UUID accountId,
    String callerWorkload,
    UUID callerContextId,
    String requestDigest,
    UUID tokenJti,
    long issuedAtEpochSecond,
    long notBeforeEpochSecond,
    long expiresAtEpochSecond) {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public AccountGameplayDelegationPendingIdentity {
    Objects.requireNonNull(operationId);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(accountId);
    Objects.requireNonNull(callerContextId);
    Objects.requireNonNull(tokenJti);
    if (requestDigest == null || !SHA256.matcher(requestDigest).matches()) {
      throw new IllegalArgumentException("Request digest is malformed");
    }
    if (callerWorkload == null || callerWorkload.length() > 256) {
      throw new IllegalArgumentException("Caller workload is malformed");
    }
  }
}
