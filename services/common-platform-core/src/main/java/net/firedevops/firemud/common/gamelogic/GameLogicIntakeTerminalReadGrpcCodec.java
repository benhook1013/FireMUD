package net.firedevops.firemud.common.gamelogic;

import com.google.protobuf.ByteString;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.gamelogic.v1.ReadGameplayRuleIntakeTerminalRequest;
import net.firedevops.firemud.gamelogic.v1.ReadGameplayRuleIntakeTerminalResponse;

/** Closed exact request echo and canonical original terminal mapping. */
public final class GameLogicIntakeTerminalReadGrpcCodec {
  private GameLogicIntakeTerminalReadGrpcCodec() {}

  public static ReadGameplayRuleIntakeTerminalRequest toRequest(
      GameLogicIntakeTerminalReadEvidence.Request request) {
    return ReadGameplayRuleIntakeTerminalRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalIntakeAuthorization(ByteString.copyFrom(request.binding().canonicalBytes()))
        .setIntakeAuthorizationDigest(request.binding().digest())
        .build();
  }

  public static GameLogicIntakeTerminalReadEvidence.Request fromRequest(
      ReadGameplayRuleIntakeTerminalRequest request) {
    if (!request.getUnknownFields().asMap().isEmpty()
        || request.getOriginalIntakeAuthorization().size() > 4194304)
      throw new IllegalArgumentException("Unknown or oversized terminal lookup");
    DraftAuthorizationFenceBinding.canonicalUuid(request.getReadRequestId());
    var binding =
        GameLogicIntakeAuthorizationBinding.fromStored(
            request.getOriginalIntakeAuthorization().toByteArray());
    if (!binding.digest().equals(request.getIntakeAuthorizationDigest()))
      throw new IllegalArgumentException("Changed original intake digest");
    return new GameLogicIntakeTerminalReadEvidence.Request(
        request.getSchemaVersion(),
        request.getTargetNamespace(),
        UUID.fromString(request.getReadRequestId()),
        binding);
  }

  public static ReadGameplayRuleIntakeTerminalResponse toResponse(
      GameLogicIntakeTerminalReadEvidence evidence) {
    var response =
        ReadGameplayRuleIntakeTerminalResponse.newBuilder()
            .setRequest(toRequest(evidence.request()));
    if (evidence.terminal().isEmpty()) return response.setAbsent(true).build();
    var terminal = evidence.terminal().orElseThrow();
    byte[] bytes = terminal.canonicalBytes();
    if (bytes.length > GameLogicGameplayRuleIntakeTerminal.MAX_CANONICAL_BYTES)
      throw new IllegalArgumentException("Oversized original terminal");
    return response
        .setOriginalTerminal(ByteString.copyFrom(bytes))
        .setTerminalDigest(terminal.digest())
        .build();
  }

  public static GameLogicIntakeTerminalReadEvidence fromResponse(
      GameLogicIntakeTerminalReadEvidence.Request request,
      ReadGameplayRuleIntakeTerminalResponse response) {
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || !toRequest(request).equals(response.getRequest()))
      throw new IllegalArgumentException("Changed terminal read correlation");
    return switch (response.getResultCase()) {
      case ABSENT -> {
        if (!response.getAbsent() || !response.getTerminalDigest().isEmpty())
          throw new IllegalArgumentException("Invalid absence evidence");
        yield new GameLogicIntakeTerminalReadEvidence(request, Optional.empty());
      }
      case ORIGINAL_TERMINAL -> {
        var terminal =
            GameLogicGameplayRuleIntakeTerminal.fromStored(
                response.getOriginalTerminal().toByteArray());
        if (!terminal.digest().equals(response.getTerminalDigest()))
          throw new IllegalArgumentException("Changed terminal digest");
        yield new GameLogicIntakeTerminalReadEvidence(request, Optional.of(terminal));
      }
      default -> throw new IllegalArgumentException("Explicit owner result required");
    };
  }
}
