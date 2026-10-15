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
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.util.MutableHandlerRegistry;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderClient;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadClient;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GrpcAccountGameLogicIntakeSettlementReadClient;
import net.firedevops.firemud.common.gamelogic.GrpcGameLogicIntakeAuthorizationClient;
import net.firedevops.firemud.common.gamelogic.GrpcGameLogicIntakeRetainClient;
import net.firedevops.firemud.common.gamelogic.GrpcGameLogicIntakeSourceReadClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOperation;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOutcomeRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalReadGrpcService;
import net.firedevops.firemud.gamedesign.draft.GameDesignReviewedBaseRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignWorldSourceCommitService;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignGameplayRuleSourceReadGrpcService;
import net.firedevops.firemud.gamedesign.publication.GameDesignGameplayRuleSourceReadService;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicIntakeCommandService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceipt;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceiptRepository;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceiptService;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldSourceReadGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateService;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeGrpcService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeRepository;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeTerminalReadGrpcService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeTerminalReadService;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftCommitOrderVerifier;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationService;
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
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceClosureDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamily;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamilyCount;
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

/**
 * Bounded GD+World source commit through selection, Account intake, GL terminal, settlement, and
 * the immutable GD receipt. SQL owners and loopback mTLS handlers are actual implementations;
 * creator, issuer, authored source, Account environment, and workload certificates are controlled
 * test inputs and establish no production or registered authority.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GenuineSelectedGameLogicIntakePostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> ACCOUNT_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> WORLD_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("genuine-intake-primary")
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
              "genuine-intake-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void controlledCreatorAndWorldFixturesDriveActualOwnersThroughExactIntakeAndReceipt()
      throws Exception {
    var local = gameDesignLocal();
    var world = worldStore();
    var gameLogic = gameLogicStore();
    try (var account =
        new AccountControlUiOriginalOrderFixture(
            ACCOUNT_POSTGRES.getJdbcUrl(),
            ACCOUNT_POSTGRES.getUsername(),
            ACCOUNT_POSTGRES.getPassword(),
            REDIS.getHost(),
            REDIS.getMappedPort(6379),
            temporary.resolve("account"),
            local.target().canonicalTenantId())) {
      var accountAccess = account.preparedOriginalCreator();
      var accountRepository =
          new AccountGameLogicIntakeAuthorizationRepository(accountAccess.sources().dsl);
      var accountSourcePermissionOwner =
          account.gameLogicIntakeSourceReadService(Clock.systemUTC(), NAMESPACE);
      var accountHeldReadOwner =
          new AccountGameLogicIntakeAuthorizationReadService(
              accountRepository, accountAccess.sources().manager, NAMESPACE);

      var endpoints = new ServiceEndpointsProperties();
      var pki = new TestPki(temporary.resolve("pki"));
      var gameDesignSourceReads = new AtomicInteger();
      var gameDesignTerminalReads = new AtomicInteger();
      var gameLogicTerminalReads = new AtomicInteger();
      var worldTerminalReads = new AtomicInteger();
      var accountHandlers = new MutableHandlerRegistry();
      var gameDesignHandlers = new MutableHandlerRegistry();
      var gameLogicHandlers = new MutableHandlerRegistry();
      var worldHandlers = new MutableHandlerRegistry();
      Server accountServer = null;
      Server gameDesignServer = null;
      Server gameLogicServer = null;
      Server worldServer = null;
      try {
        accountServer = startServer(pki, pki.accountServer(), accountHandlers);
        endpoints.setAccountService(loopback(accountServer));
        gameDesignServer = startServer(pki, pki.gameDesignServer(), gameDesignHandlers);
        endpoints.setGameDesignService(loopback(gameDesignServer));
        gameLogicServer = startServer(pki, pki.gameLogicServer(), gameLogicHandlers);
        endpoints.setGameLogicService(loopback(gameLogicServer));
        worldServer = startServer(pki, pki.worldServer(), worldHandlers);
        endpoints.setWorldManagementService(loopback(worldServer));

        registerWorldSourceIdentity(
            local, world, endpoints, pki, gameDesignHandlers, worldHandlers);
        var binding = binding(local, "genuine-admission");
        var reviewedBaseOwner = new GameDesignReviewedBaseRepository(local.dsl());
        transaction(local)
            .execute(
                ignored -> {
                  var reviewedBase =
                      reviewedBaseOwner.resolve(
                          local.target(), DraftBaseReference.parse(binding.baseCommitId()));
                  new GameDesignSourceRepository(local.dsl()).claimReviewed(binding, reviewedBase);
                  var retained = reviewedBaseOwner.requireCurrentRetained(binding);
                  assertThat(retained.canonicalBytes())
                      .containsExactly(reviewedBase.canonicalBytes());
                  assertThat(retained.digest()).isEqualTo(reviewedBase.digest());
                  return null;
                });
        var originalRequest = account.prepareOriginalDraftOrder(binding, NAMESPACE);
        var original = originalRequest.original();
        var credential = originalRequest.originalCreatorCredential();

        var accountToGameDesignSource =
            new GameplayRuleSourceReadClient(
                endpoints,
                pki.accountClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var gameDesignToAccountPermission =
            new GrpcGameLogicIntakeSourceReadClient(
                endpoints,
                pki.gameDesignClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var gameLogicToAccountHeld =
            new GameLogicIntakeAuthorizationReadClient(
                endpoints,
                pki.gameLogicClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var gameLogicToGameDesignSource =
            new GameplayRuleSourceReadClient(
                endpoints,
                pki.gameLogicClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var accountToGameLogicTerminal =
            new GameLogicIntakeTerminalReadClient(
                endpoints,
                pki.accountClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var gameDesignToAccountAuthorization =
            new GrpcGameLogicIntakeAuthorizationClient(
                endpoints,
                pki.gameDesignClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var gameDesignToGameLogicRetain =
            new GrpcGameLogicIntakeRetainClient(
                endpoints,
                pki.gameDesignClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var gameDesignToAccountSettlement =
            new GrpcAccountGameLogicIntakeSettlementReadClient(
                endpoints,
                pki.gameDesignClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var originalOrderClient =
            new AccountOriginalDraftOrderClient(
                endpoints,
                pki.gameDesignClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var originalTerminalClient =
            new GameDesignDraftTerminalReadClient(
                endpoints,
                pki.accountClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var worldToAccountHeldOrder =
            new DraftCommitOrderReadClient(
                endpoints,
                pki.worldClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var accountToWorldTerminal =
            new WorldDraftTerminalReadClient(
                endpoints,
                pki.accountClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var gameDesignToWorldApply =
            new WorldOriginalDraftGraphApplyClient(
                endpoints,
                pki.gameDesignClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);

        var gameDesignSourceOwner =
            new GameDesignGameplayRuleSourceReadService(
                new GameplayRuleSourceRepository(local.dsl()),
                local.transactions(),
                gameDesignToAccountPermission,
                NAMESPACE);
        var gameDesignTerminalOwner = new GameDesignDraftTerminalOutcomeRepository(local.dsl());
        var accountAuthorizationOwner =
            new AccountGameLogicIntakeAuthorizationService(
                accountAccess.actors(),
                accountAccess.sources().fences,
                accountRepository,
                accountToGameDesignSource,
                accountAccess.sources().manager,
                NAMESPACE);
        var accountSettlementOwner =
            new AccountGameLogicIntakeSettlementService(
                accountRepository,
                accountToGameLogicTerminal,
                accountAccess.sources().manager,
                NAMESPACE);
        var gameLogicRepository = new GameLogicGameplayRuleIntakeRepository(gameLogic.dsl());
        var gameLogicOwner =
            new GameLogicGameplayRuleIntakeService(
                gameLogicRepository,
                gameLogic.transactions(),
                gameLogicToAccountHeld,
                gameLogicToGameDesignSource,
                NAMESPACE);
        var worldIntakes = new WorldAuthoredSourceIntakeRepository(world.dsl());
        var worldIdentities = new WorldAuthoredVersionIdentityRepository(world.dsl());
        var worldFence = new WorldDesignPublicationFenceRepository(world.dsl(), worldIntakes);
        var mapper = new tools.jackson.databind.ObjectMapper();
        var worldApplications =
            new WorldDraftGraphApplicationRepository(world.dsl(), worldFence, mapper);
        var worldTerminals =
            new WorldDraftTerminalOutcomeRepository(world.dsl(), worldFence, mapper);
        var worldApplicationService =
            new WorldDraftGraphApplicationService(
                worldApplications,
                world.transactions(),
                new WorldDraftCommitOrderVerifier(worldToAccountHeldOrder, NAMESPACE));
        var worldOriginalApply =
            new WorldOriginalDraftGraphApplicationService(
                worldIdentities, worldIntakes, worldApplicationService);
        registerServices(
            accountHandlers,
            Map.of(),
            account.originalDraftOrderProducer(NAMESPACE),
            new net.firedevops.firemud.accountservice.authordraft
                .AccountDraftCommitOrderReadGrpcService(
                account.heldOrderOwner(NAMESPACE), NAMESPACE),
            new AccountGameLogicIntakeAuthorizationGrpcService(
                accountAuthorizationOwner, accountAccess.sources().terms, NAMESPACE),
            new AccountGameLogicIntakeSourceReadGrpcService(
                accountSourcePermissionOwner, NAMESPACE),
            new AccountGameLogicIntakeAuthorizationReadGrpcService(accountHeldReadOwner, NAMESPACE),
            new AccountGameLogicIntakeSettlementGrpcService(accountSettlementOwner, NAMESPACE));
        registerServices(
            gameDesignHandlers,
            Map.of(
                "readselectedgameplayrulesource", gameDesignSourceReads,
                "readgamedesigndraftterminaloutcome", gameDesignTerminalReads),
            new GameDesignGameplayRuleSourceReadGrpcService(gameDesignSourceOwner, NAMESPACE),
            new GameDesignDraftTerminalReadGrpcService(gameDesignTerminalOwner, NAMESPACE));
        registerServices(
            gameLogicHandlers,
            Map.of("readgameplayruleintaketerminal", gameLogicTerminalReads),
            new GameLogicGameplayRuleIntakeGrpcService(gameLogicOwner, NAMESPACE),
            new GameLogicGameplayRuleIntakeTerminalReadGrpcService(
                new GameLogicGameplayRuleIntakeTerminalReadService(gameLogicRepository, NAMESPACE),
                NAMESPACE));
        registerServices(
            worldHandlers,
            Map.of("readworlddraftterminaloutcome", worldTerminalReads),
            new WorldDraftTerminalReadGrpcService(worldTerminals, worldApplications, NAMESPACE),
            new WorldOriginalDraftGraphApplyGrpcService(worldOriginalApply, NAMESPACE));

        try (accountToGameDesignSource;
            gameDesignToAccountPermission;
            gameLogicToAccountHeld;
            gameLogicToGameDesignSource;
            accountToGameLogicTerminal;
            gameDesignToAccountAuthorization;
            gameDesignToGameLogicRetain;
            gameDesignToAccountSettlement;
            originalOrderClient;
            originalTerminalClient;
            worldToAccountHeldOrder;
            accountToWorldTerminal;
            gameDesignToWorldApply) {
          accountToGameDesignSource.init();
          gameDesignToAccountPermission.init();
          gameLogicToAccountHeld.init();
          gameLogicToGameDesignSource.init();
          accountToGameLogicTerminal.init();
          gameDesignToAccountAuthorization.init();
          gameDesignToGameLogicRetain.init();
          gameDesignToAccountSettlement.init();
          originalOrderClient.init();
          originalTerminalClient.init();
          worldToAccountHeldOrder.init();
          accountToWorldTerminal.init();
          gameDesignToWorldApply.init();

          var commitService =
              new GameDesignWorldSourceCommitService(
                  originalOrderClient,
                  gameDesignToWorldApply,
                  new DraftCommitCoordinatorRepository(local.dsl()),
                  new GameDesignDraftTerminalOutcomeRepository(local.dsl()),
                  new GameDesignSourceRepository(local.dsl()),
                  local.transactions(),
                  NAMESPACE);
          var originalTerminal = commitService.commit(original, credential);
          assertThat(originalTerminal.result())
              .isEqualTo(
                  net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOutcome.Result
                      .COMMITTED);
          assertThat(originalTerminal.operation().accountBindingBytes())
              .containsExactly(originalRequest.originalAccountBinding());
          assertSynchronizedSource(local, original);
          account.assertOriginalDraftOrderPending(original);

          var originalTerminalReconciler =
              account.terminalReconciler(originalTerminalClient, accountToWorldTerminal, NAMESPACE);
          assertThat(originalTerminalReconciler.reconcile(original.operationId()))
              .contains(DraftAuthorizationFenceRepository.Settlement.COMMITTED);
          account.assertOriginalDraftSettlement(
              original, DraftAuthorizationFenceRepository.Settlement.COMMITTED);
          assertThat(gameDesignTerminalReads).hasValue(1);
          assertThat(worldTerminalReads).hasValue(1);
          assertThat(gameDesignSourceReads).hasValue(0);

          var selection = reserveSelection(local, original);
          var receiptOwner =
              new SelectedDraftGameLogicReceiptService(
                  local.dsl(),
                  local.transactions(),
                  gameDesignToGameLogicRetain,
                  gameDesignToAccountSettlement,
                  NAMESPACE);
          var authorizationCalls = new AtomicInteger();
          var lostFirstResponse = new AtomicBoolean();
          GameLogicIntakeAuthorizationClient lostResponseClient =
              new GameLogicIntakeAuthorizationClient() {
                @Override
                public GameLogicIntakeAuthorizationEvidence.Result authorize(
                    GameLogicIntakeAuthorizationEvidence.Request request,
                    String originalCreatorCredential) {
                  authorizationCalls.incrementAndGet();
                  var response =
                      gameDesignToAccountAuthorization.authorize(
                          request, originalCreatorCredential);
                  if (lostFirstResponse.compareAndSet(false, true))
                    throw new IllegalStateException(
                        "test-only lost Account authorization response");
                  return response;
                }

                @Override
                public GameLogicIntakeAuthorizationEvidence.Result recover(
                    GameLogicIntakeAuthorizationEvidence.Request request,
                    String originalCreatorCredential) {
                  return gameDesignToAccountAuthorization.recover(
                      request, originalCreatorCredential);
                }

                @Override
                public GameLogicIntakeAuthorizationEvidence.Result abort(
                    GameLogicIntakeAuthorizationEvidence.Request request,
                    String originalCreatorCredential) {
                  return gameDesignToAccountAuthorization.abort(request, originalCreatorCredential);
                }
              };
          var command =
              new SelectedDraftGameLogicIntakeCommandService(
                  local.dsl(), lostResponseClient, receiptOwner, NAMESPACE);
          var wrongSourceRequest =
              GameLogicIntakeAuthorizationEvidence.Request.create(
                  NAMESPACE, UUID.randomUUID(), binding(local, "substituted-admission"));
          assertThatThrownBy(
                  () -> command.authorizeAndRetain(selection, wrongSourceRequest, credential))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("selected Draft binding");
          assertThat(authorizationCalls).hasValue(0);
          assertThat(gameDesignSourceReads).hasValue(0);

          var request =
              GameLogicIntakeAuthorizationEvidence.Request.create(
                  NAMESPACE, UUID.randomUUID(), selection.selectedCommit());
          assertThatThrownBy(() -> command.authorizeAndRetain(selection, request, credential))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("Account intake authorization denied or unavailable");
          assertThat(authorizationCalls).hasValue(1);
          assertThat(
                  accountAccess
                      .sources()
                      .dsl
                      .fetchCount(DSL.table("account_game_logic_intake_authorizations")))
              .isEqualTo(1);
          assertThat(gameDesignSourceReads).hasValue(1);

          SelectedDraftGameLogicReceipt recovered =
              command.recoverAndRetain(selection, request, credential);
          assertThat(recovered.authorization().source().binding().canonicalBytes())
              .containsExactly(selection.selectedCommit().canonicalBytes());
          assertThat(recovered.receipt().terminal().outcome())
              .isEqualTo(
                  net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal
                      .Outcome.RETAINED);
          assertThat(gameDesignSourceReads).hasValue(2);
          assertThat(gameLogicTerminalReads).hasValue(1);

          var retry = command.recoverAndRetain(selection, request, credential);
          assertThat(retry.selection().canonicalBytes())
              .containsExactly(recovered.selection().canonicalBytes());
          assertThat(retry.authorization().canonicalBytes())
              .containsExactly(recovered.authorization().canonicalBytes());
          assertThat(retry.receipt().canonicalBytes())
              .containsExactly(recovered.receipt().canonicalBytes());
          assertThat(gameDesignSourceReads).hasValue(2);
          assertThat(gameLogicTerminalReads).hasValue(1);
          var retainedReceipt =
              new SelectedDraftGameLogicReceiptRepository(local.dsl())
                  .read(selection, recovered.authorization())
                  .orElseThrow();
          assertThat(retainedReceipt.selection().canonicalBytes())
              .containsExactly(recovered.selection().canonicalBytes());
          assertThat(retainedReceipt.authorization().canonicalBytes())
              .containsExactly(recovered.authorization().canonicalBytes());
          assertThat(retainedReceipt.receipt().canonicalBytes())
              .containsExactly(recovered.receipt().canonicalBytes());
          assertThat(
                  gameLogicRepository
                      .findTerminal(recovered.authorization().operationId())
                      .orElseThrow()
                      .canonicalBytes())
              .containsExactly(recovered.receipt().terminal().canonicalBytes());
          assertThat(
                  accountAccess
                      .sources()
                      .dsl
                      .fetchCount(DSL.table("account_game_logic_intake_settlements")))
              .isEqualTo(1);
          assertThat(
                  gameLogic.dsl().fetchCount(DSL.table("game_logic_gameplay_rule_intake_terminal")))
              .isEqualTo(1);
          assertThat(local.dsl().fetchCount(DSL.table("game_design_selected_game_logic_receipt")))
              .isEqualTo(1);
          assertThat(
                  accountAccess
                      .sources()
                      .tx(() -> accountAccess.sources().fences.readSettlement(original)))
              .isEqualTo(DraftAuthorizationFenceRepository.Settlement.COMMITTED);
        }
      } finally {
        stopAll(worldServer, gameLogicServer, gameDesignServer, accountServer);
      }
    }
  }

  private AuthoredDraftPublishSelection reserveSelection(
      Local local, DraftAuthorizationFenceBinding original) {
    var tx = transaction(local);
    var selected = new GameDesignDraftTerminalOperation(original).gameDesignBinding();
    return tx.execute(
        ignored ->
            new AuthoredDraftPublishSelectionRepository(
                    local.dsl(), new DraftCommitCoordinatorRepository(local.dsl()))
                .reserve(
                    new AuthoredDraftPublishSelection.PublishIntent(
                        local.target().canonicalTenantId(),
                        local.target().canonicalVersionId(),
                        UUID.randomUUID().toString(),
                        "1",
                        "controlled owner source receipt proof",
                        selected.requestId(),
                        selected.commitId(),
                        selected.digest()))
                .selection());
  }

  private static void assertSynchronizedSource(
      Local local, DraftAuthorizationFenceBinding original) {
    var snapshot =
        new GameDesignSourceRepository(local.dsl())
            .readSynchronized(local.target(), original.commitId())
            .orElseThrow();
    var gameplay = snapshot.gameplayEvidence().orElseThrow();
    assertThat(gameplay.manifest().families().get(GameplayRuleManifest.Family.ADMISSION_TAGS))
        .hasSize(1);
    assertThat(gameplay.binding().canonicalBytes()).containsExactly(original.gameDesignBinding());
  }

  private void registerWorldSourceIdentity(
      Local local,
      WorldStore world,
      ServiceEndpointsProperties endpoints,
      TestPki pki,
      MutableHandlerRegistry gameDesignHandlers,
      MutableHandlerRegistry worldHandlers)
      throws Exception {
    var gameSourceRepository = new GameAuthoredWorldSourceRepository(local.dsl());
    registerServices(
        gameDesignHandlers,
        Map.of(),
        new TenantIdentityGrpcService(
            new GameTenantCreationRepository(local.dsl(), new GameRepository(local.dsl())),
            NAMESPACE),
        new AuthoredWorldSourceReadGrpcService(gameSourceRepository, NAMESPACE),
        new AuthoredWorldVersionStateGrpcService(
            new AuthoredWorldVersionStateService(local.transactions(), gameSourceRepository),
            NAMESPACE));
    var source =
        transaction(local)
            .execute(
                ignored ->
                    gameSourceRepository.register(
                        NAMESPACE,
                        UUID.randomUUID(),
                        local.target().canonicalTenantId(),
                        "controlled-tenant",
                        "controlled-world",
                        "Controlled authored World source"));
    var intakes = new WorldAuthoredSourceIntakeRepository(world.dsl());
    var identities = new WorldAuthoredVersionIdentityRepository(world.dsl());
    try (var sourceClient =
            new AuthoredWorldSourceClient(
                endpoints,
                pki.worldClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var versionClient =
            new AuthoredWorldVersionStateClient(
                endpoints,
                pki.worldClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE);
        var intakeClient =
            new WorldAuthoredSourceIntakeClient(
                endpoints,
                pki.gameDesignClient().properties(pki.ca()),
                new GrpcChannelFactory(),
                NAMESPACE)) {
      sourceClient.init();
      versionClient.init();
      var intakeService =
          new WorldAuthoredSourceIntakeService(
              sourceClient, intakes, world.transactions(), NAMESPACE);
      var identityService =
          new WorldAuthoredVersionIdentityService(
              versionClient, identities, intakes, world.transactions(), NAMESPACE);
      var associationComplete = new AtomicBoolean();
      var associationHook =
          new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
              return next.startCall(
                  new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override
                    public void sendMessage(RespT message) {
                      if (associationComplete.compareAndSet(false, true)) {
                        var peer = GrpcPeerIdentity.current();
                        if (peer == null
                            || !peer.isService("game-design-service")
                            || !peer.isInNamespace(NAMESPACE))
                          throw new SecurityException(
                              "Authenticated controlled Game Design socket peer required");
                        identityService.associate(
                            NAMESPACE,
                            source.canonicalTenantId(),
                            source.worldSlug(),
                            source.operationId(),
                            source.evidenceDigest(),
                            local.target().canonicalVersionId(),
                            local.target().gameDesignVersionRowId(),
                            UUID.randomUUID());
                      }
                      super.sendMessage(message);
                    }
                  },
                  headers);
            }
          };
      worldHandlers.addService(
          ServerInterceptors.intercept(
              new WorldAuthoredSourceIntakeGrpcService(intakeService, NAMESPACE),
              new GrpcPeerIdentityInterceptor(),
              associationHook));
      intakeClient.init();
      var request =
          new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
              1,
              NAMESPACE,
              UUID.randomUUID(),
              source.canonicalTenantId(),
              source.worldSlug(),
              source.operationId(),
              source.evidenceDigest());
      var response = intakeClient.intake(request);
      var retained = intakes.read(NAMESPACE, request.intakeRequestId()).orElseThrow();
      assertThat(response.receiptDigest()).isEqualTo(retained.receiptDigest());
      var identity =
          identities
              .readByCanonicalTarget(
                  NAMESPACE,
                  source.canonicalTenantId(),
                  local.target().canonicalVersionId(),
                  local.target().gameDesignVersionRowId())
              .orElseThrow();
      assertThat(identity.sourceIntakeReceipt()).isEqualTo(retained);
    }
  }

  private Local gameDesignLocal() {
    var schema = "game_design_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    data.setSchema(schema);
    Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_design")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(migrationLocation("game-design"))
        .load()
        .migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(data);
    var game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("Genuine selected intake Game");
    game.setDescription("Persisted Game Design identity and source provenance");
    var savedGame =
        java.util.Objects.requireNonNull(
            transaction(manager).execute(ignored -> new GameRepository(dsl).save(game)));
    var version = new Version();
    version.setTenantId(savedGame.getTenantId());
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    var saved =
        java.util.Objects.requireNonNull(
            transaction(manager).execute(ignored -> new VersionRepository(dsl).save(version)));
    var target =
        new DraftCommitBinding.TargetProof(
            saved.getCanonicalTenantId(),
            saved.getCanonicalVersionId(),
            saved.getId(),
            saved.getTenantId(),
            saved.getIdentitySourceGameRowId(),
            saved.getIdentitySourceGameTenantKey(),
            saved.getIdentitySourceProvenanceKind());
    assertThat(new GameplayRuleSourceRepository(dsl).readGenesis(target)).isPresent();
    return new Local(dsl, manager, target);
  }

  private GameLogicStore gameLogicStore() {
    var schema = "game_logic_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    data.setSchema(schema);
    Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_logic")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(migrationLocation("game-logic"))
        .load()
        .migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
    return new GameLogicStore(dsl, new DataSourceTransactionManager(data));
  }

  private WorldStore worldStore() {
    var schema = "world_management_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            WORLD_POSTGRES.getJdbcUrl(),
            WORLD_POSTGRES.getUsername(),
            WORLD_POSTGRES.getPassword());
    data.setSchema(schema);
    Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_world_management")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(migrationLocation("world-management"))
        .load()
        .migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
    return new WorldStore(dsl, new DataSourceTransactionManager(data));
  }

  private DraftCommitBinding binding(Local local, String admissionTag) throws Exception {
    var genesis =
        new GameDesignSourceRepository(local.dsl()).readGenesis(local.target()).orElseThrow();
    String baseCommitId = "genesis:" + genesis.policy().receiptId();
    UUID commit = UUID.randomUUID();
    UUID region = UUID.randomUUID();
    UUID zone = UUID.randomUUID();
    UUID room = UUID.randomUUID();
    var familyCounts = new java.util.ArrayList<WorldFreshGraphFamilyCount>();
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
    var inbound = WorldInboundSourceClosureDeclaration.newBuilder().setSchemaVersion(1);
    for (WorldInboundSourceFamily family :
        List.of(
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE,
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING)) {
      inbound.addFamilyCounts(
          WorldInboundSourceFamilyCount.newBuilder().setFamily(family).setCount(0));
    }
    var declaration =
        WorldFreshGraphDeclaration.newBuilder()
            .setTenantId(local.target().canonicalTenantId().toString())
            .setVersionId(local.target().canonicalVersionId().toString())
            .addAllFamilyCounts(familyCounts)
            .setInboundSourceClosure(inbound.build())
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                    .setTenantId(local.target().canonicalTenantId().toString())
                    .setVersionId(local.target().canonicalVersionId().toString())
                    .setRoomTemplateId(room.toString()))
            .build();
    var mutations =
        List.of(
            mutation(
                    commit, room, region, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
                .setRoom(
                    RoomDesignMutation.newBuilder()
                        .setName("Controlled room")
                        .setZoneId(zone.toString())
                        .setDescription("Controlled original World source fixture"))
                .build(),
            mutation(
                    commit, zone, region, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
                .setZone(
                    ZoneDesignMutation.newBuilder()
                        .setName("Controlled zone")
                        .setRegionId(region.toString()))
                .build(),
            mutation(
                    commit,
                    region,
                    region,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
                .setRegion(
                    RegionDesignMutation.newBuilder()
                        .setName("Controlled region")
                        .setWeather("clear")
                        .setShardId(1))
                .setFreshGraphDeclaration(declaration)
                .build());
    var revisions = new java.util.ArrayList<DraftCommitBinding.RevisionPayload>();
    var units = new java.util.ArrayList<DraftCommitBinding.AffectedUnit>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.randomUUID(),
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.upsertPayload(new GameplayRuleManifest.AdmissionTag(admissionTag))));
    units.add(
        new DraftCommitBinding.AffectedUnit(
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.SCOPE,
            local.target().canonicalVersionId().toString(),
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
        local.target(), UUID.randomUUID(), commit, baseCommitId, revisions, units);
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

  private static TransactionTemplate transaction(Local local) {
    return transaction(local.transactions());
  }

  private static TransactionTemplate transaction(DataSourceTransactionManager manager) {
    var tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return tx;
  }

  private static String migrationLocation(String service) {
    var directoryName = service + "-service";
    var current = Path.of("").toAbsolutePath();
    while (current != null) {
      var migrations =
          current
              .resolve("services")
              .resolve(directoryName)
              .resolve("src/main/resources/db/migration");
      if (Files.isDirectory(migrations)) return "filesystem:" + migrations;
      current = current.getParent();
    }
    throw new IllegalStateException("Service migration directory is unavailable: " + service);
  }

  private static Server startServer(
      TestPki pki, TestIdentity identity, MutableHandlerRegistry handlers) throws Exception {
    var builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .fallbackHandlerRegistry(handlers)
            .maxInboundMessageSize(20 * 1024 * 1024)
            .maxInboundMetadataSize(AccountOriginalDraftOrderGrpcCodec.MAX_METADATA_BYTES)
            .sslContext(
                GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                    .trustManager(pki.ca().toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
    return builder.build().start();
  }

  private static void registerServices(
      MutableHandlerRegistry handlers,
      Map<String, AtomicInteger> methodCounts,
      BindableService... services) {
    for (var service : services)
      handlers.addService(
          ServerInterceptors.intercept(
              service, new GrpcPeerIdentityInterceptor(), countingInterceptor(methodCounts)));
  }

  private static ServerInterceptor countingInterceptor(Map<String, AtomicInteger> methodCounts) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String method = call.getMethodDescriptor().getFullMethodName().toLowerCase(Locale.ROOT);
        methodCounts.forEach(
            (fragment, count) -> {
              if (method.contains(fragment)) count.incrementAndGet();
            });
        return next.startCall(call, headers);
      }
    };
  }

  private static String loopback(Server server) {
    return "127.0.0.1:" + server.getPort();
  }

  private static void stopAll(Server... servers) throws InterruptedException {
    for (var server : servers) if (server != null) server.shutdownNow();
    var terminated = true;
    for (var server : servers)
      if (server != null) terminated &= server.awaitTermination(5, TimeUnit.SECONDS);
    if (!terminated) throw new IllegalStateException("Test owner server did not stop");
  }

  private record Local(
      DSLContext dsl,
      DataSourceTransactionManager transactions,
      DraftCommitBinding.TargetProof target) {}

  private record GameLogicStore(DSLContext dsl, DataSourceTransactionManager transactions) {}

  private record WorldStore(DSLContext dsl, DataSourceTransactionManager transactions) {}

  private record TestIdentity(Path certificate, Path key) {
    CommonGrpcClientProperties properties(Path ca) {
      var result = new CommonGrpcClientProperties();
      result.setPlaintext(false);
      result.setCertChain(certificate.toString());
      result.setPrivateKey(key.toString());
      result.setCaCert(ca.toString());
      return result;
    }
  }

  /** Ephemeral test CA and controlled same-namespace Account, GD, World, and GL identities. */
  private static final class TestPki {
    private final Path ca;
    private final TestIdentity accountServer;
    private final TestIdentity accountClient;
    private final TestIdentity gameDesignServer;
    private final TestIdentity gameDesignClient;
    private final TestIdentity worldServer;
    private final TestIdentity worldClient;
    private final TestIdentity gameLogicServer;
    private final TestIdentity gameLogicClient;

    TestPki(Path root) throws Exception {
      Files.createDirectories(root);
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var caKeys = generator.generateKeyPair();
      var caName = new org.bouncycastle.asn1.x500.X500Name("CN=Test-only genuine intake proof CA");
      var now = java.time.Instant.now();
      var caBuilder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              caName,
              java.math.BigInteger.ONE,
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              caName,
              caKeys.getPublic());
      caBuilder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(true));
      caBuilder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          true,
          new org.bouncycastle.asn1.x509.KeyUsage(org.bouncycastle.asn1.x509.KeyUsage.keyCertSign));
      var caCertificate =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(
                  caBuilder.build(
                      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                          .build(caKeys.getPrivate())));
      ca = pem(root.resolve("ca.pem"), caCertificate);
      accountServer = issue(root, "account-service", true, caKeys, caCertificate);
      accountClient = issue(root, "account-service", false, caKeys, caCertificate);
      gameDesignServer = issue(root, "game-design-service", true, caKeys, caCertificate);
      gameDesignClient = issue(root, "game-design-service", false, caKeys, caCertificate);
      worldServer = issue(root, "world-management-service", true, caKeys, caCertificate);
      worldClient = issue(root, "world-management-service", false, caKeys, caCertificate);
      gameLogicServer = issue(root, "game-logic-service", true, caKeys, caCertificate);
      gameLogicClient = issue(root, "game-logic-service", false, caKeys, caCertificate);
    }

    Path ca() {
      return ca;
    }

    TestIdentity accountServer() {
      return accountServer;
    }

    TestIdentity accountClient() {
      return accountClient;
    }

    TestIdentity gameDesignServer() {
      return gameDesignServer;
    }

    TestIdentity gameDesignClient() {
      return gameDesignClient;
    }

    TestIdentity worldServer() {
      return worldServer;
    }

    TestIdentity worldClient() {
      return worldClient;
    }

    TestIdentity gameLogicServer() {
      return gameLogicServer;
    }

    TestIdentity gameLogicClient() {
      return gameLogicClient;
    }

    private static TestIdentity issue(
        Path root,
        String service,
        boolean server,
        java.security.KeyPair caKeys,
        java.security.cert.X509Certificate caCertificate)
        throws Exception {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keys = generator.generateKeyPair();
      var now = java.time.Instant.now();
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              caCertificate,
              new java.math.BigInteger(120, new java.security.SecureRandom()),
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              new org.bouncycastle.asn1.x500.X500Name("CN=" + service),
              keys.getPublic());
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(false));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          false,
          new org.bouncycastle.asn1.x509.KeyUsage(
              org.bouncycastle.asn1.x509.KeyUsage.digitalSignature
                  | org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
          false,
          new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
              server
                  ? org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth
                  : org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
          false,
          new org.bouncycastle.asn1.x509.GeneralNames(
              new org.bouncycastle.asn1.x509.GeneralName[] {
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier,
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.dNSName, "localhost"),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.iPAddress, "127.0.0.1")
              }));
      var certificate =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(
                  builder.build(
                      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                          .build(caKeys.getPrivate())));
      String prefix = service + (server ? "-server" : "-client");
      return new TestIdentity(
          pem(root.resolve(prefix + ".crt"), certificate),
          pem(root.resolve(prefix + ".key"), keys.getPrivate()));
    }

    private static Path pem(Path path, Object value) throws Exception {
      try (var writer =
          new org.bouncycastle.openssl.jcajce.JcaPEMWriter(Files.newBufferedWriter(path))) {
        writer.writeObject(value);
      }
      return path;
    }
  }
}
