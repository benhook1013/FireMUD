package net.firedevops.firemud.common.gamelogic;

import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceResponse;

/** Closed wire and exact request/source correlation. Decode only after peer authentication. */
public final class GameplayRuleSourceReadGrpcCodec {
  public static final int MAX_WIRE_BYTES = 24 * 1024 * 1024;

  private GameplayRuleSourceReadGrpcCodec() {}

  public static ReadSelectedGameplayRuleSourceRequest toRequest(
      GameplayRuleSourceReadEvidence.Request value) {
    var builder =
        ReadSelectedGameplayRuleSourceRequest.newBuilder()
            .setSchemaVersion(value.schemaVersion())
            .setTargetNamespace(value.targetNamespace())
            .setReadRequestId(value.readRequestId().toString())
            .setGameDesignBindingJson(value.binding().canonicalJson())
            .setGameDesignBindingDigest(value.binding().digest())
            .setPurpose(value.purpose())
            .setAuthorizationProofDigest(value.proof().digest());
    var bytes = com.google.protobuf.ByteString.copyFrom(value.proof().canonicalBytes());
    if (value.proof() instanceof GameplayRuleSourceReadEvidence.Preliminary)
      builder.setPreliminarySourceScope(bytes);
    else builder.setFinalizedIntakeAuthorization(bytes);
    var wire = builder.build();
    if (wire.getSerializedSize() > MAX_WIRE_BYTES)
      throw new IllegalArgumentException("Oversized GD source request");
    return wire;
  }

  public static GameplayRuleSourceReadEvidence.Request fromRequest(
      ReadSelectedGameplayRuleSourceRequest value) {
    if (value == null
        || value.getSerializedSize() > MAX_WIRE_BYTES
        || !value.getUnknownFields().asMap().isEmpty())
      throw new IllegalArgumentException("Unknown source fields");
    DraftAuthorizationFenceBinding.canonicalUuid(value.getReadRequestId());
    GameplayRuleSourceReadEvidence.Proof proof =
        switch (value.getAuthorizationProofCase()) {
          case PRELIMINARY_SOURCE_SCOPE ->
              new GameplayRuleSourceReadEvidence.Preliminary(
                  GameLogicIntakeSourceReadScope.fromStored(
                      value.getPreliminarySourceScope().toByteArray()));
          case FINALIZED_INTAKE_AUTHORIZATION ->
              new GameplayRuleSourceReadEvidence.Finalized(
                  GameLogicIntakeAuthorizationBinding.fromStored(
                      value.getFinalizedIntakeAuthorization().toByteArray()));
          default -> throw new IllegalArgumentException("Source permission proof required");
        };
    if (!proof.digest().equals(value.getAuthorizationProofDigest()))
      throw new IllegalArgumentException("Changed source proof digest");
    return new GameplayRuleSourceReadEvidence.Request(
        value.getSchemaVersion(),
        value.getTargetNamespace(),
        UUID.fromString(value.getReadRequestId()),
        DraftCommitBinding.fromStored(
            value.getGameDesignBindingJson(), value.getGameDesignBindingDigest()),
        proof,
        value.getPurpose());
  }

  public static ReadSelectedGameplayRuleSourceResponse toResponse(
      GameplayRuleSourceReadEvidence evidence) {
    var wire =
        ReadSelectedGameplayRuleSourceResponse.newBuilder()
            .setRequest(toRequest(evidence.request()))
            .setCompleteSourceSnapshotJson(evidence.source().snapshotJson())
            .setCompleteSourceSnapshotDigest(evidence.source().digest())
            .build();
    if (wire.getSerializedSize() > MAX_WIRE_BYTES)
      throw new IllegalArgumentException("Oversized GD source response");
    return wire;
  }

  public static GameplayRuleSourceReadEvidence fromResponse(
      GameplayRuleSourceReadEvidence.Request request,
      ReadSelectedGameplayRuleSourceResponse response) {
    if (response == null
        || response.getSerializedSize() > MAX_WIRE_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || !toRequest(request).equals(response.getRequest()))
      throw new IllegalArgumentException("Changed source read correlation");
    var source = new GameplayRuleSelectedSource(response.getCompleteSourceSnapshotJson());
    if (!source.digest().equals(response.getCompleteSourceSnapshotDigest()))
      throw new IllegalArgumentException("Changed source digest");
    return new GameplayRuleSourceReadEvidence(request, source);
  }
}
