package net.firedevops.firemud.common.gamelogic;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GameLogicIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.GameLogicIntakeAuthorizationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Closed producer transport codec. The creator credential is never encoded in protobuf. */
public final class GameLogicIntakeAuthorizationGrpcCodec {
  public static final int MAX_WIRE_BYTES = 24 * 1024 * 1024;
  private static final int MAX_SCOPE_BYTES = 4 * 1024 * 1024;
  private static final int MAX_AUTHORIZATION_BYTES = 4 * 1024 * 1024;

  private GameLogicIntakeAuthorizationGrpcCodec() {}

  public static GameLogicIntakeAuthorizationRequest toRequest(
      GameLogicIntakeAuthorizationEvidence.Request request) {
    var wire =
        GameLogicIntakeAuthorizationRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setTransportRequestId(request.transportRequestId().toString())
            .setIntakeRequestId(request.intakeRequestId().toString())
            .setSelectedDraftBinding(ByteString.copyFrom(request.selectedDraftBinding()))
            .build();
    requireWireSize(wire.getSerializedSize());
    return wire;
  }

  /** Invoke only after authenticating the exact same-namespace Game Design workload. */
  public static GameLogicIntakeAuthorizationEvidence.Request fromRequest(
      GameLogicIntakeAuthorizationRequest wire) {
    try {
      noUnknown(wire);
      requireWireSize(wire.getSerializedSize());
      if (wire.getSelectedDraftBinding().size()
          > GameLogicIntakeAuthorizationEvidence.MAX_SELECTION_BYTES) throw invalid();
      var request =
          new GameLogicIntakeAuthorizationEvidence.Request(
              wire.getSchemaVersion(),
              wire.getTargetNamespace(),
              canonicalUuid(wire.getTransportRequestId()),
              canonicalUuid(wire.getIntakeRequestId()),
              wire.getSelectedDraftBinding().toByteArray());
      if (!toRequest(request).equals(wire)) throw invalid();
      return request;
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  public static GameLogicIntakeAuthorizationResponse toResponse(
      GameLogicIntakeAuthorizationEvidence.Result result) {
    var builder =
        GameLogicIntakeAuthorizationResponse.newBuilder()
            .setRequest(toRequest(result.request()))
            .setOutcome(toWire(result.outcome()));
    result
        .sourceScope()
        .ifPresent(
            scope ->
                builder.setPreliminarySourceScope(ByteString.copyFrom(scope.canonicalBytes())));
    result
        .authorization()
        .ifPresent(
            binding ->
                builder.setFinalizedIntakeAuthorization(
                    ByteString.copyFrom(binding.canonicalBytes())));
    var response = builder.build();
    requireWireSize(response.getSerializedSize());
    return response;
  }

  public static GameLogicIntakeAuthorizationEvidence.Result fromResponse(
      GameLogicIntakeAuthorizationEvidence.Request request,
      GameLogicIntakeAuthorizationResponse wire) {
    try {
      noUnknown(wire);
      requireWireSize(wire.getSerializedSize());
      if (!wire.hasRequest() || !toRequest(request).equals(wire.getRequest())) throw invalid();
      Optional<GameLogicIntakeSourceReadScope> scope = Optional.empty();
      Optional<GameLogicIntakeAuthorizationBinding> authorization = Optional.empty();
      if (wire.getPreliminarySourceScope().size() > MAX_SCOPE_BYTES
          || wire.getFinalizedIntakeAuthorization().size() > MAX_AUTHORIZATION_BYTES)
        throw invalid();
      if (!wire.getPreliminarySourceScope().isEmpty())
        scope =
            Optional.of(
                GameLogicIntakeSourceReadScope.fromStored(
                    wire.getPreliminarySourceScope().toByteArray()));
      if (!wire.getFinalizedIntakeAuthorization().isEmpty())
        authorization =
            Optional.of(
                GameLogicIntakeAuthorizationBinding.fromStored(
                    wire.getFinalizedIntakeAuthorization().toByteArray()));
      return new GameLogicIntakeAuthorizationEvidence.Result(
          request, fromWire(wire.getOutcome()), scope, authorization);
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static UUID canonicalUuid(String value) {
    DraftAuthorizationFenceBinding.canonicalUuid(value);
    return UUID.fromString(value);
  }

  private static GameLogicIntakeAuthorizationResponse.Outcome toWire(
      GameLogicIntakeAuthorizationEvidence.Outcome outcome) {
    return switch (outcome) {
      case RESERVED -> GameLogicIntakeAuthorizationResponse.Outcome.RESERVED;
      case FINALIZED -> GameLogicIntakeAuthorizationResponse.Outcome.FINALIZED;
      case ABORTED -> GameLogicIntakeAuthorizationResponse.Outcome.ABORTED;
    };
  }

  private static GameLogicIntakeAuthorizationEvidence.Outcome fromWire(
      GameLogicIntakeAuthorizationResponse.Outcome outcome) {
    return switch (outcome) {
      case RESERVED -> GameLogicIntakeAuthorizationEvidence.Outcome.RESERVED;
      case FINALIZED -> GameLogicIntakeAuthorizationEvidence.Outcome.FINALIZED;
      case ABORTED -> GameLogicIntakeAuthorizationEvidence.Outcome.ABORTED;
      default -> throw invalid();
    };
  }

  private static void noUnknown(Message wire) {
    if (wire == null || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
  }

  private static void requireWireSize(int size) {
    if (size > MAX_WIRE_BYTES) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid Game Logic intake authorization transport");
  }
}
