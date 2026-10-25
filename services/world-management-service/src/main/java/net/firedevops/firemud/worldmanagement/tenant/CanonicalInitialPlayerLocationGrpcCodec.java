package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Outcome;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Result;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRoom;

/** Closed schema-1 codec for the unregistered World initial-player-location owner boundary. */
final class CanonicalInitialPlayerLocationGrpcCodec {
  static final int SCHEMA_VERSION = 1;
  static final int MAX_REQUEST_BYTES = 16 * 1024;
  private static final Pattern RAW_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  private CanonicalInitialPlayerLocationGrpcCodec() {}

  static Selection fromRequest(CanonicalInitialPlayerLocationRequest request) {
    if (request == null
        || request.getSerializedSize() > MAX_REQUEST_BYTES
        || request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasExpectedTarget()
        || !request.getExpectedTarget().getUnknownFields().asMap().isEmpty()) {
      throw invalid("a complete closed schema-1 request is required");
    }

    var target = request.getExpectedTarget();
    UUID tenantId = uuid(target.getCanonicalTenantUuid(), "canonical_tenant_uuid");
    UUID realmId = uuid(target.getRealmUuid(), "realm_uuid");
    UUID gameInstanceId = uuid(target.getGameInstanceId(), "game_instance_id");
    UUID versionId = uuid(target.getCanonicalVersionUuid(), "canonical_version_uuid");
    UUID namespaceId =
        uuid(target.getPlayableStateNamespaceUuid(), "playable_state_namespace_uuid");
    if (!RealmEntryPolicy.isCanonicalSlug(target.getWorldSlug())
        || !RealmEntryPolicy.isCanonicalSlug(target.getRealmSlug())) {
      throw invalid("world_slug and realm_slug must be canonical selectors");
    }
    if (target.getCatalogRevision() <= 0
        || target.getPlayableStateScope()
            != net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED) {
      throw invalid("a positive catalog revision and SHARED target are required");
    }
    rawDigest(target.getFrozenPolicyDigest(), "frozen_policy_digest");
    boundedText(target.getPublishedReleaseBundleRef(), "published_release_bundle_ref", 128);

    String assignmentIntentDigest =
        rawDigest(request.getAssignmentIntentDigest(), "assignment_intent_digest");
    String admissionRequestDigest =
        rawDigest(request.getInitialAdmissionRequestDigest(), "initial_admission_request_digest");
    String terminalProofDigest =
        prefixedDigest(request.getTerminalProofDigest(), "terminal_proof_digest");
    boundedText(request.getInitialAdmissionRequestId(), "initial_admission_request_id", 128);
    if (request.getActiveLifecycleEpoch() <= 0
        || request.getCommittedPointerVersion() != 1
        || request.getAuditEventId() <= 0) {
      throw invalid("the exact positive ACTIVE epoch, first pointer, and audit id are required");
    }

    return new Selection(
        uuid(request.getPlacementOperationUuid(), "placement_operation_uuid"),
        target,
        tenantId,
        realmId,
        target.getWorldSlug(),
        target.getRealmSlug(),
        gameInstanceId,
        target.getCatalogRevision(),
        versionId,
        target.getFrozenPolicyDigest(),
        namespaceId,
        target.getPlayableStateScope().name().substring("PLAYABLE_STATE_SCOPE_".length()),
        target.getPublishedReleaseBundleRef(),
        uuid(request.getCanonicalAccountUuid(), "canonical_account_uuid"),
        uuid(request.getCharacterUuid(), "character_uuid"),
        uuid(request.getAssignmentUuid(), "assignment_uuid"),
        assignmentIntentDigest,
        uuid(request.getInitialAdmissionHoldUuid(), "initial_admission_hold_uuid"),
        uuid(request.getInitialAdmissionHoldFence(), "initial_admission_hold_fence"),
        request.getInitialAdmissionRequestId(),
        admissionRequestDigest,
        request.getActiveLifecycleEpoch(),
        terminalProofDigest,
        request.getCommittedPointerVersion(),
        request.getAuditEventId());
  }

  static CanonicalInitialPlayerLocationResponse toResponse(Selection selection, Result result) {
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(result, "result");
    if (!result.retained()
        || result.requestDigest() == null
        || !PREFIXED_SHA256.matcher(result.requestDigest()).matches()
        || result.resultDigest() == null
        || !PREFIXED_SHA256.matcher(result.resultDigest()).matches()
        || result.outcome() == Outcome.UNREGISTERED) {
      throw new IllegalStateException("A retained APPLIED or CONFLICT World result is required");
    }

    var response =
        CanonicalInitialPlayerLocationResponse.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setPlacementOperationUuid(selection.placementOperationId().toString())
            .setRequestDigest(result.requestDigest())
            .setCanonicalAccountUuid(selection.canonicalAccountId().toString())
            .setCharacterUuid(selection.characterId().toString())
            .setAssignmentUuid(selection.assignmentId().toString())
            .setAssignmentIntentDigest(selection.assignmentIntentDigest())
            .setResultDigest(result.resultDigest());
    if (result.outcome() == Outcome.APPLIED) {
      if (result.startLocation() == null
          || result.runtimeRoomInstanceId() == null
          || !selection.canonicalTenantId().equals(result.startLocation().tenantId())
          || !selection.canonicalVersionId().equals(result.startLocation().versionId())
          || result.runtimeRoomInstanceId() <= 0) {
        throw new IllegalStateException(
            "Applied World result lacks its exact retained ROOM binding");
      }
      response
          .setOutcome(
              CanonicalInitialPlayerLocationOutcome
                  .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED)
          .setStartLocation(
              CanonicalInitialPlayerLocationRoom.newBuilder()
                  .setCanonicalTenantUuid(result.startLocation().tenantId().toString())
                  .setCanonicalVersionUuid(result.startLocation().versionId().toString())
                  .setRoomTemplateUuid(result.startLocation().roomTemplateId().toString())
                  .build())
          .setRuntimeRoomInstanceId(Long.toString(result.runtimeRoomInstanceId()));
    } else if (result.outcome() == Outcome.CONFLICT) {
      if (result.conflictCode() == null
          || result.startLocation() != null
          || result.runtimeRoomInstanceId() != null) {
        throw new IllegalStateException("Conflicting World result has an invalid retained shape");
      }
      response
          .setOutcome(
              CanonicalInitialPlayerLocationOutcome
                  .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_CONFLICT)
          .setConflictCode(result.conflictCode());
    } else {
      throw new IllegalStateException("Unsupported World initial-location outcome");
    }
    return response.build();
  }

  private static UUID uuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw invalid(field + " must be canonical non-nil UUID text");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw invalid(field + " must be canonical non-nil UUID text", invalid);
    }
  }

  private static String rawDigest(String value, String field) {
    if (!RAW_SHA256.matcher(value).matches()) {
      throw invalid(field + " must be a lowercase SHA-256 digest");
    }
    return value;
  }

  private static String prefixedDigest(String value, String field) {
    if (!PREFIXED_SHA256.matcher(value).matches()) {
      throw invalid(field + " must be a sha256:-prefixed digest");
    }
    return value;
  }

  private static String boundedText(String value, String field, int maxLength) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.trim())
        || value.length() > maxLength
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid(field + " must be bounded canonical text");
    }
    return value;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(
        "Invalid World initial-player-location request: " + message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(
        "Invalid World initial-player-location request: " + message, cause);
  }

  record Selection(
      UUID placementOperationId,
      net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
          expectedTarget,
      UUID canonicalTenantId,
      UUID realmId,
      String worldSlug,
      String realmSlug,
      UUID canonicalGameInstanceId,
      long catalogRevision,
      UUID canonicalVersionId,
      String frozenPolicyDigest,
      UUID playableStateNamespaceId,
      String playableStateScope,
      String publishedReleaseBundleRef,
      UUID canonicalAccountId,
      UUID characterId,
      UUID assignmentId,
      String assignmentIntentDigest,
      UUID initialAdmissionHoldId,
      UUID initialAdmissionHoldFence,
      String initialAdmissionRequestId,
      String initialAdmissionRequestDigest,
      long activeLifecycleEpoch,
      String terminalProofDigest,
      long committedPointerVersion,
      long auditEventId) {
    Selection {
      Objects.requireNonNull(placementOperationId, "placementOperationId");
      Objects.requireNonNull(expectedTarget, "expectedTarget");
      Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
      Objects.requireNonNull(realmId, "realmId");
      Objects.requireNonNull(canonicalGameInstanceId, "canonicalGameInstanceId");
      Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
      Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(canonicalAccountId, "canonicalAccountId");
      Objects.requireNonNull(characterId, "characterId");
      Objects.requireNonNull(assignmentId, "assignmentId");
      Objects.requireNonNull(initialAdmissionHoldId, "initialAdmissionHoldId");
      Objects.requireNonNull(initialAdmissionHoldFence, "initialAdmissionHoldFence");
    }
  }
}
