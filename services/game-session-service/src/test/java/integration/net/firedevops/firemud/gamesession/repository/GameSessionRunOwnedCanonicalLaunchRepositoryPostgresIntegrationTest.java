package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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

  @Test
  void retriesCanonicalLaunchAfterAdvisoryWaitWithRepeatableReadDatasourceDefault()
      throws Exception {
    Fixture fixture = fixtureWithRepeatableReadDefault();
    AuthoredWorldSourceEvidence source = source(SOURCE_GAME_ROW_ID);
    RunOwnedInitialAdmissionLaunchEvidence evidence = launchEvidence(source, OWNER_ACCOUNT_ID);
    FixtureCanonicalRealmCatalog preparedCatalog =
        fixture.catalogRepository.establishVerifiedFixtureRealm(evidence);
    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne("SHOW default_transaction_isolation"),
                    "PostgreSQL did not return the datasource transaction isolation setting")
                .get(0, String.class))
        .isEqualTo("repeatable read");
    assertThat(fixture.catalogRepository.readVerifiedFixtureRealm(evidence))
        .isEqualTo(preparedCatalog);

    String lockKey =
        "canonical-launch:" + NAMESPACE + ":" + evidence.fixtureCapability().operationId();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection lockConnection = fixture.dataSource.getConnection()) {
      boolean lockHeld = false;
      try {
        try (PreparedStatement acquire =
            lockConnection.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
          acquire.setString(1, lockKey);
          acquire.execute();
          lockHeld = true;
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<FixtureCanonicalInstanceLaunch> firstAttempt =
            executor.submit(() -> contender(fixture, evidence, ready, start));
        Future<FixtureCanonicalInstanceLaunch> secondAttempt =
            executor.submit(() -> contender(fixture, evidence, ready, start));
        assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        awaitAdvisoryWaiters(fixture, lockKey, 2);

        try (PreparedStatement release =
            lockConnection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
          release.setString(1, lockKey);
          try (var result = release.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).isTrue();
          }
          lockHeld = false;
        }

        FixtureCanonicalInstanceLaunch first = firstAttempt.get(30, TimeUnit.SECONDS);
        FixtureCanonicalInstanceLaunch second = secondAttempt.get(30, TimeUnit.SECONDS);
        assertThat(second).isEqualTo(first);
        assertThat(first.status())
            .isEqualTo(
                net.firedevops.firemud.common.gamesession
                    .CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                    .STARTING);
        assertThat(
                fixture.dsl.fetchCount(
                    DSL.table(DSL.name("game_session_canonical_instance_launch"))))
            .isEqualTo(1);
        assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isEqualTo(1);

        OwnerRows committedRows = ownerRows(fixture, evidence, first.gameInstanceId());
        assertThat(fixture.launchRepository.readVerifiedFixtureLaunch(evidence)).isEqualTo(first);
        assertThat(fixture.launchRepository.createVerifiedFixtureStartingLaunch(evidence))
            .isEqualTo(first);
        assertThat(ownerRows(fixture, evidence, first.gameInstanceId())).isEqualTo(committedRows);
      } finally {
        if (lockHeld) {
          try (PreparedStatement release =
              lockConnection.prepareStatement(
                  "SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            release.setString(1, lockKey);
            release.execute();
          }
        }
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private static void awaitAdvisoryWaiters(Fixture fixture, String lockKey, int expectedWaiters) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      Long waiting =
          Objects.requireNonNull(
                  fixture.dsl.fetchOne(
                      "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                          + "AND NOT granted AND objsubid = 1 "
                          + "AND classid = (((hashtextextended(?, 0) >> 32) & 4294967295)::oid) "
                          + "AND objid = ((hashtextextended(?, 0) & 4294967295)::oid)",
                      lockKey,
                      lockKey),
                  "PostgreSQL did not return the advisory-lock waiter count")
              .get(0, Long.class);
      if (waiting == expectedWaiters) return;
      try {
        Thread.sleep(10);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while awaiting canonical launch advisory-lock waiters", interrupted);
      }
    }
    throw new AssertionError(
        "Both canonical launch calls did not wait on the original advisory lock");
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

  @Test
  void originalV1003CatalogRetainsPhysicalOwnerRowsWithoutAcquiringMissingPublishedPolicy() {
    Fixture fixture = fixture("1003");
    var source = source(SOURCE_GAME_ROW_ID);
    var evidence = launchEvidence(source, OWNER_ACCOUNT_ID);
    var receipt = registerAndAssociate(fixture, source, evidence);
    insertHistoricalCatalog(fixture, receipt, uuid(1101));
    var columns = historicalOwnerColumns(fixture);
    var before = originalOwnerRows(fixture, columns);
    assertThat(before.values()).allSatisfy(rows -> assertThat(rows).hasSize(1));
    assertThat(columns.get("game_session_fresh_tenant_association"))
        .contains("complete_source_evidence");
    assertThat(columns.get("game_session_canonical_realm_catalog"))
        .doesNotContain("complete_policy_set_evidence");
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT complete_source_evidence FROM "
                        + "game_session_fresh_tenant_association")
                .get("complete_source_evidence"))
        .isNotNull();
    int rank =
        Objects.requireNonNull(
            fixture
                .dsl
                .fetchSingle("SELECT max(installed_rank) AS rank FROM flyway_schema_history")
                .get("rank", Integer.class));
    var historyBefore = originalHistoryRows(fixture, rank);

    for (String target : java.util.List.of("1004", "1005")) {
      migrate(fixture, target).load().migrate();
      assertThat(originalOwnerRows(fixture, columns))
          .as("original owner rows at V%s", target)
          .isEqualTo(before);
      assertThat(originalHistoryRows(fixture, rank)).isEqualTo(historyBefore);
    }
    migrate(fixture, null).load().migrate();
    assertThat(originalOwnerRows(fixture, columns)).isEqualTo(before);
    assertThat(originalHistoryRows(fixture, rank)).isEqualTo(historyBefore);
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT complete_policy_set_evidence IS NULL "
                        + "AND fixture_policy_request_bytes IS NULL AND fixture_policy_seal_operation_id IS NULL "
                        + "AND fixture_policy_seal_request_digest IS NULL "
                        + "AND fixture_policy_seal_evidence_digest IS NULL AS unqualified "
                        + "FROM game_session_canonical_realm_catalog")
                .get("unqualified", Boolean.class))
        .isTrue();

    var catalogs = new GameSessionCanonicalRealmCatalogRepository(fixture.dsl);
    assertThatThrownBy(() -> catalogs.readByRequest(NAMESPACE, uuid(1105)))
        .isInstanceOf(
            GameSessionCanonicalRealmCatalogRepository.InvalidCatalogEvidenceException.class)
        .hasMessageContaining("Exactly one complete catalog policy provenance required");
    assertThat(catalogs.readVisiblePublicProductionSnapshots(NAMESPACE)).isEmpty();
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_session_canonical_realm_catalog "
                        + "SET complete_policy_set_evidence = decode('0102', 'hex')"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(originalOwnerRows(fixture, columns)).isEqualTo(before);
    assertThat(originalHistoryRows(fixture, rank)).isEqualTo(historyBefore);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isZero();
  }

  @Test
  void originalV1004LaunchRetainsPhysicalRowsWithoutAcquiringTupleOrCatalogCapture() {
    Fixture fixture = fixture("1004");
    var source = source(SOURCE_GAME_ROW_ID);
    var evidence = launchEvidence(source, OWNER_ACCOUNT_ID);
    var receipt = registerAndAssociate(fixture, source, evidence);
    var request =
        new net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest(
            uuid(1105),
            NAMESPACE,
            source.canonicalTenantId(),
            receipt.operationId(),
            "historical-production",
            "Historical Production",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    var policy =
        CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            request,
            source,
            uuid(1203),
            72L,
            "human-history-policy",
            "human-release-bundle",
            "generation-1",
            3L);
    UUID realmId = uuid(1201);
    // The current catalog writer requires later fixture-provenance columns. Seed the original
    // V1004 SQL shape with a closed complete policy; current readback is checked after upgrade.
    insertHistoricalCatalog(fixture, receipt, realmId, policy);
    insertPreV1005HumanLaunch(fixture, receipt, realmId, "original-human-history", policy);

    var columns = historicalOwnerColumns(fixture);
    for (String table :
        java.util.List.of("game_instances", "game_session_canonical_instance_launch")) {
      columns.put(
          table,
          fixture
              .dsl
              .fetch(
                  "SELECT column_name FROM information_schema.columns "
                      + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                  fixture.schema,
                  table)
              .getValues("column_name", String.class));
    }
    assertThat(columns.get("game_session_canonical_instance_launch"))
        .doesNotContain(
            "original_start_session_tuple",
            "catalog_creation_request_id",
            "catalog_revision",
            "catalog_source_intake_operation_id",
            "catalog_source_intake_request_id");
    var before = originalOwnerRows(fixture, columns);
    assertThat(before).hasSize(9);
    assertThat(before.values()).allSatisfy(rows -> assertThat(rows).hasSize(1));
    int rank =
        Objects.requireNonNull(
            fixture
                .dsl
                .fetchSingle("SELECT max(installed_rank) AS rank FROM flyway_schema_history")
                .get("rank", Integer.class));
    var historyBefore = originalHistoryRows(fixture, rank);

    for (String target : java.util.List.of("1005", "1010", "1011")) {
      migrate(fixture, target).load().migrate();
      assertThat(originalOwnerRows(fixture, columns))
          .as("original owner rows at V%s", target)
          .isEqualTo(before);
      assertThat(originalHistoryRows(fixture, rank)).isEqualTo(historyBefore);
    }
    migrate(fixture, null).load().migrate();
    assertThat(originalOwnerRows(fixture, columns)).isEqualTo(before);
    assertThat(originalHistoryRows(fixture, rank)).isEqualTo(historyBefore);
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT original_start_session_tuple IS NULL AND catalog_creation_request_id IS NULL "
                        + "AND catalog_revision IS NULL AND catalog_source_intake_operation_id IS NULL "
                        + "AND catalog_source_intake_request_id IS NULL AS unqualified "
                        + "FROM game_session_canonical_instance_launch")
                .get("unqualified", Boolean.class))
        .isTrue();
    assertThat(fixture.catalogRepository.readByRequest(NAMESPACE, request.requestId())).isPresent();

    assertThatThrownBy(
            () ->
                insertPreV1005HumanLaunch(
                    fixture, receipt, realmId, "new-missing-history-capture", policy))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining(
            "New canonical launch requires exactly one complete Human or verified fixture provenance and exact catalog identity");
    var tuple =
        CanonicalHumanPublicationOwnerTestFixtures.tuple(
            NAMESPACE,
            source.canonicalTenantId(),
            uuid(Math.toIntExact(OWNER_ACCOUNT_ID)),
            GAME_TEMPLATE_ID,
            "original-human-history");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_session_canonical_instance_launch SET original_start_session_tuple = ?, "
                        + "catalog_creation_request_id = ?, catalog_revision = ?, "
                        + "catalog_source_intake_operation_id = ?, catalog_source_intake_request_id = ?",
                    tuple.canonicalBytes(),
                    request.requestId(),
                    1L,
                    receipt.operationId(),
                    receipt.intakeRequestId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining("Game Session canonical instance launch evidence is immutable");
    assertThat(originalOwnerRows(fixture, columns)).isEqualTo(before);
    assertThat(originalHistoryRows(fixture, rank)).isEqualTo(historyBefore);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isEqualTo(1);
    assertThat(
            fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
        .isEqualTo(1);
  }

  @Test
  void forwardHumanPolicyGuardPreservesActualV1011HistoryAndRejectsLegacyCatalogReuse() {
    Fixture fixture = fixture("1003");
    var source = source(SOURCE_GAME_ROW_ID);
    var evidence = launchEvidence(source, OWNER_ACCOUNT_ID);
    var receipt = registerAndAssociate(fixture, source, evidence);
    UUID realmId = uuid(1101);
    insertHistoricalCatalog(fixture, receipt, realmId);
    migrate(fixture, "1011").load().migrate();
    insertHumanLaunch(fixture, receipt, realmId, "retained-human-launch");
    var before = retainedLaunchCatalogRows(fixture);
    Record triggerBefore = launchTriggerIdentity(fixture);

    migrate(fixture, null).load().migrate();

    assertThat(retainedLaunchCatalogRows(fixture)).isEqualTo(before);
    assertThat(launchTriggerIdentity(fixture)).isEqualTo(triggerBefore);
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '1011.1' AND success")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThatThrownBy(
            () -> insertHumanLaunch(fixture, receipt, realmId, "new-legacy-human-launch"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining(
            "Launch provenance differs from the exact retained canonical catalog owner");
    assertThat(retainedLaunchCatalogRows(fixture)).isEqualTo(before);
  }

  @Test
  void forwardHumanPolicyGuardAcceptsCompletePublishedCatalog() {
    Fixture fixture = fixture();
    var source = source(SOURCE_GAME_ROW_ID);
    var evidence = launchEvidence(source, OWNER_ACCOUNT_ID);
    var receipt = registerAndAssociate(fixture, source, evidence);
    var request =
        new net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest(
            uuid(1102),
            NAMESPACE,
            source.canonicalTenantId(),
            receipt.operationId(),
            "human-production",
            "Human Production",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    var policy =
        CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            request,
            source,
            uuid(1103),
            72L,
            "human-policy-publication",
            "human-release-bundle",
            "generation-1",
            3L);
    var prepared = fixture.catalogRepository.prepareInitialPublicProduction(request, policy);
    var catalog =
        Objects.requireNonNull(
            fixture.transactions.execute(
                status -> fixture.catalogRepository.createInitialPublicProduction(prepared)));
    insertHumanLaunch(fixture, receipt, catalog.realmId(), "complete-human-launch");
    assertThat(
            fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
        .isEqualTo(1);
  }

  private static java.util.Map<String, java.util.List<String>> historicalOwnerColumns(
      Fixture fixture) {
    var columns = new java.util.LinkedHashMap<String, java.util.List<String>>();
    for (String table :
        java.util.List.of(
            "game_session_tenant_scope_reservation",
            "game_session_tenant_canonical_claim",
            "game_session_fresh_tenant_association",
            "game_session_authored_world_tenant_source_binding",
            "game_session_authored_world_source_intake",
            "gameplay_tenant_shared_playable_state_namespace",
            "game_session_canonical_realm_catalog")) {
      columns.put(
          table,
          fixture
              .dsl
              .fetch(
                  "SELECT column_name FROM information_schema.columns "
                      + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                  fixture.schema,
                  table)
              .getValues("column_name", String.class));
    }
    return columns;
  }

  private static java.util.Map<String, java.util.List<java.util.Map<String, Object>>>
      originalOwnerRows(Fixture fixture, java.util.Map<String, java.util.List<String>> columns) {
    var rows = new java.util.LinkedHashMap<String, java.util.List<java.util.Map<String, Object>>>();
    columns.forEach(
        (table, names) -> {
          String projection =
              names.stream()
                  .map(name -> "\"" + name.replace("\"", "\"\"") + "\"")
                  .collect(java.util.stream.Collectors.joining(", "));
          rows.put(
              table,
              fixture
                  .dsl
                  .fetch(
                      "SELECT jsonb_build_array("
                          + projection
                          + ")::text AS original_bytes, xmin::text AS physical_version FROM \""
                          + table
                          + "\" ORDER BY original_bytes")
                  .intoMaps());
        });
    return rows;
  }

  private static java.util.List<java.util.Map<String, Object>> originalHistoryRows(
      Fixture fixture, int rank) {
    return fixture
        .dsl
        .fetch(
            "SELECT row_to_json(history)::text AS original_bytes, "
                + "xmin::text AS physical_version FROM flyway_schema_history history "
                + "WHERE installed_rank <= ? ORDER BY installed_rank",
            rank)
        .intoMaps();
  }

  private static IntakeReceipt registerAndAssociate(
      Fixture fixture,
      AuthoredWorldSourceEvidence source,
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    var receipt =
        Objects.requireNonNull(
            fixture.transactions.execute(
                status ->
                    fixture.sourceRepository.register(
                        UUID.fromString(evidence.fixtureCapability().operationId()), source)));
    fixture.transactions.executeWithoutResult(
        status ->
            fixture.tenantAssociationRepository.associateFresh(
                associationOperationId(source), NAMESPACE, receipt, evidence.creationEvidence()));
    return receipt;
  }

  private static java.util.List<String> retainedLaunchCatalogRows(Fixture fixture) {
    return fixture
        .dsl
        .fetch(
            "SELECT 'catalog' AS kind, xmin::text AS xmin, to_jsonb(owner_row)::text AS bytes "
                + "FROM game_session_canonical_realm_catalog owner_row UNION ALL "
                + "SELECT 'launch', xmin::text, to_jsonb(owner_row)::text "
                + "FROM game_session_canonical_instance_launch owner_row ORDER BY kind, bytes")
        .map(row -> row.get("kind") + "/" + row.get("xmin") + "/" + row.get("bytes"));
  }

  private static Record launchTriggerIdentity(Fixture fixture) {
    return fixture.dsl.fetchSingle(
        "SELECT oid, tgname, tgfoid, tgtype, tgenabled FROM pg_trigger "
            + "WHERE tgrelid = 'game_session_canonical_instance_launch'::regclass "
            + "AND tgname = 'gs_canonical_instance_launch_complete_catalog_identity'");
  }

  private static void insertHistoricalCatalog(
      Fixture fixture, IntakeReceipt receipt, UUID realmId) {
    insertHistoricalCatalog(fixture, receipt, realmId, null);
  }

  private static void insertHistoricalCatalog(
      Fixture fixture,
      IntakeReceipt receipt,
      UUID realmId,
      net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence.PublishedSet policy) {
    var intake =
        requiredRecord(
            fixture.dsl,
            "SELECT * FROM game_session_authored_world_source_intake WHERE operation_id = ?",
            receipt.operationId());
    String characterCreationPolicy = policy == null ? "ALLOW_NEW" : "PRESEEDED_ONLY";
    var columns = new java.util.LinkedHashMap<String, Object>();
    for (String field :
        java.util.List.of(
            "target_namespace",
            "canonical_tenant_id",
            "tenant_slug",
            "world_slug",
            "source_schema_version",
            "source_registration_request_id",
            "source_operation_id",
            "source_request_digest",
            "source_game_row_id",
            "source_game_tenant_key",
            "source_provenance_kind",
            "source_evidence_digest")) {
      columns.put(field, intake.get(field));
    }
    columns.put("realm_id", realmId);
    columns.put("realm_slug", "historical-production");
    columns.put("realm_display_name", "Historical Production");
    columns.put("visible", true);
    columns.put("public_production", true);
    columns.put("state_scope", "SHARED");
    columns.put("playable_state_namespace_id", uuid(1104));
    columns.put("character_creation_policy", characterCreationPolicy);
    columns.put("catalog_revision", 1L);
    columns.put("creation_request_id", uuid(1105));
    var source = receipt.source();
    UUID creationId = uuid(1105);
    String requestDigest =
        CanonicalHumanPublicationOwnerTestFixtures.historicalDigest(
            "game-session-canonical-realm-catalog-request/v1",
            "1",
            creationId.toString(),
            NAMESPACE,
            source.canonicalTenantId().toString(),
            receipt.operationId().toString(),
            receipt.intakeRequestId().toString(),
            receipt.requestDigest(),
            receipt.receiptDigest(),
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
            "historical-production",
            "Historical Production",
            "true",
            "true",
            "SHARED",
            characterCreationPolicy,
            "ABSENT",
            "ABSENT");
    if (policy != null) {
      byte[] policyBytes =
          net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec.encode(policy);
      columns.put("complete_policy_set_evidence", policyBytes);
      requestDigest =
          CanonicalHumanPublicationOwnerTestFixtures.historicalDigest(
              "game-session-complete-catalog-request/v1",
              requestDigest,
              java.util.HexFormat.of().formatHex(policyBytes));
    }
    columns.put("request_digest", requestDigest);
    columns.put(
        "receipt_digest",
        CanonicalHumanPublicationOwnerTestFixtures.historicalDigest(
            "game-session-canonical-realm-catalog-receipt/v1",
            "1",
            NAMESPACE,
            source.canonicalTenantId().toString(),
            creationId.toString(),
            requestDigest,
            realmId.toString(),
            uuid(1104).toString(),
            "1",
            receipt.operationId().toString(),
            receipt.receiptDigest()));
    columns.put("source_intake_operation_id", receipt.operationId());
    columns.put("source_intake_schema_version", 1);
    columns.put("source_intake_request_id", receipt.intakeRequestId());
    columns.put("source_intake_request_digest", receipt.requestDigest());
    columns.put("source_intake_receipt_digest", receipt.receiptDigest());
    columns.put("source_world_display_name", receipt.source().worldDisplayName());
    var namespace = new java.util.LinkedHashMap<String, Object>();
    for (var entry : columns.entrySet()) {
      String field = entry.getKey();
      if (field.equals("target_namespace")
          || field.equals("canonical_tenant_id")
          || field.equals("playable_state_namespace_id")
          || field.startsWith("source_") && !field.equals("source_world_display_name")) {
        namespace.put(field, entry.getValue());
      }
    }
    fixture.transactions.executeWithoutResult(
        status -> {
          insertOwnerRow(fixture, "gameplay_tenant_shared_playable_state_namespace", namespace);
          insertOwnerRow(fixture, "game_session_canonical_realm_catalog", columns);
        });
  }

  private static void insertPreV1005HumanLaunch(
      Fixture fixture,
      IntakeReceipt receipt,
      UUID realmId,
      String requestId,
      net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence.PublishedSet policy) {
    var fresh =
        requiredRecord(
            fixture.dsl,
            "SELECT * FROM game_session_fresh_tenant_association WHERE canonical_tenant_id = ?",
            receipt.source().canonicalTenantId());
    var catalog =
        requiredRecord(
            fixture.dsl,
            "SELECT * FROM game_session_canonical_realm_catalog WHERE realm_id = ?",
            realmId);
    UUID instanceUuid = UUID.randomUUID();
    var tuple =
        CanonicalHumanPublicationOwnerTestFixtures.tuple(
            NAMESPACE,
            receipt.source().canonicalTenantId(),
            uuid(Math.toIntExact(OWNER_ACCOUNT_ID)),
            GAME_TEMPLATE_ID,
            requestId);
    var descriptorRequest =
        new net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            requestId,
            receipt.source().canonicalTenantId(),
            receipt.source().worldSlug(),
            receipt.source().operationId(),
            receipt.source().evidenceDigest(),
            GAME_TEMPLATE_ID,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptor =
        net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "human-launch-descriptor",
            72L,
            false,
            null,
            "{}",
            "generation-1",
            3L,
            73L,
            "human-release-bundle",
            false,
            null);
    // Closed complete pair, but upstream publication and Account identity remain controlled inputs.
    var binding = CanonicalHumanPublicationOwnerTestFixtures.completeBinding(descriptor, policy);
    String bindingJson = new tools.jackson.databind.ObjectMapper().writeValueAsString(binding);
    fixture.transactions.executeWithoutResult(
        status -> {
          long instanceId =
              fixture
                  .dsl
                  .fetchSingle(
                      "INSERT INTO game_instances (tenant_id, runtime_version, owner_account_id, owner_account_uuid, status, row_version, "
                          + "game_instance_uuid, game_template_id, launch_descriptor_id, version_id, release_bundle_id, "
                          + "generation_config_revision, version_state_epoch, run_owned_start_request_id, "
                          + "run_owned_start_request_digest, run_owned_start_published_release_bundle_ref) "
                          + "VALUES (?, '72', NULL, ?, 'STARTING', 0, ?, ?, 'human-launch-descriptor', 72, 73, 'generation-1', "
                          + "3, ?, ?, 'human-release-bundle') RETURNING id",
                      fresh.get("legacy_game_session_tenant_id"),
                      tuple.preAuthorizationTuple().action().target().ownerAccountId(),
                      instanceUuid,
                      GAME_TEMPLATE_ID,
                      requestId,
                      tuple.mutationDigest())
                  .get("id", Long.class);
          var columns = new java.util.LinkedHashMap<String, Object>();
          columns.put("target_namespace", NAMESPACE);
          columns.put("control_plane_request_id", requestId);
          columns.put("game_session_tenant_id", fresh.get("legacy_game_session_tenant_id"));
          columns.put("canonical_tenant_id", receipt.source().canonicalTenantId());
          for (String field :
              java.util.List.of(
                  "association_operation_id",
                  "association_request_id",
                  "source_schema_version",
                  "source_target_namespace",
                  "source_request_id",
                  "source_canonical_tenant_id",
                  "source_game_row_id",
                  "source_game_tenant_key",
                  "source_provenance_kind",
                  "association_kind")) {
            String target = "tenant_" + field;
            if (field.equals("source_provenance_kind")) {
              columns.put(target, fresh.get("provenance_kind"));
            } else {
              columns.put(target, fresh.get(field));
            }
          }
          columns.put("game_instance_id", instanceId);
          columns.put("game_instance_uuid", instanceUuid);
          columns.put("canonical_realm_id", realmId);
          columns.put("world_slug", catalog.get("world_slug"));
          columns.put("playable_state_namespace_id", catalog.get("playable_state_namespace_id"));
          columns.put("playable_state_scope", catalog.get("state_scope"));
          columns.put("public_production", true);
          columns.put("captured_starting_row_version", 0L);
          columns.put("game_template_id", GAME_TEMPLATE_ID);
          columns.put("launch_descriptor_id", "human-launch-descriptor");
          columns.put("version_id", 72L);
          columns.put("release_bundle_id", 73L);
          columns.put("generation_config_revision", "generation-1");
          columns.put("version_state_epoch", 3L);
          columns.put("complete_launch_binding_evidence", org.jooq.JSONB.valueOf(bindingJson));
          insertOwnerRow(fixture, "game_session_canonical_instance_launch", columns);
        });
  }

  private static void insertHumanLaunch(
      Fixture fixture, IntakeReceipt receipt, UUID realmId, String requestId) {
    var fresh =
        requiredRecord(
            fixture.dsl,
            "SELECT * FROM game_session_fresh_tenant_association WHERE canonical_tenant_id = ?",
            receipt.source().canonicalTenantId());
    var catalog =
        requiredRecord(
            fixture.dsl,
            "SELECT * FROM game_session_canonical_realm_catalog WHERE realm_id = ?",
            realmId);
    UUID instanceUuid = UUID.randomUUID();
    var tuple =
        CanonicalHumanPublicationOwnerTestFixtures.tuple(
            NAMESPACE,
            receipt.source().canonicalTenantId(),
            uuid(Math.toIntExact(OWNER_ACCOUNT_ID)),
            GAME_TEMPLATE_ID,
            requestId);
    fixture.transactions.executeWithoutResult(
        status -> {
          long instanceId =
              fixture
                  .dsl
                  .fetchSingle(
                      "INSERT INTO game_instances (tenant_id, runtime_version, owner_account_id, status, row_version, "
                          + "game_instance_uuid, game_template_id, launch_descriptor_id, version_id, release_bundle_id, "
                          + "generation_config_revision, version_state_epoch, run_owned_start_request_id, "
                          + "run_owned_start_request_digest, run_owned_start_published_release_bundle_ref) "
                          + "VALUES (?, '72', ?, 'STARTING', 0, ?, ?, 'human-launch-descriptor', 72, 73, 'generation-1', "
                          + "3, ?, ?, 'human-release-bundle') RETURNING id",
                      fresh.get("legacy_game_session_tenant_id"),
                      OWNER_ACCOUNT_ID,
                      instanceUuid,
                      GAME_TEMPLATE_ID,
                      requestId,
                      tuple.mutationDigest())
                  .get("id", Long.class);
          var columns = new java.util.LinkedHashMap<String, Object>();
          columns.put("target_namespace", NAMESPACE);
          columns.put("control_plane_request_id", requestId);
          columns.put("game_session_tenant_id", fresh.get("legacy_game_session_tenant_id"));
          columns.put("canonical_tenant_id", receipt.source().canonicalTenantId());
          for (String field :
              java.util.List.of(
                  "association_operation_id",
                  "association_request_id",
                  "source_schema_version",
                  "source_target_namespace",
                  "source_request_id",
                  "source_canonical_tenant_id",
                  "source_game_row_id",
                  "source_game_tenant_key",
                  "source_provenance_kind",
                  "association_kind")) {
            String target = "tenant_" + field;
            if (field.equals("source_provenance_kind")) {
              columns.put(target, fresh.get("provenance_kind"));
            } else {
              columns.put(target, fresh.get(field));
            }
          }
          columns.put("game_instance_id", instanceId);
          columns.put("game_instance_uuid", instanceUuid);
          columns.put("canonical_realm_id", realmId);
          columns.put("world_slug", catalog.get("world_slug"));
          columns.put("playable_state_namespace_id", catalog.get("playable_state_namespace_id"));
          columns.put("playable_state_scope", catalog.get("state_scope"));
          columns.put("public_production", true);
          columns.put("captured_starting_row_version", 0L);
          columns.put("game_template_id", GAME_TEMPLATE_ID);
          columns.put("launch_descriptor_id", "human-launch-descriptor");
          columns.put("version_id", 72L);
          columns.put("release_bundle_id", 73L);
          columns.put("generation_config_revision", "generation-1");
          columns.put("version_state_epoch", 3L);
          // Guard-level SQL input; the full Human launch binding remains separately
          // owner-validated.
          String bindingJson =
              String.format(
                  "{\"descriptor\":{\"targetNamespace\":\"%s\",\"controlPlaneRequestId\":\"%s\","
                      + "\"canonicalTenantId\":\"%s\",\"worldSlug\":\"%s\",\"gameTemplateId\":%d,"
                      + "\"launchDescriptorId\":\"human-launch-descriptor\",\"versionId\":72,\"releaseBundleId\":73,"
                      + "\"generationConfigRevision\":\"generation-1\",\"versionStateEpoch\":3,"
                      + "\"publishedReleaseBundleRef\":\"human-release-bundle\"},\"releaseAttestation\":{}}",
                  NAMESPACE,
                  requestId,
                  receipt.source().canonicalTenantId(),
                  catalog.get("world_slug"),
                  GAME_TEMPLATE_ID);
          columns.put("complete_launch_binding_evidence", org.jooq.JSONB.valueOf(bindingJson));
          columns.put("original_start_session_tuple", tuple.canonicalBytes());
          columns.put("catalog_creation_request_id", catalog.get("creation_request_id"));
          columns.put("catalog_revision", catalog.get("catalog_revision"));
          columns.put("catalog_source_intake_operation_id", receipt.operationId());
          columns.put("catalog_source_intake_request_id", receipt.intakeRequestId());
          insertOwnerRow(fixture, "game_session_canonical_instance_launch", columns);
        });
  }

  private static void insertOwnerRow(
      Fixture fixture, String table, java.util.Map<String, Object> columns) {
    fixture
        .dsl
        .insertInto(DSL.table(DSL.name(table)))
        .columns(columns.keySet().stream().map(field -> DSL.field(DSL.name(field))).toList())
        .values(columns.values())
        .execute();
  }

  private static org.flywaydb.core.api.configuration.FluentConfiguration migrate(
      Fixture fixture, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(fixture.dataSource)
            .schemas(fixture.schema)
            .defaultSchema(fixture.schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION);
    if (target != null) configuration.target(target);
    return configuration;
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
    return fixture(null);
  }

  private static Fixture fixture(String targetVersion) {
    return fixture(targetVersion, false);
  }

  private static Fixture fixtureWithRepeatableReadDefault() {
    return fixture(null, true);
  }

  private static Fixture fixture(String targetVersion, boolean repeatableReadDefault) {
    String schema = "gs_run_owned_launch_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource =
        repeatableReadDefault
            ? new DriverManagerDataSource() {
              @Override
              public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                return connection;
              }
            }
            : new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    var migration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION);
    if (targetVersion != null) migration.target(targetVersion);
    migration.load().migrate();

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
        launchRepository,
        dataSource,
        schema);
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transactions,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionFreshTenantAssociationRepository tenantAssociationRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionCanonicalInstanceLaunchRepository launchRepository,
      DriverManagerDataSource dataSource,
      String schema) {}

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
