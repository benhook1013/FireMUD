package net.firedevops.firemud.common.publication;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/**
 * Exact HELD read returned by the authenticated Account mTLS client. Constructing the request or
 * decoding structurally valid bytes is not proof; callers obtain this value from {@link
 * AccountPublicationAuthorizationReadClient} after exact peer and response verification.
 */
public final class AccountPublicationAuthorizationReadEvidence {
  private final Request request;

  AccountPublicationAuthorizationReadEvidence(Request request) {
    this.request = Objects.requireNonNull(request, "request");
  }

  public Request request() {
    return request;
  }

  /** Complete immutable Account authorization binding plus read correlation identity and digest. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      byte[] originalPublicationAuthorizationBinding,
      String publicationAuthorizationDigest) {
    public static final int SCHEMA_VERSION = 1;
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException(
            "Unsupported Account publication-authorization read schema version");
      }
      if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical Account workload namespace is required");
      }
      if (readRequestId == null || NIL_UUID.equals(readRequestId)) {
        throw new IllegalArgumentException("Non-nil read request identity is required");
      }
      if (originalPublicationAuthorizationBinding == null
          || originalPublicationAuthorizationBinding.length == 0) {
        throw new IllegalArgumentException(
            "Complete original Account publication authorization binding is required");
      }
      originalPublicationAuthorizationBinding = originalPublicationAuthorizationBinding.clone();
      AccountPublicationAuthorizationBinding binding =
          AccountPublicationAuthorizationBinding.fromStored(
              originalPublicationAuthorizationBinding);
      if (!Arrays.equals(originalPublicationAuthorizationBinding, binding.canonicalBytes())) {
        throw new IllegalArgumentException(
            "Canonical original Account publication authorization binding is required");
      }
      String expectedDigest = DraftAuthorizationFenceBinding.digest(binding.canonicalBytes());
      if (!expectedDigest.equals(publicationAuthorizationDigest)) {
        throw new IllegalArgumentException(
            "Account publication authorization digest differs from its canonical binding");
      }
      if (readRequestId.equals(binding.operationId()) || readRequestId.equals(binding.fenceId())) {
        throw new IllegalArgumentException(
            "Publication authorization read requires a separate request identity");
      }
    }

    public static Request create(
        String targetNamespace, AccountPublicationAuthorizationBinding binding) {
      Objects.requireNonNull(binding, "binding");
      UUID readRequestId;
      do {
        readRequestId = UUID.randomUUID();
      } while (readRequestId.equals(binding.operationId())
          || readRequestId.equals(binding.fenceId()));
      byte[] canonicalBytes = binding.canonicalBytes();
      return new Request(
          SCHEMA_VERSION,
          targetNamespace,
          readRequestId,
          canonicalBytes,
          DraftAuthorizationFenceBinding.digest(canonicalBytes));
    }

    @Override
    public byte[] originalPublicationAuthorizationBinding() {
      return originalPublicationAuthorizationBinding.clone();
    }

    public AccountPublicationAuthorizationBinding binding() {
      return AccountPublicationAuthorizationBinding.fromStored(
          originalPublicationAuthorizationBinding);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && readRequestId.equals(that.readRequestId)
          && Arrays.equals(
              originalPublicationAuthorizationBinding, that.originalPublicationAuthorizationBinding)
          && publicationAuthorizationDigest.equals(that.publicationAuthorizationDigest);
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(
              schemaVersion, targetNamespace, readRequestId, publicationAuthorizationDigest);
      return 31 * result + Arrays.hashCode(originalPublicationAuthorizationBinding);
    }
  }
}
