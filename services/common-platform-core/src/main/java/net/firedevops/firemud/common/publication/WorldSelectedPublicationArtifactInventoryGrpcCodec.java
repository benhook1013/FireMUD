package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedPublicationArtifactInventoryRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedPublicationArtifactInventoryResponse;

/** Closed wire mapping for the authenticated retained World inventory read. */
public final class WorldSelectedPublicationArtifactInventoryGrpcCodec {
  private static final int SCHEMA_VERSION = 1;

  private WorldSelectedPublicationArtifactInventoryGrpcCodec() {}

  public static ReadSelectedPublicationArtifactInventoryRequest toRequest(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    Objects.requireNonNull(freezeEvidence, "freezeEvidence");
    return ReadSelectedPublicationArtifactInventoryRequest.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setFreezeRequest(
            WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(freezeEvidence.request()))
        .setFreezeAcknowledgement(
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(
                freezeEvidence.acknowledgement()))
        .build();
  }

  /** Decodes the exact existing freeze request+acknowledgement as immutable lookup identity. */
  public static WorldSelectedDraftPublicationFreezeEvidence fromRequest(
      ReadSelectedPublicationArtifactInventoryRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadSelectedPublicationArtifactInventoryRequest");
    if (request.getSchemaVersion() != SCHEMA_VERSION
        || !request.hasFreezeRequest()
        || !request.hasFreezeAcknowledgement()) {
      throw new IllegalArgumentException("Complete World freeze lookup identity is required");
    }
    var freezeRequest =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromRequest(request.getFreezeRequest());
    return WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
        freezeRequest, request.getFreezeAcknowledgement());
  }

  public static ReadSelectedPublicationArtifactInventoryResponse toResponse(
      WorldSelectedPublicationArtifactInventoryEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    return ReadSelectedPublicationArtifactInventoryResponse.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setCanonicalPublicInventory(ByteString.copyFrom(evidence.canonicalBytes()))
        .setPublicInventoryDigest(evidence.digest())
        .build();
  }

  /** Strictly decodes the one public typed view and binds it to the caller's exact freeze. */
  public static WorldSelectedPublicationArtifactInventoryEvidence fromResponse(
      WorldSelectedDraftPublicationFreezeEvidence expectedFreeze,
      ReadSelectedPublicationArtifactInventoryResponse response) {
    Objects.requireNonNull(expectedFreeze, "expectedFreeze");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadSelectedPublicationArtifactInventoryResponse");
    if (response.getSchemaVersion() != SCHEMA_VERSION
        || response.getCanonicalPublicInventory().isEmpty()
        || response.getPublicInventoryDigest().isEmpty()) {
      throw new IllegalArgumentException("World response lacks a complete public inventory");
    }
    return WorldSelectedPublicationArtifactInventoryEvidence.fromCanonicalBytes(
        expectedFreeze,
        response.getCanonicalPublicInventory().toByteArray(),
        response.getPublicInventoryDigest());
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }
}
