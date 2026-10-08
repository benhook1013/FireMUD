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

/** Closed request mapping and strict exact-readback validation for the Game Session owner API. */
public final class CanonicalGameInstanceLaunchAssociationReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private CanonicalGameInstanceLaunchAssociationReadGrpcCodec() {}

  public static GetCanonicalGameInstanceLaunchAssociationRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
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
    var completeRequest =
        GetLaunchDescriptorRequest.newBuilder()
            .setRequestId(expectedRequest.readRequestId().toString())
            .setCanonicalTenantId(expectedRequest.canonicalTenantId().toString())
            .setWorldSlug(expectedRequest.worldSlug())
            .setControlPlaneRequestId(expectedRequest.controlPlaneRequestId())
            .setExpectedRequestDigest(expectedRequest.expectedDescriptorRequestDigest())
            .setExpectedResultDigest(expectedRequest.expectedDescriptorResultDigest())
            .build();
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
        status,
        response.getCurrentRowVersion(),
        binding);
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
