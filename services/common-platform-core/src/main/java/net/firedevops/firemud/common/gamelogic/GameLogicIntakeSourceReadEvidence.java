package net.firedevops.firemud.common.gamelogic;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact Account owner confirmation, never a locally minted permission. */
public record GameLogicIntakeSourceReadEvidence(Request request) {
  public GameLogicIntakeSourceReadEvidence {
    Objects.requireNonNull(request);
  }

  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      String intendedReader,
      String purpose,
      GameplayRuleSourceReadEvidence.Proof proof) {
    public Request {
      if (schemaVersion != 1 || !GrpcPeerIdentity.isValidNamespace(targetNamespace))
        throw new IllegalArgumentException(
            "Canonical source permission schema and namespace required");
      DraftAuthorizationFenceBinding.requireUuid(readRequestId);
      Objects.requireNonNull(proof);
      String service =
          proof instanceof GameplayRuleSourceReadEvidence.Preliminary
              ? "account-service"
              : "game-logic-service";
      if (!("spiffe://firemud/ns/" + targetNamespace + "/sa/" + service).equals(intendedReader)
          || !GameLogicIntakeSourceReadScope.PURPOSE.equals(purpose))
        throw new IllegalArgumentException("Exact reader and closed purpose required");
      if (proof instanceof GameplayRuleSourceReadEvidence.Preliminary preliminary
          && (!targetNamespace.equals(preliminary.scope().targetNamespace())
              || !intendedReader.equals(preliminary.scope().intendedReader())
              || !purpose.equals(preliminary.scope().purpose())))
        throw new IllegalArgumentException("Preliminary scope differs");
      if (readRequestId.equals(proof.operationId())
          || readRequestId.equals(proof.intakeRequestId())
          || readRequestId.equals(proof.fenceId())
          || readRequestId.equals(proof.selected().requestId())
          || readRequestId.equals(proof.selected().commitId()))
        throw new IllegalArgumentException("Independent read correlation required");
    }

    public static Request create(
        String namespace,
        String reader,
        String purpose,
        GameplayRuleSourceReadEvidence.Proof proof) {
      return new Request(1, namespace, UUID.randomUUID(), reader, purpose, proof);
    }
  }
}
