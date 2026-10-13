package net.firedevops.firemud.entitymanagement.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Detects unclassified runtime rows and quarantined-actor audit evidence before cleanup. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class QuarantinedActorRetentionRepository {
  private static final String HAS_UNCLASSIFIED_OR_QUARANTINED_RUNTIME_EVIDENCE_SQL =
      """
      WITH target AS (
          SELECT CAST(? AS BIGINT) AS tenant_id,
                 CAST(? AS TEXT) AS game_instance_id
      )
      SELECT
          CAST((EXISTS (
              SELECT 1
              FROM item_instances item_instance
              CROSS JOIN target
              WHERE item_instance.tenant_id = target.tenant_id
                AND item_instance.game_instance_id = target.game_instance_id
          )
          OR EXISTS (
              SELECT 1
              FROM container_instances container
              CROSS JOIN target
              WHERE container.tenant_id = target.tenant_id
                AND container.game_instance_id = target.game_instance_id
          )
          OR EXISTS (
              SELECT 1
              FROM item_stacks stack
              CROSS JOIN target
              WHERE stack.tenant_id = target.tenant_id
                AND stack.game_instance_id = target.game_instance_id
          )
          OR EXISTS (
              SELECT 1
              FROM room_ground_inventory ground
              CROSS JOIN target
              WHERE ground.tenant_id = target.tenant_id
                AND ground.game_instance_id = target.game_instance_id
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
              CROSS JOIN target
              WHERE audit.tenant_id = target.tenant_id
                AND (
                    audit.source_game_instance_id = target.game_instance_id
                    OR audit.destination_game_instance_id = target.game_instance_id
                )
          )) AS BOOLEAN)
      """;

  private final DSLContext dsl;

  public QuarantinedActorRetentionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public boolean hasUnclassifiedOrQuarantinedRuntimeEvidence(Long tenantId, String gameInstanceId) {
    Record result =
        dsl.fetchOne(
            DSL.sql(
                HAS_UNCLASSIFIED_OR_QUARANTINED_RUNTIME_EVIDENCE_SQL, tenantId, gameInstanceId));
    if (result == null) {
      throw new IllegalStateException("RUNTIME_EVIDENCE_READBACK_UNAVAILABLE");
    }
    Boolean retained = result.get(0, Boolean.class);
    if (retained == null) {
      throw new IllegalStateException("RUNTIME_EVIDENCE_READBACK_UNAVAILABLE");
    }
    return retained;
  }
}
