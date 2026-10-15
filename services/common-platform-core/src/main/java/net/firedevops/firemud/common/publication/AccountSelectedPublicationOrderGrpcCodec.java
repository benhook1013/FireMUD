package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationRequest;
import net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Closed producer transport. Credential data never appears in responses or diagnostics. */
public final class AccountSelectedPublicationOrderGrpcCodec {
  public static final int MAX_CREDENTIAL_BYTES = 16384;
  public static final int MAX_SELECTION_BYTES = 1048576;

  private AccountSelectedPublicationOrderGrpcCodec() {}

  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID requestId,
      byte[] originalSelection,
      String originalCreatorCredential) {
    public Request {
      if (schemaVersion != 1
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || requestId == null
          || requestId.equals(new UUID(0, 0))) {
        throw invalid();
      }
      if (originalSelection == null
          || originalSelection.length == 0
          || originalSelection.length > MAX_SELECTION_BYTES
          || originalCreatorCredential == null
          || originalCreatorCredential.isEmpty()
          || originalCreatorCredential.length() > MAX_CREDENTIAL_BYTES
          || originalCreatorCredential.chars().anyMatch(c -> c <= 32 || c >= 127)) {
        throw invalid();
      }
      originalSelection = originalSelection.clone();
      var selection = decodeSelection(originalSelection);
      if (!Arrays.equals(originalSelection, selection.canonicalBytes())) throw invalid();
    }

    public static Request create(
        String namespace, AuthoredDraftPublishSelectionBinding selection, String credential) {
      return new Request(1, namespace, UUID.randomUUID(), selection.canonicalBytes(), credential);
    }

    @Override
    public byte[] originalSelection() {
      return originalSelection.clone();
    }

    public AuthoredDraftPublishSelectionBinding selection() {
      return decodeSelection(originalSelection);
    }

    @Override
    public String toString() {
      return "AccountSelectedPublicationOrder.Request[redacted]";
    }
  }

  public static AuthorizeSelectedPublicationRequest toRequest(Request request) {
    Objects.requireNonNull(request);
    return AuthorizeSelectedPublicationRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setRequestId(request.requestId().toString())
        .setOriginalSelection(ByteString.copyFrom(request.originalSelection()))
        .build();
  }

  /** Invoke only after authenticating the exact same-namespace Game Design workload. */
  public static Request fromRequest(AuthorizeSelectedPublicationRequest wire, String credential) {
    try {
      noUnknown(wire);
      UUID id = UUID.fromString(wire.getRequestId());
      if (!id.toString().equals(wire.getRequestId())) throw invalid();
      return new Request(
          wire.getSchemaVersion(),
          wire.getTargetNamespace(),
          id,
          wire.getOriginalSelection().toByteArray(),
          credential);
    } catch (RuntimeException malformed) {
      // Do not retain a parsing cause that could contain credential-bearing request data.
      throw invalid();
    }
  }

  public static AuthorizeSelectedPublicationResponse toResponse(
      Request request, AccountPublicationAuthorizationBinding binding) {
    requireSelection(request, binding);
    return AuthorizeSelectedPublicationResponse.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setRequestId(request.requestId().toString())
        .setOriginalSelection(ByteString.copyFrom(request.originalSelection()))
        .setPublicationAuthorizationBinding(ByteString.copyFrom(binding.canonicalBytes()))
        .build();
  }

  public static AccountPublicationAuthorizationBinding fromResponse(
      Request request, AuthorizeSelectedPublicationResponse wire) {
    try {
      noUnknown(wire);
      if (wire.getSchemaVersion() != request.schemaVersion()
          || !wire.getTargetNamespace().equals(request.targetNamespace())
          || !wire.getRequestId().equals(request.requestId().toString())
          || !Arrays.equals(wire.getOriginalSelection().toByteArray(), request.originalSelection())
          || wire.getPublicationAuthorizationBinding().size() > MAX_SELECTION_BYTES)
        throw invalid();
      var binding =
          AccountPublicationAuthorizationBinding.fromStored(
              wire.getPublicationAuthorizationBinding().toByteArray());
      requireSelection(request, binding);
      return binding;
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static void requireSelection(
      Request request, AccountPublicationAuthorizationBinding binding) {
    if (binding == null
        || !Arrays.equals(
            request.originalSelection(), binding.input().selection().canonicalBytes()))
      throw invalid();
  }

  private static AuthoredDraftPublishSelectionBinding decodeSelection(byte[] bytes) {
    try {
      return AuthoredDraftPublishSelectionBinding.fromStored(
          new String(bytes, StandardCharsets.UTF_8), DraftAuthorizationFenceBinding.digest(bytes));
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static void noUnknown(Message wire) {
    if (wire == null || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid selected-publication order transport evidence");
  }
}
