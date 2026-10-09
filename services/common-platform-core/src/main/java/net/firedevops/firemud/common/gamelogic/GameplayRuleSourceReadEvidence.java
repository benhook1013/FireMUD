package net.firedevops.firemud.common.gamelogic;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact authenticated GD complete-source read; never a mutation authorization. */
public record GameplayRuleSourceReadEvidence(Request request, GameplayRuleSelectedSource source) {
  public GameplayRuleSourceReadEvidence {
    Objects.requireNonNull(request);
    Objects.requireNonNull(source);
    if (!request.binding().equals(source.binding()))
      throw new IllegalArgumentException("GD source differs from selected complete binding");
  }

  public record Request(
      int schemaVersion, String targetNamespace, UUID readRequestId, DraftCommitBinding binding) {
    public Request {
      if (schemaVersion != 1 || !GrpcPeerIdentity.isValidNamespace(targetNamespace))
        throw new IllegalArgumentException("Supported source schema and namespace required");
      DraftAuthorizationFenceBinding.requireUuid(readRequestId);
      Objects.requireNonNull(binding);
      if (readRequestId.equals(binding.requestId()) || readRequestId.equals(binding.commitId()))
        throw new IllegalArgumentException("Separate source read correlation required");
    }

    public static Request create(String namespace, DraftCommitBinding binding) {
      return new Request(1, namespace, UUID.randomUUID(), binding);
    }
  }
}
