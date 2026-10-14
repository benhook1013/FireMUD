package net.firedevops.firemud.common.authoring;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact HELD read returned by the authenticated Account mTLS client. The value shape alone is not
 * proof; callers obtain it from {@link DraftCommitOrderReadClient} after exact peer verification.
 */
public final class DraftCommitOrderReadEvidence {
  private final Request request;

  DraftCommitOrderReadEvidence(Request request) {
    this.request = Objects.requireNonNull(request, "request");
  }

  public Request request() {
    return request;
  }

  /** Complete original binding plus one stable request/response correlation identity. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      byte[] originalAccountBinding) {
    public static final int SCHEMA_VERSION = 1;
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported Account COMMIT_ORDER read schema version");
      }
      if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical Account workload namespace is required");
      }
      if (readRequestId == null || NIL_UUID.equals(readRequestId)) {
        throw new IllegalArgumentException("Non-nil read request identity is required");
      }
      if (originalAccountBinding == null || originalAccountBinding.length == 0) {
        throw new IllegalArgumentException("Complete original Account binding is required");
      }
      originalAccountBinding = originalAccountBinding.clone();
      DraftAuthorizationFenceBinding binding =
          DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
      if (!Arrays.equals(originalAccountBinding, binding.canonicalBytes())) {
        throw new IllegalArgumentException("Canonical original Account binding is required");
      }
      if (readRequestId.equals(binding.operationId())
          || readRequestId.equals(binding.requestId())
          || readRequestId.equals(binding.commitId())
          || readRequestId.equals(binding.fenceId())) {
        throw new IllegalArgumentException(
            "COMMIT_ORDER read requires a separate request identity");
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
  }
}
