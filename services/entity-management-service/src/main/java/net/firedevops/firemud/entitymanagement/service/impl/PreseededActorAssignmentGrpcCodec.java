package net.firedevops.firemud.entitymanagement.service.impl;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentOutcome;
import net.firedevops.firemud.entitymanagement.validation.CanonicalEntitySourceValues;

/** Closed schema-1 codec for the run-owned preseeded assignment operation. */
final class PreseededActorAssignmentGrpcCodec {
  static final int SCHEMA_VERSION = 1;

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
    if (!request.getExpectedTarget().getUnknownFields().asMap().isEmpty()
        || !request.getCorePayload().getUnknownFields().asMap().isEmpty()) {
      throw invalid("assignment intent contains unknown fields");
    }

    var target = request.getExpectedTarget();
    PlayableStateScope scope = parseScope(target.getPlayableStateScope());
    var expectedTarget =
        new PreseededActorAssignmentExpectedTarget(
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

    var core = request.getCorePayload();
    if (core.getActorKind()
        != CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER) {
      throw invalid("actor_kind must be PLAYER");
    }
    var corePayload =
        new PreseededActorCorePayload(
            PreseededActorCorePayload.ActorKind.PLAYER, core.getDisplayName());

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
      UUID actorUuid =
          CanonicalEntitySourceValues.parseCanonicalNonNilUuid(result.characterUuid().toString());
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

  private static UUID parseUuid(String value, String field) {
    try {
      return CanonicalEntitySourceValues.parseCanonicalNonNilUuid(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", invalid);
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
}
