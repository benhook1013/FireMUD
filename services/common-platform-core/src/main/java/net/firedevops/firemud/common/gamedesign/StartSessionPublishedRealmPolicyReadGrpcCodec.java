package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionPublishedRealmPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionPublishedRealmPolicyResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed transport mapping for the authenticated exact StartSession published-policy read. */
public final class StartSessionPublishedRealmPolicyReadGrpcCodec {
  public static final int REQUEST_SCHEMA_VERSION = 1;
  public static final int RESPONSE_SCHEMA_VERSION = 1;

  /** 256 KiB exact authorization tuple plus the bounded descriptor/release projection budget. */
  public static final int MAX_REQUEST_WIRE_BYTES = 4 * 1024 * 1024;

  /** 32 MiB policy carrier plus 8 MiB for association, source, and release framing. */
  public static final int MAX_RESPONSE_WIRE_BYTES =
      RealmPolicyPublishedEvidenceCodec.MAX_ENVELOPE_BYTES + 8 * 1024 * 1024;

  public static final int MAX_WIRE_BYTES = MAX_RESPONSE_WIRE_BYTES;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  /** Exact request the Game Session caller intends to replay and disclose. */
  public record ReadRequest(
      String targetNamespace,
      UUID readRequestId,
      Request authorizationRequest,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence releaseAttestation,
      DraftCommitBinding.TargetProof target) {
    public ReadRequest {
      Objects.requireNonNull(targetNamespace, "targetNamespace");
      requireNonNil(readRequestId, "readRequestId");
      Objects.requireNonNull(authorizationRequest, "authorizationRequest");
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(releaseAttestation, "releaseAttestation");
      Objects.requireNonNull(target, "target");
      requireExactReplay(authorizationRequest);
      if (!targetNamespace.equals(authorizationRequest.targetNamespace())
          || !targetNamespace.equals(descriptor.targetNamespace())) {
        throw new IllegalArgumentException("Exact same-namespace request required");
      }
      if (readRequestId.equals(authorizationRequest.readRequestId())
          || readRequestId.equals(authorizationRequest.ownerAttemptId())
          || readRequestId.equals(descriptor.authoredWorldSourceOperationId())
          || readRequestId.toString().equals(descriptor.controlPlaneRequestId())) {
        throw new IllegalArgumentException("Independent canonical read request ID required");
      }
      descriptor.requireValid();
      releaseAttestation.requireValid(descriptor);
      if (!descriptor.canonicalTenantId().equals(target.canonicalTenantId())
          || !releaseAttestation.canonicalVersionId().equals(target.canonicalVersionId())
          || descriptor.versionId() != target.gameDesignVersionRowId()) {
        throw new IllegalArgumentException("Exact descriptor, release, and TargetProof required");
      }
    }
  }

  /** Structurally decoded request. Descriptor semantics wait until Account confirms the replay. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Generated protobuf messages are immutable; retain the exact original request.")
  public record WireRequest(
      ReadStartSessionPublishedRealmPolicyRequest message,
      UUID readRequestId,
      Request authorizationRequest,
      DraftCommitBinding.TargetProof target) {
    public WireRequest {
      Objects.requireNonNull(message, "message");
      Objects.requireNonNull(readRequestId, "readRequestId");
      Objects.requireNonNull(authorizationRequest, "authorizationRequest");
      Objects.requireNonNull(target, "target");
    }
  }

  /** Complete validated authorization, source, and published immutable policy response. */
  public record ReadResult(
      ReadRequest request,
      Result authorizationResult,
      AuthoredWorldSourceEvidence sourceEvidence,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    public ReadResult {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(authorizationResult, "authorizationResult");
      Objects.requireNonNull(sourceEvidence, "sourceEvidence");
      Objects.requireNonNull(publishedSet, "publishedSet");
    }
  }

  private StartSessionPublishedRealmPolicyReadGrpcCodec() {}

  public static ReadStartSessionPublishedRealmPolicyRequest toRequest(ReadRequest request) {
    Objects.requireNonNull(request, "request");
    ReadStartSessionPublishedRealmPolicyRequest message =
        ReadStartSessionPublishedRealmPolicyRequest.newBuilder()
            .setSchemaVersion(REQUEST_SCHEMA_VERSION)
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setAuthorizationRequest(
                StartSessionLaunchDescriptorGrpcCodec.toRequest(request.authorizationRequest()))
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
                    request.descriptor()))
            .setReleaseAttestation(
                AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(
                    request.releaseAttestation()))
            .setCanonicalTargetProofJson(
                com.google.protobuf.ByteString.copyFrom(
                    RealmPolicyPublishedEvidence.targetProofJson(request.target()),
                    StandardCharsets.UTF_8))
            .build();
    requireRequestSerializedSize(message);
    return message;
  }

  /** Decode structure and the unchanged authorization request only after peer authentication. */
  public static WireRequest fromRequest(ReadStartSessionPublishedRealmPolicyRequest message) {
    Objects.requireNonNull(message, "message");
    requireNoUnknownFields(message, "ReadStartSessionPublishedRealmPolicyRequest");
    requireRequestSerializedSize(message);
    if (message.getSchemaVersion() != REQUEST_SCHEMA_VERSION
        || !message.hasAuthorizationRequest()
        || !message.hasLaunchDescriptor()
        || !message.hasReleaseAttestation()
        || message.getCanonicalTargetProofJson().isEmpty()) {
      throw new IllegalArgumentException("Complete schema-1 published-policy request required");
    }
    Request authorization =
        StartSessionLaunchDescriptorGrpcCodec.fromRequest(message.getAuthorizationRequest());
    UUID readRequestId = parseCanonicalNonNilUuid(message.getReadRequestId(), "readRequestId");
    if (!message.getTargetNamespace().equals(authorization.targetNamespace())
        || readRequestId.equals(authorization.readRequestId())
        || readRequestId.equals(authorization.ownerAttemptId())) {
      throw new IllegalArgumentException("Exact namespace and independent read ID required");
    }
    DraftCommitBinding.TargetProof target =
        decodeTargetProof(message.getCanonicalTargetProofJson().toByteArray());
    return new WireRequest(message, readRequestId, authorization, target);
  }

  /**
   * After Account's exact association read succeeds, reuse the complete launch and release codecs
   * to bind every supplied immutable value to that exact association.
   */
  public static ReadRequest authorize(WireRequest request, Result authorizationResult) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(authorizationResult, "authorizationResult");
    if (!request.authorizationRequest().equals(authorizationResult.request())) {
      throw new IllegalArgumentException("Account-confirmed request differs from exact replay");
    }
    var associationResponse =
        StartSessionTemplateAssociationReadGrpcCodec.toResponse(authorizationResult);
    var descriptorResponse =
        ResolveStartSessionLaunchDescriptorResponse.newBuilder()
            .setSchemaVersion(StartSessionLaunchDescriptorGrpcCodec.RESPONSE_SCHEMA_VERSION)
            .setAssociationRead(associationResponse)
            .setLaunchDescriptor(request.message().getLaunchDescriptor())
            .build();
    var resolved =
        StartSessionLaunchDescriptorGrpcCodec.fromResponse(
            request.authorizationRequest(), descriptorResponse);
    if (!(resolved.outcome() instanceof StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome)) {
      throw new IllegalArgumentException("Exact launch descriptor required");
    }
    var descriptorAndRelease =
        AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteEvidenceMessages(
            ((StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome) resolved.outcome())
                .descriptor()
                .request(),
            request.message().getLaunchDescriptor(),
            request.message().getReleaseAttestation());
    if (!descriptorAndRelease
        .descriptor()
        .equals(
            ((StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome) resolved.outcome())
                .descriptor())) {
      throw new IllegalArgumentException("Complete descriptor evidence changed during decoding");
    }
    ReadRequest authorized =
        new ReadRequest(
            request.message().getTargetNamespace(),
            request.readRequestId(),
            request.authorizationRequest(),
            descriptorAndRelease.descriptor(),
            descriptorAndRelease.releaseAttestation(),
            request.target());
    requireAssociationBinding(authorized, authorizationResult);
    return authorized;
  }

  /**
   * Encodes a complete owner result, then runs the exact consumer validation before returning it.
   */
  public static ReadStartSessionPublishedRealmPolicyResponse toResponse(
      WireRequest wireRequest,
      ReadRequest authorizedRequest,
      Result authorizationResult,
      AuthoredWorldSourceEvidence sourceEvidence,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    Objects.requireNonNull(wireRequest, "wireRequest");
    Objects.requireNonNull(authorizedRequest, "authorizedRequest");
    Objects.requireNonNull(authorizationResult, "authorizationResult");
    Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    Objects.requireNonNull(publishedSet, "publishedSet");
    if (!authorizedRequest.authorizationRequest().equals(authorizationResult.request())
        || !authorizedRequest.equals(authorize(wireRequest, authorizationResult))) {
      throw new IllegalArgumentException("Exact Account-confirmed request required");
    }
    ReadStartSessionPublishedRealmPolicyResponse response =
        ReadStartSessionPublishedRealmPolicyResponse.newBuilder()
            .setSchemaVersion(RESPONSE_SCHEMA_VERSION)
            .setRequest(wireRequest.message())
            .setAuthorizationResponse(
                StartSessionTemplateAssociationReadGrpcCodec.toResponse(authorizationResult))
            .setSourceEvidence(
                AuthoredWorldSourceGrpcCodec.toReadResponse(
                    sourceRequest(authorizedRequest), sourceEvidence))
            .setPublishedPolicySet(
                com.google.protobuf.ByteString.copyFrom(
                    RealmPolicyPublishedEvidenceCodec.encode(publishedSet)))
            .build();
    requireResponseSerializedSize(response);
    fromResponse(wireRequest, response);
    return response;
  }

  /**
   * Validates the whole echo, nested Account correlation, source read, TargetProof, and carrier.
   */
  public static ReadResult fromResponse(
      WireRequest expectedRequest, ReadStartSessionPublishedRealmPolicyResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadStartSessionPublishedRealmPolicyResponse");
    requireResponseSerializedSize(response);
    if (response.getSchemaVersion() != RESPONSE_SCHEMA_VERSION
        || !response.hasRequest()
        || !response.hasAuthorizationResponse()
        || !response.hasSourceEvidence()
        || response.getPublishedPolicySet().isEmpty()
        || !expectedRequest.message().equals(response.getRequest())) {
      throw new IllegalArgumentException("Complete exact published-policy response required");
    }
    Result authorizationResult =
        StartSessionTemplateAssociationReadGrpcCodec.fromResponse(
            expectedRequest.authorizationRequest(), response.getAuthorizationResponse());
    ReadRequest authorized = authorize(expectedRequest, authorizationResult);
    AuthoredWorldSourceEvidence source =
        AuthoredWorldSourceGrpcCodec.fromReadResponse(
            sourceRequest(authorized), response.getSourceEvidence());
    requireSourceBinding(authorized, source);
    RealmPolicyPublishedEvidence.PublishedSet publishedSet =
        RealmPolicyPublishedEvidenceCodec.decode(response.getPublishedPolicySet().toByteArray());
    requirePublishedSetBinding(authorized, authorizationResult, source, publishedSet);
    return new ReadResult(authorized, authorizationResult, source, publishedSet);
  }

  public static AuthoredWorldSourceGrpcCodec.ReadRequest sourceRequest(ReadRequest request) {
    Objects.requireNonNull(request, "request");
    return new AuthoredWorldSourceGrpcCodec.ReadRequest(
        request.targetNamespace(),
        request.readRequestId(),
        request.descriptor().authoredWorldSourceOperationId(),
        request.descriptor().canonicalTenantId(),
        request.descriptor().worldSlug());
  }

  public static AuthoredWorldSourceGrpcCodec.ReadRequest sourceRequest(
      WireRequest request, AuthoredWorldLaunchDescriptorEvidence descriptor) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");
    return new AuthoredWorldSourceGrpcCodec.ReadRequest(
        request.message().getTargetNamespace(),
        request.readRequestId(),
        descriptor.authoredWorldSourceOperationId(),
        descriptor.canonicalTenantId(),
        descriptor.worldSlug());
  }

  public static void requireRequestSerializedSize(Message message) {
    Objects.requireNonNull(message, "message");
    if (message.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) {
      throw new IllegalArgumentException("Published-policy request exceeds the 4 MiB bound");
    }
    if (message instanceof ReadStartSessionPublishedRealmPolicyRequest request
        && request.hasAuthorizationRequest()
        && request.getAuthorizationRequest().getCanonicalPostAuthorizationTuple().size()
            > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
      throw new IllegalArgumentException("Exact StartSession tuple exceeds its canonical bound");
    }
  }

  public static void requireResponseSerializedSize(Message message) {
    Objects.requireNonNull(message, "message");
    if (message.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES) {
      throw new IllegalArgumentException("Published-policy response exceeds the 40 MiB bound");
    }
  }

  private static void requireAssociationBinding(ReadRequest request, Result authorizationResult) {
    var association = authorizationResult.association();
    var release = request.releaseAttestation();
    var descriptor = request.descriptor();
    var replay =
        (StartSessionTemplateAssociationReadEvidence.ExactReplay)
            request.authorizationRequest().selection();
    if (!authorizationResult.request().equals(request.authorizationRequest())
        || !request.targetNamespace().equals(association.targetNamespace())
        || !request.targetNamespace().equals(release.targetNamespace())
        || !request.targetNamespace().equals(descriptor.targetNamespace())
        || !association.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !association.canonicalTenantId().equals(request.target().canonicalTenantId())
        || !association.canonicalVersionId().equals(replay.canonicalVersionId())
        || !association.canonicalVersionId().equals(release.canonicalVersionId())
        || !association.canonicalVersionId().equals(request.target().canonicalVersionId())
        || association.templateId() != descriptor.gameTemplateId()
        || association.templateId()
            != request
                .authorizationRequest()
                .decodedTuple()
                .preAuthorizationTuple()
                .action()
                .target()
                .gameTemplateId()
        || !association.worldSlug().equals(descriptor.worldSlug())
        || !association.sourceOperationId().equals(descriptor.authoredWorldSourceOperationId())
        || !association.sourceOperationId().equals(release.authoredWorldSourceOperationId())
        || !association
            .sourceEvidenceDigest()
            .equals(descriptor.authoredWorldSourceEvidenceDigest())
        || !association.sourceEvidenceDigest().equals(release.authoredWorldSourceEvidenceDigest())
        || !association.publishWorkflowId().equals(replay.publishWorkflowId())
        || !association.publishWorkflowId().equals(release.publishWorkflowId())
        || !association.selectedCommitId().equals(replay.selectedCommitId())
        || !association.selectedCommitId().toString().equals(release.commitId())
        || !association.associationDigest().equals(replay.associationDigest())
        || request.target().gameDesignVersionRowId() != descriptor.versionId()
        || !descriptor.publishedReleaseBundleRef().equals(release.publishedReleaseBundleRef())) {
      throw new IllegalArgumentException(
          "Account-confirmed association differs from exact descriptor, release, or TargetProof");
    }
    var releaseBundle = authorizationResult.releaseBundle();
    if (releaseBundle.getVersionId() != descriptor.versionId()
        || !releaseBundle
            .getPublishedReleaseBundleRef()
            .equals(descriptor.publishedReleaseBundleRef())
        || !releaseBundle.getPublishWorkflowId().equals(release.publishWorkflowId())
        || !releaseBundle.getCanonicalVersionId().equals(release.canonicalVersionId().toString())) {
      throw new IllegalArgumentException(
          "Association release bundle differs from exact attestation");
    }
  }

  private static void requireSourceBinding(
      ReadRequest request, AuthoredWorldSourceEvidence source) {
    var descriptor = request.descriptor();
    var target = request.target();
    if (!request.targetNamespace().equals(source.targetNamespace())
        || !descriptor.authoredWorldSourceOperationId().equals(source.operationId())
        || !descriptor.authoredWorldSourceEvidenceDigest().equals(source.evidenceDigest())
        || !descriptor.canonicalTenantId().equals(source.canonicalTenantId())
        || !descriptor.worldSlug().equals(source.worldSlug())
        || source.sourceGameRowId() != target.sourceGameRowId()
        || !source.sourceGameTenantKey().equals(target.sourceGameTenantKey())
        || !source.provenanceKind().equals(target.sourceProvenanceKind())) {
      throw new IllegalArgumentException("Exact authored-world source does not match TargetProof");
    }
  }

  private static void requirePublishedSetBinding(
      ReadRequest request,
      Result authorizationResult,
      AuthoredWorldSourceEvidence source,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    requireAssociationBinding(request, authorizationResult);
    requireSourceBinding(request, source);
    var descriptor = request.descriptor();
    var release = request.releaseAttestation();
    var target = request.target();
    if (!target.equals(publishedSet.target())
        || !descriptor.canonicalTenantId().equals(target.canonicalTenantId())
        || !release.canonicalVersionId().equals(target.canonicalVersionId())
        || descriptor.versionId() != target.gameDesignVersionRowId()
        || !publishedSet.publishedReleaseBundleRef().equals(descriptor.publishedReleaseBundleRef())
        || !publishedSet.publishedReleaseBundleRef().equals(release.publishedReleaseBundleRef())
        || !publishedSet.publishWorkflowId().equals(release.publishWorkflowId())
        || !publishedSet.manifestHash().equals(release.manifestHash())
        || !publishedSet.sourceCommitId().toString().equals(release.commitId())
        || publishedSet.publicationVersionStateEpoch() > descriptor.versionStateEpoch()
        || publishedSet.publicationVersionStateEpoch() > release.versionStateEpoch()) {
      throw new IllegalArgumentException(
          "Published policy set differs from exact TargetProof, release, or publication epoch");
    }
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(publishedSet.terminalEvidenceBytes());
    if (terminal.outcome() != GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
        || terminal.publicationVersionStateEpoch() != publishedSet.publicationVersionStateEpoch()
        || !terminal.publishedReleaseBundleRef().equals(publishedSet.publishedReleaseBundleRef())
        || !terminal
            .publishedReleaseBundleDigest()
            .equals(publishedSet.publishedReleaseBundleDigest())
        || !terminal.releaseContent().publishWorkflowId().equals(release.publishWorkflowId())
        || !terminal.releaseContent().canonicalTenantId().equals(target.canonicalTenantId())
        || !terminal.releaseContent().canonicalVersionId().equals(target.canonicalVersionId())) {
      throw new IllegalArgumentException(
          "Canonical published terminal does not bind exact policy set");
    }
  }

  private static DraftCommitBinding.TargetProof decodeTargetProof(byte[] canonicalJson) {
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(canonicalJson))
              .toString();
      JsonNode node = JSON.readTree(json);
      RealmPolicySource.fields(
          node,
          "canonicalTenantId",
          "canonicalVersionId",
          "gameDesignVersionRowId",
          "gameDesignVersionTenantKey",
          "sourceGameRowId",
          "sourceGameTenantKey",
          "sourceProvenanceKind");
      DraftCommitBinding.TargetProof target =
          new DraftCommitBinding.TargetProof(
              parseCanonicalNonNilUuid(
                  RealmPolicySource.text(node, "canonicalTenantId"), "canonicalTenantId"),
              parseCanonicalNonNilUuid(
                  RealmPolicySource.text(node, "canonicalVersionId"), "canonicalVersionId"),
              canonicalPositiveDecimal(node, "gameDesignVersionRowId"),
              RealmPolicySource.text(node, "gameDesignVersionTenantKey"),
              canonicalPositiveDecimal(node, "sourceGameRowId"),
              RealmPolicySource.text(node, "sourceGameTenantKey"),
              RealmPolicySource.text(node, "sourceProvenanceKind"));
      if (!Arrays.equals(
          canonicalJson,
          RealmPolicyPublishedEvidence.targetProofJson(target).getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException("Noncanonical complete TargetProof required");
      }
      return target;
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Canonical seven-field TargetProof required", invalid);
    }
  }

  private static long canonicalPositiveDecimal(JsonNode node, String field) {
    String value = RealmPolicySource.text(node, field);
    if (!value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(field + " must be canonical positive decimal text");
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException(field + " must be canonical positive decimal text");
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(field + " exceeds the positive 64-bit range", invalid);
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(Objects.requireNonNull(value, field));
      requireNonNil(parsed, field);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be canonical lowercase UUID text");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static void requireExactReplay(Request request) {
    if (!(request.selection() instanceof StartSessionTemplateAssociationReadEvidence.ExactReplay)) {
      throw new IllegalArgumentException("Exact Account-confirmed StartSession replay required");
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

  private static void requireNonNil(UUID value, String field) {
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a non-nil UUID");
    }
  }
}
