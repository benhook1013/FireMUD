package net.firedevops.firemud.worldmanagement.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.world.FixtureInitialAdmissionGrpcCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceFixtureActivationRequest;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest;
import net.firedevops.firemud.gamesession.client.GrpcWorldFixtureInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalInitialAdmissionOwner;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalFixtureInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.impl.FixtureInitialAdmissionOwnerReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcService;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionCanonicalInitialAdmissionOwnerClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldFixtureInitialAdmissionHoldGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldFixtureInitialAdmissionHoldOwner;
import net.firedevops.firemud.worldmanagement.tenant.WorldFixtureInitialAdmissionHoldRepository;
import net.firedevops.firemud.worldmanagement.v1.FixtureInitialAdmissionHoldOperationRequest;
import net.firedevops.firemud.worldmanagement.v1.FixtureInitialAdmissionHoldOperationResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldFixtureInitialAdmissionHoldServiceGrpc;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Two actual PostgreSQL owners and reciprocal fixed-leaf mTLS. Game Design creation/source/
 * descriptor bytes and the World installation artifact remain controlled upstream prerequisites. No
 * default receiver, scheduler, deployed activation, Account JOIN or player admission is proved.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldFixtureFirstOpenOwnerCompositionTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void awaitJdbc() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  @SuppressWarnings("unchecked")
  void actualFirstOpenAndAuthenticatedTerminalSettleOriginalWorldHoldThenReplayHistorically(
      @TempDir Path temporary) throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            temporary,
            "Fixture first OPEN test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "gs", "spiffe://firemud/ns/dev/sa/game-session-service", true, true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "world", "spiffe://firemud/ns/dev/sa/world-management-service", true, true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-world-leaf", "spiffe://firemud/ns/dev/sa/world-management-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-gs-service", "spiffe://firemud/ns/dev/sa/account-service", true));
    String gsLeaf = digest(pki.identity("gs").certificate().getEncoded());
    String worldLeaf = digest(pki.identity("world").certificate().getEncoded());
    String ca = digest(pki.caCertificate().getEncoded());
    var mapper = new ObjectMapper();
    var controlled = RunOwnedWorldFixtureEvidenceTestFixtures.create(mapper, gsLeaf, worldLeaf, ca);
    Path capability = immutable(temporary.resolve("capability.json"), controlled.capabilityBytes());
    Path receipt = immutable(temporary.resolve("receipt.json"), controlled.sourceReceiptBytes());

    var gs = database("fixture_gs_", false);
    var world = database("fixture_world_", true);
    var gsTls = pki.clientProperties(temporary, pki.identity("gs"));
    var worldTls = pki.clientProperties(temporary, pki.identity("world"));
    var catalog = new GameSessionCanonicalRealmCatalogRepository(gs.dsl(), gs.manager(), "dev");
    var launch =
        new GameSessionCanonicalInstanceLaunchRepository(gs.dsl(), gs.manager(), "dev", catalog);
    ObjectProvider<GameSessionCanonicalInstanceLaunchRepository> launches =
        mock(ObjectProvider.class);
    when(launches.getIfAvailable()).thenReturn(launch);
    var fixed =
        new RunOwnedCanonicalLaunchPreparationOwner(
            true,
            capability.toString(),
            receipt.toString(),
            controlled.capability().runId(),
            controlled.capability().composeProjectName(),
            gsTls,
            launches,
            "dev");
    var allocated = fixed.prepareFixedVerifiedFixtureLaunch();
    var actualLaunch = allocated.launch();
    var c = actualLaunch.catalog().snapshot();
    UUID version =
        UUID.fromString(
            controlled
                .sourceReceipt()
                .launchReadEvidence()
                .publishedReleaseBundle()
                .getCanonicalVersionId());
    String id = UUID.randomUUID().toString();
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            "dev",
            c.tenantId(),
            c.worldSlug(),
            c.realmId(),
            c.playableStateNamespaceId(),
            c.stateScope(),
            actualLaunch.gameInstanceUuid(),
            version,
            2L,
            c.catalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            id);
    var activation =
        new WorldCanonicalInstanceFixtureActivationRequest(
            UUID.randomUUID(),
            c.tenantId(),
            actualLaunch.gameInstanceUuid(),
            controlled.fixtureProvenanceDigest(),
            1L,
            0L);
    var request =
        new WorldFixtureInitialAdmissionHoldRequest(
            new WorldCanonicalInitialAdmissionHold.Request(
                "dev",
                c.tenantId(),
                c.worldSlug(),
                c.realmId(),
                c.playableStateNamespaceId(),
                c.stateScope(),
                actualLaunch.gameInstanceUuid(),
                version,
                2L,
                id,
                requestDigest,
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
                c.catalogRevision(),
                null),
            activation);

    var outboundWorld = new AtomicReference<GrpcWorldFixtureInitialAdmissionHoldClient>();
    var transport =
        new RunOwnedCanonicalInitialAdmissionOwner.WorldTransport() {
          @Override
          public HoldIdentity acquire(WorldFixtureInitialAdmissionHoldRequest exact) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(
                    gs.dsl()
                        .fetchSingle(
                            "SELECT state FROM game_session_canonical_initial_admission_intent "
                                + "WHERE initial_admission_request_id=?",
                            id)
                        .get(0, String.class))
                .isEqualTo("PENDING_HOLD");
            return outboundWorld.get().acquire(exact);
          }

          @Override
          public void verifyActive(
              WorldFixtureInitialAdmissionHoldRequest exact, HoldIdentity identity) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            outboundWorld.get().verifyActive(exact, identity);
          }
        };
    var gsRepository =
        new CanonicalFixtureInitialAdmissionRepository(
            gs.dsl(), fixed, launch, gs.manager(), transport);
    var gsOwner = new RunOwnedCanonicalInitialAdmissionOwner(fixed, gsRepository, transport);
    var allocationService =
        new GameSessionControlPlaneGrpcService(
            null, null, null, null, null, null, new SimpleMeterRegistry());
    allocationService.configureRunOwnedFixtureLaunchAssociationReadBoundary(fixed, true, "dev");
    var terminalService = new FixtureInitialAdmissionOwnerReadGrpcService(fixed, gsOwner);
    Server gsServer = server(pki, "gs", allocationService, terminalService);
    Server worldServer = null;
    RunOwnedCanonicalWorldLaunchEvidenceProvider worldSources = null;
    GrpcGameSessionCanonicalInitialAdmissionOwnerClient terminalClient = null;
    GrpcWorldFixtureInitialAdmissionHoldClient worldClient = null;
    try {
      var gsEndpoints = new ServiceEndpointsProperties();
      gsEndpoints.setGameSessionService("localhost:" + gsServer.getPort());
      var descriptors = mock(GameDesignClient.class);
      when(descriptors.getLaunchDescriptor(any()))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new GameDesignClient.LaunchDescriptorReadback(
                    controlled.sourceReceipt().launchReadEvidence().launchDescriptor(),
                    controlled.launchDescriptor());
              });
      worldSources =
          new RunOwnedCanonicalWorldLaunchEvidenceProvider(
              true,
              capability.toString(),
              receipt.toString(),
              controlled.capability().runId(),
              controlled.capability().composeProjectName(),
              worldTls,
              descriptors,
              gsEndpoints,
              new GrpcChannelFactory(),
              "dev");
      terminalClient =
          new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
              gsEndpoints, worldTls, new GrpcChannelFactory(), "dev");
      terminalClient.init();
      var source = worldSources;
      var terminals = terminalClient;
      var actualWorldOwner = new AtomicReference<WorldFixtureInitialAdmissionHoldOwner>();
      var acquireCalls = new AtomicInteger();
      var preparationFailure = new AtomicReference<RuntimeException>();
      var worldAdapter = new AtomicReference<WorldFixtureInitialAdmissionHoldGrpcService>();
      var lazyWorld =
          new WorldFixtureInitialAdmissionHoldServiceGrpc
              .WorldFixtureInitialAdmissionHoldServiceImplBase() {
            @Override
            public void acquireFixtureInitialAdmissionHold(
                FixtureInitialAdmissionHoldOperationRequest input,
                StreamObserver<FixtureInitialAdmissionHoldOperationResponse> observer) {
              acquireCalls.incrementAndGet();
              try {
                // The production fixed provider authenticates the actual incoming leaf before
                // preparation. The request is comparison-only and cannot choose another fixture.
                var evidence = source.requireAuthenticatedEvidence();
                var lookup = FixtureInitialAdmissionGrpcCodec.fromWorldRequest(input, false);
                if (!request.equals(lookup.request()))
                  throw new IllegalArgumentException("Fixture differs");
                if (actualWorldOwner.get() == null) {
                  var stored =
                      RunOwnedWorldFixedPresetResourcesPostgresIntegrationTest
                          .fixtureFirstOpenStorage(
                              world.dsl(), world.manager(), mapper, evidence, activation);
                  var holds =
                      new WorldFixtureInitialAdmissionHoldRepository(
                          world.dsl(), world.manager(), stored.associations());
                  var owner =
                      new WorldFixtureInitialAdmissionHoldOwner(
                          holds, source, stored.activation(), terminals);
                  actualWorldOwner.set(owner);
                  worldAdapter.set(new WorldFixtureInitialAdmissionHoldGrpcService(owner, "dev"));
                }
                worldAdapter.get().acquireFixtureInitialAdmissionHold(input, observer);
              } catch (RuntimeException failure) {
                preparationFailure.set(failure);
                observer.onError(
                    Status.FAILED_PRECONDITION
                        .withCause(failure)
                        .withDescription("Controlled fixture preparation unavailable")
                        .asRuntimeException());
              }
            }

            @Override
            public void readFixtureInitialAdmissionHoldIdentity(
                FixtureInitialAdmissionHoldOperationRequest input,
                StreamObserver<FixtureInitialAdmissionHoldOperationResponse> observer) {
              worldAdapter.get().readFixtureInitialAdmissionHoldIdentity(input, observer);
            }

            @Override
            public void readFixtureInitialAdmissionHoldState(
                FixtureInitialAdmissionHoldOperationRequest input,
                StreamObserver<FixtureInitialAdmissionHoldOperationResponse> observer) {
              worldAdapter.get().readFixtureInitialAdmissionHoldState(input, observer);
            }
          };
      worldServer = server(pki, "world", lazyWorld);
      var worldEndpoints = new ServiceEndpointsProperties();
      worldEndpoints.setWorldManagementService("localhost:" + worldServer.getPort());
      worldClient =
          new GrpcWorldFixtureInitialAdmissionHoldClient(
              worldEndpoints, gsTls, new GrpcChannelFactory(), "dev", worldLeaf);
      worldClient.init();
      outboundWorld.set(worldClient);

      net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof committed;
      try {
        committed = gsOwner.open(request);
      } catch (RuntimeException failure) {
        if (preparationFailure.get() != null) {
          throw new AssertionError(
              "Actual World fixture preparation failed", preparationFailure.get());
        }
        throw failure;
      }
      assertThat(committed.outcome())
          .isEqualTo(
              net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof.Outcome
                  .COMMITTED);
      assertThat(committed.pointerVersion()).isEqualTo(1L);
      assertThat(committed.auditEventId()).isPositive();
      var identity =
          new HoldIdentity(request.selector(), committed.holdId(), committed.holdFence());
      assertThat(worldClient.readIdentity(request)).isEqualTo(identity);
      assertThat(
              gs.dsl()
                  .fetchSingle(
                      "SELECT status,row_version FROM game_instances WHERE id=?",
                      actualLaunch.gameInstanceId())
                  .intoMap())
          .containsEntry("status", "RUNNING")
          .containsEntry("row_version", 1L);
      assertThat(
              gs.dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_initial_admission_attempt "
                          + "WHERE initial_admission_request_id=?",
                      id)
                  .get(0, String.class))
          .isEqualTo("COMMITTED");
      assertThat(gs.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isEqualTo(1);
      assertThat(gs.dsl().fetchCount(DSL.table("gameplay_admission_pointer_event"))).isEqualTo(1);
      assertThat(
              world
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM initial_admission_bind_hold WHERE hold_id=?",
                      identity.holdId())
                  .get(0, String.class))
          .isEqualTo("PENDING");

      assertThat(actualWorldOwner.get().settleFromAuthenticatedOwnerRead(request, identity))
          .isEqualTo(committed);
      assertThat(
              world
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM initial_admission_bind_hold WHERE hold_id=?",
                      identity.holdId())
                  .get(0, String.class))
          .isEqualTo("COMMITTED");
      assertThat(acquireCalls).hasValue(1);

      // Historical exact retry after mutable GS lifecycle change must not reacquire, verify
      // current World ACTIVE, or rewrite either owner's original result.
      gs.dsl()
          .execute(
              "UPDATE game_instances SET status='STOPPED',row_version=2 WHERE id=?",
              actualLaunch.gameInstanceId());
      var gsRows = rows(gs.dsl());
      var worldRows = rows(world.dsl());
      var restarted =
          new RunOwnedCanonicalInitialAdmissionOwner(
              fixed,
              new CanonicalFixtureInitialAdmissionRepository(
                  gs.dsl(), fixed, launch, gs.manager(), transport),
              transport);
      assertThat(restarted.open(request)).isEqualTo(committed);
      assertThat(worldClient.readIdentity(request)).isEqualTo(identity);
      assertThat(actualWorldOwner.get().settleFromAuthenticatedOwnerRead(request, identity))
          .isEqualTo(committed);
      assertThat(acquireCalls).hasValue(1);
      assertThat(rows(gs.dsl())).isEqualTo(gsRows);
      assertThat(rows(world.dsl())).isEqualTo(worldRows);

      // A different actual leaf with the same valid World URI is not the fixed recipient.
      try (var wrong =
          new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
              gsEndpoints,
              pki.clientProperties(temporary, pki.identity("wrong-world-leaf")),
              new GrpcChannelFactory(),
              "dev")) {
        wrong.init();
        assertThatThrownBy(() -> wrong.readFixture(request, identity, gsLeaf))
            .satisfies(
                error ->
                    assertThat(Status.fromThrowable(error).getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
      }
      Server wrongService = server(pki, "wrong-gs-service", terminalService);
      try {
        var wrongEndpoint = new ServiceEndpointsProperties();
        wrongEndpoint.setGameSessionService("localhost:" + wrongService.getPort());
        try (var wrong =
            new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
                wrongEndpoint, worldTls, new GrpcChannelFactory(), "dev")) {
          wrong.init();
          assertThatThrownBy(() -> wrong.readFixture(request, identity, gsLeaf))
              .satisfies(
                  error ->
                      assertThat(Status.fromThrowable(error).getCode())
                          .isEqualTo(Status.Code.UNAUTHENTICATED));
        }
      } finally {
        stop(wrongService);
      }
      try (var wrongPin =
          new GrpcWorldFixtureInitialAdmissionHoldClient(
              worldEndpoints, gsTls, new GrpcChannelFactory(), "dev", "0".repeat(64))) {
        wrongPin.init();
        assertThatThrownBy(() -> wrongPin.acquire(request))
            .satisfies(
                error ->
                    assertThat(Status.fromThrowable(error).getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
      }
      assertThat(acquireCalls).hasValue(1);
      assertThat(rows(gs.dsl())).isEqualTo(gsRows);
      assertThat(rows(world.dsl())).isEqualTo(worldRows);
    } finally {
      if (worldClient != null) worldClient.close();
      if (terminalClient != null) terminalClient.close();
      if (worldSources != null) worldSources.closeGameSessionFixtureClient();
      stop(worldServer);
      stop(gsServer);
    }
  }

  private static Database database(String prefix, boolean world) {
    String schema = prefix + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setSchema(schema);
    if (world) {
      WorldPostgresMigrationTestSupport.migration(dataSource, schema, null).migrate();
    } else {
      Path root = WorldPostgresMigrationTestSupport.repositoryRoot();
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .placeholders(Map.of("serviceSchema", schema))
          .locations(
              "filesystem:"
                  + root.resolve("services/game-session-service/src/main/resources/db/migration"),
              WorldPostgresMigrationTestSupport.sagaMigrations(root))
          .load()
          .migrate();
    }
    return new Database(
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
        new DataSourceTransactionManager(dataSource));
  }

  private static Map<String, List<String>> rows(DSLContext dsl) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    String schema = dsl.fetchSingle("SELECT current_schema()").get(0, String.class);
    for (String table :
        dsl.fetch("SELECT tablename FROM pg_tables WHERE schemaname=? ORDER BY tablename", schema)
            .getValues(0, String.class)) {
      result.put(
          table,
          new ArrayList<>(
              dsl.fetch(
                      "SELECT to_jsonb(t)::text||':'||t.xmin::text FROM "
                          + DSL.name(schema, table)
                          + " t ORDER BY 1")
                  .getValues(0, String.class)));
    }
    return result;
  }

  private static Server server(
      LoopbackMtlsTestPki.Pki pki, String alias, BindableService... services) throws Exception {
    var identity = pki.identity(alias);
    var builder =
        NettyServerBuilder.forPort(0)
            .maxInboundMessageSize(FixtureInitialAdmissionGrpcCodec.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
    for (var service : services) {
      builder.addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()));
    }
    return builder.build().start();
  }

  private static void stop(Server server) throws InterruptedException {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static Path immutable(Path path, byte[] bytes) throws Exception {
    Files.write(path, bytes);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"));
    return path;
  }

  private static String digest(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record Database(DSLContext dsl, DataSourceTransactionManager manager) {}
}
