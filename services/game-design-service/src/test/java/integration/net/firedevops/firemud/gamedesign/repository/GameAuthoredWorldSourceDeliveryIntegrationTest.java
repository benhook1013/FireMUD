package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.gamedesign.config.GameAuthoredWorldSourceDeliveryProperties;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository.DeliveryClaim;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.service.impl.GameAuthoredWorldSourceDeliveryService;
import net.firedevops.firemud.gamedesign.service.impl.GameAuthoredWorldSourceDeliveryWorker;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
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

@Testcontainers(disabledWithoutDocker = true)
class GameAuthoredWorldSourceDeliveryIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final String NAMESPACE = "authored-world-delivery-test";
  private static final MigrationVersion PRE_DELIVERY_VERSION = MigrationVersion.fromVersion("40");
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Table<?> SOURCE_OPERATIONS =
      DSL.table(DSL.name("game_design_authored_world_source_operations"));
  private static final Table<?> TENANT_BINDINGS =
      DSL.table(DSL.name("game_design_tenant_slug_binding"));
  private static final Table<?> DELIVERIES =
      DSL.table(DSL.name("game_design_authored_world_source_deliveries"));
  private static final org.jooq.Field<Long> GAME_ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> GAME_TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final org.jooq.Field<String> GAME_NAME = DSL.field(DSL.name("name"), String.class);
  private static final org.jooq.Field<String> GAME_DESCRIPTION =
      DSL.field(DSL.name("description"), String.class);
  private static final org.jooq.Field<UUID> GAME_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void freshRegistrationQueuesOneStableWorldRequestAndExactSourceRetryReusesIt() {
    Fixture fixture = fixture(null);
    UUID registrationRequestId = uuid("11111111-1111-4111-8111-111111111111");

    AuthoredWorldSourceEvidence source =
        fixture.register(
            registrationRequestId, fixture.freshGame(), "fresh-tenant", "silver-march");
    DeliveryClaim firstClaim =
        fixture.deliveryRepository().read(source.operationId()).orElseThrow();
    AuthoredWorldSourceEvidence retry =
        fixture.register(
            registrationRequestId, fixture.freshGame(), "fresh-tenant", "silver-march");
    DeliveryClaim retryClaim =
        fixture.deliveryRepository().read(source.operationId()).orElseThrow();

    assertThat(firstClaim.source()).isEqualTo(source);
    assertThat(firstClaim.request().schemaVersion()).isEqualTo(1);
    assertThat(firstClaim.request().targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(firstClaim.request().canonicalTenantId()).isEqualTo(source.canonicalTenantId());
    assertThat(firstClaim.request().worldSlug()).isEqualTo(source.worldSlug());
    assertThat(firstClaim.request().sourceOperationId()).isEqualTo(source.operationId());
    assertThat(firstClaim.request().expectedSourceEvidenceDigest())
        .isEqualTo(source.evidenceDigest());
    assertThat(firstClaim.request().intakeRequestId()).isNotEqualTo(source.operationId());
    assertThat(retry).isEqualTo(source);
    assertThat(retryClaim).isEqualTo(firstClaim);
    assertThat(fixture.dsl().fetchCount(SOURCE_OPERATIONS)).isEqualTo(1);
    assertThat(fixture.dsl().fetchCount(TENANT_BINDINGS)).isEqualTo(1);
    assertThat(fixture.dsl().fetchCount(DELIVERIES)).isEqualTo(1);
  }

  @Test
  void pendingDeliveryPagesAreNamespaceBoundKeysetOrderedAndExcludeAcknowledgedClaims() {
    Fixture fixture = fixture(null);
    String otherNamespace = "other-authored-world-delivery-test";
    AuthoredWorldSourceEvidence firstNamespaceSource =
        fixture.register(
            NAMESPACE,
            uuid("12121212-1212-4212-8212-121212121212"),
            fixture.freshGame(),
            "pending-tenant-one",
            "pending-world-one");
    AuthoredWorldSourceEvidence secondNamespaceSource =
        fixture.register(
            NAMESPACE,
            uuid("13131313-1313-4313-8313-131313131313"),
            fixture.createFreshGame(),
            "pending-tenant-two",
            "pending-world-two");
    AuthoredWorldSourceEvidence otherNamespaceSource =
        fixture.register(
            otherNamespace,
            uuid("14141414-1414-4414-8414-141414141414"),
            fixture.freshGame(),
            "other-pending-tenant",
            "other-pending-world");

    var firstPage = fixture.deliveryRepository().readPending(NAMESPACE, null, 1);
    var secondPage =
        fixture
            .deliveryRepository()
            .readPending(NAMESPACE, firstPage.get(0).request().intakeRequestId(), 1);
    var otherNamespacePage = fixture.deliveryRepository().readPending(otherNamespace, null, 10);

    assertThat(firstPage).hasSize(1);
    assertThat(secondPage).hasSize(1);
    assertThat(firstPage.get(0).source().operationId())
        .isNotEqualTo(secondPage.get(0).source().operationId());
    assertThat(
            List.of(
                firstPage.get(0).source().operationId(), secondPage.get(0).source().operationId()))
        .containsExactlyInAnyOrder(
            firstNamespaceSource.operationId(), secondNamespaceSource.operationId());
    assertThat(otherNamespacePage)
        .singleElement()
        .extracting(claim -> claim.source().operationId())
        .isEqualTo(otherNamespaceSource.operationId());

    DeliveryClaim pending = firstPage.get(0);
    CommittedReceipt receipt =
        committedReceipt(pending.request(), uuid("15151515-1515-4515-8515-151515151515"), 'a');
    DeliveryClaim acknowledged =
        fixture
            .transactionTemplate()
            .execute(status -> fixture.deliveryRepository().acknowledge(pending, receipt));

    assertThat(acknowledged).isNotNull();
    assertThat(fixture.deliveryRepository().readPending(NAMESPACE, null, 10))
        .singleElement()
        .extracting(claim -> claim.source().operationId())
        .isEqualTo(
            firstNamespaceSource.operationId().equals(pending.source().operationId())
                ? secondNamespaceSource.operationId()
                : firstNamespaceSource.operationId());
  }

  @Test
  void registeredWorkerDispatchesDurableClaimAndAcknowledgedRestartDoesNotRedeliver() {
    Fixture fixture = fixture(null);
    AuthoredWorldSourceEvidence source = fixture.registerFresh(88);
    DeliveryClaim pending = fixture.deliveryRepository().read(source.operationId()).orElseThrow();
    CommittedReceipt worldReceipt =
        committedReceipt(pending.request(), uuid("16161616-1616-4616-8616-161616161616"), 'b');
    WorldAuthoredSourceIntakeClient worldClient = mock(WorldAuthoredSourceIntakeClient.class);
    when(worldClient.intake(pending.request())).thenReturn(worldReceipt);
    when(worldClient.read(any(WorldAuthoredSourceIntakeGrpcCodec.ReadRequest.class)))
        .thenReturn(worldReceipt);
    GameAuthoredWorldSourceDeliveryService deliveryService =
        new GameAuthoredWorldSourceDeliveryService(
            fixture.deliveryRepository(), worldClient, fixture.transactionManager(), NAMESPACE);
    GameAuthoredWorldSourceDeliveryWorker worker =
        new GameAuthoredWorldSourceDeliveryWorker(
            fixture.deliveryRepository(),
            deliveryService,
            new GameAuthoredWorldSourceDeliveryProperties(),
            NAMESPACE);

    assertThat(worker.runPass()).isOne();
    DeliveryClaim acknowledged =
        fixture.deliveryRepository().read(source.operationId()).orElseThrow();
    assertThat(acknowledged.acknowledgedReceipt()).contains(worldReceipt);

    GameAuthoredWorldSourceDeliveryWorker restartedWorker =
        new GameAuthoredWorldSourceDeliveryWorker(
            fixture.deliveryRepository(),
            deliveryService,
            new GameAuthoredWorldSourceDeliveryProperties(),
            NAMESPACE);
    assertThat(restartedWorker.runPass()).isZero();
    verify(worldClient).intake(pending.request());
    verify(worldClient).read(any(WorldAuthoredSourceIntakeGrpcCodec.ReadRequest.class));
  }

  @Test
  void sourceAndDeliveryIntentRollBackTogether() {
    Fixture fixture = fixture(null);
    UUID registrationRequestId = uuid("22222222-2222-4222-8222-222222222222");
    AtomicReference<AuthoredWorldSourceEvidence> sourceRef = new AtomicReference<>();

    assertThatThrownBy(
            () ->
                fixture
                    .transactionTemplate()
                    .execute(
                        status -> {
                          sourceRef.set(
                              fixture
                                  .sourceRepository()
                                  .register(
                                      NAMESPACE,
                                      registrationRequestId,
                                      fixture.freshGame().getCanonicalTenantId(),
                                      "rollback-tenant",
                                      "rollback-world",
                                      "Rollback World"));
                          throw new IllegalStateException("simulate failed source owner commit");
                        }))
        .hasMessageContaining("simulate failed source owner commit");

    AuthoredWorldSourceEvidence attempted = sourceRef.get();
    assertThat(attempted).isNotNull();
    assertThat(
            fixture
                .sourceRepository()
                .read(
                    attempted.operationId(),
                    attempted.canonicalTenantId(),
                    attempted.worldSlug(),
                    attempted.targetNamespace()))
        .isEmpty();
    assertThat(fixture.deliveryRepository().read(attempted.operationId())).isEmpty();
    assertThat(fixture.dsl().fetchCount(SOURCE_OPERATIONS)).isZero();
    assertThat(fixture.dsl().fetchCount(TENANT_BINDINGS)).isZero();
    assertThat(fixture.dsl().fetchCount(DELIVERIES)).isZero();
  }

  @Test
  void concurrentExactDeliveriesReturnOneDurableAckAndChangedReceiptIsDenied() throws Exception {
    Fixture fixture = fixture(null);
    AuthoredWorldSourceEvidence source = fixture.registerFresh(33);
    DeliveryClaim pending = fixture.deliveryRepository().read(source.operationId()).orElseThrow();
    CommittedReceipt receipt =
        committedReceipt(pending.request(), uuid("44444444-4444-4444-8444-444444444444"), 'a');
    WorldAuthoredSourceIntakeClient worldClient = mock(WorldAuthoredSourceIntakeClient.class);
    CountDownLatch intakeCalls = new CountDownLatch(2);
    CountDownLatch releaseIntake = new CountDownLatch(1);
    when(worldClient.intake(pending.request()))
        .thenAnswer(
            invocation -> {
              intakeCalls.countDown();
              await(releaseIntake);
              return receipt;
            });
    when(worldClient.read(any(WorldAuthoredSourceIntakeGrpcCodec.ReadRequest.class)))
        .thenReturn(receipt);
    GameAuthoredWorldSourceDeliveryService service =
        new GameAuthoredWorldSourceDeliveryService(
            fixture.deliveryRepository(), worldClient, fixture.transactionManager(), NAMESPACE);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<DeliveryClaim> first = executor.submit(() -> service.deliver(source.operationId()));
      Future<DeliveryClaim> second = executor.submit(() -> service.deliver(source.operationId()));
      assertThat(intakeCalls.await(10, TimeUnit.SECONDS)).isTrue();
      releaseIntake.countDown();

      DeliveryClaim firstResult = first.get(10, TimeUnit.SECONDS);
      DeliveryClaim secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(firstResult).isEqualTo(secondResult);
      assertThat(firstResult.acknowledgedReceipt()).contains(receipt);
      assertThat(fixture.deliveryRepository().read(source.operationId())).contains(firstResult);
      verify(worldClient, times(2)).intake(pending.request());
      verify(worldClient, times(2)).read(any(WorldAuthoredSourceIntakeGrpcCodec.ReadRequest.class));
    } finally {
      releaseIntake.countDown();
      executor.shutdownNow();
    }

    CommittedReceipt changedReceipt =
        committedReceipt(pending.request(), receipt.operationId(), 'b');
    assertThatThrownBy(
            () ->
                fixture
                    .transactionTemplate()
                    .execute(
                        status ->
                            fixture.deliveryRepository().acknowledge(pending, changedReceipt)))
        .isInstanceOf(GameAuthoredWorldSourceDeliveryRepository.DeliveryConflictException.class)
        .hasMessageContaining("different World receipt");
    assertThat(
            fixture
                .deliveryRepository()
                .read(source.operationId())
                .orElseThrow()
                .acknowledgedReceipt())
        .contains(receipt);
  }

  @Test
  void databaseRejectsDeliverySourceRebindingAndReceiptMutation() {
    Fixture fixture = fixture(null);
    AuthoredWorldSourceEvidence source = fixture.registerFresh(44);
    DeliveryClaim pending = fixture.deliveryRepository().read(source.operationId()).orElseThrow();
    CommittedReceipt receipt =
        committedReceipt(pending.request(), uuid("55555555-5555-4555-8555-555555555555"), 'c');
    DeliveryClaim acknowledged =
        fixture
            .transactionTemplate()
            .execute(status -> fixture.deliveryRepository().acknowledge(pending, receipt));
    assertThat(acknowledged).isNotNull();

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_design_authored_world_source_deliveries "
                            + "SET source_evidence_digest = ? WHERE source_operation_id = ?",
                        digest('d'),
                        source.operationId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("claim cannot be rebound");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_design_authored_world_source_deliveries "
                            + "SET world_receipt_digest = ? WHERE source_operation_id = ?",
                        digest('e'),
                        source.operationId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("acknowledgement is immutable");
    assertThat(fixture.deliveryRepository().read(source.operationId()).orElseThrow())
        .isEqualTo(acknowledged);
  }

  @Test
  void committedAckWithLostLocalCommitAcknowledgementRecoversFromDurableRead() {
    Fixture fixture = fixture(null);
    AuthoredWorldSourceEvidence source = fixture.registerFresh(55);
    DeliveryClaim pending = fixture.deliveryRepository().read(source.operationId()).orElseThrow();
    CommittedReceipt receipt =
        committedReceipt(pending.request(), uuid("66666666-6666-4666-8666-666666666666"), 'f');
    WorldAuthoredSourceIntakeClient worldClient = mock(WorldAuthoredSourceIntakeClient.class);
    when(worldClient.intake(pending.request())).thenReturn(receipt);
    when(worldClient.read(any(WorldAuthoredSourceIntakeGrpcCodec.ReadRequest.class)))
        .thenReturn(receipt);
    CommitAfterCommitLosesAcknowledgementTransactionManager lostAckManager =
        new CommitAfterCommitLosesAcknowledgementTransactionManager(fixture.dataSource());
    GameAuthoredWorldSourceDeliveryService service =
        new GameAuthoredWorldSourceDeliveryService(
            fixture.deliveryRepository(), worldClient, lostAckManager, NAMESPACE);

    DeliveryClaim recovered = service.deliver(source.operationId());
    DeliveryClaim retry = service.deliver(source.operationId());

    assertThat(recovered.acknowledgedReceipt()).contains(receipt);
    assertThat(retry).isEqualTo(recovered);
    assertThat(fixture.deliveryRepository().read(source.operationId())).contains(recovered);
    verify(worldClient).intake(pending.request());
    verify(worldClient).read(any(WorldAuthoredSourceIntakeGrpcCodec.ReadRequest.class));
  }

  @Test
  void retainedSourceAndGameRowsRemainUnchangedAcrossV41WithoutDeliveryAssociation() {
    Fixture fixture = fixture(PRE_DELIVERY_VERSION);
    Map<String, Object> retainedGameBefore =
        gameSnapshot(fixture.dsl(), fixture.retainedGameRowId());
    UUID registrationRequestId = uuid("77777777-7777-4777-8777-777777777777");
    AuthoredWorldSourceEvidence retainedSource =
        fixture.register(
            registrationRequestId,
            fixture.retainedCanonicalTenantId(),
            "retained-tenant",
            "old-coast",
            "Retained Coast");
    Map<String, Object> retainedSourceBefore =
        sourceSnapshot(fixture.dsl(), retainedSource.operationId());
    Map<String, Object> retainedBindingBefore =
        Objects.requireNonNull(
                fixture
                    .dsl()
                    .fetchOne(
                        "SELECT *, xmin::text AS xmin FROM game_design_tenant_slug_binding "
                            + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                        NAMESPACE,
                        retainedSource.canonicalTenantId()),
                "Expected retained tenant binding before V41 migration")
            .intoMap();

    fixture.migrate(null);

    assertThat(gameSnapshot(fixture.dsl(), fixture.retainedGameRowId()))
        .isEqualTo(retainedGameBefore);
    assertThat(sourceSnapshot(fixture.dsl(), retainedSource.operationId()))
        .isEqualTo(retainedSourceBefore);
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT *, xmin::text AS xmin FROM game_design_tenant_slug_binding "
                                + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                            NAMESPACE,
                            retainedSource.canonicalTenantId()),
                    "Expected retained tenant binding after V41 migration")
                .intoMap())
        .isEqualTo(retainedBindingBefore);
    assertThat(
            fixture
                .sourceRepository()
                .read(
                    retainedSource.operationId(),
                    retainedSource.canonicalTenantId(),
                    retainedSource.worldSlug(),
                    retainedSource.targetNamespace()))
        .contains(retainedSource);
    assertThat(fixture.deliveryRepository().read(retainedSource.operationId())).isEmpty();
    assertThat(fixture.dsl().fetchCount(DELIVERIES)).isZero();
  }

  @Test
  void historicalFreshSourceRetryDoesNotBackfillMissingV41DeliveryClaim() {
    Fixture fixture = fixture(PRE_DELIVERY_VERSION);
    UUID registrationRequestId = uuid("88888888-8888-4888-8888-888888888888");
    String tenantSlug = "historic-tenant";
    String worldSlug = "historic-world";
    String displayName = "Historical Fresh World";
    AuthoredWorldSourceEvidence historicalSource =
        seedPreDeliverySource(
            fixture,
            fixture.freshGame(),
            registrationRequestId,
            tenantSlug,
            worldSlug,
            displayName);

    fixture.migrate(null);

    AuthoredWorldSourceEvidence exactRetry =
        fixture.register(
            registrationRequestId,
            fixture.freshGame().getCanonicalTenantId(),
            tenantSlug,
            worldSlug,
            displayName);
    assertThat(exactRetry).isEqualTo(historicalSource);
    assertThat(fixture.deliveryRepository().read(historicalSource.operationId())).isEmpty();
    assertThat(fixture.dsl().fetchCount(DELIVERIES)).isZero();

    WorldAuthoredSourceIntakeClient worldClient = mock(WorldAuthoredSourceIntakeClient.class);
    GameAuthoredWorldSourceDeliveryService service =
        new GameAuthoredWorldSourceDeliveryService(
            fixture.deliveryRepository(), worldClient, fixture.transactionManager(), NAMESPACE);
    assertThatThrownBy(() -> service.deliver(historicalSource.operationId()))
        .isInstanceOf(GameAuthoredWorldSourceDeliveryService.DeliveryNotFoundException.class);
    verifyNoWorldCalls(worldClient);
  }

  private Fixture fixture(MigrationVersion target) {
    String schema = "gd_source_delivery_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, MigrationVersion.fromVersion("29"));
    DSLContext legacyDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    Long retainedGameRowId =
        legacyDsl
            .insertInto(GAME)
            .set(GAME_TENANT_ID, "retained-delivery-source")
            .set(GAME_NAME, "Retained Delivery Source")
            .set(GAME_DESCRIPTION, "Created before canonical tenant identity")
            .returning(GAME_ID)
            .fetchOne(GAME_ID);
    assertThat(retainedGameRowId).isNotNull();
    migrate(dataSource, schema, target);

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    GameAuthoredWorldSourceDeliveryRepository deliveryRepository =
        new GameAuthoredWorldSourceDeliveryRepository(dsl);
    GameAuthoredWorldSourceRepository sourceRepository =
        new GameAuthoredWorldSourceRepository(dsl, deliveryRepository);
    GameRepository gameRepository = new GameRepository(dsl);
    UUID retainedCanonicalTenantId =
        dsl.select(GAME_CANONICAL_TENANT_ID)
            .from(GAME)
            .where(GAME_ID.eq(retainedGameRowId))
            .fetchOne(GAME_CANONICAL_TENANT_ID);
    Game freshGame = new Game();
    freshGame.setTenantId(UUID.randomUUID().toString());
    freshGame.setName("Fresh Delivery Source");
    freshGame.setDescription("Created through the current Game Design owner repository");
    Game persistedFreshGame = transactionTemplate.execute(status -> gameRepository.save(freshGame));
    assertThat(persistedFreshGame).isNotNull();
    return new Fixture(
        schema,
        dataSource,
        dsl,
        transactionManager,
        transactionTemplate,
        sourceRepository,
        deliveryRepository,
        retainedGameRowId,
        retainedCanonicalTenantId,
        persistedFreshGame);
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private static void migrate(
      DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    Path gameDesignMigrations =
        repositoryRoot().resolve("services/game-design-service/src/main/resources/db/migration");
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("filesystem:" + gameDesignMigrations);
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Unable to locate repository migration sources");
  }

  private AuthoredWorldSourceEvidence seedPreDeliverySource(
      Fixture fixture,
      Game game,
      UUID registrationRequestId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName) {
    UUID operationId = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registrationRequestId,
            game.getCanonicalTenantId(),
            tenantSlug,
            worldSlug,
            worldDisplayName);
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registrationRequestId,
            operationId,
            requestDigest,
            game.getCanonicalTenantId(),
            tenantSlug,
            worldSlug,
            worldDisplayName,
            game.getId(),
            game.getTenantId(),
            "NEW_GAME_ROW",
            AuthoredWorldSourceDigest.evidenceDigest(
                NAMESPACE,
                registrationRequestId,
                operationId,
                requestDigest,
                game.getCanonicalTenantId(),
                tenantSlug,
                worldSlug,
                worldDisplayName,
                game.getId(),
                game.getTenantId(),
                "NEW_GAME_ROW"));

    fixture
        .transactionTemplate()
        .execute(
            status -> {
              fixture
                  .dsl()
                  .execute(
                      "INSERT INTO game_design_tenant_slug_binding "
                          + "(target_namespace, canonical_tenant_id, tenant_slug, "
                          + "source_game_row_id, source_game_tenant_key, provenance_kind) "
                          + "VALUES (?, ?, ?, ?, ?, ?)",
                      source.targetNamespace(),
                      source.canonicalTenantId(),
                      source.tenantSlug(),
                      source.sourceGameRowId(),
                      source.sourceGameTenantKey(),
                      source.provenanceKind());
              fixture
                  .dsl()
                  .execute(
                      "INSERT INTO game_design_authored_world_source_operations "
                          + "(operation_id, schema_version, target_namespace, "
                          + "registration_request_id, request_digest, canonical_tenant_id, "
                          + "tenant_slug, world_slug, world_display_name, source_game_row_id, "
                          + "source_game_tenant_key, provenance_kind, evidence_digest) "
                          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                      source.operationId(),
                      source.schemaVersion(),
                      source.targetNamespace(),
                      source.registrationRequestId(),
                      source.requestDigest(),
                      source.canonicalTenantId(),
                      source.tenantSlug(),
                      source.worldSlug(),
                      source.worldDisplayName(),
                      source.sourceGameRowId(),
                      source.sourceGameTenantKey(),
                      source.provenanceKind(),
                      source.evidenceDigest());
              return null;
            });
    return source;
  }

  private static CommittedReceipt committedReceipt(
      IntakeRequest request, UUID worldOperationId, char receiptMarker) {
    return new CommittedReceipt(
        request.schemaVersion(),
        request.targetNamespace(),
        request.intakeRequestId(),
        worldOperationId,
        request.canonicalTenantId(),
        request.worldSlug(),
        request.sourceOperationId(),
        request.expectedSourceEvidenceDigest(),
        WorldAuthoredSourceIntakeGrpcCodec.requestDigest(request),
        digest(receiptMarker));
  }

  private static Map<String, Object> gameSnapshot(DSLContext dsl, long gameRowId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id, tenant_id, canonical_tenant_id, tenant_identity_provenance_kind, "
                    + "tenant_identity_source_game_id, tenant_identity_source_legacy_tenant_id, "
                    + "xmin::text AS xmin FROM game WHERE id = ?",
                gameRowId),
            "Expected game snapshot for row " + gameRowId)
        .intoMap();
  }

  private static Map<String, Object> sourceSnapshot(DSLContext dsl, UUID operationId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT *, xmin::text AS xmin FROM game_design_authored_world_source_operations "
                    + "WHERE operation_id = ?",
                operationId),
            "Expected authored-world source snapshot for operation " + operationId)
        .intoMap();
  }

  private static String digest(char marker) {
    return "sha256:" + String.valueOf(marker).repeat(64);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent World intake delivery");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while awaiting concurrent delivery", exception);
    }
  }

  private static void verifyNoWorldCalls(WorldAuthoredSourceIntakeClient client) {
    verify(client, never()).intake(any());
    verify(client, never()).read(any());
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactionTemplate,
      GameAuthoredWorldSourceRepository sourceRepository,
      GameAuthoredWorldSourceDeliveryRepository deliveryRepository,
      long retainedGameRowId,
      UUID retainedCanonicalTenantId,
      Game freshGame) {
    AuthoredWorldSourceEvidence registerFresh(int suffix) {
      return register(
          uuid(String.format("%08d-1111-4111-8111-111111111111", suffix)),
          freshGame,
          "fresh-tenant-" + suffix,
          "fresh-world-" + suffix);
    }

    AuthoredWorldSourceEvidence register(
        UUID registrationRequestId, Game sourceGame, String tenantSlug, String worldSlug) {
      return register(NAMESPACE, registrationRequestId, sourceGame, tenantSlug, worldSlug);
    }

    AuthoredWorldSourceEvidence register(
        String targetNamespace,
        UUID registrationRequestId,
        Game sourceGame,
        String tenantSlug,
        String worldSlug) {
      AuthoredWorldSourceEvidence source =
          transactionTemplate.execute(
              status ->
                  sourceRepository.register(
                      targetNamespace,
                      registrationRequestId,
                      sourceGame.getCanonicalTenantId(),
                      tenantSlug,
                      worldSlug,
                      "Authored World " + worldSlug));
      return java.util.Objects.requireNonNull(source);
    }

    Game createFreshGame() {
      Game game = new Game();
      game.setTenantId(UUID.randomUUID().toString());
      game.setName("Additional Fresh Delivery Source");
      game.setDescription("A second isolated fresh tenant source for pending paging");
      return Objects.requireNonNull(
          transactionTemplate.execute(status -> new GameRepository(dsl).save(game)));
    }

    AuthoredWorldSourceEvidence register(
        UUID registrationRequestId,
        UUID canonicalTenantId,
        String tenantSlug,
        String worldSlug,
        String worldDisplayName) {
      AuthoredWorldSourceEvidence source =
          transactionTemplate.execute(
              status ->
                  sourceRepository.register(
                      NAMESPACE,
                      registrationRequestId,
                      canonicalTenantId,
                      tenantSlug,
                      worldSlug,
                      worldDisplayName));
      return java.util.Objects.requireNonNull(source);
    }

    void migrate(MigrationVersion target) {
      GameAuthoredWorldSourceDeliveryIntegrationTest.migrate(dataSource, schema, target);
    }
  }

  private static final class CommitAfterCommitLosesAcknowledgementTransactionManager
      extends DataSourceTransactionManager {
    private boolean loseNextAcknowledgement = true;

    CommitAfterCommitLosesAcknowledgementTransactionManager(DriverManagerDataSource dataSource) {
      super(dataSource);
    }

    @Override
    protected void doCommit(
        org.springframework.transaction.support.DefaultTransactionStatus status) {
      super.doCommit(status);
      if (loseNextAcknowledgement) {
        loseNextAcknowledgement = false;
        throw new IllegalStateException("simulated lost local commit acknowledgement");
      }
    }
  }
}
