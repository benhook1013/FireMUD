package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import org.jooq.DSLContext;

/** Owner-local census of corroborated ordinary Entity digest-1 baselines. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "DSLContext is an internal repository collaborator.")
public final class EntityDigestRetainedBaselineCensusRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final int MAX_PAGE_SIZE = 500;

  // Compare the numeric scope as text against the owner row, so malformed retained scope values
  // never need to be parsed. The release's original bytes independently corroborate the baseline.
  private static final String FIND_PAGE =
      """
      SELECT baseline.id AS baseline_id, owner.tenant_id, owner.id AS game_row_id,
             owner.tenant_identity_provenance_kind, target.id AS version_id,
             target.canonical_version_id, target.version_number, target.version_state,
             target.version_state_epoch, baseline.applied_commit_id, baseline.content_digest,
             baseline.digest_schema_version, baseline.recorded_from_publish_workflow_id,
             baseline.recorded_at, baseline.last_verified_publish_workflow_id,
             baseline.last_verified_at, attempt.id AS attempt_id,
             observation.observation_id, bundle.id AS release_bundle_id, artifact.id AS artifact_id
      FROM game AS owner
      JOIN version AS target
        ON target.identity_source_game_row_id = owner.id
       AND target.identity_source_game_tenant_key = owner.tenant_id
       AND target.identity_source_provenance_kind = owner.tenant_identity_provenance_kind
       AND target.tenant_id = owner.tenant_id
       AND target.canonical_tenant_id = owner.canonical_tenant_id
      JOIN publish_recorded_participant_digest AS baseline
        ON baseline.tenant_id = target.tenant_id AND baseline.scope_value = target.id::text
      JOIN published_release_bundle AS bundle
        ON bundle.tenant_id = target.tenant_id AND bundle.version_id = target.id
       AND bundle.version_number = target.version_number
       AND bundle.canonical_tenant_id = target.canonical_tenant_id
       AND bundle.canonical_version_id = target.canonical_version_id
       AND bundle.publish_workflow_id = baseline.recorded_from_publish_workflow_id
      JOIN publish_attempt AS attempt
        ON attempt.publish_workflow_id = bundle.publish_workflow_id
       AND attempt.tenant_id = target.tenant_id AND attempt.version_id = target.id
       AND attempt.version_number = target.version_number
      JOIN version_asset_artifact AS artifact
        ON artifact.tenant_id = target.tenant_id AND artifact.version_id = target.id
       AND artifact.exported_version_number = target.version_number
       AND artifact.last_workflow_id = bundle.publish_workflow_id
       AND artifact.manifest_hash = bundle.manifest_hash
       AND artifact.exported_manifest_asset_keys_json = bundle.required_manifest_asset_keys_json
      JOIN LATERAL (
        SELECT min(observed.id) AS observation_id
        FROM publish_attempt_participant_digest AS observed
        WHERE observed.publish_attempt_id = attempt.id
          AND observed.participant_key = baseline.participant_key
        HAVING count(*) = 1 AND bool_and((
          observed.id > 0
          AND observed.scope_value = baseline.scope_value
          AND observed.base_version_id IS NULL
          AND observed.applied_commit_id = baseline.applied_commit_id
          AND observed.content_digest = baseline.content_digest
          AND observed.digest_schema_version = baseline.digest_schema_version
          AND observed.error_code IS NULL AND observed.error_message IS NULL
        ) IS TRUE)
      ) AS observation ON TRUE
      WHERE owner.canonical_tenant_id = ?
        AND owner.id > 0
        AND owner.tenant_identity_source_game_id = owner.id
        AND owner.tenant_identity_source_legacy_tenant_id = owner.tenant_id
        AND owner.tenant_identity_provenance_kind IN ('RETAINED_GAME_V29', 'NEW_GAME_ROW')
        AND owner.tenant_id ~ '^[1-9][0-9]{0,18}$'
        AND (length(owner.tenant_id) < 19 OR owner.tenant_id <= '9223372036854775807')
        AND target.canonical_version_id IS NOT NULL
        AND target.id > 0 AND target.version_number > 0 AND target.version_state_epoch > 0
        AND target.version_state IN ('PUBLISHED', 'ACTIVE', 'RETIRED')
        AND NOT target.is_script_only
        AND target.base_version_id IS NULL AND target.script_patch_version IS NULL
        AND baseline.id > ? AND baseline.publish_type = 'FULL_VERSION'
        AND baseline.participant_key = 'ENTITY_MANAGEMENT' AND baseline.base_version_id IS NULL
        AND baseline.digest_schema_version = 1
        AND baseline.applied_commit_id = 'version:' || target.id::text
        AND baseline.content_digest ~ '^[0-9a-f]{64}$'
        AND baseline.recorded_from_publish_workflow_id ~
            ('^publish:' || owner.tenant_id || ':publish-request:[^:]+$')
        AND baseline.last_verified_publish_workflow_id = baseline.recorded_from_publish_workflow_id
        AND isfinite(baseline.recorded_at) AND isfinite(baseline.last_verified_at)
        AND baseline.last_verified_at >= baseline.recorded_at
        AND bundle.id > 0 AND bundle.attestation_schema_version = 'v1'
        AND NOT bundle.script_only AND bundle.script_patch_version IS NULL
        AND bundle.world_published_start_location_evidence_json IS NULL
        AND bundle.manifest_hash ~ '[^[:space:]]'
        AND attempt.id > 0 AND attempt.publish_type = 'FULL_VERSION' AND attempt.status = 'SUCCEEDED'
        AND attempt.completed_at IS NOT NULL
        AND attempt.failure_code IS NULL AND attempt.failure_message IS NULL
        AND attempt.base_version_id IS NULL AND attempt.script_patch_version IS NULL
        AND artifact.id > 0 AND artifact.artifact_state = 'PUBLISHED' AND artifact.state_epoch > 0
        AND artifact.last_error_code IS NULL AND artifact.last_error_message IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM game_design_authored_draft_publish_selection AS selected
          WHERE selected.game_design_version_tenant_key = target.tenant_id
            AND selected.game_design_version_row_id = target.id
        )
        AND NOT EXISTS (
          SELECT 1 FROM game_design_publication_operation AS operation
          WHERE operation.tenant_id = target.tenant_id AND operation.version_id = target.id
        )
        AND NOT EXISTS (
          SELECT 1 FROM publish_recorded_participant_digest AS other
          WHERE other.tenant_id = baseline.tenant_id AND other.publish_type = baseline.publish_type
            AND other.participant_key = baseline.participant_key
            AND other.base_version_id IS NULL AND other.scope_value = baseline.scope_value
            AND other.id <> baseline.id
        )
        AND (
          SELECT count(*) = 1 AND bool_and(item @> jsonb_build_object(
            'scopeValue', baseline.scope_value,
            'appliedCommitId', baseline.applied_commit_id,
            'contentDigest', baseline.content_digest,
            'digestSchemaVersion', baseline.digest_schema_version
          ) AND item->>'baseVersionId' IS NULL AND item->>'errorCode' IS NULL
            AND item->>'errorMessage' IS NULL AND item->>'abilitySchemaDigest' IS NULL)
          FROM jsonb_array_elements(CASE
            WHEN pg_input_is_valid(bundle.participant_digests_json, 'jsonb')
             AND bundle.participant_digests_json IS JSON ARRAY WITH UNIQUE KEYS
            THEN bundle.participant_digests_json::jsonb
            ELSE '[]'::jsonb
          END) AS entries(item)
          WHERE item->>'participantKey' = baseline.participant_key
        )
      ORDER BY baseline.id
      LIMIT ?
      """;

  private final DSLContext dsl;

  public EntityDigestRetainedBaselineCensusRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Reads one page for the exact canonical tenant, in ascending baseline row ID order. Use zero for
   * the first page and the last returned row ID for the next page of the same tenant. Each page
   * observes current committed evidence independently.
   *
   * <p>A finding describes consistent retained storage only. An empty page makes no claim about
   * legacy rows that lack canonical provenance or complete ordinary publication evidence.
   */
  public List<RetainedBaselineFinding> findPage(
      UUID canonicalTenantId, long afterBaselineId, int limit) {
    if (canonicalTenantId == null || NIL_UUID.equals(canonicalTenantId)) {
      throw new IllegalArgumentException("canonicalTenantId must be a non-nil UUID");
    }
    if (afterBaselineId < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("cursor must be non-negative and limit must be 1–500");
    }
    return List.copyOf(
        dsl.fetch(FIND_PAGE, canonicalTenantId, afterBaselineId, limit)
            .map(
                row ->
                    new RetainedBaselineFinding(
                        row.get("baseline_id", Long.class),
                        canonicalTenantId,
                        row.get("tenant_id", String.class),
                        row.get("game_row_id", Long.class),
                        row.get("tenant_identity_provenance_kind", String.class),
                        row.get("version_id", Long.class),
                        row.get("canonical_version_id", UUID.class),
                        row.get("version_number", Integer.class),
                        VersionLifecycleState.valueOf(row.get("version_state", String.class)),
                        row.get("version_state_epoch", Long.class),
                        row.get("applied_commit_id", String.class),
                        row.get("content_digest", String.class),
                        row.get("digest_schema_version", Integer.class),
                        row.get("recorded_from_publish_workflow_id", String.class),
                        row.get("recorded_at", LocalDateTime.class),
                        row.get("last_verified_publish_workflow_id", String.class),
                        row.get("last_verified_at", LocalDateTime.class),
                        row.get("attempt_id", Long.class),
                        row.get("observation_id", Long.class),
                        row.get("release_bundle_id", Long.class),
                        row.get("artifact_id", Long.class))));
  }

  public record RetainedBaselineFinding(
      long baselineId,
      UUID canonicalTenantId,
      String tenantKey,
      long gameRowId,
      String tenantIdentityProvenance,
      long versionId,
      UUID canonicalVersionId,
      int versionNumber,
      VersionLifecycleState versionState,
      long versionStateEpoch,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion,
      String recordedFromPublishWorkflowId,
      LocalDateTime recordedAt,
      String lastVerifiedPublishWorkflowId,
      LocalDateTime lastVerifiedAt,
      long publishAttemptId,
      long participantObservationId,
      long releaseBundleId,
      long publishedArtifactId) {}
}
