package net.firedevops.firemud.entitymanagement.service.impl;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceipt;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentOutcome;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentReceiptStatus;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptResponse;
import net.firedevops.firemud.entitymanagement.validation.CanonicalEntitySourceValues;

/** Closed schema-1 codecs for the run-owned assignment and World-owned receipt read. */
final class PreseededActorAssignmentGrpcCodec {
  static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private PreseededActorAssignmentGrpcCodec() {}

  static PreseededActorAssignmentRequest fromRequest(AssignPreseededActorRequest request) {
    if (request == null) {
      throw invalid("request is required");
    }
    if (request.getSchemaVersion() != SCHEMA_VERSION) {
      throw invalid("unsupported schema_version");
    }
    if (!request.getUnknownFields().asMap().isEmpty()) {
      throw invalid("request contains unknown fields");
    }
    if (!request.hasExpectedTarget() || !request.hasCorePayload()) {
      throw invalid("complete assignment intent is required");
    }

    var expectedTarget = fromExpectedTarget(request.getExpectedTarget());
    var corePayload = fromCorePayload(request.getCorePayload());
    var assignment =
        new PreseededActorAssignmentRequest(
            parseUuid(request.getAssignmentUuid(), "assignment_uuid"),
            parseUuid(request.getCanonicalAccountUuid(), "canonical_account_uuid"),
            corePayload,
            expectedTarget);
    String suppliedDigest = request.getMutationIntentDigest();
    if (!CanonicalEntitySourceValues.isRawSha256(suppliedDigest)
        || !assignment.mutationIntentDigest().equals(suppliedDigest)) {
      throw invalid("mutation_intent_digest does not match the exact assignment intent");
    }
    return assignment;
  }

  static AssignPreseededActorResponse toResponse(
      PreseededActorAssignmentRequest request, PreseededActorAssignmentResult result) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(result, "result");
    if (!request.assignmentUuid().equals(result.assignmentUuid())
        || !request.mutationIntentDigest().equals(result.intentDigest())) {
      throw new IllegalStateException("Persisted assignment result differs from exact request");
    }

    var response =
        AssignPreseededActorResponse.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setAssignmentUuid(result.assignmentUuid().toString())
            .setIntentDigest(result.intentDigest());
    if (result.outcome() == PreseededActorAssignmentResult.Outcome.ASSIGNED) {
      UUID actorUuid = requireCanonicalPersistedUuid(result.characterUuid());
      response
          .setOutcome(PreseededActorAssignmentOutcome.PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_ASSIGNED)
          .setCharacterUuid(actorUuid.toString());
    } else if (result.outcome() == PreseededActorAssignmentResult.Outcome.IDEMPOTENCY_CONFLICT) {
      if (result.characterUuid() != null) {
        throw new IllegalStateException("Idempotency-conflict result cannot expose an actor UUID");
      }
      response.setOutcome(
          PreseededActorAssignmentOutcome.PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_IDEMPOTENCY_CONFLICT);
    } else {
      throw new IllegalStateException("Unsupported persisted assignment outcome");
    }
    return response.build();
  }

  static ReceiptReadRequest fromReceiptReadRequest(
      ReadPreseededActorAssignmentReceiptRequest request) {
    if (request == null) {
      throw invalid("request is required");
    }
    if (request.getSchemaVersion() != SCHEMA_VERSION) {
      throw invalid("unsupported schema_version");
    }
    if (!request.getUnknownFields().asMap().isEmpty()) {
      throw invalid("request contains unknown fields");
    }
    if (!request.hasExpectedTarget()) {
      throw invalid("complete receipt selector is required");
    }

    var selector =
        new PreseededActorAssignmentReceiptRequest(
            parseUuid(request.getAssignmentUuid(), "assignment_uuid"),
            parseUuid(request.getCanonicalAccountUuid(), "canonical_account_uuid"),
            parseUuid(request.getCharacterUuid(), "character_uuid"),
            request.getIntentDigest(),
            fromExpectedTarget(request.getExpectedTarget()));
    UUID placementOperationUuid =
        parseUuid(request.getPlacementOperationUuid(), "placement_operation_uuid");
    return new ReceiptReadRequest(placementOperationUuid, selector);
  }

  static ReadPreseededActorAssignmentReceiptResponse toReceiptReadResponse(
      ReceiptReadRequest request, PreseededActorAssignmentReceipt receipt) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(receipt, "receipt");
    var selector = request.receiptRequest();
    if (!selector.assignmentUuid().equals(receipt.assignmentUuid())
        || !selector.canonicalAccountUuid().equals(receipt.canonicalAccountUuid())
        || !selector.characterUuid().equals(receipt.characterUuid())
        || !selector.intentDigest().equals(receipt.intentDigest())
        || !selector.expectedTarget().equals(receipt.target())
        || !"ASSIGNED".equals(receipt.status())) {
      throw new IllegalStateException("Persisted assignment receipt differs from exact selector");
    }
    UUID assignmentUuid = requireCanonicalPersistedUuid(receipt.assignmentUuid());
    UUID accountUuid = requireCanonicalPersistedUuid(receipt.canonicalAccountUuid());
    UUID characterUuid = requireCanonicalPersistedUuid(receipt.characterUuid());
    var persistedIntent =
        new PreseededActorAssignmentRequest(
            assignmentUuid, accountUuid, receipt.corePayload(), receipt.target());
    if (!persistedIntent.mutationIntentDigest().equals(receipt.intentDigest())) {
      throw new IllegalStateException("Persisted assignment receipt has an invalid intent digest");
    }

    return ReadPreseededActorAssignmentReceiptResponse.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setPlacementOperationUuid(request.placementOperationUuid().toString())
        .setAssignmentUuid(assignmentUuid.toString())
        .setCanonicalAccountUuid(accountUuid.toString())
        .setCharacterUuid(characterUuid.toString())
        .setIntentDigest(receipt.intentDigest())
        .setExpectedTarget(toWireExpectedTarget(receipt.target()))
        .setCorePayload(toWireCorePayload(receipt.corePayload()))
        .setStatus(
            PreseededActorAssignmentReceiptStatus
                .PRESEEDED_ACTOR_ASSIGNMENT_RECEIPT_STATUS_ASSIGNED)
        .build();
  }

  private static PreseededActorAssignmentExpectedTarget fromExpectedTarget(
      net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget target) {
    if (!target.getUnknownFields().asMap().isEmpty()) {
      throw invalid("expected_target contains unknown fields");
    }
    PlayableStateScope scope = parseScope(target.getPlayableStateScope());
    return new PreseededActorAssignmentExpectedTarget(
        parseUuid(target.getCanonicalTenantUuid(), "canonical_tenant_uuid"),
        parseUuid(target.getRealmUuid(), "realm_uuid"),
        target.getWorldSlug(),
        target.getRealmSlug(),
        target.getGameInstanceId(),
        target.getCatalogRevision(),
        parseUuid(target.getCanonicalVersionUuid(), "canonical_version_uuid"),
        target.getFrozenPolicyDigest(),
        parseUuid(target.getPlayableStateNamespaceUuid(), "playable_state_namespace_uuid"),
        target.getPublishedReleaseBundleRef(),
        scope);
  }

  private static PreseededActorCorePayload fromCorePayload(
      net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload core) {
    if (!core.getUnknownFields().asMap().isEmpty()) {
      throw invalid("core_payload contains unknown fields");
    }
    if (core.getActorKind()
        != CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER) {
      throw invalid("actor_kind must be PLAYER");
    }
    return new PreseededActorCorePayload(
        PreseededActorCorePayload.ActorKind.PLAYER, core.getDisplayName());
  }

  private static net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
      toWireExpectedTarget(PreseededActorAssignmentExpectedTarget target) {
    return net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
        .newBuilder()
        .setCanonicalTenantUuid(target.canonicalTenantUuid().toString())
        .setRealmUuid(target.realmUuid().toString())
        .setWorldSlug(target.worldSlug())
        .setRealmSlug(target.realmSlug())
        .setGameInstanceId(target.gameInstanceId())
        .setCatalogRevision(target.catalogRevision())
        .setCanonicalVersionUuid(target.canonicalVersionUuid().toString())
        .setFrozenPolicyDigest(target.frozenPolicyDigest())
        .setPlayableStateNamespaceUuid(target.playableStateNamespaceId().toString())
        .setPublishedReleaseBundleRef(target.publishedReleaseBundleRef())
        .setPlayableStateScope(target.playableStateScope())
        .build();
  }

  private static net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
      toWireCorePayload(PreseededActorCorePayload core) {
    if (core.actorKind() != PreseededActorCorePayload.ActorKind.PLAYER) {
      throw new IllegalStateException("Persisted assignment core must be PLAYER");
    }
    return net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
        .newBuilder()
        .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
        .setDisplayName(core.displayName())
        .build();
  }

  private static UUID parseUuid(String value, String field) {
    try {
      return CanonicalEntitySourceValues.parseCanonicalNonNilUuid(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static UUID requireCanonicalPersistedUuid(UUID value) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalStateException("Persisted assignment receipt contains an invalid UUID");
    }
    try {
      return CanonicalEntitySourceValues.parseCanonicalNonNilUuid(value.toString());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Persisted assignment receipt contains an invalid UUID", invalid);
    }
  }

  private static PlayableStateScope parseScope(PlayableStateScope scope) {
    return switch (scope) {
      case PLAYABLE_STATE_SCOPE_SHARED, PLAYABLE_STATE_SCOPE_ISOLATED -> scope;
      default -> throw invalid("playable_state_scope must be SHARED or ISOLATED");
    };
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("Invalid preseeded actor assignment request: " + message);
  }

  record ReceiptReadRequest(
      UUID placementOperationUuid, PreseededActorAssignmentReceiptRequest receiptRequest) {
    ReceiptReadRequest {
      Objects.requireNonNull(placementOperationUuid, "placementOperationUuid");
      Objects.requireNonNull(receiptRequest, "receiptRequest");
      if (NIL_UUID.equals(placementOperationUuid)) {
        throw invalid("placement_operation_uuid must be non-nil");
      }
    }
  }
}
