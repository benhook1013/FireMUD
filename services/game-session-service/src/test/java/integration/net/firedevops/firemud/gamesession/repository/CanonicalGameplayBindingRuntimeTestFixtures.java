package net.firedevops.firemud.gamesession.repository;

import integration.net.firedevops.firemud.gamesession.repository.CanonicalHumanPublicationOwnerTestFixtures;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
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
    if (canonicalTenantId == null || new UUID(0L, 0L).equals(canonicalTenantId)) {
      throw new IllegalArgumentException("Canonical tenant UUID must be non-nil");
    }

    String token = UUID.randomUUID().toString().replace("-", "");
    String shortToken = token.substring(0, 10);
    String targetNamespace = "fixture-" + shortToken;
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
