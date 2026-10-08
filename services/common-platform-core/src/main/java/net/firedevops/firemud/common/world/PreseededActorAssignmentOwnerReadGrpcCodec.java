package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.gamesession.v1.GetPreseededActorAssignmentOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetPreseededActorAssignmentOwnerReadResponse;

/** Closed schema-1 wire mapping for the selector-only, non-admitting assignment owner read. */
public final class PreseededActorAssignmentOwnerReadGrpcCodec {
  public static final int MAX_REQUEST_BYTES = 2 * 1024;
  public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024 + 64 * 1024;

  private static final int SCHEMA_VERSION = 1;

  private PreseededActorAssignmentOwnerReadGrpcCodec() {}

  public static PreseededActorAssignmentOwnerReadEvidence.Request fromRequest(
      GetPreseededActorAssignmentOwnerReadRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException("Actor assignment owner request exceeds its size bound");
    }
    if (!request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Closed schema-1 actor assignment owner request required");
    }
    return new PreseededActorAssignmentOwnerReadEvidence.Request(
        canonicalUuid(request.getAssignmentUuid(), "assignment_uuid"),
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
        request.getExpectedPolicyDigest(),
        request.getExpectedPublishedReleaseBundleRef());
  }

  public static GetPreseededActorAssignmentOwnerReadRequest toRequest(
      PreseededActorAssignmentOwnerReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return GetPreseededActorAssignmentOwnerReadRequest.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setAssignmentUuid(request.assignmentUuid().toString())
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
        .setExpectedPolicyDigest(request.expectedPolicyDigest())
        .setExpectedPublishedReleaseBundleRef(request.expectedPublishedReleaseBundleRef())
        .build();
  }

  public static GetPreseededActorAssignmentOwnerReadResponse toResponse(
      PreseededActorAssignmentOwnerReadEvidence.Request request,
      PreseededActorAssignmentOwnerReadEvidence evidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(evidence, "evidence");
    if (!request.equals(evidence.request())) {
      throw new IllegalArgumentException("Actor assignment evidence changed the exact request");
    }
    var sourceEvidence = evidence.sourceEvidence();
    byte[] proofBytes =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
            sourceEvidence.gameSessionOwnerProof());
    byte[] policyBytes = sourceEvidence.publishedPolicySetEvidence().canonicalBytes();
    if (proofBytes.length > CanonicalGameplayRosterOwnerReadEvidence.MAX_OWNER_PROOF_BYTES
        || policyBytes.length > CanonicalGameplayRosterOwnerReadEvidence.MAX_POLICY_SET_BYTES) {
      throw new IllegalArgumentException("Actor assignment owner response exceeds its size bound");
    }
    var response =
        GetPreseededActorAssignmentOwnerReadResponse.newBuilder()
            .setRequest(toRequest(request))
            .setGameSessionOwnerProof(ByteString.copyFrom(proofBytes))
            .setPublishedPolicySetEvidence(ByteString.copyFrom(policyBytes))
            .setAdmissionPointerSnapshotDigest(sourceEvidence.admissionPointerSnapshotDigest())
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES) {
      throw new IllegalArgumentException("Actor assignment owner response exceeds its size bound");
    }
    return response;
  }

  public static PreseededActorAssignmentOwnerReadEvidence fromResponse(
      PreseededActorAssignmentOwnerReadEvidence.Request request,
      GetPreseededActorAssignmentOwnerReadResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || response.getGameSessionOwnerProof().isEmpty()
        || response.getPublishedPolicySetEvidence().isEmpty()) {
      throw new IllegalArgumentException("Closed complete actor assignment response required");
    }
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES
        || response.getGameSessionOwnerProof().size()
            > CanonicalGameplayRosterOwnerReadEvidence.MAX_OWNER_PROOF_BYTES
        || response.getPublishedPolicySetEvidence().size()
            > CanonicalGameplayRosterOwnerReadEvidence.MAX_POLICY_SET_BYTES) {
      throw new IllegalArgumentException("Actor assignment owner response exceeds its size bound");
    }
    if (!request.equals(fromRequest(response.getRequest()))) {
      throw new IllegalArgumentException("Actor assignment response changed the exact request");
    }
    var proof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(
            response.getGameSessionOwnerProof().toByteArray());
    var policySet =
        PublishedRealmEntryPolicySetEvidence.fromStored(
            response.getPublishedPolicySetEvidence().toByteArray());
    var sourceRequest = PreseededActorAssignmentOwnerReadEvidence.sourceRequest(request, proof);
    var sourceEvidence =
        new CanonicalGameplayRosterOwnerReadEvidence(
            sourceRequest, response.getAdmissionPointerSnapshotDigest(), proof, policySet);
    return new PreseededActorAssignmentOwnerReadEvidence(request, sourceEvidence);
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
