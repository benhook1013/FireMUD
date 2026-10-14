package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidenceTestFixture;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.FixtureCanonicalRealmCatalog;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.InvalidFreshTenantAssociationException;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof for the current verified-fixture source, fresh association, catalog, and
 * STARTING launch owners. The upstream GDS receipt and fixture-policy seal are controlled test-only
 * inputs parsed by the production closed parser; this does not create Human terminal or Account
 * protection rows, activate a runtime, write World state, or claim a genuine owner handoff.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionRunOwnedCanonicalLaunchRepositoryPostgresIntegrationTest {
  private static final String NAMESPACE =
      RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_TARGET_NAMESPACE;
  private static final long SOURCE_GAME_ROW_ID = 919L;
  private static final String SOURCE_GAME_TENANT_KEY = "919";
  private static final long GAME_TEMPLATE_ID = 301L;
  private static final long OWNER_ACCOUNT_ID = 401L;
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void allocatesOneStartingLaunchWithExactPolicyAndIndependentOwnerReadback() throws Exception {
    Fixture fixture = fixture();
    AuthoredWorldSourceEvidence source = source(SOURCE_GAME_ROW_ID);
    RunOwnedInitialAdmissionLaunchEvidence evidence = launchEvidence(source, OWNER_ACCOUNT_ID);
    UUID fixtureOperationId = UUID.fromString(evidence.fixtureCapability().operationId());
    IntakeReceipt sourceReceipt =
        Objects.requireNonNull(
            fixture.transactions.execute(
                status -> fixture.sourceRepository.register(fixtureOperationId, source)),
            "registered test-only GDS source receipt");
    assertThat(fixture.sourceRepository.readByIntakeRequest(NAMESPACE, fixtureOperationId))
        .contains(sourceReceipt);

    FreshGameSessionTenantAssociation freshAssociation =
        Objects.requireNonNull(
            fixture.transactions.execute(
                status ->
                    fixture.tenantAssociationRepository.associateFresh(
                        associationOperationId(source),
                        NAMESPACE,
                        sourceReceipt,
                        evidence.creationEvidence())),
            "actual Game Session fresh private-tenant allocation");
    assertThat(freshAssociation.legacyGameSessionTenantId()).isPositive();
    assertThat(freshAssociation.sourceEvidence().sourceGameRowId())
        .isEqualTo(source.sourceGameRowId());
    assertThat(freshAssociation.sourceEvidence().sourceGameTenantKey())
        .isEqualTo(source.sourceGameTenantKey());

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    FixtureCanonicalInstanceLaunch first;
    FixtureCanonicalInstanceLaunch second;
    try {
      Future<FixtureCanonicalInstanceLaunch> firstAttempt =
          executor.submit(() -> contender(fixture, evidence, ready, start));
      Future<FixtureCanonicalInstanceLaunch> secondAttempt =
          executor.submit(() -> contender(fixture, evidence, ready, start));
      assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first = firstAttempt.get(30, TimeUnit.SECONDS);
      second = secondAttempt.get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(second).isEqualTo(first);
    FixtureCanonicalInstanceLaunch replay =
        fixture.launchRepository.createVerifiedFixtureStartingLaunch(evidence);
    assertThat(replay).isEqualTo(first);
    FixtureCanonicalInstanceLaunch independentReadback =
        fixture.launchRepository.readVerifiedFixtureLaunch(evidence);
    assertThat(independentReadback).isEqualTo(first);
    assertThat(first.status())
        .isEqualTo(
            net.firedevops.firemud.common.gamesession
                .CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                .STARTING);
    assertThat(first.rowVersion()).isZero();
    assertThat(first.gameInstanceId()).isPositive();
    assertThat(first.gameInstanceUuid()).isNotEqualTo(new UUID(0L, 0L));

    FixtureCanonicalRealmCatalog catalog = first.catalog();
    assertThat(catalog.snapshot().catalogRevision()).isPositive();
    assertThat(catalog.snapshot().sourceIntakeReceipt()).isEqualTo(sourceReceipt);
    assertThat(catalog.association().sourceEvidence()).isEqualTo(evidence.creationEvidence());
    assertThat(catalog.association().gameSessionTenantId())
        .isEqualTo(freshAssociation.legacyGameSessionTenantId());

    var sealedPolicy =
        RunOwnedInitialAdmissionFixtureWireCodec.parseFixturePolicyRequestBytes(
            evidence.fixturePolicyRequestBytes());
    assertThat(sealedPolicy.sourceEvidence()).isEqualTo(source);
    assertThat(sealedPolicy.creationEvidence()).isEqualTo(evidence.creationEvidence());
    assertThat(sealedPolicy.launchReadEvidence().publishedReleaseBundle())
        .isEqualTo(evidence.publishedReleaseBundle());
    assertThat(sealedPolicy.launchReadEvidence().versionAssetArtifactState())
        .isEqualTo(evidence.versionAssetArtifactState());
    assertThat(sealedPolicy.launchReadEvidence().versionState()).isEqualTo(evidence.versionState());
    assertThat(sealedPolicy.launchReadEvidence().launchDescriptor())
        .isEqualTo(evidence.launchDescriptor());

    Record catalogRow =
        requiredRecord(
            fixture.dsl,
            "SELECT source_game_row_id, source_game_tenant_key, fixture_policy_request_bytes, "
                + "fixture_policy_seal_operation_id, "
                + "fixture_policy_seal_request_digest, fixture_policy_seal_evidence_digest "
                + "FROM game_session_canonical_realm_catalog WHERE target_namespace = ? "
                + "AND creation_request_id = ?",
            NAMESPACE,
            fixtureOperationId);
    assertThat(catalogRow.get("source_game_row_id", Long.class))
        .isEqualTo(source.sourceGameRowId());
    assertThat(catalogRow.get("source_game_tenant_key", String.class))
        .isEqualTo(source.sourceGameTenantKey());
    assertThat(catalogRow.get("fixture_policy_request_bytes", byte[].class))
        .containsExactly(evidence.fixturePolicyRequestBytes());
    assertThat(catalogRow.get("fixture_policy_seal_operation_id", UUID.class))
        .isEqualTo(evidence.fixturePolicySeal().operationId());
    assertThat(catalogRow.get("fixture_policy_seal_request_digest", String.class))
        .isEqualTo(evidence.fixturePolicySeal().requestDigest());
    assertThat(catalogRow.get("fixture_policy_seal_evidence_digest", String.class))
        .isEqualTo(evidence.fixturePolicySeal().evidenceDigest());

    Record launchRow =
        requiredRecord(
            fixture.dsl,
            "SELECT game_session_tenant_id, game_instance_id, game_instance_uuid, "
                + "catalog_creation_request_id, catalog_revision, fixture_policy_request_bytes, "
                + "fixture_policy_seal_operation_id, fixture_policy_seal_request_digest, "
                + "fixture_policy_seal_evidence_digest FROM game_session_canonical_instance_launch "
                + "WHERE target_namespace = ? AND control_plane_request_id = ?",
            NAMESPACE,
            fixtureOperationId.toString());
    assertThat(launchRow.get("game_session_tenant_id", Long.class))
        .isEqualTo(freshAssociation.legacyGameSessionTenantId());
    assertThat(launchRow.get("game_instance_id", Long.class)).isEqualTo(first.gameInstanceId());
    assertThat(launchRow.get("game_instance_uuid", UUID.class)).isEqualTo(first.gameInstanceUuid());
    assertThat(launchRow.get("catalog_creation_request_id", UUID.class))
        .isEqualTo(catalog.snapshot().creationRequestId());
    assertThat(launchRow.get("catalog_revision", Long.class))
        .isEqualTo(catalog.snapshot().catalogRevision());
    assertThat(launchRow.get("fixture_policy_request_bytes", byte[].class))
        .containsExactly(evidence.fixturePolicyRequestBytes());
    assertThat(launchRow.get("fixture_policy_seal_operation_id", UUID.class))
        .isEqualTo(evidence.fixturePolicySeal().operationId());
    assertThat(launchRow.get("fixture_policy_seal_request_digest", String.class))
        .isEqualTo(evidence.fixturePolicySeal().requestDigest());
    assertThat(launchRow.get("fixture_policy_seal_evidence_digest", String.class))
        .isEqualTo(evidence.fixturePolicySeal().evidenceDigest());

    Record runtime =
        requiredRecord(
            fixture.dsl,
            "SELECT tenant_id, id, game_instance_uuid, owner_account_id, status, row_version, "
                + "game_template_id, launch_descriptor_id, version_id, release_bundle_id, "
                + "generation_config_revision, version_state_epoch, run_owned_start_request_id, "
                + "run_owned_start_request_digest, run_owned_start_published_release_bundle_ref "
                + "FROM game_instances WHERE tenant_id = ? AND id = ?",
            freshAssociation.legacyGameSessionTenantId(),
            first.gameInstanceId());
    assertThat(runtime.get("game_instance_uuid", UUID.class)).isEqualTo(first.gameInstanceUuid());
    assertThat(runtime.get("owner_account_id", Long.class))
        .isEqualTo(evidence.fixtureCapability().ownerAccountId());
    assertThat(runtime.get("status", String.class)).isEqualTo("STARTING");
    assertThat(runtime.get("row_version", Long.class)).isZero();
    assertThat(runtime.get("game_template_id", Long.class)).isEqualTo(evidence.gameTemplateId());
    assertThat(runtime.get("launch_descriptor_id", String.class))
        .isEqualTo(evidence.launchDescriptor().getLaunchDescriptorId());
    assertThat(runtime.get("version_id", Long.class))
        .isEqualTo(evidence.publishedReleaseBundle().getVersionId());
    assertThat(runtime.get("release_bundle_id", Long.class))
        .isEqualTo(evidence.publishedReleaseBundle().getId());
    assertThat(runtime.get("generation_config_revision", String.class))
        .isEqualTo(evidence.publishedReleaseBundle().getGenerationConfigRevision());
    assertThat(runtime.get("version_state_epoch", Long.class))
        .isEqualTo(evidence.versionState().getVersionStateEpoch())
        .isPositive();
    assertThat(runtime.get("run_owned_start_request_id", String.class))
        .isEqualTo(evidence.fixtureCapability().operationId());
    assertThat(runtime.get("run_owned_start_request_digest", String.class))
        .isEqualTo(evidence.fixtureRuntimeRequestDigest());
    assertThat(runtime.get("run_owned_start_published_release_bundle_ref", String.class))
        .isEqualTo(evidence.publishedReleaseBundle().getPublishedReleaseBundleRef());
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_authored_world_source_intake"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_scope_reservation"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_canonical_claim"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_fresh_tenant_association"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isEqualTo(1);
    assertThat(
            fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();

    OwnerRows beforeNakedInsert = ownerRows(fixture, evidence, first.gameInstanceId());
    assertThatThrownBy(() -> insertNakedCatalogRow(fixture, evidence))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining(
            "New canonical realm requires exactly one complete Human or verified fixture policy provenance");
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);
    assertThat(ownerRows(fixture, evidence, first.gameInstanceId())).isEqualTo(beforeNakedInsert);

    OwnerRows originalRows = ownerRows(fixture, evidence, first.gameInstanceId());
    AuthoredWorldSourceEvidence changedSource = source(SOURCE_GAME_ROW_ID + 1L);
    RunOwnedInitialAdmissionLaunchEvidence changedSourceEvidence =
        launchEvidence(changedSource, OWNER_ACCOUNT_ID);
    assertThatThrownBy(
            () ->
                fixture.launchRepository.createVerifiedFixtureStartingLaunch(changedSourceEvidence))
        .isInstanceOf(InvalidFreshTenantAssociationException.class)
        .hasMessageContaining("complete original Game Design receipt");
    assertThat(ownerRows(fixture, evidence, first.gameInstanceId())).isEqualTo(originalRows);

    RunOwnedInitialAdmissionLaunchEvidence changedPolicyEvidence =
        launchEvidence(source, OWNER_ACCOUNT_ID + 1L);
    assertThatThrownBy(
            () ->
                fixture.launchRepository.createVerifiedFixtureStartingLaunch(changedPolicyEvidence))
        .isInstanceOf(GameSessionCanonicalRealmCatalogRepository.CatalogConflictException.class)
        .hasMessageContaining("reused with changed input or source evidence");
    assertThat(ownerRows(fixture, evidence, first.gameInstanceId())).isEqualTo(originalRows);
  }

  private static FixtureCanonicalInstanceLaunch contender(
      Fixture fixture,
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent canonical launch gate was not released");
    }
    return fixture.launchRepository.createVerifiedFixtureStartingLaunch(evidence);
  }

  private static void insertNakedCatalogRow(
      Fixture fixture, RunOwnedInitialAdmissionLaunchEvidence evidence) {
    FixtureCanonicalRealmCatalog catalog =
        fixture.catalogRepository.readVerifiedFixtureRealm(evidence);
    IntakeReceipt sourceReceipt = catalog.snapshot().sourceIntakeReceipt();
    fixture.dsl.execute(
        "INSERT INTO game_session_canonical_realm_catalog "
            + "(target_namespace, canonical_tenant_id, tenant_slug, world_slug, realm_id, "
            + "realm_slug, realm_display_name, visible, public_production, state_scope, "
            + "playable_state_namespace_id, character_creation_policy, catalog_revision, "
            + "creation_request_id, request_digest, receipt_digest, source_intake_operation_id, "
            + "source_intake_schema_version, source_intake_request_id, source_intake_request_digest, "
            + "source_intake_receipt_digest, source_schema_version, source_registration_request_id, "
            + "source_operation_id, source_request_digest, source_world_display_name, "
            + "source_game_row_id, source_game_tenant_key, source_provenance_kind, "
            + "source_evidence_digest) "
            + "SELECT intake.target_namespace, intake.canonical_tenant_id, intake.tenant_slug, "
            + "intake.world_slug, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, intake.operation_id, "
            + "intake.schema_version, intake.intake_request_id, intake.request_digest, "
            + "intake.receipt_digest, intake.source_schema_version, "
            + "intake.source_registration_request_id, intake.source_operation_id, "
            + "intake.source_request_digest, intake.world_display_name, intake.source_game_row_id, "
            + "intake.source_game_tenant_key, intake.source_provenance_kind, "
            + "intake.source_evidence_digest FROM game_session_authored_world_source_intake intake "
            + "WHERE intake.operation_id = ?",
        UUID.randomUUID(),
        "naked-realm",
        "Naked Realm",
        evidence.fixtureCapability().visible(),
        evidence.fixtureCapability().publicProductionRealm(),
        evidence.fixtureCapability().stateScope(),
        catalog.snapshot().playableStateNamespaceId(),
        evidence.fixtureCapability().characterCreationPolicy(),
        UUID.randomUUID(),
        "sha256:" + "0".repeat(64),
        "sha256:" + "1".repeat(64),
        sourceReceipt.operationId());
  }

  private static OwnerRows ownerRows(
      Fixture fixture, RunOwnedInitialAdmissionLaunchEvidence evidence, long gameInstanceId) {
    UUID fixtureOperationId = UUID.fromString(evidence.fixtureCapability().operationId());
    return new OwnerRows(
        fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_authored_world_source_intake"))),
        fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_fresh_tenant_association"))),
        fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))),
        fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances"))),
        fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))),
        xmin(
            fixture.dsl,
            "SELECT xmin::text AS xmin FROM game_session_authored_world_source_intake "
                + "WHERE intake_request_id = ?",
            fixtureOperationId),
        xmin(
            fixture.dsl,
            "SELECT xmin::text AS xmin FROM game_session_fresh_tenant_association "
                + "WHERE association_request_id = ?",
            evidence.creationEvidence().creationRequestId()),
        xmin(
            fixture.dsl,
            "SELECT xmin::text AS xmin FROM game_session_canonical_realm_catalog "
                + "WHERE creation_request_id = ?",
            fixtureOperationId),
        xmin(
            fixture.dsl,
            "SELECT xmin::text AS xmin FROM game_instances WHERE id = ?",
            gameInstanceId),
        xmin(
            fixture.dsl,
            "SELECT xmin::text AS xmin FROM game_session_canonical_instance_launch "
                + "WHERE control_plane_request_id = ?",
            fixtureOperationId.toString()));
  }

  private static String xmin(DSLContext dsl, String sql, Object selector) {
    return requiredRecord(dsl, sql, selector).get("xmin", String.class);
  }

  private static AuthoredWorldSourceEvidence source(long sourceGameRowId) {
    UUID canonicalTenantId = uuid(1001);
    UUID registrationRequestId =
        UUID.fromString(RunOwnedInitialAdmissionLaunchEvidenceTestFixture.operationId());
    UUID sourceOperationId = uuid(1002);
    String tenantSlug = "run-owned-fixture-tenant";
    String worldSlug = RunOwnedInitialAdmissionFixtureWireCodec.WORLD_SLUG;
    String displayName = RunOwnedInitialAdmissionFixtureWireCodec.WORLD_DISPLAY_NAME;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            SOURCE_GAME_TENANT_KEY,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        sourceOperationId,
        requestDigest,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        SOURCE_GAME_TENANT_KEY,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static RunOwnedInitialAdmissionLaunchEvidence launchEvidence(
      AuthoredWorldSourceEvidence source, long ownerAccountId) {
    // The closed GDS fixture receipt binds this controlled capability to the source-owner key.
    // Game Session's private key comes from its association owner and is never selected here.
    StartSessionRequest controlledCapabilityInput =
        new StartSessionRequest(
            Long.parseLong(source.sourceGameTenantKey()),
            GAME_TEMPLATE_ID,
            RunOwnedInitialAdmissionLaunchEvidenceTestFixture.operationId(),
            ownerAccountId);
    RunOwnedInitialAdmissionLaunchEvidence evidence =
        RunOwnedInitialAdmissionLaunchEvidenceTestFixture.create(controlledCapabilityInput, source);
    assertThat(evidence.fixtureCapability().tenantId())
        .isEqualTo(Long.parseLong(source.sourceGameTenantKey()));
    return evidence;
  }

  private static UUID associationOperationId(AuthoredWorldSourceEvidence source) {
    return UUID.nameUUIDFromBytes(
        ("test-only-gs-fresh-association:" + source.canonicalTenantId())
            .getBytes(StandardCharsets.UTF_8));
  }

  private static Record requiredRecord(DSLContext dsl, String sql, Object... bindings) {
    return Objects.requireNonNull(dsl.fetchOne(sql, bindings), "Expected committed owner row");
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private static Fixture fixture() {
    String schema = "gs_run_owned_launch_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameSessionAuthoredWorldSourceRepository sourceRepository =
        new GameSessionAuthoredWorldSourceRepository(dsl);
    GameSessionFreshTenantAssociationRepository tenantAssociationRepository =
        new GameSessionFreshTenantAssociationRepository(dsl);
    GameSessionCanonicalRealmCatalogRepository catalogRepository =
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, NAMESPACE);
    GameSessionCanonicalInstanceLaunchRepository launchRepository =
        new GameSessionCanonicalInstanceLaunchRepository(
            dsl, transactionManager, NAMESPACE, catalogRepository);
    return new Fixture(
        dsl,
        transactions,
        sourceRepository,
        tenantAssociationRepository,
        catalogRepository,
        launchRepository);
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transactions,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionFreshTenantAssociationRepository tenantAssociationRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionCanonicalInstanceLaunchRepository launchRepository) {}

  private record OwnerRows(
      int sourceRows,
      int associationRows,
      int catalogRows,
      int runtimeRows,
      int launchRows,
      String sourceXmin,
      String associationXmin,
      String catalogXmin,
      String runtimeXmin,
      String launchXmin) {}
}
