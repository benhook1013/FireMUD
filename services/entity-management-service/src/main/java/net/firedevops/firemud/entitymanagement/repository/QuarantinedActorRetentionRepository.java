package net.firedevops.firemud.entitymanagement.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Holds ambiguous runtime evidence and quarantined-actor closure before instance cleanup. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class QuarantinedActorRetentionRepository {
  private static final String HAS_UNRESOLVED_RUNTIME_EVIDENCE_SQL =
      """
      SELECT
          EXISTS (
              SELECT 1
              FROM item_instances item_instance
              WHERE item_instance.tenant_id = ?
                AND item_instance.game_instance_id = ?
          )
          OR EXISTS (
              SELECT 1
              FROM container_instances container
              WHERE container.tenant_id = ?
                AND container.game_instance_id = ?
          )
          OR EXISTS (
              SELECT 1
              FROM item_stacks stack
              WHERE stack.tenant_id = ?
                AND stack.game_instance_id = ?
          )
          OR EXISTS (
              SELECT 1
              FROM room_ground_inventory ground
              WHERE ground.tenant_id = ?
                AND ground.game_instance_id = ?
          )
          OR EXISTS (
              SELECT 1
              FROM item_transfer_audits audit
              JOIN characters actor
                ON actor.tenant_id = audit.tenant_id
               AND actor.actor_identity_status = 'QUARANTINED'
               AND (
                    actor.id = audit.actor_character_id
                    OR actor.id = audit.source_character_id
                    OR actor.id = audit.destination_character_id
               )
              WHERE audit.tenant_id = ?
                AND (
                    audit.source_game_instance_id = ?
                    OR audit.destination_game_instance_id = ?
                )
          )
      """;

  private final DSLContext dsl;

  public QuarantinedActorRetentionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public boolean hasUnresolvedRuntimeEvidence(Long tenantId, String gameInstanceId) {
    Object retained =
        dsl.fetchValue(
            HAS_UNRESOLVED_RUNTIME_EVIDENCE_SQL,
            tenantId,
            gameInstanceId,
            tenantId,
            gameInstanceId,
            tenantId,
            gameInstanceId,
            tenantId,
            gameInstanceId,
            tenantId,
            gameInstanceId,
            gameInstanceId);
    return Boolean.TRUE.equals(retained);
  }
}
