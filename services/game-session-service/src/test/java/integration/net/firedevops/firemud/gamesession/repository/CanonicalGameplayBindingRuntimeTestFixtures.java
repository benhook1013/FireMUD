package net.firedevops.firemud.gamesession.repository;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Shared PostgreSQL fixtures for lease-bound binding tests that require a real launch target. */
public final class CanonicalGameplayBindingRuntimeTestFixtures {
  private static final String SHA256 = "sha256:" + "a".repeat(64);
  private static final String RAW_SHA256 = "a".repeat(64);

  private CanonicalGameplayBindingRuntimeTestFixtures() {}

  public static RuntimeTarget seedRunningLaunch(DSLContext dsl) {
    return seedRunningLaunch(dsl, UUID.randomUUID(), 1L, 23L, UUID.randomUUID());
  }

  /**
   * Seeds a fully linked fresh tenant, catalog, launch, and RUNNING runtime for the supplied
   * candidate identity. All deferred constraints and normal owner triggers remain enabled.
   */
  public static RuntimeTarget seedRunningLaunch(
      DSLContext dsl,
      UUID canonicalTenantId,
      long gameSessionTenantId,
      long gameInstanceId,
      UUID gameInstanceUuid) {
    return seedRunningLaunch(
        dsl,
        canonicalTenantId,
        gameSessionTenantId,
        gameInstanceId,
        gameInstanceUuid,
        UUID.randomUUID());
  }

  public static RuntimeTarget seedRunningLaunch(
      DSLContext dsl,
      UUID canonicalTenantId,
      long gameSessionTenantId,
      long gameInstanceId,
      UUID gameInstanceUuid,
      UUID playableStateNamespaceId) {
    if (gameSessionTenantId <= 0 || gameInstanceId <= 0) {
      throw new IllegalArgumentException("Runtime identifiers must be positive");
    }
    if (canonicalTenantId == null
        || gameInstanceUuid == null
        || playableStateNamespaceId == null
        || new UUID(0L, 0L).equals(canonicalTenantId)
        || new UUID(0L, 0L).equals(gameInstanceUuid)
        || new UUID(0L, 0L).equals(playableStateNamespaceId)) {
      throw new IllegalArgumentException("Canonical runtime identities must be non-nil");
    }

    RuntimeTarget target =
        RuntimeTarget.create(
            canonicalTenantId,
            gameSessionTenantId,
            gameInstanceId,
            gameInstanceUuid,
            playableStateNamespaceId);
    dsl.transaction(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          seedSourceAndCatalog(tx, target);
          seedFreshTenantAssociation(tx, target);
          seedStartingRuntimeAndLaunch(tx, target);
          tx.execute(
              "UPDATE game_instances SET status = 'RUNNING' WHERE tenant_id = ? AND id = ?",
              target.gameSessionTenantId(),
              target.gameInstanceId());
        });
    return target;
  }

  private static void seedSourceAndCatalog(DSLContext tx, RuntimeTarget target) {
    tx.execute(
        "INSERT INTO game_session_authored_world_tenant_source_binding"
            + " (target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id,"
            + " source_game_tenant_key, provenance_kind) VALUES (?, ?, ?, ?, ?, 'NEW_GAME_ROW')",
        target.targetNamespace(),
        target.canonicalTenantId(),
        target.tenantSlug(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey());

    tx.execute(
        "INSERT INTO game_session_authored_world_source_intake"
            + " (operation_id, schema_version, target_namespace, intake_request_id, request_digest,"
            + " source_schema_version, source_registration_request_id, source_operation_id,"
            + " source_request_digest, canonical_tenant_id, tenant_slug, world_slug,"
            + " world_display_name, source_game_row_id, source_game_tenant_key,"
            + " source_provenance_kind, source_evidence_digest, receipt_digest)"
            + " VALUES (?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', ?, ?)",
        target.sourceIntakeOperationId(),
        target.targetNamespace(),
        target.sourceIntakeRequestId(),
        SHA256,
        target.sourceRegistrationRequestId(),
        target.sourceOperationId(),
        SHA256,
        target.canonicalTenantId(),
        target.tenantSlug(),
        target.worldSlug(),
        target.worldDisplayName(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        SHA256,
        SHA256);

    tx.execute(
        "INSERT INTO gameplay_tenant_shared_playable_state_namespace"
            + " (tenant_id, playable_state_namespace_id, canonical_tenant_id, target_namespace,"
            + " source_intake_operation_id, source_intake_schema_version, source_intake_request_id,"
            + " source_intake_request_digest, source_intake_receipt_digest, source_schema_version,"
            + " source_registration_request_id, source_operation_id, source_request_digest,"
            + " source_game_row_id, source_game_tenant_key, source_provenance_kind,"
            + " source_evidence_digest)"
            + " VALUES (NULL, ?, ?, ?, ?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', ?)",
        target.playableStateNamespaceId(),
        target.canonicalTenantId(),
        target.targetNamespace(),
        target.sourceIntakeOperationId(),
        target.sourceIntakeRequestId(),
        SHA256,
        SHA256,
        target.sourceRegistrationRequestId(),
        target.sourceOperationId(),
        SHA256,
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        SHA256);

    tx.execute(
        "INSERT INTO game_session_canonical_realm_catalog"
            + " (target_namespace, canonical_tenant_id, tenant_slug, world_slug, realm_id,"
            + " realm_slug, realm_display_name, visible, public_production, state_scope,"
            + " playable_state_namespace_id, character_creation_policy, catalog_revision,"
            + " creation_request_id, request_digest, receipt_digest, source_intake_operation_id,"
            + " source_intake_schema_version, source_intake_request_id, source_intake_request_digest,"
            + " source_intake_receipt_digest, source_schema_version, source_registration_request_id,"
            + " source_operation_id, source_request_digest, source_world_display_name,"
            + " source_game_row_id, source_game_tenant_key, source_provenance_kind, source_evidence_digest)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, 'SHARED', ?, 'PLAYER_CHOICE', 1, ?, ?, ?, ?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', ?)",
        target.targetNamespace(),
        target.canonicalTenantId(),
        target.tenantSlug(),
        target.worldSlug(),
        target.realmId(),
        target.realmSlug(),
        target.realmDisplayName(),
        target.playableStateNamespaceId(),
        target.catalogCreationRequestId(),
        SHA256,
        SHA256,
        target.sourceIntakeOperationId(),
        target.sourceIntakeRequestId(),
        SHA256,
        SHA256,
        target.sourceRegistrationRequestId(),
        target.sourceOperationId(),
        SHA256,
        target.worldDisplayName(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        SHA256);
  }

  private static void seedFreshTenantAssociation(DSLContext tx, RuntimeTarget target) {
    tx.execute(
        "INSERT INTO game_session_tenant_scope_reservation"
            + " (game_session_tenant_id, reservation_kind) VALUES (?, 'FRESH_SOURCE_BOUND')",
        target.gameSessionTenantId());
    tx.execute(
        "INSERT INTO game_session_tenant_canonical_claim"
            + " (target_namespace, canonical_tenant_id, legacy_game_session_tenant_id,"
            + " association_kind, reservation_kind, association_operation_id, association_request_id)"
            + " VALUES (?, ?, ?, 'FRESH_SOURCE_BOUND', 'FRESH_SOURCE_BOUND', ?, ?)",
        target.targetNamespace(),
        target.canonicalTenantId(),
        target.gameSessionTenantId(),
        target.tenantAssociationOperationId(),
        target.tenantAssociationRequestId());
    tx.execute(
        "INSERT INTO game_session_fresh_tenant_association"
            + " (association_operation_id, target_namespace, association_request_id,"
            + " source_schema_version, source_target_namespace, source_request_id,"
            + " source_canonical_tenant_id, canonical_tenant_id, legacy_game_session_tenant_id,"
            + " source_game_row_id, source_game_tenant_key, provenance_kind, association_kind)"
            + " VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', 'FRESH_SOURCE_BOUND')",
        target.tenantAssociationOperationId(),
        target.targetNamespace(),
        target.tenantAssociationRequestId(),
        target.targetNamespace(),
        target.tenantAssociationRequestId(),
        target.canonicalTenantId(),
        target.canonicalTenantId(),
        target.gameSessionTenantId(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey());
  }

  private static void seedStartingRuntimeAndLaunch(DSLContext tx, RuntimeTarget target) {
    tx.execute(
        "INSERT INTO game_instances"
            + " (id, tenant_id, runtime_version, owner_account_id, status, row_version,"
            + " game_instance_uuid, game_template_id, launch_descriptor_id, version_id,"
            + " release_bundle_id, generation_config_revision, version_state_epoch,"
            + " run_owned_start_request_id, run_owned_start_request_digest,"
            + " run_owned_start_published_release_bundle_ref, run_owned_start_preparing_epoch,"
            + " run_owned_start_active_epoch)"
            + " VALUES (?, ?, 'fixture-runtime', 99, 'STARTING', 1, ?, 1, ?, 1, 1, ?, 1, ?, ?, ?, 1, 2)",
        target.gameInstanceId(),
        target.gameSessionTenantId(),
        target.gameInstanceUuid(),
        target.launchDescriptorId(),
        target.generationConfigRevision(),
        target.controlPlaneRequestId(),
        RAW_SHA256,
        target.publishedReleaseBundleRef());

    tx.execute(
        "INSERT INTO game_session_canonical_instance_launch"
            + " (target_namespace, control_plane_request_id, game_session_tenant_id,"
            + " canonical_tenant_id, tenant_association_operation_id, tenant_association_request_id,"
            + " tenant_source_schema_version, tenant_source_target_namespace, tenant_source_request_id,"
            + " tenant_source_canonical_tenant_id, tenant_source_game_row_id,"
            + " tenant_source_game_tenant_key, tenant_source_provenance_kind, tenant_association_kind,"
            + " game_instance_id, game_instance_uuid, canonical_realm_id, world_slug,"
            + " playable_state_namespace_id, playable_state_scope, public_production,"
            + " captured_starting_row_version, game_template_id, launch_descriptor_id, version_id,"
            + " release_bundle_id, generation_config_revision, version_state_epoch,"
            + " complete_launch_binding_evidence)"
            + " VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', 'FRESH_SOURCE_BOUND',"
            + " ?, ?, ?, ?, ?, 'SHARED', TRUE, 1, 1, ?, 1, 1, ?, 1,"
            + " jsonb_build_object('descriptor', jsonb_build_object('targetNamespace', ?,"
            + " 'controlPlaneRequestId', ?, 'canonicalTenantId', ?, 'worldSlug', ?,"
            + " 'gameTemplateId', '1', 'launchDescriptorId', ?, 'versionId', '1',"
            + " 'releaseBundleId', '1', 'generationConfigRevision', ?, 'versionStateEpoch', '1',"
            + " 'publishedReleaseBundleRef', ?), 'releaseAttestation',"
            + " jsonb_build_object('canonicalVersionId', ?)))",
        target.targetNamespace(),
        target.controlPlaneRequestId(),
        target.gameSessionTenantId(),
        target.canonicalTenantId(),
        target.tenantAssociationOperationId(),
        target.tenantAssociationRequestId(),
        target.targetNamespace(),
        target.tenantAssociationRequestId(),
        target.canonicalTenantId(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        target.gameInstanceId(),
        target.gameInstanceUuid(),
        target.realmId(),
        target.worldSlug(),
        target.playableStateNamespaceId(),
        target.launchDescriptorId(),
        target.generationConfigRevision(),
        target.targetNamespace(),
        target.controlPlaneRequestId(),
        target.canonicalTenantId().toString(),
        target.worldSlug(),
        target.launchDescriptorId(),
        target.generationConfigRevision(),
        target.publishedReleaseBundleRef(),
        target.canonicalVersionId().toString());
  }

  public record RuntimeTarget(
      String targetNamespace,
      String tenantSlug,
      UUID canonicalTenantId,
      String worldSlug,
      String worldDisplayName,
      UUID realmId,
      String realmSlug,
      String realmDisplayName,
      long gameSessionTenantId,
      long gameInstanceId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID gameInstanceUuid,
      UUID canonicalVersionId,
      long runtimeVersionId,
      long catalogRevision,
      long pointerVersion,
      String pointerSnapshotDigest,
      long activeWorldEpoch,
      String initialAdmissionRequestId,
      String initialAdmissionRequestDigest,
      UUID holdId,
      UUID holdFence,
      String holdBindingDigest,
      long auditEventId,
      String ownerProofDigest,
      Instant ownerProofTerminalAt,
      String characterCreationPolicy,
      long sourceGameRowId,
      String sourceGameTenantKey,
      UUID sourceIntakeOperationId,
      UUID sourceIntakeRequestId,
      UUID sourceRegistrationRequestId,
      UUID sourceOperationId,
      UUID tenantAssociationOperationId,
      UUID tenantAssociationRequestId,
      UUID catalogCreationRequestId,
      String launchDescriptorId,
      String generationConfigRevision,
      String controlPlaneRequestId,
      String publishedReleaseBundleRef) {
    public CanonicalPlayableTarget playableTarget() {
      return new CanonicalPlayableTarget(
          targetNamespace,
          tenantSlug,
          canonicalTenantId,
          worldSlug,
          worldDisplayName,
          realmId,
          realmSlug,
          realmDisplayName,
          gameSessionTenantId,
          gameInstanceId,
          playableStateNamespaceId,
          playableStateScope,
          gameInstanceUuid,
          canonicalVersionId,
          runtimeVersionId,
          catalogRevision,
          pointerVersion,
          pointerSnapshotDigest,
          activeWorldEpoch,
          initialAdmissionRequestId,
          initialAdmissionRequestDigest,
          OriginKind.NO_PRIOR_POINTER,
          null,
          holdId,
          holdFence,
          holdBindingDigest,
          auditEventId,
          ownerProofDigest,
          Outcome.COMMITTED,
          false,
          ownerProofTerminalAt,
          characterCreationPolicy);
    }

    public CanonicalPlayableTarget admissionTarget() {
      return playableTarget();
    }

    private static RuntimeTarget create(
        UUID canonicalTenantId,
        long gameSessionTenantId,
        long gameInstanceId,
        UUID gameInstanceUuid,
        UUID playableStateNamespaceId) {
      String token = UUID.randomUUID().toString().replace("-", "");
      String shortToken = token.substring(0, 10);
      Instant terminalAt = Instant.now();
      return new RuntimeTarget(
          "fixture-" + shortToken,
          "tenant-" + shortToken,
          canonicalTenantId,
          "world-" + shortToken,
          "Fixture World " + shortToken,
          UUID.randomUUID(),
          "main",
          "Main Realm",
          gameSessionTenantId,
          gameInstanceId,
          playableStateNamespaceId,
          "SHARED",
          gameInstanceUuid,
          UUID.randomUUID(),
          1L,
          1L,
          1L,
          RAW_SHA256,
          2L,
          "initial-" + shortToken,
          RAW_SHA256,
          UUID.randomUUID(),
          UUID.randomUUID(),
          SHA256,
          1L,
          SHA256,
          terminalAt,
          "PLAYER_CHOICE",
          gameInstanceId + 50_000L,
          "source-" + shortToken,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "descriptor-" + shortToken,
          "generation-" + shortToken,
          "launch-" + token,
          "published-release-" + shortToken);
    }
  }
}
