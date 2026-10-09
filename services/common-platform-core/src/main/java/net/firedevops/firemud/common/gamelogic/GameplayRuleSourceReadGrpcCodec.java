package net.firedevops.firemud.common.gamelogic;

import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceResponse;

/** Closed wire and exact request/source correlation. Decode only after peer authentication. */
public final class GameplayRuleSourceReadGrpcCodec {
  private GameplayRuleSourceReadGrpcCodec() {}

  public static ReadSelectedGameplayRuleSourceRequest toRequest(
      GameplayRuleSourceReadEvidence.Request value) {
    return ReadSelectedGameplayRuleSourceRequest.newBuilder()
        .setSchemaVersion(value.schemaVersion())
        .setTargetNamespace(value.targetNamespace())
        .setReadRequestId(value.readRequestId().toString())
        .setGameDesignBindingJson(value.binding().canonicalJson())
        .setGameDesignBindingDigest(value.binding().digest())
        .build();
  }

  public static GameplayRuleSourceReadEvidence.Request fromRequest(
      ReadSelectedGameplayRuleSourceRequest value) {
    if (!value.getUnknownFields().asMap().isEmpty())
      throw new IllegalArgumentException("Unknown source fields");
    DraftAuthorizationFenceBinding.canonicalUuid(value.getReadRequestId());
    return new GameplayRuleSourceReadEvidence.Request(
        value.getSchemaVersion(),
        value.getTargetNamespace(),
        UUID.fromString(value.getReadRequestId()),
        DraftCommitBinding.fromStored(
            value.getGameDesignBindingJson(), value.getGameDesignBindingDigest()));
  }

  public static ReadSelectedGameplayRuleSourceResponse toResponse(
      GameplayRuleSourceReadEvidence evidence) {
    return ReadSelectedGameplayRuleSourceResponse.newBuilder()
        .setRequest(toRequest(evidence.request()))
        .setCompleteSourceSnapshotJson(evidence.source().snapshotJson())
        .setCompleteSourceSnapshotDigest(evidence.source().digest())
        .build();
  }

  public static GameplayRuleSourceReadEvidence fromResponse(
      GameplayRuleSourceReadEvidence.Request request,
      ReadSelectedGameplayRuleSourceResponse response) {
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || !toRequest(request).equals(response.getRequest()))
      throw new IllegalArgumentException("Changed source read correlation");
    var source = new GameplayRuleSelectedSource(response.getCompleteSourceSnapshotJson());
    if (!source.digest().equals(response.getCompleteSourceSnapshotDigest()))
      throw new IllegalArgumentException("Changed source digest");
    return new GameplayRuleSourceReadEvidence(request, source);
  }
}
