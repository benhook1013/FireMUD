package integration.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import integration.net.firedevops.firemud.gamesession.repository.CanonicalHumanPublicationOwnerTestFixtures;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalLaunchPreparationSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationResult;
import net.firedevops.firemud.gamesession.service.GameSessionCanonicalLaunchPreparationService;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL source/catalog, retained owner-attempt, and immutable preparation storage. The
 * canonical Account tuple and Game Design response are test fixtures, not genuine external
 * authority or transport/mTLS proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionCanonicalLaunchPreparationServiceIntegrationTest {
  private static final String NAMESPACE = "canonical-launch-it";
  private static final UUID TENANT = uuid(101);
  private static final UUID SOURCE_OPERATION = uuid(102);
  private static final UUID INTAKE_REQUEST = uuid(104);
  private static final UUID CATALOG_REQUEST = uuid(105);
  private static final UUID ACTOR = uuid(106);
  private static final UUID REALM = uuid(107);
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final long RETAINED_TENANT_ID = 917L;
  private static final long RETAINED_GAME_INSTANCE_ID = 901L;
  private static final UUID RETAINED_REALM_ID = uuid(108);
  private static final UUID RETAINED_PLAYABLE_NAMESPACE_ID = uuid(109);
  private static final UUID RETAINED_SHARED_NAMESPACE_ID = uuid(110);
  private static final UUID AUTHORIZATION_ISSUANCE_ID = uuid(112);
  private static final UUID AUTHORIZATION_JTI = uuid(113);
  private static final UUID RESERVATION_OWNER = uuid(114);
  private static final String AUTHORIZATION_FINGERPRINT = "arfp/v1/launch-it/" + "b".repeat(64);
  private static final String LOGGING_WORKLOAD =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void awaitPostgresJdbcReady() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  void
      commitsExactFreshSourceCatalogAndSyntheticDescriptorThenReplaysWithoutChangingRetainedRows() {
    Fixture fixture = fixture(true, true);
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
        .isZero();
    fixture.requireHistoricalRowsRetained();
    IntakeReceipt source = fixture.registerFreshSource();
    CanonicalRealmCatalogSnapshot catalog = fixture.createCatalog(source);
    assertThat(
            fixture.sourceRepository.read(
                source.operationId(), TENANT, source.source().worldSlug(), NAMESPACE))
        .contains(source);
    assertThat(fixture.catalogRepository.readByRequest(NAMESPACE, CATALOG_REQUEST))
        .contains(catalog);
    CreateCanonicalLaunchPreparationRequest request = request(source, catalog);
    AuthoredWorldLaunchDescriptorClient syntheticGameDesign = syntheticGameDesignClient();
    GameSessionCanonicalLaunchPreparationService service = fixture.service(syntheticGameDesign);

    CanonicalLaunchPreparationSnapshot first = service.prepare(request);
    // A repeated call models recovery after the caller loses the first successful response.
    CanonicalLaunchPreparationSnapshot retry = service.prepare(request);

    assertThat(first).isEqualTo(retry);
    assertThat(first.operationId()).isNotEqualTo(NIL_UUID);
    assertThat(first.catalogSnapshot()).isEqualTo(catalog);
    assertThat(first.sourceIntakeReceipt()).isEqualTo(source);
    first.launchDescriptorEvidence().requireValid();
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isEqualTo(1);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT game_instance_uuid FROM game_instances WHERE id = ?",
                    RETAINED_GAME_INSTANCE_ID)
                .get("game_instance_uuid", UUID.class))
        .isNull();
    assertThat(
            requiredRecord(
                fixture.dsl,
                "SELECT representation_version, tenant_id, game_instance_id, realm_id, "
                    + "canonical_tenant_id, playable_state_namespace_id FROM gameplay_admission_pointer "
                    + "WHERE tenant_id = ?",
                RETAINED_TENANT_ID))
        .satisfies(
            row -> {
              assertThat(row.get("representation_version", Integer.class)).isEqualTo(1);
              assertThat(row.get("tenant_id", Long.class)).isEqualTo(RETAINED_TENANT_ID);
              assertThat(row.get("game_instance_id", Long.class)).isEqualTo(501L);
              assertThat(row.get("realm_id", UUID.class)).isEqualTo(RETAINED_REALM_ID);
              assertThat(row.get("canonical_tenant_id", UUID.class)).isNull();
              assertThat(row.get("playable_state_namespace_id", UUID.class))
                  .isEqualTo(RETAINED_PLAYABLE_NAMESPACE_ID);
            });
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT playable_state_namespace_id FROM "
                        + "gameplay_tenant_shared_playable_state_namespace WHERE tenant_id = ?",
                    RETAINED_TENANT_ID)
                .get("playable_state_namespace_id", UUID.class))
        .isEqualTo(RETAINED_SHARED_NAMESPACE_ID);
    fixture.requireHistoricalRowsRetained();
    verify(syntheticGameDesign, times(1)).resolve(any());
    verify(syntheticGameDesign, times(1)).get(any(GetRequest.class));
  }

  @Test
  void ownerRollbackLeavesNoPartialPreparationAndImmutableRowsRejectMutation() {
    Fixture fixture = fixture(false, false);
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
        .isZero();
    IntakeReceipt source = fixture.registerFreshSource();
    CanonicalRealmCatalogSnapshot catalog = fixture.createCatalog(source);
    CreateCanonicalLaunchPreparationRequest request = request(source, catalog);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        syntheticDescriptor(request, catalog, source);

    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status -> {
                      fixture.preparationRepository.persistPrepared(
                          request, catalog, source, descriptor);
                      throw new IllegalStateException("forced owner rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("forced owner rollback");
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
        .isZero();

    CanonicalLaunchPreparationSnapshot committed =
        fixture.service(syntheticGameDesignClient()).prepare(request);
    assertThat(
            fixture.preparationRepository.readByControlPlaneRequestId(
                NAMESPACE, request.controlPlaneRequestId()))
        .contains(committed);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_session_canonical_launch_preparation "
                        + "SET receipt_digest = ? WHERE target_namespace = ?",
                    digest("f"),
                    NAMESPACE))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("Canonical launch preparation evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "DELETE FROM game_session_canonical_launch_preparation "
                        + "WHERE target_namespace = ?",
                    NAMESPACE))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("Canonical launch preparation evidence is immutable");
    assertThatThrownBy(
            () -> fixture.dsl.execute("TRUNCATE game_session_canonical_launch_preparation"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("Canonical launch preparation evidence is immutable");
    assertThat(
            fixture.preparationRepository.readByControlPlaneRequestId(
                NAMESPACE, request.controlPlaneRequestId()))
        .contains(committed);

    // Simulate privileged storage corruption in this isolated test schema; normal update/delete
    // paths remain blocked by the immutable trigger above.
    fixture.transactions.execute(
        status -> {
          fixture.dsl.execute(
              "ALTER TABLE game_session_canonical_launch_preparation DISABLE TRIGGER "
                  + "game_session_canonical_launch_preparation_immutable");
          fixture.dsl.execute(
              "UPDATE game_session_canonical_launch_preparation "
                  + "SET catalog_evidence_json = jsonb_set(catalog_evidence_json, "
                  + "'{realmDisplayName}', to_jsonb(CAST(? AS text))) "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              "Tampered Realm Display Name",
              NAMESPACE,
              request.controlPlaneRequestId());
          fixture.dsl.execute(
              "ALTER TABLE game_session_canonical_launch_preparation ENABLE TRIGGER "
                  + "game_session_canonical_launch_preparation_immutable");
          return null;
        });
    assertThatThrownBy(
            () ->
                fixture.preparationRepository.readByControlPlaneRequestId(
                    NAMESPACE, request.controlPlaneRequestId()))
        .isInstanceOf(
            GameSessionCanonicalLaunchPreparationRepository
                .InvalidLaunchPreparationEvidenceException.class)
        .hasMessageContaining("stored identity and digests");
  }

  @Test
  void concurrentExactPreparationsReturnOneImmutableOperation() throws Exception {
    Fixture fixture = fixture(false, false);
    IntakeReceipt source = fixture.registerFreshSource();
    CanonicalRealmCatalogSnapshot catalog = fixture.createCatalog(source);
    CreateCanonicalLaunchPreparationRequest request = request(source, catalog);
    CountDownLatch bothResolved = new CountDownLatch(2);
    AuthoredWorldLaunchDescriptorClient concurrentGameDesign =
        concurrentSyntheticGameDesignClient(bothResolved);
    GameSessionCanonicalLaunchPreparationService service = fixture.service(concurrentGameDesign);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CanonicalLaunchPreparationSnapshot> first =
          executor.submit(() -> service.prepare(request));
      Future<CanonicalLaunchPreparationSnapshot> second =
          executor.submit(() -> service.prepare(request));

      assertThat(bothResolved.await(15, TimeUnit.SECONDS)).isTrue();
      CanonicalLaunchPreparationSnapshot firstResult = first.get(15, TimeUnit.SECONDS);
      CanonicalLaunchPreparationSnapshot secondResult = second.get(15, TimeUnit.SECONDS);

      assertThat(firstResult).isEqualTo(secondResult);
      assertThat(firstResult.operationId()).isEqualTo(secondResult.operationId());
      assertThat(
              fixture.dsl.fetchCount(
                  DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
          .isEqualTo(1);
      verify(concurrentGameDesign, times(2)).resolve(any());
      verify(concurrentGameDesign, times(2)).get(any(GetRequest.class));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void authorizedLaunchPreparationBindsTheRetainedAccountOwnerTupleAndRejectsSubstitutions() {
    for (String substitution : java.util.List.of("sourceFence", "linearization")) {
      AuthorizedFixture authorized = authorizedFixture();
      StartSessionPostAuthorizationExecutionTuple substituted =
          tupleWithReference(
              authorized.ownerTuple(),
              substitution.equals("sourceFence") ? "31" : "29",
              substitution.equals("linearization")
                  ? "18446744073709551614"
                  : "18446744073709551615");
      replaceAttachedProjectionForFixture(
          authorized.fixture(), authorized.ownerTuple(), substituted);
      AuthoredWorldLaunchDescriptorClient descriptorClient = syntheticGameDesignClient();

      assertThatThrownBy(
              () ->
                  authorized
                      .fixture()
                      .service(descriptorClient)
                      .prepareAuthorized(authorized.claim(), authorized.request()))
          .isInstanceOf(
              GameSessionCanonicalLaunchPreparationRepository
                  .InvalidLaunchPreparationEvidenceException.class)
          .hasMessageContaining("projection binding is invalid");
      assertThat(
              authorized
                  .fixture()
                  .dsl()
                  .fetchCount(DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
          .isZero();
      verify(descriptorClient, times(0)).resolve(any());
      verify(descriptorClient, times(0)).get(any(GetRequest.class));
    }

    AuthorizedFixture authorized = authorizedFixture();
    AuthoredWorldLaunchDescriptorClient descriptorClient = syntheticGameDesignClient();
    CanonicalLaunchPreparationSnapshot prepared =
        authorized
            .fixture()
            .service(descriptorClient)
            .prepareAuthorized(authorized.claim(), authorized.request());

    assertThat(prepared.request()).isEqualTo(authorized.request());
    assertThat(
            authorized
                .fixture()
                .preparationRepository()
                .readByControlPlaneRequestId(
                    NAMESPACE, authorized.request().controlPlaneRequestId()))
        .contains(prepared);
    assertThat(
            authorized
                .fixture()
                .dsl()
                .fetchCount(DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
        .isEqualTo(1);
    verify(descriptorClient, times(1)).resolve(any());
    verify(descriptorClient, times(1)).get(any(GetRequest.class));
  }

  private static AuthoredWorldLaunchDescriptorClient syntheticGameDesignClient() {
    AuthoredWorldLaunchDescriptorClient client = mock(AuthoredWorldLaunchDescriptorClient.class);
    when(client.resolve(any(AuthoredWorldLaunchDescriptorEvidence.Request.class)))
        .thenAnswer(
            invocation -> {
              AuthoredWorldLaunchDescriptorEvidence.Request request = invocation.getArgument(0);
              return syntheticDescriptor(request);
            });
    when(client.get(any(GetRequest.class)))
        .thenAnswer(
            invocation -> {
              GetRequest request = invocation.getArgument(0);
              AuthoredWorldLaunchDescriptorEvidence descriptor =
                  syntheticDescriptor(request.expectedRequest());
              assertThat(request.expectedResultDigest()).isEqualTo(descriptor.resultDigest());
              return descriptor;
            });
    return client;
  }

  private static AuthoredWorldLaunchDescriptorClient concurrentSyntheticGameDesignClient(
      CountDownLatch bothResolved) {
    AuthoredWorldLaunchDescriptorClient client = mock(AuthoredWorldLaunchDescriptorClient.class);
    when(client.resolve(any(AuthoredWorldLaunchDescriptorEvidence.Request.class)))
        .thenAnswer(
            invocation -> {
              AuthoredWorldLaunchDescriptorEvidence.Request request = invocation.getArgument(0);
              bothResolved.countDown();
              try {
                if (!bothResolved.await(15, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Concurrent exact attempts did not both resolve");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "Concurrent exact attempt was interrupted", exception);
              }
              return syntheticDescriptor(request);
            });
    when(client.get(any(GetRequest.class)))
        .thenAnswer(
            invocation -> {
              GetRequest request = invocation.getArgument(0);
              AuthoredWorldLaunchDescriptorEvidence descriptor =
                  syntheticDescriptor(request.expectedRequest());
              assertThat(request.expectedResultDigest()).isEqualTo(descriptor.resultDigest());
              return descriptor;
            });
    return client;
  }

  private static AuthoredWorldLaunchDescriptorEvidence syntheticDescriptor(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source) {
    return syntheticDescriptor(request.descriptorRequest(catalog, source.source()));
  }

  private static AuthoredWorldLaunchDescriptorEvidence syntheticDescriptor(
      AuthoredWorldLaunchDescriptorEvidence.Request request) {
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "synthetic-descriptor-" + request.controlPlaneRequestId(),
        515,
        false,
        null,
        "{}",
        "generation-config-1",
        4,
        616,
        "synthetic-published-release-616",
        false,
        null);
  }

  private static CreateCanonicalLaunchPreparationRequest request(
      IntakeReceipt source, CanonicalRealmCatalogSnapshot catalog) {
    return new CreateCanonicalLaunchPreparationRequest(
        "canonical-launch-it-" + catalog.realmId(),
        ACTOR,
        NAMESPACE,
        TENANT,
        catalog.realmId(),
        catalog.creationRequestId(),
        catalog.catalogRevision(),
        source.operationId(),
        404,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  private AuthorizedFixture authorizedFixture() {
    Fixture fixture = fixture(false, false);
    IntakeReceipt source = fixture.registerFreshSource();
    CanonicalRealmCatalogSnapshot catalog = fixture.createCatalog(source);
    CreateCanonicalLaunchPreparationRequest request = request(source, catalog);
    StartSessionPostAuthorizationExecutionTuple ownerTuple = ownerTuple(request);
    AttemptClaim claim = fixture.reserveAndAttach(ownerTuple);
    return new AuthorizedFixture(fixture, request, ownerTuple, claim);
  }

  private static StartSessionPostAuthorizationExecutionTuple ownerTuple(
      CreateCanonicalLaunchPreparationRequest request) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(request.canonicalTenantId(), NAMESPACE),
            new StartSessionOperatorAction.Target(
                request.gameTemplateId(), request.actingAccountUuid()),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "physical authorized launch preparation proof");
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            request.controlPlaneRequestId(), request.actingAccountUuid(), action);
    byte[] authorityBundle = authorityBundle(preTuple);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        LOGGING_WORKLOAD,
        AUTHORIZATION_FINGERPRINT,
        RESERVATION_OWNER,
        37L,
        authorityBundle,
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "29",
            "18446744073709551615"));
  }

  private static StartSessionPostAuthorizationExecutionTuple tupleWithReference(
      StartSessionPostAuthorizationExecutionTuple tuple, String sourceFence, String linearization) {
    var reference = tuple.bundleReference();
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        tuple.preAuthorizationTuple(),
        tuple.authenticatedWorkloadIdentity(),
        tuple.authorizationReferenceFingerprint(),
        tuple.reservationOwnerId(),
        tuple.reservationClaimFence(),
        tuple.authorityEvidenceBundleBytes(),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            reference.bundleVersion(), reference.sourceVersion(), sourceFence, linearization));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    return StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
        tuple,
        new Values(
            AUTHORIZATION_ISSUANCE_ID,
            "sha256:" + "a".repeat(64),
            "17",
            "CURRENT",
            Instant.parse("1999-01-01T00:00:00Z"),
            Instant.parse("2099-01-01T00:05:00Z"),
            1L,
            2L,
            3L,
            4L,
            5L,
            23L,
            AUTHORIZATION_JTI,
            "2",
            "3"));
  }

  private static void replaceAttachedProjectionForFixture(
      Fixture fixture,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      StartSessionPostAuthorizationExecutionTuple substitutedTuple) {
    fixture
        .dsl()
        .execute(
            "ALTER TABLE game_session_start_session_operator_attempt DISABLE TRIGGER "
                + "game_session_start_session_operator_attempt_immutable");
    try {
      fixture
          .dsl()
          .execute(
              "UPDATE game_session_start_session_operator_attempt "
                  + "SET account_redemption_projection = ? "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              substitutedTuple.canonicalBytes(),
              NAMESPACE,
              originalTuple.controlPlaneRequestId());
    } finally {
      fixture
          .dsl()
          .execute(
              "ALTER TABLE game_session_start_session_operator_attempt ENABLE TRIGGER "
                  + "game_session_start_session_operator_attempt_immutable");
    }
  }

  private record AuthorizedFixture(
      Fixture fixture,
      CreateCanonicalLaunchPreparationRequest request,
      StartSessionPostAuthorizationExecutionTuple ownerTuple,
      AttemptClaim claim) {}

  private Fixture fixture(
      boolean seedRetainedNumericRows, boolean seedCanonicalOwnerEvidenceBeforeV24) {
    String schema = "gs_canonical_launch_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
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
        .target(MigrationVersion.fromVersion("11"))
        .load()
        .migrate();

    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    if (seedRetainedNumericRows) {
      dsl.execute(
          "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id, status) "
              + "VALUES (?, ?, ?, ?, ?)",
          RETAINED_GAME_INSTANCE_ID,
          RETAINED_TENANT_ID,
          "retained-runtime",
          77L,
          "RUNNING");
      dsl.execute(
          "INSERT INTO gameplay_tenant_shared_playable_state_namespace "
              + "(tenant_id, playable_state_namespace_id) VALUES (?, ?)",
          RETAINED_TENANT_ID,
          RETAINED_SHARED_NAMESPACE_ID);
      dsl.execute(
          "INSERT INTO gameplay_admission_pointer "
              + "(world_slug, world_display_name, realm_slug, realm_display_name, tenant_id, "
              + "game_instance_id, pointer_version, visible, requires_character_selection, "
              + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
              + "public_production_realm, realm_id, playable_state_namespace_id) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          "legacy-world",
          "Legacy World",
          "legacy-realm",
          "Legacy Realm",
          RETAINED_TENANT_ID,
          501L,
          1L,
          true,
          false,
          "SHARED",
          "ALLOW_NEW",
          "fixture",
          "retained evidence",
          true,
          RETAINED_REALM_ID,
          RETAINED_PLAYABLE_NAMESPACE_ID);
    }

    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("23"))
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext transactionalDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameSessionAuthoredWorldSourceRepository sourceRepository =
        new GameSessionAuthoredWorldSourceRepository(transactionalDsl);
    GameSessionCanonicalRealmCatalogRepository catalogRepository =
        new GameSessionCanonicalRealmCatalogRepository(transactionalDsl);
    IntakeReceipt preV24Source = null;
    CanonicalRealmCatalogSnapshot preV24Catalog = null;
    if (seedCanonicalOwnerEvidenceBeforeV24) {
      preV24Source =
          java.util.Objects.requireNonNull(
              transactions.execute(
                  status -> sourceRepository.register(uuid(977), historicalSource())));
      preV24Catalog = seedHistoricalCatalog(transactionalDsl, transactions, preV24Source);
    }
    String historicalCatalogBefore =
        preV24Catalog == null
            ? null
            : historicalProjection(
                transactionalDsl,
                "game_session_canonical_realm_catalog",
                "creation_request_id",
                preV24Catalog.creationRequestId());
    String historicalSourceBefore =
        preV24Source == null
            ? null
            : historicalProjection(
                transactionalDsl,
                "game_session_authored_world_source_intake",
                "operation_id",
                preV24Source.operationId());
    String historicalNamespaceBefore =
        preV24Source == null
            ? null
            : historicalProjection(
                transactionalDsl,
                "gameplay_tenant_shared_playable_state_namespace",
                "canonical_tenant_id",
                preV24Source.source().canonicalTenantId());
    String historicalBindingBefore =
        preV24Source == null
            ? null
            : historicalProjection(
                transactionalDsl,
                "game_session_authored_world_tenant_source_binding",
                "canonical_tenant_id",
                preV24Source.source().canonicalTenantId());

    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    GameSessionCanonicalLaunchPreparationRepository preparationRepository =
        new GameSessionCanonicalLaunchPreparationRepository(transactionalDsl, catalogRepository);
    GameSessionStartSessionOperatorAttemptRepository attemptRepository =
        new GameSessionStartSessionOperatorAttemptRepository(
            transactionalDsl, Duration.ofMinutes(5));
    return new Fixture(
        transactionalDsl,
        sourceRepository,
        catalogRepository,
        preparationRepository,
        attemptRepository,
        transactionManager,
        transactions,
        preV24Source,
        preV24Catalog,
        historicalCatalogBefore,
        historicalSourceBefore,
        historicalNamespaceBefore,
        historicalBindingBefore);
  }

  private static AuthoredWorldSourceEvidence freshSource() {
    String worldSlug = "violet-wilds";
    String tenantSlug = "tenant-" + TENANT.toString().substring(0, 8);
    String displayName = "Violet Wilds";
    UUID registrationRequestId = uuid(111);
    long sourceGameRowId = 821;
    String sourceGameTenantKey = "fresh-source-821";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, TENANT, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            SOURCE_OPERATION,
            requestDigest,
            TENANT,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        SOURCE_OPERATION,
        requestDigest,
        TENANT,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  /** Literal V23 owner rows are retained history, never complete current policy authority. */
  private static AuthoredWorldSourceEvidence historicalSource() {
    UUID tenant = uuid(970);
    UUID registration = uuid(971);
    UUID operation = uuid(972);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registration,
            tenant,
            "historical-tenant",
            "historical-world",
            "Historical World");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registration,
        operation,
        requestDigest,
        tenant,
        "historical-tenant",
        "historical-world",
        "Historical World",
        971L,
        "historical-gds-key",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            operation,
            requestDigest,
            tenant,
            "historical-tenant",
            "historical-world",
            "Historical World",
            971L,
            "historical-gds-key",
            "NEW_GAME_ROW"));
  }

  private static CanonicalRealmCatalogSnapshot seedHistoricalCatalog(
      DSLContext dsl, TransactionTemplate transactions, IntakeReceipt intake) {
    var source = intake.source();
    UUID request = uuid(976);
    UUID realm = uuid(974);
    UUID namespace = uuid(973);
    String requestDigest =
        CanonicalHumanPublicationOwnerTestFixtures.historicalDigest(
            "game-session-canonical-realm-catalog-request/v1",
            "1",
            request.toString(),
            NAMESPACE,
            source.canonicalTenantId().toString(),
            intake.operationId().toString(),
            intake.intakeRequestId().toString(),
            intake.requestDigest(),
            intake.receiptDigest(),
            "1",
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
            "historical-realm",
            "Historical Realm",
            "true",
            "true",
            "SHARED",
            "PRESEEDED_ONLY",
            "ABSENT",
            "ABSENT");
    String receiptDigest =
        CanonicalHumanPublicationOwnerTestFixtures.historicalDigest(
            "game-session-canonical-realm-catalog-receipt/v1",
            "1",
            NAMESPACE,
            source.canonicalTenantId().toString(),
            request.toString(),
            requestDigest,
            realm.toString(),
            namespace.toString(),
            "1",
            intake.operationId().toString(),
            intake.receiptDigest());
    java.util.Map<String, Object> columns = new java.util.LinkedHashMap<>();
    columns.put("target_namespace", NAMESPACE);
    columns.put("canonical_tenant_id", source.canonicalTenantId());
    columns.put("tenant_slug", source.tenantSlug());
    columns.put("world_slug", source.worldSlug());
    columns.put("realm_id", realm);
    columns.put("realm_slug", "historical-realm");
    columns.put("realm_display_name", "Historical Realm");
    columns.put("visible", true);
    columns.put("public_production", true);
    columns.put("state_scope", "SHARED");
    columns.put("playable_state_namespace_id", namespace);
    columns.put("character_creation_policy", "PRESEEDED_ONLY");
    columns.put("catalog_revision", 1L);
    columns.put("creation_request_id", request);
    columns.put("request_digest", requestDigest);
    columns.put("receipt_digest", receiptDigest);
    columns.put("source_intake_operation_id", intake.operationId());
    columns.put("source_intake_schema_version", 1);
    columns.put("source_intake_request_id", intake.intakeRequestId());
    columns.put("source_intake_request_digest", intake.requestDigest());
    columns.put("source_intake_receipt_digest", intake.receiptDigest());
    columns.put("source_schema_version", 1);
    columns.put("source_registration_request_id", source.registrationRequestId());
    columns.put("source_operation_id", source.operationId());
    columns.put("source_request_digest", source.requestDigest());
    columns.put("source_world_display_name", source.worldDisplayName());
    columns.put("source_game_row_id", source.sourceGameRowId());
    columns.put("source_game_tenant_key", source.sourceGameTenantKey());
    columns.put("source_provenance_kind", source.provenanceKind());
    columns.put("source_evidence_digest", source.evidenceDigest());
    transactions.executeWithoutResult(
        status -> {
          java.util.Map<String, Object> namespaceColumns = new java.util.LinkedHashMap<>();
          namespaceColumns.put("target_namespace", NAMESPACE);
          namespaceColumns.put("canonical_tenant_id", source.canonicalTenantId());
          namespaceColumns.put("playable_state_namespace_id", namespace);
          columns.forEach(
              (name, value) -> {
                if (name.startsWith("source_") && !name.equals("source_world_display_name")) {
                  namespaceColumns.put(name, value);
                }
              });
          dsl.execute(
              "INSERT INTO gameplay_tenant_shared_playable_state_namespace ("
                  + String.join(",", namespaceColumns.keySet())
                  + ") VALUES ("
                  + String.join(",", java.util.Collections.nCopies(namespaceColumns.size(), "?"))
                  + ")",
              namespaceColumns.values().toArray());
          dsl.execute(
              "INSERT INTO game_session_canonical_realm_catalog ("
                  + String.join(",", columns.keySet())
                  + ") VALUES ("
                  + String.join(",", java.util.Collections.nCopies(columns.size(), "?"))
                  + ")",
              columns.values().toArray());
        });
    return new CanonicalRealmCatalogSnapshot(
        NAMESPACE,
        source.canonicalTenantId(),
        source.tenantSlug(),
        source.worldSlug(),
        realm,
        "historical-realm",
        "Historical Realm",
        true,
        true,
        "SHARED",
        namespace,
        "PRESEEDED_ONLY",
        1L,
        request,
        requestDigest,
        receiptDigest,
        intake);
  }

  private static String historicalProjection(
      DSLContext dsl, String table, String selector, UUID identity) {
    return requiredRecord(
            dsl,
            "SELECT xmin::text || '/' || (to_jsonb(owner_row) "
                + "- 'complete_policy_set_evidence' - 'fixture_policy_request_bytes' - 'fixture_policy_seal_operation_id' "
                + "- 'fixture_policy_seal_request_digest' - 'fixture_policy_seal_evidence_digest')::text AS retained "
                + "FROM "
                + table
                + " owner_row WHERE "
                + selector
                + " = ?",
            identity)
        .get("retained", String.class);
  }

  private static Record requiredRecord(DSLContext dsl, String sql, Object... bindings) {
    return java.util.Objects.requireNonNull(
        dsl.fetchOne(sql, bindings), "Expected owner evidence row is missing");
  }

  private static String digest(String letter) {
    return "sha256:" + letter.repeat(64);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private record Fixture(
      DSLContext dsl,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionCanonicalLaunchPreparationRepository preparationRepository,
      GameSessionStartSessionOperatorAttemptRepository attemptRepository,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      IntakeReceipt preV24Source,
      CanonicalRealmCatalogSnapshot preV24Catalog,
      String historicalCatalogBefore,
      String historicalSourceBefore,
      String historicalNamespaceBefore,
      String historicalBindingBefore) {
    void requireHistoricalRowsRetained() {
      java.util.Objects.requireNonNull(
          preV24Catalog, "fixture was created without historical V23 owner rows");
      java.util.Objects.requireNonNull(
          preV24Source, "fixture was created without historical V23 owner rows");
      assertThat(
              historicalProjection(
                  dsl,
                  "game_session_canonical_realm_catalog",
                  "creation_request_id",
                  preV24Catalog.creationRequestId()))
          .isEqualTo(historicalCatalogBefore);
      assertThat(
              historicalProjection(
                  dsl,
                  "game_session_authored_world_source_intake",
                  "operation_id",
                  preV24Source.operationId()))
          .isEqualTo(historicalSourceBefore);
      assertThat(
              historicalProjection(
                  dsl,
                  "gameplay_tenant_shared_playable_state_namespace",
                  "canonical_tenant_id",
                  preV24Source.source().canonicalTenantId()))
          .isEqualTo(historicalNamespaceBefore);
      assertThat(
              historicalProjection(
                  dsl,
                  "game_session_authored_world_tenant_source_binding",
                  "canonical_tenant_id",
                  preV24Source.source().canonicalTenantId()))
          .isEqualTo(historicalBindingBefore);
      assertThat(
              requiredRecord(
                      dsl,
                      "SELECT complete_policy_set_evidence FROM "
                          + "game_session_canonical_realm_catalog WHERE creation_request_id = ?",
                      preV24Catalog.creationRequestId())
                  .get("complete_policy_set_evidence", byte[].class))
          .isNull();
      assertThatThrownBy(
              () -> catalogRepository.readByRequest(NAMESPACE, preV24Catalog.creationRequestId()))
          .isInstanceOf(
              GameSessionCanonicalRealmCatalogRepository.InvalidCatalogEvidenceException.class)
          .hasMessage("Exactly one complete catalog policy provenance required");
    }

    IntakeReceipt registerFreshSource() {
      IntakeReceipt receipt =
          transactions.execute(status -> sourceRepository.register(INTAKE_REQUEST, freshSource()));
      return java.util.Objects.requireNonNull(receipt);
    }

    CanonicalRealmCatalogSnapshot createCatalog(IntakeReceipt source) {
      CreateCanonicalRealmCatalogRequest request =
          new CreateCanonicalRealmCatalogRequest(
              CATALOG_REQUEST,
              NAMESPACE,
              TENANT,
              source.operationId(),
              "violet-realm",
              "Violet Realm",
              true,
              true,
              "SHARED",
              "PRESEEDED_ONLY",
              null,
              null);
      transactions.execute(
          status ->
              new GameSessionFreshTenantAssociationRepository(dsl)
                  .associateFresh(
                      uuid(980),
                      NAMESPACE,
                      source,
                      CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(
                          source.source())));
      var policy =
          CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
              request,
              source.source(),
              uuid(981),
              515L,
              "controlled-publish-launch-preparation",
              "synthetic-published-release-616",
              "generation-config-1",
              4L);
      var prepared = catalogRepository.prepareInitialPublicProduction(request, policy);
      CanonicalRealmCatalogSnapshot created =
          transactions.execute(status -> catalogRepository.createInitialPublicProduction(prepared));
      return java.util.Objects.requireNonNull(created);
    }

    GameSessionCanonicalLaunchPreparationService service(
        AuthoredWorldLaunchDescriptorClient descriptorClient) {
      return new GameSessionCanonicalLaunchPreparationService(
          descriptorClient,
          catalogRepository,
          sourceRepository,
          preparationRepository,
          attemptRepository,
          transactionManager,
          NAMESPACE);
    }

    AttemptClaim reserveAndAttach(StartSessionPostAuthorizationExecutionTuple tuple) {
      ReservationResult reservation =
          java.util.Objects.requireNonNull(
              transactions.execute(status -> attemptRepository.reserve(tuple)));
      AttemptClaim claim = reservation.claim().orElseThrow();
      AccountRedemptionProjection projection =
          new AccountRedemptionProjection(
              tuple.authorizationReferenceFingerprint(),
              tuple.authorityEvidenceBundleBytes(),
              AUTHORIZATION_ISSUANCE_ID,
              Long.parseLong(tuple.issuanceFence()),
              tuple.canonicalBytes());
      java.util.Objects.requireNonNull(
          transactions.execute(
              status -> attemptRepository.attachAccountRedemptionProjection(claim, projection)));
      return claim;
    }
  }
}
