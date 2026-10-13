package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Objects;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence.OwnedAffectedTuple;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence.Request;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedStartLocationAffectedTuple;

/** Closed wire mapping and exact request-echo validation for the World selector read. */
public final class WorldPublishedStartLocationGrpcCodec {
  private WorldPublishedStartLocationGrpcCodec() {}

  public static ReadWorldPublishedStartLocationRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    var builder =
        ReadWorldPublishedStartLocationRequest.newBuilder()
            .setSchemaVersion(Request.SCHEMA_VERSION)
            .setTargetNamespace(request.targetNamespace())
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setCanonicalVersionId(request.canonicalVersionId().toString())
            .setIntakeRequestId(request.intakeRequestId().toString())
            .setPublicationFence(request.publicationFence().toString())
            .setPublicationRequestId(request.publicationRequestId())
            .setRequestDigest(request.requestDigest())
            .setVersionStateEpoch(request.versionStateEpoch())
            .setPublishWorkflowId(request.publishWorkflowId())
            .setAppliedCommitId(request.appliedCommitId())
            .setContentDigest(request.contentDigest())
            .setDigestSchemaVersion(request.digestSchemaVersion());
    request.worldAffectedTuples().stream()
        .map(WorldPublishedStartLocationGrpcCodec::toWireTuple)
        .forEach(builder::addWorldAffectedTuples);
    return builder.build();
  }

  /**
   * Decodes only after the World receiver authenticates its exact same-namespace Game Design peer.
   */
  public static Request fromRequest(ReadWorldPublishedStartLocationRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadWorldPublishedStartLocationRequest");
    if (request.getSchemaVersion() != Request.SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported World published selector read schema version");
    }
    return toEvidenceRequest(
        request.getTargetNamespace(),
        request.getCanonicalTenantId(),
        request.getCanonicalVersionId(),
        request.getIntakeRequestId(),
        request.getPublicationFence(),
        request.getPublicationRequestId(),
        request.getRequestDigest(),
        request.getVersionStateEpoch(),
        request.getPublishWorkflowId(),
        request.getAppliedCommitId(),
        request.getContentDigest(),
        request.getDigestSchemaVersion(),
        request.getWorldAffectedTuplesList());
  }

  public static ReadWorldPublishedStartLocationResponse toResponse(
      Request request, WorldPublishedStartLocationEvidence evidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(evidence, "evidence");
    if (!request.equals(evidence.request())) {
      throw new IllegalArgumentException(
          "World selector evidence differs from exact selection request");
    }
    var builder =
        ReadWorldPublishedStartLocationResponse.newBuilder()
            .setSchemaVersion(Request.SCHEMA_VERSION)
            .setTargetNamespace(request.targetNamespace())
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setCanonicalVersionId(request.canonicalVersionId().toString())
            .setIntakeRequestId(request.intakeRequestId().toString())
            .setPublicationFence(request.publicationFence().toString())
            .setPublicationRequestId(request.publicationRequestId())
            .setRequestDigest(request.requestDigest())
            .setVersionStateEpoch(request.versionStateEpoch())
            .setPublishWorkflowId(request.publishWorkflowId())
            .setAppliedCommitId(request.appliedCommitId())
            .setContentDigest(request.contentDigest())
            .setDigestSchemaVersion(request.digestSchemaVersion())
            .setSelectorReceiptBytes(ByteString.copyFrom(evidence.selectorReceiptBytes()))
            .setOriginalAccountBindingBytes(
                ByteString.copyFrom(evidence.originalAccountBindingBytes()))
            .setAppliedResultBytes(ByteString.copyFrom(evidence.appliedResultBytes()));
    request.worldAffectedTuples().stream()
        .map(WorldPublishedStartLocationGrpcCodec::toWireTuple)
        .forEach(builder::addWorldAffectedTuples);
    return builder.build();
  }

  /** Validates the complete echoed capture tuple and all three exact retained World byte values. */
  public static WorldPublishedStartLocationEvidence fromResponse(
      Request request, ReadWorldPublishedStartLocationResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadWorldPublishedStartLocationResponse");
    if (response.getSchemaVersion() != Request.SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported World published selector response schema version");
    }
    Request echoed =
        toEvidenceRequest(
            response.getTargetNamespace(),
            response.getCanonicalTenantId(),
            response.getCanonicalVersionId(),
            response.getIntakeRequestId(),
            response.getPublicationFence(),
            response.getPublicationRequestId(),
            response.getRequestDigest(),
            response.getVersionStateEpoch(),
            response.getPublishWorkflowId(),
            response.getAppliedCommitId(),
            response.getContentDigest(),
            response.getDigestSchemaVersion(),
            response.getWorldAffectedTuplesList());
    if (!request.equals(echoed)) {
      throw new IllegalArgumentException(
          "World published selector response changed exact selection request");
    }
    if (response.getSelectorReceiptBytes().isEmpty()
        || response.getOriginalAccountBindingBytes().isEmpty()
        || response.getAppliedResultBytes().isEmpty()) {
      throw new IllegalArgumentException(
          "World published selector response lacks retained evidence bytes");
    }
    return new WorldPublishedStartLocationEvidence(
        request,
        response.getSelectorReceiptBytes().toByteArray(),
        response.getOriginalAccountBindingBytes().toByteArray(),
        response.getAppliedResultBytes().toByteArray());
  }

  private static Request toEvidenceRequest(
      String namespace,
      String tenant,
      String version,
      String intakeRequest,
      String publicationFence,
      String publicationRequest,
      String requestDigest,
      long versionStateEpoch,
      String workflow,
      String appliedCommit,
      String contentDigest,
      int digestSchema,
      java.util.List<WorldPublishedStartLocationAffectedTuple> tuples) {
    var affected =
        tuples.stream().map(WorldPublishedStartLocationGrpcCodec::fromWireTuple).toList();
    Request decoded =
        new Request(
            namespace,
            canonicalUuid(tenant, "canonicalTenantId"),
            canonicalUuid(version, "canonicalVersionId"),
            canonicalUuid(intakeRequest, "intakeRequestId"),
            canonicalUuid(publicationFence, "publicationFence"),
            publicationRequest,
            requestDigest,
            versionStateEpoch,
            workflow,
            appliedCommit,
            contentDigest,
            digestSchema,
            affected);
    if (!decoded.worldAffectedTuples().equals(affected)) {
      throw new IllegalArgumentException("World affected tuples are not in canonical order");
    }
    return decoded;
  }

  private static WorldPublishedStartLocationAffectedTuple toWireTuple(OwnedAffectedTuple tuple) {
    return WorldPublishedStartLocationAffectedTuple.newBuilder()
        .setOwner(tuple.owner())
        .setAggregateType(tuple.aggregateType())
        .setAggregateId(tuple.aggregateId())
        .setScopeType(tuple.scopeType())
        .setScopeId(tuple.scopeId())
        .setExpectedEpoch(tuple.expectedEpoch())
        .build();
  }

  private static OwnedAffectedTuple fromWireTuple(WorldPublishedStartLocationAffectedTuple tuple) {
    requireNoUnknownFields(tuple, "WorldPublishedStartLocationAffectedTuple");
    return new OwnedAffectedTuple(
        tuple.getOwner(),
        tuple.getAggregateType(),
        tuple.getAggregateId(),
        tuple.getScopeType(),
        tuple.getScopeId(),
        tuple.getExpectedEpoch());
  }

  private static java.util.UUID canonicalUuid(String value, String label) {
    try {
      java.util.UUID parsed = java.util.UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new java.util.UUID(0L, 0L))) {
        throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }
}
