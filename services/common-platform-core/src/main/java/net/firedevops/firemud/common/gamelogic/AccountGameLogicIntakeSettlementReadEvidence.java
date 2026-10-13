package net.firedevops.firemud.common.gamelogic;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact Account receipt response; it grants no fresh intake authority. */
public record AccountGameLogicIntakeSettlementReadEvidence(
    Request request, AccountGameLogicIntakeSettlementEvidence receipt) {
  public AccountGameLogicIntakeSettlementReadEvidence {
    Objects.requireNonNull(request);
    Objects.requireNonNull(receipt);
    if (!request.targetNamespace().equals(receipt.terminal().operation().targetNamespace())
        || !Arrays.equals(
            request.binding().canonicalBytes(), receipt.terminal().authorizationBytes()))
      throw new IllegalArgumentException("Receipt differs from original intake authorization");
  }

  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      GameLogicIntakeAuthorizationBinding binding) {
    public Request {
      if (schemaVersion != 1 || !GrpcPeerIdentity.isValidNamespace(targetNamespace))
        throw new IllegalArgumentException("Supported settlement schema and namespace required");
      DraftAuthorizationFenceBinding.requireUuid(readRequestId);
      Objects.requireNonNull(binding);
      if (readRequestId.equals(binding.operationId())
          || readRequestId.equals(binding.fenceId())
          || readRequestId.equals(binding.intakeRequestId()))
        throw new IllegalArgumentException("Separate transport correlation required");
    }

    public static Request create(String namespace, GameLogicIntakeAuthorizationBinding binding) {
      return new Request(1, namespace, UUID.randomUUID(), binding);
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof Request other
          && schemaVersion == other.schemaVersion
          && targetNamespace.equals(other.targetNamespace)
          && readRequestId.equals(other.readRequestId)
          && Arrays.equals(binding.canonicalBytes(), other.binding.canonicalBytes());
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          schemaVersion, targetNamespace, readRequestId, Arrays.hashCode(binding.canonicalBytes()));
    }
  }
}
