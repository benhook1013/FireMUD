package net.firedevops.firemud.common.account.startsession;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;

/**
 * Exact caller-carried World statement for Account's original StartSession activation protection.
 */
public record AccountStartSessionActivationProtectionAcquisitionInput(
    byte[] originalPostAuthorizationTuple,
    UUID gameSessionOwnerMutationId,
    UUID gameSessionOwnerAttemptId,
    long gameSessionOwnerFence,
    WorldCanonicalInstanceActivation.Request worldActivationRequest) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public AccountStartSessionActivationProtectionAcquisitionInput {
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
      throw new IllegalArgumentException(
          "Original StartSession tuple must be exact canonical bytes");
    }
    if (!"StartSession".equals(tuple.preAuthorizationTuple().actionFamily())
        || !"game-session-service".equals(tuple.preAuthorizationTuple().targetOwner())) {
      throw new IllegalArgumentException("Original human StartSession tuple is required");
    }
    gameSessionOwnerMutationId =
        requireNonNil(gameSessionOwnerMutationId, "Game Session owner mutation ID");
    gameSessionOwnerAttemptId =
        requireNonNil(gameSessionOwnerAttemptId, "Game Session owner attempt ID");
    if (gameSessionOwnerFence <= 0L) {
      throw new IllegalArgumentException("Game Session owner fence must be positive");
    }
    Objects.requireNonNull(worldActivationRequest, "exact World activation request is required");

    byte[] preparingBytes = worldActivationRequest.preparingEvidenceBytes();
    if (preparingBytes.length == 0
        || preparingBytes.length
            > AccountStartSessionActivationProtectionRequest.MAX_WORLD_PREPARING_EVIDENCE_BYTES) {
      throw new IllegalArgumentException("World PREPARING evidence is missing or oversized");
    }
    WorldCanonicalInstanceLifecycleEvidence preparing =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(preparingBytes);
    if (!Arrays.equals(preparingBytes, preparing.canonicalBytes())) {
      throw new IllegalArgumentException("World PREPARING evidence must be exact canonical bytes");
    }
    worldActivationRequest =
        new WorldCanonicalInstanceActivation.Request(
            worldActivationRequest.activationRequestId(), preparing);

    var scope = tuple.preAuthorizationTuple().action().scope();
    var world = preparing.request();
    if (!scope.targetNamespace().equals(world.targetNamespace())
        || !scope.tenantId().equals(world.canonicalTenantId())
        || !tuple.controlPlaneRequestId().equals(world.controlPlaneRequestId())
        || !tuple
            .controlPlaneRequestId()
            .equals(preparing.launchBinding().descriptor().controlPlaneRequestId())) {
      throw new IllegalArgumentException(
          "Original tuple and complete World PREPARING identity differ");
    }
    // The authored descriptor does not allocate a runtime instance. Account must separately
    // prove the instance identity against its authenticated read of the actual GS launch.
  }

  @Override
  public byte[] originalPostAuthorizationTuple() {
    return originalPostAuthorizationTuple.clone();
  }

  /** Requires the configured workload namespace to match both complete original values. */
  public void requireTargetNamespace(String targetNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)
        || !StartSessionPostAuthorizationExecutionTuple.decode(originalPostAuthorizationTuple)
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace()
            .equals(targetNamespace)
        || !worldActivationRequest
            .preparing()
            .request()
            .targetNamespace()
            .equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Target namespace differs from the original tuple or World PREPARING evidence");
    }
  }

  private static UUID requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label + " is required");
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
    return value;
  }
}
