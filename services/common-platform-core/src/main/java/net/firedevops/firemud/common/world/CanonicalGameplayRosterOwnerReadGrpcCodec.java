package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;

/** Closed schema-1 wire mapping for the Entity-only canonical roster source read. */
public final class CanonicalGameplayRosterOwnerReadGrpcCodec {
  public static final int MAX_REQUEST_BYTES = 2 * 1024;
  public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024 + 64 * 1024;

  private static final int SCHEMA_VERSION = 1;

  private CanonicalGameplayRosterOwnerReadGrpcCodec() {}

  public static CanonicalGameplayRosterOwnerReadEvidence.Request fromRequest(
      GetCanonicalGameplayRosterOwnerReadRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException("Gameplay roster read request exceeds its size bound");
    }
    if (!request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Closed schema-1 gameplay roster read request required");
    }
    return new CanonicalGameplayRosterOwnerReadEvidence.Request(
        canonicalUuid(request.getRequestUuid(), "request_uuid"),
        canonicalUuid(request.getCanonicalAccountUuid(), "canonical_account_uuid"),
        request.getTargetNamespace(),
        canonicalUuid(request.getCanonicalTenantUuid(), "canonical_tenant_uuid"),
        request.getWorldSlug(),
        canonicalUuid(request.getRealmUuid(), "realm_uuid"),
        request.getRealmSlug(),
        canonicalUuid(request.getPlayableStateNamespaceUuid(), "playable_state_namespace_uuid"),
        request.getPlayableStateScope(),
        canonicalUuid(request.getCanonicalGameInstanceUuid(), "canonical_game_instance_uuid"),
        canonicalUuid(request.getCanonicalVersionUuid(), "canonical_version_uuid"),
        request.getExpectedCatalogRevision(),
        request.getExpectedPointerVersion(),
        request.getExpectedActiveWorldEpoch());
  }

  public static GetCanonicalGameplayRosterOwnerReadRequest toRequest(
      CanonicalGameplayRosterOwnerReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return GetCanonicalGameplayRosterOwnerReadRequest.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setRequestUuid(request.requestUuid().toString())
        .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
        .setTargetNamespace(request.targetNamespace())
        .setCanonicalTenantUuid(request.canonicalTenantUuid().toString())
        .setWorldSlug(request.worldSlug())
        .setRealmUuid(request.realmUuid().toString())
        .setRealmSlug(request.realmSlug())
        .setPlayableStateNamespaceUuid(request.playableStateNamespaceUuid().toString())
        .setPlayableStateScope(request.playableStateScope())
        .setCanonicalGameInstanceUuid(request.canonicalGameInstanceUuid().toString())
        .setCanonicalVersionUuid(request.canonicalVersionUuid().toString())
        .setExpectedCatalogRevision(request.expectedCatalogRevision())
        .setExpectedPointerVersion(request.expectedPointerVersion())
        .setExpectedActiveWorldEpoch(request.expectedActiveWorldEpoch())
        .build();
  }

  public static GetCanonicalGameplayRosterOwnerReadResponse toResponse(
      CanonicalGameplayRosterOwnerReadEvidence.Request request,
      CanonicalGameplayRosterOwnerReadEvidence evidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(evidence, "evidence");
    if (!request.equals(evidence.request())) {
      throw new IllegalArgumentException("Gameplay roster evidence changed the exact request");
    }
    byte[] proofBytes =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
            evidence.gameSessionOwnerProof());
    byte[] policyBytes = evidence.publishedPolicySetEvidence().canonicalBytes();
    if (proofBytes.length > CanonicalGameplayRosterOwnerReadEvidence.MAX_OWNER_PROOF_BYTES
        || policyBytes.length > CanonicalGameplayRosterOwnerReadEvidence.MAX_POLICY_SET_BYTES) {
      throw new IllegalArgumentException("Gameplay roster owner response exceeds its size bound");
    }
    var response =
        GetCanonicalGameplayRosterOwnerReadResponse.newBuilder()
            .setRequest(toRequest(request))
            .setGameSessionOwnerProof(ByteString.copyFrom(proofBytes))
            .setPublishedPolicySetEvidence(ByteString.copyFrom(policyBytes))
            .setAdmissionPointerSnapshotDigest(evidence.admissionPointerSnapshotDigest())
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES) {
      throw new IllegalArgumentException("Gameplay roster owner response exceeds its size bound");
    }
    return response;
  }

  public static CanonicalGameplayRosterOwnerReadEvidence fromResponse(
      CanonicalGameplayRosterOwnerReadEvidence.Request request,
      GetCanonicalGameplayRosterOwnerReadResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || response.getGameSessionOwnerProof().isEmpty()
        || response.getPublishedPolicySetEvidence().isEmpty()) {
      throw new IllegalArgumentException("Closed complete gameplay roster response required");
    }
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES
        || response.getGameSessionOwnerProof().size()
            > CanonicalGameplayRosterOwnerReadEvidence.MAX_OWNER_PROOF_BYTES
        || response.getPublishedPolicySetEvidence().size()
            > CanonicalGameplayRosterOwnerReadEvidence.MAX_POLICY_SET_BYTES) {
      throw new IllegalArgumentException("Gameplay roster owner response exceeds its size bound");
    }
    var echoed = fromRequest(response.getRequest());
    if (!request.equals(echoed)) {
      throw new IllegalArgumentException("Gameplay roster response changed the exact request");
    }
    var proof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(
            response.getGameSessionOwnerProof().toByteArray());
    var policySet =
        PublishedRealmEntryPolicySetEvidence.fromStored(
            response.getPublishedPolicySetEvidence().toByteArray());
    return new CanonicalGameplayRosterOwnerReadEvidence(
        request, response.getAdmissionPointerSnapshotDigest(), proof, policySet);
  }

  private static UUID canonicalUuid(String value, String name) {
    try {
      UUID parsed = UUID.fromString(Objects.requireNonNull(value, name));
      if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
        throw new IllegalArgumentException(name + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(name + " must be a canonical non-nil UUID", invalid);
    }
  }
}
