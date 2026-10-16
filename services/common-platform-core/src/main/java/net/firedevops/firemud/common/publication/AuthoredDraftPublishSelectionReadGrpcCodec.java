package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedDraftPublicationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedDraftPublicationResponse;
import net.firedevops.firemud.gamedesign.v1.SelectedDraftPublicationStatus;

/**
 * Closed wire mapping and exact request-echo validation for the selected Draft publication read.
 */
public final class AuthoredDraftPublishSelectionReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AuthoredDraftPublishSelectionReadGrpcCodec() {}

  public static ReadSelectedDraftPublicationRequest toRequest(
      AuthoredDraftPublishSelectionReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadSelectedDraftPublicationRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalSelection(ByteString.copyFrom(request.originalSelection()))
        .setSelectionDigest(request.selectionDigest())
        .build();
  }

  /** Decode only after Game Design authenticates its exact same-namespace Account or World peer. */
  public static AuthoredDraftPublishSelectionReadEvidence.Request fromRequest(
      ReadSelectedDraftPublicationRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadSelectedDraftPublicationRequest");
    try {
      return new AuthoredDraftPublishSelectionReadEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalUuid(request.getReadRequestId()),
          request.getOriginalSelection().toByteArray(),
          request.getSelectionDigest());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "Selected Draft publication read request is invalid", invalid);
    }
  }

  /** Game Design may emit SELECTED only after reading the exact immutable retained selection. */
  public static ReadSelectedDraftPublicationResponse toSelectedResponse(
      AuthoredDraftPublishSelectionReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadSelectedDraftPublicationResponse.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalSelection(ByteString.copyFrom(request.originalSelection()))
        .setSelectionDigest(request.selectionDigest())
        .setStatus(SelectedDraftPublicationStatus.SELECTED_DRAFT_PUBLICATION_STATUS_SELECTED)
        .build();
  }

  /** Validate a positive response; all other owner states are non-OK RPC failures. */
  static AuthoredDraftPublishSelectionReadEvidence fromResponse(
      AuthoredDraftPublishSelectionReadEvidence.Request request,
      ReadSelectedDraftPublicationResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadSelectedDraftPublicationResponse");
    if (response.getSchemaVersion() != request.schemaVersion()
        || !response.getTargetNamespace().equals(request.targetNamespace())
        || !response.getReadRequestId().equals(request.readRequestId().toString())
        || !Arrays.equals(
            response.getOriginalSelection().toByteArray(), request.originalSelection())
        || !response.getSelectionDigest().equals(request.selectionDigest())) {
      throw new IllegalArgumentException(
          "Game Design response changed the exact selected Draft publication read");
    }
    if (response.getStatus()
        != SelectedDraftPublicationStatus.SELECTED_DRAFT_PUBLICATION_STATUS_SELECTED) {
      throw new IllegalArgumentException(
          "Game Design did not confirm the exact selected Draft publication");
    }
    return new AuthoredDraftPublishSelectionReadEvidence(request);
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalUuid(String value) {
    if (value == null) throw new IllegalArgumentException("Canonical read request UUID required");
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Canonical non-nil read request UUID required");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Canonical non-nil read request UUID required", invalid);
    }
  }
}
