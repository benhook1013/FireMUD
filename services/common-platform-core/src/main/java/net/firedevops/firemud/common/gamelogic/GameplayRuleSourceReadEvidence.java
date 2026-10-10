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
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      DraftCommitBinding binding,
      Proof proof,
      String purpose) {
    public Request {
      if (schemaVersion != 1 || !GrpcPeerIdentity.isValidNamespace(targetNamespace))
        throw new IllegalArgumentException("Supported source schema and namespace required");
      DraftAuthorizationFenceBinding.requireUuid(readRequestId);
      Objects.requireNonNull(binding);
      Objects.requireNonNull(proof);
      if (!GameLogicIntakeSourceReadScope.PURPOSE.equals(purpose)
          || !binding.equals(proof.selected()))
        throw new IllegalArgumentException("Exact source proof and closed purpose required");
      if (proof instanceof Preliminary preliminary
          && !targetNamespace.equals(preliminary.scope().targetNamespace()))
        throw new IllegalArgumentException("Source proof namespace differs");
      if (readRequestId.equals(binding.requestId()) || readRequestId.equals(binding.commitId()))
        throw new IllegalArgumentException("Separate source read correlation required");
      if (readRequestId.equals(proof.operationId())
          || readRequestId.equals(proof.intakeRequestId())
          || readRequestId.equals(proof.fenceId()))
        throw new IllegalArgumentException("Separate intake read correlation required");
    }

    public static Request forAccountSourceScope(
        String namespace, GameLogicIntakeSourceReadScope scope) {
      return new Request(
          1,
          namespace,
          UUID.randomUUID(),
          scope.selected(),
          new Preliminary(scope),
          GameLogicIntakeSourceReadScope.PURPOSE);
    }

    public static Request forFinalizedIntake(
        String namespace, GameLogicIntakeAuthorizationBinding authorization) {
      return new Request(
          1,
          namespace,
          UUID.randomUUID(),
          authorization.source().binding(),
          new Finalized(authorization),
          GameLogicIntakeSourceReadScope.PURPOSE);
    }
  }

  public sealed interface Proof permits Preliminary, Finalized {
    DraftCommitBinding selected();

    byte[] canonicalBytes();

    String digest();

    UUID operationId();

    UUID fenceId();

    UUID intakeRequestId();
  }

  public record Preliminary(GameLogicIntakeSourceReadScope scope) implements Proof {
    public Preliminary {
      Objects.requireNonNull(scope);
    }

    public DraftCommitBinding selected() {
      return scope.selected();
    }

    public byte[] canonicalBytes() {
      return scope.canonicalBytes();
    }

    public String digest() {
      return scope.digest();
    }

    public UUID operationId() {
      return scope.operationId();
    }

    public UUID fenceId() {
      return scope.fenceId();
    }

    public UUID intakeRequestId() {
      return scope.intakeRequestId();
    }
  }

  public record Finalized(GameLogicIntakeAuthorizationBinding authorization) implements Proof {
    public Finalized {
      Objects.requireNonNull(authorization);
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || (other instanceof Finalized finalized
              && java.util.Arrays.equals(canonicalBytes(), finalized.canonicalBytes()));
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(canonicalBytes());
    }

    public DraftCommitBinding selected() {
      return authorization.source().binding();
    }

    public byte[] canonicalBytes() {
      return authorization.canonicalBytes();
    }

    public String digest() {
      return authorization.digest();
    }

    public UUID operationId() {
      return authorization.operationId();
    }

    public UUID fenceId() {
      return authorization.fenceId();
    }

    public UUID intakeRequestId() {
      return authorization.intakeRequestId();
    }
  }
}
