package net.firedevops.firemud.common.gamelogic;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Lookup tuple only. HELD must come from the authenticated Account read. */
public record GameLogicIntakeAuthorizationReadEvidence(Request request) {
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      GameLogicIntakeAuthorizationBinding binding) {
    public Request {
      if (schemaVersion != 1 || !GrpcPeerIdentity.isValidNamespace(targetNamespace))
        throw new IllegalArgumentException("Supported intake read schema and namespace required");
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
  }
}
