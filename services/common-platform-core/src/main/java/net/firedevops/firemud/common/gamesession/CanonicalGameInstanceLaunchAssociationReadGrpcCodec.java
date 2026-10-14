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

  public static net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationRequest
      toFixtureRequest(CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest request) {
    return net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationRequest
        .newBuilder()
        .setReadRequestId(request.readRequestId().toString())
        .setTargetNamespace(request.targetNamespace())
        .setFixtureOperationId(request.fixtureOperationId().toString())
        .setExpectedFixturePolicyEvidenceDigest(request.expectedFixturePolicyEvidenceDigest())
        .build();
  }

  public static CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest
      fromFixtureRequest(
          net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationRequest
              request) {
    requireNoUnknownFields(request, "Fixture launch owner request");
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
        parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
        request.getTargetNamespace(),
        parseCanonicalNonNilUuid(request.getFixtureOperationId(), "fixtureOperationId"),
        request.getExpectedFixturePolicyEvidenceDigest());
  }

  public static net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationResponse
      toFixtureResponse(CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult result) {
    var request = result.request();
    var seal = result.fixturePolicySeal();
    return net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationResponse
        .newBuilder()
        .setReadRequestId(request.readRequestId().toString())
        .setTargetNamespace(request.targetNamespace())
        .setFixtureOperationId(request.fixtureOperationId().toString())
        .setExpectedFixturePolicyEvidenceDigest(request.expectedFixturePolicyEvidenceDigest())
        .setGameSessionTenantId(result.gameSessionTenantId())
        .setGameInstanceId(result.gameInstanceId())
        .setCanonicalTenantId(result.canonicalTenantId().toString())
        .setGameInstanceUuid(result.gameInstanceUuid().toString())
        .setCanonicalVersionId(result.canonicalVersionId().toString())
        .setCanonicalRealmId(result.canonicalRealmId().toString())
        .setPlayableStateNamespaceId(result.playableStateNamespaceId().toString())
        .setPlayableStateScope("SHARED")
        .setCatalogRevision(result.catalogRevision())
        .setCurrentGameInstanceStatus(result.currentGameInstanceStatus().name())
        .setCurrentRowVersion(result.currentRowVersion())
        .setFixturePolicyRequestBytes(
            com.google.protobuf.ByteString.copyFrom(result.fixturePolicyRequestBytes()))
        .setFixturePolicySealOperationId(seal.operationId().toString())
        .setFixturePolicySealRequestDigest(seal.requestDigest())
        .setFixturePolicySealEvidenceDigest(seal.evidenceDigest())
        .build();
  }

  public static CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult
      fromFixtureResponse(
          CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest expected,
          net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationResponse
              response) {
    requireNoUnknownFields(response, "Fixture launch owner response");
    var actual =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
            parseCanonicalNonNilUuid(response.getReadRequestId(), "readRequestId"),
            response.getTargetNamespace(),
            parseCanonicalNonNilUuid(response.getFixtureOperationId(), "fixtureOperationId"),
            response.getExpectedFixturePolicyEvidenceDigest());
    if (!expected.equals(actual) || !"SHARED".equals(response.getPlayableStateScope())) {
      throw new IllegalArgumentException(
          "Fixture launch owner changed the exact read identity or scope");
    }
    var seal =
        new net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .FixturePolicySeal(
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                .FIXTURE_POLICY_SEAL_SCHEMA,
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                .SOURCE_RECEIPT_OWNER_SERVICE,
            parseCanonicalNonNilUuid(response.getFixturePolicySealOperationId(), "sealOperationId"),
            response.getFixturePolicySealRequestDigest(),
            response.getFixturePolicySealEvidenceDigest());
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
        expected,
        response.getGameSessionTenantId(),
        response.getGameInstanceId(),
        parseCanonicalNonNilUuid(response.getCanonicalTenantId(), "canonicalTenantId"),
        parseCanonicalNonNilUuid(response.getGameInstanceUuid(), "gameInstanceUuid"),
        parseCanonicalNonNilUuid(response.getCanonicalVersionId(), "canonicalVersionId"),
        parseCanonicalNonNilUuid(response.getCanonicalRealmId(), "canonicalRealmId"),
        parseCanonicalNonNilUuid(
            response.getPlayableStateNamespaceId(), "playableStateNamespaceId"),
        response.getCatalogRevision(),
        CurrentGameInstanceStatus.valueOf(response.getCurrentGameInstanceStatus()),
        response.getCurrentRowVersion(),
        response.getFixturePolicyRequestBytes().toByteArray(),
        seal);
  }

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
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setCurrentGameInstanceStatus(result.currentGameInstanceStatus().name())
            .setCurrentRowVersion(result.currentRowVersion())
            .setOwnerAttemptId(result.ownerAttemptId().toString())
            .setOwnerFence(result.ownerFence())
            .setLaunchDescriptor(completeResponse.getLaunchDescriptor())
            .setReleaseAttestation(completeResponse.getReleaseAttestation())
            .setPublicProduction(result.publicProduction())
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
    RealmEntryPolicy.StateScope scope =
        switch (response.getPlayableStateScope()) {
          case PLAYABLE_STATE_SCOPE_SHARED -> RealmEntryPolicy.StateScope.SHARED;
          case PLAYABLE_STATE_SCOPE_ISOLATED ->
              throw new IllegalArgumentException(
                  "Canonical launch association requires SHARED scope");
          case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED ->
              throw new IllegalArgumentException("Unknown playable state scope");
        };
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
    UUID ownerAttemptId = parseCanonicalNonNilUuid(response.getOwnerAttemptId(), "ownerAttemptId");
    if (response.getOwnerFence() <= 0) {
      throw new IllegalArgumentException("ownerFence must be positive");
    }
    return new Result(
        expectedRequest,
        playableStateNamespaceId,
        scope,
        response.getPublicProduction(),
        status,
        response.getCurrentRowVersion(),
        ownerAttemptId,
        response.getOwnerFence(),
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
