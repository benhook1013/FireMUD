package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.gamesession.repository.CanonicalHumanPublicationOwnerTestFixtures;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPrivateFreshTargetSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Preparation;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshCatalogRevisionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshRuntimeAllocationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivatePreparationProtectionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateWorldPreparationBuildReader;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateWorldPreparationSourceReader;
import net.firedevops.firemud.gamesession.security.PrivatePreparationProtectionWorkloadGuard;
import net.firedevops.firemud.gamesession.security.PrivateWorldPreparationReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.impl.PrivatePreparationProtectionGrpcService;
import net.firedevops.firemud.gamesession.service.impl.PrivateWorldPreparationReadGrpcService;
import net.firedevops.firemud.gamesession.v1.PrivateWorldPreparationReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationBuildResponse;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationRequest;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Actual committed GS preparation/current selection/private target to receiver, mTLS client and
 * World input assembly. Published upstream policies/owner evidence and PKI are controlled. No Human
 * StartSession, ongoing protection, World runtime, FRESH manifest or admission is fabricated.
 */
@Testcontainers(disabledWithoutDocker = false)
@SuppressWarnings("resource")
class PrivateWorldPreparationSourceCompositionCrossServiceTest {
  private static final String NS = "private-world-proof";

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @TempDir Path directory;

  @BeforeAll
  static void awaitReady() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  void actualRetainedPolicyProjectsOriginalWorldBuildAcrossAuthenticatedRead() throws Exception {
    var db = database();
    db.allocate();
    var before = db.retainedRows();
    try (var rpc = new Running(db, directory)) {
      var build =
          rpc.client
              .readBuild(
                  db.scope(),
                  db.first.request().requestId(),
                  io.grpc.Deadline.after(5, TimeUnit.SECONDS))
              .evidence();
      assertThat(build.source().snapshot().source().canonicalVersionId())
          .isEqualTo(build.selectedWorld().request().canonicalVersionId());
      assertThat(build.source().snapshot().source().policyCarrierDigest())
          .isEqualTo(db.first.source().policyCarrierDigest());
      assertThat(build.admissionAllowed()).isFalse();
    }
    assertThat(db.retainedRows()).isEqualTo(before);
    db.assertNoAdmissionOrRuntime();
  }

  @Test
  void actualOwnerProtectionRoundTripRetainsPendingSourceAndBlocksSupersession() throws Exception {
    var db = database();
    db.allocate();
    try (var rpc = new Running(db, directory)) {
      var input =
          new WorldPrivatePreparationInputAssembly(rpc.client, Clock.systemUTC())
              .assemble(db.scope(), db.first.request().requestId());
      UUID request = UUID.randomUUID();
      var held = rpc.protectionClient.acquire(request, input.committedReadback());
      var current = rpc.protectionClient.readCurrent(held);
      assertThat(current.evidence()).isEqualTo(held.evidence());
      assertThat(current.currentRead()).isTrue();
      assertThat(current.admissionAllowed()).isFalse();
      assertThat(db.dsl.fetchCount(DSL.table("game_session_private_preparation_protection")))
          .isEqualTo(1);
      assertThatThrownBy(
              () ->
                  db.in(
                      () ->
                          db.current.select(
                              new GameSessionCurrentPrivatePlaytestRepository.Request(
                                  UUID.randomUUID(),
                                  new GameSessionCurrentPrivatePlaytestRepository.Scope(
                                      NS, db.tenant, "source-world", "private-playtest"),
                                  db.second.request().requestId(),
                                  db.second.playtestLifecycleId(),
                                  1L,
                                  db.first.playtestLifecycleId()))))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("unresolved World preparation");
      assertThat(rpc.protectionClient.acquire(request, input.committedReadback()).evidence())
          .isEqualTo(held.evidence());
      db.assertNoAdmissionOrRuntime();
    }
  }

  @Test
  void actualCommittedOwnersComposeAcrossMtlsAndIndependentRereadWithoutChangingPublicRows()
      throws Exception {
    var db = database();
    var allocated = db.allocate();
    String beforePublic = db.publicRows();
    var retained = db.retainedRows();
    try (var rpc = new Running(db, directory)) {
      var inputs =
          new WorldPrivatePreparationInputAssembly(rpc.client, Clock.systemUTC())
              .assemble(db.scope(), db.first.request().requestId());
      assertThat(inputs.admissionAllowed()).isFalse();
      assertThat(inputs.snapshot().admissionAllowed()).isFalse();
      assertThat(inputs.snapshot().target().admissionAllowed()).isFalse();
      assertThat(inputs.snapshot().target().realmId()).isEqualTo(allocated.realmId());
      assertThat(inputs.snapshot().target().receiptDigest()).isEqualTo(allocated.receiptDigest());
      assertThat(inputs.snapshot().target().creationRequestId())
          .isEqualTo(allocated.creationRequestId());
      assertThat(inputs.snapshot().source().targetPolicy())
          .isEqualTo(db.first.source().targetPolicy());
      assertThat(inputs.snapshot().source().policyCarrierDigest())
          .isEqualTo(db.first.source().policyCarrierDigest());
      assertThat(inputs.snapshot().preparation().originalRequest().toByteArray())
          .containsExactly(db.first.request().canonicalPreimage());
      assertThat(inputs.snapshot().selection().originalRequest().toByteArray())
          .containsExactly(db.selection.request().canonicalPreimage());
      assertThat(inputs.snapshot().preparation().requestDigest())
          .isEqualTo(db.first.requestDigest());
      assertThat(inputs.snapshot().selection().requestDigest())
          .isEqualTo(db.selection.requestDigest());
      assertThat(inputs.snapshot().preparation().originalRequest().toByteArray())
          .containsExactly(
              db.dsl
                  .fetchSingle(
                      "SELECT request_preimage FROM game_session_initial_playtest_preparations WHERE request_id = ?",
                      db.first.request().requestId())
                  .get(0, byte[].class));
      assertThat(inputs.snapshot().selection().originalRequest().toByteArray())
          .containsExactly(
              db.dsl
                  .fetchSingle(
                      "SELECT request_preimage FROM game_session_private_playtest_selections WHERE request_id = ?",
                      db.selection.request().requestId())
                  .get(0, byte[].class));
      assertThat(inputs.firstRead().request().readRequestId())
          .isNotEqualTo(inputs.committedReadback().request().readRequestId());
      assertThat(rpc.requests).hasSize(2);
      assertThat(rpc.responses).hasSize(2);
      assertThat(
              PrivateWorldPreparationReadGrpcCodec.fromResponse(
                  rpc.requests.get(0), rpc.responses.get(0)))
          .isEqualTo(inputs.firstRead());
      assertThat(
              PrivateWorldPreparationReadGrpcCodec.fromResponse(
                  rpc.requests.get(1), rpc.responses.get(1)))
          .isEqualTo(inputs.committedReadback());
      // The owner APIs independently reread committed state after both physical RPCs returned.
      assertThat(
              db.targets.readByPreparation(
                  db.first.request().requestId(), db.first.playtestLifecycleId()))
          .contains(allocated);
      assertThat(
              db.in(() -> db.current.readCurrent(NS, db.tenant, "source-world", "private-playtest"))
                  .orElseThrow()
                  .selectedPrepSnapshot()
                  .preparation())
          .isEqualTo(db.first);
    }
    assertThat(db.publicRows()).isEqualTo(beforePublic);
    assertThat(db.retainedRows()).isEqualTo(retained);
    db.assertNoAdmissionOrRuntime();
  }

  @Test
  void laterCatalogRevisionComposesAcrossRealMtlsWithoutReplacingTheRealmOrOriginalReceipts()
      throws Exception {
    var db = database();
    var initial = db.allocate();
    String publicBefore = db.publicRows();
    String originalBefore =
        db.dsl
            .fetchSingle(
                "SELECT to_jsonb(c)::text||':'||xmin::text FROM game_session_canonical_realm_catalog c WHERE realm_id=?",
                initial.realmId())
            .get(0, String.class);
    var revisions = new GameSessionPrivateFreshCatalogRevisionRepository(db.dsl);
    var advance =
        new GameSessionPrivateFreshCatalogRevisionRepository.AdvanceRequest(
            UUID.randomUUID(),
            initial.realmId(),
            db.second.request().requestId(),
            db.second.playtestLifecycleId(),
            1,
            db.first.playtestLifecycleId());
    var revised =
        Objects.requireNonNull(db.serializable.execute(status -> revisions.advance(advance)))
            .snapshot();
    var selected =
        db.in(
                () ->
                    db.current.select(
                        new GameSessionCurrentPrivatePlaytestRepository.Request(
                            UUID.randomUUID(),
                            new GameSessionCurrentPrivatePlaytestRepository.Scope(
                                NS, db.tenant, "source-world", "private-playtest"),
                            db.second.request().requestId(),
                            db.second.playtestLifecycleId(),
                            1L,
                            db.first.playtestLifecycleId())))
            .receipt();
    db.allocateRuntime(revised, selected);
    try (var rpc = new Running(db, directory)) {
      var inputs =
          new WorldPrivatePreparationInputAssembly(rpc.client, Clock.systemUTC())
              .assemble(db.scope(), db.second.request().requestId());
      assertThat(inputs.snapshot().target().catalogRevision()).isEqualTo(2);
      assertThat(inputs.snapshot().target().realmId()).isEqualTo(initial.realmId());
      assertThat(inputs.snapshot().target().receiptDigest()).isEqualTo(revised.receiptDigest());
      assertThat(inputs.snapshot().target().creationRequestId()).isEqualTo(advance.requestId());
      assertThat(inputs.snapshot().preparation().requestId())
          .isEqualTo(db.second.request().requestId());
      assertThat(inputs.snapshot().selection().requestId())
          .isEqualTo(selected.request().requestId());
      assertThat(inputs.snapshot().source().catalogRevision()).isEqualTo(1);
      assertThat(inputs.snapshot().admissionAllowed()).isFalse();
      assertThat(inputs.admissionAllowed()).isFalse();
      assertThat(rpc.requests).hasSize(2);
      assertThat(rpc.responses).hasSize(2);
      assertThat(revisions.readByRequest(advance.requestId())).contains(revised);
      assertThat(revisions.readByRequest(initial.creationRequestId())).contains(initial);
      assertThat(revisions.readCurrent(initial.realmId())).contains(revised);
    }
    assertThat(
            db.dsl
                .fetchSingle(
                    "SELECT to_jsonb(c)::text||':'||xmin::text FROM game_session_canonical_realm_catalog c WHERE realm_id=?",
                    initial.realmId())
                .get(0, String.class))
        .isEqualTo(originalBefore);
    assertThat(db.publicRows()).isEqualTo(publicBefore);
    db.assertNoAdmissionOrRuntime();
  }

  @Test
  void actualCurrentOwnerCasBetweenCommittedReadsRefusesOriginalInsteadOfReselecting()
      throws Exception {
    var db = database();
    db.allocate();
    String beforePublic = db.publicRows();
    try (var rpc = new Running(db, directory)) {
      rpc.afterFirst.set(
          () ->
              db.in(
                  () ->
                      db.current.select(
                          new GameSessionCurrentPrivatePlaytestRepository.Request(
                              UUID.randomUUID(),
                              new GameSessionCurrentPrivatePlaytestRepository.Scope(
                                  NS, db.tenant, "source-world", "private-playtest"),
                              db.second.request().requestId(),
                              db.second.playtestLifecycleId(),
                              1L,
                              db.first.playtestLifecycleId()))));
      assertThatThrownBy(
              () ->
                  new WorldPrivatePreparationInputAssembly(rpc.client, Clock.systemUTC())
                      .assemble(db.scope(), db.first.request().requestId()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(error.getStatus().getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
      assertThat(rpc.requests).hasSize(2);
      assertThat(rpc.responses).hasSize(1);
      var current =
          db.in(() -> db.current.readCurrent(NS, db.tenant, "source-world", "private-playtest"))
              .orElseThrow();
      assertThat(current.selection().headRevision()).isEqualTo(2);
      assertThat(current.selectedPrepSnapshot().preparation()).isEqualTo(db.second);
    }
    assertThat(db.publicRows()).isEqualTo(beforePublic);
    db.assertNoAdmissionOrRuntime();
  }

  @Test
  void absentAllocatedTargetAndWrongExpectedOriginalRefuseWithoutWritingOrInventingReadiness()
      throws Exception {
    var db = database();
    var before = db.retainedRows();
    try (var rpc = new Running(db, directory)) {
      assertThatThrownBy(() -> rpc.client.read(db.scope(), db.first.request().requestId()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(error.getStatus().getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
      assertThat(db.retainedRows()).isEqualTo(before);
      db.allocate();
      var allocated = db.retainedRows();
      assertThatThrownBy(() -> rpc.client.read(db.scope(), UUID.randomUUID()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(error.getStatus().getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
      assertThat(db.retainedRows()).isEqualTo(allocated);
    }
    db.assertNoAdmissionOrRuntime();
  }

  @Test
  void guardedSourceAndReceiptDriftCannotPoisonExistingTargetAndOwnerReadStillQualifiesIt()
      throws Exception {
    var db = database();
    var target = db.allocate();
    var before = db.retainedRows();
    for (String column : List.of("receipt_digest", "private_source_policy_carrier_digest")) {
      assertThatThrownBy(
              () ->
                  db.dsl.execute(
                      "UPDATE game_session_canonical_realm_catalog SET "
                          + column
                          + " = ? WHERE realm_id = ?",
                      "sha256:" + "b".repeat(64),
                      target.realmId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(db.retainedRows()).isEqualTo(before);
    }
    assertThat(
            db.in(
                () ->
                    db.targets.readForWorldPreparation(
                        db.first.request().requestId(), db.first.playtestLifecycleId())))
        .contains(target);
    try (var rpc = new Running(db, directory)) {
      assertThat(
              new WorldPrivatePreparationInputAssembly(rpc.client, Clock.systemUTC())
                  .assemble(db.scope(), db.first.request().requestId())
                  .admissionAllowed())
          .isFalse();
    }
    assertThat(db.retainedRows()).isEqualTo(before);
  }

  private static Database database() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.exists(root.resolve("settings.gradle.kts")))
      root = root.getParent();
    if (root == null) throw new IllegalStateException("Repository source migrations unavailable");
    String schema = "private_world_source_" + UUID.randomUUID().toString().replace("-", "");
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    ds.setSchema(schema);
    Flyway.configure()
        .dataSource(ds)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations(
            "filesystem:"
                + root.resolve("services/game-session-service/src/main/resources/db/migration"),
            "filesystem:"
                + root.resolve("services/common-saga/src/main/resources/db/migration/saga"))
        .load()
        .migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES);
    var transactions = new DataSourceTransactionManager(ds);
    var write = new TransactionTemplate(transactions);
    var serializable = new TransactionTemplate(transactions);
    serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    UUID tenant = UUID.randomUUID(),
        registration = UUID.randomUUID(),
        operation = UUID.randomUUID();
    String digest =
        AuthoredWorldSourceDigest.requestDigest(
            NS, registration, tenant, "source-tenant", "source-world", "Source World");
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            NS,
            registration,
            operation,
            digest,
            tenant,
            "source-tenant",
            "source-world",
            "Source World",
            731L,
            "controlled-source-game",
            "NEW_GAME_ROW",
            AuthoredWorldSourceDigest.evidenceDigest(
                NS,
                registration,
                operation,
                digest,
                tenant,
                "source-tenant",
                "source-world",
                "Source World",
                731L,
                "controlled-source-game",
                "NEW_GAME_ROW"));
    var intake =
        Objects.requireNonNull(
            write.execute(
                status ->
                    new GameSessionAuthoredWorldSourceRepository(dsl)
                        .register(UUID.randomUUID(), source)));
    write.executeWithoutResult(
        status ->
            new GameSessionFreshTenantAssociationRepository(dsl)
                .associateFresh(
                    UUID.randomUUID(),
                    NS,
                    intake,
                    CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(source)));
    var catalogRequest =
        new CreateCanonicalRealmCatalogRequest(
            UUID.randomUUID(),
            NS,
            tenant,
            intake.operationId(),
            "source-public",
            "Source Public",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    UUID version = UUID.randomUUID();
    var policy =
        RealmEntryPolicy.parse(
            """
        {"schemaVersion":1,"worldSlug":"source-world","worldDisplayName":"Source World","realmSlug":"private-playtest",
         "realmDisplayName":"Private Playtest","visible":true,"publicProduction":false,"stateScope":"ISOLATED","entryPolicy":"PRESEEDED_ONLY"}
        """,
            JsonMapper.builder().build());
    var published =
        CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            catalogRequest,
            source,
            version,
            72L,
            "controlled-publish-" + UUID.randomUUID(),
            "release-bundle:" + version,
            "generation-1",
            3L,
            List.of(policy));
    var catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
    var preparedCatalog = catalogs.prepareInitialPublicProduction(catalogRequest, published);
    var catalog =
        Objects.requireNonNull(
            write.execute(status -> catalogs.createInitialPublicProduction(preparedCatalog)));
    var preparations = new GameSessionInitialPlaytestLifecycleRepository(dsl);
    Instant now =
        dsl.fetchSingle("SELECT clock_timestamp()").get(0, OffsetDateTime.class).toInstant();
    var firstRequest =
        preparationRequest(
            tenant,
            catalog,
            version,
            published.publishedReleaseBundleDigest(),
            now.plusSeconds(3600));
    var first =
        Objects.requireNonNull(write.execute(status -> preparations.prepare(firstRequest)))
            .preparation();
    // Prepare the replacement before target allocation; no second target/runtime is manufactured.
    var secondRequest =
        preparationRequest(
            tenant,
            catalog,
            version,
            published.publishedReleaseBundleDigest(),
            now.plusSeconds(7200));
    var second =
        Objects.requireNonNull(write.execute(status -> preparations.prepare(secondRequest)))
            .preparation();
    var current = new GameSessionCurrentPrivatePlaytestRepository(dsl);
    var selected =
        Objects.requireNonNull(
                write.execute(
                    status ->
                        current.select(
                            new GameSessionCurrentPrivatePlaytestRepository.Request(
                                UUID.randomUUID(),
                                new GameSessionCurrentPrivatePlaytestRepository.Scope(
                                    NS, tenant, "source-world", "private-playtest"),
                                first.request().requestId(),
                                first.playtestLifecycleId(),
                                null,
                                null))))
            .receipt();
    return new Database(
        dsl,
        transactions,
        write,
        serializable,
        tenant,
        first,
        second,
        selected,
        current,
        new GameSessionPrivateFreshTargetRepository(dsl));
  }

  private static GameSessionInitialPlaytestLifecycleRepository.Request preparationRequest(
      UUID tenant,
      CanonicalRealmCatalogSnapshot catalog,
      UUID version,
      String bundleDigest,
      Instant expiry) {
    return new GameSessionInitialPlaytestLifecycleRepository.Request(
        UUID.randomUUID(),
        NS,
        tenant,
        "source-world",
        "private-playtest",
        catalog.realmId(),
        catalog.creationRequestId(),
        catalog.catalogRevision(),
        catalog.sourceIntakeReceipt().operationId(),
        version,
        "release-bundle:" + version,
        bundleDigest,
        GameSessionInitialPlaytestLifecycleRepository.InitializationMode.FRESH,
        expiry);
  }

  private record Database(
      DSLContext dsl,
      DataSourceTransactionManager transactions,
      TransactionTemplate write,
      TransactionTemplate serializable,
      UUID tenant,
      Preparation first,
      Preparation second,
      GameSessionCurrentPrivatePlaytestRepository.SelectionReceipt selection,
      GameSessionCurrentPrivatePlaytestRepository current,
      GameSessionPrivateFreshTargetRepository targets) {
    <T> T in(Supplier<T> work) {
      return Objects.requireNonNull(write.execute(status -> work.get()));
    }

    Scope scope() {
      return new Scope(NS, tenant, "source-world", "private-playtest");
    }

    CanonicalPrivateFreshTargetSnapshot allocate() {
      var target =
          Objects.requireNonNull(
                  serializable.execute(
                      status ->
                          targets.allocate(
                              new GameSessionPrivateFreshTargetRepository.Request(
                                  UUID.randomUUID(),
                                  first.request().requestId(),
                                  first.playtestLifecycleId()))))
              .snapshot();
      allocateRuntime(target, selection);
      return target;
    }

    void allocateRuntime(
        CanonicalPrivateFreshTargetSnapshot target,
        GameSessionCurrentPrivatePlaytestRepository.SelectionReceipt selected) {
      serializable.execute(
          status ->
              new GameSessionPrivateFreshRuntimeAllocationRepository(dsl)
                  .allocate(
                      new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
                          UUID.randomUUID(),
                          target.preparationRequestId(),
                          target.playtestLifecycleId(),
                          target.realmId(),
                          target.catalogRevision(),
                          target.creationRequestId(),
                          target.receiptDigest(),
                          selected.request().requestId(),
                          selected.headRevision())));
    }

    String publicRows() {
      return rows(
          "game_session_canonical_realm_catalog", " WHERE private_preparation_request_id IS NULL");
    }

    List<String> retainedRows() {
      return List.of(
          rows("game_session_canonical_realm_catalog", ""),
          rows("game_session_initial_playtest_preparations", ""),
          rows("game_session_private_playtest_selections", ""),
          rows("game_session_current_private_playtests", ""),
          rows("game_session_private_fresh_runtime_allocations", ""));
    }

    String rows(String table, String where) {
      return dsl.fetchSingle(
              "SELECT coalesce(jsonb_agg(snapshot ORDER BY snapshot::text)::text, '[]') FROM "
                  + "(SELECT to_jsonb(value) || jsonb_build_object('xmin',value.xmin::text) AS snapshot FROM "
                  + table
                  + " value"
                  + where
                  + ") retained")
          .get(0, String.class);
    }

    void assertNoAdmissionOrRuntime() {
      assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("game_instances"))).isZero();
    }
  }

  private static final class Running implements AutoCloseable {
    final AtomicReference<Runnable> afterFirst = new AtomicReference<>();
    final List<PrivateWorldPreparationReadGrpcCodec.Request> requests =
        new CopyOnWriteArrayList<>();
    final List<ReadPrivateWorldPreparationResponse> responses = new CopyOnWriteArrayList<>();
    final WorldPrivatePreparationSourceClient client;
    final WorldPrivatePreparationProtectionClient protectionClient;
    final Server server;

    Running(Database db, Path directory) throws Exception {
      Files.createDirectories(directory);
      var pki =
          LoopbackMtlsTestPki.create(
              directory,
              "Private World composition test CA",
              new LoopbackMtlsTestPki.IdentitySpec(
                  "world", "spiffe://firemud/ns/" + NS + "/sa/world-management-service", false),
              new LoopbackMtlsTestPki.IdentitySpec(
                  "gs", "spiffe://firemud/ns/" + NS + "/sa/game-session-service", true));
      var actual =
          new PrivateWorldPreparationReadGrpcService(
              db.current,
              db.targets,
              new GameSessionPrivateFreshRuntimeAllocationRepository(db.dsl),
              db.transactions,
              NS,
              new GameSessionPrivateWorldPreparationBuildReader(
                  db.dsl,
                  new GameSessionPrivateWorldPreparationSourceReader(
                      db.current,
                      db.targets,
                      new GameSessionPrivateFreshRuntimeAllocationRepository(db.dsl))));
      var completed = new AtomicInteger();
      var handler =
          new PrivateWorldPreparationReadServiceGrpc.PrivateWorldPreparationReadServiceImplBase() {
            @Override
            public void readPrivateWorldPreparationBuild(
                ReadPrivateWorldPreparationRequest request,
                StreamObserver<ReadPrivateWorldPreparationBuildResponse> observer) {
              actual.readPrivateWorldPreparationBuild(request, observer);
            }

            @Override
            public void readPrivateWorldPreparation(
                ReadPrivateWorldPreparationRequest wire,
                StreamObserver<ReadPrivateWorldPreparationResponse> observer) {
              requests.add(PrivateWorldPreparationReadGrpcCodec.parseRequest(wire));
              actual.readPrivateWorldPreparation(
                  wire,
                  new StreamObserver<>() {
                    @Override
                    public void onNext(ReadPrivateWorldPreparationResponse value) {
                      responses.add(value);
                      observer.onNext(value);
                    }

                    @Override
                    public void onError(Throwable failure) {
                      observer.onError(failure);
                    }

                    @Override
                    public void onCompleted() {
                      try {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                        if (completed.incrementAndGet() == 1) {
                          Runnable change = afterFirst.getAndSet(null);
                          if (change != null) change.run();
                        }
                        observer.onCompleted();
                      } catch (RuntimeException failure) {
                        observer.onError(Status.INTERNAL.asRuntimeException());
                      }
                    }
                  });
            }
          };
      var leaf = pki.identity("gs");
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .sslContext(
                  GrpcSslContexts.configure(
                          SslContextBuilder.forServer(leaf.privateKey(), leaf.certificate()))
                      .trustManager(pki.caCertificate())
                      .clientAuth(ClientAuth.REQUIRE)
                      .build())
              .maxInboundMessageSize(PrivatePreparationProtectionGrpcCodec.MAX_WIRE_BYTES)
              .addService(
                  ServerInterceptors.interceptForward(
                      handler,
                      new GrpcPeerIdentityInterceptor(),
                      new PrivateWorldPreparationReadWorkloadGuard(NS)))
              .addService(
                  ServerInterceptors.interceptForward(
                      new PrivatePreparationProtectionGrpcService(
                          new GameSessionPrivatePreparationProtectionRepository(
                              db.dsl, db.transactions),
                          NS),
                      new GrpcPeerIdentityInterceptor(),
                      new PrivatePreparationProtectionWorkloadGuard(NS)))
              .build()
              .start();
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameSessionService("localhost:" + server.getPort());
      client =
          new WorldPrivatePreparationSourceClient(
              endpoints,
              pki.clientProperties(directory, pki.identity("world")),
              new GrpcChannelFactory(),
              NS,
              Clock.systemUTC());
      protectionClient =
          new WorldPrivatePreparationProtectionClient(
              endpoints,
              pki.clientProperties(directory, pki.identity("world")),
              new GrpcChannelFactory(),
              NS,
              Clock.systemUTC());
      try {
        client.init();
        protectionClient.init();
      } catch (Exception | Error failure) {
        server.shutdownNow();
        throw failure;
      }
    }

    @Override
    public void close() throws Exception {
      try {
        try {
          client.close();
        } finally {
          protectionClient.close();
        }
      } finally {
        server.shutdownNow();
        assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }
}
