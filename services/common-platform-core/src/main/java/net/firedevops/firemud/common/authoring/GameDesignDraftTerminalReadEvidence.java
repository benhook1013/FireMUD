package net.firedevops.firemud.common.authoring;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Validated Game Design terminal-read value; producer authentication comes only from transport. */
public record GameDesignDraftTerminalReadEvidence(
    Request request, Optional<DraftAuthorizationFenceBinding.OwnerReadback> ownerReadback) {
  public GameDesignDraftTerminalReadEvidence {
    Objects.requireNonNull(request, "request");
    ownerReadback = Objects.requireNonNull(ownerReadback, "ownerReadback");
    ownerReadback.ifPresent(
        value -> {
          if (value.owner() != DraftAuthorizationFenceBinding.Owner.GAME_DESIGN
              || (value.outcome() != DraftAuthorizationFenceBinding.Outcome.COMMITTED
                  && value.outcome()
                      != DraftAuthorizationFenceBinding.Outcome.DEFINITIVELY_ABORTED)) {
            throw new IllegalArgumentException(
                "Game Design readback must be a committed or definitive-abort owner result");
          }
          value.requireBinding(request.accountBinding());
          if (!Arrays.equals(value.fullBinding(), request.originalAccountBinding())
              || value.result().length == 0) {
            throw new IllegalArgumentException(
                "Game Design readback differs from the original binding or lacks result bytes");
          }
        });
  }

  /** The canonical original Account binding and a separate fresh caller-owned read identity. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      byte[] originalAccountBinding) {
    public static final int SCHEMA_VERSION = 1;

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported Game Design terminal read schema version");
      }
      if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical Game Design workload namespace is required");
      }
      requireNonNil(readRequestId, "readRequestId");
      if (originalAccountBinding == null || originalAccountBinding.length == 0) {
        throw new IllegalArgumentException("Complete original Account binding is required");
      }
      originalAccountBinding = originalAccountBinding.clone();
      DraftAuthorizationFenceBinding binding =
          DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
      if (readRequestId.equals(binding.operationId())
          || readRequestId.equals(binding.requestId())
          || readRequestId.equals(binding.commitId())
          || readRequestId.equals(binding.fenceId())) {
        throw new IllegalArgumentException("Game Design terminal read requires a fresh identity");
      }
    }

    public static Request create(String targetNamespace, byte[] originalAccountBinding) {
      DraftAuthorizationFenceBinding binding =
          DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
      UUID readRequestId;
      do {
        readRequestId = UUID.randomUUID();
      } while (readRequestId.equals(binding.operationId())
          || readRequestId.equals(binding.requestId())
          || readRequestId.equals(binding.commitId())
          || readRequestId.equals(binding.fenceId()));
      return new Request(SCHEMA_VERSION, targetNamespace, readRequestId, originalAccountBinding);
    }

    @Override
    public byte[] originalAccountBinding() {
      return originalAccountBinding.clone();
    }

    public DraftAuthorizationFenceBinding accountBinding() {
      return DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && readRequestId.equals(that.readRequestId)
          && Arrays.equals(originalAccountBinding, that.originalAccountBinding);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(schemaVersion, targetNamespace, readRequestId);
      return 31 * result + Arrays.hashCode(originalAccountBinding);
    }

    private static void requireNonNil(UUID value, String label) {
      if (value == null || new UUID(0L, 0L).equals(value)) {
        throw new IllegalArgumentException("Non-nil " + label + " is required");
      }
    }
  }
}
