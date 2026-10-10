package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Result;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.v1.GetCompleteLaunchBindingResponse;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;

/**
 * Closed request/result mapping and strict exact-readback validation for the Game Session owner
 * API.
 */
public final class CanonicalGameInstanceLaunchAssociationReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private CanonicalGameInstanceLaunchAssociationReadGrpcCodec() {}

  public static GetCanonicalGameInstanceLaunchAssociationRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    requireIndependentReadIdentity(request);
    return GetCanonicalGameInstanceLaunchAssociationRequest.newBuilder()
        .setReadRequestId(request.readRequestId().toString())
        .setTargetNamespace(request.targetNamespace())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setGameInstanceUuid(request.gameInstanceUuid().toString())
        .setControlPlaneRequestId(request.controlPlaneRequestId())
        .setLaunchDescriptorId(request.launchDescriptorId())
        .setExpectedDescriptorRequestDigest(request.expectedDescriptorRequestDigest())
        .setExpectedDescriptorResultDigest(request.expectedDescriptorResultDigest())
        .setExpectedReleaseAttestationEvidenceDigest(
            request.expectedReleaseAttestationEvidenceDigest())
        .build();
  }

  /** Decodes only the closed exact selector; decoding does not authenticate its caller. */
  public static Request fromRequest(GetCanonicalGameInstanceLaunchAssociationRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "GetCanonicalGameInstanceLaunchAssociation request");
    try {
      Request decoded =
          new Request(
              parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
              request.getTargetNamespace(),
              parseCanonicalNonNilUuid(request.getCanonicalTenantId(), "canonicalTenantId"),
              request.getWorldSlug(),
              parseCanonicalNonNilUuid(request.getGameInstanceUuid(), "gameInstanceUuid"),
              request.getControlPlaneRequestId(),
              request.getLaunchDescriptorId(),
              request.getExpectedDescriptorRequestDigest(),
              request.getExpectedDescriptorResultDigest(),
              request.getExpectedReleaseAttestationEvidenceDigest());
      requireIndependentReadIdentity(decoded);
      if (!toRequest(decoded).equals(request)) {
        throw new IllegalArgumentException("Canonical launch association request is not canonical");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Exact canonical launch association request required", malformed);
    }
  }

  /** Encodes and revalidates the complete exact association read result. */
  public static GetCanonicalGameInstanceLaunchAssociationResponse toResponse(Result result) {
    Objects.requireNonNull(result, "result");
    Request request = result.request();
    CompleteLaunchBindingEvidence binding = result.launchBindingEvidence();
    var completeRequest = completeBindingRequest(request);
    var completeResponse =
        AuthoredWorldLaunchDescriptorGrpcCodec.toCompleteResponse(completeRequest, binding);
    var response =
        GetCanonicalGameInstanceLaunchAssociationResponse.newBuilder()
            .setReadRequestId(request.readRequestId().toString())
            .setTargetNamespace(request.targetNamespace())
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setWorldSlug(request.worldSlug())
            .setGameInstanceUuid(request.gameInstanceUuid().toString())
            .setControlPlaneRequestId(request.controlPlaneRequestId())
            .setLaunchDescriptorId(request.launchDescriptorId())
            .setDescriptorRequestDigest(request.expectedDescriptorRequestDigest())
            .setDescriptorResultDigest(request.expectedDescriptorResultDigest())
            .setReleaseAttestationEvidenceDigest(request.expectedReleaseAttestationEvidenceDigest())
            .setPlayableStateNamespaceId(result.playableStateNamespaceId().toString())
            .setPlayableStateScope(result.playableStateScope().name())
            .setCurrentGameInstanceStatus(result.currentGameInstanceStatus().name())
            .setCurrentRowVersion(result.currentRowVersion())
            .setLaunchDescriptor(completeResponse.getLaunchDescriptor())
            .setReleaseAttestation(completeResponse.getReleaseAttestation())
            .setPublicProduction(result.publicProduction())
            .setCanonicalRealmId(result.canonicalRealmId().toString())
            .setCatalogRevision(result.catalogRevision())
            .build();
    if (!result.equals(fromResponse(request, response))) {
      throw new IllegalArgumentException(
          "Canonical launch association result did not survive its closed wire mapping");
    }
    return response;
  }

  /** Validates all ten request echoes and the complete unchanged descriptor/release evidence. */
  public static Result fromResponse(
      Request expectedRequest, GetCanonicalGameInstanceLaunchAssociationResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "GetCanonicalGameInstanceLaunchAssociation response");
    if (response.hasError()) {
      requireNoUnknownFields(
          response.getError(), "GetCanonicalGameInstanceLaunchAssociation error");
      throw new IllegalArgumentException(
          "Game Session rejected the exact canonical launch association read");
    }

    requireEcho(
        expectedRequest.readRequestId().toString(), response.getReadRequestId(), "readRequestId");
    requireEcho(
        expectedRequest.targetNamespace(), response.getTargetNamespace(), "targetNamespace");
    requireEcho(
        expectedRequest.canonicalTenantId().toString(),
        response.getCanonicalTenantId(),
        "canonicalTenantId");
    requireEcho(expectedRequest.worldSlug(), response.getWorldSlug(), "worldSlug");
    requireEcho(
        expectedRequest.gameInstanceUuid().toString(),
        response.getGameInstanceUuid(),
        "gameInstanceUuid");
    requireEcho(
        expectedRequest.controlPlaneRequestId(),
        response.getControlPlaneRequestId(),
        "controlPlaneRequestId");
    requireEcho(
        expectedRequest.launchDescriptorId(),
        response.getLaunchDescriptorId(),
        "launchDescriptorId");
    requireEcho(
        expectedRequest.expectedDescriptorRequestDigest(),
        response.getDescriptorRequestDigest(),
        "descriptorRequestDigest");
    requireEcho(
        expectedRequest.expectedDescriptorResultDigest(),
        response.getDescriptorResultDigest(),
        "descriptorResultDigest");
    requireEcho(
        expectedRequest.expectedReleaseAttestationEvidenceDigest(),
        response.getReleaseAttestationEvidenceDigest(),
        "releaseAttestationEvidenceDigest");

    if (!response.hasLaunchDescriptor() || !response.hasReleaseAttestation()) {
      throw new IllegalArgumentException(
          "Game Session must return the complete descriptor and release attestation pair");
    }
    var completeRequest = completeBindingRequest(expectedRequest);
    var completeResponse =
        GetCompleteLaunchBindingResponse.newBuilder()
            .setRequestId(response.getReadRequestId())
            .setLaunchDescriptor(response.getLaunchDescriptor())
            .setReleaseAttestation(response.getReleaseAttestation())
            .build();
    CompleteLaunchBindingEvidence binding =
        AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
            completeRequest, completeResponse);

    UUID playableStateNamespaceId =
        parseCanonicalNonNilUuid(
            response.getPlayableStateNamespaceId(), "playableStateNamespaceId");
    RealmEntryPolicy.StateScope scope;
    try {
      scope = RealmEntryPolicy.StateScope.valueOf(response.getPlayableStateScope());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Unknown playable state scope", invalid);
    }
    if (scope != RealmEntryPolicy.StateScope.SHARED) {
      throw new IllegalArgumentException("Canonical launch association requires SHARED scope");
    }
    if (!response.getPublicProduction()) {
      throw new IllegalArgumentException("Canonical launch association requires public production");
    }
    UUID canonicalRealmId =
        parseCanonicalNonNilUuid(response.getCanonicalRealmId(), "canonicalRealmId");
    if (response.getCatalogRevision() <= 0) {
      throw new IllegalArgumentException("Catalog revision is missing or invalid");
    }

    CurrentGameInstanceStatus status;
    try {
      status = CurrentGameInstanceStatus.valueOf(response.getCurrentGameInstanceStatus());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Unknown current Game Session instance status", invalid);
    }
    if (response.getCurrentRowVersion() < 0) {
      throw new IllegalArgumentException("Current Game Session row version is missing or invalid");
    }
    return new Result(
        expectedRequest,
        playableStateNamespaceId,
        scope,
        response.getPublicProduction(),
        canonicalRealmId,
        response.getCatalogRevision(),
        status,
        response.getCurrentRowVersion(),
        binding);
  }

  private static GetLaunchDescriptorRequest completeBindingRequest(Request request) {
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(request.readRequestId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setControlPlaneRequestId(request.controlPlaneRequestId())
        .setExpectedRequestDigest(request.expectedDescriptorRequestDigest())
        .setExpectedResultDigest(request.expectedDescriptorResultDigest())
        .build();
  }

  private static void requireIndependentReadIdentity(Request request) {
    if (request.readRequestId().toString().equals(request.controlPlaneRequestId())) {
      throw new IllegalArgumentException(
          "Launch association read identity must remain separate from the original request");
    }
  }

  private static void requireEcho(String expected, String actual, String field) {
    if (actual.isEmpty() || !expected.equals(actual)) {
      throw new IllegalArgumentException(
          "Game Session launch association " + field + " echo changed");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Missing " + label);
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid " + label, invalid);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
    return parsed;
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
    for (Map.Entry<Descriptors.FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
      if (field.getKey().getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
        continue;
      }
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
