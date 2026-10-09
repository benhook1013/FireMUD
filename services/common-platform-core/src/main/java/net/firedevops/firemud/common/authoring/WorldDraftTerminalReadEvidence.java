package net.firedevops.firemud.common.authoring;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Validated World terminal-read value; peer authentication is supplied only by transport guards.
 */
public record WorldDraftTerminalReadEvidence(
    Request request, Optional<DraftAuthorizationFenceBinding.OwnerReadback> ownerReadback) {
  public WorldDraftTerminalReadEvidence {
    Objects.requireNonNull(request, "request");
    ownerReadback = Objects.requireNonNull(ownerReadback, "ownerReadback");
    ownerReadback.ifPresent(
        value -> {
          if (value.owner() != DraftAuthorizationFenceBinding.Owner.WORLD
              || (value.outcome() != DraftAuthorizationFenceBinding.Outcome.DEFINITIVELY_ABORTED
                  && value.outcome() != DraftAuthorizationFenceBinding.Outcome.COMMITTED)) {
            throw new IllegalArgumentException("World readback must be an exact terminal outcome");
          }
          DraftAuthorizationFenceBinding binding = request.accountBinding();
          value.requireBinding(binding);
          if (!Arrays.equals(value.fullBinding(), request.originalAccountBinding())) {
            throw new IllegalArgumentException("World readback differs from the original binding");
          }
          if (value.outcome() == DraftAuthorizationFenceBinding.Outcome.COMMITTED) {
            WorldDraftTerminalReadGrpcCodec.requireCommittedResult(request, value);
          }
        });
  }

  /** Returns the intake request identity from this validated committed World APPLIED result. */
  public UUID appliedIntakeRequestId() {
    DraftAuthorizationFenceBinding.OwnerReadback readback =
        ownerReadback.orElseThrow(
            () ->
                new IllegalStateException(
                    "World APPLIED intake request requires a committed terminal readback"));
    if (readback.outcome() != DraftAuthorizationFenceBinding.Outcome.COMMITTED) {
      throw new IllegalStateException(
          "World APPLIED intake request requires a committed terminal readback");
    }
    return WorldDraftTerminalReadGrpcCodec.requireCommittedResult(request, readback);
  }

  /** The canonical original Account binding and a separate caller-owned fresh read identity. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      byte[] originalAccountBinding) {
    public static final int SCHEMA_VERSION = 1;

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported World terminal read schema version");
      }
      if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical World workload namespace is required");
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
        throw new IllegalArgumentException("World terminal read requires a fresh request identity");
      }
    }

    public static Request create(String targetNamespace, byte[] originalAccountBinding) {
      return new Request(
          SCHEMA_VERSION, targetNamespace, UUID.randomUUID(), originalAccountBinding);
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
