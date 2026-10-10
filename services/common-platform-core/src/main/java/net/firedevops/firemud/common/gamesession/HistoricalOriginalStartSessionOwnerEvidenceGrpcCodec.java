package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence.LaunchAssociation;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence.Request;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence.Result;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceRequest;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceResponse;

/** Closed request and response mapping for historical original StartSession evidence. */
public final class HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec() {}

  public static ReadHistoricalOriginalStartSessionOwnerEvidenceRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    var selector = request.associationSelector();
    return ReadHistoricalOriginalStartSessionOwnerEvidenceRequest.newBuilder()
        .setSchemaVersion(HistoricalOriginalStartSessionOwnerEvidence.SCHEMA_VERSION)
        .setReadRequestId(selector.readRequestId().toString())
        .setTargetNamespace(selector.targetNamespace())
        .setCanonicalTenantId(selector.canonicalTenantId().toString())
        .setWorldSlug(selector.worldSlug())
        .setGameInstanceUuid(selector.gameInstanceUuid().toString())
        .setControlPlaneRequestId(selector.controlPlaneRequestId())
        .setLaunchDescriptorId(selector.launchDescriptorId())
        .setExpectedDescriptorRequestDigest(selector.expectedDescriptorRequestDigest())
        .setExpectedDescriptorResultDigest(selector.expectedDescriptorResultDigest())
        .setExpectedReleaseAttestationEvidenceDigest(
            selector.expectedReleaseAttestationEvidenceDigest())
        .setExpectedOwnerAttemptId(request.expectedOwnerAttemptId().toString())
        .setExpectedOwnerFence(request.expectedOwnerFence())
        .build();
  }

  /** Decodes only the closed exact-selector request; decoding does not authenticate its caller. */
  public static Request fromRequest(
      ReadHistoricalOriginalStartSessionOwnerEvidenceRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadHistoricalOriginalStartSessionOwnerEvidenceRequest");
    if (request.getSchemaVersion() != HistoricalOriginalStartSessionOwnerEvidence.SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported historical StartSession read schema");
    }
    try {
      var selector =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              parseUuid(request.getReadRequestId(), "readRequestId"),
              request.getTargetNamespace(),
              parseUuid(request.getCanonicalTenantId(), "canonicalTenantId"),
              request.getWorldSlug(),
              parseUuid(request.getGameInstanceUuid(), "gameInstanceUuid"),
              request.getControlPlaneRequestId(),
              request.getLaunchDescriptorId(),
              request.getExpectedDescriptorRequestDigest(),
              request.getExpectedDescriptorResultDigest(),
              request.getExpectedReleaseAttestationEvidenceDigest());
      Request decoded =
          new Request(
              selector,
              parseUuid(request.getExpectedOwnerAttemptId(), "expectedOwnerAttemptId"),
              request.getExpectedOwnerFence());
      if (!toRequest(decoded).equals(request)) {
        throw new IllegalArgumentException("Historical StartSession request is not canonical");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Canonical historical original StartSession evidence request required", malformed);
    }
  }

  public static ReadHistoricalOriginalStartSessionOwnerEvidenceResponse toResponse(Result result) {
    Objects.requireNonNull(result, "result");
    var association = result.launchAssociation();
    CompleteLaunchBindingEvidence binding = association.launchBindingEvidence();
    var descriptor = binding.descriptor();
    var attestation = binding.releaseAttestation();
    var associationRead = result.firstSelection();
    var descriptorPin = result.descriptorPin();
    var descriptorRequest = descriptorPin.associationRead().request();
    return ReadHistoricalOriginalStartSessionOwnerEvidenceResponse.newBuilder()
        .setRequest(toRequest(result.request()))
        .setCanonicalPostAuthorizationTuple(
            com.google.protobuf.ByteString.copyFrom(result.originalTuple().canonicalBytes()))
        .setOwnerAttemptId(result.ownerAttemptId().toString())
        .setOwnerFence(result.ownerFence())
        .setAccountRedemptionProjection(
            com.google.protobuf.ByteString.copyFrom(result.accountRedemptionProjection()))
        .setTemplateAssociationRequest(
            StartSessionTemplateAssociationReadGrpcCodec.toRequest(associationRead.request()))
        .setTemplateAssociationResponse(
            StartSessionTemplateAssociationReadGrpcCodec.toResponse(associationRead))
        .setTemplateAssociationRequestDigest(result.templateAssociationRequestDigest())
        .setTemplateAssociationResponseDigest(result.templateAssociationResponseDigest())
        .setDescriptorPinResponse(
            StartSessionLaunchDescriptorGrpcCodec.toResponse(
                descriptorRequest, descriptorPin.associationRead(), descriptorPin.outcome()))
        .setDescriptorPinRequestDigest(result.descriptorPinRequestDigest())
        .setDescriptorPinResponseDigest(result.descriptorPinResponseDigest())
        .setPlayableStateNamespaceId(association.playableStateNamespaceId().toString())
        .setPlayableStateScope(association.playableStateScope().name())
        .setPublicProduction(association.publicProduction())
        .setCapturedStartingRowVersion(association.capturedStartingRowVersion())
        .setLaunchDescriptor(
            AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor))
        .setReleaseAttestation(
            AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(attestation))
        .setAssociationTargetNamespace(association.targetNamespace())
        .setAssociationCanonicalTenantId(association.canonicalTenantId().toString())
        .setAssociationWorldSlug(association.worldSlug())
        .setAssociationGameInstanceUuid(association.gameInstanceUuid().toString())
        .setAssociationControlPlaneRequestId(association.controlPlaneRequestId())
        .setAssociationLaunchDescriptorId(association.launchDescriptorId())
        .build();
  }

  /** Validates the exact request echo, original pins, projection, and immutable launch binding. */
  public static Result fromResponse(
      Request expectedRequest, ReadHistoricalOriginalStartSessionOwnerEvidenceResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadHistoricalOriginalStartSessionOwnerEvidenceResponse");
    if (!response.hasRequest()
        || !response.hasTemplateAssociationRequest()
        || !response.hasTemplateAssociationResponse()
        || !response.hasDescriptorPinResponse()
        || !response.hasLaunchDescriptor()
        || !response.hasReleaseAttestation()) {
      throw new IllegalArgumentException(
          "Complete historical original StartSession evidence required");
    }

    Request echoed = fromRequest(response.getRequest());
    if (!expectedRequest.equals(echoed)) {
      throw new IllegalArgumentException(
          "Game Session changed the exact historical read request echo");
    }

    byte[] tupleBytes = response.getCanonicalPostAuthorizationTuple().toByteArray();
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
    if (!java.util.Arrays.equals(tuple.canonicalBytes(), tupleBytes)) {
      throw new IllegalArgumentException("Historical original StartSession tuple is not canonical");
    }

    var firstRequest =
        StartSessionTemplateAssociationReadGrpcCodec.fromRequest(
            response.getTemplateAssociationRequest());
    var firstSelection =
        StartSessionTemplateAssociationReadGrpcCodec.fromResponse(
            firstRequest, response.getTemplateAssociationResponse());
    var selector = expectedRequest.associationSelector();
    if (!(firstRequest.selection()
            instanceof StartSessionTemplateAssociationReadEvidence.InitialConfigured)
        || !selector.targetNamespace().equals(firstRequest.targetNamespace())
        || !java.util.Arrays.equals(firstRequest.canonicalPostAuthorizationTuple(), tupleBytes)
        || !expectedRequest.expectedOwnerAttemptId().equals(firstRequest.ownerAttemptId())
        || expectedRequest.expectedOwnerFence() != firstRequest.ownerFence()) {
      throw new IllegalArgumentException(
          "Historical first selection differs from its exact original attempt and tuple");
    }

    var pinnedAssociation = firstSelection.association();
    var originalSelectionRequest = firstSelection.request();
    var exactReplayRequest =
        new StartSessionTemplateAssociationReadEvidence.Request(
            originalSelectionRequest.schemaVersion(),
            originalSelectionRequest.targetNamespace(),
            originalSelectionRequest.readRequestId(),
            originalSelectionRequest.canonicalPostAuthorizationTuple(),
            originalSelectionRequest.ownerAttemptId(),
            originalSelectionRequest.ownerFence(),
            new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                pinnedAssociation.canonicalVersionId(),
                pinnedAssociation.selectedCommitId(),
                pinnedAssociation.publishWorkflowId(),
                pinnedAssociation.associationDigest()));
    var descriptorPin =
        StartSessionLaunchDescriptorGrpcCodec.fromResponse(
            exactReplayRequest, response.getDescriptorPinResponse());

    if (!(descriptorPin.outcome()
        instanceof StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome descriptorOutcome)) {
      throw new IllegalArgumentException(
          "Historical StartSession descriptor pin has no descriptor");
    }
    AuthoredWorldLaunchDescriptorEvidence.Request expectedDescriptorRequest =
        descriptorOutcome.descriptor().request();
    CompleteLaunchBindingEvidence launchBinding =
        AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteEvidenceMessages(
            expectedDescriptorRequest,
            response.getLaunchDescriptor(),
            response.getReleaseAttestation());

    RealmEntryPolicy.StateScope stateScope;
    try {
      stateScope = RealmEntryPolicy.StateScope.valueOf(response.getPlayableStateScope());
    } catch (RuntimeException invalidScope) {
      throw new IllegalArgumentException(
          "Historical playable-state scope is malformed", invalidScope);
    }
    LaunchAssociation launchAssociation =
        new LaunchAssociation(
            response.getAssociationTargetNamespace(),
            parseUuid(response.getAssociationCanonicalTenantId(), "associationCanonicalTenantId"),
            response.getAssociationWorldSlug(),
            parseUuid(response.getAssociationGameInstanceUuid(), "associationGameInstanceUuid"),
            response.getAssociationControlPlaneRequestId(),
            response.getAssociationLaunchDescriptorId(),
            parseUuid(response.getPlayableStateNamespaceId(), "playableStateNamespaceId"),
            stateScope,
            response.getPublicProduction(),
            response.getCapturedStartingRowVersion(),
            launchBinding);

    Result result =
        new Result(
            expectedRequest,
            tuple,
            parseUuid(response.getOwnerAttemptId(), "ownerAttemptId"),
            response.getOwnerFence(),
            response.getAccountRedemptionProjection().toByteArray(),
            firstSelection,
            descriptorPin,
            launchAssociation,
            response.getTemplateAssociationRequestDigest(),
            response.getTemplateAssociationResponseDigest(),
            response.getDescriptorPinRequestDigest(),
            response.getDescriptorPinResponseDigest());
    if (!toResponse(result).equals(response)) {
      throw new IllegalArgumentException(
          "Historical original StartSession response is not canonical");
    }
    return result;
  }

  private static UUID parseUuid(String value, String field) {
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
