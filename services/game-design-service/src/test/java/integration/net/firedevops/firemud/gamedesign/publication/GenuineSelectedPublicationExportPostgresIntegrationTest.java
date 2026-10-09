package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.AuthorityEvidenceBundleReference;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionRequest;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadGrpcService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.common.account.StartSessionRedeemedOperationProjectionClient;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderClient;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadClient;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityClient;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.common.gamelogic.GameplayAbilitySchemaProjection;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GrpcAccountGameLogicIntakeSettlementReadClient;
import net.firedevops.firemud.common.gamelogic.GrpcGameLogicIntakeAuthorizationClient;
import net.firedevops.firemud.common.gamelogic.GrpcGameLogicIntakeRetainClient;
import net.firedevops.firemud.common.gamelogic.GrpcGameLogicIntakeSourceReadClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.PublicationReadGuard;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.client.GameLogicClient;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOutcomeRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalReadGrpcService;
import net.firedevops.firemud.gamedesign.draft.GameDesignWorldSourceCommitService;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.AssetSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignGameplayRuleSourceReadGrpcService;
import net.firedevops.firemud.gamedesign.publication.GameDesignGameplayRuleSourceReadService;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSource;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventory;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventoryReadService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftControlPlaneDigest;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicIntakeCommandService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceipt;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceiptService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationDigestReadService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationOwner;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociation.SourceRead;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociationRepository;
import net.firedevops.firemud.gamedesign.publication.StartSessionLaunchDescriptorProducer;
import net.firedevops.firemud.gamedesign.publication.StartSessionTemplateAssociationReadService;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSource;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateReferenceRepository;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptParticipantDigestRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPurgeWorkflowRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService.SelectedExportResult;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishAttemptService;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import net.firedevops.firemud.gamedesign.service.RecordedParticipantDigestService;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import net.firedevops.firemud.gamedesign.service.impl.AssetExportServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateService;
import net.firedevops.firemud.gamedesign.service.impl.PublishAttemptServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.PublishedReleaseBundleServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetArtifactServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetExportCandidateServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionPublishCommandServiceImpl;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import net.firedevops.firemud.gamelogic.service.impl.GameLogicGrpcService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeGrpcService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeRepository;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeTerminalReadGrpcService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeTerminalReadService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicPublicationSourceReadService;
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
import org.mapstruct.factory.Mappers;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

/**
 * Real original GD+World source settlement and Account/GL intake-to-GD-receipt, followed by actual
 * selected inventory/export, the owner-local publication finalizer, and descriptor SQL storage.
 * Selected-publication Account/freeze/inventory admission and Entity/Automation participant digests
 * are stipulated; the World participant uses only retained fixture freeze evidence. In-memory S3
 * and StartSession Account projection are test doubles. This is not a four-owner authenticated
 * release, runtime launch, activation, or registration proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GenuineSelectedPublicationExportPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  private static final PostgreSQLContainer<?> GAME_DESIGN_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final PostgreSQLContainer<?> WORLD_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final PostgreSQLContainer<?> ACCOUNT_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final PostgreSQLContainer<?> GAME_LOGIC_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("selected-export-account-primary")
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
  private static final GenericContainer<?> REDIS_REPLICA =
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
              "selected-export-account-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void realSelectedSourceReceiptInventoryAndExportAreExactAndRetryable() throws Exception {
    var gd = gameDesignStore();
    var world = serviceStore(WORLD_POSTGRES, "world-management");
    var gameLogic = serviceStore(GAME_LOGIC_POSTGRES, "game-logic");
    var pki = new TestPki(temporary.resolve("pki"));
    var gdHandlers = new MutableHandlerRegistry();
    var worldHandlers = new MutableHandlerRegistry();
    var accountHandlers = new MutableHandlerRegistry();
    var gameLogicHandlers = new MutableHandlerRegistry();
    Map<String, StartSessionProjectionBinding> startSessionProjections = new ConcurrentHashMap<>();

    Server gdServer = startServer(pki, "game-design-service", gdHandlers);
    Server worldServer = startServer(pki, "world-management-service", worldHandlers);
    Server accountServer = startServer(pki, "account-service", accountHandlers);
    Server gameLogicServer = startServer(pki, "game-logic-service", gameLogicHandlers);
    try (var account =
        new AccountControlUiOriginalOrderFixture(
            ACCOUNT_POSTGRES.getJdbcUrl(),
            ACCOUNT_POSTGRES.getUsername(),
            ACCOUNT_POSTGRES.getPassword(),
            REDIS.getHost(),
            REDIS.getMappedPort(6379),
            temporary.resolve("account"),
            gd.target().canonicalTenantId())) {
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService(loopback(accountServer));
      endpoints.setGameDesignService(loopback(gdServer));
      endpoints.setWorldManagementService(loopback(worldServer));
      endpoints.setGameLogicService(loopback(gameLogicServer));
      var channels = new GrpcChannelFactory();

      var sourceRepository = new GameAuthoredWorldSourceRepository(gd.dsl());
      register(
          gdHandlers,
          new TenantIdentityGrpcService(
              new GameTenantCreationRepository(gd.dsl(), new GameRepository(gd.dsl())),
              sourceRepository,
              NAMESPACE),
          new AuthoredWorldVersionStateGrpcService(
              new AuthoredWorldVersionStateService(gd.transactions(), sourceRepository),
              NAMESPACE));

      var worldIntakes = new WorldAuthoredSourceIntakeRepository(world.dsl());
      var worldIdentities = new WorldAuthoredVersionIdentityRepository(world.dsl());

      try (var sourceClient =
              new AuthoredWorldSourceClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var versionClient =
              new net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var worldIntakeClient =
              new WorldAuthoredSourceIntakeClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var worldIdentityClient =
              new WorldAuthoredVersionIdentityClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var accountOriginalOrderClient =
              new AccountOriginalDraftOrderClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var accountHeldOrderClient =
              new DraftCommitOrderReadClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var worldApplyClient =
              new WorldOriginalDraftGraphApplyClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var gdTerminalClient =
              new GameDesignDraftTerminalReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var worldTerminalClient =
              new WorldDraftTerminalReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var accountToGdSource =
              new GameplayRuleSourceReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var gdToAccountPermission =
              new GrpcGameLogicIntakeSourceReadClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var glToAccountHeld =
              new GameLogicIntakeAuthorizationReadClient(
                  endpoints, pki.client("game-logic-service"), channels, NAMESPACE);
          var glToGdSource =
              new GameplayRuleSourceReadClient(
                  endpoints, pki.client("game-logic-service"), channels, NAMESPACE);
          var accountToGlTerminal =
              new GameLogicIntakeTerminalReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var gdToAccountIntakeAuthorization =
              new GrpcGameLogicIntakeAuthorizationClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var gdToGlRetain =
              new GrpcGameLogicIntakeRetainClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var gdToAccountSettlement =
              new GrpcAccountGameLogicIntakeSettlementReadClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE)) {
        sourceClient.init();
        versionClient.init();
        var worldIntakeOwner =
            new WorldAuthoredSourceIntakeService(
                sourceClient, worldIntakes, world.transactions(), NAMESPACE);
        var worldIdentityOwner =
            new WorldAuthoredVersionIdentityService(
                versionClient, worldIdentities, worldIntakes, world.transactions(), NAMESPACE);
        register(
            worldHandlers,
            new WorldAuthoredSourceIntakeGrpcService(worldIntakeOwner, NAMESPACE),
            new WorldAuthoredVersionIdentityGrpcService(worldIdentityOwner, NAMESPACE));
        worldIntakeClient.init();
        worldIdentityClient.init();

        var worldSource =
            gd.transaction(
                () ->
                    sourceRepository.register(
                        NAMESPACE,
                        UUID.randomUUID(),
                        gd.target().canonicalTenantId(),
                        "genuine-selected-export-tenant",
                        "genuine-selected-export-world",
                        "Genuine selected export source"));
        var worldIntakeRequest =
            new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
                1,
                NAMESPACE,
                UUID.randomUUID(),
                worldSource.canonicalTenantId(),
                worldSource.worldSlug(),
                worldSource.operationId(),
                worldSource.evidenceDigest());
        var worldIntakeReceipt = worldIntakeClient.intake(worldIntakeRequest);
        var worldIdentityRequest =
            new WorldAuthoredVersionIdentityEvidence.Request(
                1,
                NAMESPACE,
                worldSource.canonicalTenantId(),
                worldSource.worldSlug(),
                worldSource.operationId(),
                worldSource.evidenceDigest(),
                gd.target().canonicalVersionId(),
                gd.target().gameDesignVersionRowId(),
                UUID.randomUUID());
        var worldIdentityReceipt = worldIdentityClient.associate(worldIdentityRequest);
        assertThat(worldIdentityReceipt.sourceIntakeReceipt()).isEqualTo(worldIntakeReceipt);

        var assetBytes = "actual selected resource bytes".getBytes(StandardCharsets.UTF_8);
        GameAsset assetToSave = new GameAsset();
        assetToSave.setTenantId(gd.target().gameDesignVersionTenantKey());
        assetToSave.setFileName("selected-resource.txt");
        assetToSave.setContentType("text/plain");
        assetToSave.setData(assetBytes);
        GameAsset sourceAsset =
            gd.transaction(() -> new GameAssetRepository(gd.dsl()).save(assetToSave));
        var selectedCommit = binding(gd.target(), sourceAsset.getId(), sourceAsset.getFileName());
        var originalOrder = account.prepareOriginalDraftOrder(selectedCommit, NAMESPACE);
        var original = originalOrder.original();
        var accountAccess = account.preparedOriginalCreator();
        var accountRepository =
            new AccountGameLogicIntakeAuthorizationRepository(accountAccess.sources().dsl);

        register(
            accountHandlers,
            account.originalDraftOrderProducer(NAMESPACE),
            new AccountDraftCommitOrderReadGrpcService(
                account.heldOrderOwner(NAMESPACE), NAMESPACE));

        var coordinator = new DraftCommitCoordinatorRepository(gd.dsl());
        var gdTerminals = new GameDesignDraftTerminalOutcomeRepository(gd.dsl());
        var gdSources = new GameDesignSourceRepository(gd.dsl());
        var worldFence = new WorldDesignPublicationFenceRepository(world.dsl(), worldIntakes);
        var worldApplications =
            new WorldDraftGraphApplicationRepository(world.dsl(), worldFence, new ObjectMapper());
        var worldApplicationService =
            new WorldDraftGraphApplicationService(
                worldApplications,
                world.transactions(),
                new WorldDraftCommitOrderVerifier(accountHeldOrderClient, NAMESPACE));
        var worldOriginalApply =
            new WorldOriginalDraftGraphApplicationService(
                worldIdentities, worldIntakes, worldApplicationService);
        register(
            worldHandlers,
            new WorldOriginalDraftGraphApplyGrpcService(worldOriginalApply, NAMESPACE),
            new WorldDraftTerminalReadGrpcService(
                new WorldDraftTerminalOutcomeRepository(
                    world.dsl(), worldFence, new ObjectMapper()),
                worldApplications,
                NAMESPACE));

        register(
            gdHandlers,
            new GameDesignDraftTerminalReadGrpcService(gdTerminals, NAMESPACE),
            new GameDesignGameplayRuleSourceReadGrpcService(
                new GameDesignGameplayRuleSourceReadService(
                    new net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository(
                        gd.dsl()),
                    gd.transactions(),
                    gdToAccountPermission,
                    NAMESPACE),
                NAMESPACE));

        var glRepository = new GameLogicGameplayRuleIntakeRepository(gameLogic.dsl());
        var glPublicationSourceReader =
            new GameLogicPublicationSourceReadService(glRepository, NAMESPACE);
        var glPublicationReadBindings =
            new CopyOnWriteArrayList<GameLogicPublicationSourceReadBinding>();
        var glPublicationReadResults =
            new CopyOnWriteArrayList<GameLogicPublicationSourceReadService.Result>();
        GameLogicDraftDesignDigestService observingGlPublicationReader =
            binding -> {
              glPublicationReadBindings.add(binding);
              var result = glPublicationSourceReader.read(binding);
              glPublicationReadResults.add(result);
              return result;
            };
        var accountAuthorizationOwner =
            new AccountGameLogicIntakeAuthorizationService(
                accountAccess.actors(),
                accountAccess.sources().fences,
                accountRepository,
                accountToGdSource,
                accountAccess.sources().manager,
                NAMESPACE);
        var accountSourcePermissionOwner =
            new AccountGameLogicIntakeSourceReadService(
                accountRepository,
                account.coordination(),
                accountAccess.sources().manager,
                Clock.systemUTC(),
                NAMESPACE);
        var accountHeldAuthorizationOwner =
            new AccountGameLogicIntakeAuthorizationReadService(
                accountRepository, accountAccess.sources().manager, NAMESPACE);
        var accountSettlementOwner =
            new AccountGameLogicIntakeSettlementService(
                accountRepository, accountToGlTerminal, accountAccess.sources().manager, NAMESPACE);
        register(
            accountHandlers,
            new AccountGameLogicIntakeAuthorizationGrpcService(
                accountAuthorizationOwner, accountAccess.sources().terms, NAMESPACE),
            new AccountGameLogicIntakeSourceReadGrpcService(
                accountSourcePermissionOwner, NAMESPACE),
            new AccountGameLogicIntakeAuthorizationReadGrpcService(
                accountHeldAuthorizationOwner, NAMESPACE),
            new AccountGameLogicIntakeSettlementGrpcService(accountSettlementOwner, NAMESPACE));
        register(accountHandlers, accountProjectionDouble(startSessionProjections));

        var glOwner =
            new GameLogicGameplayRuleIntakeService(
                glRepository, gameLogic.transactions(), glToAccountHeld, glToGdSource, NAMESPACE);
        register(
            gameLogicHandlers,
            new GameLogicGameplayRuleIntakeGrpcService(glOwner, NAMESPACE),
            new GameLogicGameplayRuleIntakeTerminalReadGrpcService(
                new GameLogicGameplayRuleIntakeTerminalReadService(glRepository, NAMESPACE),
                NAMESPACE),
            new GameLogicGrpcService(
                null,
                null,
                null,
                null,
                null,
                null,
                observingGlPublicationReader,
                null,
                new SimpleMeterRegistry(),
                new PublicationReadGuard(NAMESPACE)));

        accountOriginalOrderClient.init();
        accountHeldOrderClient.init();
        worldApplyClient.init();
        gdTerminalClient.init();
        worldTerminalClient.init();
        accountToGdSource.init();
        gdToAccountPermission.init();
        glToAccountHeld.init();
        glToGdSource.init();
        accountToGlTerminal.init();
        gdToAccountIntakeAuthorization.init();
        gdToGlRetain.init();
        gdToAccountSettlement.init();

        var sourceCommit =
            new GameDesignWorldSourceCommitService(
                accountOriginalOrderClient,
                worldApplyClient,
                coordinator,
                gdTerminals,
                gdSources,
                gd.transactions(),
                NAMESPACE);
        var originalTerminal =
            sourceCommit.commit(original, originalOrder.originalCreatorCredential());
        assertThat(originalTerminal.result())
            .isEqualTo(
                net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOutcome.Result
                    .COMMITTED);
        account.assertOriginalDraftOrderPending(original);
        account.reconcileOriginalDraft(original, gdTerminalClient, worldTerminalClient, NAMESPACE);
        account.reconcileOriginalDraft(original, gdTerminalClient, worldTerminalClient, NAMESPACE);
        assertThat(
                accountAccess
                    .sources()
                    .tx(() -> accountAccess.sources().fences.readSettlement(original)))
            .isEqualTo(DraftAuthorizationFenceRepository.Settlement.COMMITTED);
        var appliedWorld =
            worldApplications.readCommitted(NAMESPACE, original.canonicalBytes()).orElseThrow();
        assertThat(appliedWorld.status()).isEqualTo("APPLIED");
        assertThat(appliedWorld.startLocationReceipt()).isPresent();
        assertThat(world.dsl().fetchCount(DSL.table("world_draft_graph_application"))).isOne();

        var templateSource =
            new TemplateConfigSourceRepository(gd.dsl())
                .readSnapshot(gd.target(), selectedCommit.commitId())
                .orElseThrow();
        assertThat(templateSource.entries()).hasSize(1);
        long templateId = Long.parseLong(templateSource.entries().getFirst().templateId());
        var enforcedPhase =
            new TemplateReferenceRepository(gd.dsl())
                .readPhase(gd.target().canonicalTenantId())
                .orElseThrow();
        assertThat(enforcedPhase.phase()).isEqualTo(TemplateReferenceRepository.Phase.ENFORCED);
        assertThat(enforcedPhase.inventoryTemplateCount()).isEqualTo(1L);
        var normalizedBase =
            new TemplateReferenceRepository(gd.dsl())
                .readExactBaseReference(gd.target().canonicalTenantId(), templateId)
                .orElseThrow();
        assertThat(normalizedBase.canonicalVersionId()).isEqualTo(gd.target().canonicalVersionId());
        assertThat(normalizedBase.sourceCommitId()).isEqualTo(selectedCommit.commitId());
        assertThat(normalizedBase.sourceRevisionId())
            .isEqualTo(templateSource.entries().getFirst().revisionId());

        String publishRequestId = UUID.randomUUID().toString();
        var intent =
            new AuthoredDraftPublishSelection.PublishIntent(
                gd.target().canonicalTenantId(),
                gd.target().canonicalVersionId(),
                publishRequestId,
                Long.toString(gd.version().getVersionStateEpoch()),
                "genuine selected inventory and export proof",
                selectedCommit.requestId(),
                selectedCommit.commitId(),
                selectedCommit.digest());
        var selection =
            gd.transaction(
                () ->
                    new AuthoredDraftPublishSelectionRepository(gd.dsl(), coordinator)
                        .reserve(intent)
                        .selection());
        var intakeRequest =
            GameLogicIntakeAuthorizationEvidence.Request.create(
                NAMESPACE, UUID.randomUUID(), selection.selectedCommit());
        var receiptService =
            new SelectedDraftGameLogicReceiptService(
                gd.dsl(), gd.transactions(), gdToGlRetain, gdToAccountSettlement, NAMESPACE);
        var intakeCommand =
            new SelectedDraftGameLogicIntakeCommandService(
                gd.dsl(), gdToAccountIntakeAuthorization, receiptService, NAMESPACE);
        SelectedDraftGameLogicReceipt retainedReceipt =
            intakeCommand.authorizeAndRetain(
                selection, intakeRequest, originalOrder.originalCreatorCredential());
        SelectedDraftGameLogicReceipt exactRetry =
            intakeCommand.recoverAndRetain(
                selection, intakeRequest, originalOrder.originalCreatorCredential());
        assertThat(exactRetry.selection().canonicalBytes())
            .containsExactly(retainedReceipt.selection().canonicalBytes());
        assertThat(exactRetry.authorization().canonicalBytes())
            .containsExactly(retainedReceipt.authorization().canonicalBytes());
        assertThat(exactRetry.receipt().canonicalBytes())
            .containsExactly(retainedReceipt.receipt().canonicalBytes());
        assertThat(retainedReceipt.selection().canonicalBytes())
            .containsExactly(selection.canonicalBytes());
        assertThat(retainedReceipt.authorization().source().binding().canonicalBytes())
            .containsExactly(selection.selectedCommit().canonicalBytes());
        assertThat(retainedReceipt.receipt().terminal().outcome())
            .isEqualTo(GameLogicGameplayRuleIntakeTerminal.Outcome.RETAINED);
        assertThat(
                glRepository
                    .findTerminal(retainedReceipt.authorization().operationId())
                    .orElseThrow()
                    .canonicalBytes())
            .containsExactly(retainedReceipt.receipt().terminal().canonicalBytes());
        assertThat(
                accountAccess
                    .sources()
                    .dsl
                    .fetchCount(DSL.table("account_game_logic_intake_settlements")))
            .isOne();
        assertThat(gd.dsl().fetchCount(DSL.table("game_design_selected_game_logic_receipt")))
            .isOne();

        var selectorEvidence =
            selectedPublicationWorldEvidence(
                original, appliedWorld, selectedCommit, selection, intent, publishRequestId);
        GameDesignPublicationOperation publicationOperation =
            IsolatedPublicationOperationFixtures.forSelection(
                AuthoredDraftPublishSelectionBinding.fromStored(
                    selection.canonicalJson(), selection.digest()),
                selectorEvidence);
        var selectedWorldSourceRequest =
            new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                1,
                NAMESPACE,
                UUID.randomUUID(),
                appliedWorld.application().operation().ownerBinding().intakeRequestId(),
                gd.target().canonicalTenantId());
        var selectedWorldSourceRead =
            new SourceRead(
                selectedWorldSourceRequest, worldIntakeClient.readById(selectedWorldSourceRequest));
        var publicationReservation =
            gd.transaction(
                () ->
                    new SelectedDraftPublicationOwner(gd.dsl())
                        .reserve(
                            intent,
                            publicationOperation.account(),
                            publicationOperation.world(),
                            publicationOperation.inventory(),
                            selectedWorldSourceRead));
        var retainedTemplateAssociations =
            new SelectedDraftTemplateWorldSourceAssociationRepository(gd.dsl())
                .readExact(
                    publicationReservation.operation(),
                    publicationReservation.sourceCapture().templateConfig());
        assertThat(retainedTemplateAssociations).hasSize(1);
        assertThat(retainedTemplateAssociations.getFirst().templateId()).isEqualTo(templateId);

        var publicationRequest =
            PublicationDigestRequestBinding.full(
                gd.target().canonicalTenantId().toString(),
                Long.toString(gd.target().gameDesignVersionRowId()),
                publishRequestId);
        var selectedPublicationDigests =
            new SelectedDraftPublicationDigestReadService(gd.dsl(), gd.transactions(), NAMESPACE)
                .read(NAMESPACE, publicationRequest);
        assertThat(selectedPublicationDigests.requestBinding().canonicalPreimage())
            .containsExactly(publicationRequest.canonicalPreimage());
        assertThat(selectedPublicationDigests.requestDigest())
            .isEqualTo(publicationRequest.requestDigest());
        DesignControlPlaneDigestDto actualDesignDigest =
            selectedPublicationDigests.gameDesignDigest();
        assertThat(actualDesignDigest.tenantId())
            .isEqualTo(gd.target().canonicalTenantId().toString());
        assertThat(actualDesignDigest.scopeValue()).isEqualTo(publicationRequest.versionId());
        assertThat(actualDesignDigest.appliedCommitId())
            .isEqualTo(selectedCommit.commitId().toString());
        assertThat(actualDesignDigest.digestSchemaVersion())
            .isEqualTo(SelectedDraftControlPlaneDigest.SCHEMA_VERSION);
        assertThat(actualDesignDigest.contentDigest()).matches("[0-9a-f]{64}");
        var retainedWorldDigest = selectedPublicationDigests.worldManagementDigest();
        assertThat(retainedWorldDigest.participantKey()).isEqualTo("WORLD_MANAGEMENT");
        assertThat(retainedWorldDigest.succeeded()).isTrue();
        assertThat(retainedWorldDigest.baseVersionId()).isNull();
        assertThat(retainedWorldDigest.scopeValue()).isEqualTo(publicationRequest.versionId());
        assertThat(retainedWorldDigest.appliedCommitId())
            .isEqualTo(selectedCommit.commitId().toString());
        assertThat(retainedWorldDigest.contentDigest())
            .isEqualTo(publicationOperation.world().request().contentDigest());
        assertThat(retainedWorldDigest.digestSchemaVersion())
            .isEqualTo(publicationOperation.world().request().digestSchemaVersion());
        var inventoryReader =
            new SelectedDraftAssetInventoryReadService(gd.dsl(), gd.transactions());
        SelectedDraftAssetInventory actualInventory = inventoryReader.read(publicationRequest);
        assertThat(actualInventory.assets()).hasSize(1);
        assertThat(actualInventory.assets().getFirst().usageKey())
            .isEqualTo("selected-resource.txt");
        assertThat(actualInventory.assets().getFirst().contentBytes()).containsExactly(assetBytes);
        assertThat(actualInventory.sourceFamilyDeclarations())
            .containsEntry("ORDINARY", "PRESENT")
            .containsEntry("GAMEPLAY_RULE", "PRESENT")
            .containsEntry("TEMPLATE_CONFIG", "PRESENT");
        assertThat(actualInventory.gameLogicReceipt().selection().canonicalBytes())
            .containsExactly(retainedReceipt.selection().canonicalBytes());
        assertThat(actualInventory.gameLogicReceipt().authorization().canonicalBytes())
            .containsExactly(retainedReceipt.authorization().canonicalBytes());
        assertThat(actualInventory.gameLogicReceipt().receipt().canonicalBytes())
            .containsExactly(retainedReceipt.receipt().canonicalBytes());

        var gameLogicDigestClient =
            new GameLogicClient(
                endpoints,
                pki.client("game-design-service"),
                channels,
                BlockingGrpcStubCustomizer.noop());
        ReflectionTestUtils.setField(gameLogicDigestClient, "workloadNamespace", NAMESPACE);
        ReflectionTestUtils.invokeMethod(gameLogicDigestClient, "init");
        PublishParticipantDigestDto actualGameLogicDigest;
        try (gameLogicDigestClient) {
          actualGameLogicDigest =
              gameLogicDigestClient.getDraftDesignDigestForVersion(
                  publicationRequest, retainedReceipt);
          var exactRetryDigest =
              gameLogicDigestClient.getDraftDesignDigestForVersion(
                  publicationRequest, retainedReceipt);
          assertThat(exactRetryDigest).isEqualTo(actualGameLogicDigest);
        }
        var expectedGlSourceReadBinding =
            new GameLogicPublicationSourceReadBinding(
                publicationRequest, retainedReceipt.authorization());
        assertThat(actualGameLogicDigest.succeeded()).isTrue();
        assertThat(actualGameLogicDigest.participantKey()).isEqualTo("GAME_LOGIC");
        assertThat(actualGameLogicDigest.scopeValue()).isEqualTo(publicationRequest.versionId());
        assertThat(actualGameLogicDigest.appliedCommitId())
            .isEqualTo(selectedCommit.commitId().toString());
        assertThat(actualGameLogicDigest.contentDigest()).matches("[0-9a-f]{64}");
        assertThat("sha256:" + actualGameLogicDigest.contentDigest())
            .isEqualTo(
                GameplayRuleManifest.sha256(retainedReceipt.receipt().terminal().manifestBytes()));
        assertThat(actualGameLogicDigest.digestSchemaVersion())
            .isEqualTo(GameLogicPublicationSourceReadService.DIGEST_SCHEMA_VERSION);
        assertThat(actualGameLogicDigest.abilitySchemaDigest())
            .isEqualTo(
                GameplayAbilitySchemaProjection.digest(
                    retainedReceipt.authorization().source().manifest()));
        assertThat(glPublicationReadBindings).hasSize(2);
        for (GameLogicPublicationSourceReadBinding observed : glPublicationReadBindings) {
          assertThat(observed.canonicalBytes())
              .containsExactly(expectedGlSourceReadBinding.canonicalBytes());
          assertThat(observed.authorization().canonicalBytes())
              .containsExactly(retainedReceipt.authorization().canonicalBytes());
          assertThat(observed.publicationRequest().canonicalPreimage())
              .containsExactly(publicationRequest.canonicalPreimage());
        }
        assertThat(glPublicationReadResults).hasSize(2);
        for (GameLogicPublicationSourceReadService.Result observed : glPublicationReadResults) {
          assertThat(observed.binding().canonicalBytes())
              .containsExactly(expectedGlSourceReadBinding.canonicalBytes());
          assertThat(observed.terminalBytes())
              .containsExactly(retainedReceipt.receipt().terminal().canonicalBytes());
          assertThat(observed.selectedSourceBytes())
              .containsExactly(retainedReceipt.authorization().source().canonicalBytes());
          assertThat(observed.manifestDigest())
              .isEqualTo("sha256:" + actualGameLogicDigest.contentDigest());
          assertThat(observed.abilitySchemaDigest())
              .isEqualTo(actualGameLogicDigest.abilitySchemaDigest());
        }

        var candidateService =
            new VersionAssetExportCandidateServiceImpl(
                gd.dsl(),
                new GameRepository(gd.dsl()),
                new VersionRepository(gd.dsl()),
                gd.transactions(),
                new ObjectMapper());
        var objectStore = new InMemoryConditionalObjectStore(candidateService, publicationRequest);
        objectStore.requireCandidateBeforeWrites(actualInventory);
        var properties = new AssetStoreProperties();
        properties.setEndpoint("https://objects.invalid");
        properties.setBucket("genuine-selected-export-test");
        var exporter =
            new AssetExportServiceImpl(
                new VersionAssetPublicationRepository(gd.dsl()),
                objectStore.client(),
                properties,
                new ObjectMapper(),
                gd.dsl(),
                gd.transactions(),
                candidateService);

        SelectedExportResult first = exporter.exportSelectedAssets(publicationRequest);
        assertThat(first.candidateBinding().inventoryBytes())
            .containsExactly(actualInventory.canonicalBytes());
        assertThat(first.candidateBinding().inventoryDigest()).isEqualTo(actualInventory.digest());
        assertThat(first.candidateBinding().selectedCommitDigest())
            .isEqualTo(selectedCommit.digest());
        assertThat(first.manifest().artifactDigests()).hasSize(1);
        assertThat(first.manifest().artifactDigests().getFirst().contentDigest())
            .isEqualTo(actualInventory.assets().getFirst().contentDigest());
        assertThat(first.manifest().artifactDigests().getFirst().contentDigest())
            .isEqualTo("sha256:" + sha256(assetBytes));
        assertThat(objectStore.objects()).hasSize(2);
        assertThat(objectStore.bytesAt("artifacts/sha256/" + sha256(assetBytes)))
            .containsExactly(assetBytes);
        assertThat(objectStore.contentTypeAt("artifacts/sha256/" + sha256(assetBytes)))
            .isEqualTo("text/plain");
        assertThat(
                objectStore.bytesAt(
                    "manifests/sha256/" + first.manifest().manifestHash().substring(7)))
            .containsExactly(first.candidateBinding().manifestBytes());

        SelectedExportResult retry = exporter.exportSelectedAssets(publicationRequest);
        assertThat(retry).isEqualTo(first);
        assertThat(objectStore.putCalls()).isEqualTo(4);
        assertThat(objectStore.getCalls()).isEqualTo(4);
        assertThat(candidateService.readSelectedCandidate(publicationRequest))
            .isEqualTo(first.candidateBinding());
        assertThat(gd.dsl().fetchCount(DSL.table("version_asset_export_candidate"))).isOne();

        byte[] alteredManifestBytes = alteredManifest(first.candidateBinding().manifestBytes());
        var alteredManifest =
            new ExportedAssetManifest(
                "sha256:" + sha256(alteredManifestBytes),
                1,
                first.manifest().requiredManifestAssetKeys(),
                first.manifest().artifactDigests());
        assertThatThrownBy(
                () ->
                    candidateService.recordSelectedCandidate(
                        actualInventory, alteredManifest, alteredManifestBytes))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("SELECTED_ASSET_EXPORT_CANDIDATE_CONFLICT");
        assertThat(candidateService.readSelectedCandidate(publicationRequest))
            .isEqualTo(first.candidateBinding());

        int writesBeforeSubstitution = objectStore.putCalls();
        var changedPublicationRequest =
            PublicationDigestRequestBinding.full(
                publicationRequest.tenantId(),
                publicationRequest.versionId(),
                "different-selected-publication-request");
        assertThatThrownBy(() -> exporter.exportSelectedAssets(changedPublicationRequest))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((io.grpc.StatusRuntimeException) failure).getStatus().getCode())
                        .isEqualTo(io.grpc.Status.Code.NOT_FOUND));
        assertThat(objectStore.putCalls()).isEqualTo(writesBeforeSubstitution);
        assertThat(candidateService.readSelectedCandidate(publicationRequest))
            .isEqualTo(first.candidateBinding());
        assertThat(gd.dsl().fetchCount(DSL.table("game_design_selected_game_logic_receipt")))
            .isOne();

        var mapper = Mappers.getMapper(VersionMapper.class);
        var publishAttemptRepository = new PublishAttemptRepository(gd.dsl());
        var releaseRepository = new PublishedReleaseBundleRepository(gd.dsl());
        var releaseService =
            new PublishedReleaseBundleServiceImpl(
                releaseRepository,
                new RevisionRepository(gd.dsl()),
                new VersionRepository(gd.dsl()),
                new ObjectMapper());
        var artifactService =
            new VersionAssetArtifactServiceImpl(
                new VersionAssetArtifactRepository(gd.dsl()),
                new VersionAssetPurgeWorkflowRepository(gd.dsl()),
                new VersionRepository(gd.dsl()),
                releaseRepository,
                new LaunchDescriptorRepository(gd.dsl()),
                new VersionTemplateRemapSetRepository(gd.dsl()),
                exporter,
                releaseService,
                new ObjectMapper());
        var participantDigests =
            selectedSelectorParticipantDigests(
                selectedCommit,
                gd.target().gameDesignVersionRowId(),
                retainedWorldDigest,
                actualDesignDigest,
                actualGameLogicDigest);
        var gate = mock(PublishGateService.class);
        when(gate.collectSelectedFullVersionParticipantDigests(any(), any(), any()))
            .thenReturn(participantDigests);
        var controlPlaneDigests =
            new net.firedevops.firemud.gamedesign.service.impl.ControlPlaneDigestServiceImpl(
                new net.firedevops.firemud.gamedesign.repository.GameTemplateRepository(gd.dsl()),
                new GameAssetRepository(gd.dsl()),
                new RevisionRepository(gd.dsl()),
                new ObjectMapper());
        var realAttemptPersistence =
            new PublishAttemptServiceImpl(
                publishAttemptRepository, new PublishAttemptParticipantDigestRepository(gd.dsl()));
        var publisher =
            new VersionPublishCommandServiceImpl(
                new VersionRepository(gd.dsl()),
                new GameRepository(gd.dsl()),
                publishAttemptRepository,
                mapper,
                exporter,
                transactionBoundPublishAttempts(gd, realAttemptPersistence),
                gate,
                controlPlaneDigests,
                artifactService,
                releaseService,
                mock(RecordedParticipantDigestService.class));
        var published =
            publisher.publishSelectedDraftFullVersion(
                gd.target().gameDesignVersionTenantKey(),
                gd.target().gameDesignVersionRowId(),
                intent.notes(),
                publishRequestId,
                publicationRequest.derivedWorkflowIdentity());
        assertThat(published.versionState()).isEqualTo(VersionLifecycleState.PUBLISHED);
        assertThat(
                new net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository(gd.dsl())
                    .findByPublishWorkflowId(publicationRequest.derivedWorkflowIdentity())
                    .orElseThrow()
                    .getStatus())
            .isEqualTo(net.firedevops.firemud.gamedesign.model.PublishAttemptStatus.SUCCEEDED);
        var release =
            releaseService.getPublishedReleaseBundle(
                gd.target().gameDesignVersionTenantKey(), gd.target().gameDesignVersionRowId());
        assertThat(release.attestationSchemaVersion()).isEqualTo("v2");
        assertThat(release.worldPublishedStartLocationEvidence())
            .isEqualTo(publicationOperation.world());
        var retainedGameLogicParticipant =
            release.participantDigests().stream()
                .filter(digest -> "GAME_LOGIC".equals(digest.participantKey()))
                .findFirst()
                .orElseThrow();
        assertThat(retainedGameLogicParticipant.contentDigest())
            .isEqualTo(actualGameLogicDigest.contentDigest());
        assertThat(retainedGameLogicParticipant.abilitySchemaDigest())
            .isEqualTo(actualGameLogicDigest.abilitySchemaDigest());
        assertThat(retainedGameLogicParticipant.appliedCommitId())
            .isEqualTo(selectedCommit.commitId().toString());
        assertThat(retainedGameLogicParticipant.digestSchemaVersion())
            .isEqualTo(actualGameLogicDigest.digestSchemaVersion());
        var retainedDesignParticipant =
            release.participantDigests().stream()
                .filter(digest -> "GAME_DESIGN_CONTROL_PLANE".equals(digest.participantKey()))
                .findFirst()
                .orElseThrow();
        assertThat(retainedDesignParticipant.scopeValue())
            .isEqualTo(actualDesignDigest.scopeValue());
        assertThat(retainedDesignParticipant.appliedCommitId())
            .isEqualTo(actualDesignDigest.appliedCommitId());
        assertThat(retainedDesignParticipant.contentDigest())
            .isEqualTo(actualDesignDigest.contentDigest());
        assertThat(retainedDesignParticipant.digestSchemaVersion())
            .isEqualTo(actualDesignDigest.digestSchemaVersion());
        assertThat(gd.dsl().fetchCount(DSL.table("published_release_bundle"))).isOne();
        assertThat(
                gd.dsl()
                    .fetchCount(DSL.table("game_design_start_session_launch_descriptor_binding")))
            .isZero();

        var association = retainedTemplateAssociations.getFirst();
        var pinnedSelection =
            new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                gd.target().canonicalVersionId(),
                selectedCommit.commitId(),
                publicationRequest.derivedWorkflowIdentity(),
                association.digest());
        String controlPlaneRequestId = "genuine-start-session-" + UUID.randomUUID();
        UUID ownerAttemptId = UUID.randomUUID();
        long ownerFence = 19L;
        var startSessionTuple =
            startSessionTuple(
                controlPlaneRequestId,
                gd.target().canonicalTenantId(),
                templateId,
                "Genuine selected descriptor storage proof");
        startSessionProjections.put(
            controlPlaneRequestId,
            new StartSessionProjectionBinding(startSessionTuple, ownerAttemptId, ownerFence));
        try (var projectionClient =
            new StartSessionRedeemedOperationProjectionClient(
                endpoints, pki.client("game-design-service"), channels, NAMESPACE)) {
          projectionClient.init();
          var associationReader =
              new StartSessionTemplateAssociationReadService(
                  gd.dsl(), gd.transactions(), NAMESPACE, projectionClient, releaseService);
          var descriptorProducer =
              new StartSessionLaunchDescriptorProducer(
                  gd.dsl(),
                  gd.transactions(),
                  NAMESPACE,
                  associationReader,
                  releaseService,
                  new ObjectMapper());
          var firstDescriptorBinding =
              withGameSessionPeer(
                  () ->
                      descriptorProducer.resolve(
                          startSessionReadRequest(
                              startSessionTuple,
                              ownerAttemptId,
                              ownerFence,
                              pinnedSelection,
                              UUID.randomUUID())));
          assertThat(firstDescriptorBinding.descriptor().versionId())
              .isEqualTo(gd.target().gameDesignVersionRowId());
          assertThat(firstDescriptorBinding.descriptor().launchDescriptorId()).isNotBlank();
          assertThat(firstDescriptorBinding.associationRead().association().associationDigest())
              .isEqualTo(association.digest());

          var replay =
              withGameSessionPeer(
                  () ->
                      descriptorProducer.resolve(
                          startSessionReadRequest(
                              startSessionTuple,
                              ownerAttemptId,
                              ownerFence,
                              pinnedSelection,
                              UUID.randomUUID())));
          assertThat(replay.descriptor()).isEqualTo(firstDescriptorBinding.descriptor());
          assertThat(replay.associationRead().request().readRequestId())
              .isNotEqualTo(firstDescriptorBinding.associationRead().request().readRequestId());

          var changedTuple =
              startSessionTuple(
                  controlPlaneRequestId,
                  gd.target().canonicalTenantId(),
                  templateId,
                  "Changed original StartSession tuple");
          // The fixed Account projection double returns the original operation; these altered
          // tuple/attempt/fence requests are rejected before Game Design opens its SQL snapshot.
          assertProjectionUnavailable(
              "original operation tuple",
              () ->
                  withGameSessionPeer(
                      () ->
                          descriptorProducer.resolve(
                              startSessionReadRequest(
                                  changedTuple,
                                  ownerAttemptId,
                                  ownerFence,
                                  pinnedSelection,
                                  UUID.randomUUID()))));

          assertProjectionUnavailable(
              "owner attempt",
              () ->
                  withGameSessionPeer(
                      () ->
                          descriptorProducer.resolve(
                              startSessionReadRequest(
                                  startSessionTuple,
                                  UUID.randomUUID(),
                                  ownerFence,
                                  pinnedSelection,
                                  UUID.randomUUID()))));
          assertProjectionUnavailable(
              "owner fence",
              () ->
                  withGameSessionPeer(
                      () ->
                          descriptorProducer.resolve(
                              startSessionReadRequest(
                                  startSessionTuple,
                                  ownerAttemptId,
                                  ownerFence + 1L,
                                  pinnedSelection,
                                  UUID.randomUUID()))));
          var changedSelection =
              new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                  gd.target().canonicalVersionId(),
                  selectedCommit.commitId(),
                  publicationRequest.derivedWorkflowIdentity(),
                  "sha256:" + "0".repeat(64));
          assertFailedPrecondition(
              () ->
                  withGameSessionPeer(
                      () ->
                          descriptorProducer.resolve(
                              startSessionReadRequest(
                                  startSessionTuple,
                                  ownerAttemptId,
                                  ownerFence,
                                  changedSelection,
                                  UUID.randomUUID()))));
        }
        assertThat(gd.dsl().fetchCount(DSL.table("launch_descriptor"))).isOne();
        assertThat(
                gd.dsl()
                    .fetchCount(DSL.table("game_design_start_session_launch_descriptor_binding")))
            .isOne();
        assertThat(gd.dsl().fetchCount(DSL.table("published_release_bundle"))).isOne();
        assertThat(candidateService.readSelectedCandidate(publicationRequest))
            .isEqualTo(first.candidateBinding());
      }
    } finally {
      stopAll(gameLogicServer, accountServer, worldServer, gdServer);
    }
  }

  /**
   * Entity and Automation participant rows remain stipulated test inputs. The World digest comes
   * only from the retained fixture freeze; Game Design and Game Logic use their actual owner-local
   * selected-source reads. This does not prove authenticated fresh World freeze admission or a
   * complete four-owner publication.
   */
  private static List<PublishParticipantDigestDto> selectedSelectorParticipantDigests(
      DraftCommitBinding selectedCommit,
      long versionRowId,
      PublishParticipantDigestDto worldManagementDigest,
      DesignControlPlaneDigestDto gameDesignDigest,
      PublishParticipantDigestDto gameLogicDigest) {
    return AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
        .map(
            participant -> {
              if ("WORLD_MANAGEMENT".equals(participant)) return worldManagementDigest;
              if ("GAME_LOGIC".equals(participant)) return gameLogicDigest;
              if ("GAME_DESIGN_CONTROL_PLANE".equals(participant)) {
                return new PublishParticipantDigestDto(
                    participant,
                    gameDesignDigest.scopeValue(),
                    null,
                    gameDesignDigest.appliedCommitId(),
                    gameDesignDigest.contentDigest(),
                    gameDesignDigest.digestSchemaVersion(),
                    null,
                    null,
                    null);
              }
              return new PublishParticipantDigestDto(
                  participant,
                  Long.toString(versionRowId),
                  null,
                  selectedCommit.commitId().toString(),
                  "c".repeat(64),
                  AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                      participant, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                  null,
                  null,
                  null);
            })
        .toList();
  }

  /** Uses the real owner persistence service inside the fixture's explicit READ_COMMITTED tx. */
  private static PublishAttemptService transactionBoundPublishAttempts(
      Store store, PublishAttemptServiceImpl delegate) {
    return new PublishAttemptService() {
      @Override
      public <T> T executeScriptPatchTransaction(Supplier<T> operation) {
        return delegate.executeScriptPatchTransaction(() -> store.transaction(operation));
      }

      @Override
      public <T> T executeFullVersionTransaction(Supplier<T> operation) {
        return delegate.executeFullVersionTransaction(() -> store.transaction(operation));
      }

      @Override
      public void createFullVersionAttempt(
          net.firedevops.firemud.gamedesign.dto.VersionDto version,
          String workflowId,
          String requestDigest) {
        delegate.createFullVersionAttempt(version, workflowId, requestDigest);
      }

      @Override
      public void createScriptPatchAttempt(
          net.firedevops.firemud.gamedesign.dto.VersionDto version,
          String workflowId,
          Long baseVersionId,
          String requestDigest) {
        delegate.createScriptPatchAttempt(version, workflowId, baseVersionId, requestDigest);
      }

      @Override
      public void recordScriptPatchParticipantDigests(
          String workflowId, List<PublishParticipantDigestDto> digests) {
        delegate.recordScriptPatchParticipantDigests(workflowId, digests);
      }

      @Override
      public void markScriptPatchSucceeded(String workflowId) {
        delegate.markScriptPatchSucceeded(workflowId);
      }

      @Override
      public void markScriptPatchFailed(String workflowId, String code, String message) {
        delegate.markScriptPatchFailed(workflowId, code, message);
      }

      @Override
      public java.util.Optional<net.firedevops.firemud.gamedesign.entity.PublishAttempt>
          findByPublishWorkflowId(String workflowId) {
        return delegate.findByPublishWorkflowId(workflowId);
      }

      @Override
      public void recordFullVersionParticipantDigests(
          String workflowId, List<PublishParticipantDigestDto> digests) {
        delegate.recordFullVersionParticipantDigests(workflowId, digests);
      }

      @Override
      public void markFullVersionSucceeded(String workflowId) {
        delegate.markFullVersionSucceeded(workflowId);
      }

      @Override
      public void markFullVersionFailed(String workflowId, String code, String message) {
        delegate.markFullVersionFailed(workflowId, code, message);
      }
    };
  }

  /** Test-only Account projection responder that replays a fixed original tuple and fence. */
  private static BindableService accountProjectionDouble(
      Map<String, StartSessionProjectionBinding> projections) {
    return new StartSessionOperatorAuthorizationServiceGrpc
        .StartSessionOperatorAuthorizationServiceImplBase() {
      @Override
      public void readRedeemedOperationProjection(
          ReadRedeemedOperationProjectionRequest request,
          StreamObserver<ReadRedeemedOperationProjectionResponse> responseObserver) {
        try {
          var requestedPreTuple =
              StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
                  request
                      .getCanonicalPreAuthorizationTupleBytes()
                      .toString(StandardCharsets.UTF_8));
          var original =
              Objects.requireNonNull(
                  projections.get(requestedPreTuple.controlPlaneRequestId()),
                  "No stipulated Account projection was registered for this logical operation");
          var tuple = original.tuple();
          var preTuple = tuple.preAuthorizationTuple();
          var bundle =
              StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
          var reference = tuple.bundleReference();
          Instant redeemedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
          var response =
              ReadRedeemedOperationProjectionResponse.newBuilder()
                  .setControlPlaneRequestId(preTuple.controlPlaneRequestId())
                  .setCanonicalPreAuthorizationTupleBytes(
                      ByteString.copyFrom(preTuple.canonicalJson(), StandardCharsets.UTF_8))
                  .setMutationDigest(preTuple.mutationDigest())
                  .setAuthorizationReferenceFingerprint(tuple.authorizationReferenceFingerprint())
                  .setReservationOwnerId(tuple.reservationOwnerId().toString())
                  .setReservationClaimFence(tuple.reservationClaimFence())
                  .setAuthenticatedRedeemerWorkloadIdentity(
                      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service")
                  .setOwnerAttemptId(original.ownerAttemptId().toString())
                  .setOwnerFence(original.ownerFence())
                  .setReferenceExpiresAt(timestamp(Instant.parse(bundle.authorizationExpiresAt())))
                  .setRedeemedAt(timestamp(redeemedAt))
                  .setIssuanceOperationId(bundle.issuanceOperationId().toString())
                  .setIssuanceFence(Long.parseLong(bundle.issuanceFence()))
                  .setBundleReference(
                      AuthorityEvidenceBundleReference.newBuilder()
                          .setBundleVersion(reference.bundleVersion())
                          .setSourceVersion(reference.sourceVersion())
                          .setSourceFence(reference.sourceFence())
                          .setLinearization(reference.linearization()))
                  .setAuthorityEvidenceBundle(
                      ByteString.copyFrom(tuple.authorityEvidenceBundleBytes()))
                  .build();
          responseObserver.onNext(response);
          responseObserver.onCompleted();
        } catch (RuntimeException invalid) {
          responseObserver.onError(invalid);
        }
      }
    };
  }

  private static StartSessionPostAuthorizationExecutionTuple startSessionTuple(
      String controlPlaneRequestId, UUID canonicalTenantId, long templateId, String auditReason) {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(canonicalTenantId, NAMESPACE),
            new StartSessionOperatorAction.Target(templateId, UUID.randomUUID()),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            auditReason);
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            controlPlaneRequestId, UUID.randomUUID(), action);
    var reference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
    var now = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
    var authority =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of("tenantId", canonicalTenantId.toString(), "targetNamespace", NAMESPACE),
                "actionFamily",
                preTuple.actionFamily(),
                "applicableAccountId",
                preTuple.actor().accountId().toString(),
                "applicableTenantId",
                canonicalTenantId.toString()),
            "accountProjectionEvidence",
            Map.of(
                "sourceType",
                "ACCOUNT",
                "sourceEvidenceId",
                "sha256:" + "a".repeat(64),
                "sourceEvidenceVersion",
                "17",
                "projectionStatus",
                "CURRENT",
                "evaluatedAt",
                now.toString(),
                "expiresAt",
                now.plusSeconds(300).toString()),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId",
                UUID.randomUUID().toString(),
                "controlPlaneRequestId",
                preTuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    preTuple.controlPlaneRequestId()),
                "mutationDigest",
                preTuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration",
                1L,
                "accountAuthorityGeneration",
                2L,
                "tenantAuthorityGeneration",
                Map.of(canonicalTenantId.toString(), 3L),
                "membershipAuthorityGeneration",
                Map.of(canonicalTenantId.toString(), 4L),
                "privateRealmGrantVersions",
                List.of()),
            "membershipVersion",
            Map.of(canonicalTenantId.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                preTuple.actor().accountId().toString(),
                "controlUiTokenJti",
                UUID.randomUUID().toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      byte[] authorityBytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(new ObjectMapper().writeValueAsString(authority));
      return StartSessionPostAuthorizationExecutionTuple.createHuman(
          preTuple,
          "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
          "arfp/v1/test-key/" + "b".repeat(64),
          UUID.randomUUID(),
          19L,
          authorityBytes,
          reference);
    } catch (IOException invalid) {
      throw new IllegalStateException("Could not construct stipulated Account tuple", invalid);
    }
  }

  private static StartSessionTemplateAssociationReadEvidence.Request startSessionReadRequest(
      StartSessionPostAuthorizationExecutionTuple tuple,
      UUID attemptId,
      long fence,
      StartSessionTemplateAssociationReadEvidence.Selection selection,
      UUID observationId) {
    return new StartSessionTemplateAssociationReadEvidence.Request(
        StartSessionTemplateAssociationReadEvidence.SCHEMA_VERSION,
        NAMESPACE,
        observationId,
        tuple.canonicalBytes(),
        attemptId,
        fence,
        selection);
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static <T> T withGameSessionPeer(Supplier<T> operation) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service",
            NAMESPACE,
            "game-session-service");
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return operation.get();
    } finally {
      context.detach(previous);
    }
  }

  private static void assertFailedPrecondition(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(((io.grpc.StatusRuntimeException) failure).getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.FAILED_PRECONDITION));
  }

  private static void assertProjectionUnavailable(String rejectedEvidence, Runnable operation) {
    assertThatThrownBy(operation::run)
        .as(
            "Account projection rejects changed %s before the Game Design SQL snapshot",
            rejectedEvidence)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(
            failure -> {
              var status = ((io.grpc.StatusRuntimeException) failure).getStatus();
              assertThat(status.getCode()).isEqualTo(io.grpc.Status.Code.UNAVAILABLE);
              assertThat(status.getDescription())
                  .isEqualTo("Exact redeemed StartSession projection unavailable");
            });
  }

  private Store gameDesignStore() {
    var store = serviceStore(GAME_DESIGN_POSTGRES, "game-design");
    String tenantKey = UUID.randomUUID().toString();
    UUID creationRequestId = UUID.randomUUID();
    var creation =
        Objects.requireNonNull(
            store.transaction(
                () ->
                    new GameTenantCreationRepository(store.dsl(), new GameRepository(store.dsl()))
                        .createCandidate(
                            NAMESPACE,
                            creationRequestId,
                            tenantKey,
                            "Genuine selected export Game",
                            "Persisted Game Design source and publication identity")));
    assertThat(
            new GameTenantCreationRepository(store.dsl(), new GameRepository(store.dsl()))
                .read(creationRequestId, NAMESPACE))
        .contains(creation);
    assertThat(
            new TemplateReferenceRepository(store.dsl())
                .readPhase(creation.canonicalTenantId())
                .orElseThrow()
                .phase())
        .isEqualTo(TemplateReferenceRepository.Phase.BACKFILLING);
    var version = new Version();
    version.setTenantId(tenantKey);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    var savedVersion =
        Objects.requireNonNull(
            store.transaction(() -> new VersionRepository(store.dsl()).save(version)));
    var target =
        new TargetProof(
            savedVersion.getCanonicalTenantId(),
            savedVersion.getCanonicalVersionId(),
            savedVersion.getId(),
            savedVersion.getTenantId(),
            savedVersion.getIdentitySourceGameRowId(),
            savedVersion.getIdentitySourceGameTenantKey(),
            savedVersion.getIdentitySourceProvenanceKind());
    assertThat(new GameDesignSourceRepository(store.dsl()).readGenesis(target)).isPresent();
    return store.withIdentity(target, savedVersion);
  }

  private static Store serviceStore(PostgreSQLContainer<?> postgres, String service) {
    var schema = service.replace('-', '_') + "_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    data.setSchema(schema);
    Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_" + service.replace('-', '_'))
        .placeholders(Map.of("serviceSchema", schema))
        .locations(migrationLocation(service))
        .load()
        .migrate();
    return new Store(
        DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES),
        new DataSourceTransactionManager(data),
        null,
        null);
  }

  private static String migrationLocation(String service) {
    var directoryName = service + "-service";
    for (Path current = Path.of("").toAbsolutePath();
        current != null;
        current = current.getParent()) {
      Path migrations =
          current
              .resolve("services")
              .resolve(directoryName)
              .resolve("src/main/resources/db/migration");
      if (Files.isDirectory(migrations)) return "filesystem:" + migrations;
    }
    throw new IllegalStateException("Service migration directory is unavailable: " + service);
  }

  private static DraftCommitBinding binding(TargetProof target, long assetId, String fileName)
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
                        .setName("Genuine selected room")
                        .setZoneId(zone.toString())
                        .setDescription("Selected export source room"))
                .build(),
            mutation(
                    commit, zone, region, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
                .setZone(
                    ZoneDesignMutation.newBuilder()
                        .setName("Genuine selected zone")
                        .setRegionId(region.toString()))
                .build(),
            mutation(
                    commit,
                    region,
                    region,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
                .setRegion(
                    RegionDesignMutation.newBuilder()
                        .setName("Genuine selected region")
                        .setWeather("clear")
                        .setShardId(1))
                .setFreshGraphDeclaration(declaration)
                .build());

    var revisions = new ArrayList<RevisionPayload>();
    var units = new ArrayList<AffectedUnit>();
    UUID gameplayRuleRevisionId = UUID.randomUUID();
    revisions.add(
        new RevisionPayload(
            "0",
            gameplayRuleRevisionId,
            Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.upsertPayload(
                new GameplayRuleManifest.AdmissionTag("genuine-selected-export"))));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.SCOPE,
            target.canonicalVersionId().toString(),
            GameplayRuleSource.SCOPE,
            GameplayRuleSource.SCOPE_ID,
            "0"));
    revisions.add(
        new RevisionPayload(
            "1",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            AssetSource.upsertPayload(
                Long.toString(assetId), fileName, AssetSource.Requiredness.REQUIRED)));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            AssetSource.SCOPE,
            target.canonicalVersionId().toString(),
            AssetSource.SCOPE,
            AssetSource.SCOPE_ID,
            "0"));
    var templateConfig =
        new TemplateConfigSource.Config(
            "{\"schemaVersion\":1,\"baseVersionId\":\""
                + target.canonicalVersionId()
                + "\",\"world\":{\"regions\":[],\"rooms\":[]},"
                + "\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":[{"
                + "\"family\":\"ADMISSION_TAGS\",\"key\":\"genuine-selected-export\","
                + "\"revisionId\":\""
                + gameplayRuleRevisionId
                + "\"}]},\"automation\":{\"scripts\":[],"
                + "\"scriptPatch\":{\"presence\":\"ABSENT\"}},\"supportedSettings\":[]}");
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            TemplateConfigSource.createPayload(
                "Genuine selected export template", templateConfig)));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            TemplateConfigSource.SCOPE,
            target.canonicalVersionId().toString(),
            TemplateConfigSource.SCOPE,
            TemplateConfigSource.SCOPE_ID,
            "0"));
    for (var worldMutation : mutations) {
      revisions.add(
          new RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(worldMutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().omittingInsignificantWhitespace().print(worldMutation)));
      String family =
          worldMutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              worldMutation.getAggregateId(),
              "AGGREGATE",
              worldMutation.getAggregateId(),
              "0"));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              worldMutation.getAggregateId(),
              "REGION_SUBTREE",
              worldMutation.getScopeId(),
              "0"));
    }
    return DraftCommitBinding.create(
        target, UUID.randomUUID(), commit, "base-commit-0", revisions, units);
  }

  private static WorldDesignMutationRevision.Builder mutation(
      UUID commit, UUID aggregate, UUID region, WorldDesignAggregateType type) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(aggregate.toString())
        .setAggregateType(type)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(region.toString());
  }

  private static WorldPublishedStartLocationEvidence selectedPublicationWorldEvidence(
      DraftAuthorizationFenceBinding original,
      WorldDraftGraphAppliedResult applied,
      DraftCommitBinding selectedCommit,
      AuthoredDraftPublishSelection selection,
      AuthoredDraftPublishSelection.PublishIntent intent,
      String publishRequestId) {
    var retained = applied.startLocationReceipt().orElseThrow();
    var selectorReceipt =
        WorldDraftStartLocationEvidence.create(
                retained.targetNamespace(),
                retained.operationId(),
                retained.requestId(),
                retained.commitId(),
                retained.authorizationFenceId(),
                retained.accountBindingDigest(),
                retained.bindingDigest(),
                retained.startLocation(),
                retained.graphDigest())
            .canonicalBytes();
    var tuples =
        selectedCommit.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            NAMESPACE,
            selectedCommit.target().canonicalTenantId(),
            selectedCommit.target().canonicalVersionId(),
            applied.application().operation().ownerBinding().intakeRequestId(),
            UUID.randomUUID(),
            publishRequestId,
            selection.digest().substring("sha256:".length()),
            Long.parseLong(intent.expectedVersionStateEpoch()),
            PublicationDigestRequestBinding.full(
                    selectedCommit.target().canonicalTenantId().toString(),
                    Long.toString(selectedCommit.target().gameDesignVersionRowId()),
                    publishRequestId)
                .derivedWorkflowIdentity(),
            selectedCommit.commitId().toString(),
            retained.graphDigest(),
            3,
            tuples);
    return new WorldPublishedStartLocationEvidence(
        request, selectorReceipt, original.canonicalBytes(), applied.canonicalBytes());
  }

  private static Server startServer(TestPki pki, String service, MutableHandlerRegistry handlers)
      throws Exception {
    var identity = pki.server(service);
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .fallbackHandlerRegistry(handlers)
        .maxInboundMessageSize(20 * 1024 * 1024)
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

  private static byte[] alteredManifest(byte[] original) {
    String json = new String(original, StandardCharsets.UTF_8);
    String marker = "\"selectedInventoryDigest\":\"";
    int valueStart = json.indexOf(marker);
    if (valueStart < 0) throw new IllegalStateException("Selected digest field is missing");
    valueStart += marker.length();
    int valueEnd = json.indexOf('"', valueStart);
    if (valueEnd < 0) throw new IllegalStateException("Selected digest value is malformed");
    String previous = json.substring(valueStart, valueEnd);
    String changed = previous.charAt(0) == '0' ? "1".repeat(64) : "0".repeat(64);
    return (json.substring(0, valueStart) + changed + json.substring(valueEnd))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record Store(
      DSLContext dsl,
      DataSourceTransactionManager transactions,
      TargetProof target,
      Version version) {
    <T> T transaction(Supplier<T> operation) {
      var transaction = new TransactionTemplate(transactions);
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      return transaction.execute(ignored -> operation.get());
    }

    Store withIdentity(TargetProof target, Version version) {
      return new Store(dsl, transactions, target, version);
    }
  }

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

  private record StartSessionProjectionBinding(
      StartSessionPostAuthorizationExecutionTuple tuple, UUID ownerAttemptId, long ownerFence) {}

  /** Ephemeral same-namespace mTLS identities for the four real owner boundaries. */
  private static final class TestPki {
    private final Path ca;
    private final Map<String, TestIdentity> servers = new ConcurrentHashMap<>();
    private final Map<String, TestIdentity> clients = new ConcurrentHashMap<>();

    TestPki(Path root) throws Exception {
      Files.createDirectories(root);
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var caKeys = generator.generateKeyPair();
      var caName = new org.bouncycastle.asn1.x500.X500Name("CN=Test-only selected export proof CA");
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
      for (String service :
          List.of(
              "account-service",
              "game-design-service",
              "world-management-service",
              "game-logic-service")) {
        servers.put(service, issue(root, service, true, caKeys, caCertificate));
        clients.put(service, issue(root, service, false, caKeys, caCertificate));
      }
    }

    Path ca() {
      return ca;
    }

    TestIdentity server(String service) {
      return Objects.requireNonNull(servers.get(service), "unknown test server identity");
    }

    CommonGrpcClientProperties client(String service) {
      return Objects.requireNonNull(clients.get(service), "unknown test client identity")
          .properties(ca);
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

  /**
   * Object IO only; every put still observes the committed candidate through the real GD reader.
   */
  private static final class InMemoryConditionalObjectStore {
    private final VersionAssetExportCandidateService candidates;
    private final PublicationDigestRequestBinding request;
    private final Map<String, StoredObject> stored = new ConcurrentHashMap<>();
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger gets = new AtomicInteger();
    private final S3Client client = mock(S3Client.class);
    private byte[] inventoryBytes;

    InMemoryConditionalObjectStore(
        VersionAssetExportCandidateService candidates, PublicationDigestRequestBinding request) {
      this.candidates = candidates;
      this.request = request;
      doAnswer(
              invocation -> {
                PutObjectRequest put = invocation.getArgument(0);
                RequestBody body = invocation.getArgument(1);
                puts.incrementAndGet();
                if (!"*".equals(put.ifNoneMatch())) {
                  throw new AssertionError("Selected object PUT was not conditional");
                }
                var retained = candidates.readSelectedCandidate(request);
                if (inventoryBytes == null
                    || !request.requestDigest().equals(retained.requestDigest())
                    || !Arrays.equals(request.canonicalPreimage(), retained.requestPreimage())
                    || !Arrays.equals(inventoryBytes, retained.inventoryBytes())
                    || !("sha256:" + sha256(retained.inventoryBytes()))
                        .equals(retained.inventoryDigest())
                    || !("sha256:" + sha256(retained.manifestBytes()))
                        .equals(retained.manifest().manifestHash())) {
                  throw new AssertionError(
                      "Exact durable candidate, inventory and manifest were not readable before object PUT");
                }
                byte[] bytes;
                try (var stream = body.contentStreamProvider().newStream()) {
                  bytes = stream.readAllBytes();
                }
                String key = put.key();
                StoredObject prior =
                    stored.putIfAbsent(key, new StoredObject(bytes, put.contentType()));
                if (prior != null) {
                  throw S3Exception.builder()
                      .statusCode(412)
                      .message("conditional collision")
                      .build();
                }
                return PutObjectResponse.builder().build();
              })
          .when(client)
          .putObject(any(PutObjectRequest.class), any(RequestBody.class));
      when(client.getObjectAsBytes(any(GetObjectRequest.class)))
          .thenAnswer(
              invocation -> {
                gets.incrementAndGet();
                GetObjectRequest get = invocation.getArgument(0);
                StoredObject object = stored.get(get.key());
                if (object == null) {
                  throw S3Exception.builder()
                      .statusCode(404)
                      .message("missing test object")
                      .build();
                }
                return ResponseBytes.fromByteArray(
                    GetObjectResponse.builder()
                        .contentLength((long) object.bytes().length)
                        .contentType(object.contentType())
                        .build(),
                    object.bytes());
              });
    }

    void requireCandidateBeforeWrites(SelectedDraftAssetInventory inventory) {
      inventoryBytes = inventory.canonicalBytes();
      if (!stored.isEmpty())
        throw new IllegalStateException("Test object store is already populated");
    }

    S3Client client() {
      return client;
    }

    Map<String, StoredObject> objects() {
      return Map.copyOf(stored);
    }

    byte[] bytesAt(String key) {
      return Objects.requireNonNull(stored.get(key), "missing selected test object").bytes();
    }

    String contentTypeAt(String key) {
      return Objects.requireNonNull(stored.get(key), "missing selected test object").contentType();
    }

    int putCalls() {
      return puts.get();
    }

    int getCalls() {
      return gets.get();
    }
  }

  private record StoredObject(byte[] bytes, String contentType) {
    StoredObject {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
