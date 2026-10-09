package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.JsonFormat;
import io.grpc.BindableService;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.util.MutableHandlerRegistry;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadGrpcService;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderClient;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadClient;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadEvidence;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityClient;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyClient;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOperation;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOutcome;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOutcomeRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalReadGrpcService;
import net.firedevops.firemud.gamedesign.draft.GameDesignWorldSourceCommitService;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSource;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateService;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftCommitOrderVerifier;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphAppliedResult;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcomeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalDraftGraphApplicationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalDraftGraphApplyGrpcService;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Actual owner mutations and strict loopback mTLS; no runtime registration or activation proof. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GenuineGameDesignWorldSourceCommitPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> GD =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> WORLD =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> ACCOUNT =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("genuine-world-primary")
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
              "genuine-world-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void genuineBothOwnerApplicationAndAuthenticatedTerminalSettlement() throws Exception {
    compose(Failure.NONE);
  }

  @Test
  void lostWorldAcknowledgementRecoversOriginalApplicationWithoutDuplicateMutation()
      throws Exception {
    compose(Failure.LOST_ACKNOWLEDGEMENT);
  }

  @Test
  void unknownWorldOutcomeRetainsSlotAndInvisibilityUntilExactRecovery() throws Exception {
    compose(Failure.UNAVAILABLE);
  }

  private void compose(Failure failure) throws Exception {
    var gd = gameDesign();
    var world = store(WORLD, "world-management");
    var pki = new GenuineWorldSourceProofPki(temporary.resolve("pki"));
    var gdHandlers = new MutableHandlerRegistry();
    var worldHandlers = new MutableHandlerRegistry();
    var accountHandlers = new MutableHandlerRegistry();
    // Bind all ports before any client captures endpoint addresses.
    Server gdServer = server(pki, "game-design-service", gdHandlers);
    Server worldServer = server(pki, "world-management-service", worldHandlers);
    Server accountServer = server(pki, "account-service", accountHandlers);
    try (var account =
        new AccountControlUiOriginalOrderFixture(
            ACCOUNT.getJdbcUrl(),
            ACCOUNT.getUsername(),
            ACCOUNT.getPassword(),
            REDIS.getHost(),
            REDIS.getMappedPort(6379),
            temporary.resolve("account"),
            gd.target().canonicalTenantId())) {
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameDesignService("localhost:" + gdServer.getPort());
      endpoints.setWorldManagementService("localhost:" + worldServer.getPort());
      endpoints.setAccountService("localhost:" + accountServer.getPort());
      var factory = new GrpcChannelFactory();
      var sourceRepository = new GameAuthoredWorldSourceRepository(gd.store().dsl());
      register(
          gdHandlers,
          new TenantIdentityGrpcService(
              new GameTenantCreationRepository(
                  gd.store().dsl(), new GameRepository(gd.store().dsl())),
              sourceRepository,
              NAMESPACE),
          new AuthoredWorldVersionStateGrpcService(
              new AuthoredWorldVersionStateService(gd.store().transactions(), sourceRepository),
              NAMESPACE));
      var source =
          Objects.requireNonNull(
              transaction(gd.store())
                  .execute(
                      ignored ->
                          sourceRepository.register(
                              NAMESPACE,
                              UUID.randomUUID(),
                              gd.target().canonicalTenantId(),
                              "genuine-tenant",
                              "genuine-world",
                              "Genuine authored World")));
      var intakes = new WorldAuthoredSourceIntakeRepository(world.dsl());
      var identities = new WorldAuthoredVersionIdentityRepository(world.dsl());
      try (var sourceClient =
              new AuthoredWorldSourceClient(
                  endpoints, pki.client("world-management-service"), factory, NAMESPACE);
          var versionClient =
              new AuthoredWorldVersionStateClient(
                  endpoints, pki.client("world-management-service"), factory, NAMESPACE);
          var intakeClient =
              new WorldAuthoredSourceIntakeClient(
                  endpoints, pki.client("game-design-service"), factory, NAMESPACE);
          var associationClient =
              new WorldAuthoredVersionIdentityClient(
                  endpoints, pki.client("game-design-service"), factory, NAMESPACE);
          var accountClient =
              new AccountOriginalDraftOrderClient(
                  endpoints, pki.client("game-design-service"), factory, NAMESPACE);
          var heldClient =
              new DraftCommitOrderReadClient(
                  endpoints, pki.client("world-management-service"), factory, NAMESPACE);
          var worldClient =
              new WorldOriginalDraftGraphApplyClient(
                  endpoints, pki.client("game-design-service"), factory, NAMESPACE);
          var gdTerminalClient =
              new GameDesignDraftTerminalReadClient(
                  endpoints, pki.client("account-service"), factory, NAMESPACE);
          var worldTerminalClient =
              new WorldDraftTerminalReadClient(
                  endpoints, pki.client("account-service"), factory, NAMESPACE)) {
        sourceClient.init();
        versionClient.init();
        var intakeService =
            new WorldAuthoredSourceIntakeService(
                sourceClient, intakes, world.transactions(), NAMESPACE);
        var identityService =
            new WorldAuthoredVersionIdentityService(
                versionClient, identities, intakes, world.transactions(), NAMESPACE);
        register(
            worldHandlers,
            new WorldAuthoredSourceIntakeGrpcService(intakeService, NAMESPACE),
            new WorldAuthoredVersionIdentityGrpcService(identityService, NAMESPACE));
        intakeClient.init();
        associationClient.init();
        var intakeRequest =
            new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
                1,
                NAMESPACE,
                UUID.randomUUID(),
                source.canonicalTenantId(),
                source.worldSlug(),
                source.operationId(),
                source.evidenceDigest());
        var publicIntake = intakeClient.intake(intakeRequest);
        var intake = intakes.read(NAMESPACE, intakeRequest.intakeRequestId()).orElseThrow();
        assertThat(publicIntake.receiptDigest()).isEqualTo(intake.receiptDigest());
        UUID intakeReadRequestId = UUID.randomUUID();
        while (intakeReadRequestId.equals(intake.intakeRequestId())) {
          intakeReadRequestId = UUID.randomUUID();
        }
        var publicIntakeReadback =
            intakeClient.readById(
                new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                    1,
                    NAMESPACE,
                    intakeReadRequestId,
                    intake.intakeRequestId(),
                    intake.canonicalTenantId()));
        var gameDesignSourceReadback =
            sourceRepository
                .read(
                    source.operationId(), source.canonicalTenantId(), source.worldSlug(), NAMESPACE)
                .orElseThrow();
        assertThat(gameDesignSourceReadback).isEqualTo(source);
        assertThat(publicIntakeReadback.source()).isEqualTo(gameDesignSourceReadback);
        assertThat(publicIntakeReadback)
            .isEqualTo(
                new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
                    intake.schemaVersion(),
                    intake.targetNamespace(),
                    intake.intakeRequestId(),
                    intake.operationId(),
                    intake.canonicalTenantId(),
                    intake.worldSlug(),
                    intake.sourceOperationId(),
                    intake.sourceEvidenceDigest(),
                    intake.requestDigest(),
                    intake.receiptDigest(),
                    gameDesignSourceReadback));
        var associationRequest =
            new WorldAuthoredVersionIdentityEvidence.Request(
                1,
                NAMESPACE,
                source.canonicalTenantId(),
                source.worldSlug(),
                source.operationId(),
                source.evidenceDigest(),
                gd.target().canonicalVersionId(),
                gd.target().gameDesignVersionRowId(),
                UUID.randomUUID());
        var publicAssociation = associationClient.associate(associationRequest);
        assertThat(publicAssociation.request()).isEqualTo(associationRequest);
        assertThat(publicAssociation.sourceIntakeReceipt()).isEqualTo(publicIntake);
        assertThat(publicAssociation.versionStateEvidence().request())
            .isEqualTo(associationRequest.versionReadRequest());
        assertThat(publicAssociation.versionStateEvidence().sourceEvidence()).isEqualTo(source);
        assertThat(publicAssociation.versionStateEvidence().canonicalVersionId())
            .isEqualTo(gd.target().canonicalVersionId());
        assertThat(publicAssociation.versionStateEvidence().versionState())
            .isEqualTo(
                net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                    .VERSION_LIFECYCLE_STATE_DRAFT);
        var identity =
            identities
                .readByCanonicalTarget(
                    NAMESPACE,
                    source.canonicalTenantId(),
                    gd.target().canonicalVersionId(),
                    gd.target().gameDesignVersionRowId())
                .orElseThrow();
        assertThat(identity.operationId()).isEqualTo(publicAssociation.operationId());
        assertThat(identity.canonicalVersionId()).isEqualTo(gd.target().canonicalVersionId());
        assertThat(identity.gameDesignVersionId()).isEqualTo(gd.target().gameDesignVersionRowId());
        assertThat(identity.sourceIntakeReceipt()).isEqualTo(intake);
        assertThat(identity.versionStateEvidence())
            .isEqualTo(publicAssociation.versionStateEvidence());
        var binding = binding(gd.target());
        var request = account.prepareOriginalDraftOrder(binding, NAMESPACE);
        var original = request.original();
        register(
            accountHandlers,
            account.originalDraftOrderProducer(NAMESPACE),
            new AccountDraftCommitOrderReadGrpcService(
                account.heldOrderOwner(NAMESPACE), NAMESPACE));
        var coordinator = new DraftCommitCoordinatorRepository(gd.store().dsl());
        var terminals = new GameDesignDraftTerminalOutcomeRepository(gd.store().dsl());
        var sources = new GameDesignSourceRepository(gd.store().dsl());
        var fence = new WorldDesignPublicationFenceRepository(world.dsl(), intakes);
        var applications =
            new WorldDraftGraphApplicationRepository(world.dsl(), fence, new ObjectMapper());
        var applicationService =
            new WorldDraftGraphApplicationService(
                applications,
                world.transactions(),
                new WorldDraftCommitOrderVerifier(heldClient, NAMESPACE));
        var entry =
            new WorldOriginalDraftGraphApplicationService(identities, intakes, applicationService);
        var failurePending = new AtomicBoolean(failure != Failure.NONE);
        worldHandlers.addService(
            ServerInterceptors.intercept(
                new WorldOriginalDraftGraphApplyGrpcService(entry, NAMESPACE),
                new GrpcPeerIdentityInterceptor(),
                failureInterceptor(failure, failurePending)));
        register(
            worldHandlers,
            new WorldDraftTerminalReadGrpcService(
                new WorldDraftTerminalOutcomeRepository(world.dsl(), fence, new ObjectMapper()),
                applications,
                NAMESPACE));
        register(gdHandlers, new GameDesignDraftTerminalReadGrpcService(terminals, NAMESPACE));
        accountClient.init();
        heldClient.init();
        worldClient.init();
        gdTerminalClient.init();
        worldTerminalClient.init();
        var composer =
            new GameDesignWorldSourceCommitService(
                accountClient,
                worldClient,
                coordinator,
                terminals,
                sources,
                gd.store().transactions(),
                NAMESPACE);
        Optional<WorldDraftGraphAppliedResult> firstWorldApplication = Optional.empty();
        if (failure != Failure.NONE) {
          assertThatThrownBy(() -> composer.commit(original, request.originalCreatorCredential()))
              .isInstanceOf(io.grpc.StatusRuntimeException.class);
          account.assertOriginalDraftOrderPending(original);
          assertThat(coordinator.readApplicationSlot(gd.target())).isPresent();
          assertThat(coordinator.readVisibilityFence(gd.target())).isEmpty();
          assertThat(sources.readSynchronized(gd.target(), binding.commitId())).isEmpty();
          assertThat(terminals.read(new GameDesignDraftTerminalOperation(original))).isEmpty();
          var partial = coordinator.read(gd.target(), binding.requestId()).orElseThrow();
          assertThat(
                  partial
                      .ownerStates()
                      .get(DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE)
                      .status())
              .isEqualTo(DraftCommitCoordinatorRepository.OwnerStatus.APPLIED);
          assertThat(partial.ownerStates().get(DraftCommitBinding.Owner.WORLD_MANAGEMENT).status())
              .isEqualTo(DraftCommitCoordinatorRepository.OwnerStatus.IN_PROGRESS);
          firstWorldApplication = applications.readCommitted(NAMESPACE, original.canonicalBytes());
          assertThat(firstWorldApplication.isPresent())
              .isEqualTo(failure == Failure.LOST_ACKNOWLEDGEMENT);
          failurePending.set(false);
        }
        var terminal = composer.commit(original, request.originalCreatorCredential());
        assertThat(terminal.result()).isEqualTo(GameDesignDraftTerminalOutcome.Result.COMMITTED);
        assertThat(terminal.operation().accountBindingBytes()).isEqualTo(original.canonicalBytes());
        assertThat(coordinator.readApplicationSlot(gd.target())).isEmpty();
        assertThat(coordinator.readVisibilityFence(gd.target()).orElseThrow().commitId())
            .isEqualTo(binding.commitId());
        assertThat(
                sources
                    .readSynchronized(gd.target(), binding.commitId())
                    .orElseThrow()
                    .command()
                    .binding())
            .isEqualTo(binding);
        var actualWorld =
            applications.readCommitted(NAMESPACE, original.canonicalBytes()).orElseThrow();
        firstWorldApplication.ifPresent(
            first -> assertThat(actualWorld.canonicalBytes()).isEqualTo(first.canonicalBytes()));
        assertThat(actualWorld.status()).isEqualTo("APPLIED");
        assertThat(actualWorld.startLocationReceipt()).isPresent();
        assertThat(actualWorld.application().operation().accountBindingBytes())
            .isEqualTo(original.canonicalBytes());
        assertThat(
                coordinator
                    .read(gd.target(), binding.requestId())
                    .orElseThrow()
                    .ownerStates()
                    .values())
            .allMatch(
                owner -> owner.status() == DraftCommitCoordinatorRepository.OwnerStatus.APPLIED);
        long worldApplicationCount =
            world.dsl().fetchCount(DSL.table("world_draft_graph_application"));
        int gdRevisionCount =
            gd.store().dsl().fetchCount(DSL.table("game_design_gameplay_rule_revision"));
        assertThat(worldApplicationCount).isEqualTo(1);
        assertThat(gdRevisionCount).isEqualTo(1);
        assertThat(composer.commit(original, null).finalEvidenceBytes())
            .isEqualTo(terminal.finalEvidenceBytes());
        assertThat(world.dsl().fetchCount(DSL.table("world_draft_graph_application")))
            .isEqualTo(worldApplicationCount);
        assertThat(gd.store().dsl().fetchCount(DSL.table("game_design_gameplay_rule_revision")))
            .isEqualTo(gdRevisionCount);
        account.assertOriginalDraftOrderPending(original);
        account.reconcileOriginalDraft(original, gdTerminalClient, worldTerminalClient, NAMESPACE);
        account.reconcileOriginalDraft(original, gdTerminalClient, worldTerminalClient, NAMESPACE);
        assertThatThrownBy(
                () ->
                    heldClient.read(
                        DraftCommitOrderReadEvidence.Request.create(
                            NAMESPACE, original.canonicalBytes())))
            .isInstanceOf(io.grpc.StatusRuntimeException.class);
      }
    } finally {
      for (var server : List.of(accountServer, worldServer, gdServer)) {
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  private static ServerInterceptor failureInterceptor(Failure failure, AtomicBoolean pending) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (failure == Failure.UNAVAILABLE && pending.get()) {
          call.close(
              Status.UNAVAILABLE.withDescription("Test-only unavailable World owner"),
              new Metadata());
          return new ServerCall.Listener<>() {};
        }
        if (failure == Failure.LOST_ACKNOWLEDGEMENT && pending.compareAndSet(true, false)) {
          return next.startCall(
              new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                @Override
                public void sendMessage(RespT message) {
                  // The real owner committed before producing this response; suppress its
                  // acknowledgement.
                }

                @Override
                public void close(Status status, Metadata trailers) {
                  super.close(
                      status.isOk()
                          ? Status.UNAVAILABLE.withDescription(
                              "Test-only lost World acknowledgement")
                          : status,
                      trailers);
                }
              },
              headers);
        }
        return next.startCall(call, headers);
      }
    };
  }

  private GameDesign gameDesign() {
    var store = store(GD, "game-design");
    var game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("Genuine GD+World source");
    game.setDescription("Real authoring source provenance");
    var savedGame =
        Objects.requireNonNull(
            transaction(store).execute(ignored -> new GameRepository(store.dsl()).save(game)));
    var version = new Version();
    version.setTenantId(savedGame.getTenantId());
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    var saved =
        Objects.requireNonNull(
            transaction(store)
                .execute(ignored -> new VersionRepository(store.dsl()).save(version)));
    var target =
        new DraftCommitBinding.TargetProof(
            saved.getCanonicalTenantId(),
            saved.getCanonicalVersionId(),
            saved.getId(),
            saved.getTenantId(),
            saved.getIdentitySourceGameRowId(),
            saved.getIdentitySourceGameTenantKey(),
            saved.getIdentitySourceProvenanceKind());
    assertThat(new GameDesignSourceRepository(store.dsl()).readGenesis(target)).isPresent();
    return new GameDesign(store, target);
  }

  private static Store store(PostgreSQLContainer<?> postgres, String service) {
    String schema = service.replace('-', '_') + "_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    data.setSchema(schema);
    Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(migrations(service))
        .load()
        .migrate();
    return new Store(
        DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES),
        new DataSourceTransactionManager(data));
  }

  private static String migrations(String service) {
    for (Path current = Path.of("").toAbsolutePath();
        current != null;
        current = current.getParent()) {
      Path target =
          current.resolve("services/" + service + "-service/src/main/resources/db/migration");
      if (Files.isDirectory(target)) return "filesystem:" + target;
    }
    throw new IllegalStateException("Owner migration directory unavailable");
  }

  private static TransactionTemplate transaction(Store store) {
    var result = new TransactionTemplate(store.transactions());
    result.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return result;
  }

  private static Server server(
      GenuineWorldSourceProofPki pki, String service, MutableHandlerRegistry handlers)
      throws Exception {
    var identity = pki.server(service);
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .fallbackHandlerRegistry(handlers)
        .maxInboundMessageSize(WorldOriginalDraftGraphApplyGrpcCodec.MAX_REQUEST_WIRE_BYTES)
        .maxInboundMetadataSize(AccountOriginalDraftOrderGrpcCodec.MAX_METADATA_BYTES)
        .sslContext(
            GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                .trustManager(pki.ca().toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .build()
        .start();
  }

  private static void register(MutableHandlerRegistry handlers, BindableService... services) {
    for (var service : services)
      handlers.addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()));
  }

  private static DraftCommitBinding binding(DraftCommitBinding.TargetProof target)
      throws Exception {
    UUID commit = UUID.randomUUID();
    UUID region = UUID.randomUUID();
    UUID zone = UUID.randomUUID();
    UUID room = UUID.randomUUID();
    var familyCounts = new ArrayList<WorldFreshGraphFamilyCount>();
    for (var family :
        List.of(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)) {
      familyCounts.add(
          WorldFreshGraphFamilyCount.newBuilder()
              .setFamily(family)
              .setCount(
                  family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION
                          || family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE
                          || family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM
                      ? 1
                      : 0)
              .build());
    }
    var declaration =
        WorldFreshGraphDeclaration.newBuilder()
            .setTenantId(target.canonicalTenantId().toString())
            .setVersionId(target.canonicalVersionId().toString())
            .addAllFamilyCounts(familyCounts)
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                    .setTenantId(target.canonicalTenantId().toString())
                    .setVersionId(target.canonicalVersionId().toString())
                    .setRoomTemplateId(room.toString()))
            .build();
    var mutations =
        List.of(
            mutation(
                    commit, room, region, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
                .setRoom(
                    RoomDesignMutation.newBuilder()
                        .setName("Genuine room")
                        .setZoneId(zone.toString())
                        .setDescription("Genuine room description"))
                .build(),
            mutation(
                    commit, zone, region, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
                .setZone(
                    ZoneDesignMutation.newBuilder()
                        .setName("Genuine zone")
                        .setRegionId(region.toString()))
                .build(),
            mutation(
                    commit,
                    region,
                    region,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
                .setRegion(
                    RegionDesignMutation.newBuilder()
                        .setName("Genuine region")
                        .setWeather("clear")
                        .setShardId(1))
                .setFreshGraphDeclaration(declaration)
                .build());
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>();
    var units = new ArrayList<DraftCommitBinding.AffectedUnit>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.randomUUID(),
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.upsertPayload(
                new GameplayRuleManifest.AdmissionTag("genuine-world-admission"))));
    units.add(
        new DraftCommitBinding.AffectedUnit(
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.SCOPE,
            target.canonicalVersionId().toString(),
            GameplayRuleSource.SCOPE,
            "effective",
            "0"));
    for (var mutation : mutations) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(mutation.getLogicalRevisionId()),
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().omittingInsignificantWhitespace().print(mutation)));
      var family = mutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      units.add(
          new DraftCommitBinding.AffectedUnit(
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "AGGREGATE",
              mutation.getAggregateId(),
              "0"));
      units.add(
          new DraftCommitBinding.AffectedUnit(
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "REGION_SUBTREE",
              mutation.getScopeId(),
              "0"));
    }
    return DraftCommitBinding.create(
        target, UUID.randomUUID(), commit, "base-commit-0", revisions, units);
  }

  private static WorldDesignMutationRevision.Builder mutation(
      UUID commit, UUID id, UUID region, WorldDesignAggregateType type) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(id.toString())
        .setAggregateType(type)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(region.toString());
  }

  private enum Failure {
    NONE,
    LOST_ACKNOWLEDGEMENT,
    UNAVAILABLE
  }

  private record Store(DSLContext dsl, DataSourceTransactionManager transactions) {}

  private record GameDesign(Store store, DraftCommitBinding.TargetProof target) {}
}
