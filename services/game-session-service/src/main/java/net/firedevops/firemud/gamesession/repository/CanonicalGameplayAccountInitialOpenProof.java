package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owner-issued current OPEN proof, or a typed refusal that cannot be used as admission evidence. */
sealed interface CanonicalGameplayAccountInitialOpenProof
    permits CanonicalGameplayAccountInitialOpenProof.CurrentOpen,
        CanonicalGameplayAccountInitialOpenProof.Refused {
  record CurrentOpen(
      UUID initializerId,
      UUID originalRequestId,
      UUID originalCallerContextId,
      long originalAttemptFence,
      UUID accountId,
      UUID tenantId,
      UUID playableStateNamespaceId,
      String key,
      BigInteger sourceControlEpoch,
      BigInteger inventorySnapshotRevision,
      String accountLoginEvidenceDigest,
      String sourceControlDigest,
      String incarnationDigest,
      long issuedAtEpochMillis,
      long expiresAtEpochMillis,
      Map<String, String> exactFields)
      implements CanonicalGameplayAccountInitialOpenProof {
    CurrentOpen {
      Objects.requireNonNull(initializerId, "initializerId");
      Objects.requireNonNull(originalRequestId, "originalRequestId");
      Objects.requireNonNull(originalCallerContextId, "originalCallerContextId");
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(sourceControlEpoch, "sourceControlEpoch");
      Objects.requireNonNull(inventorySnapshotRevision, "inventorySnapshotRevision");
      requireDigest(accountLoginEvidenceDigest, "accountLoginEvidenceDigest");
      requireDigest(sourceControlDigest, "sourceControlDigest");
      requireDigest(incarnationDigest, "incarnationDigest");
      exactFields = Map.copyOf(Objects.requireNonNull(exactFields, "exactFields"));
      if (originalAttemptFence <= 0L
          || sourceControlEpoch.signum() < 0
          || sourceControlEpoch.toString().length() > 256
          || inventorySnapshotRevision.signum() <= 0
          || issuedAtEpochMillis <= 0
          || expiresAtEpochMillis <= issuedAtEpochMillis
          || !CanonicalGameplayAccountInitialBarrierValue.FIELDS.equals(exactFields.keySet())
          || !"OPEN".equals(exactFields.get("state"))
          || !sourceControlEpoch.toString().equals(exactFields.get("ownerFenceEpoch"))
          || !initializerId.toString().equals(exactFields.get("ownerOperationId"))
          || !accountId.toString().equals(exactFields.get("accountId"))
          || !tenantId.toString().equals(exactFields.get("tenantId"))
          || !playableStateNamespaceId.toString()
              .equals(exactFields.get("playableStateNamespaceId"))
          || !incarnationDigest.equals(exactFields.get("incarnationDigest"))) {
        throw new IllegalArgumentException("Current INITIAL OPEN proof is malformed");
      }
    }
  }

  record Refused(Reason reason) implements CanonicalGameplayAccountInitialOpenProof {
    Refused {
      Objects.requireNonNull(reason, "reason");
    }
  }

  enum Reason {
    ORIGINAL_ATTEMPT_MISMATCH,
    SOURCE_CONTROL_UNAVAILABLE,
    TARGET_NEVER_DISPATCHED,
    REMOTE_OUTCOME_UNCERTAIN,
    PHYSICAL_VALUE_MISSING_OR_CONFLICTING,
    PROTECTED_OBSERVATION_UNAVAILABLE,
    CURRENT_CONTROL_CHANGED
  }

  private static void requireDigest(String value, String name) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be canonical SHA-256");
    }
  }
}
