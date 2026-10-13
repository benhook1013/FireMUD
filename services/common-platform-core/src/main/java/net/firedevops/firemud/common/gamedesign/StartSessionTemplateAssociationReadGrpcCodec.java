package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.StartSessionExactTemplateAssociationSelection;
import net.firedevops.firemud.gamedesign.v1.StartSessionTemplateWorldSourceAssociationEvidence;

/** Closed public request/response mapping for the authenticated StartSession owner read. */
public final class StartSessionTemplateAssociationReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private StartSessionTemplateAssociationReadGrpcCodec() {}

  public static ReadStartSessionTemplateAssociationRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    var builder =
        ReadStartSessionTemplateAssociationRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setCanonicalPostAuthorizationTuple(
                ByteString.copyFrom(request.canonicalPostAuthorizationTuple()))
            .setOwnerAttemptId(request.ownerAttemptId().toString())
            .setOwnerFence(request.ownerFence());
    if (request.selection() instanceof InitialConfigured) {
      builder.setInitialConfigured(
          net.firedevops.firemud.gamedesign.v1.StartSessionConfiguredTemplateSelection
              .getDefaultInstance());
    } else if (request.selection() instanceof ExactReplay replay) {
      builder.setExactReplay(
          StartSessionExactTemplateAssociationSelection.newBuilder()
              .setCanonicalVersionId(replay.canonicalVersionId().toString())
              .setSelectedCommitId(replay.selectedCommitId().toString())
              .setPublishWorkflowId(replay.publishWorkflowId())
              .setAssociationDigest(replay.associationDigest()));
    } else {
      throw new IllegalArgumentException("Unsupported template association selection mode");
    }
    return builder.build();
  }

  /** Decode only after the receiver authenticated its exact same-namespace Game Session peer. */
  public static Request fromRequest(ReadStartSessionTemplateAssociationRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadStartSessionTemplateAssociationRequest");
    try {
      var selection =
          switch (request.getSelectionCase()) {
            case INITIAL_CONFIGURED -> new InitialConfigured();
            case EXACT_REPLAY ->
                new ExactReplay(
                    parseCanonicalUuid(
                        request.getExactReplay().getCanonicalVersionId(), "canonicalVersionId"),
                    parseCanonicalUuid(
                        request.getExactReplay().getSelectedCommitId(), "selectedCommitId"),
                    request.getExactReplay().getPublishWorkflowId(),
                    request.getExactReplay().getAssociationDigest());
            case SELECTION_NOT_SET ->
                throw new IllegalArgumentException(
                    "Explicit initial or exact replay mode required");
          };
      return new Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalUuid(request.getReadRequestId(), "readRequestId"),
          request.getCanonicalPostAuthorizationTuple().toByteArray(),
          parseCanonicalUuid(request.getOwnerAttemptId(), "ownerAttemptId"),
          request.getOwnerFence(),
          selection);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "Canonical StartSession template association request required", invalid);
    }
  }

  public static ReadStartSessionTemplateAssociationResponse toResponse(Result result) {
    Objects.requireNonNull(result, "result");
    Association association = result.association();
    var worldRequest =
        WorldAuthoredSourceIntakeGrpcCodec.toReadByIdRequest(association.worldReadRequest());
    var worldResponse =
        WorldAuthoredSourceIntakeGrpcCodec.toReadByIdResponse(
            association.worldReadRequest(), association.worldReceipt());
    var associationMessage =
        StartSessionTemplateWorldSourceAssociationEvidence.newBuilder()
            .setCanonicalTenantId(association.canonicalTenantId().toString())
            .setTemplateId(association.templateId())
            .setCanonicalVersionId(association.canonicalVersionId().toString())
            .setSelectedCommitId(association.selectedCommitId().toString())
            .setPublishWorkflowId(association.publishWorkflowId())
            .setPublicationSelectionDigest(association.publicationSelectionDigest())
            .setAssociationDigest(association.associationDigest())
            .setTargetNamespace(association.targetNamespace())
            .setIntakeRequestId(association.intakeRequestId().toString())
            .setWorldOperationId(association.worldOperationId().toString())
            .setSourceOperationId(association.sourceOperationId().toString())
            .setWorldSlug(association.worldSlug())
            .setSourceEvidenceDigest(association.sourceEvidenceDigest())
            .setWorldReadRequest(worldRequest)
            .setWorldReadResponse(worldResponse);
    return ReadStartSessionTemplateAssociationResponse.newBuilder()
        .setRequest(toRequest(result.request()))
        .setAssociation(associationMessage)
        .setReleaseBundle(result.releaseBundle())
        .setWorldPublishedStartLocationEvidence(
            ByteString.copyFrom(result.worldPublishedStartLocationEvidence().canonicalBytes()))
        .setReferencePhaseEpoch(result.phaseEpoch())
        .build();
  }

  /** Validates the full exact request echo and every public release/source correlation. */
  public static Result fromResponse(
      Request expectedRequest, ReadStartSessionTemplateAssociationResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadStartSessionTemplateAssociationResponse");
    if (!response.hasRequest() || !response.hasAssociation() || !response.hasReleaseBundle()) {
      throw new IllegalArgumentException(
          "Complete exact template association read response required");
    }
    Request echoed = fromRequest(response.getRequest());
    if (!expectedRequest.equals(echoed)) {
      throw new IllegalArgumentException("Game Design changed the exact read request echo");
    }
    var wireAssociation = response.getAssociation();
    if (!wireAssociation.hasWorldReadRequest() || !wireAssociation.hasWorldReadResponse()) {
      throw new IllegalArgumentException("Complete public World source receipt is required");
    }
    Association association =
        Association.fromStoredProjection(
            parseCanonicalUuid(wireAssociation.getCanonicalTenantId(), "canonicalTenantId"),
            wireAssociation.getTemplateId(),
            parseCanonicalUuid(wireAssociation.getCanonicalVersionId(), "canonicalVersionId"),
            parseCanonicalUuid(wireAssociation.getSelectedCommitId(), "selectedCommitId"),
            wireAssociation.getPublishWorkflowId(),
            wireAssociation.getPublicationSelectionDigest(),
            wireAssociation.getAssociationDigest(),
            wireAssociation.getTargetNamespace(),
            parseCanonicalUuid(wireAssociation.getIntakeRequestId(), "intakeRequestId"),
            parseCanonicalUuid(wireAssociation.getWorldOperationId(), "worldOperationId"),
            parseCanonicalUuid(wireAssociation.getSourceOperationId(), "sourceOperationId"),
            wireAssociation.getWorldSlug(),
            wireAssociation.getSourceEvidenceDigest(),
            wireAssociation.getWorldReadRequest(),
            wireAssociation.getWorldReadResponse());
    WorldPublishedStartLocationEvidence worldEvidence =
        WorldPublishedStartLocationEvidence.fromStored(
            response.getWorldPublishedStartLocationEvidence().toByteArray());
    try {
      return new Result(
          expectedRequest,
          association,
          response.getReleaseBundle(),
          worldEvidence,
          response.getReferencePhaseEpoch());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "Game Design returned conflicting association, release, or phase evidence", invalid);
    }
  }

  private static UUID parseCanonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(Objects.requireNonNull(value, field));
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
    for (Map.Entry<Descriptors.FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
      if (field.getKey().getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
      if (field.getKey().isRepeated()) {
        for (Object nested : (List<?>) field.getValue()) {
          requireNoUnknownFields((Message) nested, label + "." + field.getKey().getName());
        }
      } else {
        requireNoUnknownFields((Message) field.getValue(), label + "." + field.getKey().getName());
      }
    }
  }
}
