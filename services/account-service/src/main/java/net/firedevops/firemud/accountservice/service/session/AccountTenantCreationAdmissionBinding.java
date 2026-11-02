package net.firedevops.firemud.accountservice.service.session;

import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;

/** Immutable original data only; constructing this binding does not grant admission authority. */
public record AccountTenantCreationAdmissionBinding(
    UUID intentRequestId,
    UUID creationOperationId,
    UUID issuanceOperationId,
    UUID actorAccountId,
    String targetNamespace,
    UUID gameDesignCreationRequestId,
    String gameDesignRequestDigest,
    String intentDigest,
    String activeRegistryDigest,
    UUID reauthenticationEvidenceId,
    String tokenHash,
    long tokenCleanupDeadlineEpochMillis,
    long tokenExpiresAtEpochMillis,
    long preparedAtEpochMillis,
    long operationDeadlineEpochMillis,
    long receiptCleanupDeadlineEpochMillis) {

  public static final String SCHEMA = "account-tenant-creation-admission-v1";
  public static final long MAX_OPERATION_MILLIS = 300_000L;
  public static final long RECEIPT_RETENTION_MILLIS = 86_400_000L;
  public static final long MAX_REDIS_SAFE_INTEGER = 9_007_199_254_740_991L;

  public AccountTenantCreationAdmissionBinding {
    requireUuid(intentRequestId);
    requireUuid(creationOperationId);
    requireUuid(issuanceOperationId);
    requireUuid(actorAccountId);
    requireUuid(gameDesignCreationRequestId);
    requireUuid(reauthenticationEvidenceId);
    if (targetNamespace == null
        || !targetNamespace.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
      throw new IllegalArgumentException("Canonical target namespace is required");
    }
    requireDigest(gameDesignRequestDigest, "sha256:");
    requireDigest(intentDigest, "sha256:");
    requireDigest(activeRegistryDigest, "");
    AccountTenantCreationAdmissionRedisKeys.token(tokenHash);
    requireMillis(tokenCleanupDeadlineEpochMillis);
    requireMillis(tokenExpiresAtEpochMillis);
    requireMillis(preparedAtEpochMillis);
    requireMillis(operationDeadlineEpochMillis);
    requireMillis(receiptCleanupDeadlineEpochMillis);
    if (tokenExpiresAtEpochMillis % 1000L != 0L
        || tokenCleanupDeadlineEpochMillis < tokenExpiresAtEpochMillis
        || operationDeadlineEpochMillis <= preparedAtEpochMillis
        || operationDeadlineEpochMillis > tokenExpiresAtEpochMillis
        || operationDeadlineEpochMillis - preparedAtEpochMillis > MAX_OPERATION_MILLIS
        || receiptCleanupDeadlineEpochMillis - operationDeadlineEpochMillis
            != RECEIPT_RETENTION_MILLIS) {
      throw new IllegalArgumentException("Original admission deadlines violate the bounded policy");
    }
  }

  /** Canonical receipt data, not a physical Redis or durable SQL admission proof. */
  public byte[] canonicalReceipt() {
    return AccountControlUiAuthority.canonical(
        Map.ofEntries(
            Map.entry("schema", SCHEMA),
            Map.entry("intentRequestId", intentRequestId.toString()),
            Map.entry("creationOperationId", creationOperationId.toString()),
            Map.entry("issuanceOperationId", issuanceOperationId.toString()),
            Map.entry("actorAccountId", actorAccountId.toString()),
            Map.entry("targetNamespace", targetNamespace),
            Map.entry("gameDesignCreationRequestId", gameDesignCreationRequestId.toString()),
            Map.entry("gameDesignRequestDigest", gameDesignRequestDigest),
            Map.entry("intentDigest", intentDigest),
            Map.entry("activeRegistryDigest", activeRegistryDigest),
            Map.entry("reauthenticationEvidenceId", reauthenticationEvidenceId.toString()),
            Map.entry("tokenHash", tokenHash),
            Map.entry("tokenCleanupDeadlineEpochMillis", tokenCleanupDeadlineEpochMillis),
            Map.entry("tokenExpiresAtEpochMillis", tokenExpiresAtEpochMillis),
            Map.entry("preparedAtEpochMillis", preparedAtEpochMillis),
            Map.entry("operationDeadlineEpochMillis", operationDeadlineEpochMillis),
            Map.entry("receiptCleanupDeadlineEpochMillis", receiptCleanupDeadlineEpochMillis)));
  }

  private static void requireUuid(UUID value) {
    if (value == null
        || value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
      throw new IllegalArgumentException("Non-nil original identity is required");
    }
  }

  private static void requireDigest(String value, String prefix) {
    if (value == null || !value.matches(prefix + "[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Canonical original digest is required");
    }
  }

  private static void requireMillis(long value) {
    if (value <= 0L || value > MAX_REDIS_SAFE_INTEGER) {
      throw new IllegalArgumentException("Deadline is outside the exact Redis integer range");
    }
  }
}
