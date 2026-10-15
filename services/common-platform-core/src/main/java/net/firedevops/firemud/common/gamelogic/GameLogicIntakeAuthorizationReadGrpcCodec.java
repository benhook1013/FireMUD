package net.firedevops.firemud.common.gamelogic;

import com.google.protobuf.ByteString;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadHeldGameLogicIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldGameLogicIntakeAuthorizationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Exact closed HELD wire mapping; authentication precedes decoding at the receiver. */
public final class GameLogicIntakeAuthorizationReadGrpcCodec {
  private GameLogicIntakeAuthorizationReadGrpcCodec() {}

  public static ReadHeldGameLogicIntakeAuthorizationRequest toRequest(
      GameLogicIntakeAuthorizationReadEvidence.Request value) {
    return ReadHeldGameLogicIntakeAuthorizationRequest.newBuilder()
        .setSchemaVersion(value.schemaVersion())
        .setTargetNamespace(value.targetNamespace())
        .setReadRequestId(value.readRequestId().toString())
        .setOriginalIntakeAuthorization(ByteString.copyFrom(value.binding().canonicalBytes()))
        .setIntakeAuthorizationDigest(value.binding().digest())
        .build();
  }

  public static GameLogicIntakeAuthorizationReadEvidence.Request fromRequest(
      ReadHeldGameLogicIntakeAuthorizationRequest value) {
    if (!value.getUnknownFields().asMap().isEmpty())
      throw new IllegalArgumentException("Unknown intake read fields");
    DraftAuthorizationFenceBinding.canonicalUuid(value.getReadRequestId());
    var binding =
        GameLogicIntakeAuthorizationBinding.fromStored(
            value.getOriginalIntakeAuthorization().toByteArray());
    if (!binding.digest().equals(value.getIntakeAuthorizationDigest()))
      throw new IllegalArgumentException("Changed intake digest");
    return new GameLogicIntakeAuthorizationReadEvidence.Request(
        value.getSchemaVersion(),
        value.getTargetNamespace(),
        UUID.fromString(value.getReadRequestId()),
        binding);
  }

  public static ReadHeldGameLogicIntakeAuthorizationResponse toHeldResponse(
      GameLogicIntakeAuthorizationReadEvidence.Request request) {
    return ReadHeldGameLogicIntakeAuthorizationResponse.newBuilder()
        .setRequest(toRequest(request))
        .setHeld(true)
        .build();
  }

  public static GameLogicIntakeAuthorizationReadEvidence fromResponse(
      GameLogicIntakeAuthorizationReadEvidence.Request request,
      ReadHeldGameLogicIntakeAuthorizationResponse response) {
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || !toRequest(request).equals(response.getRequest())
        || !response.getHeld())
      throw new IllegalArgumentException("Exact held intake response required");
    return new GameLogicIntakeAuthorizationReadEvidence(request);
  }
}
