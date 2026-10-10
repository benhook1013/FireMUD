package net.firedevops.firemud.common.gamelogic;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact request and terminal evidence for one transport attempt to retain selected rule input. */
public record GameLogicIntakeRetainEvidence(
    Request request, GameLogicGameplayRuleIntakeTerminal terminal) {
  public static final int MAX_AUTHORIZATION_BYTES = 4 * 1024 * 1024;

  public GameLogicIntakeRetainEvidence {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(terminal, "terminal");
    if (!request.targetNamespace().equals(terminal.operation().targetNamespace())
        || !Arrays.equals(request.originalAuthorizationBytes(), terminal.authorizationBytes())
        || !request.authorizationDigest().equals(terminal.authorizationDigest())) {
      throw new IllegalArgumentException(
          "Terminal differs from exact namespace or original authorization");
    }
  }

  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID correlationId,
      GameLogicIntakeAuthorizationBinding binding) {
    public Request {
      if (schemaVersion != 1 || !GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Supported retain schema and namespace required");
      }
      DraftAuthorizationFenceBinding.requireUuid(correlationId);
      Objects.requireNonNull(binding, "binding");
      if (binding.canonicalBytes().length > MAX_AUTHORIZATION_BYTES) {
        throw new IllegalArgumentException("Original intake authorization exceeds 4 MiB");
      }
      if (correlationId.equals(binding.operationId())
          || correlationId.equals(binding.fenceId())
          || correlationId.equals(binding.intakeRequestId())) {
        throw new IllegalArgumentException("Separate transport correlation required");
      }
    }

    public static Request create(
        String targetNamespace, GameLogicIntakeAuthorizationBinding binding) {
      UUID correlation;
      do {
        correlation = UUID.randomUUID();
      } while (correlation.equals(binding.operationId())
          || correlation.equals(binding.fenceId())
          || correlation.equals(binding.intakeRequestId()));
      return new Request(1, targetNamespace, correlation, binding);
    }

    public byte[] originalAuthorizationBytes() {
      return binding.canonicalBytes();
    }

    public String authorizationDigest() {
      return binding.digest();
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof Request other
          && schemaVersion == other.schemaVersion
          && targetNamespace.equals(other.targetNamespace)
          && correlationId.equals(other.correlationId)
          && Arrays.equals(originalAuthorizationBytes(), other.originalAuthorizationBytes());
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          schemaVersion,
          targetNamespace,
          correlationId,
          Arrays.hashCode(originalAuthorizationBytes()));
    }
  }
}
