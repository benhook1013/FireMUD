package net.firedevops.firemud.common.gamelogic;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact closed producer request and bounded recovery result, without credential material. */
public final class GameLogicIntakeAuthorizationEvidence {
  public static final int MAX_SELECTION_BYTES = 4 * 1024 * 1024;

  private GameLogicIntakeAuthorizationEvidence() {}

  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID transportRequestId,
      UUID intakeRequestId,
      byte[] selectedDraftBinding) {
    public Request {
      if (schemaVersion != 1
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || transportRequestId == null
          || intakeRequestId == null
          || transportRequestId.equals(intakeRequestId)) throw invalid();
      DraftAuthorizationFenceBinding.requireUuid(transportRequestId);
      DraftAuthorizationFenceBinding.requireUuid(intakeRequestId);
      if (selectedDraftBinding == null
          || selectedDraftBinding.length == 0
          || selectedDraftBinding.length > MAX_SELECTION_BYTES) throw invalid();
      selectedDraftBinding = selectedDraftBinding.clone();
      decodeSelection(selectedDraftBinding);
    }

    public static Request create(
        String namespace, UUID intakeRequestId, DraftCommitBinding selected) {
      Objects.requireNonNull(selected);
      return new Request(
          1, namespace, UUID.randomUUID(), intakeRequestId, selected.canonicalBytes());
    }

    @Override
    public byte[] selectedDraftBinding() {
      return selectedDraftBinding.clone();
    }

    public DraftCommitBinding selected() {
      return decodeSelection(selectedDraftBinding);
    }

    @Override
    public String toString() {
      return "GameLogicIntakeAuthorization.Request[redacted]";
    }
  }

  public enum Outcome {
    RESERVED,
    FINALIZED,
    ABORTED
  }

  public record Result(
      Request request,
      Outcome outcome,
      Optional<GameLogicIntakeSourceReadScope> sourceScope,
      Optional<GameLogicIntakeAuthorizationBinding> authorization) {
    public Result {
      Objects.requireNonNull(request);
      Objects.requireNonNull(outcome);
      sourceScope = Objects.requireNonNull(sourceScope);
      authorization = Objects.requireNonNull(authorization);
      sourceScope.ifPresent(
          scope -> {
            if (!request.targetNamespace().equals(scope.targetNamespace())
                || !request.intakeRequestId().equals(scope.intakeRequestId())
                || !request.selected().equals(scope.selected())) throw invalid();
          });
      authorization.ifPresent(
          binding -> {
            if (!request.intakeRequestId().equals(binding.intakeRequestId())
                || !request.selected().equals(binding.source().binding())) throw invalid();
          });
      if (sourceScope.isPresent() && authorization.isPresent()) {
        var scope = sourceScope.orElseThrow();
        var binding = authorization.orElseThrow();
        if (!scope.operationId().equals(binding.operationId())
            || !scope.fenceId().equals(binding.fenceId())
            || !scope.intakeRequestId().equals(binding.intakeRequestId())
            || !scope.actorAccountId().equals(binding.actorAccountId())) throw invalid();
      }
      if (outcome == Outcome.FINALIZED && authorization.isEmpty()) throw invalid();
      if (outcome != Outcome.FINALIZED && (sourceScope.isEmpty() || authorization.isPresent()))
        throw invalid();
    }

    public static Result authorized(
        Request request, GameLogicIntakeAuthorizationBinding authorization) {
      return new Result(request, Outcome.FINALIZED, Optional.empty(), Optional.of(authorization));
    }
  }

  static DraftCommitBinding decodeSelection(byte[] bytes) {
    try {
      String json = new String(bytes, StandardCharsets.UTF_8);
      var selection =
          DraftCommitBinding.fromStored(json, DraftAuthorizationFenceBinding.digest(bytes));
      if (!Arrays.equals(bytes, selection.canonicalBytes())) throw invalid();
      return selection;
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid Game Logic intake authorization evidence");
  }
}
