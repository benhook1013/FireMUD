package net.firedevops.firemud.common.authoring;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact producer response obtained from the strict Account client; shape alone is not authority.
 */
public final class AccountOriginalDraftOrderEvidence {
  private final int schemaVersion;
  private final String targetNamespace;
  private final UUID requestId;
  private final byte[] originalAccountBinding;

  AccountOriginalDraftOrderEvidence(AccountOriginalDraftOrderGrpcCodec.Request request) {
    Objects.requireNonNull(request);
    schemaVersion = request.schemaVersion();
    targetNamespace = request.targetNamespace();
    requestId = request.requestId();
    originalAccountBinding = request.originalAccountBinding();
  }

  public boolean matches(AccountOriginalDraftOrderGrpcCodec.Request request) {
    return request != null
        && schemaVersion == request.schemaVersion()
        && targetNamespace.equals(request.targetNamespace())
        && requestId.equals(request.requestId())
        && Arrays.equals(originalAccountBinding, request.originalAccountBinding());
  }

  public DraftAuthorizationFenceBinding original() {
    return DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
  }

  @Override
  public String toString() {
    return "AccountOriginalDraftOrderEvidence[redacted]";
  }
}
