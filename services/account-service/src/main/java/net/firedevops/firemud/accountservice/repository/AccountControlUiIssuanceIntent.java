package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiOriginalSourceCapture;

/**
 * Reserved identity and write-once post-sign hash, not a completed evidence bundle or credential.
 */
public record AccountControlUiIssuanceIntent(
    AccountControlUiIssuanceOperation operation,
    UUID jti,
    UUID bundleId,
    long bundleVersion,
    long sourceVersion,
    long sourceFence,
    Optional<String> tokenHash) {
  public AccountControlUiIssuanceIntent {
    Objects.requireNonNull(operation);
    nonNil(jti);
    nonNil(bundleId);
    if (bundleVersion != 1L || sourceVersion <= 0L || sourceFence <= 0L) {
      throw new IllegalArgumentException("Invalid reserved control-ui capture reference");
    }
    tokenHash = Objects.requireNonNull(tokenHash);
    tokenHash.ifPresent(
        hash -> {
          if (!hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid compact JWT hash");
        });
    if (!AccountControlUiOriginalSourceCapture.read(operation.originalCapture())
        .accountUuid()
        .equals(operation.request().accountUuid())) {
      throw new IllegalStateException("Original source subject differs from its operation");
    }
    if (operation.completedResponse() != null
        && !tokenHash.equals(Optional.of(operation.completedResponse().binding().tokenHash()))) {
      throw new IllegalStateException(
          "Original committed result differs from reserved token identity");
    }
  }

  private static void nonNil(UUID value) {
    if (value == null || value.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException("Non-nil reserved identity required");
  }

  @Override
  public String toString() {
    return "AccountControlUiIssuanceIntent[operationId="
        + operation.operationId()
        + ", non-authorizing]";
  }
}
