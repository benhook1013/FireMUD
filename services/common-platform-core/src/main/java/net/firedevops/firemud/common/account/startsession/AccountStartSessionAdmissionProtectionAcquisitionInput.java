package net.firedevops.firemud.common.account.startsession;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;

/** Immutable caller-carried fields for original StartSession admission-protection acquisition. */
public record AccountStartSessionAdmissionProtectionAcquisitionInput(
    byte[] originalPostAuthorizationTuple,
    UUID gameSessionOwnerMutationId,
    UUID gameSessionOwnerAttemptId,
    long gameSessionOwnerFence,
    UUID accountWorldParticipationId,
    long accountWorldParticipationFence,
    WorldCanonicalInitialAdmissionHold.HoldIdentity worldHoldIdentity) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public AccountStartSessionAdmissionProtectionAcquisitionInput {
    Objects.requireNonNull(
        originalPostAuthorizationTuple, "original StartSession tuple is required");
    if (originalPostAuthorizationTuple.length == 0
        || originalPostAuthorizationTuple.length
            > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
      throw new IllegalArgumentException("Original StartSession tuple is missing or oversized");
    }
    originalPostAuthorizationTuple = originalPostAuthorizationTuple.clone();
    var tuple = StartSessionPostAuthorizationExecutionTuple.decode(originalPostAuthorizationTuple);
    if (!Arrays.equals(originalPostAuthorizationTuple, tuple.canonicalBytes())) {
      throw new IllegalArgumentException("Original StartSession tuple must be canonical");
    }
    if (!"StartSession".equals(tuple.preAuthorizationTuple().actionFamily())
        || !"game-session-service".equals(tuple.preAuthorizationTuple().targetOwner())) {
      throw new IllegalArgumentException("Original human StartSession tuple is required");
    }
    gameSessionOwnerMutationId =
        requireNonNil(gameSessionOwnerMutationId, "Game Session mutation ID");
    gameSessionOwnerAttemptId = requireNonNil(gameSessionOwnerAttemptId, "Game Session attempt ID");
    if (gameSessionOwnerFence <= 0L) {
      throw new IllegalArgumentException("Game Session owner fence must be positive");
    }
    accountWorldParticipationId =
        requireNonNil(accountWorldParticipationId, "Account World participation ID");
    if (accountWorldParticipationFence <= 0L) {
      throw new IllegalArgumentException("Account World participation fence must be positive");
    }
    Objects.requireNonNull(worldHoldIdentity, "complete World hold identity is required");
    byte[] holdBytes = worldHoldIdentity.canonicalBytes();
    if (holdBytes.length == 0 || holdBytes.length > 64 * 1024) {
      throw new IllegalArgumentException("Complete World hold identity is missing or oversized");
    }
    if (!Arrays.equals(
        holdBytes,
        WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(holdBytes).canonicalBytes())) {
      throw new IllegalArgumentException("World hold identity must be canonical");
    }
    if (!tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .tenantId()
            .equals(worldHoldIdentity.request().canonicalTenantId())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace()
            .equals(worldHoldIdentity.request().targetNamespace())) {
      throw new IllegalArgumentException("Original tuple and complete World hold scope differ");
    }
  }

  @Override
  public byte[] originalPostAuthorizationTuple() {
    return originalPostAuthorizationTuple.clone();
  }

  /** Requires the configured target namespace to match both complete original values. */
  public void requireTargetNamespace(String targetNamespace) {
    if (!tupleTargetNamespace().equals(targetNamespace)
        || !worldHoldIdentity.request().targetNamespace().equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Target namespace differs from the original tuple or hold");
    }
  }

  private String tupleTargetNamespace() {
    return StartSessionPostAuthorizationExecutionTuple.decode(originalPostAuthorizationTuple)
        .preAuthorizationTuple()
        .action()
        .scope()
        .targetNamespace();
  }

  private static UUID requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a non-nil UUID");
    }
    return value;
  }
}
