package net.firedevops.firemud.common.gamelogic;

import com.google.protobuf.ByteString;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionRequest;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Closed canonical proof mapping; decode only after authenticating GD. */
public final class GameLogicIntakeSourceReadProtoCodec {
  public static final int MAX_WIRE_BYTES = 24 * 1024 * 1024;

  private GameLogicIntakeSourceReadProtoCodec() {}

  public static GameLogicIntakeSourcePermissionRequest toRequest(
      GameLogicIntakeSourceReadEvidence.Request request) {
    var builder =
        GameLogicIntakeSourcePermissionRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setIntendedReader(request.intendedReader())
            .setPurpose(request.purpose())
            .setAuthorizationProofDigest(request.proof().digest());
    var bytes = ByteString.copyFrom(request.proof().canonicalBytes());
    if (request.proof() instanceof GameplayRuleSourceReadEvidence.Preliminary)
      builder.setPreliminarySourceScope(bytes);
    else builder.setFinalizedIntakeAuthorization(bytes);
    var wire = builder.build();
    if (wire.getSerializedSize() > MAX_WIRE_BYTES)
      throw new IllegalArgumentException("Oversized Account source permission request");
    return wire;
  }

  public static GameLogicIntakeSourceReadEvidence.Request fromRequest(
      GameLogicIntakeSourcePermissionRequest request) {
    if (request == null
        || request.getSerializedSize() > MAX_WIRE_BYTES
        || !request.getUnknownFields().asMap().isEmpty())
      throw new IllegalArgumentException("Unknown or oversized permission fields");
    DraftAuthorizationFenceBinding.canonicalUuid(request.getReadRequestId());
    GameplayRuleSourceReadEvidence.Proof proof =
        switch (request.getAuthorizationProofCase()) {
          case PRELIMINARY_SOURCE_SCOPE ->
              new GameplayRuleSourceReadEvidence.Preliminary(
                  GameLogicIntakeSourceReadScope.fromStored(
                      request.getPreliminarySourceScope().toByteArray()));
          case FINALIZED_INTAKE_AUTHORIZATION ->
              new GameplayRuleSourceReadEvidence.Finalized(
                  GameLogicIntakeAuthorizationBinding.fromStored(
                      request.getFinalizedIntakeAuthorization().toByteArray()));
          default -> throw new IllegalArgumentException("Source permission proof required");
        };
    if (!proof.digest().equals(request.getAuthorizationProofDigest()))
      throw new IllegalArgumentException("Changed proof digest");
    return new GameLogicIntakeSourceReadEvidence.Request(
        request.getSchemaVersion(),
        request.getTargetNamespace(),
        UUID.fromString(request.getReadRequestId()),
        request.getIntendedReader(),
        request.getPurpose(),
        proof);
  }

  public static GameLogicIntakeSourcePermissionResponse toResponse(
      GameLogicIntakeSourceReadEvidence.Request request) {
    var wire =
        GameLogicIntakeSourcePermissionResponse.newBuilder()
            .setRequest(toRequest(request))
            .setPermitted(true)
            .build();
    if (wire.getSerializedSize() > MAX_WIRE_BYTES)
      throw new IllegalArgumentException("Oversized Account source permission response");
    return wire;
  }

  public static GameLogicIntakeSourceReadEvidence fromResponse(
      GameLogicIntakeSourceReadEvidence.Request request,
      GameLogicIntakeSourcePermissionResponse response) {
    if (response == null
        || response.getSerializedSize() > MAX_WIRE_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || !toRequest(request).equals(response.getRequest())
        || !response.getPermitted())
      throw new IllegalArgumentException("Exact Account source permission confirmation required");
    return new GameLogicIntakeSourceReadEvidence(request);
  }
}
