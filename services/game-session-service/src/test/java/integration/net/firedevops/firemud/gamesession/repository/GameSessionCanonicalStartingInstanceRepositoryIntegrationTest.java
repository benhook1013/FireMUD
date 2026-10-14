package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository.InvalidCanonicalStartingInstanceEvidenceException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository.StartingInstance;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
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
 * PostgreSQL proof for atomic creation of the V21 UUID-owned STARTING row and V26 launch
 * association. Inputs are typed source/release fixtures; this is not Account redemption, World
 * activation, runtime activation, or an end-to-end launch producer proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionCanonicalStartingInstanceRepositoryIntegrationTest {
  private static final String NAMESPACE = "canonical-starting-it";
  private static final UUID TENANT = uuid(201);
  private static final UUID REGISTRATION_REQUEST_ID = uuid(202);
  private static final UUID SOURCE_OPERATION_ID = uuid(203);
  private static final UUID INTAKE_REQUEST_ID = uuid(204);
  private static final UUID TENANT_ASSOCIATION_OPERATION_ID = uuid(205);
  private static final UUID CATALOG_REQUEST_ID = uuid(206);
  private static final UUID CANONICAL_VERSION_ID = uuid(207);
  private static final UUID OWNER_ACCOUNT_UUID = uuid(208);
  private static final String CONTROL_PLANE_REQUEST_ID = "canonical-starting-control-request-1";
  private static final StartSessionPostAuthorizationExecutionTuple ORIGINAL_TUPLE =
      CanonicalHumanPublicationOwnerTestFixtures.tuple(
          NAMESPACE, TENANT, OWNER_ACCOUNT_UUID, 301L, CONTROL_PLANE_REQUEST_ID);
  private static final String REQUEST_DIGEST = ORIGINAL_TUPLE.mutationDigest();
  private static final String OTHER_REQUEST_DIGEST = "b".repeat(64);
  private static final String RUNTIME_FLAGS_JSON = "{}";
  private static final String CHANGED_LAUNCH_DESCRIPTOR_ID = "descriptor-for-starting-changed";
  private static final long GAME_TEMPLATE_ID = 301L;
  private static final long VERSION_ID = 302L;
  private static final long RELEASE_BUNDLE_ID = 303L;
  private static final long VERSION_STATE_EPOCH = 7L;
  private static final long SOURCE_GAME_ROW_ID = 919L;
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void createsUuidOwnedStartingRowAndAssociationAndExactRetryReturnsOriginalIdentity() {
    Fixture fixture = fixture(true);
    CompleteLaunchBindingEvidence binding =
        launchBinding(fixture.source().source(), fixture.publishedSet(), CONTROL_PLANE_REQUEST_ID);

    StartingInstance first =
        fixture.start(OWNER_ACCOUNT_UUID, CONTROL_PLANE_REQUEST_ID, REQUEST_DIGEST, binding);
    StartingInstance retry =
        fixture.start(OWNER_ACCOUNT_UUID, CONTROL_PLANE_REQUEST_ID, REQUEST_DIGEST, binding);

    assertThat(retry).isEqualTo(first);
    assertThat(first.gameInstanceId()).isPositive();
    assertThat(first.gameInstanceUuid()).isNotEqualTo(new UUID(0L, 0L));
    assertThat(fixture.tenantAssociation().sourceEvidence().sourceGameRowId())
        .isEqualTo(fixture.source().source().sourceGameRowId());
    assertThat(fixture.tenantAssociation().sourceEvidence().sourceGameTenantKey())
        .isEqualTo(fixture.source().source().sourceGameTenantKey());
    assertThat(first.launchAssociation().gameInstanceUuid()).isEqualTo(first.gameInstanceUuid());
    assertThat(first.launchAssociation().currentGameInstanceStatus().name()).isEqualTo("STARTING");
    assertThat(first.launchAssociation().capturedStartingRowVersion()).isZero();

    Record runtime =
        requiredRecord(
            fixture.dsl(),
            "SELECT id, tenant_id, game_instance_uuid, owner_account_id, owner_account_uuid, "
                + "runtime_version, status, row_version, game_template_id, launch_descriptor_id, "
                + "version_id, release_bundle_id, version_state_epoch, generation_config_revision, "
                + "script_patch_version, script_patch_base_version_id, script_pin_epoch, "
                + "script_patch_pinned_control_plane_request_id, run_owned_start_request_id, "
                + "run_owned_start_request_digest, run_owned_start_published_release_bundle_ref "
                + "FROM game_instances WHERE tenant_id = ? AND id = ?",
            fixture.tenantAssociation().legacyGameSessionTenantId(),
            first.gameInstanceId());
    assertThat(runtime.get("game_instance_uuid", UUID.class)).isEqualTo(first.gameInstanceUuid());
    assertThat(runtime.get("owner_account_id", Long.class)).isNull();
    assertThat(runtime.get("owner_account_uuid", UUID.class)).isEqualTo(OWNER_ACCOUNT_UUID);
    assertThat(runtime.get("runtime_version", String.class)).isEqualTo(Long.toString(VERSION_ID));
    assertThat(runtime.get("status", String.class)).isEqualTo("STARTING");
    assertThat(runtime.get("row_version", Long.class)).isZero();
    assertThat(runtime.get("game_template_id", Long.class)).isEqualTo(GAME_TEMPLATE_ID);
    assertThat(runtime.get("version_id", Long.class)).isEqualTo(VERSION_ID);
    assertThat(runtime.get("release_bundle_id", Long.class)).isEqualTo(RELEASE_BUNDLE_ID);
    assertThat(runtime.get("version_state_epoch", Long.class)).isEqualTo(VERSION_STATE_EPOCH);
    assertThat(runtime.get("run_owned_start_request_id", String.class))
        .isEqualTo(CONTROL_PLANE_REQUEST_ID);
    assertThat(runtime.get("run_owned_start_request_digest", String.class))
        .isEqualTo(REQUEST_DIGEST);
    assertThat(runtime.get("script_patch_version", String.class)).isNull();
    assertThat(runtime.get("script_patch_base_version_id", Long.class)).isNull();
    assertThat(runtime.get("script_pin_epoch", Long.class)).isNull();
    assertThat(runtime.get("script_patch_pinned_control_plane_request_id", String.class)).isNull();

    Record association =
        requiredRecord(
            fixture.dsl(),
            "SELECT complete_launch_binding_evidence -> 'descriptor' ->> 'runtimeFlagsJson' "
                + "AS runtime_flags, complete_launch_binding_evidence -> 'descriptor' "
                + "->> 'scriptPatchVersion' AS candidate_script_patch, game_instance_uuid, "
                + "original_start_session_tuple, catalog_creation_request_id, catalog_revision "
                + "FROM game_session_canonical_instance_launch "
                + "WHERE target_namespace = ? AND control_plane_request_id = ?",
            NAMESPACE,
            CONTROL_PLANE_REQUEST_ID);
    assertThat(association.get("runtime_flags", String.class)).isEqualTo(RUNTIME_FLAGS_JSON);
    assertThat(association.get("candidate_script_patch", String.class)).isNull();
    assertThat(association.get("game_instance_uuid", UUID.class))
        .isEqualTo(first.gameInstanceUuid());
    assertThat(association.get("original_start_session_tuple", byte[].class))
        .containsExactly(fixture.originalTuple().canonicalBytes());
    assertThat(association.get("catalog_creation_request_id", UUID.class))
        .isEqualTo(CATALOG_REQUEST_ID);
    assertThat(association.get("catalog_revision", Long.class))
        .isEqualTo(
            fixture
                .catalogRepository()
                .readByRequest(NAMESPACE, CATALOG_REQUEST_ID)
                .orElseThrow()
                .catalogRevision());
    assertThat(fixture.dsl().fetchCount(DSL.table(DSL.name("game_instances")))).isEqualTo(1);
    assertThat(
            fixture.dsl().fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
        .isEqualTo(1);
    assertThat(fixture.dsl().fetchCount(DSL.table(DSL.name("gameplay_admission_pointer"))))
        .isZero();
  }

  @Test
  void sameRequestContendersReturnOneOriginalRowAndAssociation() throws Exception {
    Fixture fixture = fixture(true);
    CompleteLaunchBindingEvidence binding =
        launchBinding(fixture.source().source(), fixture.publishedSet(), CONTROL_PLANE_REQUEST_ID);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<StartingInstance> first =
          executor.submit(() -> contender(fixture, binding, ready, start));
      Future<StartingInstance> second =
          executor.submit(() -> contender(fixture, binding, ready, start));
      assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      StartingInstance firstResult = first.get(20, TimeUnit.SECONDS);
      StartingInstance secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat(firstResult).isEqualTo(secondResult);
      assertThat(fixture.dsl().fetchCount(DSL.table(DSL.name("game_instances")))).isEqualTo(1);
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void changedOwnerDigestOrBindingConflictsWithoutCreatingAnotherRuntimeRow() {
    Fixture fixture = fixture(true);
    CompleteLaunchBindingEvidence binding =
        launchBinding(fixture.source().source(), fixture.publishedSet(), CONTROL_PLANE_REQUEST_ID);
    StartingInstance original =
        fixture.start(OWNER_ACCOUNT_UUID, CONTROL_PLANE_REQUEST_ID, REQUEST_DIGEST, binding);

    assertThatThrownBy(
            () -> fixture.start(uuid(209), CONTROL_PLANE_REQUEST_ID, REQUEST_DIGEST, binding))
        .isInstanceOf(InvalidCanonicalStartingInstanceEvidenceException.class)
        .hasMessageContaining("owner");
    assertThatThrownBy(
            () ->
                fixture.start(
                    OWNER_ACCOUNT_UUID, CONTROL_PLANE_REQUEST_ID, OTHER_REQUEST_DIGEST, binding))
        .isInstanceOf(InvalidCanonicalStartingInstanceEvidenceException.class)
        .hasMessageContaining("launch request");

    CompleteLaunchBindingEvidence changedBinding =
        launchBinding(
            fixture.source().source(),
            fixture.publishedSet(),
            CONTROL_PLANE_REQUEST_ID,
            CHANGED_LAUNCH_DESCRIPTOR_ID,
            fixture.source().source().operationId());
    assertThatThrownBy(
            () ->
                fixture.start(
                    OWNER_ACCOUNT_UUID, CONTROL_PLANE_REQUEST_ID, REQUEST_DIGEST, changedBinding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Canonical descriptor pin differs from complete launch binding");

    assertThat(
            requiredRecord(
                    fixture.dsl(),
                    "SELECT id, game_instance_uuid FROM game_instances "
                        + "WHERE tenant_id = ? AND id = ?",
                    fixture.tenantAssociation().legacyGameSessionTenantId(),
                    original.gameInstanceId())
                .get("game_instance_uuid", UUID.class))
        .isEqualTo(original.gameInstanceUuid());
    assertThat(fixture.dsl().fetchCount(DSL.table(DSL.name("game_instances")))).isEqualTo(1);
    assertThat(
            fixture.dsl().fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
        .isEqualTo(1);
  }

  @Test
  void invalidPersistedSourceAndMissingRealmLeaveNoPartialStartingRowOrAssociation() {
    Fixture fixture = fixture(true);
    CompleteLaunchBindingEvidence mismatchedSource =
        launchBinding(
            fixture.source().source(),
            fixture.publishedSet(),
            CONTROL_PLANE_REQUEST_ID,
            "descriptor-for-starting",
            uuid(299));
    assertThatThrownBy(
            () ->
                fixture.start(
                    OWNER_ACCOUNT_UUID, CONTROL_PLANE_REQUEST_ID, REQUEST_DIGEST, mismatchedSource))
        .isInstanceOf(InvalidCanonicalStartingInstanceEvidenceException.class)
        .hasMessageContaining("persisted authored-world source receipt");
    assertNoStartingRows(fixture);

    Fixture missingRealm = fixture(false);
    CompleteLaunchBindingEvidence validBinding =
        launchBinding(
            missingRealm.source().source(), missingRealm.publishedSet(), CONTROL_PLANE_REQUEST_ID);
    assertThatThrownBy(
            () ->
                missingRealm.start(
                    OWNER_ACCOUNT_UUID, CONTROL_PLANE_REQUEST_ID, REQUEST_DIGEST, validBinding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("visible public SHARED realm");
    assertNoStartingRows(missingRealm);
  }

  private static StartingInstance contender(
      Fixture fixture,
      CompleteLaunchBindingEvidence binding,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent STARTING contender gate was not released");
    }
    return fixture.start(OWNER_ACCOUNT_UUID, CONTROL_PLANE_REQUEST_ID, REQUEST_DIGEST, binding);
  }

  private static void assertNoStartingRows(Fixture fixture) {
    assertThat(fixture.dsl().fetchCount(DSL.table(DSL.name("game_instances")))).isZero();
    assertThat(
            fixture.dsl().fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
        .isZero();
  }

  private static CompleteLaunchBindingEvidence launchBinding(
      AuthoredWorldSourceEvidence source,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet,
      String requestId) {
    return launchBinding(
        source, publishedSet, requestId, "descriptor-for-starting", source.operationId());
  }

  private static CompleteLaunchBindingEvidence launchBinding(
      AuthoredWorldSourceEvidence source,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet,
      String requestId,
      String launchDescriptorId,
      UUID authoredWorldSourceOperationId) {
    AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            requestId,
            source.canonicalTenantId(),
            source.worldSlug(),
            authoredWorldSourceOperationId,
            source.evidenceDigest(),
            GAME_TEMPLATE_ID,
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            RUNTIME_FLAGS_JSON);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            launchDescriptorId,
            VERSION_ID,
            false,
            null,
            RUNTIME_FLAGS_JSON,
            "generation-config-8",
            VERSION_STATE_EPOCH,
            RELEASE_BUNDLE_ID,
            "published-release-bundle-303",
            false,
            null);
    return CanonicalHumanPublicationOwnerTestFixtures.completeBinding(descriptor, publishedSet);
  }

  private static AuthoredWorldSourceEvidence freshSource() {
    String tenantSlug = "fresh-tenant-" + TENANT.toString().substring(0, 8);
    String worldSlug = "amber-reach";
    String displayName = "Amber Reach";
    String sourceKey = "gd-source-game-919";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, REGISTRATION_REQUEST_ID, TENANT, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_REQUEST_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            TENANT,
            tenantSlug,
            worldSlug,
            displayName,
            SOURCE_GAME_ROW_ID,
            sourceKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION_REQUEST_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        TENANT,
        tenantSlug,
        worldSlug,
        displayName,
        SOURCE_GAME_ROW_ID,
        sourceKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static Record requiredRecord(DSLContext dsl, String sql, Object... bindings) {
    return Objects.requireNonNull(dsl.fetchOne(sql, bindings), "Expected owner row is missing");
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transactions,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionFreshTenantAssociationRepository tenantAssociationRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionCanonicalStartingInstanceRepository startingRepository,
      IntakeReceipt source,
      FreshGameSessionTenantAssociation tenantAssociation,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    StartingInstance start(
        UUID ownerAccountUuid,
        String controlPlaneRequestId,
        String requestDigest,
        CompleteLaunchBindingEvidence binding) {
      return Objects.requireNonNull(
          transactions.execute(
              status ->
                  startingRepository.createStarting(
                      tenantAssociation,
                      binding,
                      ownerAccountUuid,
                      controlPlaneRequestId,
                      requestDigest,
                      originalTuple)),
          "canonical STARTING owner result");
    }
  }

  private static Fixture fixture(boolean createRealm) {
    String schema = "gs_starting_owner_" + UUID.randomUUID().toString().replace("-", "");
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
        new GameSessionCanonicalRealmCatalogRepository(dsl);
    GameSessionCanonicalStartingInstanceRepository startingRepository =
        new GameSessionCanonicalStartingInstanceRepository(dsl, NAMESPACE);

    IntakeReceipt source =
        Objects.requireNonNull(
            transactions.execute(
                status -> sourceRepository.register(INTAKE_REQUEST_ID, freshSource())),
            "registered authored-world source receipt");
    FreshGameSessionTenantAssociation tenantAssociation =
        Objects.requireNonNull(
            transactions.execute(
                status ->
                    tenantAssociationRepository.associateFresh(
                        TENANT_ASSOCIATION_OPERATION_ID,
                        NAMESPACE,
                        source,
                        CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(
                            source.source()))),
            "fresh complete source-bound Game Session tenant association");
    var request =
        new CreateCanonicalRealmCatalogRequest(
            CATALOG_REQUEST_ID,
            NAMESPACE,
            TENANT,
            source.operationId(),
            "amber-realm",
            "Amber Realm",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    var publishedSet =
        CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            request,
            source.source(),
            CANONICAL_VERSION_ID,
            VERSION_ID,
            "publication-for-starting-303",
            "published-release-bundle-303",
            "generation-config-8",
            VERSION_STATE_EPOCH);
    if (createRealm) {
      var prepared = catalogRepository.prepareInitialPublicProduction(request, publishedSet);
      transactions.execute(status -> catalogRepository.createInitialPublicProduction(prepared));
    }
    var binding = launchBinding(source.source(), publishedSet, CONTROL_PLANE_REQUEST_ID);
    CanonicalHumanPublicationOwnerTestFixtures.pinLaunch(
        dsl, transactions, ORIGINAL_TUPLE, source.source(), publishedSet, binding);
    return new Fixture(
        dsl,
        transactions,
        sourceRepository,
        tenantAssociationRepository,
        catalogRepository,
        startingRepository,
        source,
        tenantAssociation,
        ORIGINAL_TUPLE,
        publishedSet);
  }
}
