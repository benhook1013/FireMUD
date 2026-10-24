package net.firedevops.firemud.gamesession.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.charactercreate.ControlledPlayerCreatedOwnerStoreFixture;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterSourceClient;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy;
import net.firedevops.firemud.entitymanagement.testing.ControlledPlayerCreatedRosterSourceFixture;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository.SelectionOutcome;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayRosterOwnerReadGrpcAdapter;
import net.firedevops.firemud.gamesession.testing.ControlledPlayerCreatedOriginalOwnerFixture;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicSourceServiceGrpc;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real owner stores and authenticated transports with explicitly controlled upstream authority.
 * This does not establish genuine Account ingress, production PKI, registration or player
 * admission, nor the production five-second RPC latency budget.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class PlayerCreatedRosterOwnerCompositionIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final Duration RETENTION = Duration.ofSeconds(20);
  private static final long COUPLED_OWNER_TEST_RPC_SECONDS = 30L;
  private static final Set<String> COUPLED_OWNER_TEST_RPCS =
      Set.of(
          CanonicalGameplayRosterServiceGrpc.SERVICE_NAME + "/ListPlayerCreatedRoster",
          CanonicalGameplayRosterServiceGrpc.SERVICE_NAME + "/ReadSelectedPlayerCreatedCharacter",
          CharacterCreatePublicSourceServiceGrpc.SERVICE_NAME + "/ReadPlayerCreatedRosterSource",
          CanonicalGameplayRosterOwnerReadServiceGrpc.SERVICE_NAME
              + "/GetCanonicalGameplayRosterReference");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @TempDir Path directory;

  @Test
  void actualCreatedTerminalCrossesAuthenticatedOwnersAndReplaysTheImmutableOriginal()
      throws Exception {
    try (var owners = new Owners(directory)) {
      UUID account = UUID.randomUUID();
      UUID creation = UUID.randomUUID();
      String displayName =
          new net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection
                  .AuthoredCore(
                  owners
                      .gs
                      .wireFixture()
                      .authored()
                      .projections()
                      .getFirst()
                      .core()
                      .canonicalJson())
              .displayName();
      var terminal = owners.entity.createActor(account, creation, displayName);
      assertThat(terminal.getOutcome())
          .isEqualTo(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED);
      var source =
          owners
              .gs
              .sourceReader()
              .resolvePlayerCreatedRosterSource(owners.gs.wireFixture().request());
      var context = owners.context(account);
      owners.roster.reserveSelectionInput(source, context, null, null, null);
      var menu = owners.roster.listPlayerCreatedRoster(source, context);
      assertThat(menu.actors()).hasSize(1);
      assertThat(menu.actors().getFirst().getDisplayName()).isEqualTo(displayName);
      assertThat(menu.actors().getFirst().getCharacterUuid()).isEqualTo(terminal.getCharacterId());
      var selectedContext = context.toBuilder().setCharacterId(terminal.getCharacterId()).build();
      var selected = owners.roster.readSelectedPlayerCreatedCharacter(menu, selectedContext);
      assertThat(selected.creationOperationUuid()).isEqualTo(creation);
      assertThat(selected.terminal()).isEqualTo(terminal);
      owners.roster.completeSelection(source, context, SelectionOutcome.SELECTED);

      var retained = owners.gs.referenceOwner().read(menu.originalSelection());
      assertThat(CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(retained))
          .isEqualTo(menu.response());
      assertThat(
              CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(
                  retained))
          .isEqualTo(selected.response());
      var originalBytes = retained.toByteArray();
      // Actual current source is still the issued target: retirement must refuse.
      assertThat(owners.gs.referenceOwner().retireTarget(menu.originalSelection())).isFalse();
      var replay = owners.roster.listPlayerCreatedRoster(source, context);
      assertThat(replay.response()).isEqualTo(menu.response());
      assertThat(
              owners.roster.readSelectedPlayerCreatedCharacter(replay, selectedContext).response())
          .isEqualTo(selected.response());
      assertThat(owners.gs.referenceOwner().read(menu.originalSelection()).toByteArray())
          .containsExactly(originalBytes);

      // A caller-selected actor absent from the actual frozen menu cannot become a receipt.
      assertThatThrownBy(
              () ->
                  owners.roster.readSelectedPlayerCreatedCharacter(
                      replay,
                      context.toBuilder().setCharacterId(UUID.randomUUID().toString()).build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(owners.gs.referenceOwner().read(menu.originalSelection()).toByteArray())
          .containsExactly(originalBytes);
      var foreignContext =
          context.toBuilder()
              .setRequestId(UUID.randomUUID().toString())
              .setRealmId(UUID.randomUUID().toString())
              .build();
      assertThatThrownBy(() -> owners.roster.listPlayerCreatedRoster(source, foreignContext))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(owners.gs.retainedOriginalReferenceCount()).isEqualTo(1);
      assertThat(
              owners.entityDsl.fetchCount(DSL.table("entity_canonical_roster_original_selections")))
          .isEqualTo(1);
    }
  }

  private static final class Owners implements AutoCloseable {
    private final ArrayDeque<AutoCloseable> resources = new ArrayDeque<>();
    private final DriverManagerDataSource gsData;
    private final DriverManagerDataSource entityData;
    private final org.jooq.DSLContext entityDsl;
    private final ControlledPlayerCreatedOriginalOwnerFixture gs;
    private final ControlledPlayerCreatedOwnerStoreFixture entity;
    private final CanonicalPlayerCreatedRosterClient roster;

    private Owners(Path directory) throws Exception {
      gsData = dataSource("created_owner_gs_" + UUID.randomUUID().toString().replace("-", ""));
      entityData =
          dataSource("created_owner_entity_" + UUID.randomUUID().toString().replace("-", ""));
      try {
        var pki =
            LoopbackMtlsTestPki.create(
                Files.createDirectories(directory.resolve("peers")),
                "Created owner composition test CA",
                new LoopbackMtlsTestPki.IdentitySpec(
                    "gs-client", uri("game-session-service"), false),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "gs-server", uri("game-session-service"), true),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "entity-client", uri("entity-management-service"), false),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "entity-server", uri("entity-management-service"), true));
        var endpoints = new ServiceEndpointsProperties();
        var gsTls = pki.clientProperties(directory, pki.identity("gs-client"));
        var entityTls = pki.clientProperties(directory, pki.identity("entity-client"));
        var recovery =
            own(
                new CanonicalGameplayRosterRecoveryClient(
                    endpoints, gsTls, new GrpcChannelFactory(), NAMESPACE));
        var initial =
            new ControlledPlayerCreatedRosterSourceFixture(
                    Files.createDirectories(directory.resolve("upstream")))
                .wireFixture();
        gs =
            new ControlledPlayerCreatedOriginalOwnerFixture(
                gsData,
                initial,
                recovery,
                Clock.systemUTC(),
                new CanonicalGameplayRosterReferencePolicy(
                    RETENTION, RETENTION, RETENTION, Duration.ofSeconds(1), 20));
        var beans = new StaticListableBeanFactory();
        beans.addBean("source", gs.publishedRouteReader());
        beans.addBean("reference", gs.referenceOwner());
        var gsServer =
            server(
                pki,
                "gs-server",
                new GameSessionCharacterCreatePublicSourceGrpcService(gs.sourceReader(), NAMESPACE),
                new CanonicalGameplayRosterOwnerReadGrpcAdapter(
                    beans.getBeanProvider(
                        net.firedevops.firemud.gamesession.service
                            .CanonicalPublishedPlayerRouteReadService.class),
                    NAMESPACE,
                    beans.getBeanProvider(
                        net.firedevops.firemud.gamesession.service
                            .CanonicalGameplayRosterReferenceOwner.class)));
        endpoints.setGameSessionService("127.0.0.1:" + gsServer.getPort());
        var sourceClient =
            own(
                new EntityPlayerCreatedRosterSourceClient(
                    endpoints, entityTls, coupledOwnerTestChannels(), NAMESPACE));
        sourceClient.init();
        var originalClient =
            own(
                new GameSessionCanonicalGameplayRosterOwnerReadClient(
                    endpoints, entityTls, coupledOwnerTestChannels(), NAMESPACE));
        originalClient.init();
        var migration =
            Flyway.configure()
                .dataSource(entityData)
                .locations(
                    "filesystem:"
                        + Path.of("../entity-management-service/src/main/resources/db/migration")
                            .toAbsolutePath()
                            .normalize(),
                    "classpath:db/migration/saga")
                .schemas(entityData.getSchema())
                .defaultSchema(entityData.getSchema())
                .placeholders(Map.of("serviceSchema", entityData.getSchema()));
        migration
            .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
            .setTransactionalLock(false);
        migration.load().migrate();
        entityDsl = DSL.using(new TransactionAwareDataSourceProxy(entityData), SQLDialect.POSTGRES);
        entity =
            new ControlledPlayerCreatedOwnerStoreFixture(
                entityDsl,
                new DataSourceTransactionManager(entityData),
                NAMESPACE,
                gs.wireFixture(),
                sourceClient,
                originalClient,
                Clock.systemUTC(),
                new CanonicalGameplayRosterRetentionPolicy(
                    RETENTION, RETENTION, RETENTION, Duration.ofSeconds(1), 20));
        var entityServer =
            server(pki, "entity-server", entity.rosterAdapter(), entity.recoveryAdapter());
        endpoints.setEntityManagementService("127.0.0.1:" + entityServer.getPort());
        recovery.init();
        roster =
            own(
                new CanonicalPlayerCreatedRosterClient(
                    endpoints, gsTls, coupledOwnerTestChannels(), NAMESPACE, gs.referenceOwner()));
        roster.init();
      } catch (Exception failure) {
        try {
          close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    private PlayerExecutionContext context(UUID account) {
      var route = gs.routeReader().readCurrentTarget(gs.route().canonicalTenantId());
      return PlayerExecutionContext.newBuilder()
          .setAccountId(account.toString())
          .setTenantId(route.canonicalTenantId().toString())
          .setPlayableStateNamespaceId(route.playableStateNamespaceId().toString())
          .setGameInstanceId(route.canonicalGameInstanceId().toString())
          .setSessionId(UUID.randomUUID().toString())
          .setRealmId(route.realmId().toString())
          .setRequestId(UUID.randomUUID().toString())
          .setPlayableStateScope(route.playableStateScope())
          .build();
    }

    private <T extends AutoCloseable> T own(T resource) {
      resources.push(resource);
      return resource;
    }

    private Server server(LoopbackMtlsTestPki.Pki pki, String alias, BindableService... services)
        throws Exception {
      var identity = pki.identity(alias);
      var builder =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .maxInboundMessageSize(
                  net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence
                      .MAX_PLAYER_CREATED_CONFIRMATION_BYTES)
              .sslContext(
                  GrpcSslContexts.configure(
                          SslContextBuilder.forServer(
                              identity.privateKey(), identity.certificate()))
                      .trustManager(pki.caCertificate())
                      .clientAuth(ClientAuth.REQUIRE)
                      .build());
      for (var service : services)
        builder.addService(
            ServerInterceptors.intercept(
                service,
                new net.firedevops.firemud.entitymanagement.security
                    .CanonicalGameplayRosterPeerInterceptor(NAMESPACE),
                new GrpcPeerIdentityInterceptor()));
      var server = builder.build().start();
      own(() -> server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
      return server;
    }

    @Override
    public void close() throws Exception {
      Exception failure = null;
      while (!resources.isEmpty()) {
        try {
          resources.pop().close();
        } catch (Exception cleanup) {
          if (failure == null) failure = cleanup;
          else failure.addSuppressed(cleanup);
        }
      }
      var admin = DSL.using(dataSource(null), SQLDialect.POSTGRES);
      admin.execute("DROP SCHEMA IF EXISTS \"" + gsData.getSchema() + "\" CASCADE");
      admin.execute("DROP SCHEMA IF EXISTS \"" + entityData.getSchema() + "\" CASCADE");
      if (failure != null) throw failure;
    }
  }

  /**
   * Keep real network, TLS and peer validation while allowing cold physical owner composition a
   * bounded test-only budget. There are no retries; all other RPC call options remain unchanged.
   */
  private static GrpcChannelFactory coupledOwnerTestChannels() {
    return new GrpcChannelFactory() {
      @Override
      public ManagedChannel buildChannel(
          String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
          throws SSLException {
        var transport = super.buildChannel(target, defaultPort, properties, keepAlive);
        return new ManagedChannel() {
          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions options) {
            var testOptions =
                COUPLED_OWNER_TEST_RPCS.contains(method.getFullMethodName())
                    ? options.withDeadlineAfter(COUPLED_OWNER_TEST_RPC_SECONDS, TimeUnit.SECONDS)
                    : options;
            return transport.newCall(method, testOptions);
          }

          @Override
          public String authority() {
            return transport.authority();
          }

          @Override
          @SuppressFBWarnings(
              value = "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT",
              justification =
                  "ManagedChannel.shutdown initiates shutdown; returning this preserves the wrapper's fluent identity.")
          public ManagedChannel shutdown() {
            transport.shutdown();
            return this;
          }

          @Override
          @SuppressFBWarnings(
              value = "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT",
              justification =
                  "ManagedChannel.shutdownNow initiates shutdown; returning this preserves the wrapper's fluent identity.")
          public ManagedChannel shutdownNow() {
            transport.shutdownNow();
            return this;
          }

          @Override
          public boolean isShutdown() {
            return transport.isShutdown();
          }

          @Override
          public boolean isTerminated() {
            return transport.isTerminated();
          }

          @Override
          public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return transport.awaitTermination(timeout, unit);
          }
        };
      }
    };
  }

  private static DriverManagerDataSource dataSource(String schema) {
    var data =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    data.setSchema(schema);
    return data;
  }

  private static String uri(String service) {
    return "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service;
  }
}
