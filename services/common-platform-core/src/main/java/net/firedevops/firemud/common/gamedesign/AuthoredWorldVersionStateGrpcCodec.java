package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldSourceReceipt;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;

/** Closed request mapping and exact response validation for authored-world version-state reads. */
public final class AuthoredWorldVersionStateGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AuthoredWorldVersionStateGrpcCodec() {}

  /** Encodes exactly the schema-v1 source-qualified read tuple. */
  public static GetAuthoredWorldVersionStateRequest toRequest(
      AuthoredWorldVersionStateEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return GetAuthoredWorldVersionStateRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setSourceOperationId(request.sourceOperationId().toString())
        .setExpectedSourceEvidenceDigest(request.expectedSourceEvidenceDigest())
        .setVersionId(request.versionId())
        .build();
  }

  /** Decodes a strict request after the authenticated handler has checked its peer. */
  public static AuthoredWorldVersionStateEvidence.Request fromRequest(
      GetAuthoredWorldVersionStateRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "GetAuthoredWorldVersionStateRequest");
    try {
      return new AuthoredWorldVersionStateEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
          parseCanonicalNonNilUuid(request.getCanonicalTenantId(), "canonicalTenantId"),
          request.getWorldSlug(),
          parseCanonicalNonNilUuid(request.getSourceOperationId(), "sourceOperationId"),
          request.getExpectedSourceEvidenceDigest(),
          request.getVersionId());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Authored-world version-state request is invalid", exception);
    }
  }

  /** Encodes the complete validated source receipt and exact current-state evidence. */
  public static GetAuthoredWorldVersionStateResponse toResponse(
      net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    evidence.requireValid();
    AuthoredWorldSourceEvidence source = evidence.sourceEvidence();
    var request = evidence.request();
    AuthoredWorldSourceReceipt sourceReceipt =
        AuthoredWorldSourceReceipt.newBuilder()
            .setSchemaVersion(source.schemaVersion())
            .setTargetNamespace(source.targetNamespace())
            .setRegistrationRequestId(source.registrationRequestId().toString())
            .setOperationId(source.operationId().toString())
            .setRequestDigest(source.requestDigest())
            .setCanonicalTenantId(source.canonicalTenantId().toString())
            .setTenantSlug(source.tenantSlug())
            .setWorldSlug(source.worldSlug())
            .setWorldDisplayName(source.worldDisplayName())
            .setSourceGameRowId(source.sourceGameRowId())
            .setSourceGameTenantKey(source.sourceGameTenantKey())
            .setProvenanceKind(source.provenanceKind())
            .setEvidenceDigest(source.evidenceDigest())
            .build();
    return GetAuthoredWorldVersionStateResponse.newBuilder()
        .setEvidence(
            net.firedevops.firemud.gamedesign.v1.AuthoredWorldVersionStateEvidence.newBuilder()
                .setSchemaVersion(request.schemaVersion())
                .setTargetNamespace(request.targetNamespace())
                .setReadRequestId(request.readRequestId().toString())
                .setCanonicalTenantId(request.canonicalTenantId().toString())
                .setWorldSlug(request.worldSlug())
                .setSourceOperationId(request.sourceOperationId().toString())
                .setExpectedSourceEvidenceDigest(request.expectedSourceEvidenceDigest())
                .setVersionId(request.versionId())
                .setSourceEvidence(sourceReceipt)
                .setVersionState(evidence.versionState())
                .setVersionStateEpoch(evidence.versionStateEpoch())
                .setEvidenceDigest(evidence.evidenceDigest()))
        .build();
  }

  /** Validates every response carrier and binds every echo to the exact read request. */
  public static net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence
      fromResponse(
          AuthoredWorldVersionStateEvidence.Request request,
          GetAuthoredWorldVersionStateResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "GetAuthoredWorldVersionStateResponse");
    if (!response.hasEvidence()) {
      throw new IllegalArgumentException("Version-state response has no evidence");
    }
    var wireEvidence = response.getEvidence();
    requireNoUnknownFields(wireEvidence, "AuthoredWorldVersionStateEvidence");
    if (!wireEvidence.hasSourceEvidence()) {
      throw new IllegalArgumentException("Version-state evidence has no complete source receipt");
    }
    AuthoredWorldSourceReceipt wireSource = wireEvidence.getSourceEvidence();
    requireNoUnknownFields(wireSource, "AuthoredWorldSourceReceipt");

    net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence evidence;
    try {
      var echoedRequest =
          new AuthoredWorldVersionStateEvidence.Request(
              wireEvidence.getSchemaVersion(),
              wireEvidence.getTargetNamespace(),
              parseCanonicalNonNilUuid(wireEvidence.getReadRequestId(), "readRequestId"),
              parseCanonicalNonNilUuid(wireEvidence.getCanonicalTenantId(), "canonicalTenantId"),
              wireEvidence.getWorldSlug(),
              parseCanonicalNonNilUuid(wireEvidence.getSourceOperationId(), "sourceOperationId"),
              wireEvidence.getExpectedSourceEvidenceDigest(),
              wireEvidence.getVersionId());
      if (!request.equals(echoedRequest)) {
        throw new IllegalArgumentException(
            "Version-state response does not echo the exact read request");
      }
      AuthoredWorldSourceEvidence sourceEvidence =
          new AuthoredWorldSourceEvidence(
              wireSource.getSchemaVersion(),
              wireSource.getTargetNamespace(),
              parseCanonicalNonNilUuid(
                  wireSource.getRegistrationRequestId(), "registrationRequestId"),
              parseCanonicalNonNilUuid(wireSource.getOperationId(), "source operationId"),
              wireSource.getRequestDigest(),
              parseCanonicalNonNilUuid(
                  wireSource.getCanonicalTenantId(), "source canonicalTenantId"),
              wireSource.getTenantSlug(),
              wireSource.getWorldSlug(),
              wireSource.getWorldDisplayName(),
              wireSource.getSourceGameRowId(),
              wireSource.getSourceGameTenantKey(),
              wireSource.getProvenanceKind(),
              wireSource.getEvidenceDigest());
      VersionLifecycleState versionState =
          VersionLifecycleState.forNumber(wireEvidence.getVersionStateValue());
      if (versionState == null) {
        throw new IllegalArgumentException("Version-state enum value is unsupported");
      }
      evidence =
          new net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence(
              echoedRequest,
              sourceEvidence,
              versionState,
              wireEvidence.getVersionStateEpoch(),
              wireEvidence.getEvidenceDigest());
      evidence.requireValid();
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Game Design authored-world version-state response is invalid", exception);
    }
    return evidence;
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
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", exception);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    return parsed;
  }
}
