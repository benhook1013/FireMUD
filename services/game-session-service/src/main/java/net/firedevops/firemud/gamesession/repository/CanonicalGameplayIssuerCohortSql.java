package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;

/** Shared exact SQL and binding order for capturing and qualifying the issuer bootstrap cohort. */
final class CanonicalGameplayIssuerCohortSql {
  private static final String COHORT_SQL =
      "SELECT source_kind, source_key, source_revision, source_row_json FROM ("
          + "SELECT CASE WHEN i.issuer_id = ? THEN 'BINDING'"
          + " ELSE 'UNRESOLVED_LEGACY_BINDING' END AS source_kind,"
          + " encode(i.binding_ref, 'hex') AS source_key, i.inventory_revision AS source_revision,"
          + " to_jsonb(i)::text AS source_row_json FROM game_session_canonical_gameplay_binding_inventory i"
          + " WHERE i.inventory_revision <= ?"
          + " UNION ALL SELECT 'TRANSITION' AS source_kind, t.transition_id::text AS source_key,"
          + " t.inventory_revision AS source_revision,"
          // Preserve frozen pre-V1035 transition bytes for the absence-equivalent new NULL field.
          // A definitive abort UUID remains part of the full captured source.
          + " CASE WHEN to_jsonb(t)->'admission_orphan_cleanup_id' = 'null'::jsonb"
          + " THEN (to_jsonb(t) - 'admission_orphan_cleanup_id')::text"
          + " ELSE to_jsonb(t)::text END AS source_row_json"
          + " FROM game_session_canonical_binding_transition t WHERE t.inventory_revision <= ?"
          + " UNION ALL SELECT CASE WHEN r.issuer_id = ? THEN 'RESERVATION'"
          + " ELSE 'UNRESOLVED_LEGACY_RESERVATION' END AS source_kind,"
          + " r.reservation_id::text AS source_key, r.inventory_revision AS source_revision,"
          + " to_jsonb(r)::text AS source_row_json FROM game_session_canonical_issuer_partition_reservation r"
          + " WHERE r.inventory_revision <= ?"
          + " UNION ALL SELECT 'ACCOUNT_OBLIGATION' AS source_kind,"
          + " a.transition_id::text || ':' || a.obligation_ordinal::text AS source_key,"
          + " a.inventory_revision AS source_revision, to_jsonb(a)::text AS source_row_json"
          + " FROM game_session_canonical_binding_account_index_obligation a"
          + " WHERE a.inventory_revision <= ?"
          + " UNION ALL SELECT 'ISSUER_OBLIGATION' AS source_kind,"
          + " encode(o.binding_ref, 'hex') || ':' || o.transition_id::text AS source_key,"
          + " o.inventory_revision AS source_revision,"
          // Preserve pre-V1036 frozen bytes only for the absence-equivalent new NULL linkage.
          // An actual release-operation identity remains significant captured source evidence.
          + " CASE WHEN to_jsonb(o)->'local_release_operation_id' = 'null'::jsonb"
          + " THEN (to_jsonb(o) - 'local_release_operation_id')::text"
          + " ELSE to_jsonb(o)::text END AS source_row_json"
          + " FROM game_session_canonical_binding_issuer_index_obligation o"
          + " WHERE o.inventory_revision <= ?"
          + " UNION ALL SELECT 'REGION_OBLIGATION' AS source_kind,"
          + " g.transition_id::text AS source_key, g.inventory_revision AS source_revision,"
          // V1042's untouched bridge extension is absence-equivalent to the old row shape.
          // Its CHECK makes every extension field NULL until a real bridge operation exists.
          // Once materialized, capture the entire bridge source, including all readback evidence.
          + " CASE WHEN to_jsonb(g)->'bridge_operation_id' = 'null'::jsonb"
          + " THEN (to_jsonb(g) - ARRAY['bridge_operation_id', 'bridge_preimage_state',"
          + " 'runtime_entity_id', 'entity_projection_generation', 'entity_projection_fence',"
          + " 'entity_workload_namespace', 'assignment_uuid', 'assignment_intent_digest',"
          + " 'entity_reference_digest', 'world_source_digest', 'canonical_region_id',"
          + " 'world_lifecycle_epoch', 'world_lifecycle_row_version', 'world_capture_id',"
          + " 'world_graph_sha256', 'world_preparation_input_digest', 'executor_fence',"
          + " 'lease_token_correlation', 'redis_readback_digest', 'acknowledged_at'])::text"
          + " ELSE to_jsonb(g)::text END AS source_row_json"
          + " FROM game_session_canonical_binding_region_bridge_obligation g"
          + " WHERE g.inventory_revision <= ?"
          + ") captured_cohort ORDER BY source_kind COLLATE \"C\", source_key COLLATE \"C\"";

  static final String FUTURE_REVISION_SQL =
      "SELECT EXISTS (SELECT 1 FROM game_session_canonical_gameplay_binding_inventory"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_transition"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_partition_reservation"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_account_index_obligation"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_issuer_index_obligation"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_region_bridge_obligation"
          + " WHERE inventory_revision > ?) AS has_future_revision";

  private CanonicalGameplayIssuerCohortSql() {}

  static String cohortSql() {
    return COHORT_SQL;
  }

  static Object[] cohortBindings(String issuerId, BigDecimal inventoryRevision) {
    return new Object[] {
      issuerId,
      inventoryRevision,
      inventoryRevision,
      issuerId,
      inventoryRevision,
      inventoryRevision,
      inventoryRevision,
      inventoryRevision
    };
  }

  static Object[] futureRevisionBindings(BigDecimal inventoryRevision) {
    return new Object[] {
      inventoryRevision,
      inventoryRevision,
      inventoryRevision,
      inventoryRevision,
      inventoryRevision,
      inventoryRevision
    };
  }
}
