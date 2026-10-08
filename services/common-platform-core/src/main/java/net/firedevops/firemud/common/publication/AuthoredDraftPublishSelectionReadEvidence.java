package net.firedevops.firemud.common.publication;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * Exact immutable SELECTED read returned by the authenticated Game Design client. This attests
 * reserved selection only, not current DRAFT state, actor authority, source holds, World freeze,
 * release/settlement, or runtime admission. Structurally valid bytes alone are not owner evidence.
 */
public final class AuthoredDraftPublishSelectionReadEvidence {
  private final Request request;

  AuthoredDraftPublishSelectionReadEvidence(Request request) {
    this.request = Objects.requireNonNull(request, "request");
  }

  public Request request() {
    return request;
  }

  /** Complete original immutable selection plus read correlation identity and digest. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      byte[] originalSelection,
      String selectionDigest) {
    public static final int SCHEMA_VERSION = 1;
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException(
            "Unsupported selected Draft publication read schema version");
      }
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical Game Design workload namespace is required");
      }
      if (readRequestId == null || NIL_UUID.equals(readRequestId)) {
        throw new IllegalArgumentException("Non-nil read request identity is required");
      }
      if (originalSelection == null || originalSelection.length == 0 || selectionDigest == null) {
        throw new IllegalArgumentException("Complete original selection and digest are required");
      }
      originalSelection = originalSelection.clone();
      AuthoredDraftPublishSelectionBinding binding = decode(originalSelection, selectionDigest);
      if (!Arrays.equals(originalSelection, binding.canonicalBytes())) {
        throw new IllegalArgumentException("Exact canonical original selection bytes are required");
      }
    }

    public static Request create(
        String targetNamespace, AuthoredDraftPublishSelectionBinding binding) {
      Objects.requireNonNull(binding, "binding");
      return new Request(
          SCHEMA_VERSION,
          targetNamespace,
          UUID.randomUUID(),
          binding.canonicalBytes(),
          binding.digest());
    }

    @Override
    public byte[] originalSelection() {
      return originalSelection.clone();
    }

    public AuthoredDraftPublishSelectionBinding binding() {
      return decode(originalSelection, selectionDigest);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && readRequestId.equals(that.readRequestId)
          && Arrays.equals(originalSelection, that.originalSelection)
          && selectionDigest.equals(that.selectionDigest);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(schemaVersion, targetNamespace, readRequestId, selectionDigest)
          + Arrays.hashCode(originalSelection);
    }

    private static AuthoredDraftPublishSelectionBinding decode(byte[] bytes, String digest) {
      try {
        return AuthoredDraftPublishSelectionBinding.fromStored(
            new String(bytes, StandardCharsets.UTF_8), digest);
      } catch (RuntimeException invalid) {
        throw new IllegalArgumentException(
            "Exact canonical original selection and digest required", invalid);
      }
    }
  }
}
