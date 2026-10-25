package net.firedevops.firemud.gamesession.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadClient;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.testing.ControlledPlayerCreatedRosterSourceFixture.WireFixture;
import net.firedevops.firemud.gamesession.charactercreate.GameSessionCharacterCreatePublicSourceService;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controlled historical GS source for coupled owner tests, never production admission authority.
 *
 * <p>The caller owns an isolated PostgreSQL container and disposable schema, including cleanup.
 * Catalog/launch/first-OPEN history is constructed in the real pre1006 layout with all installed
 * guards, then upgraded through the actual migrations. Original attempt, Account-projection
 * attachment, association/descriptor pins and authored-core retention use actual owner writers. The
 * current route, complete public source, pointer verification/locks and roster original owner are
 * real. Account authority and publication/World responses remain explicitly controlled.
 */
public final class ControlledPlayerCreatedOriginalOwnerFixture {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final DriverManagerDataSource dataSource;
  private final String schema;
  private final Path migrations;
  private final CanonicalPlayerRouteReadService routeReader;
  private final CanonicalPublishedPlayerRouteReadService publishedRouteReader;
  private final DSLContext dsl;
  private final TransactionTemplate transactions;
  private final GameSessionCanonicalRealmCatalogRepository catalogs;
  private final CanonicalInitialAdmissionRepository admissions;
  private final CanonicalGameInstanceLaunchAssociationRepository launches;
  private final GameSessionCharacterCreatePublicSourceService sourceReader;
  private final CanonicalGameplayRosterReferenceRepository referenceRepository;
  private final CanonicalGameplayRosterReferenceOwner referenceOwner;
  private final CanonicalPlayableTarget route;
  private final WireFixture wireFixture;
  private final AtomicInteger controlledWorldLifecycleReads = new AtomicInteger();

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The caller and fixture intentionally share the isolated DataSource and schema lifecycle.")
  public ControlledPlayerCreatedOriginalOwnerFixture(
      DriverManagerDataSource dataSource,
      WireFixture supplied,
      CanonicalGameplayRosterRecoveryClient recovery,
      Clock clock,
      CanonicalGameplayRosterReferencePolicy policy)
      throws Exception {
    Objects.requireNonNull(supplied, "complete controlled publication fixture");
    this.dataSource = Objects.requireNonNull(dataSource);
    Objects.requireNonNull(clock);
    migrations = resolveMigrationDirectory();
    schema = Objects.requireNonNull(dataSource.getSchema(), "caller-owned isolated schema");
    migrate("1005");
    var manager = new DataSourceTransactionManager(dataSource);
    transactions = new TransactionTemplate(manager);
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
    var events = new GameplayAdmissionPointerEventRepository(dsl);
    var pointers = new GameSessionCanonicalAdmissionPointerRepository(dsl, catalogs, events);
    admissions = new CanonicalInitialAdmissionRepository(dsl, catalogs, pointers);
    String namespace = supplied.request().getTargetNamespace();
    launches = new CanonicalGameInstanceLaunchAssociationRepository(dsl, namespace);
    var upstream = supplied.authored();
    var original =
        StartSessionPostAuthorizationExecutionTuple.decode(
            upstream.request().canonicalPostAuthorizationTuple());
    var tuple = freshControlledTuple(original, clock);
    var attempts = new GameSessionStartSessionOperatorAttemptRepository(dsl, Duration.ofMinutes(5));
    var claim =
        Objects.requireNonNull(transactions.execute(ignored -> attempts.reserve(tuple)))
            .claim()
            .orElseThrow();
    var authority =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    var redemption =
        new GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection(
            tuple.authorizationReferenceFingerprint(),
            tuple.authorityEvidenceBundleBytes(),
            authority.issuanceOperationId(),
            Long.parseLong(tuple.issuanceFence()),
            tuple.canonicalBytes());
    transactions.executeWithoutResult(
        ignored -> attempts.attachAccountRedemptionProjection(claim, redemption));
    var association = upstream.associationRead().association();
    var initialRequest =
        new StartSessionTemplateAssociationReadEvidence.Request(
            1,
            namespace,
            upstream.request().readRequestId(),
            tuple.canonicalBytes(),
            claim.ownerAttemptId(),
            claim.ownerFence(),
            new StartSessionTemplateAssociationReadEvidence.InitialConfigured());
    var initial =
        new StartSessionTemplateAssociationReadEvidence.Result(
            initialRequest,
            association,
            upstream.associationRead().releaseBundle(),
            upstream.associationRead().worldPublishedStartLocationEvidence(),
            upstream.associationRead().phaseEpoch());
    var selections = new GameSessionStartSessionTemplateAssociationRepository(dsl, attempts);
    transactions.execute(ignored -> selections.pinInitialOrValidateExactReplay(claim, initial));
    var exactRequest =
        new StartSessionTemplateAssociationReadEvidence.Request(
            1,
            namespace,
            initialRequest.readRequestId(),
            tuple.canonicalBytes(),
            claim.ownerAttemptId(),
            claim.ownerFence(),
            new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                association.canonicalVersionId(), association.selectedCommitId(),
                association.publishWorkflowId(), association.associationDigest()));
    var exact =
        new StartSessionTemplateAssociationReadEvidence.Result(
            exactRequest,
            association,
            initial.releaseBundle(),
            initial.worldPublishedStartLocationEvidence(),
            initial.phaseEpoch());
    var authored =
        new AuthoredInitialActorCoreReadEvidence.ReadResult(
            exactRequest, exact, upstream.descriptor(), upstream.projections());
    var descriptors = new GameSessionStartSessionLaunchDescriptorRepository(dsl, selections);
    transactions.execute(
        ignored ->
            descriptors.pin(
                claim,
                new StartSessionLaunchDescriptorGrpcCodec.Resolved(
                    exact,
                    new StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome(
                        authored.descriptor()))));
    var source = association.worldReceipt().source();
    var intakeRepository = new GameSessionAuthoredWorldSourceRepository(dsl);
    var intake =
        Objects.requireNonNull(
            transactions.execute(ignored -> intakeRepository.register(UUID.randomUUID(), source)));
    var tenantAssociation =
        Objects.requireNonNull(
            transactions.execute(
                ignored ->
                    new GameSessionFreshTenantAssociationRepository(dsl)
                        .associateFresh(
                            UUID.randomUUID(), namespace, intake, creationEvidence(source))));
    var policySet = supplied.policySet();
    var realmPolicy =
        policySet.policies().stream()
            .filter(item -> item.policy().realmSlug().equals(supplied.request().getRealmSlug()))
            .findFirst()
            .orElseThrow()
            .policy();
    var catalogRequest =
        new CreateCanonicalRealmCatalogRequest(
            UUID.randomUUID(),
            namespace,
            source.canonicalTenantId(),
            intake.operationId(),
            realmPolicy.realmSlug(),
            realmPolicy.realmDisplayName(),
            true,
            true,
            "SHARED",
            "PLAYER_CREATED",
            null,
            null);
    var published = internalPolicies(policySet);
    // Real owner preparation validates the complete PLAYER_CREATED source from the outset.
    catalogs.prepareInitialPublicProduction(catalogRequest, published);
    var catalog =
        seedPriorCatalog(
            catalogRequest,
            intake,
            UUID.fromString(supplied.request().getRealmId()),
            supplied.world().request().playableStateNamespaceId(),
            published);
    var binding = supplied.world().launchBinding();
    UUID instance = supplied.world().request().canonicalGameInstanceId();
    long runtime =
        seedPriorLaunch(
            catalog,
            tenantAssociation.legacyGameSessionTenantId(),
            tenantAssociation.associationOperationId(),
            tenantAssociation.sourceEvidence(),
            tuple,
            binding,
            instance);
    var captured =
        new CanonicalGameInstanceLaunchAssociation(
            namespace,
            tenantAssociation.legacyGameSessionTenantId(),
            tenantAssociation.associationOperationId(),
            source.canonicalTenantId(),
            instance,
            source.worldSlug(),
            catalog.playableStateNamespaceId(),
            realmPolicy.stateScope(),
            true,
            tuple.controlPlaneRequestId(),
            authored.descriptor().launchDescriptorId(),
            0L,
            binding,
            CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING,
            0L);
    var launch =
        new CanonicalInitialAdmissionLaunchTarget(
            captured, catalog.realmId(), runtime, authored.descriptor().versionId());
    seedPriorCommittedAdmission(catalog, launch, supplied.world().lifecycleEpoch(), events);
    // Upgrade actual prior history before current source-retention and reference owner work.
    migrate("1013");
    var retention = new GameSessionAuthoredActorCoreRetentionRepository(dsl, manager, namespace);
    var currentAttemptRequest =
        new net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence
            .Request(
            UUID.randomUUID(),
            namespace,
            tuple.canonicalBytes(),
            claim.ownerAttemptId(),
            claim.ownerMutationId(),
            claim.ownerFence());
    var attempt =
        transactions.execute(ignored -> attempts.readCurrentOriginalAttempt(currentAttemptRequest));
    var retained =
        new GameSessionAuthoredActorCoreRetentionRepository.Candidate(
            namespace,
            tuple.controlPlaneRequestId(),
            source.canonicalTenantId(),
            instance,
            claim.ownerAttemptId(),
            claim.ownerMutationId(),
            claim.ownerFence(),
            Objects.requireNonNull(attempt).leaseExpiresAt(),
            tuple.canonicalBytes(),
            redemption.originalAccountPostAuthorizationTuple(),
            hash(redemption.originalAccountPostAuthorizationTuple(), false),
            association.canonicalVersionId(),
            association.selectedCommitId(),
            association.publishWorkflowId(),
            association.associationDigest(),
            association.sourceOperationId(),
            association.sourceEvidenceDigest(),
            source.worldSlug(),
            authored.descriptor().launchDescriptorId(),
            authored.descriptor().requestDigest(),
            authored.descriptor().resultDigest(),
            binding.releaseAttestation().evidenceDigest(),
            StartSessionTemplateAssociationReadGrpcCodec.toRequest(exactRequest).toByteArray(),
            authored.canonicalBytes(),
            authored.digest());
    transactions.execute(
        ignored -> {
          attempts.readCurrentOriginalAttempt(currentAttemptRequest);
          return retention.retainInOwnerTransaction(retained);
        });
    migrateLatest();
    routeReader = new CanonicalPlayerRouteReadService(namespace, catalogs, admissions, launches);
    var gameDesign = mock(AuthoredInitialActorCoreReadClient.class);
    when(gameDesign.read(any()))
        .thenAnswer(
            call -> {
              requireOutsideSql();
              if (!new AuthoredInitialActorCoreReadEvidence.ReadRequest(authored.request())
                  .equals(call.getArgument(0)))
                throw new IllegalArgumentException("Controlled authored replay selector changed");
              return authored;
            });
    var world = mock(WorldCanonicalInstanceLifecycleClient.class);
    when(world.read(any()))
        .thenAnswer(
            call -> {
              requireOutsideSql();
              controlledWorldLifecycleReads.incrementAndGet();
              var requested = (WorldCanonicalInstanceLifecycleEvidence.Request) call.getArgument(0);
              var base = supplied.world();
              var expected = base.request();
              if (!requested.equals(
                  new WorldCanonicalInstanceLifecycleEvidence.Request(
                      expected.schemaVersion(),
                      requested.readRequestId(),
                      expected.targetNamespace(),
                      expected.canonicalTenantId(),
                      expected.worldSlug(),
                      expected.canonicalGameInstanceId(),
                      expected.playableStateNamespaceId(),
                      expected.playableStateScope(),
                      expected.publicProduction(),
                      expected.controlPlaneRequestId(),
                      expected.canonicalVersionId(),
                      expected.expectedDescriptorRequestDigest(),
                      expected.expectedDescriptorResultDigest(),
                      expected.expectedReleaseAttestationDigest())))
                throw new IllegalArgumentException("Controlled World selector changed");
              return new WorldCanonicalInstanceLifecycleEvidence(
                  requested,
                  base.launchBinding(),
                  base.startLocation(),
                  base.runtimeRoomInstanceId(),
                  base.lifecycleStatus(),
                  base.lifecycleEpoch(),
                  base.rowVersion(),
                  base.captureId(),
                  base.graphSha256(),
                  base.preparationInputDigest(),
                  base.operationalRegionAssignments());
            });
    var policies = mock(GameDesignPublishedRealmPolicyClient.class);
    when(policies.listPublishedRealmEntryPolicies(any(), any()))
        .thenAnswer(
            call -> {
              requireOutsideSql();
              if (!source.canonicalTenantId().equals(call.getArgument(0))
                  || !association.canonicalVersionId().equals(call.getArgument(1)))
                throw new IllegalArgumentException("Controlled published policy selector changed");
              return policySet;
            });
    sourceReader =
        new GameSessionCharacterCreatePublicSourceService(
            namespace,
            routeReader,
            catalogs,
            admissions,
            launches,
            retention,
            gameDesign,
            world,
            policies);
    route = routeReader.readCurrentTarget(source.canonicalTenantId());
    var request =
        supplied.request().toBuilder()
            .setWorldSlug(route.worldSlug())
            .setRealmSlug(route.realmSlug())
            .setRealmId(route.realmId().toString())
            .setPlayableStateNamespaceId(route.playableStateNamespaceId().toString())
            .setCatalogRevision(route.catalogRevision())
            .setPointerVersion(route.pointerVersion())
            .setCanonicalGameInstanceId(route.canonicalGameInstanceId().toString())
            .setExpectedCanonicalVersionId(route.canonicalVersionId().toString())
            .setActiveWorldEpoch(route.activeWorldEpoch())
            .build();
    var response = sourceReader.readPlayerCreatedRosterSource(request);
    var actualWorld =
        net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.fromStored(
            response.getWorldLifecycleEvidence().toByteArray());
    wireFixture = new WireFixture(request, response, authored, actualWorld, policySet);
    referenceRepository =
        new CanonicalGameplayRosterReferenceRepository(dsl, Objects.requireNonNull(policy));
    publishedRouteReader =
        new CanonicalPublishedPlayerRouteReadService(
            namespace, routeReader, catalogs, launches, policies);
    referenceOwner =
        new CanonicalGameplayRosterReferenceOwner(
            referenceRepository,
            transactions,
            publishedRouteReader,
            routeReader,
            admissions,
            Objects.requireNonNull(recovery),
            new SimpleMeterRegistry(),
            sourceReader);
  }

  public GameSessionCharacterCreatePublicSourceService sourceReader() {
    return sourceReader;
  }

  public CanonicalGameplayRosterReferenceOwner referenceOwner() {
    return referenceOwner;
  }

  public CanonicalGameplayRosterReferenceRepository referenceRepository() {
    return referenceRepository;
  }

  public CanonicalPlayableTarget route() {
    return route;
  }

  public WireFixture wireFixture() {
    return wireFixture;
  }

  public CanonicalPlayerRouteReadService routeReader() {
    return routeReader;
  }

  public CanonicalPublishedPlayerRouteReadService publishedRouteReader() {
    return publishedRouteReader;
  }

  /** Counts complete source resolution's independently reread World lifecycle boundary. */
  public int controlledWorldLifecycleReadCount() {
    return controlledWorldLifecycleReads.get();
  }

  public int retainedOriginalReferenceCount() {
    return dsl.fetchCount(DSL.table("game_session_canonical_roster_selection"));
  }

  private void migrate(String version) {
    migrateTo(version);
  }

  private void migrateLatest() {
    migrateTo(null);
  }

  private void migrateTo(String version) {
    var migration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("filesystem:" + migrations)
            .placeholders(Map.of("serviceSchema", schema));
    if (version != null) migration.target(MigrationVersion.fromVersion(version));
    migration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    migration.load().migrate();
  }

  private static Path resolveMigrationDirectory() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
        Path migrations =
            current.resolve("services/game-session-service/src/main/resources/db/migration");
        if (Files.isDirectory(migrations)) {
          return migrations;
        }
        throw new IllegalStateException(
            "Could not resolve Game Session migration directory from repository root " + current);
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from the current directory");
  }

  private static StartSessionPostAuthorizationExecutionTuple freshControlledTuple(
      StartSessionPostAuthorizationExecutionTuple original, Clock clock) throws Exception {
    var bundle =
        StartSessionAuthorityEvidenceBundle.decode(original.authorityEvidenceBundleBytes());
    Map<String, Object> values = new LinkedHashMap<>(bundle.jsonValue());
    @SuppressWarnings("unchecked")
    var oldProjection = (Map<String, Object>) values.get("accountProjectionEvidence");
    var projection = new LinkedHashMap<>(oldProjection);
    Instant evaluated = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    projection.put("evaluatedAt", evaluated.toString());
    projection.put("expiresAt", evaluated.plus(Duration.ofMinutes(5)).toString());
    values.put("accountProjectionEvidence", projection);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        original.preAuthorizationTuple(),
        original.authenticatedWorkloadIdentity(),
        original.authorizationReferenceFingerprint(),
        original.reservationOwnerId(),
        original.reservationClaimFence(),
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(values)),
        original.bundleReference());
  }

  private static RealmPolicyPublishedEvidence.PublishedSet internalPolicies(
      PublishedRealmEntryPolicySetEvidence set) {
    return new RealmPolicyPublishedEvidence.PublishedSet(
        set.target(),
        set.versionNumber(),
        set.sourceCommitId(),
        set.sourceEpoch(),
        set.publishedReleaseBundleRef(),
        set.publishedReleaseBundleDigest(),
        set.publishWorkflowId(),
        set.manifestHash(),
        set.publicationVersionStateEpoch(),
        set.operationBytes(),
        set.captureBytes(),
        set.terminalEvidenceBytes(),
        set.policyCount(),
        set.policySetDigest(),
        set.policies().stream()
            .map(
                item ->
                    new RealmPolicyPublishedEvidence.Policy(
                        item.policyId(),
                        new RealmPolicySource.Policy(
                            item.sourceCommitId(),
                            item.sourceRevisionId(),
                            item.logicalRevisionId(),
                            item.policy()),
                        item.policyDigest()))
            .toList());
  }

  private static FreshTenantCreationEvidence creationEvidence(AuthoredWorldSourceEvidence source) {
    UUID request = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    String digest =
        GameTenantCreationDigest.requestDigest(
            source.targetNamespace(),
            request,
            source.sourceGameTenantKey(),
            source.worldDisplayName(),
            null);
    return new FreshTenantCreationEvidence(
        1,
        source.targetNamespace(),
        request,
        operation,
        digest,
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        GameTenantCreationDigest.evidenceDigest(
            source.targetNamespace(),
            request,
            operation,
            digest,
            source.canonicalTenantId(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind()));
  }

  private CanonicalRealmCatalogSnapshot seedPriorCatalog(
      CreateCanonicalRealmCatalogRequest request,
      GameSessionAuthoredWorldSourceRepository.IntakeReceipt intake,
      UUID realm,
      UUID playableNamespace,
      RealmPolicyPublishedEvidence.PublishedSet policies) {
    var source = intake.source();
    byte[] policyBytes = RealmPolicyPublishedEvidenceCodec.encode(policies);
    String base =
        decimalFrame(
            "game-session-canonical-realm-catalog-request/v1",
            "1",
            request.requestId().toString(),
            request.targetNamespace(),
            request.canonicalTenantId().toString(),
            intake.operationId().toString(),
            intake.intakeRequestId().toString(),
            intake.requestDigest(),
            intake.receiptDigest(),
            Integer.toString(source.schemaVersion()),
            source.registrationRequestId().toString(),
            source.operationId().toString(),
            source.requestDigest(),
            source.canonicalTenantId().toString(),
            source.tenantSlug(),
            source.worldSlug(),
            source.worldDisplayName(),
            Long.toString(source.sourceGameRowId()),
            source.sourceGameTenantKey(),
            source.provenanceKind(),
            source.evidenceDigest(),
            request.realmSlug(),
            request.realmDisplayName(),
            "true",
            "true",
            "SHARED",
            "PLAYER_CREATED",
            "ABSENT",
            "ABSENT");
    String requestDigest =
        decimalFrame(
            "game-session-complete-catalog-request/v1",
            base,
            HexFormat.of().formatHex(policyBytes));
    String receipt =
        decimalFrame(
            "game-session-canonical-realm-catalog-receipt/v1",
            "1",
            request.targetNamespace(),
            request.canonicalTenantId().toString(),
            request.requestId().toString(),
            requestDigest,
            realm.toString(),
            playableNamespace.toString(),
            "1",
            intake.operationId().toString(),
            intake.receiptDigest());
    var sourceColumns =
        map(
            "source_intake_operation_id",
            intake.operationId(),
            "source_intake_schema_version",
            1,
            "source_intake_request_id",
            intake.intakeRequestId(),
            "source_intake_request_digest",
            intake.requestDigest(),
            "source_intake_receipt_digest",
            intake.receiptDigest(),
            "source_schema_version",
            source.schemaVersion(),
            "source_registration_request_id",
            source.registrationRequestId(),
            "source_operation_id",
            source.operationId(),
            "source_request_digest",
            source.requestDigest(),
            "source_game_row_id",
            source.sourceGameRowId(),
            "source_game_tenant_key",
            source.sourceGameTenantKey(),
            "source_provenance_kind",
            source.provenanceKind(),
            "source_evidence_digest",
            source.evidenceDigest());
    transactions.executeWithoutResult(
        ignored -> {
          var shared = new LinkedHashMap<>(sourceColumns);
          shared.putAll(
              map(
                  "tenant_id",
                  null,
                  "playable_state_namespace_id",
                  playableNamespace,
                  "canonical_tenant_id",
                  request.canonicalTenantId(),
                  "target_namespace",
                  request.targetNamespace()));
          insert("gameplay_tenant_shared_playable_state_namespace", shared);
          var catalog = new LinkedHashMap<>(sourceColumns);
          catalog.putAll(
              map(
                  "target_namespace",
                  request.targetNamespace(),
                  "canonical_tenant_id",
                  request.canonicalTenantId(),
                  "tenant_slug",
                  source.tenantSlug(),
                  "world_slug",
                  source.worldSlug(),
                  "realm_id",
                  realm,
                  "realm_slug",
                  request.realmSlug(),
                  "realm_display_name",
                  request.realmDisplayName(),
                  "visible",
                  true,
                  "public_production",
                  true,
                  "state_scope",
                  "SHARED",
                  "playable_state_namespace_id",
                  playableNamespace,
                  "character_creation_policy",
                  "PLAYER_CREATED",
                  "catalog_revision",
                  1L,
                  "creation_request_id",
                  request.requestId(),
                  "request_digest",
                  requestDigest,
                  "receipt_digest",
                  receipt,
                  "source_world_display_name",
                  source.worldDisplayName(),
                  "complete_policy_set_evidence",
                  policyBytes));
          insert("game_session_canonical_realm_catalog", catalog);
        });
    return new CanonicalRealmCatalogSnapshot(
        request.targetNamespace(),
        source.canonicalTenantId(),
        source.tenantSlug(),
        source.worldSlug(),
        realm,
        request.realmSlug(),
        request.realmDisplayName(),
        true,
        true,
        "SHARED",
        playableNamespace,
        "PLAYER_CREATED",
        1L,
        request.requestId(),
        requestDigest,
        receipt,
        intake);
  }

  private long seedPriorLaunch(
      CanonicalRealmCatalogSnapshot catalog,
      long tenantId,
      UUID associationOperation,
      net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence tenantSource,
      StartSessionPostAuthorizationExecutionTuple tuple,
      net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence binding,
      UUID instance)
      throws Exception {
    var descriptor = binding.descriptor();
    var tupleSource = catalog.sourceIntakeReceipt();
    String bindingJson = JSON.writeValueAsString(binding);
    return Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              var runtime =
                  map(
                      "tenant_id",
                      tenantId,
                      "runtime_version",
                      Long.toString(descriptor.versionId()),
                      "owner_account_uuid",
                      tuple.preAuthorizationTuple().actor().accountId(),
                      "status",
                      "STARTING",
                      "row_version",
                      0L,
                      "game_template_id",
                      descriptor.gameTemplateId(),
                      "launch_descriptor_id",
                      descriptor.launchDescriptorId(),
                      "version_id",
                      descriptor.versionId(),
                      "release_bundle_id",
                      descriptor.releaseBundleId(),
                      "version_state_epoch",
                      descriptor.versionStateEpoch(),
                      "generation_config_revision",
                      descriptor.generationConfigRevision(),
                      "run_owned_start_request_id",
                      tuple.controlPlaneRequestId(),
                      "run_owned_start_request_digest",
                      tuple.mutationDigest(),
                      "run_owned_start_published_release_bundle_ref",
                      descriptor.publishedReleaseBundleRef(),
                      "game_instance_uuid",
                      instance);
              var fields = new LinkedHashMap<Field<?>, Object>();
              runtime.forEach((key, value) -> fields.put(DSL.field(DSL.name(key)), value));
              long allocated =
                  dsl.insertInto(DSL.table("game_instances"))
                      .set(fields)
                      .returning(DSL.field("id", Long.class))
                      .fetchSingle()
                      .get("id", Long.class);
              insert(
                  "game_session_canonical_instance_launch",
                  map(
                      "target_namespace",
                      catalog.targetNamespace(),
                      "control_plane_request_id",
                      tuple.controlPlaneRequestId(),
                      "game_session_tenant_id",
                      tenantId,
                      "canonical_tenant_id",
                      catalog.tenantId(),
                      "tenant_association_operation_id",
                      associationOperation,
                      "tenant_association_request_id",
                      tenantSource.requestId(),
                      "tenant_source_schema_version",
                      tenantSource.schemaVersion(),
                      "tenant_source_target_namespace",
                      tenantSource.targetNamespace(),
                      "tenant_source_request_id",
                      tenantSource.requestId(),
                      "tenant_source_canonical_tenant_id",
                      tenantSource.canonicalTenantId(),
                      "tenant_source_game_row_id",
                      tenantSource.sourceGameRowId(),
                      "tenant_source_game_tenant_key",
                      tenantSource.sourceGameTenantKey(),
                      "tenant_source_provenance_kind",
                      tenantSource.provenanceKind(),
                      "tenant_association_kind",
                      "FRESH_SOURCE_BOUND",
                      "game_instance_id",
                      allocated,
                      "game_instance_uuid",
                      instance,
                      "canonical_realm_id",
                      catalog.realmId(),
                      "world_slug",
                      catalog.worldSlug(),
                      "playable_state_namespace_id",
                      catalog.playableStateNamespaceId(),
                      "playable_state_scope",
                      "SHARED",
                      "public_production",
                      true,
                      "captured_starting_row_version",
                      0L,
                      "game_template_id",
                      descriptor.gameTemplateId(),
                      "launch_descriptor_id",
                      descriptor.launchDescriptorId(),
                      "version_id",
                      descriptor.versionId(),
                      "release_bundle_id",
                      descriptor.releaseBundleId(),
                      "generation_config_revision",
                      descriptor.generationConfigRevision(),
                      "version_state_epoch",
                      descriptor.versionStateEpoch(),
                      "complete_launch_binding_evidence",
                      org.jooq.JSONB.valueOf(bindingJson),
                      "original_start_session_tuple",
                      tuple.canonicalBytes(),
                      "catalog_creation_request_id",
                      catalog.creationRequestId(),
                      "catalog_revision",
                      catalog.catalogRevision(),
                      "catalog_source_intake_operation_id",
                      tupleSource.operationId(),
                      "catalog_source_intake_request_id",
                      tupleSource.intakeRequestId()));
              dsl.execute("UPDATE game_instances SET status='RUNNING' WHERE id=?", allocated);
              return allocated;
            }));
  }

  private void seedPriorCommittedAdmission(
      CanonicalRealmCatalogSnapshot catalog,
      CanonicalInitialAdmissionLaunchTarget launch,
      long epoch,
      GameplayAdmissionPointerEventRepository events) {
    var association = launch.association();
    String requestId = "controlled-initial-" + catalog.realmId();
    UUID version = association.launchBindingEvidence().releaseAttestation().canonicalVersionId();
    String digest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            catalog.targetNamespace(),
            catalog.tenantId(),
            catalog.worldSlug(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            "SHARED",
            association.gameInstanceUuid(),
            version,
            epoch,
            1L,
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            requestId);
    var request =
        new CanonicalInitialAdmissionRequest(
            catalog.targetNamespace(),
            catalog.tenantId(),
            catalog.worldSlug(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            "SHARED",
            association.gameInstanceUuid(),
            version,
            epoch,
            1L,
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            requestId,
            digest,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "sha256:" + "e".repeat(64));
    transactions.executeWithoutResult(
        ignored -> {
          var pendingAt = dsl.fetchSingle("SELECT clock_timestamp()").get(0, OffsetDateTime.class);
          insert(
              "game_session_canonical_initial_admission_attempt",
              map(
                  "target_namespace",
                  request.targetNamespace(),
                  "initial_admission_request_id",
                  requestId,
                  "request_digest",
                  digest,
                  "canonical_tenant_id",
                  catalog.tenantId(),
                  "world_slug",
                  catalog.worldSlug(),
                  "realm_id",
                  catalog.realmId(),
                  "playable_state_namespace_id",
                  catalog.playableStateNamespaceId(),
                  "playable_state_scope",
                  "SHARED",
                  "canonical_game_instance_id",
                  association.gameInstanceUuid(),
                  "game_session_tenant_id",
                  association.gameSessionTenantId(),
                  "game_instance_id",
                  launch.gameInstanceId(),
                  "canonical_version_id",
                  version,
                  "runtime_version_id",
                  launch.runtimeVersionId(),
                  "expected_catalog_revision",
                  1L,
                  "origin_kind",
                  request.originKind().name(),
                  "active_lifecycle_epoch",
                  epoch,
                  "hold_id",
                  request.holdId(),
                  "hold_fence",
                  request.holdFence(),
                  "hold_binding_digest",
                  request.holdBindingDigest(),
                  "status",
                  "PENDING",
                  "created_at",
                  pendingAt,
                  "updated_at",
                  pendingAt));
          var now =
              dsl.fetchSingle("SELECT clock_timestamp()").get(0, OffsetDateTime.class).toInstant();
          long audit =
              events.appendCanonicalInitialAdmissionOpen(request, catalog, launch, 1L, 1L, now);
          String columns =
              "world_slug,realm_slug,world_display_name,realm_display_name,tenant_id,game_instance_id,"
                  + "pointer_version,visible,requires_character_selection,state_scope,character_creation_policy,last_updated_by,"
                  + "last_update_reason,created_at,updated_at,public_production_realm,catalog_revision,realm_id,"
                  + "playable_state_namespace_id,representation_version,target_namespace,canonical_tenant_id,admission_state,"
                  + "canonical_game_instance_id,canonical_version_id,runtime_version_id,initial_admission_request_id,"
                  + "initial_admission_request_digest,initial_admission_origin_kind,initial_admission_prior_pointer_version,"
                  + "initial_admission_active_epoch,initial_admission_hold_id,initial_admission_hold_fence,initial_admission_hold_binding_digest";
          String eventColumns =
              columns
                  .replace("last_updated_by", "actor_principal")
                  .replace("last_update_reason", "reason")
                  .replace("created_at", "occurred_at")
                  .replace("updated_at", "occurred_at");
          long pointer =
              dsl.fetchSingle(
                      "INSERT INTO gameplay_admission_pointer ("
                          + columns
                          + ") SELECT "
                          + eventColumns
                          + " FROM gameplay_admission_pointer_event WHERE id=? RETURNING id",
                      audit)
                  .get("id", Long.class);
          String proof =
              binaryFrame(
                  "gs-canonical-initial-admission-commit-proof/v1",
                  digest,
                  requestId,
                  request.targetNamespace(),
                  catalog.tenantId().toString(),
                  catalog.worldSlug(),
                  catalog.realmId().toString(),
                  catalog.playableStateNamespaceId().toString(),
                  "SHARED",
                  association.gameInstanceUuid().toString(),
                  version.toString(),
                  Long.toString(launch.runtimeVersionId()),
                  Long.toString(epoch),
                  "1",
                  request.originKind().name(),
                  "",
                  request.holdId().toString(),
                  request.holdFence().toString(),
                  request.holdBindingDigest(),
                  "1",
                  Long.toString(audit),
                  "",
                  "",
                  "",
                  now.toString());
          dsl.execute(
              "UPDATE game_session_canonical_initial_admission_attempt SET status='COMMITTED',pointer_id=?,"
                  + "pointer_version=1,audit_event_id=?,commit_proof_digest=?,terminal_at=?::timestamptz,updated_at=?::timestamptz "
                  + "WHERE target_namespace=? AND initial_admission_request_id=?",
              pointer,
              audit,
              proof,
              now.atOffset(ZoneOffset.UTC),
              now.atOffset(ZoneOffset.UTC),
              request.targetNamespace(),
              requestId);
        });
  }

  private void insert(String table, Map<String, Object> values) {
    var fields = new LinkedHashMap<Field<?>, Object>();
    values.forEach((key, value) -> fields.put(DSL.field(DSL.name(key)), value));
    dsl.insertInto(DSL.table(DSL.name(table))).set(fields).execute();
  }

  private static Map<String, Object> map(Object... values) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
    return result;
  }

  private static String decimalFrame(String... fields) {
    var bytes = new ByteArrayOutputStream();
    for (String field : fields) {
      byte[] value = field.getBytes(StandardCharsets.UTF_8);
      bytes.writeBytes((value.length + ":").getBytes(StandardCharsets.US_ASCII));
      bytes.writeBytes(value);
    }
    return hash(bytes.toByteArray(), true);
  }

  private static String binaryFrame(String... fields) {
    try {
      var bytes = new ByteArrayOutputStream();
      var frame = new DataOutputStream(bytes);
      for (String field : fields) {
        byte[] value = field.getBytes(StandardCharsets.UTF_8);
        frame.writeInt(value.length);
        frame.write(value);
      }
      return hash(bytes.toByteArray(), true);
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String hash(byte[] bytes, boolean prefix) {
    try {
      return (prefix ? "sha256:" : "")
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void requireOutsideSql() {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()
        || org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive())
      throw new IllegalStateException("Controlled upstream call entered GS SQL");
  }
}
