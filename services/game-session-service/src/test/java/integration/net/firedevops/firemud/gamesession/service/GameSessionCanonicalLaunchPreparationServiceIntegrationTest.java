package integration.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
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
import net.firedevops.firemud.gamesession.service.GameSessionCanonicalLaunchPreparationService;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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
 * Real PostgreSQL source/catalog transactions and immutable preparation storage. Game Design's
 * descriptor response is deliberately synthetic here; this is not transport or mTLS proof.
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
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void
      commitsExactFreshSourceCatalogAndSyntheticDescriptorThenReplaysWithoutChangingRetainedRows() {
    Fixture fixture = fixture(true, true);
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
        .isZero();
    IntakeReceipt source = java.util.Objects.requireNonNull(fixture.preV24Source);
    CanonicalRealmCatalogSnapshot catalog = java.util.Objects.requireNonNull(fixture.preV24Catalog);
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
                  status -> sourceRepository.register(INTAKE_REQUEST, freshSource())));
      CreateCanonicalRealmCatalogRequest catalogRequest =
          new CreateCanonicalRealmCatalogRequest(
              CATALOG_REQUEST,
              NAMESPACE,
              TENANT,
              preV24Source.operationId(),
              "violet-realm",
              "Violet Realm",
              true,
              true,
              "SHARED",
              "explicit-policy-v1",
              null,
              null);
      var preparedCatalog = catalogRepository.prepareInitialPublicProduction(catalogRequest);
      preV24Catalog =
          java.util.Objects.requireNonNull(
              transactions.execute(
                  status -> catalogRepository.createInitialPublicProduction(preparedCatalog)));
    }

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
    return new Fixture(
        transactionalDsl,
        sourceRepository,
        catalogRepository,
        preparationRepository,
        transactionManager,
        transactions,
        preV24Source,
        preV24Catalog);
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
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      IntakeReceipt preV24Source,
      CanonicalRealmCatalogSnapshot preV24Catalog) {
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
              "explicit-policy-v1",
              null,
              null);
      var prepared = catalogRepository.prepareInitialPublicProduction(request);
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
          transactionManager,
          NAMESPACE);
    }
  }
}
