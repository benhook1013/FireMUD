package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.v1.DraftSynchronizedVisibilityFence;
import net.firedevops.firemud.gamedesign.v1.DraftVisibilityTarget;
import net.firedevops.firemud.gamedesign.v1.ReadDraftSynchronizedVisibilityRequest;
import net.firedevops.firemud.gamedesign.v1.ReadDraftSynchronizedVisibilityResponse;

/** Closed wire mapping and exact echo validation for synchronized Draft visibility reads. */
public final class DraftSynchronizedVisibilityGrpcCodec {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private DraftSynchronizedVisibilityGrpcCodec() {}

  public static ReadDraftSynchronizedVisibilityRequest toRequest(
      DraftSynchronizedVisibilityEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadDraftSynchronizedVisibilityRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setTarget(toTarget(request.target()))
        .build();
  }

  /** Decodes only after the receiver has authenticated its exact same-namespace peer. */
  public static DraftSynchronizedVisibilityEvidence.Request fromRequest(
      ReadDraftSynchronizedVisibilityRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadDraftSynchronizedVisibilityRequest");
    if (!request.hasTarget()) {
      throw new IllegalArgumentException("Complete synchronized visibility target is required");
    }
    try {
      return new DraftSynchronizedVisibilityEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
          fromTarget(request.getTarget()));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Synchronized visibility request is invalid", exception);
    }
  }

  public static ReadDraftSynchronizedVisibilityResponse toResponse(
      DraftSynchronizedVisibilityEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    evidence.requireValid();
    var fence = evidence.fence();
    Timestamp timestamp =
        Timestamp.newBuilder()
            .setSeconds(fence.createdAt().toEpochSecond())
            .setNanos(fence.createdAt().getNano())
            .build();
    return ReadDraftSynchronizedVisibilityResponse.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setTargetNamespace(evidence.request().targetNamespace())
        .setReadRequestId(evidence.request().readRequestId().toString())
        .setTarget(toTarget(evidence.binding().target()))
        .setBindingJson(evidence.binding().canonicalJson())
        .setBindingDigest(evidence.binding().digest())
        .setWorkflowState(evidence.workflowState())
        .setFence(
            DraftSynchronizedVisibilityFence.newBuilder()
                .setRequestId(fence.requestId().toString())
                .setCommitId(fence.commitId().toString())
                .setInputDigest(fence.inputDigest())
                .setResultVectorJson(fence.resultVectorJson())
                .setCreatedAt(timestamp))
        .build();
  }

  /** Decodes the full binding and rejects every changed or missing request/fence carrier. */
  public static DraftSynchronizedVisibilityEvidence fromResponse(
      DraftSynchronizedVisibilityEvidence.Request request,
      ReadDraftSynchronizedVisibilityResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadDraftSynchronizedVisibilityResponse");
    if (!response.hasTarget() || !response.hasFence()) {
      throw new IllegalArgumentException("Synchronized visibility response is incomplete");
    }
    requireNoUnknownFields(response.getTarget(), "DraftVisibilityTarget");
    requireNoUnknownFields(response.getFence(), "DraftSynchronizedVisibilityFence");

    try {
      DraftSynchronizedVisibilityEvidence.Request echoed =
          new DraftSynchronizedVisibilityEvidence.Request(
              response.getSchemaVersion(),
              response.getTargetNamespace(),
              parseCanonicalNonNilUuid(response.getReadRequestId(), "readRequestId"),
              fromTarget(response.getTarget()));
      if (!request.equals(echoed)) {
        throw new IllegalArgumentException(
            "Game Design response does not echo the exact read ID and complete target");
      }
      DraftCommitBinding binding =
          DraftCommitBinding.fromStored(response.getBindingJson(), response.getBindingDigest());
      Timestamp timestamp = response.getFence().getCreatedAt();
      if (!response.getFence().hasCreatedAt()) {
        throw new IllegalArgumentException("Synchronized fence timestamp is required");
      }
      requireNoUnknownFields(timestamp, "Timestamp");
      if (timestamp.getSeconds() < -62135596800L
          || timestamp.getSeconds() > 253402300799L
          || timestamp.getNanos() < 0
          || timestamp.getNanos() > 999999999) {
        throw new IllegalArgumentException(
            "Synchronized fence timestamp must be valid protobuf time");
      }
      return new DraftSynchronizedVisibilityEvidence(
          request,
          binding,
          response.getWorkflowState(),
          new DraftSynchronizedVisibilityEvidence.Fence(
              parseCanonicalNonNilUuid(response.getFence().getRequestId(), "fence requestId"),
              parseCanonicalNonNilUuid(response.getFence().getCommitId(), "fence commitId"),
              response.getFence().getInputDigest(),
              response.getFence().getResultVectorJson(),
              OffsetDateTime.ofInstant(
                  Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()),
                  ZoneOffset.UTC)));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "Game Design synchronized visibility response is invalid", exception);
    }
  }

  private static DraftVisibilityTarget toTarget(DraftCommitBinding.TargetProof target) {
    return DraftVisibilityTarget.newBuilder()
        .setCanonicalTenantId(target.canonicalTenantId().toString())
        .setCanonicalVersionId(target.canonicalVersionId().toString())
        .setGameDesignVersionRowId(target.gameDesignVersionRowId())
        .setGameDesignVersionTenantKey(target.gameDesignVersionTenantKey())
        .setSourceGameRowId(target.sourceGameRowId())
        .setSourceGameTenantKey(target.sourceGameTenantKey())
        .setSourceProvenanceKind(target.sourceProvenanceKind())
        .build();
  }

  private static DraftCommitBinding.TargetProof fromTarget(DraftVisibilityTarget target) {
    Objects.requireNonNull(target, "target");
    requireNoUnknownFields(target, "DraftVisibilityTarget");
    return new DraftCommitBinding.TargetProof(
        parseCanonicalNonNilUuid(target.getCanonicalTenantId(), "canonicalTenantId"),
        parseCanonicalNonNilUuid(target.getCanonicalVersionId(), "canonicalVersionId"),
        target.getGameDesignVersionRowId(),
        target.getGameDesignVersionTenantKey(),
        target.getSourceGameRowId(),
        target.getSourceGameTenantKey(),
        target.getSourceProvenanceKind());
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", exception);
    }
  }
}
