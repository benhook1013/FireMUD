package net.firedevops.firemud.common.authoring;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ClaimOriginalDraftRequest;
import net.firedevops.firemud.account.v1.ClaimOriginalDraftResponse;
import net.firedevops.firemud.account.v1.OriginalDraftOrderStatus;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Closed original-order mapping. Neither credentials nor environment authority enter protobuf. */
public final class AccountOriginalDraftOrderGrpcCodec {
  public static final int MAX_CREDENTIAL_BYTES = 16384;
  public static final int MAX_BINDING_BYTES = 4 * 1024 * 1024;
  public static final int MAX_WIRE_BYTES = MAX_BINDING_BYTES + 1024;
  public static final int MAX_METADATA_BYTES = ((MAX_CREDENTIAL_BYTES + 2) / 3) * 4 + 1024;

  private AccountOriginalDraftOrderGrpcCodec() {}

  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID requestId,
      byte[] originalAccountBinding,
      String originalCreatorCredential) {
    public Request {
      if (schemaVersion != 1
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || requestId == null
          || requestId.equals(new UUID(0, 0))
          || originalAccountBinding == null
          || originalAccountBinding.length == 0
          || originalAccountBinding.length > MAX_BINDING_BYTES) throw invalid();
      AccountOriginalDraftOrderCredentials.Credential.of(originalCreatorCredential);
      originalAccountBinding = originalAccountBinding.clone();
      var original = DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
      if (!Arrays.equals(originalAccountBinding, original.canonicalBytes())
          || requestId.equals(original.operationId())
          || requestId.equals(original.requestId())
          || requestId.equals(original.commitId())
          || requestId.equals(original.fenceId())) throw invalid();
    }

    public static Request create(
        String namespace, DraftAuthorizationFenceBinding original, String credential) {
      UUID correlation;
      do {
        correlation = UUID.randomUUID();
      } while (correlation.equals(original.operationId())
          || correlation.equals(original.requestId())
          || correlation.equals(original.commitId())
          || correlation.equals(original.fenceId()));
      return new Request(1, namespace, correlation, original.canonicalBytes(), credential);
    }

    @Override
    public byte[] originalAccountBinding() {
      return originalAccountBinding.clone();
    }

    public DraftAuthorizationFenceBinding original() {
      return DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
    }

    public boolean exactlyMatches(Request other) {
      return other != null
          && schemaVersion == other.schemaVersion
          && targetNamespace.equals(other.targetNamespace)
          && requestId.equals(other.requestId)
          && Arrays.equals(originalAccountBinding, other.originalAccountBinding)
          && originalCreatorCredential.equals(other.originalCreatorCredential);
    }

    @Override
    public String toString() {
      return "AccountOriginalDraftOrder.Request[redacted]";
    }
  }

  public static ClaimOriginalDraftRequest toRequest(Request request) {
    Objects.requireNonNull(request);
    return ClaimOriginalDraftRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setRequestId(request.requestId().toString())
        .setOriginalAccountBinding(ByteString.copyFrom(request.originalAccountBinding()))
        .build();
  }

  /**
   * Only after same-namespace Game Design workload authentication and protected metadata checks.
   */
  public static Request fromRequest(ClaimOriginalDraftRequest wire, String credential) {
    try {
      noUnknown(wire);
      var correlation = UUID.fromString(wire.getRequestId());
      if (!correlation.toString().equals(wire.getRequestId())) throw invalid();
      return new Request(
          wire.getSchemaVersion(),
          wire.getTargetNamespace(),
          correlation,
          wire.getOriginalAccountBinding().toByteArray(),
          credential);
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  /** Caller must first verify actual Account owner COMMIT_ORDER with these exact binding bytes. */
  public static ClaimOriginalDraftResponse toResponse(Request request) {
    return ClaimOriginalDraftResponse.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setRequestId(request.requestId().toString())
        .setOriginalAccountBinding(ByteString.copyFrom(request.originalAccountBinding()))
        .setStatus(OriginalDraftOrderStatus.ORIGINAL_DRAFT_ORDER_STATUS_COMMIT_ORDER)
        .build();
  }

  public static AccountOriginalDraftOrderEvidence fromResponse(
      Request request, ClaimOriginalDraftResponse wire) {
    try {
      noUnknown(wire);
      if (wire.getSchemaVersion() != request.schemaVersion()
          || !wire.getTargetNamespace().equals(request.targetNamespace())
          || !wire.getRequestId().equals(request.requestId().toString())
          || !Arrays.equals(
              wire.getOriginalAccountBinding().toByteArray(), request.originalAccountBinding())
          || wire.getStatus() != OriginalDraftOrderStatus.ORIGINAL_DRAFT_ORDER_STATUS_COMMIT_ORDER)
        throw invalid();
      return new AccountOriginalDraftOrderEvidence(request);
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static void noUnknown(Message wire) {
    if (wire == null || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid original Draft order transport evidence");
  }
}
