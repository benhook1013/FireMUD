package net.firedevops.firemud.gamesession.repository;

import integration.net.firedevops.firemud.gamesession.repository.CanonicalHumanPublicationOwnerTestFixtures;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository.StartingInstance;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared PostgreSQL fixtures for lease-bound binding tests that require a current launch target.
 * The source, policy, catalog, pin, and Starting records are written by their actual GS owners;
 * upstream producer inputs remain controlled test evidence and do not establish Account admission.
 * Candidate, transition, and provisional lease helpers write controlled SQL inputs for downstream
 * guards; they do not invoke gameplay binding or Account lease owners.
 */
public final class CanonicalGameplayBindingRuntimeTestFixtures {
  private static final String SHA256 = "sha256:" + "a".repeat(64);
  private static final String RAW_SHA256 = "a".repeat(64);
  private static final long GAME_TEMPLATE_ID = 1L;
  private static final long VERSION_ID = 1L;
  private static final long RELEASE_BUNDLE_ID = 1L;
  private static final long VERSION_STATE_EPOCH = 2L;
  private static final String RUNTIME_FLAGS_JSON = "{}";

  private CanonicalGameplayBindingRuntimeTestFixtures() {}

  public static RuntimeTarget seedRunningLaunch(DataSource dataSource) {
    return seedRunningLaunch(dataSource, UUID.randomUUID());
  }

  /**
   * Creates the source-bound catalog and runtime using current Game Session owners. The source,
   * publication, original tuple, and Account projection are explicitly controlled upstream test
   * inputs; this path proves neither a live producer nor initial-admission terminal authority.
   */
  public static RuntimeTarget seedRunningLaunch(DataSource dataSource, UUID canonicalTenantId) {
    return seedRunningLaunch(dataSource, canonicalTenantId, null);
  }

  /**
   * Fixed test namespace for authenticated cross-owner composition; upstream evidence is
   * controlled.
   */
  public static RuntimeTarget seedRunningLaunch(
      DataSource dataSource, UUID canonicalTenantId, String fixedNamespace) {
    if (canonicalTenantId == null || new UUID(0L, 0L).equals(canonicalTenantId)) {
      throw new IllegalArgumentException("Canonical tenant UUID must be non-nil");
    }

    String token = UUID.randomUUID().toString().replace("-", "");
    String shortToken = token.substring(0, 10);
    String targetNamespace = fixedNamespace == null ? "fixture-" + shortToken : fixedNamespace;
    String tenantSlug = "tenant-" + shortToken;
    String worldSlug = "world-" + shortToken;
    String worldDisplayName = "Fixture World " + shortToken;
    String sourceGameTenantKey = "gds-source-" + shortToken;
    long sourceGameRowId =
        Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), Long.MAX_VALUE - 1L) + 1L;
    UUID registrationRequestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            targetNamespace,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            targetNamespace,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence sourceEvidence =
        new AuthoredWorldSourceEvidence(
            1,
            targetNamespace,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW",
            sourceEvidenceDigest);

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    String controlPlaneRequestId = "launch-" + token;
    UUID ownerAccountUuid = UUID.randomUUID();

    GameSessionAuthoredWorldSourceRepository sourceRepository =
        new GameSessionAuthoredWorldSourceRepository(dsl);
    IntakeReceipt sourceReceipt =
        java.util.Objects.requireNonNull(
            transactions.execute(
                status -> sourceRepository.register(UUID.randomUUID(), sourceEvidence)),
            "registered authored-world source receipt");

    FreshTenantCreationEvidence creationEvidence =
        CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(sourceEvidence);
    FreshGameSessionTenantAssociation tenantAssociation =
        java.util.Objects.requireNonNull(
            transactions.execute(
                status ->
                    new GameSessionFreshTenantAssociationRepository(dsl)
                        .associateFresh(
                            UUID.randomUUID(), targetNamespace, sourceReceipt, creationEvidence)),
            "fresh source-bound Game Session tenant association");

    UUID catalogRequestId = UUID.randomUUID();
    UUID canonicalVersionId = UUID.randomUUID();
    String publishRequestId = "publication-" + token;
    String bundleRef = "published-release-" + shortToken;
    String generationConfigRevision = "generation-" + shortToken;
    CreateCanonicalRealmCatalogRequest catalogRequest =
        new CreateCanonicalRealmCatalogRequest(
            catalogRequestId,
            targetNamespace,
            canonicalTenantId,
            sourceReceipt.operationId(),
            "main-realm",
            "Main Realm",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    var publishedSet =
        CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            catalogRequest,
            sourceEvidence,
            canonicalVersionId,
            VERSION_ID,
            publishRequestId,
            bundleRef,
            generationConfigRevision,
            VERSION_STATE_EPOCH);
    GameSessionCanonicalRealmCatalogRepository catalogRepository =
        new GameSessionCanonicalRealmCatalogRepository(dsl);
    var preparedCatalog =
        catalogRepository.prepareInitialPublicProduction(catalogRequest, publishedSet);
    CanonicalRealmCatalogSnapshot catalog =
        java.util.Objects.requireNonNull(
            transactions.execute(
                status -> catalogRepository.createInitialPublicProduction(preparedCatalog)),
            "created public-production canonical catalog");

    AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            targetNamespace,
            controlPlaneRequestId,
            canonicalTenantId,
            worldSlug,
            sourceEvidence.operationId(),
            sourceEvidence.evidenceDigest(),
            GAME_TEMPLATE_ID,
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            RUNTIME_FLAGS_JSON);
    String launchDescriptorId = "descriptor-" + shortToken;
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            launchDescriptorId,
            VERSION_ID,
            false,
            null,
            RUNTIME_FLAGS_JSON,
            generationConfigRevision,
            VERSION_STATE_EPOCH,
            RELEASE_BUNDLE_ID,
            bundleRef,
            false,
            null);
    CompleteLaunchBindingEvidence binding =
        CanonicalHumanPublicationOwnerTestFixtures.completeBinding(descriptor, publishedSet);
    var originalTuple =
        CanonicalHumanPublicationOwnerTestFixtures.tuple(
            targetNamespace,
            canonicalTenantId,
            ownerAccountUuid,
            GAME_TEMPLATE_ID,
            controlPlaneRequestId);
    CanonicalHumanPublicationOwnerTestFixtures.pinLaunch(
        dsl, transactions, originalTuple, sourceEvidence, publishedSet, binding);

    GameSessionCanonicalStartingInstanceRepository startingRepository =
        new GameSessionCanonicalStartingInstanceRepository(dsl, targetNamespace);
    StartingInstance startingInstance =
        java.util.Objects.requireNonNull(
            transactions.execute(
                status ->
                    startingRepository.createStarting(
                        tenantAssociation,
                        binding,
                        ownerAccountUuid,
                        controlPlaneRequestId,
                        originalTuple.mutationDigest(),
                        originalTuple)),
            "created canonical STARTING instance");
    int consumerInputRows =
        java.util.Objects.requireNonNull(
            transactions.execute(
                status ->
                    dsl.execute(
                        // Controlled upstream World epoch inputs support only this downstream
                        // guard-consumer proof; they are not World ACTIVE, Human terminal, Account
                        // protection, or admission evidence.
                        "UPDATE game_instances SET status = 'RUNNING',"
                            + " row_version = row_version + 1, run_owned_start_preparing_epoch = 1,"
                            + " run_owned_start_active_epoch = 2 WHERE tenant_id = ? AND id = ?",
                        tenantAssociation.legacyGameSessionTenantId(),
                        startingInstance.gameInstanceId())),
            "controlled runtime consumer input row count");
    if (consumerInputRows != 1) {
      throw new IllegalStateException(
          "Created canonical STARTING test row did not receive controlled consumer inputs");
    }

    return new RuntimeTarget(
        targetNamespace,
        tenantSlug,
        canonicalTenantId,
        worldSlug,
        worldDisplayName,
        catalog.realmId(),
        catalog.realmSlug(),
        catalog.realmDisplayName(),
        tenantAssociation.legacyGameSessionTenantId(),
        startingInstance.gameInstanceId(),
        catalog.playableStateNamespaceId(),
        catalog.stateScope(),
        startingInstance.gameInstanceUuid(),
        canonicalVersionId,
        VERSION_ID,
        catalog.catalogRevision(),
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
        Instant.now(),
        catalog.characterCreationPolicy(),
        sourceEvidence.sourceGameRowId(),
        sourceEvidence.sourceGameTenantKey(),
        sourceReceipt.operationId(),
        sourceReceipt.intakeRequestId(),
        sourceEvidence.registrationRequestId(),
        sourceEvidence.operationId(),
        tenantAssociation.associationOperationId(),
        creationEvidence.creationRequestId(),
        catalog.creationRequestId(),
        launchDescriptorId,
        generationConfigRevision,
        controlPlaneRequestId,
        bundleRef);
  }

  /**
   * Writes controlled candidate and PREPARED transition inputs for downstream runtime-fence tests.
   * These SQL inputs are not gameplay admission or Account lease-owner evidence.
   */
  public static byte[] seedCandidateAndPreparedTransition(
      DSLContext dsl, CanonicalPlayableTarget target) {
    return seedCandidateAndPreparedTransition(
        dsl,
        target,
        target.canonicalTenantId(),
        target.gameInstanceId(),
        target.canonicalGameInstanceId());
  }

  /** Allows the runtime-fence test to supply explicit candidate identity mismatches. */
  static byte[] seedCandidateAndPreparedTransition(
      DSLContext dsl,
      CanonicalPlayableTarget target,
      UUID candidateTenant,
      long candidateRuntimeId,
      UUID candidateGameInstanceUuid) {
    UUID transitionId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID characterId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    String issuerId = GameSessionAccountDelegationProfile.ISSUER;
    UUID accountIndexFence = UUID.randomUUID();
    UUID issuerReservationId = UUID.randomUUID();
    byte[] candidateRef = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);

    dsl.execute(
        "INSERT INTO game_session_canonical_gameplay_binding_inventory"
            + " (binding_ref, account_id, tenant_id, playable_state_namespace_id, character_id,"
            + " playable_state_scope, game_instance_id, runtime_game_instance_id, session_id,"
            + " binding_generation, region_id, region_epoch, issuer_id, issuer_auth_generation,"
            + " issuer_index_layout_version, issuer_index_partition_count,"
            + " issuer_index_partition_capacity, issuer_partition_id, account_index_fence,"
            + " issuer_reservation_id, transition_id, lifecycle, account_index_state,"
            + " issuer_index_state, inventory_revision)"
            + " VALUES (?, ?, ?, ?, ?, 'SHARED', ?, ?, ?, 1, ?, 12, ?, 1, 1, 1, 1, 0, ?, ?, ?,"
            + " 'CANDIDATE_PREPARED', 'REPAIR_REQUIRED', 'REPAIR_REQUIRED', 1)",
        candidateRef,
        accountId,
        candidateTenant,
        target.playableStateNamespaceId(),
        characterId,
        candidateGameInstanceUuid,
        candidateRuntimeId,
        UUID.randomUUID().toString(),
        regionId,
        issuerId,
        accountIndexFence,
        issuerReservationId,
        transitionId);
    dsl.execute(
        "INSERT INTO game_session_canonical_binding_transition"
            + " (transition_id, tenant_id, playable_state_namespace_id, character_id,"
            + " expected_prior_binding_ref, expected_prior_binding_generation, candidate_binding_ref,"
            + " candidate_binding_generation, candidate_account_index_fence, issuer_reservation_id,"
            + " status, inventory_revision)"
            + " VALUES (?, ?, ?, ?, NULL, NULL, ?, 1, ?, ?, 'PREPARED', 1)",
        transitionId,
        target.canonicalTenantId(),
        target.playableStateNamespaceId(),
        characterId,
        candidateRef,
        accountIndexFence,
        issuerReservationId);
    return candidateRef;
  }

  /**
   * Writes controlled provisional lease inputs so database guards can evaluate the supplied target.
   * This SQL setup does not acquire an Account lease or establish actual admission authority.
   */
  public static void markProvisional(
      DSLContext dsl, CanonicalPlayableTarget target, byte[] candidateRef) {
    dsl.execute(
        "UPDATE game_session_canonical_binding_transition SET"
            + " status = 'PROVISIONAL', admission_request_id = ?, admission_lease_id = ?,"
            + " admission_lease_fence = 1, admission_lease_kind = 'NEW_BINDING',"
            + " admission_lease_digest = ?, admission_lease_evidence = '{}',"
            + " admission_lease_expires_at = 9999999999999,"
            + " admission_target_namespace = ?, admission_target_tenant_slug = ?,"
            + " admission_target_tenant_id = ?, admission_target_world_slug = ?,"
            + " admission_target_world_display_name = ?, admission_target_realm_id = ?,"
            + " admission_target_realm_slug = ?, admission_target_realm_display_name = ?,"
            + " admission_target_game_session_tenant_id = ?, admission_target_game_instance_id = ?,"
            + " admission_target_playable_state_namespace_id = ?, admission_target_playable_state_scope = ?,"
            + " admission_target_canonical_game_instance_id = ?, admission_target_canonical_version_id = ?,"
            + " admission_target_runtime_version_id = ?, admission_target_catalog_revision = ?,"
            + " admission_target_pointer_version = ?, admission_target_pointer_snapshot_digest = ?,"
            + " admission_target_active_world_epoch = ?, admission_target_initial_admission_request_id = ?,"
            + " admission_target_initial_admission_request_digest = ?,"
            + " admission_target_origin_kind = 'NO_PRIOR_POINTER',"
            + " admission_target_hold_id = ?, admission_target_hold_fence = ?,"
            + " admission_target_hold_binding_digest = ?, admission_target_audit_event_id = ?,"
            + " admission_target_owner_proof_digest = ?, admission_target_owner_proof_outcome = 'COMMITTED',"
            + " admission_target_positive_durable_abort = FALSE, admission_target_terminal_at = ?,"
            + " admission_target_character_creation_policy = ?"
            + " WHERE candidate_binding_ref = ?",
        UUID.randomUUID(),
        UUID.randomUUID(),
        RAW_SHA256,
        target.targetNamespace(),
        target.tenantSlug(),
        target.canonicalTenantId(),
        target.worldSlug(),
        target.worldDisplayName(),
        target.realmId(),
        target.realmSlug(),
        target.realmDisplayName(),
        target.gameSessionTenantId(),
        target.gameInstanceId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.canonicalGameInstanceId(),
        target.canonicalVersionId(),
        target.runtimeVersionId(),
        target.catalogRevision(),
        target.pointerVersion(),
        target.admissionPointerSnapshotDigest(),
        target.activeWorldEpoch(),
        target.initialAdmissionRequestId(),
        target.initialAdmissionRequestDigest(),
        target.holdId(),
        target.holdFence(),
        target.holdBindingDigest(),
        target.auditEventId(),
        target.ownerProofDigest(),
        Timestamp.from(target.ownerProofTerminalAt()),
        target.characterCreationPolicy(),
        candidateRef);
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
    /**
     * The downstream binding tests use this carrier for selector equality only. Its admission
     * fields are placeholders required by the current value type and do not come from an admission
     * owner or establish a terminal Human proof.
     */
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
          net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind
              .NO_PRIOR_POINTER,
          null,
          holdId,
          holdFence,
          holdBindingDigest,
          auditEventId,
          ownerProofDigest,
          net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome
              .COMMITTED,
          false,
          ownerProofTerminalAt,
          characterCreationPolicy);
    }

    public CanonicalPlayableTarget admissionTarget() {
      return playableTarget();
    }
  }
}
