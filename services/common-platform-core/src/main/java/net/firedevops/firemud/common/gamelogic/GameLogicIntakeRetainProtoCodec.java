package net.firedevops.firemud.common.gamelogic;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeRequest;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeResponse;

/** Closed request echo and canonical original-terminal mapping for the retain transport. */
public final class GameLogicIntakeRetainProtoCodec {
  public static final int MAX_WIRE_BYTES = GameLogicGameplayRuleIntakeTerminal.MAX_WIRE_BYTES;

  private GameLogicIntakeRetainProtoCodec() {}

  public static RetainGameplayRuleIntakeRequest toRequest(
      GameLogicIntakeRetainEvidence.Request request) {
    var wire =
        RetainGameplayRuleIntakeRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setCorrelationId(request.correlationId().toString())
            .setOriginalIntakeAuthorization(
                ByteString.copyFrom(request.originalAuthorizationBytes()))
            .setIntakeAuthorizationDigest(request.authorizationDigest())
            .build();
    if (wire.getSerializedSize() > MAX_WIRE_BYTES) {
      throw new IllegalArgumentException("Oversized intake retain request");
    }
    return wire;
  }

  /** Call only after authenticating the exact same-namespace Game Design workload. */
  public static GameLogicIntakeRetainEvidence.Request fromRequest(
      RetainGameplayRuleIntakeRequest request) {
    if (request == null
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSerializedSize() > MAX_WIRE_BYTES
        || request.getOriginalIntakeAuthorization().isEmpty()
        || request.getOriginalIntakeAuthorization().size()
            > GameLogicIntakeRetainEvidence.MAX_AUTHORIZATION_BYTES) {
      throw new IllegalArgumentException("Unknown or oversized intake retain request");
    }
    DraftAuthorizationFenceBinding.canonicalUuid(request.getCorrelationId());
    var bytes = request.getOriginalIntakeAuthorization().toByteArray();
    var binding = GameLogicIntakeAuthorizationBinding.fromStored(bytes);
    if (!Arrays.equals(bytes, binding.canonicalBytes())
        || !binding.digest().equals(request.getIntakeAuthorizationDigest())) {
      throw new IllegalArgumentException("Changed original intake authorization");
    }
    return new GameLogicIntakeRetainEvidence.Request(
        request.getSchemaVersion(),
        request.getTargetNamespace(),
        UUID.fromString(request.getCorrelationId()),
        binding);
  }

  public static RetainGameplayRuleIntakeResponse toResponse(
      GameLogicIntakeRetainEvidence evidence) {
    var terminal = evidence.terminal();
    byte[] terminalBytes = terminal.canonicalBytes();
    if (terminalBytes.length > GameLogicGameplayRuleIntakeTerminal.MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException("Oversized original intake terminal");
    }
    var response =
        RetainGameplayRuleIntakeResponse.newBuilder()
            .setRequest(toRequest(evidence.request()))
            .setOriginalTerminal(ByteString.copyFrom(terminalBytes))
            .setTerminalDigest(terminal.digest())
            .build();
    if (response.getSerializedSize() > MAX_WIRE_BYTES) {
      throw new IllegalArgumentException("Oversized intake retain response");
    }
    return response;
  }

  public static GameLogicIntakeRetainEvidence fromResponse(
      GameLogicIntakeRetainEvidence.Request request, RetainGameplayRuleIntakeResponse response) {
    if (response == null
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSerializedSize() > MAX_WIRE_BYTES
        || !response.hasRequest()
        || !response.getRequest().getUnknownFields().asMap().isEmpty()
        || !toRequest(request).equals(response.getRequest())
        || response.getOriginalTerminal().isEmpty()
        || response.getOriginalTerminal().size()
            > GameLogicGameplayRuleIntakeTerminal.MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException("Changed intake retain correlation or terminal size");
    }
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.fromStored(
            response.getOriginalTerminal().toByteArray());
    if (!terminal.digest().equals(response.getTerminalDigest())) {
      throw new IllegalArgumentException("Changed original intake terminal digest");
    }
    return new GameLogicIntakeRetainEvidence(request, terminal);
  }
}
