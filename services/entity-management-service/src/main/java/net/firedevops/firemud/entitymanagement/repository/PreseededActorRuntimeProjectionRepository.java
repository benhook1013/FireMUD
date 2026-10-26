package net.firedevops.firemud.entitymanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceipt;
import net.firedevops.firemud.entitymanagement.service.PreseededActorRuntimeReference;
import net.firedevops.firemud.entitymanagement.service.RuntimeEntityId;
import org.jooq.DSLContext;

/**
 * Narrow owner-internal projection storage. Allocation joins the existing assignment transaction.
 */
final class PreseededActorRuntimeProjectionRepository {
  private final DSLContext dsl;

  PreseededActorRuntimeProjectionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  void allocateFreshAssignment(PreseededActorAssignmentReceipt receipt) {
    // This is only invoked after the fresh assignment owner commit path has validated all sources.
    // Never invoked by replay or read: historical assignments cannot authorize backfill.
    int inserted =
        dsl.execute(
            """
        INSERT INTO entity_preseeded_actor_runtime_projections
          (tenant_uuid, playable_state_namespace_id, game_instance_id, character_uuid,
           assignment_uuid, intent_digest, published_owner_proof_digest)
        SELECT tenant_uuid, playable_state_namespace_id, game_instance_id, character_uuid,
               assignment_uuid, intent_digest, published_owner_proof_digest
          FROM entity_preseeded_actor_assignment_operations
         WHERE assignment_uuid = ? AND character_uuid = ? AND intent_digest = ?
           AND status = 'ASSIGNED' AND publication_evidence_format = 'CANONICAL_UUID'
        """,
            receipt.assignmentUuid(),
            receipt.characterUuid(),
            receipt.intentDigest());
    if (inserted != 1) {
      throw new IllegalStateException("Fresh committed runtime projection source is unavailable");
    }
  }

  Optional<PreseededActorRuntimeReference> read(PreseededActorAssignmentReceipt receipt) {
    var target = receipt.target();
    var row =
        dsl.fetchOne(
            """
        SELECT runtime_entity_id, projection_generation, projection_fence
          FROM entity_preseeded_actor_runtime_projections
         WHERE tenant_uuid = ? AND playable_state_namespace_id = ? AND game_instance_id = ?
           AND character_uuid = ? AND assignment_uuid = ? AND intent_digest = ?
           AND published_owner_proof_digest = ? AND projection_state = 'CURRENT'
        """,
            target.canonicalTenantUuid(),
            target.playableStateNamespaceId(),
            target.gameInstanceId(),
            receipt.characterUuid(),
            receipt.assignmentUuid(),
            receipt.intentDigest(),
            receipt.publishedOwnerProofDigest());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new PreseededActorRuntimeReference(
            new RuntimeEntityId(row.get("runtime_entity_id", Long.class)),
            row.get("projection_generation", Long.class),
            row.get("projection_fence", Long.class),
            receipt));
  }
}
