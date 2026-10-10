package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence.Request;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedPublicationArtifactInventoryResponse;

/** Closed wire mapping for the bounded recipient-specific World inventory read. */
public final class SelectedOwnerWorldInventoryReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private SelectedOwnerWorldInventoryReadGrpcCodec() {}

  public static ReadSelectedOwnerWorldInventoryRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    var freeze = request.freezeEvidence();
    var result =
        ReadSelectedOwnerWorldInventoryRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setOriginalIntakeAuthorizationBinding(
                ByteString.copyFrom(request.authorizationBinding().canonicalBytes()))
            .setIntakeAuthorizationDigest(request.authorizationBinding().digest())
            .setFreezeRequest(
                WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(freeze.request()))
            .setFreezeAcknowledgement(
                WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(freeze.acknowledgement()))
            .setIntendedReader(request.authorizationBinding().intendedReader())
            .setClosureReadPurpose(request.closureReadPurpose())
            .build();
    requireWireSize(result.getSerializedSize());
    return result;
  }

  /** Decode only after the receiver has authenticated an allowed owner peer. */
  public static Request fromRequest(ReadSelectedOwnerWorldInventoryRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(request.getSerializedSize());
    requireNoUnknownFields(request, "ReadSelectedOwnerWorldInventoryRequest");
    if (request.getSchemaVersion() != SelectedOwnerWorldInventoryReadEvidence.SCHEMA_VERSION
        || request.getTargetNamespace().isEmpty()) {
      throw invalid("Canonical selected-owner World inventory read identity is required");
    }
    UUID readRequestId = canonicalUuid(request.getReadRequestId(), "readRequestId");
    byte[] bindingBytes = request.getOriginalIntakeAuthorizationBinding().toByteArray();
    if (bindingBytes.length == 0
        || bindingBytes.length
            > SelectedOwnerWorldInventoryReadEvidence.MAX_INTAKE_AUTHORIZATION_BYTES) {
      throw invalid("Selected-owner authorization binding is absent or oversized");
    }
    SelectedOwnerIntakeAuthorizationBinding binding;
    try {
      binding = SelectedOwnerIntakeAuthorizationBinding.fromStored(bindingBytes);
    } catch (RuntimeException invalidBinding) {
      throw invalid("Selected-owner authorization binding is invalid", invalidBinding);
    }
    if (!binding.digest().equals(request.getIntakeAuthorizationDigest())) {
      throw invalid("Selected-owner authorization binding digest differs");
    }
    if (!binding.intendedReader().equals(request.getIntendedReader())) {
      throw invalid("Original selected-owner recipient differs");
    }
    String expectedPurpose =
        binding.owner() == Owner.ENTITY_MANAGEMENT
            ? "ENTITY_INTAKE_WORLD_CLOSURE_READ"
            : "AUTOMATION_INTAKE_WORLD_CLOSURE_READ";
    if (!expectedPurpose.equals(request.getClosureReadPurpose())) {
      throw invalid("Selected-owner World closure read purpose differs");
    }
    if (!request.hasFreezeRequest() || !request.hasFreezeAcknowledgement()) {
      throw invalid("Complete selected-owner World inventory freeze evidence is required");
    }
    var freezeRequest =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromRequest(request.getFreezeRequest());
    var freezeEvidence =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
            freezeRequest, request.getFreezeAcknowledgement());
    return new Request(
        request.getSchemaVersion(),
        request.getTargetNamespace(),
        readRequestId,
        binding,
        freezeEvidence);
  }

  public static ReadSelectedOwnerWorldInventoryResponse toResponse(
      Request request, WorldSelectedPublicationArtifactInventoryEvidence inventory) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(inventory, "inventory");
    if (!request.freezeEvidence().equals(inventory.freezeEvidence())) {
      throw invalid("World inventory is not bound to the exact selected-owner freeze");
    }
    byte[] inventoryBytes = inventory.canonicalBytes();
    if (inventoryBytes.length == 0
        || inventoryBytes.length
            > SelectedOwnerWorldInventoryReadEvidence.MAX_PUBLIC_INVENTORY_BYTES) {
      throw invalid("World public inventory is absent or exceeds the recipient-read limit");
    }
    var response =
        ReadSelectedOwnerWorldInventoryResponse.newBuilder()
            .setSchemaVersion(SelectedOwnerWorldInventoryReadEvidence.SCHEMA_VERSION)
            .setRequest(toRequest(request))
            .setCanonicalPublicInventory(ByteString.copyFrom(inventoryBytes))
            .setPublicInventoryDigest(inventory.digest())
            .build();
    requireWireSize(response.getSerializedSize());
    return response;
  }

  /** Requires the exact echoed request, then validates the existing typed inventory codec. */
  public static SelectedOwnerWorldInventoryReadEvidence fromResponse(
      Request expected, ReadSelectedOwnerWorldInventoryResponse response) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(response, "response");
    requireWireSize(response.getSerializedSize());
    requireNoUnknownFields(response, "ReadSelectedOwnerWorldInventoryResponse");
    if (response.getSchemaVersion() != SelectedOwnerWorldInventoryReadEvidence.SCHEMA_VERSION
        || !response.hasRequest()
        || !response.getRequest().equals(toRequest(expected))) {
      throw invalid("World changed the exact selected-owner inventory read request");
    }
    requireNoUnknownFields(response.getRequest(), "echoed ReadSelectedOwnerWorldInventoryRequest");
    byte[] inventoryBytes = response.getCanonicalPublicInventory().toByteArray();
    if (inventoryBytes.length == 0
        || inventoryBytes.length
            > SelectedOwnerWorldInventoryReadEvidence.MAX_PUBLIC_INVENTORY_BYTES
        || response.getPublicInventoryDigest().isEmpty()) {
      throw invalid("World response lacks a complete bounded public inventory");
    }
    var inventoryResponse =
        ReadSelectedPublicationArtifactInventoryResponse.newBuilder()
            .setSchemaVersion(1)
            .setCanonicalPublicInventory(ByteString.copyFrom(inventoryBytes))
            .setPublicInventoryDigest(response.getPublicInventoryDigest())
            .build();
    var inventory =
        WorldSelectedPublicationArtifactInventoryGrpcCodec.fromResponse(
            expected.freezeEvidence(), inventoryResponse);
    return new SelectedOwnerWorldInventoryReadEvidence(expected, inventory);
  }

  private static UUID canonicalUuid(String value, String label) {
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw invalid(label + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid(label + " must be a canonical non-nil UUID", malformed);
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid(label + " contains unsupported fields");
    }
  }

  private static void requireWireSize(int size) {
    if (size > SelectedOwnerWorldInventoryReadEvidence.MAX_WIRE_BYTES) {
      throw invalid("Selected-owner World inventory message exceeds its wire limit");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
