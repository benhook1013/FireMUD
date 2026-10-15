package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.common.account.sourceintake.GrpcSelectedOwnerIntakeSourceReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GrpcSelectedOwnerIntakeSourceClient;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignReviewedBaseRepository;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignSelectedOwnerIntakeSourceGrpcService;
import net.firedevops.firemud.gamedesign.publication.GameDesignSelectedOwnerIntakeSourceReadService;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual Account V1007 preliminary permission and independent GD six-family disclosure owners.
 * Creator, environment, PKI, selected author admission/readbacks and authored inputs are
 * controlled; no final intake authorization, recipient retention, genuine provenance or
 * registration is proved.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class SelectedOwnerSourceDisclosurePostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> GDS =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> ACCOUNT =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("selected-disclosure-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> REPLICA =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(REDIS)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "selected-disclosure-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void actualPreliminaryPermissionDisclosesExactSixFamiliesAndAbortPermanentlyRefuses()
      throws Exception {
    var local = sourceStore();
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            ACCOUNT.getJdbcUrl(),
            ACCOUNT.getUsername(),
            ACCOUNT.getPassword(),
            REDIS.getHost(),
            REDIS.getMappedPort(6379),
            temporary.resolve("account"),
            local.binding().target().canonicalTenantId(),
            Clock.systemUTC(),
            null)) {
      var issued = fixture.preparedOriginalCreator();
      var f = issued.sources();
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT count(*) FROM flyway_schema_history WHERE version IN ('1008','1009','1010')")
                  .get(0, Integer.class))
          .isEqualTo(3);
      retainControlledSelectedAuthorAdmission(issued, local.binding());
      var reservations =
          new AccountSelectedOwnerIntakeSourceReservationService(
              issued.actors(),
              f.fences,
              new AccountSelectedOwnerIntakeSourceReservationRepository(f.dsl),
              f.manager,
              NAMESPACE);
      var pki = new GenuineWorldSourceProofPki(temporary.resolve("pki"));
      var endpoints = new ServiceEndpointsProperties();
      Server accountServer = null;
      Server sourceServer = null;
      try {
        accountServer =
            server(
                pki,
                "account-service",
                new AccountSelectedOwnerIntakeSourceReadGrpcService(
                    fixture.selectedOwnerIntakeSourceReadService(NAMESPACE), NAMESPACE));
        endpoints.setAccountService("127.0.0.1:" + accountServer.getPort());
        try (var permissions =
            new GrpcSelectedOwnerIntakeSourceReadClient(
                endpoints,
                pki.client("game-design-service"),
                new GrpcChannelFactory(),
                NAMESPACE)) {
          permissions.init();
          var permissionCalls = new AtomicInteger();
          var sourceOwner =
              new GameDesignSelectedOwnerIntakeSourceReadService(
                  new GameDesignSourceRepository(local.dsl()),
                  local.manager(),
                  request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                    assertThat(TransactionSynchronizationManager.isSynchronizationActive())
                        .isFalse();
                    permissionCalls.incrementAndGet();
                    return permissions.read(request);
                  },
                  NAMESPACE);
          sourceServer =
              server(
                  pki,
                  "game-design-service",
                  new GameDesignSelectedOwnerIntakeSourceGrpcService(sourceOwner, NAMESPACE));
          endpoints.setGameDesignService("127.0.0.1:" + sourceServer.getPort());
          try (var sources =
                  new GrpcSelectedOwnerIntakeSourceClient(
                      endpoints,
                      pki.client("account-service"),
                      new GrpcChannelFactory(),
                      NAMESPACE);
              var wrongCaller =
                  new GrpcSelectedOwnerIntakeSourceClient(
                      endpoints,
                      pki.client("game-design-service"),
                      new GrpcChannelFactory(),
                      NAMESPACE)) {
            sources.init();
            wrongCaller.init();
            for (var owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
              UUID requestId = UUID.randomUUID();
              var scope =
                  asGameDesign(
                      () ->
                          reservations.reserveSourceRead(
                              issued.compact(),
                              requestId,
                              owner,
                              local.binding(),
                              issued.environment()));
              var retained = retainedReservation(f, scope);
              var request = SelectedOwnerIntakeSourceReadEvidence.Request.create(NAMESPACE, scope);
              var content = sources.read(request);
              var expected =
                  local
                      .tx()
                      .execute(
                          ignored ->
                              new GameDesignSourceRepository(local.dsl())
                                  .requireSelectedOwnerIntakeSource(scope));
              assertThat(content.canonicalBytes()).isEqualTo(expected.canonicalBytes());
              assertThat(content.digest()).isEqualTo(expected.digest());
              for (String family :
                  List.of(
                      "COMMAND",
                      "REALM_POLICY",
                      "ASSET",
                      "GAMEPLAY_RULE",
                      "BRANDING",
                      "TEMPLATE_CONFIG")) assertThat(content.snapshotBytes(family)).isNotEmpty();
              assertThat(
                      sources
                          .read(
                              SelectedOwnerIntakeSourceReadEvidence.Request.create(
                                  NAMESPACE, scope))
                          .canonicalBytes())
                  .isEqualTo(content.canonicalBytes());
              assertThat(
                      asGameDesign(
                              () ->
                                  reservations.reserveSourceRead(
                                      issued.compact(),
                                      requestId,
                                      owner,
                                      local.binding(),
                                      issued.environment()))
                          .canonicalBytes())
                  .isEqualTo(scope.canonicalBytes());
              assertThat(retainedReservation(f, scope)).isEqualTo(retained);
              int beforeWrongPeer = permissionCalls.get();
              assertThatThrownBy(() -> wrongCaller.read(request))
                  .isInstanceOf(io.grpc.StatusRuntimeException.class);
              assertThat(permissionCalls).hasValue(beforeWrongPeer);
              var substituted =
                  new SelectedOwnerIntakeSourceReadScope(
                      owner,
                      NAMESPACE,
                      UUID.randomUUID(),
                      scope.fenceId(),
                      scope.intakeRequestId(),
                      scope.actorAccountId(),
                      scope.selected());
              assertThatThrownBy(
                      () ->
                          sources.read(
                              SelectedOwnerIntakeSourceReadEvidence.Request.create(
                                  NAMESPACE, substituted)))
                  .isInstanceOf(io.grpc.StatusRuntimeException.class);
              assertThat(retainedReservation(f, scope)).isEqualTo(retained);
              var capturedSources =
                  f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()));
              var blockedWriter =
                  new net.firedevops.firemud.accountservice.authordraft
                      .DraftAuthorizationFenceRepository.SourceChange(
                      UUID.randomUUID(), capturedSources.sources(), new byte[] {7});
              assertThat(f.<Boolean>tx(() -> f.fences.requestSourceChange(blockedWriter)))
                  .isFalse();
              assertThat(f.<Boolean>tx(() -> f.fences.sourceMutationPermitted(blockedWriter)))
                  .isFalse();
              assertThat(f.<Boolean>tx(() -> f.fences.sourceAbortPermitted(blockedWriter)))
                  .isFalse();
              assertThat(asGameDesign(() -> reservations.abortSourceRead(scope)).state().name())
                  .isEqualTo("ABORTED");
              assertThat(f.<Boolean>tx(() -> f.fences.sourceMutationPermitted(blockedWriter)))
                  .isTrue();
              f.tx(
                  () -> {
                    f.fences.markSourceAborted(
                        blockedWriter,
                        net.firedevops.firemud.accountservice.authordraft
                            .DraftAuthorizationFenceRepository.SourceChangeAbortReason
                            .DEFINITIVE_ABORT);
                    return null;
                  });
              var aborted = retainedReservation(f, scope);
              assertThatThrownBy(() -> sources.read(request))
                  .isInstanceOf(io.grpc.StatusRuntimeException.class);
              assertThatThrownBy(
                      () ->
                          asGameDesign(
                              () ->
                                  reservations.reserveSourceRead(
                                      issued.compact(),
                                      requestId,
                                      owner,
                                      local.binding(),
                                      issued.environment())))
                  .isInstanceOf(io.grpc.StatusRuntimeException.class);
              asGameDesign(() -> reservations.abortSourceRead(scope));
              assertThat(retainedReservation(f, scope)).isEqualTo(aborted);
              assertThat(
                      local
                          .tx()
                          .execute(
                              ignored ->
                                  new GameDesignSourceRepository(local.dsl())
                                      .requireSelectedOwnerIntakeSource(scope))
                          .canonicalBytes())
                  .isEqualTo(content.canonicalBytes());
            }
          }
        }
      } finally {
        for (var server : new Server[] {sourceServer, accountServer})
          if (server != null) server.shutdownNow();
        for (var server : new Server[] {sourceServer, accountServer})
          if (server != null) assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static String retainedReservation(
      AccountControlUiOwnerSourcesFixture f, SelectedOwnerIntakeSourceReadScope scope) {
    return f.dsl
        .fetchSingle(
            "SELECT row_to_json(r)::TEXT || ':' || xmin::TEXT FROM account_selected_owner_intake_source_read_reservations r WHERE operation_id = ?",
            scope.operationId())
        .get(0, String.class);
  }

  /** Controlled upstream terminal readbacks; the preliminary permission itself is never mocked. */
  private static void retainControlledSelectedAuthorAdmission(
      AccountControlUiOriginalOrderFixture.IssuedCreator issued, DraftCommitBinding selected) {
    var f = issued.sources();
    var captured = f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()));
    var original =
        new DraftAuthorizationFenceBinding(
                UUID.randomUUID(),
                selected.requestId(),
                selected.commitId(),
                UUID.randomUUID(),
                f.account.getAccountUuid(),
                f.tenant,
                selected.target().canonicalVersionId(),
                selected.baseCommitId(),
                "0",
                selected.canonicalBytes(),
                selected.canonicalBytes(),
                selected.digest(),
                captured.sources())
            .withRequiredOwners();
    issued.actors().claimOriginalDraft(issued.compact(), original, issued.environment());
    f.tx(
        () -> {
          for (var owner : original.requiredOwners())
            f.fences.recordOwnerReadback(
                original,
                new DraftAuthorizationFenceBinding.OwnerReadback(
                    owner,
                    DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                    original.operationId(),
                    original.commitId(),
                    original.fenceId(),
                    original.inputDigest(),
                    original.canonicalBytes(),
                    new byte[] {1}));
          return null;
        });
  }

  private static Local sourceStore() {
    String schema = "selected_disclosure_" + UUID.randomUUID().toString().replace("-", "");
    var data = new DriverManagerDataSource(GDS.getJdbcUrl(), GDS.getUsername(), GDS.getPassword());
    data.setSchema(schema);
    Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(data);
    var tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("Controlled selected source disclosure");
    var savedGame = tx.execute(ignored -> new GameRepository(dsl).save(game));
    var version = new Version();
    version.setTenantId(savedGame.getTenantId());
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    var saved = tx.execute(ignored -> new VersionRepository(dsl).save(version));
    var target =
        new DraftCommitBinding.TargetProof(
            saved.getCanonicalTenantId(),
            saved.getCanonicalVersionId(),
            saved.getId(),
            saved.getTenantId(),
            saved.getIdentitySourceGameRowId(),
            saved.getIdentitySourceGameTenantKey(),
            saved.getIdentitySourceProvenanceKind());
    var policyGenesis =
        new net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository(dsl)
            .readGenesis(target)
            .orElseThrow();
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis:" + policyGenesis.receiptId(),
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    CommandSource.upsertPayload(
                        "{\"schemaVersion\":1,\"commandId\":\"DISCLOSURE\",\"semanticOwner\":\"WORLD\",\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\",\"promptPolicy\":\"NEVER\",\"actionCategory\":\"GAMEPLAY\",\"historyRecordable\":true,\"aliases\":[],\"actionTags\":[\"WORLD_BROWSE\"],\"effects\":[]}"))),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    CommandSource.SCOPE,
                    target.canonicalVersionId().toString(),
                    CommandSource.SCOPE,
                    "effective",
                    "0")));
    tx.executeWithoutResult(
        ignored -> {
          var source = new GameDesignSourceRepository(dsl);
          var reviewed =
              new GameDesignReviewedBaseRepository(dsl)
                  .resolve(target, DraftBaseReference.parse(binding.baseCommitId()));
          source.claimReviewed(binding, reviewed);
          var coordinator = new DraftCommitCoordinatorRepository(dsl);
          coordinator.claimApplicationSlot(binding);
          coordinator.markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
          var applied = source.apply(binding);
          IsolatedPublicationOwnerSetup.advanceIsolatedSourceVisibility(
              dsl, binding, List.of(applied.ownerOutcome()));
          source.captureSynchronized(binding);
          coordinator.releaseApplicationSlot(binding);
        });
    return new Local(dsl, manager, tx, binding);
  }

  private static Server server(
      GenuineWorldSourceProofPki pki, String service, io.grpc.BindableService handler)
      throws Exception {
    var identity = pki.server(service);
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                .trustManager(pki.ca().toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(handler, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static <T> T asGameDesign(Supplier<T> action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri(
                        "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-design-service")
                    .orElseThrow());
    var previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private record Local(
      DSLContext dsl,
      DataSourceTransactionManager manager,
      TransactionTemplate tx,
      DraftCommitBinding binding) {}
}
