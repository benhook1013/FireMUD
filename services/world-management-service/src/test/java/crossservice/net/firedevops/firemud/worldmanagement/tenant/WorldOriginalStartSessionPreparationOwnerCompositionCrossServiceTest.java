package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import com.google.protobuf.Empty;
import com.google.protobuf.util.JsonFormat;
import com.zaxxer.hikari.HikariDataSource;
import integration.net.firedevops.firemud.gamesession.repository.CanonicalHumanPublicationOwnerTestFixtures;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.Context;
import io.grpc.ForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture;
import net.firedevops.firemud.accountservice.service.session.AccountOriginalStartSessionAuthorizationProofFixture;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionAcquisitionGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionSettlementGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionSettlementService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionAdmissionProtectionManualFactory;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionAdmissionProtectionSettlementService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationHistoricalReadGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationHistoricalReadService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationSettlementGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationSettlementService;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadClient;
import net.firedevops.firemud.common.account.startsession.GrpcAccountInitialAdmissionSettlementReadClient;
import net.firedevops.firemud.common.account.startsession.GrpcAccountStartSessionActivationProtectionAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.GrpcAccountStartSessionActivationProtectionSettlementClient;
import net.firedevops.firemud.common.account.startsession.GrpcAccountStartSessionWorldParticipationSettlementClient;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.CanonicalPlayerCreatedRuntimeReferenceEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationClient;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.GrpcOriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidenceReadClient;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.GrpcWorldOriginalStartSessionActivationTerminalReadClient;
import net.firedevops.firemud.common.world.GrpcWorldOriginalStartSessionProtectedActivationClient;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadClient;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRuntimeReferencePeerInterceptor;
import net.firedevops.firemud.entitymanagement.config.PreseededActorAssignmentConfiguration;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAccountIdentityPort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload.ActorKind;
import net.firedevops.firemud.entitymanagement.service.PreseededActorStagingEligibilityPort;
import net.firedevops.firemud.entitymanagement.service.impl.CanonicalPreseededActorAssignmentGrpcService;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRuntimeReferenceServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorOrigin;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.gamesession.client.CanonicalInitialPlayerLocationClient;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.client.GrpcAccountStartSessionAdmissionProtectionClient;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRegionOwnerCompositionFixture;
import net.firedevops.firemud.gamesession.repository.CanonicalHumanRuntimeCompletionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.service.AccountInitialAdmissionSettlementReadService;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalRegionBootstrapAssignmentResolver;
import net.firedevops.firemud.gamesession.service.impl.AccountInitialAdmissionSettlementReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.AuthenticatedCanonicalInitialAdmissionWorldVerifier;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameInstanceLaunchAssociationReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalInitialAdmissionOwnerReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalInitialAdmissionOwnerReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.impl.HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.OriginalStartSessionCurrentAttemptReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.ProtectedCanonicalInitialAdmissionCoordinator;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.client.EntityPlayerCreatedActorPlacementReceiptClient;
import net.firedevops.firemud.worldmanagement.client.EntityPreseededActorAssignmentReceiptClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionCanonicalInitialAdmissionOwnerClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionCanonicalInitialAdmissionOwnerClient;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImpl;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
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
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Current-default owner composition with actual Account StartSession
 * issue/redemption/participation, GS SQL attempts and authenticated current-attempt reads, and
 * World guarded graph application, first-freeze Account-binding retention, PUBLISHED terminal
 * storage/readback, and preparation. The prior publication Account binding, GDS
 * operation/terminal/release and complete-launch inputs, logging claims, creator/environment/signer
 * provisioning, and ephemeral PKI are controlled upstream evidence. This proves World owner storage
 * with those stipulated inputs, not a genuine GDS producer or authority. The positive composition
 * additionally proves actual Account participation and admission settlement, authenticated
 * activation protection, guarded World ACTIVE storage, exact Account activation settlement, actual
 * Entity assignment/projection, authenticated World placement/currentness reads, and the selected
 * Game Session first-NEW region grant, SQL fence, and durable bridge acknowledgement. Account
 * staging and published-policy source evidence, the Account lease carrier, issuer reservation,
 * stable Redis deployment-incarnation evidence, and loopback PKI remain controlled test inputs;
 * this does not establish genuine Human issuer/environment authority, production PKI, registered
 * endpoint activation, or runtime admission. The small test-only World socket invokes the typed
 * preparation owner directly, not assembly. The exact runtime installation artifact and held source
 * below are synthetic fixtures, not genuine external installation/custody evidence.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldOriginalStartSessionPreparationOwnerCompositionCrossServiceTest {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(
          WorldOriginalStartSessionPreparationOwnerCompositionCrossServiceTest.class);
  private static final String NAMESPACE = "firemud";
  private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> WORLD =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> GS =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> ACCOUNT =
      // Account's exact-original LOGIN disposal refuses nondurable PostgreSQL settings.
      new PostgreSQLContainer<>(TestContainerImages.postgres())
          .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("original-prepare-primary")
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
              "original-prepare-primary",
              "6379");

  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(longs = {9_007_199_254_740_992L, 9_007_199_254_740_993L, Long.MAX_VALUE})
  void actualOwnersPrepareAndReplayOneExactOriginalOperation(
      long reservationClaimFence, @TempDir Path proofDirectory) throws Exception {
    compose(Refusal.NONE, reservationClaimFence, proofDirectory);
  }

  @Test
  void independentActualFrozenCaptureCannotReplaceOriginalPreparationInput() throws Exception {
    compose(
        Refusal.NONE,
        9_007_199_254_740_993L,
        temporary,
        ActivationFailure.NONE,
        false,
        false,
        false,
        false,
        false,
        true);
  }

  @Test
  void twoActualOriginalOperationsRetainScopeAndRejectCrossOwnerSubstitution() throws Exception {
    String alias = "native213-second-" + UUID.randomUUID();
    var secondWriter =
        new GenericContainer<>(TestContainerImages.redis())
            .withNetwork(NETWORK)
            .withNetworkAliases(alias)
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
    var secondReplica =
        new GenericContainer<>(TestContainerImages.redis())
            .withNetwork(NETWORK)
            .dependsOn(secondWriter)
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
                alias,
                "6379");
    try (secondWriter;
        secondReplica;
        var sharedWorld = database(WORLD, "world-management-service", true, true)) {
      secondWriter.start();
      secondReplica.start();
      await()
          .atMost(Duration.ofSeconds(15))
          .until(
              () ->
                  secondWriter
                      .execInContainer("redis-cli", "INFO", "replication")
                      .getStdout()
                      .contains("connected_slaves:1"));
      // Each original fixture owns a resetting ACL user, so live fixtures require distinct writers.
      var secondRedis = new RedisEndpoint(secondWriter.getHost(), secondWriter.getMappedPort(6379));
      compose(
          Refusal.NONE,
          9_007_199_254_740_993L,
          temporary.resolve("first-owner"),
          ActivationFailure.NONE,
          false,
          true,
          false,
          false,
          false,
          false,
          new IntegritySetup(
              IntegrityMode.PAIR,
              sharedWorld,
              null,
              first ->
                  compose(
                      Refusal.NONE,
                      Long.MAX_VALUE,
                      temporary.resolve("second-owner"),
                      ActivationFailure.NONE,
                      false,
                      false,
                      false,
                      false,
                      false,
                      false,
                      new IntegritySetup(
                          IntegrityMode.PAIR,
                          sharedWorld,
                          secondRedis,
                          second -> assertActualOperationPair(first, second)))));
    }
  }

  @Test
  void actualPartialExecutionRefusesForgedCommitAndLateHandleLossRollsBackBeforeAbort()
      throws Exception {
    compose(
        Refusal.NONE,
        9_007_199_254_740_993L,
        temporary.resolve("late-handle-loss"),
        ActivationFailure.NONE,
        false,
        false,
        false,
        false,
        false,
        false,
        new IntegritySetup(IntegrityMode.LATE_HANDLE_CLOSE, null, null, null));
  }

  @Test
  void actuallyAdmittedExecutionFinishesAfterOriginalAccountExpiryAndRecoversHistorically()
      throws Exception {
    compose(
        Refusal.NONE,
        9_007_199_254_740_993L,
        temporary.resolve("admitted-expiry"),
        ActivationFailure.NONE,
        false,
        false,
        false,
        false,
        false,
        false,
        new IntegritySetup(IntegrityMode.EXPIRY, null, null, null));
  }

  @Test
  void originalGameSessionLeaseExpiresWhileActualPreparationWaitsOnOwnerLock() throws Exception {
    compose(Refusal.PREPARATION_LEASE_EXPIRED);
  }

  @Test
  void concurrentSerializedAbortWaitsForActualOriginalCommit() throws Exception {
    compose(Refusal.CONCURRENT_ABORT);
  }

  @Test
  void concurrentActualOwnerPreparationRejectsPendingDuplicateAndCommitsOriginal()
      throws Exception {
    compose(Refusal.CONCURRENT_PREPARATION);
  }

  @Test
  void actualPendingTimeoutCanBeDurablyAbortedAndCannotRestart() throws Exception {
    compose(Refusal.PREPARATION_TIMEOUT_ABORT);
  }

  @Test
  void databaseOwnerLockTimeoutRetainsPendingWithoutInventingTerminal() throws Exception {
    compose(Refusal.PREPARATION_LOCK_TIMEOUT);
  }

  @Test
  void actualCommittedPreparationRetryReturnsAfterInstallationRegistryAdvances() throws Exception {
    compose(
        Refusal.NONE,
        9_007_199_254_740_993L,
        temporary,
        ActivationFailure.NONE,
        false,
        false,
        false,
        false,
        true);
  }

  @Test
  void mismatchedOwnerAttemptCannotAcquireParticipationOrMutateWorld() throws Exception {
    compose(Refusal.MISMATCHED_ATTEMPT);
  }

  @Test
  void expiredActualOwnerLeaseCannotAcquireParticipationOrMutateWorld() throws Exception {
    compose(Refusal.EXPIRED_ATTEMPT);
  }

  @Test
  void changedOwnerAttemptCannotAcquireActivationProtectionOrMutateWorld() throws Exception {
    compose(Refusal.CHANGED_ACTIVATION_ATTEMPT);
  }

  @Test
  void expiredLeaseAfterAccountAcquisitionRetainsProtectionWithoutWorldFence() throws Exception {
    compose(Refusal.EXPIRED_AFTER_ACTIVATION_PROTECTION_ACQUISITION);
  }

  @Test
  void transientLifecycleCasRollbackRetriesWithinOriginalActivationInvocation() throws Exception {
    compose(Refusal.NONE, ActivationFailure.TRANSIENT_ROLLBACK_ONCE);
  }

  @Test
  void transientLifecycleFailureRetriesWhenItsTerminalReadbackAlsoFails() throws Exception {
    compose(Refusal.NONE, ActivationFailure.TRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE);
  }

  @Test
  void uncertainLifecycleCasCommitReturnsExactTerminalWithoutRepeatingEffects() throws Exception {
    compose(Refusal.NONE, ActivationFailure.TRANSIENT_AFTER_COMMIT_ONCE);
  }

  @Test
  void committedUncertainLifecycleFailureRetriesAfterUnavailableTerminalReadback()
      throws Exception {
    compose(Refusal.NONE, ActivationFailure.TRANSIENT_AFTER_COMMIT_ONCE_READBACK_FAILURE);
  }

  @Test
  void exhaustedTransientLifecycleCasLeavesPendingAndCannotBeRestarted() throws Exception {
    compose(Refusal.NONE, ActivationFailure.TRANSIENT_ROLLBACK_ALWAYS);
  }

  @Test
  void exhaustedTransientLifecycleFailurePreservesOriginalWhenFinalReadbackFails()
      throws Exception {
    compose(Refusal.NONE, ActivationFailure.TRANSIENT_ROLLBACK_ALWAYS_FINAL_READBACK_FAILURE);
  }

  @Test
  void nontransientLifecycleCasFailureLeavesPendingAndCannotBeRestarted() throws Exception {
    compose(Refusal.NONE, ActivationFailure.NONTRANSIENT_ROLLBACK_ONCE);
  }

  @Test
  void nontransientLifecycleFailurePreservesOriginalWhenTerminalReadbackFails() throws Exception {
    compose(Refusal.NONE, ActivationFailure.NONTRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE);
  }

  @Test
  void
      actualActiveWorldCanonicalAdmissionHoldExcludesTerminationAndRetainsPendingOnUnavailableRead()
          throws Exception {
    compose(Refusal.NONE, 9_007_199_254_740_993L, temporary, ActivationFailure.NONE, true, false);
  }

  @Test
  void actualStartingCanonicalAdmissionCompletesAndCommitsAcrossWorldGameSessionAndAccountOwners()
      throws Exception {
    compose(Refusal.NONE, 9_007_199_254_740_993L, temporary, ActivationFailure.NONE, false, true);
  }

  @Test
  void
      actualStartingCanonicalAdmissionAbortSettlesAndPreventsCommitAcrossWorldGameSessionAndAccountOwners()
          throws Exception {
    compose(
        Refusal.NONE, 9_007_199_254_740_993L, temporary, ActivationFailure.NONE, false, true, true);
  }

  @Test
  void actualWorldOwnersRetainCreatedOriginPlacementAndExactRetry() throws Exception {
    compose(
        Refusal.NONE,
        9_007_199_254_740_993L,
        temporary,
        ActivationFailure.NONE,
        false,
        true,
        false,
        true);
  }

  private enum Refusal {
    NONE,
    MISMATCHED_ATTEMPT,
    EXPIRED_ATTEMPT,
    CHANGED_ACTIVATION_ATTEMPT,
    EXPIRED_AFTER_ACTIVATION_PROTECTION_ACQUISITION,
    PREPARATION_LOCK_TIMEOUT,
    PREPARATION_TIMEOUT_ABORT,
    CONCURRENT_PREPARATION,
    CONCURRENT_ABORT,
    PREPARATION_LEASE_EXPIRED
  }

  private enum ActivationFailure {
    NONE,
    TRANSIENT_ROLLBACK_ONCE,
    TRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE,
    TRANSIENT_AFTER_COMMIT_ONCE,
    TRANSIENT_AFTER_COMMIT_ONCE_READBACK_FAILURE,
    TRANSIENT_ROLLBACK_ALWAYS,
    TRANSIENT_ROLLBACK_ALWAYS_FINAL_READBACK_FAILURE,
    NONTRANSIENT_ROLLBACK_ONCE,
    NONTRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE
  }

  private void compose(Refusal refusal) throws Exception {
    compose(refusal, 9_007_199_254_740_993L, temporary, ActivationFailure.NONE);
  }

  private void compose(Refusal refusal, long reservationClaimFence, Path proofDirectory)
      throws Exception {
    compose(refusal, reservationClaimFence, proofDirectory, ActivationFailure.NONE);
  }

  private void compose(Refusal refusal, ActivationFailure activationFailure) throws Exception {
    compose(refusal, 9_007_199_254_740_993L, temporary, activationFailure);
  }

  private void compose(
      Refusal refusal,
      long reservationClaimFence,
      Path proofDirectory,
      ActivationFailure activationFailure)
      throws Exception {
    compose(refusal, reservationClaimFence, proofDirectory, activationFailure, false, false, false);
  }

  private void compose(
      Refusal refusal,
      long reservationClaimFence,
      Path proofDirectory,
      ActivationFailure activationFailure,
      boolean exerciseCanonicalInitialAdmissionHold,
      boolean exerciseCoupledCanonicalInitialAdmission)
      throws Exception {
    compose(
        refusal,
        reservationClaimFence,
        proofDirectory,
        activationFailure,
        exerciseCanonicalInitialAdmissionHold,
        exerciseCoupledCanonicalInitialAdmission,
        false);
  }

  private void compose(
      Refusal refusal,
      long reservationClaimFence,
      Path proofDirectory,
      ActivationFailure activationFailure,
      boolean exerciseCanonicalInitialAdmissionHold,
      boolean exerciseCoupledCanonicalInitialAdmission,
      boolean abortCanonicalInitialAdmission)
      throws Exception {
    compose(
        refusal,
        reservationClaimFence,
        proofDirectory,
        activationFailure,
        exerciseCanonicalInitialAdmissionHold,
        exerciseCoupledCanonicalInitialAdmission,
        abortCanonicalInitialAdmission,
        false);
  }

  private void compose(
      Refusal refusal,
      long reservationClaimFence,
      Path proofDirectory,
      ActivationFailure activationFailure,
      boolean exerciseCanonicalInitialAdmissionHold,
      boolean exerciseCoupledCanonicalInitialAdmission,
      boolean abortCanonicalInitialAdmission,
      boolean playerCreatedPlacement)
      throws Exception {
    compose(
        refusal,
        reservationClaimFence,
        proofDirectory,
        activationFailure,
        exerciseCanonicalInitialAdmissionHold,
        exerciseCoupledCanonicalInitialAdmission,
        abortCanonicalInitialAdmission,
        playerCreatedPlacement,
        false);
  }

  private void compose(
      Refusal refusal,
      long reservationClaimFence,
      Path proofDirectory,
      ActivationFailure activationFailure,
      boolean exerciseCanonicalInitialAdmissionHold,
      boolean exerciseCoupledCanonicalInitialAdmission,
      boolean abortCanonicalInitialAdmission,
      boolean playerCreatedPlacement,
      boolean advanceInstallationBeforeRetry)
      throws Exception {
    compose(
        refusal,
        reservationClaimFence,
        proofDirectory,
        activationFailure,
        exerciseCanonicalInitialAdmissionHold,
        exerciseCoupledCanonicalInitialAdmission,
        abortCanonicalInitialAdmission,
        playerCreatedPlacement,
        advanceInstallationBeforeRetry,
        false);
  }

  private void compose(
      Refusal refusal,
      long reservationClaimFence,
      Path proofDirectory,
      ActivationFailure activationFailure,
      boolean exerciseCanonicalInitialAdmissionHold,
      boolean exerciseCoupledCanonicalInitialAdmission,
      boolean abortCanonicalInitialAdmission,
      boolean playerCreatedPlacement,
      boolean advanceInstallationBeforeRetry,
      boolean exerciseIndependentCapture)
      throws Exception {
    compose(
        refusal,
        reservationClaimFence,
        proofDirectory,
        activationFailure,
        exerciseCanonicalInitialAdmissionHold,
        exerciseCoupledCanonicalInitialAdmission,
        abortCanonicalInitialAdmission,
        playerCreatedPlacement,
        advanceInstallationBeforeRetry,
        exerciseIndependentCapture,
        new IntegritySetup(IntegrityMode.NONE, null, null, null));
  }

  private void compose(
      Refusal refusal,
      long reservationClaimFence,
      Path proofDirectory,
      ActivationFailure activationFailure,
      boolean exerciseCanonicalInitialAdmissionHold,
      boolean exerciseCoupledCanonicalInitialAdmission,
      boolean abortCanonicalInitialAdmission,
      boolean playerCreatedPlacement,
      boolean advanceInstallationBeforeRetry,
      boolean exerciseIndependentCapture,
      IntegritySetup setup)
      throws Exception {
    var integrityMode = setup.mode();
    var sharedWorld = setup.sharedWorld();
    var operationProbe = setup.operationProbe();
    var redis =
        setup.redis() == null
            ? new RedisEndpoint(REDIS.getHost(), REDIS.getMappedPort(6379))
            : setup.redis();
    Files.createDirectories(proofDirectory);
    if (abortCanonicalInitialAdmission && !exerciseCoupledCanonicalInitialAdmission) {
      throw new IllegalArgumentException("Admission abort proof requires the coupled owner path");
    }
    if (playerCreatedPlacement
        && (!exerciseCoupledCanonicalInitialAdmission || abortCanonicalInitialAdmission)) {
      throw new IllegalArgumentException(
          "Created placement proof requires the committed coupled owner path");
    }
    UUID tenant = UUID.randomUUID();
    var sharedAuthoredSource = controlledSource("controlled-upstream", tenant);
    var sharedCreation =
        CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(sharedAuthoredSource);
    long compositionStartedNanos = System.nanoTime();
    logCompositionTiming(compositionStartedNanos, "fixtures-start");
    try (var account =
            new AccountControlUiOriginalOrderFixture(
                ACCOUNT.getJdbcUrl(),
                ACCOUNT.getUsername(),
                ACCOUNT.getPassword(),
                redis.host(),
                redis.port(),
                proofDirectory.resolve("account"),
                sharedCreation,
                true);
        var ownedWorld =
            sharedWorld == null ? database(WORLD, "world-management-service", true) : null;
        var gs = database(GS, "game-session-service", true)) {
      var world = sharedWorld == null ? Objects.requireNonNull(ownedWorld) : sharedWorld;
      logCompositionTiming(compositionStartedNanos, "fixture-resources-ready");
      var issuer =
          new AccountOriginalStartSessionAuthorizationProofFixture(
              account, proofDirectory.resolve("issuer"), NAMESPACE);
      var accountDurability =
          issuer
              .sourceDsl()
              .fetchSingle(
                  "SELECT pg_is_in_recovery() AS recovery, current_setting('fsync') AS fsync,"
                      + " current_setting('synchronous_commit') AS commit_mode");
      assertThat(accountDurability.get("recovery", Boolean.class)).isFalse();
      assertThat(accountDurability.get("fsync", String.class)).isEqualTo("on");
      assertThat(accountDurability.get("commit_mode", String.class)).isEqualTo("on");
      Duration lease =
          refusal == Refusal.EXPIRED_ATTEMPT
              ? Duration.ofSeconds(30)
              : refusal == Refusal.EXPIRED_AFTER_ACTIVATION_PROTECTION_ACQUISITION
                      || refusal == Refusal.PREPARATION_LEASE_EXPIRED
                  ? Duration.ofSeconds(90)
                  : Duration.ofMinutes(5);
      var attempts = new GameSessionStartSessionOperatorAttemptRepository(gs.dsl(), lease);
      var claim =
          new AtomicReference<GameSessionStartSessionOperatorAttemptRepository.AttemptClaim>();
      var action =
          new StartSessionOperatorAction(
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
              new StartSessionOperatorAction.Scope(tenant, NAMESPACE),
              new StartSessionOperatorAction.Target(91L, issuer.creatorAccountId()),
              StartSessionOperatorAction.ExpectedVersion.ABSENT,
              new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
              "TEST_ONLY owner composition");
      String requestId = "controlled-original-prepare-" + UUID.randomUUID();
      var original =
          issuer.issueAndRedeemOriginal(
              requestId,
              action,
              UUID.randomUUID(),
              reservationClaimFence,
              new AccountOriginalStartSessionAuthorizationProofFixture.OwnerAttemptLifecycle() {
                @Override
                public AccountOriginalStartSessionAuthorizationProofFixture.OwnerClaim reserve(
                    StartSessionPostAuthorizationExecutionTuple tuple) {
                  if (integrityMode == IntegrityMode.EXPIRY) {
                    // Both real owner windows remain five minutes. A genuine delay before GS
                    // reservation separates their deadlines without editing either owner's clock.
                    var reserveAfter = databaseNow(gs.dsl()).plusSeconds(3);
                    await()
                        .atMost(Duration.ofSeconds(5))
                        .until(() -> !databaseNow(gs.dsl()).isBefore(reserveAfter));
                  }
                  var retained =
                      Objects.requireNonNull(gs.write().execute(status -> attempts.reserve(tuple)))
                          .claim()
                          .orElseThrow();
                  claim.set(retained);
                  return new AccountOriginalStartSessionAuthorizationProofFixture.OwnerClaim(
                      retained.ownerAttemptId(), retained.ownerMutationId(), retained.ownerFence());
                }

                @Override
                public void attachAccountRedemptionProjection(
                    AccountOriginalStartSessionAuthorizationProofFixture.OwnerClaim owner,
                    StartSessionPostAuthorizationExecutionTuple tuple) {
                  gs.write()
                      .executeWithoutResult(
                          status ->
                              attempts.attachAccountRedemptionProjection(
                                  claim.get(),
                                  new GameSessionStartSessionOperatorAttemptRepository
                                      .AccountRedemptionProjection(
                                      tuple.authorizationReferenceFingerprint(),
                                      tuple.authorityEvidenceBundleBytes(),
                                      net.firedevops.firemud.common.operator
                                          .StartSessionAuthorityEvidenceBundle.decode(
                                              tuple.authorityEvidenceBundleBytes())
                                          .issuanceOperationId(),
                                      Long.parseLong(tuple.issuanceFence()),
                                      tuple.canonicalBytes())));
                }
              });
      var tuple = original.originalTuple();
      assertThat(original.reservationClaimFence()).isEqualTo(reservationClaimFence);
      assertThat(tuple.reservationClaimFence()).isEqualTo(reservationClaimFence);
      assertThat(
              issuer
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT reservation_claim_fence FROM account_start_session_operator_authorizations WHERE control_plane_request_id=?",
                      tuple.controlPlaneRequestId())
                  .get(0, Long.class))
          .isEqualTo(reservationClaimFence);
      var storedAttempt =
          gs.dsl()
              .fetchSingle(
                  "SELECT post_authorization_execution_tuple,account_redemption_projection FROM game_session_start_session_operator_attempt WHERE owner_attempt_id=?",
                  claim.get().ownerAttemptId());
      assertThat(storedAttempt.get("post_authorization_execution_tuple", byte[].class))
          .containsExactly(tuple.canonicalBytes());
      assertThat(storedAttempt.get("account_redemption_projection", byte[].class))
          .containsExactly(tuple.canonicalBytes());

      var createdPublication = new AtomicReference<RealmPolicyPublishedEvidence.PublishedSet>();
      var preparedInput =
          preparationInputs(
              world,
              gs,
              tuple,
              claim.get(),
              tenant,
              playerCreatedPlacement,
              createdPublication,
              sharedAuthoredSource);
      var input =
          refusal == Refusal.MISMATCHED_ATTEMPT
              ? new WorldCanonicalInstancePreparation.Input(
                  preparedInput.gameSessionReadRequest(),
                  preparedInput.gameSessionReadEvidence(),
                  UUID.randomUUID(),
                  preparedInput.gameSessionOwnerFence(),
                  preparedInput.completeLaunchBinding(),
                  preparedInput.versionIdentity(),
                  preparedInput.topologyPlan())
              : preparedInput;
      if (exerciseIndependentCapture) {
        var secondTenant = UUID.randomUUID();
        var second =
            capturePreparationSource(
                world, secondTenant, controlledSource("independent-second-capture", secondTenant));
        var secondTopology = WorldCanonicalInstanceTopologyPlan.create(second.captured());
        assertThat(secondTopology.captureId()).isNotEqualTo(input.captureId());
        assertThat(secondTopology.tenantId()).isNotEqualTo(input.topologyPlan().tenantId());
        assertThat(secondTopology.versionId()).isNotEqualTo(input.topologyPlan().versionId());
        assertThat(secondTopology.entries()).hasSize(5);
        // Both captures are real, independently admitted World owner facts in this same schema.
        // The substituted input is intentionally incoherent; it must allocate nothing.
        var tables =
            world
                .dsl()
                .fetch(
                    "SELECT tablename FROM pg_tables WHERE schemaname=current_schema() ORDER BY tablename")
                .getValues(0, String.class)
                .toArray(String[]::new);
        var beforeSubstitution = rows(world.dsl(), tables);
        assertThatThrownBy(
                () ->
                    new WorldCanonicalInstancePreparation.Input(
                        input.gameSessionReadRequest(),
                        input.gameSessionReadEvidence(),
                        input.gameSessionOwnerAttemptId(),
                        input.gameSessionOwnerFence(),
                        input.completeLaunchBinding(),
                        input.versionIdentity(),
                        secondTopology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "Canonical preparation topology differs from its exact Version identity and frozen commit");
        assertThatThrownBy(
                () ->
                    new WorldCanonicalInstanceAssociation.Claim(
                        input.gameSessionReadRequest(),
                        input.gameSessionReadEvidence(),
                        1L,
                        input.completeLaunchBinding(),
                        second.version()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "Canonical instance claim differs from its exact World launch/source/version evidence");
        assertThat(rows(world.dsl(), tables)).isEqualTo(beforeSubstitution);
      }
      var operation =
          new WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput(
              tuple.canonicalBytes(),
              claim.get().ownerMutationId(),
              input.gameSessionOwnerAttemptId(),
              claim.get().ownerFence());
      logCompositionTiming(compositionStartedNanos, "owner-preparation-ready");
      var pki = new WorldOriginalStartSessionPreparationProofPki(proofDirectory.resolve("pki"));
      var factory = new GrpcChannelFactory();
      var currentAttemptRefusal = new AtomicReference<Status>();
      var currentAttemptReceiver =
          new OriginalStartSessionCurrentAttemptReadGrpcService(attempts, gs.manager(), NAMESPACE);
      BindableService capturingCurrentAttemptReceiver =
          () ->
              ServerInterceptors.intercept(
                  currentAttemptReceiver,
                  new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                        ServerCall<ReqT, RespT> call,
                        Metadata headers,
                        ServerCallHandler<ReqT, RespT> next) {
                      var capturingCall =
                          new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                            @Override
                            public void close(Status status, Metadata trailers) {
                              if (!status.isOk()) currentAttemptRefusal.set(status);
                              super.close(status, trailers);
                            }
                          };
                      return next.startCall(capturingCall, headers);
                    }
                  });
      var admissionCatalogs = new GameSessionCanonicalRealmCatalogRepository(gs.dsl());
      CanonicalInitialAdmissionRepository admissionOwner =
          exerciseCoupledCanonicalInitialAdmission
              ? new CanonicalInitialAdmissionRepository(
                  gs.dsl(),
                  admissionCatalogs,
                  new GameSessionCanonicalAdmissionPointerRepository(
                      gs.dsl(),
                      admissionCatalogs,
                      new GameplayAdmissionPointerEventRepository(gs.dsl())))
              : null;
      var gameSessionReceivers =
          new ArrayList<BindableService>(
              List.of(
                  capturingCurrentAttemptReceiver,
                  new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(
                      new CanonicalGameInstanceLaunchAssociationRepository(gs.dsl(), NAMESPACE),
                      NAMESPACE),
                  new CanonicalGameInstanceLaunchAssociationReadGrpcService(
                      new CanonicalGameInstanceLaunchAssociationRepository(gs.dsl(), NAMESPACE),
                      NAMESPACE)));
      if (admissionOwner != null) {
        gameSessionReceivers.add(
            new CanonicalInitialAdmissionOwnerReadGrpcService(
                admissionOwner, new CanonicalInitialAdmissionOwnerReadWorkloadGuard(NAMESPACE)));
        gameSessionReceivers.add(
            new AccountInitialAdmissionSettlementReadGrpcService(
                new AccountInitialAdmissionSettlementReadService(
                    new AccountInitialAdmissionProtectionBindingRepository(gs.dsl()),
                    attempts,
                    admissionOwner,
                    NAMESPACE),
                NAMESPACE));
      }
      Server gsServer =
          server("game-session-service", pki, gameSessionReceivers.toArray(BindableService[]::new));
      var gsEndpoints = new ServiceEndpointsProperties();
      gsEndpoints.setGameSessionService("localhost:" + gsServer.getPort());
      // Keep the World listener bound while its mutually dependent Account owners are assembled.
      // Both clients copy endpoints in their constructors, so publish the actual port first.
      try (var boundWorld = new DeferredWorldServer(pki);
          var currentAttempt =
              new GrpcOriginalStartSessionCurrentAttemptClient(
                  gsEndpoints, pki.client("account-service"), factory, NAMESPACE);
          var launchAssociation =
              new CanonicalGameInstanceLaunchAssociationClient(
                  gsEndpoints, pki.client("account-service"), factory, NAMESPACE);
          var canonicalOwnerReadClient =
              exerciseCoupledCanonicalInitialAdmission
                  ? new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
                      gsEndpoints, pki.client("world-management-service"), factory, NAMESPACE)
                  : null) {
        currentAttempt.init();
        launchAssociation.init();
        if (canonicalOwnerReadClient != null) canonicalOwnerReadClient.init();
        if (refusal == Refusal.NONE) {
          var beforeAuthenticatedRead =
              rows(gs.dsl(), "game_session_start_session_operator_attempt");
          var authenticatedCurrent =
              currentAttempt.read(
                  new net.firedevops.firemud.common.gamesession
                      .OriginalStartSessionCurrentAttemptEvidence.Request(
                      UUID.randomUUID(),
                      NAMESPACE,
                      tuple.canonicalBytes(),
                      claim.get().ownerAttemptId(),
                      claim.get().ownerMutationId(),
                      claim.get().ownerFence()));
          assertThat(authenticatedCurrent.request().originalTuple().reservationClaimFence())
              .isEqualTo(reservationClaimFence);
          assertThat(authenticatedCurrent.request().canonicalPostAuthorizationTuple())
              .containsExactly(tuple.canonicalBytes());
          assertThat(authenticatedCurrent.accountRedemptionProjection())
              .containsExactly(tuple.canonicalBytes());
          assertThat(rows(gs.dsl(), "game_session_start_session_operator_attempt"))
              .isEqualTo(beforeAuthenticatedRead);
        }

        var accountEndpoints = new ServiceEndpointsProperties();
        var worldEndpoints = new ServiceEndpointsProperties();
        worldEndpoints.setWorldManagementService("localhost:" + boundWorld.server().getPort());
        var accountWorldTerminalRead =
            new WorldStartSessionExecutionTerminalReadClient(
                worldEndpoints, pki.client("account-service"), factory, NAMESPACE);
        var accountActivationTerminalRead =
            new GrpcWorldOriginalStartSessionActivationTerminalReadClient(
                worldEndpoints, pki.client("account-service"), factory, NAMESPACE);
        var accountActivationSettlementFailure = new AtomicReference<Throwable>();
        var accountActivationSettlementReceiver =
            ServerInterceptors.intercept(
                new AccountStartSessionActivationProtectionSettlementGrpcService(
                    new AccountStartSessionActivationProtectionSettlementService(
                        new AccountStartSessionActivationProtectionRepository(issuer.sourceDsl()),
                        accountActivationTerminalRead,
                        accountManager(issuer.sourceDsl()),
                        NAMESPACE),
                    NAMESPACE),
                captureServerStatusCause(accountActivationSettlementFailure));
        var activationProtectionAcquisitionCalls = new AtomicInteger();
        BindableService activationProtectionAcquisitionReceiver =
            new AccountStartSessionActivationProtectionAcquisitionGrpcService(
                issuer.activationProtectionAcquisitionService(launchAssociation, currentAttempt),
                NAMESPACE);
        BindableService countedActivationProtectionAcquisitionReceiver =
            () ->
                ServerInterceptors.intercept(
                    activationProtectionAcquisitionReceiver,
                    new ServerInterceptor() {
                      @Override
                      public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
                          ServerCall<RequestT, ResponseT> call,
                          Metadata headers,
                          ServerCallHandler<RequestT, ResponseT> next) {
                        if (call.getMethodDescriptor()
                            .getFullMethodName()
                            .endsWith("/AcquireOriginalStartSessionActivationProtection")) {
                          activationProtectionAcquisitionCalls.incrementAndGet();
                        }
                        return next.startCall(call, headers);
                      }
                    });
        Server accountServer =
            buildServer(
                "account-service",
                pki,
                0,
                () -> accountActivationSettlementReceiver,
                new AccountStartSessionWorldParticipationAcquisitionGrpcService(
                    issuer.worldParticipationAcquisitionService(currentAttempt), NAMESPACE),
                countedActivationProtectionAcquisitionReceiver,
                new AccountStartSessionWorldParticipationSettlementGrpcService(
                    new AccountStartSessionWorldParticipationSettlementService(
                        new AccountStartSessionWorldParticipationRepository(issuer.sourceDsl()),
                        accountWorldTerminalRead,
                        accountManager(issuer.sourceDsl()),
                        NAMESPACE),
                    NAMESPACE),
                new AccountStartSessionWorldParticipationHistoricalReadGrpcService(
                    new AccountStartSessionWorldParticipationHistoricalReadService(
                        new AccountStartSessionWorldParticipationRepository(issuer.sourceDsl()),
                        accountManager(issuer.sourceDsl()),
                        NAMESPACE),
                    NAMESPACE));
        accountEndpoints.setAccountService("localhost:" + accountServer.getPort());
        try (var acquisition =
                new AccountStartSessionWorldParticipationAcquisitionClient(
                    accountEndpoints, pki.client("world-management-service"), factory, NAMESPACE);
            var worldParticipationSettlement =
                new GrpcAccountStartSessionWorldParticipationSettlementClient(
                    accountEndpoints, pki.client("world-management-service"), factory, NAMESPACE);
            var worldProtectionAcquisition =
                new GrpcAccountStartSessionActivationProtectionAcquisitionClient(
                    accountEndpoints, pki.client("world-management-service"), factory, NAMESPACE);
            var worldProtectionSettlement =
                new GrpcAccountStartSessionActivationProtectionSettlementClient(
                    accountEndpoints, pki.client("world-management-service"), factory, NAMESPACE);
            var worldProtectedActivation =
                new GrpcWorldOriginalStartSessionProtectedActivationClient(
                    worldEndpoints, pki.client("game-session-service"), factory, NAMESPACE);
            var historicalAccount =
                new AccountStartSessionWorldParticipationHistoricalReadClient(
                    accountEndpoints, pki.client("world-management-service"), factory, NAMESPACE);
            accountWorldTerminalRead;
            accountActivationTerminalRead;
            var historicalGs =
                new HistoricalOriginalStartSessionOwnerEvidenceReadClient(
                    gsEndpoints, pki.client("world-management-service"), factory, NAMESPACE)) {
          acquisition.init();
          worldParticipationSettlement.init();
          worldProtectionAcquisition.init();
          worldProtectionSettlement.init();
          historicalAccount.init();
          historicalGs.init();
          var integrity = new IntegrityControl(integrityMode, world);
          var association =
              Mockito.spy(
                  new WorldCanonicalInstanceAssociationRepository(
                      world.dsl(),
                      new WorldCompleteLaunchBindingRepository(world.dsl()),
                      new WorldAuthoredSourceIntakeRepository(world.dsl()),
                      new WorldAuthoredVersionIdentityRepository(world.dsl())));
          if (integrityMode == IntegrityMode.LATE_HANDLE_CLOSE) {
            Mockito.doAnswer(
                    invocation -> {
                      integrity.probeBeforeRealAssociation(invocation.getArgument(0));
                      return invocation.callRealMethod();
                    })
                .when(association)
                .retainClaimInOwnerTransaction(
                    org.mockito.ArgumentMatchers.any(
                        WorldCanonicalInstanceAssociation.Claim.class));
          }
          var repository =
              Mockito.spy(
                  new WorldCanonicalInstancePreparationRepository(
                      world.dsl(),
                      world.manager(),
                      association,
                      new WorldCanonicalFrozenTopologyRepository(
                          world.dsl(),
                          new WorldAuthoredGraphSnapshotRepository(world.dsl()),
                          new WorldDraftTopologyCommitRepository(
                              world.dsl(),
                              new WorldDesignPublicationFenceRepository(
                                  world.dsl(),
                                  new WorldAuthoredSourceIntakeRepository(world.dsl())),
                              JSON),
                          digestService(world.dsl())),
                      Duration.ofSeconds(
                          (refusal == Refusal.PREPARATION_LOCK_TIMEOUT
                                  || refusal == Refusal.PREPARATION_TIMEOUT_ABORT)
                              ? 3
                              : refusal == Refusal.PREPARATION_LEASE_EXPIRED ? 120 : 10)));
          var candidateReads = new AtomicInteger();
          Mockito.doAnswer(
                  invocation -> {
                    candidateReads.incrementAndGet();
                    return invocation.callRealMethod();
                  })
              .when(repository)
              .hasRetainedCommittedCandidate(
                  org.mockito.ArgumentMatchers.any(WorldCanonicalInstancePreparation.Input.class),
                  org.mockito.ArgumentMatchers.any(
                      WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput.class));
          var verifier = new WorldStartSessionAccountParticipationVerifier(NAMESPACE, acquisition);
          var identity = new AtomicReference<WorldCanonicalInstanceExecutionIdentity>();
          var verificationCalls = new AtomicInteger();
          WorldCanonicalInstancePreparationService.OriginalOperationCommitAuthorityVerifier
              verifiedOwner =
                  (supplied, suppliedOperation) -> {
                    verificationCalls.incrementAndGet();
                    var held = verifier.verifyAndHold(supplied, suppliedOperation);
                    identity.set(held.executionIdentity());
                    return integrity.wrapRealAuthority(held);
                  };
          var historicalVerifier =
              new WorldCanonicalInstanceOriginalOperationRecoveryVerifier(
                  historicalAccount, historicalGs);
          java.util.function.Supplier<WorldCanonicalInstancePreparationService> serviceFactory =
              () ->
                  new WorldCanonicalInstancePreparationService(
                      repository, verifiedOwner, historicalVerifier, world.installationRegistry());
          var service = new AtomicReference<>(serviceFactory.get());
          var result = new AtomicReference<WorldCanonicalInstancePreparation.Result>();
          var ownerRefusal = new AtomicReference<RuntimeException>();
          var recovering = new AtomicBoolean();
          var recovered =
              new AtomicReference<
                  net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal>();
          MethodDescriptor<Empty, Empty> method =
              MethodDescriptor.<Empty, Empty>newBuilder()
                  .setType(MethodDescriptor.MethodType.UNARY)
                  .setFullMethodName("controlled.proof.World/PrepareOriginal")
                  .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                  .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                  .build();
          // Controlled transport wrapper only. The real World verifier authenticates the peer;
          // Account and GS receivers below are their actual unregistered owner adapters.
          var observedInput = new AtomicReference<>(input);
          var observedOperation = new AtomicReference<>(operation);
          var socket =
              ServerServiceDefinition.builder("controlled.proof.World")
                  .addMethod(
                      method,
                      ServerCalls.asyncUnaryCall(
                          (Empty ignored, io.grpc.stub.StreamObserver<Empty> observer) -> {
                            try {
                              var peer = GrpcPeerIdentity.current();
                              if (peer == null
                                  || !("spiffe://firemud/ns/"
                                          + NAMESPACE
                                          + "/sa/game-session-service")
                                      .equals(peer.uri()))
                                throw new SecurityException(
                                    "Controlled socket requires the exact GS peer");
                              if (recovering.get())
                                recovered.set(
                                    service
                                        .get()
                                        .recoverExactTerminal(identity.get())
                                        .orElseThrow());
                              else
                                result.set(
                                    service
                                        .get()
                                        .prepare(observedInput.get(), observedOperation.get()));
                              observer.onNext(Empty.getDefaultInstance());
                              observer.onCompleted();
                            } catch (RuntimeException denied) {
                              ownerRefusal.set(denied);
                              observer.onError(
                                  Status.FAILED_PRECONDITION
                                      .withDescription("Original World preparation denied")
                                      .withCause(denied)
                                      .asRuntimeException());
                            }
                          }))
                  .build();
          var lifecycleRepository =
              new WorldCanonicalInstanceLifecycleReadRepository(
                  world.dsl(), world.manager(), association);
          WorldCanonicalInitialAdmissionHoldOwner canonicalHoldOwner =
              admissionOwner == null
                  ? null
                  : new WorldCanonicalInitialAdmissionHoldOwner(
                      new WorldCanonicalInitialAdmissionHoldRepository(
                          world.dsl(), world.manager(), association, lifecycleRepository),
                      canonicalOwnerReadClient);
          var terminalReadbackFault = new ActivationTerminalReadbackFault();
          var activationTransactionFault =
              activationFailure == ActivationFailure.NONE
                  ? null
                  : new ActivationTransactionFault(
                      world.manager(), world.dsl(), activationFailure, terminalReadbackFault);
          var activationRepository =
              new WorldCanonicalInstanceActivationRepository(
                  world.dsl(),
                  activationTransactionFault == null ? world.manager() : activationTransactionFault,
                  lifecycleRepository);
          var activationService =
              new WorldCanonicalInstanceActivationService(
                  activationRepository, world.installationRegistry());
          var acquiredActivationProtection =
              new AtomicReference<AccountStartSessionActivationProtectionEvidence>();
          var accountProtectionRowsAfterAcquisition =
              new AtomicReference<Map<String, List<String>>>();
          AccountStartSessionActivationProtectionAcquisitionClient activationProtectionClient =
              acquisitionInput -> {
                var evidence = worldProtectionAcquisition.acquire(acquisitionInput);
                if (refusal == Refusal.EXPIRED_AFTER_ACTIVATION_PROTECTION_ACQUISITION) {
                  // Account has committed its real protection; expire only the original lease.
                  acquiredActivationProtection.set(evidence);
                  accountProtectionRowsAfterAcquisition.set(
                      rows(
                          issuer.sourceDsl(),
                          "account_start_session_activation_protections",
                          "account_start_session_activation_protection_sources",
                          "account_start_session_activation_protection_settlements"));
                  await()
                      .atMost(Duration.ofSeconds(120))
                      .until(
                          () ->
                              gs.dsl()
                                  .fetchSingle(
                                      "SELECT lease_expires_at <= clock_timestamp() "
                                          + "FROM game_session_start_session_operator_attempt "
                                          + "WHERE owner_attempt_id=?",
                                      claim.get().ownerAttemptId())
                                  .get(0, Boolean.class));
                }
                return evidence;
              };
          var protectedActivationService =
              new WorldOriginalStartSessionProtectedActivationService(
                  activationRepository, activationService, activationProtectionClient, NAMESPACE);
          var worldReceivers =
              new ArrayList<BindableService>(
                  List.of(
                      new WorldStartSessionExecutionTerminalReadGrpcService(
                          service.get(), NAMESPACE),
                      new WorldOriginalStartSessionProtectedActivationGrpcService(
                          protectedActivationService, NAMESPACE),
                      new WorldOriginalStartSessionActivationTerminalReadGrpcService(
                          activationService, NAMESPACE)));
          if (canonicalHoldOwner != null) {
            worldReceivers.add(
                new WorldCanonicalInitialAdmissionHoldGrpcService(canonicalHoldOwner, NAMESPACE));
          }
          boundWorld.addService(socket);
          for (var receiver : worldReceivers) boundWorld.addService(receiver.bindService());
          Server worldServer = boundWorld.server();
          accountWorldTerminalRead.init();
          accountActivationTerminalRead.init();
          worldProtectedActivation.init();
          var channel =
              factory.buildChannel(
                  "localhost:" + worldServer.getPort(),
                  6565,
                  pki.client("game-session-service"),
                  false);
          try {
            logCompositionTiming(compositionStartedNanos, "transport-ready");
            Map<String, List<String>> worldBefore =
                rows(
                    world.dsl(),
                    "world_instance",
                    "world_canonical_instance_association",
                    "world_canonical_instance_preparation",
                    "world_canonical_instance_topology_identity",
                    "region_instance",
                    "zone_instance",
                    "room_instance",
                    "room_instance_exit",
                    "world_canonical_instance_execution",
                    "world_canonical_instance_activation_fence",
                    "world_runtime_installation_registration",
                    "world_runtime_installation_current",
                    "world_runtime_required_owner_snapshot");
            Map<String, List<String>> accountBefore =
                rows(
                    issuer.sourceDsl(),
                    "account_start_session_operator_authorizations",
                    "account_start_session_world_participations",
                    "account_start_session_world_original_attempt_evidence",
                    "account_start_session_world_participation_sources",
                    "account_start_session_world_participation_settlements",
                    "account_start_session_activation_protections",
                    "account_start_session_activation_protection_sources",
                    "account_start_session_activation_protection_settlements");
            Map<String, List<String>> gsBefore =
                rows(
                    gs.dsl(),
                    "game_session_start_session_operator_attempt",
                    "game_session_start_session_template_association_pin",
                    "game_session_start_session_launch_descriptor_pin",
                    "game_session_canonical_instance_launch");
            if (refusal == Refusal.EXPIRED_ATTEMPT) {
              // The actual owner lease expires naturally; no SQL lease/history edits are used.
              await()
                  .atMost(Duration.ofSeconds(60))
                  .until(
                      () ->
                          gs.dsl()
                              .fetchSingle(
                                  "SELECT lease_expires_at <= clock_timestamp() FROM game_session_start_session_operator_attempt WHERE owner_attempt_id=?",
                                  claim.get().ownerAttemptId())
                              .get(0, Boolean.class));
            }
            if (refusal == Refusal.NONE
                || refusal == Refusal.CHANGED_ACTIVATION_ATTEMPT
                || refusal == Refusal.EXPIRED_AFTER_ACTIVATION_PROTECTION_ACQUISITION) {
              // Exercise lifecycle readback with boxed room IDs outside the Long cache range.
              world
                  .dsl()
                  .execute(
                      "SELECT setval((current_schema() || '.room_instance_id_seq')::regclass, GREATEST((SELECT last_value FROM room_instance_id_seq), 127), true)");
            }
            if (refusal == Refusal.MISMATCHED_ATTEMPT || refusal == Refusal.EXPIRED_ATTEMPT) {
              assertThatThrownBy(
                      () ->
                          ClientCalls.blockingUnaryCall(
                              channel,
                              method,
                              CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                              Empty.getDefaultInstance()))
                  .isInstanceOf(StatusRuntimeException.class);
              assertThat(result.get()).isNull();
              assertThat(ownerRefusal.get()).isNotNull();
              assertThat(ownerRefusal.get()).isInstanceOf(StatusRuntimeException.class);
              assertThat(Status.fromThrowable(ownerRefusal.get()).getCode())
                  .isEqualTo(Status.Code.FAILED_PRECONDITION);
              Status originalAttemptRefusal = currentAttemptRefusal.get();
              assertThat(originalAttemptRefusal).isNotNull();
              assertThat(originalAttemptRefusal.getCode())
                  .isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(originalAttemptRefusal.getDescription())
                  .isEqualTo(
                      refusal == Refusal.MISMATCHED_ATTEMPT
                          ? "Current original-attempt read differs from the stored attempt, mutation, or fence"
                          : "Game Session owner attempt claim lease is expired and remains non-replayable");
              assertThat(rows(world.dsl(), worldBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(worldBefore);
              assertThat(rows(issuer.sourceDsl(), accountBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(accountBefore);
              assertThat(rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(gsBefore);
              return;
            }
            if (refusal == Refusal.PREPARATION_LOCK_TIMEOUT
                || refusal == Refusal.PREPARATION_TIMEOUT_ABORT
                || refusal == Refusal.CONCURRENT_PREPARATION
                || refusal == Refusal.CONCURRENT_ABORT
                || refusal == Refusal.PREPARATION_LEASE_EXPIRED) {
              try (var blocker = world.pool().getConnection();
                  var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                blocker.setAutoCommit(false);
                int blockerPid;
                try (var query = blocker.createStatement();
                    var pid = query.executeQuery("SELECT pg_backend_pid()")) {
                  assertThat(pid.next()).isTrue();
                  blockerPid = pid.getInt(1);
                }
                try (var lock =
                    blocker.prepareStatement(
                        "SELECT 1 FROM world_design_publication_fence_owner WHERE local_tenant_key=? AND version_id=? FOR UPDATE")) {
                  lock.setLong(
                      1, input.completeLaunchBinding().sourceIntakeReceipt().localTenantKey());
                  lock.setLong(2, input.versionIdentity().localVersionKey());
                  try (var locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                  }
                }
                try {
                  var attempt =
                      executor.submit(
                          () ->
                              ClientCalls.blockingUnaryCall(
                                  channel,
                                  method,
                                  CallOptions.DEFAULT.withDeadlineAfter(
                                      refusal == Refusal.PREPARATION_LEASE_EXPIRED ? 130 : 15,
                                      TimeUnit.SECONDS),
                                  Empty.getDefaultInstance()));
                  await()
                      .atMost(Duration.ofSeconds(10))
                      .until(
                          () ->
                              world
                                  .dsl()
                                  .fetchSingle(
                                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND ? = ANY(pg_blocking_pids(pid)))",
                                      blockerPid)
                                  .get(0, Boolean.class));
                  if (refusal == Refusal.PREPARATION_LEASE_EXPIRED) {
                    assertThat(identity.get().originalGameSessionLeaseExpiresAt())
                        .isAfter(Instant.now());
                    await()
                        .atMost(Duration.ofSeconds(100))
                        .until(
                            () ->
                                gs.dsl()
                                    .fetchSingle(
                                        "SELECT lease_expires_at <= clock_timestamp() FROM game_session_start_session_operator_attempt WHERE owner_attempt_id=?",
                                        claim.get().ownerAttemptId())
                                    .get(0, Boolean.class));
                    assertThat(identity.get().originalAuthorizationExpiry()).isAfter(Instant.now());
                    blocker.rollback();
                    assertThatThrownBy(() -> attempt.get(15, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class);
                    assertThat(result.get()).isNull();
                    Throwable failure = ownerRefusal.get();
                    assertThat(failure).isNotNull();
                    java.sql.SQLException sqlFailure = null;
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                      if (cause instanceof java.sql.SQLException sql) {
                        sqlFailure = sql;
                      }
                    }
                    assertThat((Throwable) sqlFailure).isNotNull();
                    assertThat(sqlFailure.getSQLState()).isEqualTo("23514");
                    assertThat(sqlFailure.getMessage())
                        .contains(
                            "Original StartSession authorization or Game Session lease expired before World execution admission");
                    assertThat(
                            repository
                                .readExactExecution(identity.get())
                                .operation()
                                .orElseThrow()
                                .state())
                        .isEqualTo(
                            WorldCanonicalInstancePreparationRepository.ExecutionState.PENDING);
                    for (String table :
                        List.of(
                            "world_instance",
                            "world_canonical_instance_association",
                            "world_canonical_instance_preparation")) {
                      assertThat(world.dsl().fetchCount(org.jooq.impl.DSL.table(table))).isZero();
                    }
                    assertThat(rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new)))
                        .isEqualTo(gsBefore);
                    return;
                  }
                  if (refusal == Refusal.CONCURRENT_ABORT) {
                    var abort =
                        executor.submit(() -> repository.recordDurableAbort(identity.get()));
                    await()
                        .atMost(Duration.ofSeconds(5))
                        .until(
                            () ->
                                world
                                    .dsl()
                                    .fetchSingle(
                                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity a JOIN pg_stat_activity original ON original.pid = ANY(pg_blocking_pids(a.pid)) WHERE a.wait_event_type='Lock' AND ? = ANY(pg_blocking_pids(original.pid)))",
                                        blockerPid)
                                    .get(0, Boolean.class));
                    assertThat(abort.isDone()).isFalse();
                    blocker.rollback();
                    attempt.get(15, TimeUnit.SECONDS);
                    var terminal = abort.get(15, TimeUnit.SECONDS);
                    assertThat(terminal.state())
                        .isEqualTo(
                            WorldCanonicalInstancePreparationRepository.ExecutionState.COMMITTED);
                    assertThat(result.get()).isNotNull();
                    assertThat(terminal.worldInstanceId())
                        .isEqualTo(result.get().association().worldInstanceId());
                    assertThat(repository.readExactExecution(identity.get()).operation())
                        .contains(terminal);
                    assertThat(association.readOwnerAssociation(input.canonicalGameInstanceId()))
                        .contains(result.get().association());
                    for (String table :
                        List.of(
                            "world_instance",
                            "world_canonical_instance_association",
                            "world_canonical_instance_preparation",
                            "world_canonical_instance_execution")) {
                      assertThat(world.dsl().fetchCount(org.jooq.impl.DSL.table(table)))
                          .isEqualTo(1);
                    }
                    assertThat(rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new)))
                        .isEqualTo(gsBefore);
                    return;
                  }
                  if (refusal == Refusal.CONCURRENT_PREPARATION) {
                    assertThat(
                            repository
                                .readExactExecution(identity.get())
                                .operation()
                                .orElseThrow()
                                .state())
                        .isEqualTo(
                            WorldCanonicalInstancePreparationRepository.ExecutionState.PENDING);
                    assertThatThrownBy(
                            () ->
                                ClientCalls.blockingUnaryCall(
                                    channel,
                                    method,
                                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                                    Empty.getDefaultInstance()))
                        .isInstanceOf(StatusRuntimeException.class);
                    assertThat(ownerRefusal.get())
                        .isInstanceOf(
                            WorldCanonicalInstancePreparationRepository
                                .ConflictingPreparationException.class)
                        .hasMessage(
                            "Exact original World execution has a retained PENDING intent; only serialized ABORTED recovery may resolve it");
                    assertThat(result.get()).isNull();
                    blocker.rollback();
                    attempt.get(15, TimeUnit.SECONDS);
                    assertThat(result.get()).isNotNull();
                    assertThat(
                            repository
                                .readExactExecution(identity.get())
                                .operation()
                                .orElseThrow()
                                .state())
                        .isEqualTo(
                            WorldCanonicalInstancePreparationRepository.ExecutionState.COMMITTED);
                    assertThat(association.readOwnerAssociation(input.canonicalGameInstanceId()))
                        .contains(result.get().association());
                    for (String table :
                        List.of(
                            "world_instance",
                            "world_canonical_instance_association",
                            "world_canonical_instance_preparation",
                            "world_canonical_instance_execution")) {
                      assertThat(world.dsl().fetchCount(org.jooq.impl.DSL.table(table)))
                          .isEqualTo(1);
                    }
                    assertThat(rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new)))
                        .isEqualTo(gsBefore);
                    return;
                  }
                  assertThatThrownBy(() -> attempt.get(15, TimeUnit.SECONDS))
                      .isInstanceOf(java.util.concurrent.ExecutionException.class);
                  assertThat(result.get()).isNull();
                  Throwable failure = ownerRefusal.get();
                  assertThat(failure).isNotNull();
                  java.sql.SQLException sqlFailure = null;
                  for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof java.sql.SQLException sql) {
                      sqlFailure = sql;
                    }
                  }
                  assertThat((Throwable) sqlFailure).isNotNull();
                  assertThat(sqlFailure.getSQLState()).isIn("55P03", "57014");
                } finally {
                  blocker.rollback();
                }
              }
              assertThat(
                      repository
                          .readExactExecution(identity.get())
                          .operation()
                          .orElseThrow()
                          .state())
                  .isEqualTo(WorldCanonicalInstancePreparationRepository.ExecutionState.PENDING);
              assertThat(association.readOwnerAssociation(input.canonicalGameInstanceId()))
                  .isEmpty();
              assertThat(world.dsl().fetchCount(org.jooq.impl.DSL.table("world_instance")))
                  .isZero();
              assertThat(
                      world
                          .dsl()
                          .fetchCount(
                              org.jooq.impl.DSL.table("world_canonical_instance_preparation")))
                  .isZero();
              assertThat(rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(gsBefore);
              if (refusal == Refusal.PREPARATION_TIMEOUT_ABORT) {
                var aborted = repository.recordDurableAbort(identity.get());
                assertThat(aborted.state())
                    .isEqualTo(WorldCanonicalInstancePreparationRepository.ExecutionState.ABORTED);
                var abortRows = rows(world.dsl(), worldBefore.keySet().toArray(String[]::new));
                assertThat(repository.recordDurableAbort(identity.get())).isEqualTo(aborted);
                assertThat(repository.readExactExecution(identity.get()).operation())
                    .contains(aborted);
                assertThat(rows(world.dsl(), worldBefore.keySet().toArray(String[]::new)))
                    .isEqualTo(abortRows);
                ownerRefusal.set(null);
                assertThatThrownBy(
                        () ->
                            ClientCalls.blockingUnaryCall(
                                channel,
                                method,
                                CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                                Empty.getDefaultInstance()))
                    .isInstanceOf(StatusRuntimeException.class);
                assertThat(ownerRefusal.get())
                    .isInstanceOf(
                        WorldCanonicalInstancePreparationRepository.ConflictingPreparationException
                            .class)
                    .hasMessageContaining("ABORTED");
                assertThat(result.get()).isNull();
                assertThat(rows(world.dsl(), worldBefore.keySet().toArray(String[]::new)))
                    .isEqualTo(abortRows);
                assertThat(rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new)))
                    .isEqualTo(gsBefore);
              }
              return;
            }
            if (integrityMode == IntegrityMode.LATE_HANDLE_CLOSE) {
              assertLateHandleRollbackAndAbort(
                  world,
                  gs,
                  issuer.sourceDsl(),
                  input,
                  operation,
                  identity,
                  repository,
                  integrity,
                  service,
                  serviceFactory,
                  observedInput,
                  observedOperation,
                  result,
                  ownerRefusal,
                  channel,
                  method);
              return;
            }
            if (integrityMode == IntegrityMode.EXPIRY) {
              // The ordinary ten-second caller budget is unchanged. The uninterrupted owner
              // continues after reply loss; only actual durable storage earns completion credit.
              assertThatThrownBy(
                      () ->
                          ClientCalls.blockingUnaryCall(
                              channel,
                              method,
                              CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                              Empty.getDefaultInstance()))
                  .isInstanceOfSatisfying(
                      StatusRuntimeException.class,
                      denied ->
                          assertThat(denied.getStatus().getCode())
                              .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
              Instant expiry =
                  net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.decode(
                          tuple.authorityEvidenceBundleBytes())
                      .expiresAt();
              await()
                  .atMost(Duration.between(Instant.now(), expiry).plusSeconds(30))
                  .pollInterval(Duration.ofMillis(100))
                  .until(() -> result.get() != null || ownerRefusal.get() != null);
              if (result.get() == null)
                throw new IllegalStateException(
                    "Real admitted expiry execution did not commit after reply loss",
                    ownerRefusal.get());
            } else {
              try {
                ClientCalls.blockingUnaryCall(
                    channel,
                    method,
                    CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                    Empty.getDefaultInstance());
              } catch (StatusRuntimeException remoteFailure) {
                throw new IllegalStateException(
                    "Actual World owner refused supported preparation",
                    ownerRefusal.get() == null ? remoteFailure : ownerRefusal.get());
              }
            }
            var prepared = result.get();
            assertThat(prepared).isNotNull();
            assertThat(prepared.startLocation())
                .isEqualTo(
                    input
                        .topologyPlan()
                        .sourceBinding()
                        .plan()
                        .graph()
                        .freshGraphDeclaration()
                        .orElseThrow()
                        .startLocation());
            assertThat(prepared.runtimeRoomInstanceId()).isPositive();
            assertThat(prepared.runtimeRoomInstanceId()).isGreaterThan(127L);
            assertMaterializedTopologyAndAssociation(world.dsl(), input, prepared, association);
            if (integrityMode == IntegrityMode.EXPIRY) {
              assertAdmittedExpiryRecovery(
                  world,
                  gs,
                  issuer.sourceDsl(),
                  identity.get(),
                  repository,
                  prepared,
                  integrity,
                  verificationCalls,
                  accountWorldTerminalRead);
              return;
            }
            assertThat(
                    world
                        .dsl()
                        .fetchSingle(
                            "SELECT s.required_owners="
                                + "world_runtime_required_owners(r.artifact_bytes)"
                                + " AND s.registry_revision=c.revision"
                                + " AND s.artifact_digest=r.artifact_digest"
                                + " AND jsonb_array_length(s.required_owners)=1"
                                + " AND s.required_owners->0->>'ownerId'='world-management-service'"
                                + " FROM world_runtime_required_owner_snapshot s"
                                + " JOIN world_runtime_installation_current c"
                                + " ON c.target_namespace=s.target_namespace"
                                + " JOIN world_runtime_installation_registration r ON r.revision=c.revision"
                                + " WHERE s.canonical_game_instance_id=?",
                            input.canonicalGameInstanceId())
                        .get(0, Boolean.class))
                .as(
                    "complete exact synthetic installation owner fixture frozen before materialization")
                .isTrue();
            var after = rows(world.dsl(), worldBefore.keySet().toArray(String[]::new));
            var protectedAccount =
                rows(issuer.sourceDsl(), accountBefore.keySet().toArray(String[]::new));
            if (advanceInstallationBeforeRetry) {
              long firstRevision =
                  world
                      .dsl()
                      .fetchSingle(
                          "SELECT revision FROM world_runtime_installation_current WHERE target_namespace=?",
                          NAMESPACE)
                      .get(0, Long.class);
              long frozenRevision =
                  world
                      .dsl()
                      .fetchSingle(
                          "SELECT registry_revision FROM world_runtime_required_owner_snapshot WHERE canonical_game_instance_id=?",
                          input.canonicalGameInstanceId())
                      .get(0, Long.class);
              assertThat(frozenRevision).isEqualTo(firstRevision);

              var advancedRegistration = advanceInstallationRegistry(world);
              assertThat(advancedRegistration.revision()).isGreaterThan(firstRevision);
              assertThat(
                      world
                          .dsl()
                          .fetchSingle(
                              "SELECT revision FROM world_runtime_installation_current WHERE target_namespace=?",
                              NAMESPACE)
                          .get(0, Long.class))
                  .isEqualTo(advancedRegistration.revision());
              assertThat(
                      world
                          .dsl()
                          .fetchSingle(
                              "SELECT registry_revision FROM world_runtime_required_owner_snapshot WHERE canonical_game_instance_id=?",
                              input.canonicalGameInstanceId())
                          .get(0, Long.class))
                  .isEqualTo(firstRevision);
              var afterAdvance = rows(world.dsl(), worldBefore.keySet().toArray(String[]::new));
              assertThat(afterAdvance.get("world_runtime_installation_registration"))
                  .hasSize(after.get("world_runtime_installation_registration").size() + 1);
              assertThat(afterAdvance.get("world_runtime_installation_current"))
                  .isNotEqualTo(after.get("world_runtime_installation_current"));
              assertThat(afterAdvance.get("world_runtime_required_owner_snapshot"))
                  .isEqualTo(after.get("world_runtime_required_owner_snapshot"));
              after = afterAdvance;
            }
            var refreshedInput = refreshGameSessionObservation(gs.dsl(), input);
            assertThat(refreshedInput.gameSessionReadRequest().readRequestId())
                .isNotEqualTo(input.gameSessionReadRequest().readRequestId());
            assertThat(WorldCanonicalInstancePreparationRepository.inputJson(refreshedInput))
                .isEqualTo(WorldCanonicalInstancePreparationRepository.inputJson(input));
            observedInput.set(refreshedInput);
            ClientCalls.blockingUnaryCall(
                channel,
                method,
                CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                Empty.getDefaultInstance());
            assertThat(result.get()).isEqualTo(prepared);
            assertThat(rows(world.dsl(), worldBefore.keySet().toArray(String[]::new)))
                .isEqualTo(after);
            assertThat(rows(issuer.sourceDsl(), accountBefore.keySet().toArray(String[]::new)))
                .isEqualTo(protectedAccount);
            assertThat(rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new)))
                .isEqualTo(gsBefore);
            assertThat(protectedAccount.get("account_start_session_world_participations"))
                .hasSize(1);
            assertThat(
                    protectedAccount.get("account_start_session_world_original_attempt_evidence"))
                .hasSize(1);
            assertThat(protectedAccount.get("account_start_session_world_participation_sources"))
                .isNotEmpty();
            assertThat(
                    protectedAccount.get("account_start_session_world_participation_settlements"))
                .isEmpty();
            assertThat(
                    repository.readExactExecution(identity.get()).operation().orElseThrow().state())
                .isEqualTo(WorldCanonicalInstancePreparationRepository.ExecutionState.COMMITTED);
            if (advanceInstallationBeforeRetry) {
              return;
            }
            var evidence = input.gameSessionReadEvidence();
            var lifecycleReadRequest =
                new WorldCanonicalInstanceLifecycleEvidence.Request(
                    1,
                    UUID.randomUUID(),
                    NAMESPACE,
                    tenant,
                    evidence.worldSlug(),
                    input.canonicalGameInstanceId(),
                    evidence.playableStateNamespaceId(),
                    "SHARED",
                    true,
                    requestId,
                    input.versionIdentity().canonicalVersionId(),
                    evidence.descriptorRequestDigest(),
                    evidence.descriptorResultDigest(),
                    evidence.releaseAttestationEvidenceDigest());
            var read = lifecycleRepository.read(lifecycleReadRequest).orElseThrow();
            assertThat(read.lifecycleStatus()).isEqualTo("PREPARING");
            assertThat(read.lifecycleEpoch()).isEqualTo(1L);
            assertThat(read.launchBinding()).isEqualTo(input.completeLaunchBinding().evidence());
            assertThat(read.runtimeRoomInstanceId()).isEqualTo(prepared.runtimeRoomInstanceId());
            assertThat(read.preparationInputDigest()).isEqualTo(prepared.inputDigest());
            var resolvedAssociation =
                association.readOwnerAssociation(input.canonicalGameInstanceId()).orElseThrow();
            // Alter one identity field after the real owners have committed the association.
            // This is a refusal probe, not a fabricated alternative owner projection.
            for (String changedField : List.of("namespace", "realm", "catalog")) {
              var changedIdentityEvidence =
                  new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
                      evidence.readRequestId(),
                      evidence.targetNamespace(),
                      evidence.canonicalTenantId(),
                      evidence.worldSlug(),
                      evidence.canonicalGameInstanceId(),
                      evidence.controlPlaneRequestId(),
                      evidence.launchDescriptorId(),
                      evidence.descriptorRequestDigest(),
                      evidence.descriptorResultDigest(),
                      evidence.releaseAttestationEvidenceDigest(),
                      changedField.equals("namespace")
                          ? UUID.randomUUID()
                          : evidence.playableStateNamespaceId(),
                      evidence.playableStateScope(),
                      evidence.publicProduction(),
                      changedField.equals("realm")
                          ? UUID.randomUUID()
                          : evidence.canonicalRealmId(),
                      changedField.equals("catalog")
                          ? Math.addExact(evidence.catalogRevision(), 1L)
                          : evidence.catalogRevision(),
                      evidence.currentGameSessionStatus(),
                      evidence.currentGameSessionRowVersion(),
                      evidence.descriptor(),
                      evidence.releaseAttestation());
              var changedIdentityClaim =
                  new WorldCanonicalInstanceAssociation.Claim(
                      input.gameSessionReadRequest(),
                      changedIdentityEvidence,
                      resolvedAssociation.worldInstanceId(),
                      input.completeLaunchBinding(),
                      input.versionIdentity());
              var beforeIdentityRefusal =
                  rows(world.dsl(), worldBefore.keySet().toArray(String[]::new));
              assertThatThrownBy(
                      () ->
                          world
                              .write()
                              .executeWithoutResult(
                                  status ->
                                      association.retainClaimInOwnerTransaction(
                                          changedIdentityClaim)))
                  .as("retained association rejects independent %s substitution", changedField)
                  .isInstanceOf(
                      WorldCanonicalInstanceAssociationRepository
                          .InvalidAssociationEvidenceException.class)
                  .hasMessageContaining(
                      "World row has no matching insert-born canonical prepare identity");
              assertThat(association.readOwnerAssociation(input.canonicalGameInstanceId()))
                  .contains(resolvedAssociation);
              assertThat(rows(world.dsl(), worldBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(beforeIdentityRefusal);
              assertThat(rows(issuer.sourceDsl(), accountBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(protectedAccount);
              assertThat(rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(gsBefore);
            }
            var prepareFields = resolvedAssociation.worldPrepareFields();
            var operationalRegionPlan =
                world
                    .dsl()
                    .fetch(
                        "EXPLAIN (FORMAT TEXT) "
                            + WorldCanonicalInstanceLifecycleReadRepository
                                .OPERATIONAL_REGION_ASSIGNMENTS_SQL,
                        resolvedAssociation.worldInstanceId(),
                        resolvedAssociation.worldInstanceId(),
                        prepareFields.privateTenantKey(),
                        prepareFields.privateGameInstanceKey())
                    .getValues("QUERY PLAN", String.class);
            var operationalRegionPlanText =
                String.join("\n", operationalRegionPlan).toLowerCase(java.util.Locale.ROOT);
            assertThat(operationalRegionPlan)
                .as("physical EXPLAIN output for the production REGION readback query")
                .isNotEmpty();
            assertThat(operationalRegionPlanText)
                .as("physical EXPLAIN output: %s", operationalRegionPlanText)
                .contains(
                    "full join", "world_instance_id", "family", "tenant_id", "game_instance_id");
            recovering.set(true);
            ownerRefusal.set(null);
            try {
              ClientCalls.blockingUnaryCall(
                  channel,
                  method,
                  CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                  Empty.getDefaultInstance());
            } catch (StatusRuntimeException remoteFailure) {
              throw new IllegalStateException(
                  "Actual historical owner recovery refused exact operation",
                  ownerRefusal.get() == null ? remoteFailure : ownerRefusal.get());
            }
            assertThat(recovered.get().outcome())
                .isEqualTo(
                    net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal.Outcome
                        .COMMITTED);
            assertThat(recovered.get().originalPostAuthorizationTuple())
                .containsExactly(tuple.canonicalBytes());
            assertThat(recovered.get().preparationInputJson())
                .isEqualTo(identity.get().preparationInputJson());
            assertThat(rows(world.dsl(), worldBefore.keySet().toArray(String[]::new)))
                .isEqualTo(after);
            assertThat(rows(issuer.sourceDsl(), accountBefore.keySet().toArray(String[]::new)))
                .isEqualTo(protectedAccount);
            assertThat(rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new)))
                .isEqualTo(gsBefore);
            UUID settlementRequestId = UUID.randomUUID();
            var originalRecoveryIdentity = identity.get();
            var participationSettlement =
                new WorldOriginalOperationSettlementRecovery(
                        serviceFactory.get(), worldParticipationSettlement)
                    .recover(originalRecoveryIdentity, settlementRequestId)
                    .orElseThrow();
            // Recreate the recovery service and every outbound recovery client, preserving only
            // the original lookup/correlation and actual owner storage. This is component restart,
            // not an operating-system crash or a restarted database/server deployment.
            var worldRowsBeforeRecoveryReplay = allRows(world.dsl());
            var accountRowsBeforeRecoveryReplay = allRows(issuer.sourceDsl());
            var gameSessionRowsBeforeRecoveryReplay = allRows(gs.dsl());
            try (var restartedSettlement =
                    new GrpcAccountStartSessionWorldParticipationSettlementClient(
                        accountEndpoints,
                        pki.client("world-management-service"),
                        factory,
                        NAMESPACE);
                var restartedAccountHistory =
                    new AccountStartSessionWorldParticipationHistoricalReadClient(
                        accountEndpoints,
                        pki.client("world-management-service"),
                        factory,
                        NAMESPACE);
                var restartedGameSessionHistory =
                    new HistoricalOriginalStartSessionOwnerEvidenceReadClient(
                        gsEndpoints, pki.client("world-management-service"), factory, NAMESPACE)) {
              restartedSettlement.init();
              restartedAccountHistory.init();
              restartedGameSessionHistory.init();
              var restartedPreparation =
                  new WorldCanonicalInstancePreparationService(
                      repository,
                      verifiedOwner,
                      new WorldCanonicalInstanceOriginalOperationRecoveryVerifier(
                          restartedAccountHistory, restartedGameSessionHistory),
                      world.installationRegistry());
              var restartedRecovery =
                  new WorldOriginalOperationSettlementRecovery(
                      restartedPreparation, restartedSettlement);
              var replay =
                  restartedRecovery
                      .recover(originalRecoveryIdentity, settlementRequestId)
                      .orElseThrow();
              assertThat(replay.settlementRequestId()).isEqualTo(settlementRequestId);
              assertThat(replay.terminal().canonicalBytes())
                  .containsExactly(participationSettlement.terminal().canonicalBytes());
              assertThat(replay.settledAt()).isEqualTo(participationSettlement.settledAt());
            }
            assertThat(allRows(world.dsl())).isEqualTo(worldRowsBeforeRecoveryReplay);
            assertThat(allRows(issuer.sourceDsl())).isEqualTo(accountRowsBeforeRecoveryReplay);
            assertThat(allRows(gs.dsl())).isEqualTo(gameSessionRowsBeforeRecoveryReplay);
            assertThat(participationSettlement.terminal().canonicalBytes())
                .containsExactly(recovered.get().canonicalBytes());
            byte[] participationTerminalBytes =
                issuer
                    .sourceDsl()
                    .fetchSingle(
                        "SELECT terminal_bytes FROM account_start_session_world_participation_settlements WHERE participation_id=?",
                        identity.get().accountWorldParticipationId())
                    .get("terminal_bytes", byte[].class);
            assertThat(participationTerminalBytes)
                .containsExactly(recovered.get().canonicalBytes());
            var settledAccountRows =
                rows(
                    issuer.sourceDsl(),
                    "account_start_session_world_participation_settlements",
                    "account_start_session_activation_protections",
                    "account_start_session_activation_protection_sources",
                    "account_start_session_activation_protection_settlements");
            assertThat(
                    settledAccountRows.get("account_start_session_world_participation_settlements"))
                .hasSize(1);
            assertThat(settledAccountRows.get("account_start_session_activation_protections"))
                .isEmpty();

            var activationRequest =
                new WorldCanonicalInstanceActivation.Request(UUID.randomUUID(), read);
            var activationInput =
                new AccountStartSessionActivationProtectionAcquisitionInput(
                    tuple.canonicalBytes(),
                    claim.get().ownerMutationId(),
                    refusal == Refusal.CHANGED_ACTIVATION_ATTEMPT
                        ? UUID.randomUUID()
                        : claim.get().ownerAttemptId(),
                    claim.get().ownerFence(),
                    activationRequest);
            var activationWorldBefore =
                rows(world.dsl(), worldBefore.keySet().toArray(String[]::new));
            var activationAccountBefore =
                rows(issuer.sourceDsl(), accountBefore.keySet().toArray(String[]::new));
            var activationGsBefore = rows(gs.dsl(), gsBefore.keySet().toArray(String[]::new));
            if (refusal == Refusal.CHANGED_ACTIVATION_ATTEMPT) {
              // The foreign Account precondition is a nonretryable World precondition failure.
              assertThatThrownBy(() -> worldProtectedActivation.activate(activationInput))
                  .isInstanceOfSatisfying(
                      StatusRuntimeException.class,
                      failure ->
                          assertThat(Status.fromThrowable(failure).getCode())
                              .isEqualTo(Status.Code.FAILED_PRECONDITION));
              assertThat(rows(world.dsl(), activationWorldBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(activationWorldBefore);
              assertThat(
                      rows(
                          issuer.sourceDsl(),
                          activationAccountBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(activationAccountBefore);
              assertThat(rows(gs.dsl(), activationGsBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(activationGsBefore);
              return;
            }

            if (refusal == Refusal.EXPIRED_AFTER_ACTIVATION_PROTECTION_ACQUISITION) {
              // Enter World orchestration directly so the controlled expiry wait does not consume
              // the separate five-second World RPC budget; this case does not prove that RPC.
              assertThatThrownBy(() -> protectedActivationService.activate(activationInput))
                  .isInstanceOfSatisfying(
                      WorldCanonicalInstanceActivationService.ActivationDeniedException.class,
                      failure ->
                          assertThat(failure)
                              .hasMessageContaining(
                                  "Original Game Session lease expired before World activation intent retention"));

              var acquired = acquiredActivationProtection.get();
              assertThat(acquired).isNotNull();
              var acquiredRequest = acquired.request();
              assertThat(acquired.acquiredAt()).isBefore(acquiredRequest.originalLeaseExpiresAt());
              assertThat(acquiredRequest.originalPostAuthorizationTupleBytes())
                  .containsExactly(tuple.canonicalBytes());
              assertThat(acquiredRequest.accountRedemptionProjectionBytes())
                  .containsExactly(tuple.canonicalBytes());
              assertThat(acquiredRequest.gameSessionOwnerMutationId())
                  .isEqualTo(claim.get().ownerMutationId());
              assertThat(acquiredRequest.gameSessionOwnerAttemptId())
                  .isEqualTo(claim.get().ownerAttemptId());
              assertThat(acquiredRequest.gameSessionOwnerFence())
                  .isEqualTo(claim.get().ownerFence());
              assertThat(acquiredRequest.accountWorldParticipationId())
                  .isEqualTo(identity.get().accountWorldParticipationId());
              assertThat(acquiredRequest.accountWorldParticipationFence())
                  .isEqualTo(identity.get().accountWorldParticipationFence());
              assertThat(acquiredRequest.worldActivationRequest().activationRequestId())
                  .isEqualTo(activationRequest.activationRequestId());
              assertThat(
                      rows(
                          issuer.sourceDsl(),
                          "account_start_session_activation_protections",
                          "account_start_session_activation_protection_sources",
                          "account_start_session_activation_protection_settlements"))
                  .isEqualTo(accountProtectionRowsAfterAcquisition.get());

              var retainedAccountProtection =
                  issuer
                      .sourceDsl()
                      .fetchSingle(
                          "SELECT original_post_authorization_tuple,account_redemption_projection,"
                              + "game_session_owner_mutation_id,game_session_owner_attempt_id,"
                              + "game_session_owner_fence,original_lease_expires_at,"
                              + "account_world_participation_id,account_world_participation_fence,"
                              + "world_activation_request_id,world_preparing_evidence "
                              + "FROM account_start_session_activation_protections "
                              + "WHERE activation_protection_id=?",
                          acquired.accountProtectionId());
              assertThat(
                      retainedAccountProtection.get(
                          "original_post_authorization_tuple", byte[].class))
                  .containsExactly(tuple.canonicalBytes());
              assertThat(
                      retainedAccountProtection.get("account_redemption_projection", byte[].class))
                  .containsExactly(tuple.canonicalBytes());
              assertThat(
                      retainedAccountProtection.get("game_session_owner_mutation_id", UUID.class))
                  .isEqualTo(claim.get().ownerMutationId());
              assertThat(retainedAccountProtection.get("game_session_owner_attempt_id", UUID.class))
                  .isEqualTo(claim.get().ownerAttemptId());
              assertThat(retainedAccountProtection.get("game_session_owner_fence", Long.class))
                  .isEqualTo(claim.get().ownerFence());
              assertThat(
                      retainedAccountProtection
                          .get("original_lease_expires_at", OffsetDateTime.class)
                          .toInstant())
                  .isEqualTo(acquiredRequest.originalLeaseExpiresAt());
              assertThat(
                      retainedAccountProtection.get("account_world_participation_id", UUID.class))
                  .isEqualTo(identity.get().accountWorldParticipationId());
              assertThat(
                      retainedAccountProtection.get(
                          "account_world_participation_fence", Long.class))
                  .isEqualTo(identity.get().accountWorldParticipationFence());
              assertThat(retainedAccountProtection.get("world_activation_request_id", UUID.class))
                  .isEqualTo(activationRequest.activationRequestId());
              assertThat(retainedAccountProtection.get("world_preparing_evidence", byte[].class))
                  .containsExactly(read.canonicalBytes());

              assertThat(
                      gs.dsl()
                          .fetchSingle(
                              "SELECT lease_expires_at <= clock_timestamp() "
                                  + "FROM game_session_start_session_operator_attempt "
                                  + "WHERE owner_attempt_id=?",
                              claim.get().ownerAttemptId())
                          .get(0, Boolean.class))
                  .isTrue();
              assertThat(rows(world.dsl(), activationWorldBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(activationWorldBefore);
              assertThat(
                      world
                          .dsl()
                          .fetchCount(DSL.table("world_canonical_instance_activation_fence")))
                  .isZero();
              assertThat(
                      world
                          .dsl()
                          .fetchCount(DSL.table("world_canonical_instance_activation_operation")))
                  .isZero();
              assertThat(
                      world
                          .dsl()
                          .fetch("SELECT status FROM world_instance")
                          .getValues("status", String.class))
                  .containsExactly("PREPARING");
              assertThat(rows(gs.dsl(), activationGsBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(activationGsBefore);

              var accountAfterDenial =
                  rows(issuer.sourceDsl(), activationAccountBefore.keySet().toArray(String[]::new));
              for (var entry : activationAccountBefore.entrySet()) {
                String table = entry.getKey();
                if (!table.startsWith("account_start_session_activation_protection")) {
                  assertThat(accountAfterDenial.get(table))
                      .as("Account original owner table %s remains unchanged", table)
                      .isEqualTo(entry.getValue());
                }
              }
              assertThat(accountAfterDenial.get("account_start_session_activation_protections"))
                  .hasSize(1);
              assertThat(
                      accountAfterDenial.get("account_start_session_activation_protection_sources"))
                  .isNotEmpty();
              assertThat(
                      accountAfterDenial.get(
                          "account_start_session_activation_protection_settlements"))
                  .isEmpty();
              assertThat(activationProtectionAcquisitionCalls.get()).isEqualTo(1);

              String protectedSourceKey =
                  issuer
                      .sourceDsl()
                      .fetchSingle(
                          "SELECT source_key "
                              + "FROM account_start_session_activation_protection_sources "
                              + "WHERE activation_protection_id=? ORDER BY source_key LIMIT 1",
                          acquired.accountProtectionId())
                      .get("source_key", String.class);
              assertThatThrownBy(
                      () ->
                          new TransactionTemplate(accountManager(issuer.sourceDsl()))
                              .executeWithoutResult(
                                  status ->
                                      issuer
                                          .sourceDsl()
                                          .execute(
                                              "SELECT account_control_ui_hold_required_sources("
                                                  + "ARRAY[?]::text[])",
                                              protectedSourceKey)))
                  .hasMessageContaining(
                      "Original StartSession activation protection remains pending");
              assertThat(
                      rows(
                          issuer.sourceDsl(),
                          activationAccountBefore.keySet().toArray(String[]::new)))
                  .isEqualTo(accountAfterDenial);
              return;
            }

            if (activationFailure
                    == ActivationFailure.TRANSIENT_ROLLBACK_ALWAYS_FINAL_READBACK_FAILURE
                || activationFailure
                    == ActivationFailure.NONTRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE) {
              var protection = worldProtectionAcquisition.acquire(activationInput);
              var held =
                  new WorldCanonicalInstanceActivationService.HeldActivationAuthority() {
                    @Override
                    public void requireHeld() {}

                    @Override
                    public void close() {}
                  };
              if (activationFailure
                  == ActivationFailure.TRANSIENT_ROLLBACK_ALWAYS_FINAL_READBACK_FAILURE) {
                assertThatThrownBy(
                        () ->
                            world
                                .installationRegistry()
                                .withLaunchOwners(
                                    activationRequest.canonicalGameInstanceId(),
                                    () -> activationRepository.activateProtected(protection, held)))
                    .isInstanceOfSatisfying(
                        TransientDataAccessResourceException.class,
                        failure -> assertSuppressedTerminalReadbackFailure(failure));
              } else {
                assertThatThrownBy(
                        () ->
                            world
                                .installationRegistry()
                                .withLaunchOwners(
                                    activationRequest.canonicalGameInstanceId(),
                                    () -> activationRepository.activateProtected(protection, held)))
                    .isInstanceOfSatisfying(
                        IllegalStateException.class,
                        failure -> {
                          assertThat(failure)
                              .hasMessage("TEST_ONLY nontransient World activation failure");
                          assertSuppressedTerminalReadbackFailure(failure);
                        });
              }
              assertThat(terminalReadbackFault.failedReadbacks()).isEqualTo(1);
              assertPendingActivationState(world.dsl());
              int expectedAttempts =
                  activationFailure
                          == ActivationFailure.TRANSIENT_ROLLBACK_ALWAYS_FINAL_READBACK_FAILURE
                      ? 3
                      : 1;
              assertThat(activationTransactionFault.lifecycleCasCommitAttempts())
                  .isEqualTo(expectedAttempts);
              assertThat(activationTransactionFault.writeTransactionsStarted())
                  .isEqualTo(expectedAttempts + 1);
              assertThat(activationTransactionFault.verifiedPendingRollbacks())
                  .isEqualTo(expectedAttempts);
              assertThat(activationProtectionAcquisitionCalls.get()).isEqualTo(1);
              var pendingAccountRows =
                  rows(
                      issuer.sourceDsl(),
                      "account_start_session_activation_protections",
                      "account_start_session_activation_protection_sources",
                      "account_start_session_activation_protection_settlements");
              assertThat(pendingAccountRows.get("account_start_session_activation_protections"))
                  .hasSize(1);
              assertThat(
                      pendingAccountRows.get(
                          "account_start_session_activation_protection_settlements"))
                  .isEmpty();
              return;
            }

            if (activationFailure == ActivationFailure.TRANSIENT_ROLLBACK_ALWAYS
                || activationFailure == ActivationFailure.NONTRANSIENT_ROLLBACK_ONCE) {
              assertThatThrownBy(() -> worldProtectedActivation.activate(activationInput))
                  .isInstanceOf(StatusRuntimeException.class);
              assertPendingActivationState(world.dsl());
              assertThat(activationTransactionFault.lifecycleCasCommitAttempts())
                  .isEqualTo(
                      activationFailure == ActivationFailure.TRANSIENT_ROLLBACK_ALWAYS ? 3 : 1);
              assertThat(activationTransactionFault.writeTransactionsStarted())
                  .isEqualTo(
                      activationFailure == ActivationFailure.TRANSIENT_ROLLBACK_ALWAYS ? 4 : 2);
              assertThat(activationTransactionFault.verifiedPendingRollbacks())
                  .isEqualTo(
                      activationFailure == ActivationFailure.TRANSIENT_ROLLBACK_ALWAYS ? 3 : 1);
              assertThat(activationProtectionAcquisitionCalls.get()).isEqualTo(1);
              var pendingAccountRows =
                  rows(
                      issuer.sourceDsl(),
                      "account_start_session_activation_protections",
                      "account_start_session_activation_protection_sources",
                      "account_start_session_activation_protection_settlements");
              assertThat(pendingAccountRows.get("account_start_session_activation_protections"))
                  .hasSize(1);
              assertThat(
                      issuer
                          .sourceDsl()
                          .fetch(
                              "SELECT original_lease_expires_at "
                                  + "FROM account_start_session_activation_protections")
                          .getValues("original_lease_expires_at", OffsetDateTime.class))
                  .hasSize(1);

              assertThatThrownBy(() -> worldProtectedActivation.activate(activationInput))
                  .isInstanceOfSatisfying(
                      StatusRuntimeException.class,
                      failure ->
                          assertThat(Status.fromThrowable(failure).getCode())
                              .isEqualTo(Status.Code.FAILED_PRECONDITION));
              assertPendingActivationState(world.dsl());
              assertThat(
                      rows(
                          issuer.sourceDsl(),
                          "account_start_session_activation_protections",
                          "account_start_session_activation_protection_sources",
                          "account_start_session_activation_protection_settlements"))
                  .isEqualTo(pendingAccountRows);
              assertThat(activationProtectionAcquisitionCalls.get()).isEqualTo(1);
              return;
            }

            WorldOriginalStartSessionActivationTerminalEvidence activationTerminal =
                worldProtectedActivation.activate(activationInput);
            if (activationFailure != ActivationFailure.NONE) {
              assertThat(activationProtectionAcquisitionCalls.get()).isEqualTo(1);
              boolean lifecycleRetryOccurred =
                  activationFailure == ActivationFailure.TRANSIENT_ROLLBACK_ONCE
                      || activationFailure
                          == ActivationFailure.TRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE
                      || activationFailure
                          == ActivationFailure.TRANSIENT_AFTER_COMMIT_ONCE_READBACK_FAILURE;
              assertThat(activationTransactionFault.lifecycleCasCommitAttempts())
                  .isEqualTo(lifecycleRetryOccurred ? 2 : 1);
              assertThat(activationTransactionFault.writeTransactionsStarted())
                  .isEqualTo(lifecycleRetryOccurred ? 3 : 2);
              boolean lifecycleRollbackOccurred =
                  activationFailure == ActivationFailure.TRANSIENT_ROLLBACK_ONCE
                      || activationFailure
                          == ActivationFailure.TRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE;
              if (lifecycleRollbackOccurred) {
                assertThat(activationTransactionFault.verifiedPendingRollbacks()).isEqualTo(1);
              }
              int expectedReadbackFailures =
                  activationFailure == ActivationFailure.TRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE
                          || activationFailure
                              == ActivationFailure.TRANSIENT_AFTER_COMMIT_ONCE_READBACK_FAILURE
                      ? 1
                      : 0;
              assertThat(terminalReadbackFault.failedReadbacks())
                  .isEqualTo(expectedReadbackFailures);
            }
            assertThat(activationTerminal.outcome())
                .isEqualTo(WorldCanonicalInstanceActivation.Outcome.COMMITTED);
            assertThat(activationTerminal.activationResult().lifecycleEvidence().lifecycleStatus())
                .isEqualTo("ACTIVE");
            assertThat(activationTerminal.activationResult().lifecycleEvidence().lifecycleEpoch())
                .isEqualTo(2L);
            assertThat(
                    activationTerminal
                        .accountProtectionEvidence()
                        .request()
                        .originalPostAuthorizationTupleBytes())
                .containsExactly(tuple.canonicalBytes());
            var retainedProtectionRequest =
                activationTerminal.accountProtectionEvidence().request();
            assertThat(retainedProtectionRequest.accountWorldParticipationId())
                .isEqualTo(identity.get().accountWorldParticipationId());
            assertThat(retainedProtectionRequest.accountWorldParticipationFence())
                .isEqualTo(identity.get().accountWorldParticipationFence());
            assertThat(retainedProtectionRequest.gameSessionOwnerMutationId())
                .isEqualTo(claim.get().ownerMutationId());
            assertThat(retainedProtectionRequest.gameSessionOwnerAttemptId())
                .isEqualTo(claim.get().ownerAttemptId());
            assertThat(retainedProtectionRequest.gameSessionOwnerFence())
                .isEqualTo(claim.get().ownerFence());
            assertThat(retainedProtectionRequest.launchAssociation().currentGameInstanceStatus())
                .isEqualTo(
                    net.firedevops.firemud.common.gamesession
                        .CanonicalGameInstanceLaunchAssociationReadEvidence
                        .CurrentGameInstanceStatus.STARTING);
            assertThat(retainedProtectionRequest.worldActivationRequest().preparingEvidenceBytes())
                .containsExactly(read.canonicalBytes());
            var retainedProtectionRow =
                issuer
                    .sourceDsl()
                    .fetchSingle(
                        "SELECT original_post_authorization_tuple,original_lease_expires_at FROM account_start_session_activation_protections WHERE activation_protection_id=?",
                        activationTerminal.accountProtectionEvidence().accountProtectionId());
            assertThat(retainedProtectionRow.get("original_post_authorization_tuple", byte[].class))
                .containsExactly(tuple.canonicalBytes());
            assertThat(retainedProtectionRequest.originalLeaseExpiresAt())
                .isEqualTo(
                    retainedProtectionRow
                        .get("original_lease_expires_at", OffsetDateTime.class)
                        .toInstant());
            byte[] worldTerminalBytes =
                world
                    .dsl()
                    .fetchSingle(
                        "SELECT terminal_evidence_bytes FROM world_canonical_instance_activation_fence WHERE activation_request_id=?",
                        activationTerminal.activationRequestId())
                    .get("terminal_evidence_bytes", byte[].class);
            assertThat(worldTerminalBytes).containsExactly(activationTerminal.canonicalBytes());
            assertThat(activationProtectionAcquisitionCalls.get()).isEqualTo(1);
            assertThat(
                    world
                        .dsl()
                        .fetch(
                            "SELECT activation_request_id "
                                + "FROM world_canonical_instance_activation_fence WHERE canonical_game_instance_id=?",
                            input.canonicalGameInstanceId()))
                .hasSize(1);
            assertThat(
                    world
                        .dsl()
                        .fetch(
                            "SELECT activation_request_id "
                                + "FROM world_canonical_instance_activation_operation WHERE canonical_game_instance_id=?",
                            input.canonicalGameInstanceId()))
                .hasSize(1);

            AccountStartSessionActivationProtectionSettlementEvidence activationSettlement;
            try {
              activationSettlement =
                  worldProtectionSettlement.settle(
                      new AccountStartSessionActivationProtectionSettlementInput(
                          NAMESPACE,
                          UUID.randomUUID(),
                          activationTerminal.accountProtectionEvidence().accountProtectionId(),
                          activationTerminal.accountProtectionEvidence().accountProtectionFence()));
            } catch (StatusRuntimeException remoteFailure) {
              Throwable serverCause = accountActivationSettlementFailure.get();
              if (serverCause == null) throw remoteFailure;
              throw new AssertionError(
                  "Actual Account activation settlement handler failed: " + serverCause,
                  serverCause);
            }
            assertThat(activationSettlement.terminal().canonicalBytes())
                .containsExactly(activationTerminal.canonicalBytes());
            byte[] accountTerminalBytes =
                issuer
                    .sourceDsl()
                    .fetchSingle(
                        "SELECT terminal_bytes FROM account_start_session_activation_protection_settlements WHERE activation_protection_id=?",
                        activationTerminal.accountProtectionEvidence().accountProtectionId())
                    .get("terminal_bytes", byte[].class);
            assertThat(accountTerminalBytes).containsExactly(activationTerminal.canonicalBytes());

            var activeReadRequest =
                new WorldCanonicalInstanceLifecycleEvidence.Request(
                    lifecycleReadRequest.schemaVersion(),
                    UUID.randomUUID(),
                    lifecycleReadRequest.targetNamespace(),
                    lifecycleReadRequest.canonicalTenantId(),
                    lifecycleReadRequest.worldSlug(),
                    lifecycleReadRequest.canonicalGameInstanceId(),
                    lifecycleReadRequest.playableStateNamespaceId(),
                    lifecycleReadRequest.playableStateScope(),
                    lifecycleReadRequest.publicProduction(),
                    lifecycleReadRequest.controlPlaneRequestId(),
                    lifecycleReadRequest.canonicalVersionId(),
                    lifecycleReadRequest.expectedDescriptorRequestDigest(),
                    lifecycleReadRequest.expectedDescriptorResultDigest(),
                    lifecycleReadRequest.expectedReleaseAttestationDigest());
            var active = lifecycleRepository.read(activeReadRequest).orElseThrow();
            assertThat(active.lifecycleStatus()).isEqualTo("ACTIVE");
            assertThat(active.lifecycleEpoch()).isEqualTo(2L);
            assertThat(active.rowVersion()).isEqualTo(read.rowVersion() + 1);
            assertThat(rows(gs.dsl(), activationGsBefore.keySet().toArray(String[]::new)))
                .isEqualTo(activationGsBefore);
            var finalAccountRows =
                rows(
                    issuer.sourceDsl(),
                    "account_start_session_world_participation_settlements",
                    "account_start_session_activation_protections",
                    "account_start_session_activation_protection_sources",
                    "account_start_session_activation_protection_settlements");
            assertThat(finalAccountRows.get("account_start_session_activation_protections"))
                .hasSize(1);
            assertThat(finalAccountRows.get("account_start_session_activation_protection_sources"))
                .isNotEmpty();
            assertThat(
                    finalAccountRows.get("account_start_session_activation_protection_settlements"))
                .hasSize(1);
            if (exerciseCanonicalInitialAdmissionHold) {
              exerciseCanonicalInitialAdmissionHold(
                  world, association, lifecycleRepository, activeReadRequest);
            }
            if (exerciseCoupledCanonicalInitialAdmission) {
              if (admissionOwner == null || canonicalHoldOwner == null) {
                throw new AssertionError("Coupled admission owner composition was not prepared");
              }
              exerciseCoupledCanonicalInitialAdmission(
                  gs,
                  world,
                  account,
                  issuer,
                  currentAttempt,
                  proofDirectory,
                  pki,
                  factory,
                  gsEndpoints,
                  worldEndpoints,
                  association,
                  lifecycleRepository,
                  admissionOwner,
                  canonicalHoldOwner,
                  activeReadRequest,
                  tuple,
                  claim.get(),
                  retainedProtectionRequest,
                  recovered.get(),
                  abortCanonicalInitialAdmission,
                  createdPublication.get());
            }
            if (operationProbe != null) {
              operationProbe.accept(
                  new OperationProof(
                      world,
                      gs,
                      issuer.sourceDsl(),
                      input,
                      operation,
                      identity.get(),
                      prepared,
                      association,
                      repository,
                      verificationCalls,
                      candidateReads,
                      (requestedInput, requestedOperation) -> {
                        boolean priorRecovering = recovering.getAndSet(false);
                        observedInput.set(requestedInput);
                        observedOperation.set(requestedOperation);
                        result.set(null);
                        ownerRefusal.set(null);
                        try {
                          ClientCalls.blockingUnaryCall(
                              channel,
                              method,
                              CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                              Empty.getDefaultInstance());
                        } catch (StatusRuntimeException refused) {
                          throw ownerRefusal.get() == null ? refused : ownerRefusal.get();
                        } finally {
                          observedInput.set(input);
                          observedOperation.set(operation);
                          recovering.set(priorRecovering);
                        }
                        return result.get();
                      },
                      () ->
                          readAuthenticatedHistoricalTerminal(
                              accountWorldTerminalRead, identity.get())));
            }
            // Only the explicitly coupled path completes GS; ordinary preparation remains STARTING.
          } finally {
            logCompositionTiming(compositionStartedNanos, "cleanup-start");
            channel.shutdownNow();
            assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
          }
        } finally {
          shutdown(accountServer);
        }
      } finally {
        shutdown(gsServer);
      }
    } finally {
      logCompositionTiming(compositionStartedNanos, "composition-exit");
    }
  }

  /** Observes actual owner phases; never supplies an identity, SQL result, or permission. */
  private static final class IntegrityControl {
    private final IntegrityMode mode;
    private final Database world;
    private final AtomicReference<WorldCanonicalInstanceExecutionIdentity> identity =
        new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> beforeWriter = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> materializedWitness =
        new AtomicReference<>();
    private final AtomicBoolean wrongFenceProved = new AtomicBoolean();
    private final AtomicBoolean partialCommitProved = new AtomicBoolean();
    private final AtomicBoolean terminalPhaseReached = new AtomicBoolean();

    private IntegrityControl(IntegrityMode mode, Database world) {
      this.mode = mode;
      this.world = world;
    }

    private WorldCanonicalInstancePreparationService.HeldCommitAuthority wrapRealAuthority(
        WorldCanonicalInstancePreparationService.HeldCommitAuthority delegate) {
      identity.set(delegate.executionIdentity());
      if (mode != IntegrityMode.LATE_HANDLE_CLOSE && mode != IntegrityMode.EXPIRY) return delegate;
      if (mode == IntegrityMode.EXPIRY) {
        assertThat(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive())
            .isFalse();
        var expiry = delegate.executionIdentity().originalAuthorizationExpiry();
        assertThat(delegate.executionIdentity().originalGameSessionLeaseExpiresAt())
            .isAfter(expiry.plusSeconds(2));
        assertThat(expiry).isAfter(databaseNow(world.dsl()).plusSeconds(10));
        LOGGER.info(
            "Real authenticated Account handle held outside SQL until admission window: {}",
            expiry);
        try {
          await()
              .atMost(Duration.between(Instant.now(), expiry).plusSeconds(2))
              .pollInterval(Duration.ofMillis(100))
              .until(() -> !databaseNow(world.dsl()).isBefore(expiry.minusSeconds(6)));
          delegate.requireHeld();
        } catch (RuntimeException | Error failure) {
          delegate.close();
          throw failure;
        }
      }
      return new WorldCanonicalInstancePreparationService.HeldCommitAuthority() {
        @Override
        public WorldCanonicalInstanceExecutionIdentity executionIdentity() {
          return delegate.executionIdentity();
        }

        @Override
        public void close() {
          delegate.close();
        }

        @Override
        public void requireHeld() {
          delegate.requireHeld();
          if (!org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()) return;
          var i = delegate.executionIdentity();
          var row =
              world
                  .dsl()
                  .fetchOne(
                      "SELECT operation_state,execution_claimed,world_execution_fence FROM world_canonical_instance_execution "
                          + "WHERE target_namespace=? AND canonical_tenant_id=? AND control_plane_request_id=?",
                      i.targetNamespace(),
                      i.canonicalTenantId(),
                      i.controlPlaneRequestId());
          if (row == null) return;
          if (mode == IntegrityMode.LATE_HANDLE_CLOSE
              && "PENDING".equals(row.get("operation_state", String.class))
              && Boolean.TRUE.equals(row.get("execution_claimed", Boolean.class))
              && wrongFenceProved.compareAndSet(false, true)) {
            var before = allRows(world.dsl());
            assertNativeRefusal(
                world.dsl(),
                () ->
                    nativeBegin(
                        world.dsl(),
                        i,
                        Math.addExact(row.get("world_execution_fence", Long.class), 1L)),
                "World execution intent does not match the exact original full identity");
            assertThat(allRows(world.dsl())).isEqualTo(before);
            beforeWriter.set(before);
          }
          boolean associated =
              Boolean.TRUE.equals(
                  world
                      .dsl()
                      .fetchSingle(
                          "SELECT EXISTS (SELECT 1 FROM world_canonical_instance_preparation p "
                              + "JOIN world_canonical_instance_association a ON a.world_instance_id=p.world_instance_id "
                              + "JOIN world_canonical_instance_execution e ON e.canonical_game_instance_id=p.canonical_game_instance_id "
                              + "WHERE e.canonical_game_instance_id=? AND e.operation_state='EXECUTING' "
                              + "AND e.active_transaction_id=txid_current() AND p.input_json=? AND p.input_digest=?)",
                          i.canonicalGameInstanceId(),
                          i.preparationInputJson(),
                          i.preparationInputDigest())
                      .get(0, Boolean.class));
          if (!associated || !terminalPhaseReached.compareAndSet(false, true)) return;
          materializedWitness.set(allRows(world.dsl()));
          if (mode == IntegrityMode.LATE_HANDLE_CLOSE) {
            assertThat(partialCommitProved).isTrue();
            delegate.close();
            delegate.requireHeld();
            throw new AssertionError("Closed real Account handle unexpectedly remained usable");
          }
          assertThat(databaseNow(world.dsl())).isBefore(i.originalAuthorizationExpiry());
          await()
              .atMost(Duration.ofSeconds(8))
              .pollInterval(Duration.ofMillis(50))
              .until(
                  () ->
                      databaseNow(world.dsl())
                          .isAfter(i.originalAuthorizationExpiry().plusMillis(50)));
          // Expiry is ingress-only: the real uninterrupted handle is still held after admission.
          delegate.requireHeld();
        }
      };
    }

    private void probeBeforeRealAssociation(WorldCanonicalInstanceAssociation.Claim claim) {
      var i = Objects.requireNonNull(identity.get());
      assertThat(
              world
                  .dsl()
                  .fetchSingle(
                      "SELECT e.operation_state='EXECUTING' AND e.active_transaction_id=txid_current() "
                          + "AND p.world_instance_id=? AND p.input_json=? AND p.input_digest=? "
                          + "AND NOT EXISTS (SELECT 1 FROM world_canonical_instance_association a "
                          + "WHERE a.canonical_game_instance_id=e.canonical_game_instance_id) "
                          + "FROM world_canonical_instance_execution e JOIN world_canonical_instance_preparation p "
                          + "ON p.canonical_game_instance_id=e.canonical_game_instance_id WHERE e.canonical_game_instance_id=?",
                      claim.worldInstanceId(),
                      i.preparationInputJson(),
                      i.preparationInputDigest(),
                      i.canonicalGameInstanceId())
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(
              world
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM world_canonical_instance_topology_identity WHERE world_instance_id=?",
                      claim.worldInstanceId())
                  .get(0, Long.class))
          .isEqualTo(5);
      var before = allRows(world.dsl());
      assertNativeRefusal(
          world.dsl(),
          () ->
              world
                  .dsl()
                  .fetch(
                      "SELECT world_commit_canonical_instance_execution(?)",
                      claim.worldInstanceId()),
          "World execution cannot commit without its exact V35 preparation and V34 association");
      assertThat(allRows(world.dsl())).isEqualTo(before);
      assertNativeRefusal(
          world.dsl(),
          () ->
              world
                  .dsl()
                  .execute(
                      "UPDATE world_canonical_instance_execution SET operation_state='COMMITTED',active_transaction_id=NULL,"
                          + "world_instance_id=?,terminal_at=clock_timestamp() WHERE target_namespace=? AND canonical_tenant_id=? "
                          + "AND control_plane_request_id=?",
                      claim.worldInstanceId(),
                      i.targetNamespace(),
                      i.canonicalTenantId(),
                      i.controlPlaneRequestId()),
          "World COMMITTED state requires the current exact V35 preparation and V34 association");
      assertThat(allRows(world.dsl())).isEqualTo(before);
      assertNativeRefusal(
          world.dsl(),
          () ->
              world
                  .dsl()
                  .execute(
                      "SET CONSTRAINTS trg_world_canonical_instance_execution_no_inflight_commit IMMEDIATE"),
          "World EXECUTING state cannot commit without exact terminal settlement");
      assertThat(allRows(world.dsl())).isEqualTo(before);
      partialCommitProved.set(true);
    }
  }

  private static void assertNativeRefusal(DSLContext sql, Runnable attempt, String message) {
    sql.connection(
        connection -> {
          assertThat(connection.getAutoCommit()).isFalse();
          assertThat(connection.getTransactionIsolation())
              .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
          var savepoint = connection.setSavepoint();
          try {
            assertThatThrownBy(attempt::run)
                .isInstanceOf(org.jooq.exception.DataAccessException.class)
                .hasMessageContaining(message)
                .satisfies(
                    failure -> {
                      SQLException cause = null;
                      for (Throwable current = failure;
                          current != null;
                          current = current.getCause()) {
                        if (current instanceof SQLException found) cause = found;
                      }
                      assertThat((Throwable) cause).isNotNull();
                      assertThat(Objects.requireNonNull(cause).getSQLState()).isEqualTo("23514");
                    });
          } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
          }
        });
  }

  private static void nativeBegin(
      DSLContext sql, WorldCanonicalInstanceExecutionIdentity i, long fence) {
    sql.fetch(
        "SELECT world_begin_canonical_instance_execution_with_original_attempt(?,?,?,?,?,?,?,?,?,?,?,"
            + "CAST(? AS TIMESTAMPTZ),?,?)",
        i.preparationInputJson(),
        i.preparationInputDigest(),
        i.originalPostAuthorizationTuple(),
        i.accountWorldParticipationId(),
        i.accountWorldParticipationFence(),
        i.gameSessionOwnerAttemptId(),
        i.gameSessionOwnerFence(),
        i.canonicalGameInstanceId(),
        fence,
        i.gameSessionOwnerMutationId(),
        i.originalAttemptReadRequestId(),
        OffsetDateTime.ofInstant(i.originalGameSessionLeaseExpiresAt(), java.time.ZoneOffset.UTC),
        i.originalAttemptEvidenceBytes(),
        i.originalAttemptEvidenceDigest());
  }

  private static void assertLateHandleRollbackAndAbort(
      Database world,
      Database gs,
      DSLContext account,
      WorldCanonicalInstancePreparation.Input input,
      WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput operation,
      AtomicReference<WorldCanonicalInstanceExecutionIdentity> identity,
      WorldCanonicalInstancePreparationRepository repository,
      IntegrityControl integrity,
      AtomicReference<WorldCanonicalInstancePreparationService> service,
      java.util.function.Supplier<WorldCanonicalInstancePreparationService> serviceFactory,
      AtomicReference<WorldCanonicalInstancePreparation.Input> observedInput,
      AtomicReference<WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput>
          observedOperation,
      AtomicReference<WorldCanonicalInstancePreparation.Result> result,
      AtomicReference<RuntimeException> ownerRefusal,
      ManagedChannel channel,
      MethodDescriptor<Empty, Empty> method) {
    assertThatThrownBy(
            () ->
                ClientCalls.blockingUnaryCall(
                    channel,
                    method,
                    CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                    Empty.getDefaultInstance()))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(ownerRefusal.get())
        .isInstanceOf(WorldCanonicalInstancePreparationService.PreparationDeniedException.class)
        .hasMessage("Exact Account participation handle is no longer available locally");
    assertThat(integrity.wrongFenceProved).isTrue();
    assertThat(integrity.partialCommitProved).isTrue();
    assertThat(integrity.terminalPhaseReached).isTrue();
    assertThat(integrity.materializedWitness.get().get("world_canonical_instance_association"))
        .hasSize(1);
    assertThat(integrity.materializedWitness.get().get("world_canonical_instance_preparation"))
        .hasSize(1);
    assertThat(allRows(world.dsl())).isEqualTo(integrity.beforeWriter.get());
    assertThat(result.get()).isNull();
    var i = Objects.requireNonNull(identity.get());
    var pending = repository.readExactExecution(i).operation().orElseThrow();
    assertThat(pending.state())
        .isEqualTo(WorldCanonicalInstancePreparationRepository.ExecutionState.PENDING);
    assertThat(
            world
                .dsl()
                .fetchSingle(
                    "SELECT execution_claimed FROM world_canonical_instance_execution "
                        + "WHERE canonical_game_instance_id=?",
                    input.canonicalGameInstanceId())
                .get(0, Boolean.class))
        .isTrue();
    var accountBefore = allRows(account);
    var gsBefore = allRows(gs.dsl());
    var aborted = repository.recordDurableAbort(i);
    assertThat(aborted.state())
        .isEqualTo(WorldCanonicalInstancePreparationRepository.ExecutionState.ABORTED);
    assertThat(aborted.worldExecutionFence()).isEqualTo(pending.worldExecutionFence());
    var afterAbort = allRows(world.dsl());
    assertThat(repository.recordDurableAbort(i)).isEqualTo(aborted);
    assertThat(allRows(world.dsl())).isEqualTo(afterAbort);
    world
        .write()
        .executeWithoutResult(
            status -> {
              assertNativeRefusal(
                  world.dsl(),
                  () -> nativeBegin(world.dsl(), i, aborted.worldExecutionFence()),
                  "World execution intent is terminal or already in flight");
              assertNativeRefusal(
                  world.dsl(),
                  () ->
                      world
                          .dsl()
                          .fetch(
                              "SELECT * FROM world_prepare_canonical_instance(?,?)",
                              i.preparationInputJson(),
                              i.preparationInputDigest()),
                  "V35 requires the exact current World and Account owner-observation identity");
              assertNativeRefusal(
                  world.dsl(),
                  () ->
                      world
                          .dsl()
                          .execute(
                              "INSERT INTO world_canonical_instance_association DEFAULT VALUES"),
                  "V34 association requires the exact active World execution identity and fence");
            });
    assertThat(allRows(world.dsl())).isEqualTo(afterAbort);
    service.set(
        serviceFactory.get()); // New service, same actual authenticated owners and durable rows.
    observedInput.set(input);
    observedOperation.set(operation);
    ownerRefusal.set(null);
    assertThatThrownBy(
            () ->
                ClientCalls.blockingUnaryCall(
                    channel,
                    method,
                    CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                    Empty.getDefaultInstance()))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(ownerRefusal.get())
        .isInstanceOf(
            WorldCanonicalInstancePreparationRepository.ConflictingPreparationException.class)
        .hasMessage("Exact original World execution is durably ABORTED and cannot be restarted");
    assertThat(allRows(world.dsl())).isEqualTo(afterAbort);
    assertThat(allRows(account)).isEqualTo(accountBefore);
    assertThat(allRows(gs.dsl())).isEqualTo(gsBefore);
  }

  private static void assertAdmittedExpiryRecovery(
      Database world,
      Database gs,
      DSLContext account,
      WorldCanonicalInstanceExecutionIdentity identity,
      WorldCanonicalInstancePreparationRepository repository,
      WorldCanonicalInstancePreparation.Result prepared,
      IntegrityControl integrity,
      AtomicInteger verificationCalls,
      WorldStartSessionExecutionTerminalReadClient terminalRead) {
    assertThat(integrity.terminalPhaseReached).isTrue();
    assertThat(databaseNow(world.dsl())).isAfter(identity.originalAuthorizationExpiry());
    assertThat(identity.originalGameSessionLeaseExpiresAt()).isAfter(databaseNow(world.dsl()));
    var terminal = repository.readExactExecution(identity);
    assertThat(terminal.originalAuthorizationValidAtRead()).isFalse();
    assertThat(terminal.operation().orElseThrow().state())
        .isEqualTo(WorldCanonicalInstancePreparationRepository.ExecutionState.COMMITTED);
    assertThat(terminal.operation().orElseThrow().worldInstanceId())
        .isEqualTo(prepared.association().worldInstanceId());
    var worldBefore = allRows(world.dsl());
    var gsBefore = allRows(gs.dsl());
    var accountBefore = allRows(account);
    int before = verificationCalls.get();
    var historical = readAuthenticatedHistoricalTerminal(terminalRead, identity);
    assertThat(historical.outcome())
        .isEqualTo(
            net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal.Outcome
                .COMMITTED);
    assertThat(historical.originalPostAuthorizationTuple())
        .containsExactly(identity.originalPostAuthorizationTuple());
    assertThat(historical.preparationInputJson()).isEqualTo(identity.preparationInputJson());
    assertThat(verificationCalls).hasValue(before);
    assertThat(repository.readExactExecution(identity).operation()).isEqualTo(terminal.operation());
    assertThat(allRows(world.dsl())).isEqualTo(worldBefore);
    assertThat(allRows(gs.dsl())).isEqualTo(gsBefore);
    assertThat(allRows(account)).isEqualTo(accountBefore);
  }

  private static net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal
      readAuthenticatedHistoricalTerminal(
          WorldStartSessionExecutionTerminalReadClient client,
          WorldCanonicalInstanceExecutionIdentity identity) {
    return client.read(
        new net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadRequest(
            UUID.randomUUID(),
            NAMESPACE,
            identity.originalPostAuthorizationTuple(),
            identity.accountWorldParticipationId(),
            identity.accountWorldParticipationFence(),
            identity.gameSessionOwnerAttemptId(),
            identity.gameSessionOwnerFence(),
            identity.canonicalGameInstanceId(),
            identity.preparationInputJson()));
  }

  private enum IntegrityMode {
    NONE,
    PAIR,
    LATE_HANDLE_CLOSE,
    EXPIRY
  }

  private record RedisEndpoint(String host, int port) {}

  private record IntegritySetup(
      IntegrityMode mode,
      Database sharedWorld,
      RedisEndpoint redis,
      OperationProbe operationProbe) {}

  @FunctionalInterface
  private interface OperationProbe {
    void accept(OperationProof proof) throws Exception;
  }

  @FunctionalInterface
  private interface AuthenticatedPreparation {
    WorldCanonicalInstancePreparation.Result invoke(
        WorldCanonicalInstancePreparation.Input input,
        WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput operation);
  }

  private record OperationProof(
      Database world,
      Database gs,
      DSLContext account,
      WorldCanonicalInstancePreparation.Input input,
      WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput operation,
      WorldCanonicalInstanceExecutionIdentity identity,
      WorldCanonicalInstancePreparation.Result result,
      WorldCanonicalInstanceAssociationRepository association,
      WorldCanonicalInstancePreparationRepository repository,
      AtomicInteger verificationCalls,
      AtomicInteger candidateReads,
      AuthenticatedPreparation authenticatedPrepare,
      java.util.function.Supplier<
              net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal>
          authenticatedHistoricalRead) {}

  private static Map<String, List<String>> allRows(DSLContext sql) {
    String[] tables =
        sql.fetch(
                "SELECT tablename FROM pg_tables WHERE schemaname=current_schema() ORDER BY tablename")
            .getValues(0, String.class)
            .toArray(String[]::new);
    return rows(sql, tables);
  }

  private static Instant databaseNow(DSLContext sql) {
    return sql.fetchSingle("SELECT clock_timestamp()").get(0, OffsetDateTime.class).toInstant();
  }

  private static void assertActualOperationPair(OperationProof first, OperationProof second) {
    assertThat(first.world()).isSameAs(second.world());
    assertThat(first.input().canonicalGameInstanceId())
        .isNotEqualTo(second.input().canonicalGameInstanceId());
    assertThat(first.result().association().worldInstanceId())
        .isNotEqualTo(second.result().association().worldInstanceId());
    assertThat(first.input().completeLaunchBinding())
        .isNotEqualTo(second.input().completeLaunchBinding());
    assertThat(first.operation().originalPostAuthorizationTuple())
        .isNotEqualTo(second.operation().originalPostAuthorizationTuple());
    long firstFence =
        first
            .repository()
            .readExactExecution(first.identity())
            .operation()
            .orElseThrow()
            .worldExecutionFence();
    long secondFence =
        second
            .repository()
            .readExactExecution(second.identity())
            .operation()
            .orElseThrow()
            .worldExecutionFence();
    assertThat(secondFence)
        .as("one World owner's actual sequence, not a cross-domain numeric comparison")
        .isGreaterThan(firstFence);

    // A real extra Version under the FIRST source makes Version substitution independent of tenant.
    var intake = first.input().completeLaunchBinding().sourceIntakeReceipt();
    var source = intake.source();
    var alternateState =
        AuthoredWorldVersionStateEvidence.create(
            new AuthoredWorldVersionStateEvidence.Request(
                1,
                NAMESPACE,
                UUID.randomUUID(),
                source.canonicalTenantId(),
                source.worldSlug(),
                source.operationId(),
                source.evidenceDigest(),
                43L),
            source,
            UUID.randomUUID(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            1L);
    var alternateVersion =
        Objects.requireNonNull(
            first
                .world()
                .write()
                .execute(
                    status ->
                        new WorldAuthoredVersionIdentityRepository(first.world().dsl())
                            .acceptFresh(intake, alternateState)));
    assertThat(alternateVersion.canonicalTenantId())
        .isEqualTo(first.input().versionIdentity().canonicalTenantId());
    assertThat(alternateVersion.canonicalVersionId())
        .isNotEqualTo(first.input().versionIdentity().canonicalVersionId());

    var worldBefore = allRows(first.world().dsl());
    var firstGsBefore = allRows(first.gs().dsl());
    var secondGsBefore = allRows(second.gs().dsl());
    var firstAccountBefore = allRows(first.account());
    var secondAccountBefore = allRows(second.account());
    for (var own : List.of(first, second)) {
      var foreign = own == first ? second : first;
      assertThatThrownBy(
              () ->
                  new WorldCanonicalInstanceAssociation.Claim(
                      own.input().gameSessionReadRequest(),
                      own.input().gameSessionReadEvidence(),
                      own.result().association().worldInstanceId(),
                      foreign.input().completeLaunchBinding(),
                      own.input().versionIdentity()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "Canonical instance claim differs from its exact World launch/source/version evidence");
      var foreignRowClaim =
          new WorldCanonicalInstanceAssociation.Claim(
              own.input().gameSessionReadRequest(),
              own.input().gameSessionReadEvidence(),
              foreign.result().association().worldInstanceId(),
              own.input().completeLaunchBinding(),
              own.input().versionIdentity());
      assertThatThrownBy(
              () ->
                  own.world()
                      .write()
                      .executeWithoutResult(
                          status ->
                              own.association().retainClaimInOwnerTransaction(foreignRowClaim)))
          .isInstanceOf(
              WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException.class)
          .hasMessageContaining("World row has no matching insert-born canonical prepare identity");
      assertThatThrownBy(
              () ->
                  own.identity()
                      .requireMatches(
                          foreign.input(),
                          WorldCanonicalInstancePreparationRepository.inputJson(foreign.input())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "World execution identity differs from the exact original tuple, target, or preparation input");
      int verified = own.verificationCalls().get();
      int candidates = own.candidateReads().get();
      assertThatThrownBy(() -> own.authenticatedPrepare().invoke(own.input(), foreign.operation()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "Original StartSession tuple or owner attempt differs from the exact assembled World operation");
      assertThat(own.verificationCalls()).hasValue(verified);
      assertThat(own.candidateReads()).hasValue(candidates);
      assertThat(own.association().readOwnerAssociation(own.input().canonicalGameInstanceId()))
          .contains(own.result().association());
      assertThat(allRows(first.world().dsl())).isEqualTo(worldBefore);
    }
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceAssociation.Claim(
                    first.input().gameSessionReadRequest(),
                    first.input().gameSessionReadEvidence(),
                    first.result().association().worldInstanceId(),
                    first.input().completeLaunchBinding(),
                    alternateVersion))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "Canonical instance claim differs from its exact World launch/source/version evidence");

    long legacyId =
        first
            .world()
            .dsl()
            .fetchSingle(
                "SELECT id FROM world_instance WHERE control_plane_request_id='native213-retained-request'")
            .get(0, Long.class);
    var legacyClaim =
        new WorldCanonicalInstanceAssociation.Claim(
            first.input().gameSessionReadRequest(),
            first.input().gameSessionReadEvidence(),
            legacyId,
            first.input().completeLaunchBinding(),
            first.input().versionIdentity());
    assertThatThrownBy(
            () ->
                first
                    .world()
                    .write()
                    .executeWithoutResult(
                        status -> first.association().retainClaimInOwnerTransaction(legacyClaim)))
        .isInstanceOf(
            WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException.class)
        .hasMessageContaining("World row has no matching insert-born canonical prepare identity");
    assertThatThrownBy(
            () ->
                first
                    .world()
                    .dsl()
                    .execute(
                        "UPDATE world_instance SET canonical_game_instance_id=? WHERE id=?",
                        first.input().canonicalGameInstanceId(),
                        legacyId))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining(
            "A retained World instance cannot acquire canonical creation identity");

    var currentObservation = observeCompletedGameSessionMetadata(first.gs().dsl(), first.input());
    assertThat(first.input().gameSessionReadEvidence().currentGameSessionStatus())
        .isEqualTo("STARTING");
    assertThat(currentObservation.gameSessionReadEvidence().currentGameSessionStatus())
        .isEqualTo("RUNNING");
    assertThat(currentObservation.gameSessionReadEvidence().currentGameSessionRowVersion())
        .isGreaterThan(first.input().gameSessionReadEvidence().currentGameSessionRowVersion());
    assertThat(currentObservation.gameSessionReadRequest().readRequestId())
        .isNotEqualTo(first.input().gameSessionReadRequest().readRequestId());
    // Settlement closes fresh participation acquisition. Historical read authenticates the
    // original owners without treating current GS observations as permission to execute again.
    assertThat(WorldCanonicalInstancePreparationRepository.inputJson(currentObservation))
        .isEqualTo(WorldCanonicalInstancePreparationRepository.inputJson(first.input()));
    int verificationBeforeHistoricalRead = first.verificationCalls().get();
    var historical = first.authenticatedHistoricalRead().get();
    assertThat(historical.outcome())
        .isEqualTo(
            net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal.Outcome
                .COMMITTED);
    assertThat(historical.originalPostAuthorizationTuple())
        .containsExactly(first.identity().originalPostAuthorizationTuple());
    assertThat(historical.preparationInputJson())
        .isEqualTo(first.identity().preparationInputJson());
    assertThat(historical.worldExecutionFence()).isEqualTo(firstFence);
    assertThat(first.repository().readOwnerPreparation(currentObservation))
        .contains(first.result());
    assertThat(first.verificationCalls()).hasValue(verificationBeforeHistoricalRead);
    assertThat(allRows(first.world().dsl())).isEqualTo(worldBefore);
    assertThat(allRows(first.gs().dsl())).isEqualTo(firstGsBefore);
    assertThat(allRows(second.gs().dsl())).isEqualTo(secondGsBefore);
    assertThat(allRows(first.account())).isEqualTo(firstAccountBefore);
    assertThat(allRows(second.account())).isEqualTo(secondAccountBefore);
    assertThat(
            first
                .repository()
                .readExactExecution(first.identity())
                .operation()
                .orElseThrow()
                .worldExecutionFence())
        .isEqualTo(firstFence);
    assertThat(
            second
                .repository()
                .readExactExecution(second.identity())
                .operation()
                .orElseThrow()
                .worldExecutionFence())
        .isEqualTo(secondFence);
  }

  // Subtract adjacent cumulative milestones for phase durations. Exit markers do not imply success.
  private static void assertMaterializedTopologyAndAssociation(
      DSLContext dsl,
      WorldCanonicalInstancePreparation.Input input,
      WorldCanonicalInstancePreparation.Result prepared,
      WorldCanonicalInstanceAssociationRepository associations) {
    var topology = input.topologyPlan();
    assertThat(prepared.captureId()).isEqualTo(topology.captureId());
    assertThat(prepared.graphSha256())
        .isEqualTo(
            dsl.fetchSingle(
                    "SELECT graph_sha256 FROM world_canonical_frozen_topology WHERE capture_id=?",
                    topology.captureId())
                .get(0, String.class));
    assertThat(prepared.regionCount()).isEqualTo(1);
    assertThat(prepared.zoneCount()).isEqualTo(1);
    assertThat(prepared.roomCount()).isEqualTo(2);
    assertThat(prepared.exitCount()).isEqualTo(1);
    assertThat(prepared.storageStatus()).isEqualTo("MATERIALIZED_UNVERIFIED");
    assertThat(associations.readOwnerAssociation(input.canonicalGameInstanceId()))
        .contains(prepared.association());
    assertThat(prepared.association().completeLaunchBinding())
        .isEqualTo(input.completeLaunchBinding());
    assertThat(prepared.association().versionIdentity()).isEqualTo(input.versionIdentity());

    var read = input.gameSessionReadEvidence();
    var launch = input.completeLaunchBinding();
    var source = launch.sourceIntakeReceipt();
    var descriptor = launch.descriptor();
    var expected = new TreeMap<String, Object>();
    expected.put("schema_version", (short) 2);
    expected.put("canonical_game_instance_id", input.canonicalGameInstanceId());
    expected.put("canonical_target_namespace", read.targetNamespace());
    expected.put("canonical_tenant_id", read.canonicalTenantId());
    expected.put("canonical_world_slug", read.worldSlug());
    expected.put("playable_state_namespace_id", read.playableStateNamespaceId());
    expected.put("playable_state_scope", "SHARED");
    expected.put("public_production", true);
    expected.put("playtest_lifecycle_id", null);
    expected.put("playtest_state_generation", null);
    expected.put("canonical_realm_id", read.canonicalRealmId());
    expected.put("catalog_revision", read.catalogRevision());
    expected.put("control_plane_request_id", read.controlPlaneRequestId());
    expected.put("world_instance_id", prepared.association().worldInstanceId());
    expected.put("canonical_launch_binding_operation_id", launch.operationId());
    expected.put("version_identity_operation_id", input.versionIdentity().operationId());
    expected.put("canonical_version_id", input.versionIdentity().canonicalVersionId());
    expected.put("local_tenant_key", source.localTenantKey());
    expected.put(
        "private_game_instance_key",
        prepared.association().worldPrepareFields().privateGameInstanceKey());
    expected.put("game_template_id", descriptor.gameTemplateId());
    expected.put("launch_descriptor_id", descriptor.launchDescriptorId());
    expected.put("local_version_key", input.versionIdentity().localVersionKey());
    expected.put(
        "script_patch_version",
        descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null);
    expected.put("runtime_flags_json", descriptor.runtimeFlagsJson());
    expected.put("generation_config_revision", descriptor.generationConfigRevision());
    expected.put("release_bundle_id", descriptor.releaseBundleId());
    expected.put("published_release_bundle_ref", descriptor.publishedReleaseBundleRef());
    expected.put("version_state_epoch", descriptor.versionStateEpoch());
    expected.put("remap_set_id", descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null);
    expected.put("intake_operation_id", source.operationId());
    expected.put("intake_request_id", source.intakeRequestId());
    expected.put("source_operation_id", source.sourceOperationId());
    expected.put("source_evidence_digest", source.sourceEvidenceDigest());
    expected.put("intake_request_digest", source.requestDigest());
    expected.put("intake_receipt_digest", source.receiptDigest());
    expected.put("descriptor_request_digest", descriptor.requestDigest());
    expected.put("descriptor_result_digest", descriptor.resultDigest());
    expected.put(
        "release_attestation_digest", launch.evidence().releaseAttestation().evidenceDigest());
    for (String fixtureColumn :
        List.of(
            "canonical_authority_kind",
            "fixture_provenance_digest",
            "game_session_tenant_id",
            "game_session_game_instance_id",
            "game_design_version_id",
            "preparation_input_digest",
            "preparing_lifecycle_epoch",
            "game_session_association_response_bytes")) {
      expected.put(fixtureColumn, null);
    }
    var retained =
        dsl.fetchSingle(
            "SELECT * FROM world_canonical_instance_association WHERE canonical_game_instance_id=?",
            input.canonicalGameInstanceId());
    assertThat(retained.intoMap().keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
    expected.forEach(
        (column, value) -> {
          if (value == null) assertThat(retained.get(column)).as(column).isNull();
          else assertThat(retained.get(column, value.getClass())).as(column).isEqualTo(value);
        });

    var mappings =
        dsl.fetch(
            "SELECT family,template_id FROM world_canonical_instance_topology_identity WHERE world_instance_id=? AND canonical_game_instance_id=?",
            prepared.association().worldInstanceId(),
            input.canonicalGameInstanceId());
    assertThat(mappings.getValues("family", String.class))
        .containsExactlyInAnyOrder("REGION", "ZONE", "ROOM", "ROOM", "ROOM_EXIT");
    assertThat(mappings.getValues("template_id", UUID.class))
        .containsExactlyInAnyOrderElementsOf(
            topology.entries().stream().map(entry -> entry.identity().templateId()).toList());
    for (var entry : topology.entries()) {
      assertThat(
              dsl.fetchSingle(
                      """
          SELECT count(*)=1 FROM world_canonical_instance_topology_identity m
          JOIN world_authored_topology_identity a
            ON a.family=m.family AND a.template_id=m.template_id
            AND a.private_row_key=m.template_private_row_key
          WHERE m.world_instance_id=? AND m.canonical_game_instance_id=?
            AND m.family=? AND m.template_id=?
            AND a.version_identity_operation_id=? AND a.request_id=? AND a.commit_id=?
          """,
                      prepared.association().worldInstanceId(),
                      input.canonicalGameInstanceId(),
                      entry.identity().family().name(),
                      entry.identity().templateId(),
                      input.versionIdentity().operationId(),
                      topology.sourceBinding().plan().binding().requestId(),
                      topology.sourceBinding().plan().binding().commitId())
                  .get(0, Boolean.class))
          .as("exact frozen-to-runtime mapping %s", entry.identity())
          .isTrue();
    }
    var region =
        dsl.fetchSingle(
            """
        SELECT r.name='Selected region' AND r.weather='RAIN' AND r.shard_id=7
          AND r.generation_seed=89123 AND r.generator_type=''
          AND r.generator_params=''
          AND r.spacing_multiplier=1.75 AS authored_payload,
          i.name=r.name AND i.weather=r.weather AND i.shard_id=r.shard_id
          AND i.generation_seed=r.generation_seed AND i.generator_type=r.generator_type
          AND i.generator_params=r.generator_params AND i.spacing_multiplier=r.spacing_multiplier
          AND i.version=0 AS runtime_payload,
          i.world_instance_id=m.world_instance_id AND i.tenant_id=r.tenant_id
          AND i.game_instance_id=w.game_instance_id AND m.runtime_identity=i.canonical_region_instance_id
          AND m.runtime_room_instance_id IS NULL AS scope
        FROM world_canonical_instance_topology_identity m
        JOIN region r ON r.id=m.template_private_row_key
        JOIN region_instance i ON i.id=m.runtime_row_id
        JOIN world_instance w ON w.id=m.world_instance_id
        WHERE m.world_instance_id=? AND m.family='REGION'
        """,
            prepared.association().worldInstanceId());
    assertThat(region.intoMap().values())
        .as("all authored and runtime REGION fields")
        .containsOnly(true);
    var zone =
        dsl.fetchSingle(
            """
        SELECT z.name='Selected zone' AND i.name=z.name AND i.version=0 AS payload,
          z.region_id=parent.template_private_row_key AND i.region_instance_id=parent.runtime_row_id
          AND i.template_zone_id=z.id AND i.zone_instance_id=i.id AS parent,
          i.tenant_id=z.tenant_id AND i.game_instance_id=w.game_instance_id
          AND m.runtime_identity=i.canonical_zone_instance_id
          AND m.runtime_room_instance_id IS NULL AS scope
        FROM world_canonical_instance_topology_identity m
        JOIN zone z ON z.id=m.template_private_row_key
        JOIN zone_instance i ON i.id=m.runtime_row_id
        JOIN world_instance w ON w.id=m.world_instance_id
        JOIN world_canonical_instance_topology_identity parent
          ON parent.world_instance_id=m.world_instance_id AND parent.family='REGION'
        WHERE m.world_instance_id=? AND m.family='ZONE'
        """,
            prepared.association().worldInstanceId());
    assertThat(zone.intoMap().values())
        .as("ZONE payload and exact REGION parent")
        .containsOnly(true);
    var rooms =
        dsl.fetch(
            """
        SELECT r.name,r.description,r.name_localized_variants_json,r.description_localized_variants_json,
          i.name=r.name AND i.description=r.description
          AND i.name_localized_variants_json=r.name_localized_variants_json
          AND i.description_localized_variants_json=r.description_localized_variants_json
          AND i.version=0 AS payload,
          r.zone_id=parent.template_private_row_key AND i.zone_instance_id=parent.runtime_row_id
          AND i.region_instance_id=z.region_instance_id AND i.template_room_id=r.id
          AND i.room_instance_row_id=i.id AS parents,
          i.tenant_id=r.tenant_id AND i.game_instance_id=w.game_instance_id
          AND m.runtime_room_instance_id=i.id AND m.runtime_identity IS NULL AS scope
        FROM world_canonical_instance_topology_identity m
        JOIN room r ON r.id=m.template_private_row_key
        JOIN room_instance i ON i.id=m.runtime_row_id
        JOIN world_instance w ON w.id=m.world_instance_id
        JOIN world_canonical_instance_topology_identity parent
          ON parent.world_instance_id=m.world_instance_id AND parent.family='ZONE'
        JOIN zone_instance z ON z.id=parent.runtime_row_id
        WHERE m.world_instance_id=? AND m.family='ROOM'
        ORDER BY r.name
        """,
            prepared.association().worldInstanceId());
    assertThat(rooms).hasSize(2);
    assertThat(rooms.getValues("name", String.class))
        .containsExactly("Selected destination", "Selected start");
    assertThat(rooms.getValues("description", String.class))
        .containsExactly(
            "A distinct destination beyond the gate.", "The eastern gate opens onto a ridge.");
    assertThat(rooms.getValues("name_localized_variants_json", String.class))
        .containsExactly("{\"fr\":\"Arrivee\"}", "{\"fr\":\"Depart\"}");
    assertThat(rooms.getValues("description_localized_variants_json", String.class))
        .containsExactly("{\"fr\":\"La crete\"}", "{\"fr\":\"La porte est\"}");
    for (String column : List.of("payload", "parents", "scope")) {
      assertThat(rooms.getValues(column, Boolean.class)).as(column).containsExactly(true, true);
    }
    var exit =
        dsl.fetchSingle(
            """
        SELECT e.direction='EAST' AND e.cost=13 AND i.direction=e.direction AND i.cost=e.cost
          AND i.version=0 AS payload,
          e.from_room_id=f.template_private_row_key AND e.to_room_id=t.template_private_row_key
          AND i.from_room_instance_record_id=f.runtime_room_instance_id
          AND i.to_room_instance_record_id=t.runtime_room_instance_id
          AND f.runtime_room_instance_id<>t.runtime_room_instance_id
          AND fr.name='Selected start' AND tr.name='Selected destination'
          AND f.runtime_room_instance_id=? AS endpoints,
          i.tenant_id=e.tenant_id AND i.game_instance_id=w.game_instance_id
          AND m.runtime_identity IS NULL AND m.runtime_room_instance_id IS NULL AS scope
        FROM world_canonical_instance_topology_identity m
        JOIN room_exit e ON e.id=m.template_private_row_key
        JOIN room_instance_exit i ON i.id=m.runtime_row_id
        JOIN world_instance w ON w.id=m.world_instance_id
        JOIN world_canonical_instance_topology_identity f
          ON f.world_instance_id=m.world_instance_id AND f.family='ROOM' AND f.template_private_row_key=e.from_room_id
        JOIN world_canonical_instance_topology_identity t
          ON t.world_instance_id=m.world_instance_id AND t.family='ROOM' AND t.template_private_row_key=e.to_room_id
        JOIN room_instance fr ON fr.id=f.runtime_row_id
        JOIN room_instance tr ON tr.id=t.runtime_row_id
        WHERE m.world_instance_id=? AND m.family='ROOM_EXIT'
        """,
            prepared.runtimeRoomInstanceId(),
            prepared.association().worldInstanceId());
    assertThat(exit.intoMap().values())
        .as("EXIT cost and distinct canonical endpoint mappings")
        .containsOnly(true);
  }

  private static WorldCanonicalInstancePreparation.Input observeCompletedGameSessionMetadata(
      DSLContext gs, WorldCanonicalInstancePreparation.Input input) {
    var previous = input.gameSessionReadEvidence();
    var request =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.randomUUID(),
            previous.targetNamespace(),
            previous.canonicalTenantId(),
            previous.worldSlug(),
            previous.canonicalGameInstanceId(),
            previous.controlPlaneRequestId(),
            previous.launchDescriptorId(),
            previous.descriptorRequestDigest(),
            previous.descriptorResultDigest(),
            previous.releaseAttestationEvidenceDigest());
    var owner = new CanonicalGameInstanceLaunchAssociationRepository(gs, NAMESPACE);
    // STARTING-only admission evidence is intentionally unavailable after the actual owner commits.
    // This local immutable/current projection is observation-only. The separate
    // Account-authenticated
    // historical terminal read verifies retained authority; these fields never authorize execution.
    assertThat(owner.readOriginalStartSessionAssociation(request)).isEmpty();
    var retained = owner.readWithCatalog(previous.controlPlaneRequestId()).orElseThrow();
    var actual = retained.association();
    assertThat(actual.launchBindingEvidence()).isEqualTo(input.completeLaunchBinding().evidence());
    assertThat(actual.gameInstanceUuid()).isEqualTo(input.canonicalGameInstanceId());
    assertThat(actual.targetNamespace()).isEqualTo(previous.targetNamespace());
    var read =
        new WorldCanonicalInstanceAssociation.GameSessionReadRequest(
            request.readRequestId(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.gameInstanceUuid(),
            request.controlPlaneRequestId(),
            request.launchDescriptorId(),
            request.expectedDescriptorRequestDigest(),
            request.expectedDescriptorResultDigest(),
            request.expectedReleaseAttestationEvidenceDigest());
    var evidence =
        new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
            request.readRequestId(),
            previous.targetNamespace(),
            previous.canonicalTenantId(),
            previous.worldSlug(),
            previous.canonicalGameInstanceId(),
            previous.controlPlaneRequestId(),
            previous.launchDescriptorId(),
            previous.descriptorRequestDigest(),
            previous.descriptorResultDigest(),
            previous.releaseAttestationEvidenceDigest(),
            actual.playableStateNamespaceId(),
            actual.playableStateScope().name(),
            actual.publicProduction(),
            retained.canonicalRealmId(),
            retained.catalogRevision(),
            actual.currentGameInstanceStatus().name(),
            actual.currentRowVersion(),
            previous.descriptor(),
            previous.releaseAttestation());
    return new WorldCanonicalInstancePreparation.Input(
        read,
        evidence,
        input.gameSessionOwnerAttemptId(),
        input.gameSessionOwnerFence(),
        input.completeLaunchBinding(),
        input.versionIdentity(),
        input.topologyPlan());
  }

  private static WorldCanonicalInstancePreparation.Input refreshGameSessionObservation(
      DSLContext gs, WorldCanonicalInstancePreparation.Input input) {
    var previous = input.gameSessionReadEvidence();
    var request =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.randomUUID(),
            previous.targetNamespace(),
            previous.canonicalTenantId(),
            previous.worldSlug(),
            previous.canonicalGameInstanceId(),
            previous.controlPlaneRequestId(),
            previous.launchDescriptorId(),
            previous.descriptorRequestDigest(),
            previous.descriptorResultDigest(),
            previous.releaseAttestationEvidenceDigest());
    var retained =
        new CanonicalGameInstanceLaunchAssociationRepository(gs, NAMESPACE)
            .readOriginalStartSessionAssociation(request)
            .orElseThrow();
    assertThat(retained.ownerAttemptId()).isEqualTo(input.gameSessionOwnerAttemptId());
    assertThat(retained.ownerFence()).isEqualTo(input.gameSessionOwnerFence());
    var actual = retained.association();
    var read =
        new WorldCanonicalInstanceAssociation.GameSessionReadRequest(
            request.readRequestId(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.gameInstanceUuid(),
            request.controlPlaneRequestId(),
            request.launchDescriptorId(),
            request.expectedDescriptorRequestDigest(),
            request.expectedDescriptorResultDigest(),
            request.expectedReleaseAttestationEvidenceDigest());
    var evidence =
        new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
            request.readRequestId(),
            previous.targetNamespace(),
            previous.canonicalTenantId(),
            previous.worldSlug(),
            previous.canonicalGameInstanceId(),
            previous.controlPlaneRequestId(),
            previous.launchDescriptorId(),
            previous.descriptorRequestDigest(),
            previous.descriptorResultDigest(),
            previous.releaseAttestationEvidenceDigest(),
            actual.playableStateNamespaceId(),
            actual.playableStateScope().name(),
            actual.publicProduction(),
            retained.canonicalRealmId(),
            retained.catalogRevision(),
            actual.currentGameInstanceStatus().name(),
            actual.currentRowVersion(),
            previous.descriptor(),
            previous.releaseAttestation());
    return new WorldCanonicalInstancePreparation.Input(
        read,
        evidence,
        input.gameSessionOwnerAttemptId(),
        input.gameSessionOwnerFence(),
        input.completeLaunchBinding(),
        input.versionIdentity(),
        input.topologyPlan());
  }

  private static void logCompositionTiming(long startedNanos, String phase) {
    long elapsedNanos = System.nanoTime() - startedNanos;
    LOGGER.info(
        "World owner composition timing: invocation={} phase={} elapsedMillis={}",
        startedNanos,
        phase,
        TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
  }

  private static CanonicalInitialAdmissionRequest canonicalAdmissionRequest(
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity) {
    var request = identity.request();
    return new CanonicalInitialAdmissionRequest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        request.activeLifecycleEpoch(),
        request.expectedCatalogRevision(),
        CanonicalInitialAdmissionRequest.OriginKind.valueOf(
            request.initialAdmissionOrigin().name()),
        request.expectedPriorPointerVersion(),
        request.initialAdmissionRequestId(),
        request.initialAdmissionRequestDigest(),
        identity.holdId(),
        identity.holdFence(),
        identity.holdBindingDigest());
  }

  private static void exerciseCanonicalInitialAdmissionHold(
      Database world,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest)
      throws Exception {
    var association =
        associationRepository
            .readOwnerAssociation(lifecycleRequest.canonicalGameInstanceId())
            .orElseThrow();
    assertThat(association.isRunOwnedFixture()).isFalse();
    var active = lifecycleRepository.read(lifecycleRequest).orElseThrow();
    assertThat(active.lifecycleStatus()).isEqualTo("ACTIVE");
    assertThat(active.lifecycleEpoch()).isGreaterThan(0L);

    var request =
        canonicalHoldRequest(
            association,
            active.lifecycleEpoch(),
            "controlled-canonical-admission-" + UUID.randomUUID());
    var repository =
        new WorldCanonicalInitialAdmissionHoldRepository(
            world.dsl(), world.manager(), associationRepository, lifecycleRepository);
    // This is a controlled World-boundary response source. The composition has no real
    // Game Session canonical-admission owner producer or pointer to claim here.
    var observedProof = new AtomicReference<GameSessionCanonicalInitialAdmissionOwnerProof>();
    var readFailure = new AtomicReference<RuntimeException>();
    GameSessionCanonicalInitialAdmissionOwnerClient controlledGameSessionReader =
        identity -> {
          RuntimeException failure = readFailure.get();
          if (failure != null) throw failure;
          return Objects.requireNonNull(observedProof.get(), "controlled owner proof");
        };
    var owner =
        new WorldCanonicalInitialAdmissionHoldOwner(repository, controlledGameSessionReader);

    var identity =
        acquireCanonicalHoldAheadOfTermination(
            world, association, owner, request, lifecycleRequest);
    assertThat(identity.request()).isEqualTo(request);
    assertThat(owner.acquire(request, lifecycleRequest)).isEqualTo(identity);
    var restartedOwner =
        new WorldCanonicalInitialAdmissionHoldOwner(
            new WorldCanonicalInitialAdmissionHoldRepository(
                world.dsl(), world.manager(), associationRepository, lifecycleRepository),
            ignored -> {
              throw new AssertionError("Identity reconstruction must not read Game Session");
            });
    assertThat(restartedOwner.readIdentity(request)).contains(identity);

    var readback = owner.readState(identity, lifecycleRequest).orElseThrow();
    assertThat(readback.holdIdentity()).isEqualTo(identity);
    assertThat(readback.holdStatus().name()).isEqualTo("PENDING");
    assertThat(readback.isPendingAtExpectedActiveEpoch()).isTrue();

    var conflict =
        canonicalHoldRequest(
            association,
            active.lifecycleEpoch(),
            "conflicting-canonical-admission-" + UUID.randomUUID());
    assertThatThrownBy(() -> owner.acquire(conflict, lifecycleRequest))
        .isInstanceOf(WorldCanonicalInitialAdmissionHoldRepository.HoldConflictException.class);

    var mismatchedRequest = withChangedDigest(request);
    assertThatThrownBy(() -> owner.acquire(mismatchedRequest, lifecycleRequest))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException.class);

    readFailure.set(Status.UNAVAILABLE.asRuntimeException());
    assertThatThrownBy(() -> owner.settleFromAuthenticatedOwnerRead(identity))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(owner.readState(identity, lifecycleRequest).orElseThrow().holdStatus().name())
        .isEqualTo("PENDING");
    readFailure.set(null);
    observedProof.set(pendingOwnerProof(identity));
    assertThat(owner.settleFromAuthenticatedOwnerRead(identity)).isEqualTo(observedProof.get());
    assertThat(owner.readState(identity, lifecycleRequest).orElseThrow().holdStatus().name())
        .isEqualTo("PENDING");

    var differentRequest =
        canonicalHoldRequest(
            association,
            active.lifecycleEpoch(),
            "mismatched-canonical-admission-" + UUID.randomUUID());
    var differentIdentity =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            differentRequest, UUID.randomUUID(), UUID.randomUUID());
    observedProof.set(pendingOwnerProof(differentIdentity));
    assertThatThrownBy(() -> owner.settleFromAuthenticatedOwnerRead(identity))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException.class);
    assertThat(owner.readState(identity, lifecycleRequest).orElseThrow().holdStatus().name())
        .isEqualTo("PENDING");
  }

  private static WorldCanonicalInitialAdmissionHold.Request canonicalHoldRequest(
      WorldCanonicalInstanceAssociation association, long activeLifecycleEpoch, String requestId) {
    var target = association.identity();
    var origin = CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER;
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            target.targetNamespace(),
            target.canonicalTenantId(),
            target.worldSlug(),
            target.canonicalRealmId(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.canonicalGameInstanceId(),
            association.canonicalVersionId(),
            activeLifecycleEpoch,
            target.catalogRevision(),
            origin,
            null,
            requestId);
    return new WorldCanonicalInitialAdmissionHold.Request(
        target.targetNamespace(),
        target.canonicalTenantId(),
        target.worldSlug(),
        target.canonicalRealmId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.canonicalGameInstanceId(),
        association.canonicalVersionId(),
        activeLifecycleEpoch,
        requestId,
        requestDigest,
        WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        target.catalogRevision(),
        null);
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof pendingOwnerProof(
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity) {
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
        null,
        null,
        null,
        false,
        null);
  }

  /**
   * Uses the existing authenticated terminal producer, not synthetic permission or lifecycle
   * writes.
   */
  private static GameSessionCanonicalInitialAdmissionOwnerProof
      settleAbortedHoldWhileCanonicalSnapshotIsOpen(
          Database world,
          WorldCanonicalInstanceAssociationRepository associations,
          WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
          WorldCanonicalInitialAdmissionHold.HoldIdentity identity,
          java.util.concurrent.Callable<GameSessionCanonicalInitialAdmissionOwnerProof> settle)
          throws Exception {
    var holdRead = new CountDownLatch(1);
    var settlementCommitted = new CountDownLatch(1);
    var paused = new AtomicBoolean();
    var readerPid = new java.util.concurrent.atomic.AtomicInteger();
    var lifecycleReads = new java.util.concurrent.atomic.AtomicInteger();
    var listener =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void fetchEnd(org.jooq.ExecuteContext context) {
            String sql = context.sql();
            if (sql == null
                || !sql.startsWith(
                    "SELECT * FROM initial_admission_bind_hold WHERE hold_id=? AND hold_fence=?")
                || !paused.compareAndSet(false, true)) return;
            var inside =
                DSL.using(
                    Objects.requireNonNull(context.connection(), "Actual hold-read connection"),
                    SQLDialect.POSTGRES);
            var settings =
                inside.fetchSingle(
                    "SELECT pg_backend_pid(), current_setting('transaction_isolation'), "
                        + "current_setting('transaction_read_only')");
            readerPid.set(settings.get(0, Integer.class));
            assertThat(settings.get(1, String.class)).isEqualTo("repeatable read");
            assertThat(settings.get(2, String.class)).isEqualTo("on");
            assertThat(
                    inside
                        .fetchSingle(
                            "SELECT status FROM initial_admission_bind_hold WHERE hold_id=?",
                            identity.holdId())
                        .get(0, String.class))
                .isEqualTo("PENDING");
            holdRead.countDown();
            try {
              assertThat(settlementCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("Interrupted canonical snapshot probe", interrupted);
            }
            // This query uses the actual repository transaction connection after the separate
            // authenticated writer committed. It must not observe the new terminal row.
            assertThat(
                    inside
                        .fetchSingle(
                            "SELECT status FROM initial_admission_bind_hold WHERE hold_id=?",
                            identity.holdId())
                        .get(0, String.class))
                .isEqualTo("PENDING");
          }

          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String sql = context.sql();
            if (sql != null && sql.startsWith("SELECT * FROM world_instance WHERE id=?")) {
              var inside =
                  DSL.using(
                      Objects.requireNonNull(context.connection(), "Actual lifecycle connection"),
                      SQLDialect.POSTGRES);
              assertThat(inside.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class))
                  .isEqualTo(readerPid.get());
              lifecycleReads.incrementAndGet();
            }
          }
        };
    var observedDsl = DSL.using(world.dsl().configuration().derive(listener));
    var observedLifecycles =
        new WorldCanonicalInstanceLifecycleReadRepository(
            observedDsl, world.manager(), associations);
    var observedRepository =
        new WorldCanonicalInitialAdmissionHoldRepository(
            observedDsl, world.manager(), associations, observedLifecycles);
    var workers = Executors.newSingleThreadExecutor();
    var reading =
        workers.submit(
            () -> observedRepository.readState(identity, lifecycleRequest).orElseThrow());
    try {
      assertThat(holdRead.await(15, TimeUnit.SECONDS)).isTrue();
      var proof = settle.call();
      assertThat(proof.outcome())
          .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
      assertThat(world.dsl().fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class))
          .isNotEqualTo(readerPid.get());
      assertThat(
              world
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM initial_admission_bind_hold WHERE hold_id=?",
                      identity.holdId())
                  .get(0, String.class))
          .isEqualTo("ABORTED");
      settlementCommitted.countDown();
      var snapshot = reading.get(15, TimeUnit.SECONDS);
      assertThat(snapshot.holdIdentity()).isEqualTo(identity);
      assertThat(snapshot.holdStatus().name()).isEqualTo("PENDING");
      assertThat(snapshot.isPendingAtExpectedActiveEpoch()).isTrue();
      assertThat(lifecycleReads.get()).isPositive();
      var freshRepository =
          new WorldCanonicalInitialAdmissionHoldRepository(
              world.dsl(),
              world.manager(),
              associations,
              new WorldCanonicalInstanceLifecycleReadRepository(
                  world.dsl(), world.manager(), associations));
      assertThat(
              freshRepository
                  .readState(identity, lifecycleRequest)
                  .orElseThrow()
                  .holdStatus()
                  .name())
          .isEqualTo("ABORTED");
      return proof;
    } finally {
      settlementCommitted.countDown();
      reading.cancel(true);
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof settleAfterAuthenticatedClientClose(
      Database world,
      WorldCanonicalInstanceAssociationRepository associations,
      WorldCanonicalInstanceLifecycleReadRepository lifecycles,
      ServiceEndpointsProperties endpoints,
      WorldOriginalStartSessionPreparationProofPki pki,
      GrpcChannelFactory factory,
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity)
      throws Exception {
    var repository =
        new WorldCanonicalInitialAdmissionHoldRepository(
            world.dsl(), world.manager(), associations, lifecycles);
    var before = rows(world.dsl(), "initial_admission_bind_hold");
    try (var interrupted =
        new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
            endpoints, pki.client("world-management-service"), factory, NAMESPACE)) {
      interrupted.init();
      interrupted.close();
      var unavailableOwner = new WorldCanonicalInitialAdmissionHoldOwner(repository, interrupted);
      assertThatThrownBy(() -> unavailableOwner.settleFromAuthenticatedOwnerRead(identity))
          .isInstanceOf(IllegalStateException.class);
      assertThat(rows(world.dsl(), "initial_admission_bind_hold")).isEqualTo(before);
      var association =
          associations
              .readOwnerAssociation(identity.request().canonicalGameInstanceId())
              .orElseThrow();
      assertThatThrownBy(
              () ->
                  world
                      .dsl()
                      .execute(
                          "UPDATE world_instance SET status='TERMINATING', "
                              + "lifecycle_epoch=lifecycle_epoch+1,row_version=row_version+1 "
                              + "WHERE id=? AND status='ACTIVE' AND lifecycle_epoch=?",
                          association.worldInstanceId(),
                          identity.request().activeLifecycleEpoch()))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("55000"));
      assertThat(rows(world.dsl(), "initial_admission_bind_hold")).isEqualTo(before);
    }
    try (var recovered =
        new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
            endpoints, pki.client("world-management-service"), factory, NAMESPACE)) {
      recovered.init();
      var owner =
          new WorldCanonicalInitialAdmissionHoldOwner(
              repository,
              expected -> {
                var authenticated = recovered.read(expected);
                try {
                  recovered.close();
                } catch (java.io.IOException failure) {
                  throw new java.io.UncheckedIOException(failure);
                }
                assertThatThrownBy(() -> recovered.read(expected))
                    .isInstanceOf(IllegalStateException.class);
                // This is the actual owner-read/World-write boundary: no settlement has run yet.
                assertThat(rows(world.dsl(), "initial_admission_bind_hold")).isEqualTo(before);
                return authenticated;
              });
      var proof = owner.settleFromAuthenticatedOwnerRead(identity);
      var settled = rows(world.dsl(), "initial_admission_bind_hold");
      var replay = new WorldCanonicalInitialAdmissionHoldOwner(repository, ignored -> proof);
      assertThat(replay.settleFromAuthenticatedOwnerRead(identity)).isEqualTo(proof);
      assertThat(rows(world.dsl(), "initial_admission_bind_hold")).isEqualTo(settled);
      var changedIdentity =
          new WorldCanonicalInitialAdmissionHold.HoldIdentity(
              identity.request(), identity.holdId(), UUID.randomUUID());
      assertThatThrownBy(() -> replay.settleFromAuthenticatedOwnerRead(changedIdentity))
          .isInstanceOf(
              WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException.class);
      var contradictory =
          new GameSessionCanonicalInitialAdmissionOwnerProof(
              identity,
              proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
                  ? GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED
                  : GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
              proof.positiveDurableAbort() ? 1L : null,
              proof.positiveDurableAbort() ? 42L : null,
              proof.proofDigest(),
              !proof.positiveDurableAbort(),
              proof.terminalAt());
      var conflict =
          new WorldCanonicalInitialAdmissionHoldOwner(repository, ignored -> contradictory);
      assertThatThrownBy(() -> conflict.settleFromAuthenticatedOwnerRead(identity))
          .isInstanceOf(
              WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException.class);
      assertThat(rows(world.dsl(), "initial_admission_bind_hold")).isEqualTo(settled);
      return proof;
    }
  }

  private static void assertCommittedHoldStored(
      DSLContext world,
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity,
      GameSessionCanonicalInitialAdmissionOwnerProof proof)
      throws Exception {
    var row =
        world.fetchSingle(
            "SELECT status,owner_proof_id,owner_proof_digest,owner_pointer_version,"
                + "owner_pointer_audit_id,canonical_owner_proof_bytes,"
                + "canonical_owner_proof_digest,row_version FROM initial_admission_bind_hold "
                + "WHERE hold_id=? AND hold_fence=?",
            identity.holdId(),
            identity.holdFence());
    byte[] proofBytes = GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof);
    assertThat(row.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(row.get("owner_proof_id", String.class))
        .isEqualTo(identity.request().initialAdmissionRequestId());
    assertThat(row.get("owner_proof_digest", String.class))
        .isEqualTo(proof.proofDigest().substring("sha256:".length()));
    assertThat(row.get("owner_pointer_version", Long.class))
        .isEqualTo(proof.committedPointerVersion());
    assertThat(row.get("owner_pointer_audit_id", String.class))
        .isEqualTo(Long.toString(proof.auditEventId()));
    assertThat(row.get("canonical_owner_proof_bytes", byte[].class)).containsExactly(proofBytes);
    assertThat(row.get("canonical_owner_proof_digest", String.class))
        .isEqualTo(prefixedSha256(proofBytes));
    assertThat(row.get("row_version", Long.class)).isEqualTo(1L);
  }

  private static void assertAbortedHoldStored(
      DSLContext world,
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity,
      GameSessionCanonicalInitialAdmissionOwnerProof proof)
      throws Exception {
    var row =
        world.fetchSingle(
            "SELECT status,owner_proof_id,owner_proof_digest,owner_pointer_version,"
                + "owner_pointer_audit_id,canonical_owner_proof_bytes,"
                + "canonical_owner_proof_digest,row_version FROM initial_admission_bind_hold "
                + "WHERE hold_id=? AND hold_fence=?",
            identity.holdId(),
            identity.holdFence());
    byte[] proofBytes = GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof);
    assertThat(row.get("status", String.class)).isEqualTo("ABORTED");
    assertThat(row.get("owner_proof_id", String.class))
        .isEqualTo(identity.request().initialAdmissionRequestId());
    assertThat(row.get("owner_proof_digest", String.class))
        .isEqualTo(proof.proofDigest().substring("sha256:".length()));
    assertThat(row.get("owner_pointer_version", Long.class)).isNull();
    assertThat(row.get("owner_pointer_audit_id", String.class)).isNull();
    assertThat(row.get("canonical_owner_proof_bytes", byte[].class)).containsExactly(proofBytes);
    assertThat(row.get("canonical_owner_proof_digest", String.class))
        .isEqualTo(prefixedSha256(proofBytes));
    assertThat(row.get("row_version", Long.class)).isEqualTo(1L);
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity
      acquireCanonicalHoldAheadOfTermination(
          Database world,
          WorldCanonicalInstanceAssociation association,
          WorldCanonicalInitialAdmissionHoldOwner owner,
          WorldCanonicalInitialAdmissionHold.Request request,
          WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest)
          throws Exception {
    return acquireCanonicalHoldAheadOfTermination(
        world, association, () -> owner.acquire(request, lifecycleRequest), request);
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity
      acquireCanonicalHoldAheadOfTermination(
          Database world,
          WorldCanonicalInstanceAssociation association,
          java.util.concurrent.Callable<WorldCanonicalInitialAdmissionHold.HoldIdentity>
              acquisition,
          WorldCanonicalInitialAdmissionHold.Request request)
          throws Exception {
    var source = dataSource(world.dsl());
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Connection gate = source.getConnection();
    try {
      gate.setAutoCommit(false);
      DSLContext gateSql = DSL.using(gate, SQLDialect.POSTGRES);
      int gatePid = gateSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);
      gateSql.fetchSingle(
          "SELECT id FROM world_instance WHERE id=? FOR UPDATE", association.worldInstanceId());

      CountDownLatch acquisitionStarted = new CountDownLatch(1);
      Future<WorldCanonicalInitialAdmissionHold.HoldIdentity> acquiring =
          workers.submit(
              () -> {
                acquisitionStarted.countDown();
                return acquisition.call();
              });
      assertThat(acquisitionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      int acquisitionPid = awaitAcquisitionBlockedBy(world.dsl(), gatePid, acquiring);

      CountDownLatch transitionStarted = new CountDownLatch(1);
      AtomicInteger transitionPid = new AtomicInteger();
      Future<String> transitioning =
          workers.submit(
              () -> {
                try (Connection transition = source.getConnection()) {
                  transition.setAutoCommit(true);
                  DSLContext transitionSql = DSL.using(transition, SQLDialect.POSTGRES);
                  transitionPid.set(
                      transitionSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                  transitionStarted.countDown();
                  transitionSql.execute(
                      "UPDATE world_instance SET status='TERMINATING', "
                          + "lifecycle_epoch=lifecycle_epoch+1,row_version=row_version+1 "
                          + "WHERE id=? AND status='ACTIVE' AND lifecycle_epoch=?",
                      association.worldInstanceId(),
                      request.activeLifecycleEpoch());
                  return "COMMITTED";
                } catch (Throwable failure) {
                  return sqlState(failure);
                }
              });
      assertThat(transitionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitTransitionQueuedBehindAcquisition(
          world.dsl(), gatePid, acquisitionPid, transitionPid.get(), acquiring, transitioning);
      assertThat(acquiring.isDone()).isFalse();
      assertThat(transitioning.isDone()).isFalse();

      gate.commit();
      var identity = acquiring.get(20, TimeUnit.SECONDS);
      assertThat(transitioning.get(20, TimeUnit.SECONDS)).isEqualTo("55000");
      var current =
          world
              .dsl()
              .fetchSingle(
                  "SELECT status,lifecycle_epoch FROM world_instance WHERE id=?",
                  association.worldInstanceId());
      assertThat(current.get("status", String.class)).isEqualTo("ACTIVE");
      assertThat(current.get("lifecycle_epoch", Long.class))
          .isEqualTo(request.activeLifecycleEpoch());
      assertThat(
              world
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM initial_admission_bind_hold WHERE hold_id=?",
                      identity.holdId())
                  .get("status", String.class))
          .isEqualTo("PENDING");
      return identity;
    } finally {
      try {
        if (!gate.getAutoCommit()) gate.rollback();
      } finally {
        gate.close();
        workers.shutdownNow();
        workers.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  private static int awaitAcquisitionBlockedBy(DSLContext world, int gatePid, Future<?> acquisition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      var waiters =
          world.fetch(
              "SELECT a.pid FROM pg_stat_activity a "
                  + "WHERE a.pid<>? AND a.datname=current_database() "
                  + "AND a.wait_event_type='Lock' "
                  + "AND ?=ANY(pg_blocking_pids(a.pid)) "
                  + "ORDER BY a.pid",
              gatePid,
              gatePid);
      if (!waiters.isEmpty()) return waiters.get(0).get("pid", Integer.class);
      Thread.sleep(10L);
    }
    throw new AssertionError(
        "Canonical hold acquisition did not queue behind the World gate; acquisitionFuture="
            + futureStatus(acquisition)
            + "; lockActivities="
            + lockActivitySnapshot(world, gatePid));
  }

  private static void awaitTransitionQueuedBehindAcquisition(
      DSLContext world,
      int gatePid,
      int acquisitionPid,
      int transitionPid,
      Future<?> acquisition,
      Future<?> transition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      var acquisitionState =
          world.fetchOne(
              "SELECT wait_event_type, ?=ANY(pg_blocking_pids(pid)) AS gate_blocks "
                  + "FROM pg_stat_activity WHERE pid=?",
              gatePid,
              acquisitionPid);
      var transitionState =
          world.fetchOne(
              "SELECT wait_event_type, "
                  + "(?=ANY(pg_blocking_pids(pid)) OR ?=ANY(pg_blocking_pids(pid))) AS queued "
                  + "FROM pg_stat_activity WHERE pid=?",
              gatePid,
              acquisitionPid,
              transitionPid);
      if (acquisitionState != null
          && "Lock".equals(acquisitionState.get("wait_event_type", String.class))
          && Boolean.TRUE.equals(acquisitionState.get("gate_blocks", Boolean.class))
          && transitionState != null
          && "Lock".equals(transitionState.get("wait_event_type", String.class))
          && Boolean.TRUE.equals(transitionState.get("queued", Boolean.class))) {
        return;
      }
      Thread.sleep(10L);
    }
    throw new AssertionError(
        "Canonical hold acquisition and lifecycle transition were not both queued; acquisitionFuture="
            + futureStatus(acquisition)
            + "; transitionFuture="
            + futureStatus(transition)
            + "; lockActivities="
            + lockActivitySnapshot(world, gatePid, acquisitionPid, transitionPid));
  }

  private static WorldCanonicalInitialAdmissionHold.Request withChangedDigest(
      WorldCanonicalInitialAdmissionHold.Request request) {
    String digest = request.initialAdmissionRequestDigest();
    String changedDigest =
        digest.charAt(0) == '0' ? "1" + digest.substring(1) : "0" + digest.substring(1);
    return new WorldCanonicalInitialAdmissionHold.Request(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        request.activeLifecycleEpoch(),
        request.initialAdmissionRequestId(),
        changedDigest,
        request.initialAdmissionOrigin(),
        request.expectedCatalogRevision(),
        request.expectedPriorPointerVersion());
  }

  private static String futureStatus(Future<?> future) {
    if (!future.isDone()) return "pending";
    if (future.isCancelled()) return "cancelled";
    try {
      future.get(0, TimeUnit.MILLISECONDS);
      return "completed";
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return "interrupted";
    } catch (Exception failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      return "failed=" + cause.getClass().getName() + ": " + cause.getMessage();
    }
  }

  private static String lockActivitySnapshot(DSLContext world, int... pids) {
    String pidList = String.join(",", java.util.Collections.nCopies(pids.length, "?"));
    Object[] bindings = new Object[pids.length];
    for (int i = 0; i < pids.length; i++) bindings[i] = pids[i];
    return world
        .fetch(
            "SELECT pid,wait_event_type,wait_event,pg_blocking_pids(pid) AS blockers,"
                + "left(query,300) AS query FROM pg_stat_activity "
                + "WHERE wait_event_type='Lock' OR pid IN ("
                + pidList
                + ") ORDER BY pid",
            bindings)
        .format();
  }

  private static javax.sql.DataSource dataSource(DSLContext dsl) {
    var provider =
        (org.jooq.impl.DataSourceConnectionProvider) dsl.configuration().connectionProvider();
    var proxy = (TransactionAwareDataSourceProxy) provider.dataSource();
    var target = Objects.requireNonNull(proxy.getTargetDataSource());
    // Concurrency/fence probes retain independent physical connections outside the owner pool.
    return target instanceof HikariDataSource pool ? pool.getDataSource() : target;
  }

  private static SQLException findSqlException(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException) return sqlException;
    }
    return null;
  }

  private static String sqlState(Throwable failure) {
    SQLException sqlException = findSqlException(failure);
    return sqlException == null ? null : sqlException.getSQLState();
  }

  private static String accountRowSnapshot(DSLContext accountSql, UUID accountUuid) {
    return accountSql
        .fetchSingle(
            "SELECT row_to_json(account_row)::text || '|xmin=' || account_row.xmin::text "
                + "AS body FROM accounts account_row WHERE account_uuid=?",
            accountUuid)
        .get("body", String.class);
  }

  private static void assertAccountSourceWriteBlockedWhileAdmissionPending(
      DSLContext accountSql, UUID accountUuid, String passwordBeforeWrite) {
    assertThatThrownBy(
            () ->
                new TransactionTemplate(accountManager(accountSql))
                    .executeWithoutResult(
                        ignored ->
                            updateAccountPasswordThroughOwner(
                                accountSql, accountUuid, passwordBeforeWrite + "-blocked")))
        .satisfies(
            failure -> {
              SQLException sqlFailure =
                  Objects.requireNonNull(
                      findSqlException(failure), "Account source writer SQLException required");
              assertThat(sqlFailure.getSQLState()).isEqualTo("55P03");
              assertThat(sqlFailure.getMessage())
                  .contains(
                      "Original StartSession admission protection remains pending for a required "
                          + "source");
            });
  }

  private static void assertNoCanonicalAdmissionPointerArtifacts(
      DSLContext gsSql, String initialAdmissionRequestId) {
    assertThat(
            gsSql
                .fetchSingle(
                    "SELECT count(*) FROM gameplay_admission_pointer "
                        + "WHERE initial_admission_request_id=?",
                    initialAdmissionRequestId)
                .get(0, Long.class))
        .isZero();
    assertThat(
            gsSql
                .fetchSingle(
                    "SELECT count(*) FROM gameplay_admission_pointer_event "
                        + "WHERE initial_admission_request_id=?",
                    initialAdmissionRequestId)
                .get(0, Long.class))
        .isZero();
  }

  private static String prefixedSha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static net.firedevops.firemud.accountservice.entity.Account
      updateAccountPasswordThroughOwner(DSLContext accountSql, UUID accountUuid, String hash) {
    var accounts = new AccountRepository(accountSql);
    var account = accounts.findByAccountUuidForUpdate(accountUuid).orElseThrow();
    account.setPasswordHash(hash);
    return accounts.save(account);
  }

  private static void exerciseCoupledCanonicalInitialAdmission(
      Database gs,
      Database world,
      AccountControlUiOriginalOrderFixture account,
      AccountOriginalStartSessionAuthorizationProofFixture issuer,
      GrpcOriginalStartSessionCurrentAttemptClient currentAttempt,
      Path proofDirectory,
      WorldOriginalStartSessionPreparationProofPki pki,
      GrpcChannelFactory factory,
      ServiceEndpointsProperties gsEndpoints,
      ServiceEndpointsProperties worldEndpoints,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository,
      CanonicalInitialAdmissionRepository admissionOwner,
      WorldCanonicalInitialAdmissionHoldOwner worldHoldOwner,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim originalOwner,
      AccountStartSessionActivationProtectionRequest originalProtectionRequest,
      net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal
          authenticatedWorldTerminal,
      boolean abortCanonicalInitialAdmission,
      RealmPolicyPublishedEvidence.PublishedSet createdPublication)
      throws Exception {
    var association =
        associationRepository
            .readOwnerAssociation(lifecycleRequest.canonicalGameInstanceId())
            .orElseThrow();
    var active = lifecycleRepository.read(lifecycleRequest).orElseThrow();
    assertThat(association.isRunOwnedFixture()).isFalse();
    assertThat(active.lifecycleStatus()).isEqualTo("ACTIVE");
    assertThat(active.lifecycleEpoch()).isEqualTo(2L);
    assertThat(originalProtectionRequest.originalPostAuthorizationTupleBytes())
        .containsExactly(originalTuple.canonicalBytes());
    assertThat(originalProtectionRequest.gameSessionOwnerAttemptId())
        .isEqualTo(originalOwner.ownerAttemptId());
    assertThat(originalProtectionRequest.gameSessionOwnerMutationId())
        .isEqualTo(originalOwner.ownerMutationId());
    assertThat(originalProtectionRequest.gameSessionOwnerFence())
        .isEqualTo(originalOwner.ownerFence());

    var holdRequest =
        canonicalHoldRequest(
            association,
            active.lifecycleEpoch(),
            "coupled-canonical-admission-" + UUID.randomUUID());
    var intentRepository =
        new CanonicalInitialAdmissionIntentRepository(
            gs.dsl(),
            new GameSessionCanonicalRealmCatalogRepository(gs.dsl()),
            new CanonicalGameInstanceLaunchAssociationRepository(gs.dsl(), NAMESPACE));
    var reservedIntent =
        Objects.requireNonNull(
            gs.write()
                .execute(
                    ignored ->
                        intentRepository.reserveHumanStarting(holdRequest, lifecycleRequest)),
            "Human STARTING intent reservation returned no readback");
    assertThat(reservedIntent.state())
        .isEqualTo(CanonicalInitialAdmissionIntentSnapshot.IntentState.PENDING_HOLD);
    assertThat(reservedIntent.holdIdentity()).isNull();

    try (var worldHoldClient =
        new WorldCanonicalInitialAdmissionHoldClient(
            worldEndpoints, pki.client("game-session-service"), factory, NAMESPACE)) {
      worldHoldClient.init();
      var identity =
          acquireCanonicalHoldAheadOfTermination(
              world,
              association,
              () -> worldHoldClient.acquire(holdRequest, lifecycleRequest),
              holdRequest);
      assertThat(identity.request().canonicalRequestBytes())
          .containsExactly(holdRequest.canonicalRequestBytes());
      assertThat(worldHoldClient.acquire(holdRequest, lifecycleRequest).canonicalBytes())
          .containsExactly(identity.canonicalBytes());
      assertThat(worldHoldClient.readIdentity(holdRequest).canonicalBytes())
          .containsExactly(identity.canonicalBytes());

      var attachedIntent =
          Objects.requireNonNull(
              gs.write()
                  .execute(ignored -> intentRepository.attachHumanStarting(holdRequest, identity)),
              "Human STARTING intent attachment returned no readback");
      assertThat(attachedIntent.holdIdentity().canonicalBytes())
          .containsExactly(identity.canonicalBytes());
      var replayedIntent =
          Objects.requireNonNull(
              gs.write()
                  .execute(
                      ignored ->
                          intentRepository.reserveHumanStarting(holdRequest, lifecycleRequest)),
              "Exact Human STARTING intent retry returned no readback");
      assertThat(replayedIntent.holdIdentity().canonicalBytes())
          .containsExactly(identity.canonicalBytes());
      assertThat(
              gs.write()
                  .execute(ignored -> intentRepository.attachHumanStarting(holdRequest, identity))
                  .holdIdentity()
                  .canonicalBytes())
          .containsExactly(identity.canonicalBytes());
      var restartedIntentRepository =
          new CanonicalInitialAdmissionIntentRepository(
              gs.dsl(),
              new GameSessionCanonicalRealmCatalogRepository(gs.dsl()),
              new CanonicalGameInstanceLaunchAssociationRepository(gs.dsl(), NAMESPACE));
      var restartedIntent =
          restartedIntentRepository
              .read(NAMESPACE, holdRequest.initialAdmissionRequestId())
              .orElseThrow();
      assertThat(restartedIntent.holdRequest().canonicalRequestBytes())
          .containsExactly(holdRequest.canonicalRequestBytes());
      assertThat(restartedIntent.lifecycleRequest().canonicalBytes())
          .containsExactly(lifecycleRequest.canonicalBytes());
      assertThat(restartedIntent.holdIdentity().canonicalBytes())
          .containsExactly(identity.canonicalBytes());

      var conflictingRequest =
          canonicalHoldRequest(
              association,
              active.lifecycleEpoch(),
              "coupled-conflicting-admission-" + UUID.randomUUID());
      assertThatThrownBy(() -> worldHoldClient.acquire(conflictingRequest, lifecycleRequest))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                      .isEqualTo(Status.Code.ALREADY_EXISTS));
      var mismatchedRequest = withChangedDigest(holdRequest);
      assertThatThrownBy(() -> worldHoldClient.acquire(mismatchedRequest, lifecycleRequest))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));

      var protectionRequest =
          AccountStartSessionAdmissionProtectionRequest.create(
              originalProtectionRequest.originalPostAuthorizationTupleBytes(),
              originalProtectionRequest.accountRedemptionProjectionBytes(),
              originalProtectionRequest.gameSessionOwnerMutationId(),
              originalProtectionRequest.gameSessionOwnerAttemptId(),
              originalProtectionRequest.gameSessionOwnerFence(),
              originalProtectionRequest.originalLeaseExpiresAt(),
              originalProtectionRequest.accountWorldParticipationId(),
              originalProtectionRequest.accountWorldParticipationFence(),
              identity);
      assertThat(protectionRequest.worldAdmissionHoldIdentity().canonicalBytes())
          .containsExactly(identity.canonicalBytes());
      assertThat(protectionRequest.originalLeaseExpiresAt())
          .isEqualTo(originalProtectionRequest.originalLeaseExpiresAt());
      var gameSessionPeer =
          GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service")
              .orElseThrow();
      var accountAcquisition = issuer.admissionProtectionAcquisitionService(currentAttempt);
      var admissionTransportPki =
          LoopbackMtlsTestPki.create(
              Files.createDirectories(proofDirectory.resolve("admission-acquisition-pki")),
              "Account admission acquisition composition test CA",
              new IdentitySpec(
                  "account-admission-server",
                  "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service",
                  true),
              new IdentitySpec(
                  "game-session-admission-client",
                  "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service",
                  false));
      Server accountAcquisitionServer =
          AccountStartSessionAdmissionProtectionManualFactory.createUnregisteredServer(
              "127.0.0.1:0",
              admissionTransportPki.keyManagerFactory(
                  admissionTransportPki.identity("account-admission-server")),
              admissionTransportPki.trustManagerFactory(),
              accountAcquisition,
              NAMESPACE);
      AccountStartSessionAdmissionProtectionEvidence accountProtection;
      AccountStartSessionAdmissionProtectionEvidence accountProtectionReplay;
      try {
        accountAcquisitionServer.start();
        var accountAcquisitionEndpoints = new ServiceEndpointsProperties();
        accountAcquisitionEndpoints.setAccountService(
            "127.0.0.1:" + accountAcquisitionServer.getPort());
        try (var accountAcquisitionClient =
            new GrpcAccountStartSessionAdmissionProtectionClient(
                accountAcquisitionEndpoints,
                admissionTransportPki.clientProperties(
                    proofDirectory,
                    admissionTransportPki.identity("game-session-admission-client")),
                factory,
                NAMESPACE)) {
          accountAcquisitionClient.init();
          accountProtection = accountAcquisitionClient.acquire(protectionRequest);
          accountProtectionReplay = accountAcquisitionClient.acquire(protectionRequest);
        }
      } finally {
        accountAcquisitionServer.shutdownNow();
        if (!accountAcquisitionServer.awaitTermination(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException(
              "Account admission-protection loopback server did not terminate");
        }
      }
      assertThat(accountProtectionReplay.canonicalBytes())
          .containsExactly(accountProtection.canonicalBytes());
      assertThat(accountProtection.request().originalPostAuthorizationTupleBytes())
          .containsExactly(originalTuple.canonicalBytes());
      assertThat(accountProtection.request().gameSessionOwnerAttemptId())
          .isEqualTo(originalOwner.ownerAttemptId());
      assertThat(accountProtection.request().gameSessionOwnerMutationId())
          .isEqualTo(originalOwner.ownerMutationId());
      assertThat(accountProtection.request().gameSessionOwnerFence())
          .isEqualTo(originalOwner.ownerFence());
      assertThat(accountProtection.request().originalLeaseExpiresAt())
          .isEqualTo(originalProtectionRequest.originalLeaseExpiresAt());

      assertThat(authenticatedWorldTerminal.outcome())
          .isEqualTo(
              net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal.Outcome
                  .COMMITTED);
      var participationSettlement =
          issuer
              .sourceDsl()
              .fetchSingle(
                  "SELECT outcome,terminal_bytes "
                      + "FROM account_start_session_world_participation_settlements "
                      + "WHERE participation_id=?",
                  accountProtection.request().accountWorldParticipationId());
      assertThat(participationSettlement.get("outcome", String.class)).isEqualTo("COMMITTED");
      assertThat(participationSettlement.get("terminal_bytes", byte[].class))
          .containsExactly(authenticatedWorldTerminal.canonicalBytes());
      assertThat(
              issuer
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT account_start_session_world_participation_is_settled(?)",
                      accountProtection.request().accountWorldParticipationId())
                  .get(0, Boolean.class))
          .isTrue();

      var accountSources =
          accountProtection.sourceEvidenceVector().stream()
              .filter(source -> source.kind().name().equals("ACCOUNT"))
              .filter(
                  source ->
                      source
                          .scopeId()
                          .equals(
                              originalTuple.preAuthorizationTuple().actor().accountId().toString()))
              .toList();
      assertThat(accountSources).hasSize(1);
      var accountSource = accountSources.get(0);
      String accountSourceKey = "ACCOUNT:" + accountSource.scopeId();
      UUID accountUuid = UUID.fromString(accountSource.scopeId());
      assertThat(
              issuer
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT count(*) FROM account_start_session_admission_protection_sources "
                          + "WHERE protection_id=? AND source_key=?",
                      accountProtection.accountProtectionId(),
                      accountSourceKey)
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(
              issuer
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT count(*) FROM account_start_session_activation_protections protection "
                          + "JOIN account_start_session_activation_protection_sources source "
                          + "ON source.activation_protection_id=protection.activation_protection_id "
                          + "LEFT JOIN account_start_session_activation_protection_settlements settlement "
                          + "ON settlement.activation_protection_id=protection.activation_protection_id "
                          + "WHERE source.source_key=? "
                          + "AND settlement.activation_protection_id IS NULL",
                      accountSourceKey)
                  .get(0, Long.class))
          .isZero();
      assertThat(
              issuer
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT count(*) FROM account_start_session_admission_protection_settlements "
                          + "WHERE protection_id=?",
                      accountProtection.accountProtectionId())
                  .get(0, Long.class))
          .isZero();
      String accountPasswordBeforeSourceWrite =
          issuer
              .sourceDsl()
              .fetchSingle("SELECT password_hash FROM accounts WHERE account_uuid=?", accountUuid)
              .get("password_hash", String.class);
      String accountRowBeforeSourceWrite = accountRowSnapshot(issuer.sourceDsl(), accountUuid);
      assertAccountSourceWriteBlockedWhileAdmissionPending(
          issuer.sourceDsl(), accountUuid, accountPasswordBeforeSourceWrite);
      String accountRowAfterSourceWrite = accountRowSnapshot(issuer.sourceDsl(), accountUuid);
      assertThat(accountRowAfterSourceWrite).isEqualTo(accountRowBeforeSourceWrite);
      assertThat(
              issuer
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT count(*) FROM account_start_session_admission_protection_settlements "
                          + "WHERE protection_id=?",
                      accountProtection.accountProtectionId())
                  .get(0, Long.class))
          .isZero();

      var request = canonicalAdmissionRequest(identity);
      gs.write()
          .executeWithoutResult(
              ignored ->
                  admissionOwner.reserveProtectedStartingForRuntimeCompletion(
                      request, accountProtection));
      var pending =
          admissionOwner.read(NAMESPACE, request.initialAdmissionRequestId()).orElseThrow();
      assertThat(pending.outcome()).isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
      assertThat(pending.holdId()).isEqualTo(identity.holdId());
      assertThat(pending.holdFence()).isEqualTo(identity.holdFence());
      var worldVerifier =
          new AuthenticatedCanonicalInitialAdmissionWorldVerifier(
              worldHoldClient, intentRepository, NAMESPACE);

      if (abortCanonicalInitialAdmission) {
        var pendingWorldProof = worldVerifier.verify(request);
        String abortReason = "controlled protected Human admission abort";
        gs.write()
            .executeWithoutResult(
                ignored -> admissionOwner.abortProtected(request, accountProtection, abortReason));
        var aborted =
            admissionOwner.read(NAMESPACE, request.initialAdmissionRequestId()).orElseThrow();
        assertThat(aborted.outcome())
            .isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
        assertThat(aborted.provesAbort()).isTrue();
        assertThat(aborted.provesCommit()).isFalse();
        assertThat(aborted.holdId()).isEqualTo(identity.holdId());
        assertThat(aborted.holdFence()).isEqualTo(identity.holdFence());
        assertThatThrownBy(
                () ->
                    gs.write()
                        .executeWithoutResult(
                            ignored ->
                                admissionOwner.commitProtected(
                                    request, accountProtection, pendingWorldProof)))
            .isInstanceOf(
                CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException
                    .class)
            .hasMessageContaining("A definitively ABORTED initial admission cannot later commit");
        assertThat(
                admissionOwner.read(NAMESPACE, request.initialAdmissionRequestId()).orElseThrow())
            .isEqualTo(aborted);
        assertThat(
                gs.dsl()
                    .fetchSingle(
                        "SELECT status FROM game_instances WHERE game_instance_uuid=?",
                        request.canonicalGameInstanceId())
                    .get("status", String.class))
            .isEqualTo("STARTING");
        assertNoCanonicalAdmissionPointerArtifacts(gs.dsl(), request.initialAdmissionRequestId());

        var abortedWorldTerminal =
            settleAbortedHoldWhileCanonicalSnapshotIsOpen(
                world,
                associationRepository,
                lifecycleRequest,
                identity,
                () ->
                    settleAfterAuthenticatedClientClose(
                        world,
                        associationRepository,
                        lifecycleRepository,
                        gsEndpoints,
                        pki,
                        factory,
                        identity));
        assertThat(abortedWorldTerminal.outcome())
            .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
        assertThat(abortedWorldTerminal.positiveDurableAbort()).isTrue();
        assertThat(abortedWorldTerminal.committedPointerVersion()).isNull();
        assertThat(abortedWorldTerminal.auditEventId()).isNull();
        assertAbortedHoldStored(world.dsl(), identity, abortedWorldTerminal);
        assertThat(worldHoldClient.readState(identity, lifecycleRequest).holdStatus().name())
            .isEqualTo("ABORTED");

        assertAccountSourceWriteBlockedWhileAdmissionPending(
            issuer.sourceDsl(), accountUuid, accountPasswordBeforeSourceWrite);
        String accountRowAfterAbortedOwnerBeforeSettlement =
            accountRowSnapshot(issuer.sourceDsl(), accountUuid);
        assertThat(accountRowAfterAbortedOwnerBeforeSettlement)
            .isEqualTo(accountRowBeforeSourceWrite);

        assertAccountAdmissionProtectionSettled(
            issuer.sourceDsl(),
            gsEndpoints,
            pki,
            factory,
            gameSessionPeer,
            accountProtection,
            abortedWorldTerminal,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);

        var releasedAccount =
            Objects.requireNonNull(
                new TransactionTemplate(accountManager(issuer.sourceDsl()))
                    .execute(
                        ignored ->
                            updateAccountPasswordThroughOwner(
                                issuer.sourceDsl(),
                                accountUuid,
                                accountPasswordBeforeSourceWrite + "-aborted")),
                "Settled Account owner update readback required");
        assertThat(releasedAccount.getAccountUuid()).isEqualTo(accountUuid);
        assertThat(
                issuer
                    .sourceDsl()
                    .fetchSingle(
                        "SELECT password_hash FROM accounts WHERE account_uuid=?", accountUuid)
                    .get("password_hash", String.class))
            .isEqualTo(accountPasswordBeforeSourceWrite + "-aborted");
        return;
      }

      var worldProof = worldVerifier.verify(request);
      worldProof.requireMatches(request);
      var pendingWorldState = worldHoldClient.readState(identity, lifecycleRequest);
      assertThat(pendingWorldState.holdStatus().name()).isEqualTo("PENDING");
      assertThat(pendingWorldState.isPendingAtExpectedActiveEpoch()).isTrue();

      var completionRepository = new CanonicalHumanRuntimeCompletionRepository(gs.dsl(), NAMESPACE);
      var beforeCompletion =
          gs.dsl()
              .fetchSingle(
                  "SELECT status,row_version FROM game_instances WHERE game_instance_uuid=?",
                  request.canonicalGameInstanceId());
      assertThat(beforeCompletion.get("status", String.class)).isEqualTo("STARTING");
      assertNoCanonicalAdmissionPointerArtifacts(gs.dsl(), request.initialAdmissionRequestId());
      var completion =
          Objects.requireNonNull(
              gs.write()
                  .execute(
                      ignored ->
                          completionRepository.completeProtected(
                              request, accountProtection, worldProof)),
              "Protected Human runtime completion returned no receipt");
      var completionReplay =
          Objects.requireNonNull(
              gs.write()
                  .execute(
                      ignored ->
                          completionRepository.completeProtected(
                              request, accountProtection, worldProof)),
              "Exact Human runtime completion retry returned no receipt");
      assertThat(completionReplay).isEqualTo(completion);
      var running =
          gs.dsl()
              .fetchSingle(
                  "SELECT status,row_version FROM game_instances WHERE game_instance_uuid=?",
                  request.canonicalGameInstanceId());
      assertThat(running.get("status", String.class)).isEqualTo("RUNNING");
      assertThat(running.get("row_version", Long.class))
          .isEqualTo(beforeCompletion.get("row_version", Long.class) + 1L);
      assertThat(admissionOwner.read(NAMESPACE, request.initialAdmissionRequestId()).orElseThrow())
          .isEqualTo(pending);
      assertNoCanonicalAdmissionPointerArtifacts(gs.dsl(), request.initialAdmissionRequestId());
      assertThat(
              issuer
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT count(*) FROM account_start_session_admission_protection_settlements "
                          + "WHERE protection_id=?",
                      accountProtection.accountProtectionId())
                  .get(0, Long.class))
          .isZero();
      var retainedProtection =
          issuer
              .sourceDsl()
              .fetchSingle(
                  "SELECT protection_fence,request_binding_bytes,"
                      + "world_admission_hold_identity_bytes "
                      + "FROM account_start_session_admission_protections "
                      + "WHERE protection_id=?",
                  accountProtection.accountProtectionId());
      assertThat(retainedProtection.get("protection_fence", Long.class))
          .isEqualTo(accountProtection.accountProtectionFence());
      assertThat(retainedProtection.get("request_binding_bytes", byte[].class))
          .containsExactly(accountProtection.request().canonicalBytes());
      assertThat(retainedProtection.get("world_admission_hold_identity_bytes", byte[].class))
          .containsExactly(identity.canonicalBytes());
      assertThat(worldHoldClient.readState(identity, lifecycleRequest).holdStatus().name())
          .isEqualTo("PENDING");

      var resumedCoordinator =
          new ProtectedCanonicalInitialAdmissionCoordinator(
              admissionOwner,
              completionRepository,
              new AccountInitialAdmissionProtectionBindingRepository(gs.dsl()),
              ignored -> {
                throw new AssertionError(
                    "Interrupted-after-RUNNING resume must retain original Account protection");
              },
              worldVerifier,
              gs.manager(),
              NAMESPACE);
      var coordinatedCommit = resumedCoordinator.bind(request, protectionRequest);
      assertThat(coordinatedCommit.provesCommit()).isTrue();
      assertThat(resumedCoordinator.bind(request, protectionRequest)).isEqualTo(coordinatedCommit);
      var pointer =
          gs.dsl()
              .fetchSingle(
                  "SELECT pointer_version,initial_admission_request_digest,"
                      + "initial_admission_hold_id,initial_admission_hold_fence,"
                      + "initial_admission_hold_binding_digest,canonical_game_instance_id "
                      + "FROM gameplay_admission_pointer WHERE initial_admission_request_id=?",
                  request.initialAdmissionRequestId());
      assertThat(pointer.get("pointer_version", Long.class)).isEqualTo(1L);
      assertThat(pointer.get("initial_admission_request_digest", String.class))
          .isEqualTo(request.requestDigest());
      assertThat(pointer.get("initial_admission_hold_id", UUID.class)).isEqualTo(identity.holdId());
      assertThat(pointer.get("initial_admission_hold_fence", UUID.class))
          .isEqualTo(identity.holdFence());
      assertThat(pointer.get("initial_admission_hold_binding_digest", String.class))
          .isEqualTo(identity.holdBindingDigest());
      assertThat(pointer.get("canonical_game_instance_id", UUID.class))
          .isEqualTo(request.canonicalGameInstanceId());
      var terminal =
          admissionOwner.read(NAMESPACE, request.initialAdmissionRequestId()).orElseThrow();
      assertThat(terminal.provesCommit()).isTrue();
      assertThat(terminal.committedPointerVersion()).isEqualTo(1L);
      assertThat(terminal.auditEventId()).isNotNull();
      assertThat(
              gs.dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM gameplay_admission_pointer_event "
                          + "WHERE id=? AND initial_admission_request_id=?",
                      terminal.auditEventId(),
                      request.initialAdmissionRequestId())
                  .get(0, Long.class))
          .isEqualTo(1L);

      GameSessionCanonicalInitialAdmissionOwnerProof worldTerminal =
          settleAfterAuthenticatedClientClose(
              world,
              associationRepository,
              lifecycleRepository,
              gsEndpoints,
              pki,
              factory,
              identity);
      assertThat(worldTerminal.outcome())
          .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
      assertThat(worldTerminal.committedPointerVersion())
          .isEqualTo(terminal.committedPointerVersion());
      assertThat(worldTerminal.auditEventId()).isEqualTo(terminal.auditEventId());
      assertThat(worldTerminal.proofDigest()).isEqualTo(terminal.proofDigest());
      assertCommittedHoldStored(world.dsl(), identity, worldTerminal);
      assertThat(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                  worldHoldOwner.settleFromAuthenticatedOwnerRead(identity)))
          .containsExactly(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(worldTerminal));

      assertAccountAdmissionProtectionSettled(
          issuer.sourceDsl(),
          gsEndpoints,
          pki,
          factory,
          gameSessionPeer,
          accountProtection,
          worldTerminal,
          GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);

      String settledAccountPassword = accountPasswordBeforeSourceWrite + "-settled";
      var releasedAccount =
          Objects.requireNonNull(
              new TransactionTemplate(accountManager(issuer.sourceDsl()))
                  .execute(
                      ignored ->
                          updateAccountPasswordThroughOwner(
                              issuer.sourceDsl(), accountUuid, settledAccountPassword)),
              "Settled Account owner update readback required");
      assertThat(releasedAccount.getAccountUuid()).isEqualTo(accountUuid);
      String accountPasswordAfterSettlement =
          issuer
              .sourceDsl()
              .fetchSingle("SELECT password_hash FROM accounts WHERE account_uuid=?", accountUuid)
              .get("password_hash", String.class);
      assertThat(accountPasswordAfterSettlement).isEqualTo(settledAccountPassword);
      assertThat(accountPasswordAfterSettlement).isNotEqualTo(accountPasswordBeforeSourceWrite);
      String accountRowAfterSettlement = accountRowSnapshot(issuer.sourceDsl(), accountUuid);
      assertThat(accountRowAfterSettlement).isNotEqualTo(accountRowBeforeSourceWrite);

      var finalWorldState = worldHoldOwner.readState(identity, lifecycleRequest).orElseThrow();
      assertThat(finalWorldState.holdStatus().name()).isEqualTo("COMMITTED");
      if (createdPublication != null) {
        composeCreatedWorldPlacement(
            gs,
            world,
            proofDirectory,
            pki,
            factory,
            gsEndpoints,
            association,
            identity,
            accountUuid,
            createdPublication);
        return;
      }
      composeActualEntityWorldRegionPrerequisite(
          gs,
          world,
          account,
          proofDirectory,
          pki,
          factory,
          gsEndpoints,
          association,
          identity,
          request,
          accountUuid);
    }
  }

  private static void assertAccountAdmissionProtectionSettled(
      DSLContext accountSql,
      ServiceEndpointsProperties gsEndpoints,
      WorldOriginalStartSessionPreparationProofPki pki,
      GrpcChannelFactory factory,
      GrpcPeerIdentity gameSessionPeer,
      AccountStartSessionAdmissionProtectionEvidence accountProtection,
      GameSessionCanonicalInitialAdmissionOwnerProof expectedTerminal,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome expectedOutcome)
      throws Exception {
    try (var accountTerminalRead =
        new GrpcAccountInitialAdmissionSettlementReadClient(
            gsEndpoints, pki.client("account-service"), factory, NAMESPACE)) {
      accountTerminalRead.init();
      var accountOwnerRead =
          accountTerminalRead.read(
              new AccountInitialAdmissionSettlementReadInput(UUID.randomUUID(), accountProtection));
      assertThat(accountOwnerRead.terminal()).isEqualTo(expectedTerminal);
      assertThat(accountOwnerRead.terminal().outcome()).isEqualTo(expectedOutcome);
      var accountSettlementOwner =
          new AccountStartSessionAdmissionProtectionSettlementService(
              new AccountStartSessionAdmissionProtectionRepository(accountSql),
              accountTerminalRead,
              accountManager(accountSql),
              NAMESPACE);
      var accountSettlement =
          Context.current()
              .withValue(GrpcPeerIdentity.CONTEXT_KEY, gameSessionPeer)
              .call(
                  () ->
                      accountSettlementOwner.settle(
                          accountProtection.accountProtectionId(),
                          accountProtection.accountProtectionFence()));
      assertThat(accountSettlement.protection().canonicalBytes())
          .containsExactly(accountProtection.canonicalBytes());
      assertThat(accountSettlement.terminal()).isEqualTo(expectedTerminal);
      var accountSettlementReplay =
          Context.current()
              .withValue(GrpcPeerIdentity.CONTEXT_KEY, gameSessionPeer)
              .call(
                  () ->
                      accountSettlementOwner.settle(
                          accountProtection.accountProtectionId(),
                          accountProtection.accountProtectionFence()));
      assertThat(accountSettlementReplay.canonicalBytes())
          .containsExactly(accountSettlement.canonicalBytes());
      var storedSettlement =
          accountSql.fetchSingle(
              "SELECT outcome,terminal_bytes,terminal_digest "
                  + "FROM account_start_session_admission_protection_settlements "
                  + "WHERE protection_id=?",
              accountProtection.accountProtectionId());
      assertThat(storedSettlement.get("outcome", String.class)).isEqualTo(expectedOutcome.name());
      assertThat(storedSettlement.get("terminal_bytes", byte[].class))
          .containsExactly(accountSettlement.canonicalBytes());
      assertThat(storedSettlement.get("terminal_digest", String.class))
          .isEqualTo(accountSettlement.digest());
    }
  }

  /**
   * Extends the retained 2973 COMMITTED hold through actual Entity assignment, World placement,
   * authenticated current World reads, and the selected Game Session first-NEW region owners.
   * Account staging and published-policy evidence plus the one-use run capability remain controlled
   * local test inputs; the selected OPEN route and terminal are read from actual Game Session SQL.
   */
  private static void composeActualEntityWorldRegionPrerequisite(
      Database gs,
      Database world,
      AccountControlUiOriginalOrderFixture account,
      Path proofDirectory,
      WorldOriginalStartSessionPreparationProofPki worldPki,
      GrpcChannelFactory channels,
      ServiceEndpointsProperties gsEndpoints,
      WorldCanonicalInstanceAssociation association,
      WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity,
      CanonicalInitialAdmissionRequest admissionRequest,
      UUID accountUuid)
      throws Exception {
    var catalogs = new GameSessionCanonicalRealmCatalogRepository(gs.dsl());
    var admissionOwner =
        new CanonicalInitialAdmissionRepository(
            gs.dsl(),
            catalogs,
            new GameSessionCanonicalAdmissionPointerRepository(
                gs.dsl(), catalogs, new GameplayAdmissionPointerEventRepository(gs.dsl())));
    var launchOwner = new CanonicalGameInstanceLaunchAssociationRepository(gs.dsl(), NAMESPACE);
    var routeReader =
        new CanonicalPlayerRouteReadService(NAMESPACE, catalogs, admissionOwner, launchOwner);
    CanonicalPlayableTarget currentTarget =
        routeReader.readCurrentTarget(association.identity().canonicalTenantId());
    assertThat(currentTarget.ownerProofOutcome())
        .isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    assertThat(currentTarget.pointerVersion()).isEqualTo(1L);
    assertThat(currentTarget.holdId()).isEqualTo(holdIdentity.holdId());
    assertThat(currentTarget.holdFence()).isEqualTo(holdIdentity.holdFence());
    assertThat(currentTarget.initialAdmissionRequestId())
        .isEqualTo(admissionRequest.initialAdmissionRequestId());
    assertThat(currentTarget.initialAdmissionRequestDigest())
        .isEqualTo(admissionRequest.requestDigest());
    assertThat(currentTarget.activeWorldEpoch())
        .isEqualTo(holdIdentity.request().activeLifecycleEpoch());
    assertThat(currentTarget.canonicalGameInstanceId())
        .isEqualTo(association.identity().canonicalGameInstanceId());

    UUID assignmentUuid = UUID.randomUUID();
    String displayName = "Region Owner Player";
    String frozenPolicyDigest = "d".repeat(64);
    String releaseBundleRef = association.worldPrepareFields().publishedReleaseBundleRef();
    var assignmentTarget =
        new PreseededActorAssignmentExpectedTarget(
            currentTarget.canonicalTenantId(),
            currentTarget.realmId(),
            currentTarget.worldSlug(),
            currentTarget.realmSlug(),
            currentTarget.canonicalGameInstanceId().toString(),
            currentTarget.catalogRevision(),
            currentTarget.canonicalVersionId(),
            frozenPolicyDigest,
            currentTarget.playableStateNamespaceId(),
            releaseBundleRef,
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED);
    var assignmentRequest =
        new PreseededActorAssignmentRequest(
            assignmentUuid,
            accountUuid,
            new PreseededActorCorePayload(ActorKind.PLAYER, displayName),
            assignmentTarget);

    var entityPkiDirectory =
        Files.createDirectories(proofDirectory.resolve("entity-region-owner-pki"));
    var entityPki =
        LoopbackMtlsTestPki.create(
            entityPkiDirectory,
            "Controlled Entity region-owner composition test CA",
            new IdentitySpec(
                "entity-server",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/entity-management-service",
                true),
            new IdentitySpec(
                "game-session-client",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service",
                false),
            new IdentitySpec(
                "world-management-client",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                false));
    Files.setPosixFilePermissions(
        entityPkiDirectory.resolve("test-ca.crt"),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    String trustRootFingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(entityPki.caCertificate().getEncoded()));
    Path capabilityDirectory =
        Files.createDirectories(proofDirectory.resolve("entity-region-capability"));
    Files.setPosixFilePermissions(
        capabilityDirectory,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    Path capability = capabilityDirectory.resolve("assignment-grant.json");
    writeRegionAssignmentCapability(
        capability,
        assignmentRequest,
        "region-owner-composition",
        "firemud-region-owner-composition",
        trustRootFingerprint);

    var managers = Mockito.mock(SslManagerBundle.class);
    when(managers.getTrustManagers())
        .thenReturn(entityPki.trustManagerFactory().getTrustManagers());
    var activeBundle = Mockito.mock(SslBundle.class);
    when(activeBundle.getManagers()).thenReturn(managers);
    var activeBundles = Mockito.mock(SslBundles.class);
    when(activeBundles.getBundle("firemud-grpc")).thenReturn(activeBundle);
    var entityConfiguration = new PreseededActorAssignmentConfiguration();
    var assignmentAuthority =
        entityConfiguration.runOwnedPreseededAssignmentAuthorityFromCapability(
            capability.toString(),
            "region-owner-composition",
            "firemud-region-owner-composition",
            NAMESPACE,
            entityPkiDirectory.resolve("test-ca.crt").toString(),
            "firemud-grpc",
            activeBundles);

    var accountIdentity =
        new RuntimeAccountIdentityEvidence(
            1, NAMESPACE, assignmentUuid, accountUuid, 23L, "ACCOUNT_REPOSITORY_INSERT");
    var staging =
        AccountActorStagingEligibilityEvidence.seal(
            NAMESPACE,
            assignmentUuid,
            accountUuid,
            currentTarget.canonicalTenantId(),
            AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
            java.time.Instant.now(),
            "ACCOUNT_REPOSITORY_INSERT",
            "ACTIVE",
            "ACTIVE",
            true,
            "EXPLICIT_JOIN",
            1L,
            1L,
            "FRESH_GAME_DESIGN",
            UUID.randomUUID(),
            "sha256:" + "a".repeat(64),
            1L,
            UUID.randomUUID(),
            "sha256:" + "b".repeat(64),
            false);
    var currentEntityTarget =
        new PreseededActorAssignmentOwnerEvidence(
            assignmentUuid,
            accountUuid,
            PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
            staging.observedAt(),
            staging.membershipAuthorityGeneration(),
            staging.eligibilityDecisionDigest(),
            currentTarget.canonicalTenantId(),
            currentTarget.realmId(),
            currentTarget.worldSlug(),
            currentTarget.realmSlug(),
            currentTarget.canonicalGameInstanceId().toString(),
            currentTarget.catalogRevision(),
            currentTarget.canonicalVersionId(),
            frozenPolicyDigest,
            currentTarget.playableStateNamespaceId(),
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED,
            PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
            PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
            PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
            staging.authoritySnapshotDigest(),
            currentTarget.ownerProofDigest().substring("sha256:".length()),
            releaseBundleRef);
    PreseededActorAccountIdentityPort identityPort =
        (requestedAccount, requestedAssignment) -> {
          if (!accountUuid.toString().equals(requestedAccount)
              || !assignmentUuid.toString().equals(requestedAssignment)) {
            throw new IllegalStateException("Controlled Account identity selector changed");
          }
          return accountIdentity;
        };
    PreseededActorStagingEligibilityPort stagingPort =
        (requestedAccount, requestedTenant, requestedAssignment) -> {
          if (!accountUuid.toString().equals(requestedAccount)
              || !currentTarget.canonicalTenantId().toString().equals(requestedTenant)
              || !assignmentUuid.toString().equals(requestedAssignment)) {
            throw new IllegalStateException("Controlled Account staging selector changed");
          }
          return staging;
        };
    PreseededActorAssignmentOwnerEvidencePort targetPort =
        (request, observedIdentity, observedStaging) -> {
          if (!assignmentRequest.equals(request)
              || !accountIdentity.equals(observedIdentity)
              || !staging.equals(observedStaging)) {
            throw new IllegalStateException("Controlled Game Session evidence selector changed");
          }
          return currentEntityTarget;
        };

    try (var entityDatabase = database(GS, "entity-management-service", true)) {
      var characterRepository =
          transactionalCharacterRepository(
              new CharacterRepository(entityDatabase.dsl()), entityDatabase.manager());
      var assignmentService =
          entityConfiguration.preseededActorAssignmentService(
              assignmentAuthority, identityPort, stagingPort, targetPort, characterRepository);
      var receiptService =
          entityConfiguration.preseededActorAssignmentReceiptService(characterRepository);
      var assignmentFailure = new AtomicReference<RuntimeException>();
      var observedAssignmentService = Mockito.spy(assignmentService);
      Mockito.doAnswer(
              invocation -> {
                try {
                  return invocation.callRealMethod();
                } catch (RuntimeException failure) {
                  assignmentFailure.set(failure);
                  throw failure;
                }
              })
          .when(observedAssignmentService)
          .assign(Mockito.any(PreseededActorAssignmentRequest.class));
      CanonicalPreseededActorAssignmentGrpcService entityHandler =
          entityConfiguration.canonicalPreseededActorAssignmentGrpcService(
              NAMESPACE, observedAssignmentService, receiptService);
      var entityServerFactory =
          new BoundedGrpcServerFactory(
              "127.0.0.1:0",
              List.of(),
              entityPki.keyManagerFactory(entityPki.identity("entity-server")),
              entityPki.trustManagerFactory(),
              io.grpc.TlsServerCredentials.ClientAuth.REQUIRE,
              GrpcRequestBytePolicy.ordinary(),
              GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
      entityServerFactory.addService(
          ServerInterceptors.intercept(
              entityHandler,
              entityConfiguration.canonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE),
              new GrpcPeerIdentityInterceptor()));
      Server entityServer = entityServerFactory.createServer().start();
      try {
        ManagedChannel assignmentChannel =
            entityChannel(entityPki, "game-session-client", entityServer.getPort());
        try {
          var wireRequest = toWire(assignmentRequest);
          net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse assigned;
          try {
            assigned =
                CanonicalPreseededActorAssignmentServiceGrpc.newBlockingStub(assignmentChannel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .assignPreseededActor(wireRequest);
          } catch (StatusRuntimeException failure) {
            throw new AssertionError(
                "Actual Entity assignment failed before region composition",
                assignmentFailure.get() == null ? failure : assignmentFailure.get());
          }
          assertThat(assigned.getOutcome())
              .isEqualTo(
                  net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentOutcome
                      .PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_ASSIGNED);
          assertThat(assigned.getIntentDigest())
              .isEqualTo(assignmentRequest.mutationIntentDigest());
          var assignmentRetry =
              CanonicalPreseededActorAssignmentServiceGrpc.newBlockingStub(assignmentChannel)
                  .withDeadlineAfter(10, TimeUnit.SECONDS)
                  .assignPreseededActor(wireRequest);
          assertThat(assignmentRetry.toByteArray()).containsExactly(assigned.toByteArray());
          var runtimeReferenceSelector =
              ReadPreseededActorRuntimeReferenceRequest.newBuilder()
                  .setSchemaVersion(1)
                  .setAssignmentUuid(assignmentUuid.toString())
                  .setCanonicalAccountUuid(accountUuid.toString())
                  .setCharacterUuid(assigned.getCharacterUuid())
                  .setIntentDigest(assignmentRequest.mutationIntentDigest())
                  .setExpectedTarget(toWire(assignmentTarget))
                  .build();
          ManagedChannel worldPeerChannel =
              entityChannel(entityPki, "world-management-client", entityServer.getPort());
          try {
            assertThatThrownBy(
                    () ->
                        CanonicalPreseededActorAssignmentServiceGrpc.newBlockingStub(
                                worldPeerChannel)
                            .withDeadlineAfter(10, TimeUnit.SECONDS)
                            .readPreseededActorRuntimeReference(runtimeReferenceSelector))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(
                    failure ->
                        assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));
          } finally {
            shutdown(worldPeerChannel);
          }

          var entityEndpoints = new ServiceEndpointsProperties();
          entityEndpoints.setEntityManagementService("127.0.0.1:" + entityServer.getPort());
          var associationRepository =
              new WorldCanonicalInstanceAssociationRepository(
                  world.dsl(),
                  new WorldCompleteLaunchBindingRepository(world.dsl()),
                  new WorldAuthoredSourceIntakeRepository(world.dsl()),
                  new WorldAuthoredVersionIdentityRepository(world.dsl()));
          var lifecycleRepository =
              new WorldCanonicalInstanceLifecycleReadRepository(
                  world.dsl(), world.manager(), associationRepository);
          var holdRepository =
              new WorldCanonicalInitialAdmissionHoldRepository(
                  world.dsl(), world.manager(), associationRepository, lifecycleRepository);
          var locationRepository =
              new WorldCanonicalInitialPlayerLocationRepository(
                  world.dsl(), world.manager(), lifecycleRepository);
          var entityReceiptClient =
              new EntityPreseededActorAssignmentReceiptClient(
                  entityEndpoints,
                  entityPki.clientProperties(
                      entityPkiDirectory, entityPki.identity("world-management-client")),
                  channels,
                  NAMESPACE);
          var gsOwnerClient =
              new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
                  gsEndpoints, worldPki.client("world-management-service"), channels, NAMESPACE);
          entityReceiptClient.init();
          gsOwnerClient.init();
          var worldLocationOwner =
              new CanonicalInitialPlayerLocationService(
                  NAMESPACE,
                  associationRepository,
                  holdRepository,
                  locationRepository,
                  gsOwnerClient,
                  entityReceiptClient);
          var worldLocationGrpc =
              new CanonicalInitialPlayerLocationGrpcService(NAMESPACE, worldLocationOwner);
          BindableService authenticatedWorldLocationGrpc =
              () ->
                  ServerInterceptors.intercept(
                      worldLocationGrpc,
                      new CanonicalInitialPlayerLocationPeerInterceptor(NAMESPACE));
          Server worldLocationServer =
              server(
                  "world-management-service",
                  worldPki,
                  authenticatedWorldLocationGrpc,
                  new WorldCanonicalInstanceLifecycleReadGrpcService(
                      lifecycleRepository, NAMESPACE));
          try {
            var worldEndpoints = new ServiceEndpointsProperties();
            worldEndpoints.setWorldManagementService("127.0.0.1:" + worldLocationServer.getPort());
            // The earlier generic password-write proof deliberately leaves creator LOGIN
            // currentness unavailable. The later composition uses a separate fresh player.
            var initialLocationClient =
                new CanonicalInitialPlayerLocationClient(
                    worldEndpoints, worldPki.client("game-session-service"), channels, NAMESPACE);
            var lifecycleClient =
                new WorldCanonicalInstanceLifecycleClient(
                    worldEndpoints, worldPki.client("game-session-service"), channels, NAMESPACE);
            var runtimeReferenceClient =
                new CanonicalPreseededActorRuntimeReferenceClient(
                    entityEndpoints,
                    entityPki.clientProperties(
                        entityPkiDirectory, entityPki.identity("game-session-client")),
                    channels,
                    NAMESPACE);
            try (initialLocationClient;
                lifecycleClient;
                runtimeReferenceClient) {
              initialLocationClient.init();
              lifecycleClient.init();
              runtimeReferenceClient.init();
              var placementRequest =
                  toWorldLocationRequest(
                      currentTarget,
                      assignmentRequest,
                      assignmentTarget,
                      assigned.getCharacterUuid(),
                      holdIdentity);
              var placed = initialLocationClient.placeInitialPlayer(placementRequest);
              assertThat(placed.getOutcome())
                  .isEqualTo(
                      net.firedevops.firemud.worldmanagement.v1
                          .CanonicalInitialPlayerLocationOutcome
                          .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED);
              var placementRetry = initialLocationClient.placeInitialPlayer(placementRequest);
              assertThat(placementRetry.toByteArray()).containsExactly(placed.toByteArray());

              var regionResolver =
                  new CanonicalInitialPlayerLocationRegionResolver(
                      initialLocationClient,
                      new CanonicalRegionBootstrapAssignmentResolver(lifecycleClient));
              var worldRegion = regionResolver.resolve(placementRequest);
              assertThat(worldRegion.lifecycleAssignment().lifecycleStatus()).isEqualTo("ACTIVE");
              assertThat(worldRegion.lifecycleAssignment().lifecycleEpoch())
                  .isEqualTo(currentTarget.activeWorldEpoch());
              assertThat(worldRegion.operationalRegionId()).isNotEqualTo(new UUID(0L, 0L));

              var secondBinding =
                  controlledSecondBinding(
                      placementRequest,
                      currentTarget,
                      assignmentTarget,
                      initialLocationClient,
                      lifecycleClient,
                      runtimeReferenceClient,
                      lifecycleClient.read(worldRegion.lifecycleAssignment().request()),
                      worldRegion);
              var regionProofs =
                  CanonicalGameplayRegionOwnerCompositionFixture.installAndBridgeTwoCandidates(
                      gs.dsl(),
                      REDIS.getHost(),
                      REDIS.getMappedPort(6379),
                      placementRequest,
                      currentTarget,
                      secondBinding.worldRegionResolver(),
                      runtimeReferenceClient,
                      secondBinding);
              var regionProof = regionProofs.first();
              var secondRegionProof = regionProofs.second();
              assertThat(regionProof.operationalRegionId())
                  .isEqualTo(worldRegion.operationalRegionId());
              assertThat(regionProof.canonicalRegionId())
                  .isEqualTo(worldRegion.canonicalRegionInstanceId());
              assertThat(regionProof.lifecycleEpoch())
                  .isEqualTo(worldRegion.lifecycleAssignment().lifecycleEpoch());
              assertThat(regionProof.executorFence()).isPositive();
              assertThat(regionProof.bridgeOperationId()).isNotNull();
              assertThat(regionProof.runtimeEntityId()).isPositive();
              assertThat(regionProof.entityProjectionGeneration()).isPositive();
              assertThat(regionProof.entityProjectionFence()).isPositive();
              assertThat(regionProof.entityReferenceDigest()).hasSize(64);
              assertThat(regionProof.worldSourceDigest()).hasSize(64);
              assertThat(regionProof.redisReadbackDigest()).hasSize(64);
              assertThat(regionProof.acknowledgedInventoryRevision()).isPositive();
              assertThat(regionProof.acknowledgedAt()).isNotNull();
              assertThat(regionProof.exactLostReplyRetry()).isTrue();
              assertThat(secondRegionProof).isNotNull();
              assertThat(secondRegionProof.transitionId()).isNotEqualTo(regionProof.transitionId());
              assertThat(secondRegionProof.bridgeOperationId())
                  .isNotEqualTo(regionProof.bridgeOperationId());
              assertThat(secondRegionProof.redisReadbackDigest())
                  .isNotEqualTo(regionProof.redisReadbackDigest());
              assertThat(secondRegionProof.exactLostReplyRetry()).isTrue();
              assertThat(regionProofs.firstAbort().exactAcknowledgementRetained()).isTrue();
              assertThat(regionProofs.firstAbort().acknowledgedRevision())
                  .isEqualTo(regionProof.acknowledgedInventoryRevision());
              assertThat(regionProofs.firstAbort().abortRevision())
                  .isGreaterThan(regionProofs.firstAbort().acknowledgedRevision());
              assertThat(regionProofs.firstAbort().candidateLifecycle())
                  .isEqualTo("TERMINAL_UNRESOLVED");
              assertThat(regionProofs.firstAbort().reservationLifecycle())
                  .isEqualTo("RELEASE_PENDING");
              assertThat(regionProofs.firstTimelineUnchanged()).isTrue();
              assertThat(regionProofs.initialMetaUnchanged()).isTrue();
              assertThat(regionProofs.regionLeaseStillLive()).isTrue();
              assertThat(regionProofs.foreignOwnerCapabilityRejectedWithoutStateChange()).isTrue();
              assertThat(regionProofs.expiredGrantRejectedWithoutStateChange()).isTrue();
            }
            // Run the genuine second-player DEMO mutation after original placement assertions.
            WorldPublishedAdmissionSourceComposition.exercise(
                proofDirectory,
                gs.dsl(),
                gs.manager(),
                REDIS.getHost(),
                REDIS.getMappedPort(6379),
                currentTarget,
                accountUuid,
                account,
                worldEndpoints,
                worldPki.client("game-session-service"));
          } finally {
            shutdown(worldLocationServer);
            entityReceiptClient.close();
            gsOwnerClient.close();
          }
        } finally {
          shutdown(assignmentChannel);
        }
      } finally {
        shutdown(entityServer);
      }
    }
  }

  /**
   * Reuses the actual guarded ACTIVE, committed hold and authenticated GS terminal owners. Only
   * Entity's historical creation response is stipulated on an authenticated loopback socket; this
   * qualifies World v2 storage, not genuine Entity issuance, production PKI or playable admission.
   * The separately composed publication/catalog is PLAYER_CREATED with its own authored descriptor
   * and initial core; the retained PRESEEDED proof runs under its original separate invocation.
   */
  private static void composeCreatedWorldPlacement(
      Database gs,
      Database world,
      Path proofDirectory,
      WorldOriginalStartSessionPreparationProofPki worldPki,
      GrpcChannelFactory channels,
      ServiceEndpointsProperties gsEndpoints,
      WorldCanonicalInstanceAssociation association,
      WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity,
      UUID accountUuid,
      RealmPolicyPublishedEvidence.PublishedSet publication)
      throws Exception {
    var catalogs = new GameSessionCanonicalRealmCatalogRepository(gs.dsl());
    var admissionOwner =
        new CanonicalInitialAdmissionRepository(
            gs.dsl(),
            catalogs,
            new GameSessionCanonicalAdmissionPointerRepository(
                gs.dsl(), catalogs, new GameplayAdmissionPointerEventRepository(gs.dsl())));
    var currentTarget =
        new CanonicalPlayerRouteReadService(
                NAMESPACE,
                catalogs,
                admissionOwner,
                new CanonicalGameInstanceLaunchAssociationRepository(gs.dsl(), NAMESPACE))
            .readCurrentTarget(association.identity().canonicalTenantId());
    assertThat(currentTarget.characterCreationPolicy()).isEqualTo("PLAYER_CREATED");
    assertThat(publication.target().canonicalVersionId())
        .isEqualTo(currentTarget.canonicalVersionId());
    var policy = publication.policies().getFirst().source().policy();
    assertThat(policy.entryPolicy()).isEqualTo(RealmEntryPolicy.EntryPolicy.PLAYER_CREATED);
    assertThat(policy.worldSlug()).isEqualTo(currentTarget.worldSlug());
    assertThat(policy.realmSlug()).isEqualTo(currentTarget.realmSlug());
    assertThat(policy.initialActorCore()).isNotNull();
    var characterId = UUID.randomUUID();
    var placementId = UUID.randomUUID();
    // Stipulated original creation coordinates. No creation action, terminal or protection is
    // manufactured in an Entity owner table, and no genuine issuing-chain proof is claimed.
    var origin =
        PlayerCreatedActorOrigin.newBuilder()
            .setTargetNamespace(NAMESPACE)
            .setCreationOperationUuid(UUID.randomUUID().toString())
            .setMutationDigest("a".repeat(64))
            .setTerminalUuid(UUID.randomUUID().toString())
            .setTerminalSha256("b".repeat(64))
            .build();
    var selector =
        PlayerCreatedActorRuntimeReferenceRequest.newBuilder()
            .setSchemaVersion(1)
            .setOrigin(origin)
            .setTenantUuid(currentTarget.canonicalTenantId().toString())
            .setAccountUuid(accountUuid.toString())
            .setPlayableStateNamespaceUuid(currentTarget.playableStateNamespaceId().toString())
            .setGameInstanceUuid(currentTarget.canonicalGameInstanceId().toString())
            .setCharacterUuid(characterId.toString())
            .setCanonicalVersionUuid(currentTarget.canonicalVersionId().toString())
            .setRealmUuid(currentTarget.realmId().toString())
            .setWorldSlug(currentTarget.worldSlug())
            .setRealmSlug(currentTarget.realmSlug())
            .build();
    var controlledReceipt =
        PlayerCreatedActorRuntimeReferenceResponse.newBuilder()
            .setSchemaVersion(1)
            .setSelector(selector)
            .setRuntimeEntityId(900_003L)
            .setProjectionGeneration(1L)
            .setProjectionFence(1L)
            .setOriginalCatalogRevision(currentTarget.catalogRevision())
            .setOriginalPointerVersion(currentTarget.pointerVersion())
            .setOriginalActiveLifecycleEpoch(currentTarget.activeWorldEpoch())
            .setOriginalDescriptorDigest(policy.creationDescriptor().digest())
            // The authored binding is an explicitly controlled foreign-owner input. Policy and
            // descriptor are taken from this invocation's actual retained publication preimage.
            .setOriginalAuthoredBindingDigest("sha256:" + "c".repeat(64))
            .setOriginalPolicyContentDigest(
                prefixedSha256(policy.canonicalJson().getBytes(StandardCharsets.UTF_8)))
            .build();
    CanonicalPlayerCreatedRuntimeReferenceEvidence.requireResponse(selector, controlledReceipt);
    var reads = new AtomicInteger();
    var controlledEntity =
        new CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
            .CanonicalPlayerCreatedRuntimeReferenceServiceImplBase() {
          @Override
          public void readPlayerCreatedActorPlacementReceipt(
              PlayerCreatedActorRuntimeReferenceRequest request,
              io.grpc.stub.StreamObserver<PlayerCreatedActorRuntimeReferenceResponse> observer) {
            if (!selector.equals(request)) {
              observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
              return;
            }
            reads.incrementAndGet();
            observer.onNext(controlledReceipt);
            observer.onCompleted();
          }
        };
    var entityPkiDirectory = Files.createDirectories(proofDirectory.resolve("created-entity-pki"));
    var entityPki =
        LoopbackMtlsTestPki.create(
            entityPkiDirectory,
            "Controlled created-placement Entity CA",
            new IdentitySpec(
                "entity-server",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/entity-management-service",
                true),
            new IdentitySpec(
                "world-client",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                false));
    var serverFactory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            entityPki.keyManagerFactory(entityPki.identity("entity-server")),
            entityPki.trustManagerFactory(),
            io.grpc.TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    serverFactory.addService(
        ServerInterceptors.intercept(
            controlledEntity,
            new EntityPlayerCreatedRuntimeReferencePeerInterceptor(
                NAMESPACE, "ReadPlayerCreatedActorPlacementReceipt", "world-management-service"),
            new GrpcPeerIdentityInterceptor()));
    Server entityServer = serverFactory.createServer().start();
    try {
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setEntityManagementService("127.0.0.1:" + entityServer.getPort());
      var associationRepository =
          new WorldCanonicalInstanceAssociationRepository(
              world.dsl(),
              new WorldCompleteLaunchBindingRepository(world.dsl()),
              new WorldAuthoredSourceIntakeRepository(world.dsl()),
              new WorldAuthoredVersionIdentityRepository(world.dsl()));
      var lifecycleRepository =
          new WorldCanonicalInstanceLifecycleReadRepository(
              world.dsl(), world.manager(), associationRepository);
      var locationRepository =
          new WorldCanonicalInitialPlayerLocationRepository(
              world.dsl(), world.manager(), lifecycleRepository);
      try (var createdClient =
              new EntityPlayerCreatedActorPlacementReceiptClient(
                  endpoints,
                  entityPki.clientProperties(
                      entityPkiDirectory, entityPki.identity("world-client")),
                  channels,
                  NAMESPACE);
          var gsOwnerClient =
              new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
                  gsEndpoints, worldPki.client("world-management-service"), channels, NAMESPACE)) {
        createdClient.init();
        gsOwnerClient.init();
        var owner =
            new CanonicalInitialPlayerLocationService(
                NAMESPACE,
                associationRepository,
                new WorldCanonicalInitialAdmissionHoldRepository(
                    world.dsl(), world.manager(), associationRepository, lifecycleRepository),
                locationRepository,
                gsOwnerClient,
                ignored -> {
                  throw new AssertionError("Created placement requested an assignment");
                },
                createdClient);
        var grpc = new CanonicalInitialPlayerLocationGrpcService(NAMESPACE, owner);
        BindableService authenticatedGrpc =
            () ->
                ServerInterceptors.intercept(
                    grpc, new CanonicalInitialPlayerLocationPeerInterceptor(NAMESPACE));
        Server worldServer =
            server(
                "world-management-service",
                worldPki,
                authenticatedGrpc,
                new WorldCanonicalInstanceLifecycleReadGrpcService(lifecycleRepository, NAMESPACE));
        try {
          var worldEndpoints = new ServiceEndpointsProperties();
          worldEndpoints.setWorldManagementService("127.0.0.1:" + worldServer.getPort());
          try (var client =
              new CanonicalInitialPlayerLocationClient(
                  worldEndpoints, worldPki.client("game-session-service"), channels, NAMESPACE)) {
            client.init();
            var target =
                new PreseededActorAssignmentExpectedTarget(
                    currentTarget.canonicalTenantId(),
                    currentTarget.realmId(),
                    currentTarget.worldSlug(),
                    currentTarget.realmSlug(),
                    currentTarget.canonicalGameInstanceId().toString(),
                    currentTarget.catalogRevision(),
                    currentTarget.canonicalVersionId(),
                    prefixedSha256(policy.canonicalJson().getBytes(StandardCharsets.UTF_8))
                        .substring("sha256:".length()),
                    currentTarget.playableStateNamespaceId(),
                    association.worldPrepareFields().publishedReleaseBundleRef(),
                    net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                        .PLAYABLE_STATE_SCOPE_SHARED);
            var request =
                CanonicalInitialPlayerLocationRequest.newBuilder()
                    .setSchemaVersion(2)
                    .setPlacementOperationUuid(placementId.toString())
                    .setExpectedTarget(toWire(target))
                    .setCanonicalAccountUuid(accountUuid.toString())
                    .setCharacterUuid(characterId.toString())
                    .setPlayerCreatedOrigin(origin)
                    .setInitialAdmissionHoldUuid(holdIdentity.holdId().toString())
                    .setInitialAdmissionHoldFence(holdIdentity.holdFence().toString())
                    .setInitialAdmissionRequestId(
                        holdIdentity.request().initialAdmissionRequestId())
                    .setInitialAdmissionRequestDigest(
                        holdIdentity.request().initialAdmissionRequestDigest())
                    .setActiveLifecycleEpoch(currentTarget.activeWorldEpoch())
                    .setTerminalProofDigest(currentTarget.ownerProofDigest())
                    .setCommittedPointerVersion(currentTarget.pointerVersion())
                    .setAuditEventId(currentTarget.auditEventId())
                    .build();
            var placed = client.placeInitialPlayer(request);
            assertThat(placed.getSchemaVersion()).isEqualTo(2);
            assertThat(placed.getOutcome())
                .isEqualTo(
                    net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome
                        .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED);
            var operation =
                world
                    .dsl()
                    .fetchSingle(
                        "SELECT * FROM world_canonical_initial_player_location_operation WHERE operation_id=?",
                        placementId);
            var retainedRequest =
                new WorldCanonicalInitialPlayerLocationRepository.Request(
                    placementId,
                    operation.get("request_digest", String.class),
                    operation.get("request_bytes", byte[].class),
                    operation.get("original_lifecycle_evidence_bytes", byte[].class));
            var storedRequest = JSON.readTree(retainedRequest.requestBytes());
            assertThat(storedRequest.get("schema").asText())
                .isEqualTo("world-canonical-initial-player-location-request/v2");
            assertThat(storedRequest.has("entityAssignmentOperationId")).isFalse();
            assertThat(storedRequest.has("entityAssignmentDigest")).isFalse();
            assertThat(
                    CanonicalPlayerCreatedRuntimeReferenceEvidence.originFromStoredJson(
                        storedRequest.get("entityCreatedOrigin").toString()))
                .isEqualTo(origin);
            var retained = locationRepository.read(retainedRequest).orElseThrow();
            assertThat(retained.outcome())
                .isEqualTo(WorldCanonicalInitialPlayerLocationRepository.Outcome.APPLIED);
            assertThat(retained.resultBytes())
                .containsExactly(operation.get("result_bytes", byte[].class));
            assertThat(retained.resultDigest()).isEqualTo(placed.getResultDigest());
            assertThat(retained.requestDigest()).isEqualTo(placed.getRequestDigest());
            var location =
                world
                    .dsl()
                    .fetchSingle(
                        "SELECT * FROM character_location WHERE initial_location_operation_id=?",
                        placementId);
            assertThat(location.get("entity_assignment_operation_id")).isNull();
            assertThat(location.get("entity_assignment_digest")).isNull();
            assertThat(
                    CanonicalPlayerCreatedRuntimeReferenceEvidence.originFromStoredJson(
                        location.get("entity_created_origin", org.jooq.JSONB.class).data()))
                .isEqualTo(origin);
            assertThat(location.get("runtime_room_instance_id", Long.class))
                .isEqualTo(retained.runtimeRoomInstanceId());
            assertThat(location.get("initial_runtime_room_instance_id", Long.class))
                .isEqualTo(retained.runtimeRoomInstanceId());
            var beforeRetry = createdPlacementSnapshot(world.dsl(), placementId);
            assertThat(client.placeInitialPlayer(request).toByteArray())
                .containsExactly(placed.toByteArray());
            assertThat(client.readInitialPlayerLocation(request).toByteArray())
                .containsExactly(placed.toByteArray());
            assertThat(locationRepository.read(retainedRequest)).contains(retained);
            var region = client.readInitialPlayerLocationRegion(request);
            assertThat(region.getPlacementResult().toByteArray())
                .containsExactly(placed.toByteArray());
            assertThat(createdPlacementSnapshot(world.dsl(), placementId)).isEqualTo(beforeRetry);
            assertThat(reads.get()).isEqualTo(4);
          }
        } finally {
          shutdown(worldServer);
        }
      }
    } finally {
      shutdown(entityServer);
    }
  }

  private static List<String> createdPlacementSnapshot(DSLContext dsl, UUID placementId) {
    // Include all retained bytes, room columns and physical row versions, not just row counts.
    return dsl.fetch(
            "SELECT to_jsonb(o)::text || ':' || o.xmin::text AS snapshot "
                + "FROM world_canonical_initial_player_location_operation o WHERE operation_id=? "
                + "UNION ALL SELECT to_jsonb(l)::text || ':' || l.xmin::text AS snapshot "
                + "FROM character_location l WHERE initial_location_operation_id=? ORDER BY snapshot",
            placementId,
            placementId)
        .getValues("snapshot", String.class);
  }

  private static CanonicalInitialPlayerLocationRequest toWorldLocationRequest(
      CanonicalPlayableTarget currentTarget,
      PreseededActorAssignmentRequest assignmentRequest,
      PreseededActorAssignmentExpectedTarget assignmentTarget,
      String characterUuid,
      WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity) {
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(UUID.randomUUID().toString())
        .setExpectedTarget(toWire(assignmentTarget))
        .setCanonicalAccountUuid(assignmentRequest.canonicalAccountUuid().toString())
        .setCharacterUuid(characterUuid)
        .setAssignmentUuid(assignmentRequest.assignmentUuid().toString())
        .setAssignmentIntentDigest(assignmentRequest.mutationIntentDigest())
        .setInitialAdmissionHoldUuid(holdIdentity.holdId().toString())
        .setInitialAdmissionHoldFence(holdIdentity.holdFence().toString())
        .setInitialAdmissionRequestId(holdIdentity.request().initialAdmissionRequestId())
        .setInitialAdmissionRequestDigest(holdIdentity.request().initialAdmissionRequestDigest())
        .setActiveLifecycleEpoch(holdIdentity.request().activeLifecycleEpoch())
        .setTerminalProofDigest(currentTarget.ownerProofDigest())
        .setCommittedPointerVersion(currentTarget.pointerVersion())
        .setAuditEventId(currentTarget.auditEventId())
        .build();
  }

  private static CanonicalGameplayRegionOwnerCompositionFixture.SecondBinding
      controlledSecondBinding(
          CanonicalInitialPlayerLocationRequest firstRequest,
          CanonicalPlayableTarget currentTarget,
          PreseededActorAssignmentExpectedTarget assignmentTarget,
          CanonicalInitialPlayerLocationClient actualInitialLocationClient,
          WorldCanonicalInstanceLifecycleClient actualLifecycleClient,
          CanonicalPreseededActorRuntimeReferenceClient actualEntityReferenceClient,
          WorldCanonicalInstanceLifecycleEvidence firstLifecycleEvidence,
          CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion firstResolvedRegion) {
    UUID placementOperationId = UUID.randomUUID();
    UUID assignmentId = UUID.randomUUID();
    UUID characterId = UUID.randomUUID();
    CanonicalInitialPlayerLocationRequest secondRequest =
        firstRequest.toBuilder()
            .setPlacementOperationUuid(placementOperationId.toString())
            .setAssignmentUuid(assignmentId.toString())
            .setCharacterUuid(characterId.toString())
            .setAssignmentIntentDigest("c".repeat(64))
            .build();
    var originalLifecycleRequest = firstLifecycleEvidence.request();
    var secondLifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            originalLifecycleRequest.schemaVersion(),
            WorldCanonicalInstanceLifecycleEvidence.initialLocationReadRequestId(
                placementOperationId),
            originalLifecycleRequest.targetNamespace(),
            originalLifecycleRequest.canonicalTenantId(),
            originalLifecycleRequest.worldSlug(),
            originalLifecycleRequest.canonicalGameInstanceId(),
            originalLifecycleRequest.playableStateNamespaceId(),
            originalLifecycleRequest.playableStateScope(),
            originalLifecycleRequest.publicProduction(),
            originalLifecycleRequest.controlPlaneRequestId(),
            originalLifecycleRequest.canonicalVersionId(),
            originalLifecycleRequest.expectedDescriptorRequestDigest(),
            originalLifecycleRequest.expectedDescriptorResultDigest(),
            originalLifecycleRequest.expectedReleaseAttestationDigest());
    var secondLifecycleEvidence =
        new WorldCanonicalInstanceLifecycleEvidence(
            secondLifecycleRequest,
            firstLifecycleEvidence.launchBinding(),
            firstLifecycleEvidence.startLocation(),
            firstLifecycleEvidence.runtimeRoomInstanceId(),
            firstLifecycleEvidence.lifecycleStatus(),
            firstLifecycleEvidence.lifecycleEpoch(),
            firstLifecycleEvidence.rowVersion(),
            firstLifecycleEvidence.captureId(),
            firstLifecycleEvidence.graphSha256(),
            firstLifecycleEvidence.preparationInputDigest(),
            firstLifecycleEvidence.operationalRegionAssignments());

    var initialLocationClient = Mockito.mock(CanonicalInitialPlayerLocationClient.class);
    var originalPlacement = firstResolvedRegion.observation().getPlacementResult();
    var secondPlacement =
        originalPlacement.toBuilder()
            .setPlacementOperationUuid(secondRequest.getPlacementOperationUuid())
            .setCanonicalAccountUuid(secondRequest.getCanonicalAccountUuid())
            .setCharacterUuid(secondRequest.getCharacterUuid())
            .setAssignmentUuid(secondRequest.getAssignmentUuid())
            .setAssignmentIntentDigest(secondRequest.getAssignmentIntentDigest())
            .setRequestDigest("sha256:" + "d".repeat(64))
            .setResultDigest("sha256:" + "e".repeat(64))
            .build();
    var secondLifecycle =
        firstResolvedRegion.observation().getLifecycle().toBuilder()
            .setCanonicalRequestBytes(
                com.google.protobuf.ByteString.copyFrom(secondLifecycleRequest.canonicalBytes()))
            .build();
    var secondObservation =
        firstResolvedRegion.observation().toBuilder()
            .setPlacementResult(secondPlacement)
            .setLifecycle(secondLifecycle)
            .build();
    Mockito.doAnswer(
            invocation -> {
              CanonicalInitialPlayerLocationRequest request = invocation.getArgument(0);
              if (secondRequest.equals(request)) return secondObservation;
              return actualInitialLocationClient.readInitialPlayerLocationRegion(request);
            })
        .when(initialLocationClient)
        .readInitialPlayerLocationRegion(Mockito.any(CanonicalInitialPlayerLocationRequest.class));
    var lifecycleClient = Mockito.mock(WorldCanonicalInstanceLifecycleClient.class);
    Mockito.doAnswer(
            invocation -> {
              WorldCanonicalInstanceLifecycleEvidence.Request request = invocation.getArgument(0);
              if (secondLifecycleRequest.equals(request)) return secondLifecycleEvidence;
              return actualLifecycleClient.read(request);
            })
        .when(lifecycleClient)
        .read(Mockito.any(WorldCanonicalInstanceLifecycleEvidence.Request.class));
    var secondWorldRegionResolver =
        new CanonicalInitialPlayerLocationRegionResolver(
            initialLocationClient, new CanonicalRegionBootstrapAssignmentResolver(lifecycleClient));

    var entityReferenceClient = Mockito.mock(CanonicalPreseededActorRuntimeReferenceClient.class);
    var secondEntityReferenceSelector =
        ReadPreseededActorRuntimeReferenceRequest.newBuilder()
            .setSchemaVersion(1)
            .setAssignmentUuid(secondRequest.getAssignmentUuid())
            .setCanonicalAccountUuid(secondRequest.getCanonicalAccountUuid())
            .setExpectedTarget(secondRequest.getExpectedTarget())
            .setCharacterUuid(secondRequest.getCharacterUuid())
            .setIntentDigest(secondRequest.getAssignmentIntentDigest())
            .build();
    var secondEntityReferenceResponse =
        ReadPreseededActorRuntimeReferenceResponse.newBuilder()
            .setSchemaVersion(1)
            .setAssignmentUuid(secondEntityReferenceSelector.getAssignmentUuid())
            .setCanonicalAccountUuid(secondEntityReferenceSelector.getCanonicalAccountUuid())
            .setCharacterUuid(secondEntityReferenceSelector.getCharacterUuid())
            .setIntentDigest(secondEntityReferenceSelector.getIntentDigest())
            .setExpectedTarget(secondEntityReferenceSelector.getExpectedTarget())
            .setPublishedOwnerProofDigest(
                currentTarget.ownerProofDigest().substring("sha256:".length()))
            .setRuntimeEntityId(900_002L)
            .setProjectionGeneration(2L)
            .setProjectionFence(2L)
            .setCorePayload(
                net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
                    .newBuilder()
                    .setActorKind(
                        CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                    .setDisplayName("Controlled Second Region Player"))
            .build();
    // Only the second actor's transport response is controlled. Use the actual reader's validation
    // and closed reference so origin, exact matching and canonical bytes share one immutable
    // receipt.
    var controlledStub =
        Mockito.mock(
            CanonicalPreseededActorAssignmentServiceGrpc
                .CanonicalPreseededActorAssignmentServiceBlockingStub.class,
            Mockito.RETURNS_SELF);
    when(controlledStub.readPreseededActorRuntimeReference(secondEntityReferenceSelector))
        .thenReturn(secondEntityReferenceResponse);
    var controlledReferenceClient = Mockito.spy(actualEntityReferenceClient);
    ReflectionTestUtils.setField(controlledReferenceClient, "stub", controlledStub);
    // The spy borrows the initialized client's channel; its lifecycle stays with the actual client.
    var runtimeReference = controlledReferenceClient.read(secondEntityReferenceSelector);
    Mockito.doAnswer(
            invocation -> {
              ReadPreseededActorRuntimeReferenceRequest request = invocation.getArgument(0);
              if (secondEntityReferenceSelector.equals(request)) {
                return runtimeReference;
              }
              return actualEntityReferenceClient.read(request);
            })
        .when(entityReferenceClient)
        .read(Mockito.any(ReadPreseededActorRuntimeReferenceRequest.class));
    return new CanonicalGameplayRegionOwnerCompositionFixture.SecondBinding(
        secondRequest, currentTarget, secondWorldRegionResolver, entityReferenceClient);
  }

  private static AssignPreseededActorRequest toWire(PreseededActorAssignmentRequest request) {
    var target = request.expectedTarget();
    return AssignPreseededActorRequest.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(request.assignmentUuid().toString())
        .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
        .setExpectedTarget(toWire(target))
        .setCorePayload(
            net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
                .newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName(request.corePayload().displayName())
                .build())
        .setMutationIntentDigest(request.mutationIntentDigest())
        .build();
  }

  private static net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
      toWire(PreseededActorAssignmentExpectedTarget target) {
    return net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
        .newBuilder()
        .setCanonicalTenantUuid(target.canonicalTenantUuid().toString())
        .setRealmUuid(target.realmUuid().toString())
        .setWorldSlug(target.worldSlug())
        .setRealmSlug(target.realmSlug())
        .setGameInstanceId(target.gameInstanceId())
        .setCatalogRevision(target.catalogRevision())
        .setCanonicalVersionUuid(target.canonicalVersionUuid().toString())
        .setFrozenPolicyDigest(target.frozenPolicyDigest())
        .setPlayableStateNamespaceUuid(target.playableStateNamespaceId().toString())
        .setPublishedReleaseBundleRef(target.publishedReleaseBundleRef())
        .setPlayableStateScope(target.playableStateScope())
        .build();
  }

  private static void writeRegionAssignmentCapability(
      Path capability,
      PreseededActorAssignmentRequest request,
      String runId,
      String project,
      String trustRootFingerprint)
      throws Exception {
    var target = request.expectedTarget();
    String body =
        """
        {\
          "schema": "firemud.run-owned-preseeded-actor-assignment.v3",%n\
          "runId": "%s",%n\
          "composeProjectName": "%s",%n\
          "action": "PRESEEDED_ACTOR_ASSIGNMENT",%n\
          "trustedGameSessionUriSan": "spiffe://firemud/ns/%s/sa/game-session-service",%n\
          "trustedGameSessionNamespace": "%s",%n\
          "assignmentUuid": "%s",%n\
          "canonicalAccountUuid": "%s",%n\
          "expectedTarget": {%n\
            "canonicalTenantUuid": "%s",%n\
            "realmUuid": "%s",%n\
            "worldSlug": "%s",%n\
            "realmSlug": "%s",%n\
            "gameInstanceId": "%s",%n\
            "catalogRevision": %d,%n\
            "canonicalVersionUuid": "%s",%n\
            "frozenPolicyDigest": "%s",%n\
            "playableStateNamespaceUuid": "%s",%n\
            "publishedReleaseBundleRef": "%s",%n\
            "playableStateScope": "%s"%n\
          },%n\
          "corePayload": {"actorKind": "PLAYER", "displayName": "%s"},%n\
          "intentDigest": "%s",%n\
          "entityServerTrustRootSha256": "%s"%n\
        }%n\
        """
            .formatted(
                runId,
                project,
                NAMESPACE,
                NAMESPACE,
                request.assignmentUuid(),
                request.canonicalAccountUuid(),
                target.canonicalTenantUuid(),
                target.realmUuid(),
                target.worldSlug(),
                target.realmSlug(),
                target.gameInstanceId(),
                target.catalogRevision(),
                target.canonicalVersionUuid(),
                target.frozenPolicyDigest(),
                target.playableStateNamespaceId(),
                target.publishedReleaseBundleRef(),
                target.playableStateScope().name(),
                request.corePayload().displayName(),
                request.mutationIntentDigest(),
                trustRootFingerprint);
    Files.writeString(capability, body);
    Files.setPosixFilePermissions(
        capability,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
  }

  private static io.grpc.ManagedChannel entityChannel(
      LoopbackMtlsTestPki.Pki pki, String identityAlias, int port) throws Exception {
    var identity = pki.identity(identityAlias);
    return NettyChannelBuilder.forAddress("127.0.0.1", port)
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forClient()
                        .trustManager(pki.caCertificate())
                        .keyManager(identity.privateKey(), identity.certificate()))
                .build())
        .build();
  }

  private record CapturedPreparationSource(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      String publicationId,
      AccountPublicationAuthorizationBinding publicationAccountBinding,
      WorldSelectedPublicationArtifactInventoryEvidence inventoryEvidence,
      WorldCanonicalFrozenTopology captured,
      WorldPublishedStartLocationEvidence selector) {}

  private static CapturedPreparationSource capturePreparationSource(
      Database world, UUID tenant, AuthoredWorldSourceEvidence source) throws Exception {
    var intakeRepository = new WorldAuthoredSourceIntakeRepository(world.dsl());
    var versionRepository = new WorldAuthoredVersionIdentityRepository(world.dsl());
    var intake =
        Objects.requireNonNull(
            world
                .write()
                .execute(s -> intakeRepository.acceptFresh(NAMESPACE, UUID.randomUUID(), source)));
    var versionState =
        AuthoredWorldVersionStateEvidence.create(
            new AuthoredWorldVersionStateEvidence.Request(
                1,
                NAMESPACE,
                UUID.randomUUID(),
                tenant,
                source.worldSlug(),
                source.operationId(),
                source.evidenceDigest(),
                42L),
            source,
            UUID.randomUUID(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            1L);
    var version =
        Objects.requireNonNull(
            world.write().execute(s -> versionRepository.acceptFresh(intake, versionState)));
    var owner = new OwnerFixture(source, intake, version);
    var fence = new WorldDesignPublicationFenceRepository(world.dsl(), intakeRepository);
    var topology = new WorldDraftTopologyCommitRepository(world.dsl(), fence, JSON);
    var applications = new WorldDraftGraphApplicationRepository(world.dsl(), fence, JSON);
    var plan = topologyPlan(owner);
    // The earlier Draft/publication issuer is controlled, not this proof's Account StartSession
    // authority. World graph application, qualified first freeze, terminal, and selector readback
    // below use their actual current owners.
    var application = application(plan);
    var applied =
        new WorldDraftGraphApplicationService(
                applications,
                world.manager(),
                operation -> new WorldDraftGraphApplicationService.CommitOrderProof(operation))
            .apply(application);
    assertThat(applications.readCommitted(application).orElseThrow().canonicalBytes())
        .containsExactly(applied.canonicalBytes());
    assertThat(topology.readCommitted(plan).orElseThrow().graphBytes())
        .containsExactly(applied.graphBytes());
    String publicationId = "controlled-publication-" + UUID.randomUUID();
    var selected = selection(plan.binding(), applied.canonicalBytes(), publicationId);
    var originalPublicationBinding =
        DraftAuthorizationFenceBinding.fromStored(application.operation().accountBindingBytes());
    var publicationAccountBinding =
        publicationAccountBinding(
            source,
            originalPublicationBinding,
            selected,
            publicationId,
            version.canonicalVersionId());
    String workflow =
        PublicationDigestRequestBinding.full(tenant.toString(), "42", publicationId)
            .derivedWorkflowIdentity();
    var freeze =
        new WorldDesignPublicationFenceEvidence(
            NAMESPACE,
            tenant,
            version.canonicalVersionId(),
            version.operationId(),
            42L,
            intake.intakeRequestId(),
            intake.operationId(),
            intake.requestDigest(),
            source.operationId(),
            source.evidenceDigest(),
            intake.receiptDigest(),
            publicationId,
            selected.digest().substring(7),
            1L,
            workflow);
    var digestService = digestService(world.dsl());
    var authorizationRepository =
        new WorldSelectedDraftPublicationAuthorizationRepository(world.dsl());
    var inventoryRepository = new WorldSelectedPublicationArtifactInventoryRepository(world.dsl());
    var checkpointRepository =
        new WorldSelectedDraftPublicationCheckpointRepository(
            fence, applications, topology, digestService);
    var capturedCheckpoint =
        new AtomicReference<WorldSelectedDraftPublicationCheckpointRepository.CapturedCheckpoint>();
    var attemptCreatedInTransaction = new AtomicBoolean();
    var frozenAttempt =
        Objects.requireNonNull(
            world
                .write()
                .execute(
                    s -> {
                      attemptCreatedInTransaction.set(false);
                      var attempt =
                          fence.claimFreeze(
                              freeze,
                              () -> {
                                var captured = checkpointRepository.captureWithSource(freeze, plan);
                                capturedCheckpoint.set(captured);
                                attemptCreatedInTransaction.set(true);
                                return captured.checkpoint();
                              });
                      var retained =
                          authorizationRepository.retainOrRequireExact(
                              attempt,
                              publicationAccountBinding,
                              attemptCreatedInTransaction.get());
                      assertThat(retained.canonicalBytes())
                          .containsExactly(publicationAccountBinding.canonicalBytes());
                      inventoryRepository.retainOrRequireExact(
                          attempt,
                          retained,
                          attemptCreatedInTransaction.get(),
                          capturedCheckpoint.get());
                      return attempt;
                    }));
    assertThat(authorizationRepository.readCommitted(frozenAttempt).orElseThrow().canonicalBytes())
        .containsExactly(publicationAccountBinding.canonicalBytes());
    var freezeRequest =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            NAMESPACE,
            tenant,
            version.canonicalVersionId(),
            publicationId,
            1L,
            selected.digest().substring("sha256:".length()),
            publicationAccountBinding);
    var freezeAcknowledgement =
        new WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement(
            freezeRequest,
            intake.intakeRequestId(),
            1L,
            frozenAttempt.publicationFence(),
            WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase.FROZEN,
            frozenAttempt.checkpoint().appliedCommitId(),
            frozenAttempt.checkpoint().contentDigest(),
            frozenAttempt.checkpoint().digestSchemaVersion());
    var freezeEvidence =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
            freezeRequest,
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(freezeAcknowledgement));
    WorldSelectedPublicationArtifactInventoryEvidence inventoryEvidence =
        new WorldSelectedPublicationArtifactInventoryReadService(
                NAMESPACE, fence, authorizationRepository, inventoryRepository)
            .readRetained(freezeEvidence);
    var tuples =
        plan.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var captureRequest =
        new WorldAuthoredGraphSnapshot.CaptureRequest(
            NAMESPACE,
            tenant,
            version.canonicalVersionId(),
            intake.intakeRequestId(),
            frozenAttempt.publicationFence(),
            publicationId,
            freeze.requestDigest(),
            1L,
            workflow,
            plan.binding().commitId().toString(),
            frozenAttempt.checkpoint().contentDigest(),
            4,
            tuples);
    var frozenRepository =
        new WorldCanonicalFrozenTopologyRepository(
            world.dsl(),
            new WorldAuthoredGraphSnapshotRepository(world.dsl()),
            topology,
            digestService);
    var captured =
        new WorldCanonicalFrozenTopologyService(frozenRepository, world.manager())
            .capture(new WorldCanonicalFrozenTopology.Request(plan, captureRequest));
    assertThat(frozenRepository.readCommitted(captureRequest).orElseThrow().graphBytes())
        .containsExactly(captured.graphBytes());
    var selector =
        publishedStartLocationEvidence(
            new WorldPublishedStartLocationRepository(world.dsl(), frozenRepository, applications)
                .readCommitted(captureRequest)
                .orElseThrow());

    return new CapturedPreparationSource(
        intake,
        version,
        publicationId,
        publicationAccountBinding,
        inventoryEvidence,
        captured,
        selector);
  }

  private static WorldCanonicalInstancePreparation.Input preparationInputs(
      Database world,
      Database gs,
      StartSessionPostAuthorizationExecutionTuple tuple,
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim,
      UUID tenant,
      boolean playerCreatedPlacement,
      AtomicReference<RealmPolicyPublishedEvidence.PublishedSet> createdPublication,
      AuthoredWorldSourceEvidence source)
      throws Exception {
    var capturedSource = capturePreparationSource(world, tenant, source);
    var intake = capturedSource.intake();
    var version = capturedSource.version();
    var publicationId = capturedSource.publicationId();
    var publicationAccountBinding = capturedSource.publicationAccountBinding();
    var inventoryEvidence = capturedSource.inventoryEvidence();
    var captured = capturedSource.captured();
    var selector = capturedSource.selector();

    var gsSources = new GameSessionAuthoredWorldSourceRepository(gs.dsl());
    var gsReceipt =
        Objects.requireNonNull(
            gs.write().execute(s -> gsSources.register(UUID.randomUUID(), source)));
    var gsTenant =
        Objects.requireNonNull(
            gs.write()
                .execute(
                    s ->
                        new GameSessionFreshTenantAssociationRepository(gs.dsl())
                            .associateFresh(
                                UUID.randomUUID(),
                                NAMESPACE,
                                gsReceipt,
                                CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(
                                    source))));
    var catalogRequest =
        new CreateCanonicalRealmCatalogRequest(
            UUID.randomUUID(),
            NAMESPACE,
            tenant,
            gsReceipt.operationId(),
            source.worldSlug(),
            source.worldDisplayName(),
            true,
            true,
            "SHARED",
            playerCreatedPlacement ? "PLAYER_CREATED" : "PRESEEDED_ONLY",
            null,
            null);
    var publishedSet =
        publishedSet(
            catalogRequest,
            source,
            version.canonicalVersionId(),
            42L,
            publicationId,
            "controlled-immutable-release",
            "controlled-generation-free",
            2L,
            selector,
            inventoryEvidence);
    if (playerCreatedPlacement) createdPublication.set(publishedSet);
    assertThat(
            GameDesignPublicationOperationBinding.fromStored(publishedSet.operationBytes())
                .account()
                .canonicalBytes())
        .containsExactly(publicationAccountBinding.canonicalBytes());
    commitControlledPublicationTerminal(world, publishedSet);
    var catalog = new GameSessionCanonicalRealmCatalogRepository(gs.dsl());
    var preparedCatalog = catalog.prepareInitialPublicProduction(catalogRequest, publishedSet);
    gs.write().executeWithoutResult(s -> catalog.createInitialPublicProduction(preparedCatalog));
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            tuple.controlPlaneRequestId(),
            tenant,
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            tuple.preAuthorizationTuple().action().target().gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "controlled-original-descriptor",
            42L,
            false,
            null,
            "{}",
            "controlled-generation-free",
            2L,
            91L,
            "controlled-immutable-release",
            false,
            null);
    var complete = completeBinding(descriptor, publishedSet);
    pinLaunch(gs.dsl(), gs.write(), tuple, source, publishedSet, complete, claim, intake);
    var starting =
        Objects.requireNonNull(
            gs.write()
                .execute(
                    s ->
                        new GameSessionCanonicalStartingInstanceRepository(gs.dsl(), NAMESPACE)
                            .createStarting(
                                gsTenant,
                                complete,
                                tuple.preAuthorizationTuple().action().target().ownerAccountId(),
                                tuple.controlPlaneRequestId(),
                                tuple.mutationDigest(),
                                tuple)));
    var request =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.randomUUID(),
            NAMESPACE,
            tenant,
            source.worldSlug(),
            starting.gameInstanceUuid(),
            tuple.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            complete.releaseAttestation().evidenceDigest());
    var retained =
        new CanonicalGameInstanceLaunchAssociationRepository(gs.dsl(), NAMESPACE)
            .readOriginalStartSessionAssociation(request)
            .orElseThrow();
    var association = retained.association();
    assertThat(retained.postAuthorizationExecutionTuple().canonicalBytes())
        .containsExactly(tuple.canonicalBytes());
    assertThat(retained.ownerAttemptId()).isEqualTo(claim.ownerAttemptId());
    assertThat(retained.ownerFence()).isEqualTo(claim.ownerFence());
    var readRequest =
        new WorldCanonicalInstanceAssociation.GameSessionReadRequest(
            request.readRequestId(),
            NAMESPACE,
            tenant,
            source.worldSlug(),
            starting.gameInstanceUuid(),
            tuple.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            complete.releaseAttestation().evidenceDigest());
    var readEvidence =
        new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
            request.readRequestId(),
            NAMESPACE,
            tenant,
            source.worldSlug(),
            starting.gameInstanceUuid(),
            tuple.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            complete.releaseAttestation().evidenceDigest(),
            association.playableStateNamespaceId(),
            association.playableStateScope().name(),
            association.publicProduction(),
            retained.canonicalRealmId(),
            retained.catalogRevision(),
            association.currentGameInstanceStatus().name(),
            association.currentRowVersion(),
            descriptor,
            complete.releaseAttestation());
    var launchReceipt =
        Objects.requireNonNull(
            world
                .write()
                .execute(
                    s ->
                        new WorldCompleteLaunchBindingRepository(world.dsl())
                            .acceptFresh(NAMESPACE, intake, complete)));
    return new WorldCanonicalInstancePreparation.Input(
        readRequest,
        readEvidence,
        claim.ownerAttemptId(),
        claim.ownerFence(),
        launchReceipt,
        version,
        WorldCanonicalInstanceTopologyPlan.create(captured));
  }

  private static WorldDraftDesignDigestServiceImpl digestService(DSLContext dsl) {
    return new WorldDraftDesignDigestServiceImpl(
        new RegionRepository(dsl),
        new ZoneRepository(dsl),
        new RoomRepository(dsl),
        new RoomExitRepository(dsl),
        new GenerationRuleRepository(dsl),
        new WorldEntitySpawnBindingRepository(dsl));
  }

  private static AuthoredDraftPublishSelectionBinding selection(
      DraftCommitBinding draft, byte[] appliedBytes, String publicationId) throws Exception {
    var epochs =
        draft.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    Map.of(
                        "aggregateType",
                        unit.aggregateType(),
                        "aggregateId",
                        unit.aggregateId(),
                        "scopeType",
                        unit.scopeType(),
                        "scopeId",
                        unit.scopeId(),
                        "expectedEpoch",
                        unit.expectedEpoch(),
                        "resultingEpoch",
                        new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
            .toList();
    String vector =
        new String(
            net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
                JSON.writeValueAsString(
                    List.of(
                        Map.of(
                            "owner",
                            "WORLD_MANAGEMENT",
                            "status",
                            "APPLIED",
                            "commitId",
                            draft.commitId().toString(),
                            "bindingDigest",
                            draft.digest(),
                            "resultIdentity",
                            "TEST_ONLY-controlled-authoring",
                            "resultBytesBase64",
                            Base64.getEncoder().encodeToString(appliedBytes),
                            "appliedEpochs",
                            epochs)))),
            StandardCharsets.UTF_8);
    var intent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            draft.target().canonicalTenantId(),
            draft.target().canonicalVersionId(),
            publicationId,
            "1",
            "TEST_ONLY controlled publication upstream",
            draft.requestId(),
            draft.commitId(),
            draft.digest());
    return AuthoredDraftPublishSelectionBinding.capture(
        intent,
        draft.target(),
        draft,
        new AuthoredDraftPublishSelectionBinding.VisibilityFence(
            draft.target(),
            draft.requestId(),
            draft.commitId(),
            draft.digest(),
            vector,
            OffsetDateTime.parse("2026-10-14T00:00:00Z")));
  }

  private static Server server(
      String service,
      WorldOriginalStartSessionPreparationProofPki pki,
      BindableService... receivers)
      throws Exception {
    return buildServer(service, pki, 0, null, receivers);
  }

  private static ServerInterceptor captureServerStatusCause(
      AtomicReference<Throwable> observedCause) {
    return new ServerInterceptor() {
      @Override
      public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
          ServerCall<RequestT, ResponseT> call,
          Metadata headers,
          ServerCallHandler<RequestT, ResponseT> next) {
        var observingCall =
            new ForwardingServerCall.SimpleForwardingServerCall<RequestT, ResponseT>(call) {
              @Override
              public void close(Status status, Metadata trailers) {
                if (status.getCause() != null) observedCause.set(status.getCause());
                super.close(status, trailers);
              }
            };
        return next.startCall(observingCall, headers);
      }
    };
  }

  private static Server buildServer(
      String service,
      WorldOriginalStartSessionPreparationProofPki pki,
      int port,
      java.util.function.Supplier<ServerServiceDefinition> additionalService,
      BindableService... receivers)
      throws Exception {
    return buildServer(service, pki, port, additionalService, null, receivers);
  }

  private static Server buildServer(
      String service,
      WorldOriginalStartSessionPreparationProofPki pki,
      int port,
      java.util.function.Supplier<ServerServiceDefinition> additionalService,
      io.grpc.util.MutableHandlerRegistry deferredServices,
      BindableService... receivers)
      throws Exception {
    var identity = pki.server(service);
    var builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
            .sslContext(
                GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                    .trustManager(pki.ca().toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
    if (deferredServices != null) builder.fallbackHandlerRegistry(deferredServices);
    if (Arrays.stream(receivers)
        .anyMatch(AccountInitialAdmissionSettlementReadGrpcService.class::isInstance)) {
      builder.maxInboundMessageSize(
          AccountInitialAdmissionSettlementReadGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    }
    if (additionalService != null)
      builder.addService(
          ServerInterceptors.intercept(additionalService.get(), new GrpcPeerIdentityInterceptor()));
    for (var receiver : receivers)
      builder.addService(ServerInterceptors.intercept(receiver, new GrpcPeerIdentityInterceptor()));
    return builder.build().start();
  }

  /** Owns the early kernel-assigned World socket through all owner-assembly failure paths. */
  private static final class DeferredWorldServer implements AutoCloseable {
    private final io.grpc.util.MutableHandlerRegistry services =
        new io.grpc.util.MutableHandlerRegistry();
    private final Server server;

    private DeferredWorldServer(WorldOriginalStartSessionPreparationProofPki pki) throws Exception {
      server = buildServer("world-management-service", pki, 0, null, services);
    }

    private Server server() {
      return server;
    }

    private void addService(ServerServiceDefinition service) {
      if (services.addService(
              ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
          != null) {
        throw new IllegalArgumentException("Duplicate deferred World service");
      }
    }

    @Override
    public void close() throws InterruptedException {
      shutdown(server);
    }
  }

  private static DataSourceTransactionManager accountManager(DSLContext account) {
    // Reuse this fixture's actual schema datasource; no second Account schema or owner rows.
    var provider =
        (org.jooq.impl.DataSourceConnectionProvider) account.configuration().connectionProvider();
    var source = (TransactionAwareDataSourceProxy) provider.dataSource();
    return new DataSourceTransactionManager(Objects.requireNonNull(source.getTargetDataSource()));
  }

  private static void shutdown(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static void shutdown(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static void assertPendingActivationState(DSLContext world) {
    assertThat(world.fetch("SELECT status FROM world_instance").getValues("status", String.class))
        .containsExactly("PREPARING");
    assertThat(
            world
                .fetch("SELECT operation_state FROM world_canonical_instance_activation_fence")
                .getValues("operation_state", String.class))
        .containsExactly("PENDING");
    assertThat(world.fetchCount(DSL.table("world_canonical_instance_activation_operation")))
        .isZero();
  }

  private static Map<String, List<String>> rows(DSLContext dsl, String... tables) {
    var result = new TreeMap<String, List<String>>();
    for (String table : tables)
      result.put(
          table,
          dsl.fetch(
                  "SELECT row_to_json(t)::text || '|xmin=' || t.xmin::text AS body FROM "
                      + table
                      + " t ORDER BY row_to_json(t)::text,t.xmin::text")
              .getValues("body", String.class));
    return result;
  }

  private static CharacterRepository transactionalCharacterRepository(
      CharacterRepository target, PlatformTransactionManager manager) {
    var transactions = new TransactionInterceptor();
    transactions.setTransactionManager(manager);
    transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(transactions);
    return (CharacterRepository) proxy.getProxy();
  }

  private static Database database(PostgreSQLContainer<?> postgres, String service, boolean saga) {
    return database(postgres, service, saga, false);
  }

  private static Database database(
      PostgreSQLContainer<?> postgres, String service, boolean saga, boolean retainNumericWorld) {
    String schema = "original_prepare_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var locations = new ArrayList<String>();
    locations.add(
        "filesystem:"
            + repositoryRoot().resolve("services/" + service + "/src/main/resources/db/migration"));
    if (saga)
      locations.add(
          "filesystem:"
              + repositoryRoot()
                  .resolve("services/common-saga/src/main/resources/db/migration/saga"));
    var migrations =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .placeholders(Map.of("serviceSchema", schema))
            .locations(locations.toArray(String[]::new));
    if ("entity-management-service".equals(service)) {
      migrations
          .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
          .setTransactionalLock(false);
    }
    HikariDataSource pool = null;
    try {
      if (retainNumericWorld) {
        assertThat(service).isEqualTo("world-management-service");
        migrations.target("1007").load().migrate();
        var legacy = DSL.using(source, SQLDialect.POSTGRES);
        assertThat(legacy.fetchValue("SHOW session_replication_role", String.class))
            .isEqualTo("origin");
        legacy.execute(
            "INSERT INTO world_instance (tenant_id,game_instance_id,game_template_id,"
                + "control_plane_request_id,launch_descriptor_id,version_id,generation_config_revision,"
                + "release_bundle_id,published_release_bundle_ref,version_state_epoch,status,runtime_flags_json) VALUES "
                + "(8100001,8200001,71,'native213-retained-request','retained-descriptor',83,"
                + "'retained-config',97,'retained-release',5,'LEGACY_FIXTURE','{}')");
        migrations.target(org.flywaydb.core.api.MigrationVersion.LATEST);
      }
      migrations.load().migrate();
      if ("world-management-service".equals(service) || "game-session-service".equals(service)) {
        pool = new HikariDataSource();
        pool.setPoolName("world-fixture-" + schema);
        pool.setDataSource(source);
        pool.setMaximumPoolSize(4);
        pool.setMinimumIdle(0);
      }
      javax.sql.DataSource ownerSource = pool == null ? source : pool;
      var manager = new DataSourceTransactionManager(ownerSource);
      var write = new TransactionTemplate(manager);
      write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(ownerSource), SQLDialect.POSTGRES);
      WorldRuntimeInstallationRegistry installation = null;
      if ("world-management-service".equals(service)) {
        // This is a stipulated one-workload installation solely for this receiving-owner proof.
        // Real Account/GS/mTLS behavior remains exercised independently; none supplies global
        // installed membership, deployment completeness or production source custody.
        byte[] artifact = controlledInstallationArtifact(schema);
        installation =
            new WorldRuntimeInstallationRegistry(
                dsl,
                manager,
                schema,
                NAMESPACE,
                () ->
                    new WorldRuntimeInstallationRegistry.HeldInstallation() {
                      private boolean closed;

                      @Override
                      public byte[] exactArtifactBytes() {
                        return artifact.clone();
                      }

                      @Override
                      public void requireCurrent() {
                        if (closed)
                          throw new IllegalStateException("Synthetic installation hold is closed");
                      }

                      @Override
                      public void close() {
                        closed = true;
                      }
                    });
        installation.register();
      }
      return new Database(schema, dsl, manager, write, installation, pool);
    } catch (RuntimeException | Error failure) {
      try {
        DSL.using(source, SQLDialect.POSTGRES)
            .execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      if (pool != null) {
        try {
          pool.close();
        } catch (RuntimeException | Error cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  private static WorldRuntimeInstallationRegistry.Registration advanceInstallationRegistry(
      Database world) {
    byte[] artifact = controlledInstallationArtifact(world.schema(), "synthetic-installation-v2");
    var advancedRegistry =
        new WorldRuntimeInstallationRegistry(
            world.dsl(),
            world.manager(),
            world.schema(),
            NAMESPACE,
            () ->
                new WorldRuntimeInstallationRegistry.HeldInstallation() {
                  @Override
                  public byte[] exactArtifactBytes() {
                    return artifact.clone();
                  }

                  @Override
                  public void requireCurrent() {}

                  @Override
                  public void close() {}
                });
    return advancedRegistry.register();
  }

  private static byte[] controlledInstallationArtifact(String schema) {
    return controlledInstallationArtifact(
        schema, "synthetic-installation-v1", schema + ":artifact", schema + ":installation");
  }

  private static byte[] controlledInstallationArtifact(String schema, String generation) {
    return controlledInstallationArtifact(
        schema,
        generation,
        schema + ":" + generation + ":artifact",
        schema + ":" + generation + ":installation");
  }

  private static byte[] controlledInstallationArtifact(
      String schema, String generation, String artifactName, String installationName) {
    var artifact = JSON.createObjectNode();
    artifact.put("schema", "firemud-runtime-installation-inventory/v1");
    artifact.put(
        "artifactId",
        UUID.nameUUIDFromBytes(artifactName.getBytes(StandardCharsets.UTF_8)).toString());
    artifact.put(
        "installationId",
        UUID.nameUUIDFromBytes(installationName.getBytes(StandardCharsets.UTF_8)).toString());
    artifact.put("environment", "local-dev");
    artifact.put("generation", generation);
    artifact.put("clusterUid", "synthetic-cluster");
    artifact.put("namespaceUid", "synthetic-namespace");
    artifact.put("namespace", NAMESPACE);
    artifact.put("sourceCommit", "a".repeat(40));
    artifact.put("renderedManifestDigest", "sha256:" + "a".repeat(64));
    artifact.put("declarationsDigest", "sha256:" + "b".repeat(64));
    var executor = artifact.putObject("executor");
    executor.put("identity", "synthetic-world-receiving-fixture/v1");
    executor.put("versionDigest", "sha256:" + "c".repeat(64));
    var bindings = artifact.putObject("inputBindings");
    for (String category :
        List.of(
            "migrations",
            "configurations",
            "mountedResources",
            "secretContracts",
            "externalBindings")) bindings.putArray(category);
    var member = artifact.putArray("members").addObject();
    member.put("workloadApiVersion", "v1");
    member.put("workloadKind", "Pod");
    member.put("workloadNamespace", NAMESPACE);
    member.put("workloadName", "synthetic-world");
    member.put("containerKind", "APPLICATION");
    member.put("containerName", "world");
    member.put("image", "registry.example/synthetic/world@sha256:" + "d".repeat(64));
    member.put("ownerId", "world-management-service");
    member.put("ownership", "RUNTIME_OWNER");
    var source = member.putArray("catalogSources").addObject();
    source.put("sourceId", "synthetic-world-sql");
    source.put("contract", "synthetic/world/catalog/v1");
    source.put("epoch", "1");
    source.put("digest", "sha256:" + "e".repeat(64));
    var family = member.putArray("families").addObject();
    family.put("familyId", "synthetic-world-state");
    family.put("catalogSourceId", "synthetic-world-sql");
    family.put("replacementClass", "S3");
    var cleanup = family.putObject("cleanupContract");
    for (String field : List.of("operation", "requestIdentity", "acknowledgement", "retention"))
      cleanup.put(field, "synthetic/world/" + field + "/v1");
    return JSON.writeValueAsBytes(artifact);
  }

  private record Database(
      String schema,
      DSLContext dsl,
      DataSourceTransactionManager manager,
      TransactionTemplate write,
      WorldRuntimeInstallationRegistry installationRegistry,
      HikariDataSource pool)
      implements AutoCloseable {
    @Override
    public void close() {
      try (HikariDataSource ownedPool = pool) {
        dsl.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      }
    }
  }

  private static void assertSuppressedTerminalReadbackFailure(RuntimeException failure) {
    Throwable[] suppressed = failure.getSuppressed();
    assertThat(suppressed).hasSize(1);
    assertThat(suppressed[0])
        .isInstanceOfSatisfying(
            TransientDataAccessResourceException.class,
            readbackFailure ->
                assertThat(readbackFailure)
                    .hasMessage("TEST_ONLY transient World terminal readback failure"));
  }

  /** One-shot failure injection when the repository starts its real read-only fence read. */
  private static final class ActivationTerminalReadbackFault {
    private final AtomicBoolean failNextReadback = new AtomicBoolean();
    private final AtomicInteger failedReadbacks = new AtomicInteger();

    private void failNextReadback() {
      if (!failNextReadback.compareAndSet(false, true)) {
        throw new AssertionError("A World terminal readback failure is already armed");
      }
    }

    private void failIfArmed() {
      if (failNextReadback.compareAndSet(true, false)) {
        failedReadbacks.incrementAndGet();
        throw new TransientDataAccessResourceException(
            "TEST_ONLY transient World terminal readback failure");
      }
    }

    private int failedReadbacks() {
      return failedReadbacks.get();
    }
  }

  /** Test-local commit-boundary fault injection over the existing World transaction manager. */
  private static final class ActivationTransactionFault implements PlatformTransactionManager {
    private final PlatformTransactionManager delegate;
    private final DSLContext world;
    private final ActivationFailure failure;
    private final ActivationTerminalReadbackFault terminalReadbackFault;
    private final Map<TransactionStatus, Boolean> readOnlyTransactions = new IdentityHashMap<>();
    private int writeTransactionsStarted;
    private int writeCommits;
    private int lifecycleCasCommitAttempts;
    private int verifiedPendingRollbacks;

    private ActivationTransactionFault(
        PlatformTransactionManager delegate,
        DSLContext world,
        ActivationFailure failure,
        ActivationTerminalReadbackFault terminalReadbackFault) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      this.world = Objects.requireNonNull(world, "world");
      this.failure = Objects.requireNonNull(failure, "failure");
      this.terminalReadbackFault = Objects.requireNonNull(terminalReadbackFault);
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      TransactionDefinition effectiveDefinition =
          definition == null ? new DefaultTransactionDefinition() : definition;
      if (effectiveDefinition.isReadOnly()) terminalReadbackFault.failIfArmed();
      TransactionStatus status = delegate.getTransaction(effectiveDefinition);
      readOnlyTransactions.put(status, effectiveDefinition.isReadOnly());
      if (!effectiveDefinition.isReadOnly()) writeTransactionsStarted++;
      return status;
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
      Boolean readOnly = readOnlyTransactions.remove(status);
      if (!Boolean.TRUE.equals(readOnly)) {
        writeCommits++;
        if (writeCommits > 1) {
          int attempt = ++lifecycleCasCommitAttempts;
          if (shouldInject(attempt)) {
            if (failure == ActivationFailure.TRANSIENT_AFTER_COMMIT_ONCE
                || failure == ActivationFailure.TRANSIENT_AFTER_COMMIT_ONCE_READBACK_FAILURE) {
              delegate.commit(status);
              if (shouldFailReadback(attempt)) terminalReadbackFault.failNextReadback();
              throw transientFailure();
            }
            delegate.rollback(status);
            verifiedPendingRollbacks++;
            assertPendingActivationState(world);
            if (shouldFailReadback(attempt)) terminalReadbackFault.failNextReadback();
            if (failure == ActivationFailure.NONTRANSIENT_ROLLBACK_ONCE
                || failure == ActivationFailure.NONTRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE) {
              throw new IllegalStateException("TEST_ONLY nontransient World activation failure");
            }
            throw transientFailure();
          }
        }
      }
      delegate.commit(status);
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
      readOnlyTransactions.remove(status);
      delegate.rollback(status);
    }

    private boolean shouldInject(int attempt) {
      return switch (failure) {
        case TRANSIENT_ROLLBACK_ONCE,
            TRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE,
            TRANSIENT_AFTER_COMMIT_ONCE,
            TRANSIENT_AFTER_COMMIT_ONCE_READBACK_FAILURE,
            NONTRANSIENT_ROLLBACK_ONCE,
            NONTRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE ->
            attempt == 1;
        case TRANSIENT_ROLLBACK_ALWAYS, TRANSIENT_ROLLBACK_ALWAYS_FINAL_READBACK_FAILURE ->
            attempt <= 3;
        case NONE -> false;
      };
    }

    private boolean shouldFailReadback(int attempt) {
      return switch (failure) {
        case TRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE,
            TRANSIENT_AFTER_COMMIT_ONCE_READBACK_FAILURE,
            NONTRANSIENT_ROLLBACK_ONCE_READBACK_FAILURE ->
            attempt == 1;
        case TRANSIENT_ROLLBACK_ALWAYS_FINAL_READBACK_FAILURE -> attempt == 3;
        case NONE,
            TRANSIENT_ROLLBACK_ONCE,
            TRANSIENT_AFTER_COMMIT_ONCE,
            TRANSIENT_ROLLBACK_ALWAYS,
            NONTRANSIENT_ROLLBACK_ONCE ->
            false;
      };
    }

    private static TransientDataAccessResourceException transientFailure() {
      return new TransientDataAccessResourceException(
          "TEST_ONLY transient World activation transaction outcome");
    }

    private int writeTransactionsStarted() {
      return writeTransactionsStarted;
    }

    private int lifecycleCasCommitAttempts() {
      return lifecycleCasCommitAttempts;
    }

    private int verifiedPendingRollbacks() {
      return verifiedPendingRollbacks;
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("Repository filesystem migrations unavailable");
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(("TEST_ONLY:" + value).getBytes(StandardCharsets.UTF_8));
  }

  private static WorldDraftTopologyCommitPlan topologyPlan(OwnerFixture owner) throws Exception {
    UUID commitId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    UUID zoneId = UUID.randomUUID();
    UUID firstRoomId = UUID.randomUUID();
    UUID secondRoomId = UUID.randomUUID();
    var mutations = new ArrayList<WorldDesignMutationRevision>();
    mutations.add(
        mutation(
                commitId,
                regionId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setRegion(
                RegionDesignMutation.newBuilder()
                    .setName("Selected region")
                    .setWeather("RAIN")
                    .setShardId(7)
                    .setGenerationSeed(89123L)
                    .setSpacingMultiplier(1.75))
            .build());
    mutations.add(
        mutation(
                commitId,
                zoneId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
            .setZone(
                ZoneDesignMutation.newBuilder()
                    .setName("Selected zone")
                    .setRegionId(regionId.toString()))
            .build());
    mutations.add(
        mutation(
                commitId,
                firstRoomId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder()
                    .setName("Selected start")
                    .setDescription("The eastern gate opens onto a ridge.")
                    .setNameLocalizedVariantsJson("{\"fr\":\"Depart\"}")
                    .setDescriptionLocalizedVariantsJson("{\"fr\":\"La porte est\"}")
                    .setZoneId(zoneId.toString()))
            .build());
    mutations.add(
        mutation(
                commitId,
                secondRoomId,
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder()
                    .setName("Selected destination")
                    .setDescription("A distinct destination beyond the gate.")
                    .setNameLocalizedVariantsJson("{\"fr\":\"Arrivee\"}")
                    .setDescriptionLocalizedVariantsJson("{\"fr\":\"La crete\"}")
                    .setZoneId(zoneId.toString()))
            .build());
    mutations.add(
        mutation(
                commitId,
                UUID.randomUUID(),
                regionId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT)
            .setRoomExit(
                RoomExitDesignMutation.newBuilder()
                    .setFromRoomId(firstRoomId.toString())
                    .setToRoomId(secondRoomId.toString())
                    .setDirection("EAST")
                    .setCost(13))
            .build());
    // Opaque REGION generatorType/params remain empty: current selected inventory refuses them.
    // This original preparation proof declares zero generation and spawn intent.
    // Nonempty published intent remains refused by the canonical production owner.

    WorldFreshGraphDeclaration declaration =
        declaration(
            owner.version().canonicalVersionId(),
            owner.intake().canonicalTenantId(),
            firstRoomId,
            mutations);
    mutations.set(
        0, mutations.getFirst().toBuilder().setFreshGraphDeclaration(declaration).build());

    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> affected = new ArrayList<>();
    UUID canonicalVersionId = owner.version().canonicalVersionId();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "controlled selected Game Design Version original-order evidence"));
    affected.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "DRAFT",
            canonicalVersionId.toString(),
            "AGGREGATE",
            canonicalVersionId.toString(),
            "0"));
    for (int index = 0; index < mutations.size(); index++) {
      WorldDesignMutationRevision mutation = mutations.get(index);
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(index + 1),
              UUID.fromString(mutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().omittingInsignificantWhitespace().print(mutation)));
      String family =
          mutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      affected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "AGGREGATE",
              mutation.getAggregateId(),
              "0"));
      affected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "REGION_SUBTREE",
              regionId.toString(),
              "0"));
    }
    var source = owner.source();
    var binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                source.canonicalTenantId(),
                owner.version().canonicalVersionId(),
                owner.version().gameDesignVersionId(),
                source.sourceGameTenantKey(),
                source.sourceGameRowId(),
                source.sourceGameTenantKey(),
                source.provenanceKind()),
            UUID.randomUUID(),
            commitId,
            "controlled-base-commit",
            revisions,
            affected);
    OwnerBinding ownerBinding =
        new OwnerBinding(
            NAMESPACE,
            source.canonicalTenantId(),
            owner.version().canonicalVersionId(),
            owner.version().operationId(),
            owner.version().gameDesignVersionId(),
            owner.intake().intakeRequestId(),
            owner.intake().operationId(),
            owner.intake().requestDigest(),
            source.operationId(),
            source.evidenceDigest(),
            owner.intake().receiptDigest());
    return WorldDraftTopologyCommitPlan.create(binding, ownerBinding);
  }

  private static WorldFreshGraphDeclaration declaration(
      UUID versionId,
      UUID tenantId,
      UUID startRoomId,
      List<WorldDesignMutationRevision> mutations) {
    return WorldFreshGraphDeclaration.newBuilder()
        .setTenantId(tenantId.toString())
        .setVersionId(versionId.toString())
        .setStartLocation(
            net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                .setTenantId(tenantId.toString())
                .setVersionId(versionId.toString())
                .setRoomTemplateId(startRoomId.toString()))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE))
        .addFamilyCounts(
            count(
                mutations,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING))
        .setInboundSourceClosure(inboundSourceClosure())
        .build();
  }

  private static WorldFreshGraphFamilyCount count(
      List<WorldDesignMutationRevision> mutations, WorldDesignAggregateType family) {
    int count =
        Math.toIntExact(
            mutations.stream().filter(mutation -> mutation.getAggregateType() == family).count());
    return WorldFreshGraphFamilyCount.newBuilder().setFamily(family).setCount(count).build();
  }

  private static WorldInboundSourceClosureDeclaration inboundSourceClosure() {
    return WorldInboundSourceClosureDeclaration.newBuilder()
        .setSchemaVersion(1)
        .addFamilyCounts(
            inboundCount(WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT))
        .addFamilyCounts(
            inboundCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT))
        .addFamilyCounts(
            inboundCount(WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION))
        .addFamilyCounts(
            inboundCount(WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING))
        .addFamilyCounts(
            inboundCount(WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK))
        .addFamilyCounts(
            inboundCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE))
        .addFamilyCounts(
            inboundCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING))
        .build();
  }

  private static WorldInboundSourceFamilyCount inboundCount(WorldInboundSourceFamily family) {
    return WorldInboundSourceFamilyCount.newBuilder().setFamily(family).setCount(0).build();
  }

  private static WorldDesignMutationRevision.Builder mutation(
      UUID commitId, UUID aggregateId, UUID regionId, WorldDesignAggregateType aggregateType) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commitId.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(aggregateId.toString())
        .setAggregateType(aggregateType)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(regionId.toString());
  }

  private static AuthoredWorldSourceEvidence controlledSource(String label, UUID canonicalTenant) {
    UUID registration = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String tenantSlug = "owner-" + suffix;
    String worldSlug = "world-" + suffix;
    String displayName = "Controlled " + label;
    long sourceRow = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000L) + 1L;
    String sourceTenantKey = "source-" + suffix;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, canonicalTenant, tenantSlug, worldSlug, displayName);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registration,
        operation,
        requestDigest,
        canonicalTenant,
        tenantSlug,
        worldSlug,
        displayName,
        sourceRow,
        sourceTenantKey,
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            operation,
            requestDigest,
            canonicalTenant,
            tenantSlug,
            worldSlug,
            displayName,
            sourceRow,
            sourceTenantKey,
            "NEW_GAME_ROW"));
  }

  private static WorldDraftGraphApplication application(WorldDraftTopologyCommitPlan plan) {
    UUID operationId = UUID.randomUUID();
    UUID authorizationFenceId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    DraftCommitBinding binding = plan.binding();
    byte[] accountBytes =
        new DraftAuthorizationFenceBinding(
                operationId,
                binding.requestId(),
                binding.commitId(),
                authorizationFenceId,
                actorId,
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.baseCommitId(),
                "0",
                binding.canonicalBytes(),
                binding.canonicalBytes(),
                binding.digest(),
                List.of(
                    new SourceEvidence(
                        SourceKind.GLOBAL_ROLES,
                        actorId.toString(),
                        null,
                        "1",
                        null,
                        null,
                        "synthetic COMMIT_ORDER component fixture"
                            .getBytes(StandardCharsets.UTF_8))))
            .withRequiredOwners()
            .canonicalBytes();
    var operation =
        new WorldDraftTerminalOperation(
            operationId,
            binding.requestId(),
            binding.commitId(),
            authorizationFenceId,
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding,
            plan.ownerBinding(),
            accountBytes);
    return new WorldDraftGraphApplication(operation, plan);
  }

  private static WorldPublishedStartLocationEvidence publishedStartLocationEvidence(
      WorldPublishedStartLocationSource source) {
    var freeze = source.frozenTopology().request().freeze();
    var tuples =
        freeze.suppliedOwnedAffectedTuples().stream()
            .map(
                tuple ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        tuple.owner(),
                        tuple.aggregateType(),
                        tuple.aggregateId(),
                        tuple.scopeType(),
                        tuple.scopeId(),
                        tuple.expectedEpoch()))
            .toList();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            freeze.targetNamespace(),
            freeze.canonicalTenantId(),
            freeze.canonicalVersionId(),
            freeze.intakeRequestId(),
            freeze.publicationFence(),
            freeze.publicationRequestId(),
            freeze.requestDigest(),
            freeze.versionStateEpoch(),
            freeze.publishWorkflowId(),
            freeze.appliedCommitId(),
            freeze.contentDigest(),
            freeze.digestSchemaVersion(),
            tuples);
    return new WorldPublishedStartLocationEvidence(
        request,
        source.selectorReceipt().canonicalBytes(),
        source.appliedResult().application().operation().accountBindingBytes(),
        source.appliedResult().canonicalBytes());
  }

  private static RealmPolicyPublishedEvidence.PublishedSet publishedSet(
      CreateCanonicalRealmCatalogRequest request,
      AuthoredWorldSourceEvidence source,
      UUID canonicalVersionId,
      long versionRowId,
      String publishRequestId,
      String bundleRef,
      String generationConfigRevision,
      long publicationVersionStateEpoch,
      WorldPublishedStartLocationEvidence seed,
      WorldSelectedPublicationArtifactInventoryEvidence inventoryEvidence) {
    try {
      TargetProof target =
          new TargetProof(
              source.canonicalTenantId(),
              canonicalVersionId,
              versionRowId,
              source.sourceGameTenantKey(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind());
      var original = DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
      var draft =
          DraftCommitBinding.fromStored(
              new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
              original.inputDigest());
      var selected = selection(draft, seed.appliedResultBytes(), publishRequestId);
      var account =
          publicationAccountBinding(
              source, original, selected, publishRequestId, canonicalVersionId);
      String workflow =
          PublicationDigestRequestBinding.full(
                  source.canonicalTenantId().toString(),
                  Long.toString(versionRowId),
                  publishRequestId)
              .derivedWorkflowIdentity();
      var world = seed;
      var operation = new GameDesignPublicationOperationBinding(account, world, inventoryEvidence);
      var policyFields = new java.util.LinkedHashMap<String, Object>();
      boolean playerCreated = "PLAYER_CREATED".equals(request.characterCreationPolicy());
      policyFields.put("schemaVersion", playerCreated ? 3 : 1);
      policyFields.put("worldSlug", source.worldSlug());
      policyFields.put("worldDisplayName", source.worldDisplayName());
      policyFields.put("realmSlug", request.realmSlug());
      policyFields.put("realmDisplayName", request.realmDisplayName());
      policyFields.put("visible", request.visible());
      policyFields.put("publicProduction", request.publicProduction());
      policyFields.put("stateScope", request.stateScope());
      policyFields.put("entryPolicy", request.characterCreationPolicy());
      if (playerCreated) {
        policyFields.put(
            "creationDescriptor",
            Map.of(
                "schemaVersion", 1,
                "descriptorId", id("created-placement-descriptor").toString(),
                "descriptorRevisionId", id("created-placement-descriptor-revision").toString(),
                "inputKind", "EMPTY_OBJECT",
                "dependencies", List.of()));
        policyFields.put(
            "initialActorCore",
            Map.of("displayName", "Created Placement Player", "dispositionKey", "newcomer"));
      }
      String policyJson = RealmPolicySource.canonical(policyFields);
      var policySource =
          new RealmPolicySource.Policy(
              draft.commitId(),
              id("policy-revision:" + request.requestId()),
              "TEST_ONLY-policy",
              RealmEntryPolicy.parse(policyJson, JSON));
      var capture = new RealmPolicySnapshot(draft, "1", List.of(policySource));
      var participants =
          AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
              .map(
                  owner ->
                      new GameDesignPublicationTerminalEvidence.Participant(
                          owner,
                          Long.toString(versionRowId),
                          null,
                          draft.commitId().toString(),
                          "WORLD_MANAGEMENT".equals(owner)
                              ? world.request().contentDigest()
                              : "c".repeat(64),
                          AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                              owner,
                              AuthoredWorldReleaseAttestationEvidence
                                  .CLOSURE_SELECTOR_SCHEMA_VERSION),
                          "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null,
                          null,
                          null))
              .toList();
      var release =
          new GameDesignPublicationTerminalEvidence.ReleaseContent(
              source.canonicalTenantId(),
              canonicalVersionId,
              bundleRef,
              1,
              "v3",
              workflow,
              "sha256:" + "b".repeat(64),
              1,
              List.of(),
              List.of(),
              participants,
              List.of(),
              generationConfigRevision,
              world);
      var terminal =
          new GameDesignPublicationTerminalEvidence(
              operation.canonicalBytes(),
              GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED,
              release,
              publicationVersionStateEpoch);
      UUID policyId = id("policy-identity:" + request.requestId());
      var policy =
          new RealmPolicyPublishedEvidence.Policy(
              policyId,
              policySource,
              RealmPolicyPublishedEvidence.policyDigest(
                  policyId,
                  target,
                  1,
                  bundleRef,
                  release.contentDigest(),
                  workflow,
                  release.manifestHash(),
                  policySource));
      var frame = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(frame, "game-design-realm-policy-source-capture/v1");
      DraftAuthorizationFenceBinding.frame(frame, operation.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(frame, capture.canonicalBytes());
      var policies = List.of(policy);
      String digest =
          RealmPolicyPublishedEvidence.computePolicySetDigest(
              target,
              1,
              draft.commitId(),
              "1",
              bundleRef,
              release.contentDigest(),
              workflow,
              release.manifestHash(),
              publicationVersionStateEpoch,
              operation.canonicalBytes(),
              frame.toByteArray(),
              terminal.canonicalBytes(),
              policies);
      return new RealmPolicyPublishedEvidence.PublishedSet(
          target,
          1,
          draft.commitId(),
          "1",
          bundleRef,
          release.contentDigest(),
          workflow,
          release.manifestHash(),
          publicationVersionStateEpoch,
          operation.canonicalBytes(),
          frame.toByteArray(),
          terminal.canonicalBytes(),
          1,
          digest,
          policies);
    } catch (Exception invalid) {
      throw new IllegalStateException(
          "Controlled complete publication fixture is invalid", invalid);
    }
  }

  private static AccountPublicationAuthorizationBinding publicationAccountBinding(
      AuthoredWorldSourceEvidence source,
      DraftAuthorizationFenceBinding original,
      AuthoredDraftPublishSelectionBinding selected,
      String publishRequestId,
      UUID canonicalVersionId) {
    return new AccountPublicationAuthorizationBinding(
        id("publication-operation:" + publishRequestId + ":" + canonicalVersionId),
        id("publication-fence:" + publishRequestId + ":" + canonicalVersionId),
        new AccountPublicationAuthorizationBinding.PreallocationInput(
            original.actorAccountId(), selected),
        Arrays.stream(SourceKind.values())
            .flatMap(
                kind -> {
                  if (kind == SourceKind.RECENT_REAUTH_POLICY) {
                    return java.util.stream.Stream.of(
                        recentReauthenticationPolicyEvidence("PLATFORM", 300L, 30L),
                        recentReauthenticationPolicyEvidence("DEPLOYMENT", 240L, 30L));
                  }
                  return java.util.stream.Stream.of(
                      new SourceEvidence(
                          kind,
                          switch (kind) {
                            case ACCOUNT, GLOBAL_ROLES -> original.actorAccountId().toString();
                            case TENANT -> source.canonicalTenantId().toString();
                            case MEMBERSHIP ->
                                original.actorAccountId() + "/" + source.canonicalTenantId();
                            default -> "TEST_ONLY-" + kind;
                          },
                          "1",
                          "1",
                          null,
                          null,
                          ("TEST_ONLY-" + kind).getBytes(StandardCharsets.UTF_8)));
                })
            .toList());
  }

  private static SourceEvidence recentReauthenticationPolicyEvidence(
      String policyScope, long recentAgeSeconds, long maxClockSkewSeconds) {
    String scope = "SELF_SERVICE_GAME_CREATION/" + policyScope;
    String value =
        "account-recent-reauthentication-policy-source/v1\n"
            + "owner=ACCOUNT\n"
            + "purpose=SELF_SERVICE_GAME_CREATION\n"
            + "scope="
            + scope
            + "\nversion=1\nrecentAgeSeconds="
            + recentAgeSeconds
            + "\nmaxClockSkewSeconds="
            + maxClockSkewSeconds
            + "\n";
    return new SourceEvidence(
        SourceKind.RECENT_REAUTH_POLICY,
        scope,
        null,
        "1",
        null,
        null,
        value.getBytes(StandardCharsets.UTF_8));
  }

  private static GameDesignPublicationTerminalEvidence commitControlledPublicationTerminal(
      Database world, RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    byte[] operationBytes = publishedSet.operationBytes();
    byte[] terminalBytes = publishedSet.terminalEvidenceBytes();
    var repository = new WorldPublicationTerminalRepository(world.dsl(), world.manager());
    // This isolated verifier stipulates only the controlled upstream GDS result. The actual World
    // terminal service/repository still enforces owner guards, commits the transition, and reads it
    // back independently.
    var service =
        new WorldPublicationTerminalService(
            repository,
            (submittedOperationBytes, submittedTerminalBytes) -> {
              if (!Arrays.equals(operationBytes, submittedOperationBytes)
                  || !Arrays.equals(terminalBytes, submittedTerminalBytes)) {
                throw new SecurityException(
                    "Controlled GDS terminal differs from the exact submitted publication");
              }
              var evidence =
                  GameDesignPublicationTerminalEvidence.fromStored(submittedTerminalBytes);
              if (evidence.outcome() != GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
                  || !Arrays.equals(evidence.operationBytes(), submittedOperationBytes)) {
                throw new SecurityException(
                    "Controlled GDS terminal is not the exact PUBLISHED operation result");
              }
              var request = WorldPublicationTerminal.request(submittedTerminalBytes);
              var held = new AtomicBoolean(true);
              var authority =
                  new WorldPublicationTerminalService.HeldTerminalAuthority() {
                    @Override
                    public void requireHeld() {
                      if (!held.get()
                          || !Arrays.equals(request.operationBytes(), operationBytes)
                          || !request.exactBytes(terminalBytes)) {
                        throw new IllegalStateException(
                            "Controlled upstream terminal authority is no longer held");
                      }
                    }

                    @Override
                    public void close() {
                      held.set(false);
                    }
                  };
              return new WorldPublicationTerminalService.VerifiedTerminal(request, authority);
            });

    var completed = service.complete(operationBytes, terminalBytes);
    assertThat(completed.canonicalBytes()).containsExactly(terminalBytes);
    var committed =
        repository.readCommitted(WorldPublicationTerminal.request(terminalBytes)).orElseThrow();
    assertThat(committed.canonicalBytes()).containsExactly(terminalBytes);
    return committed;
  }

  private static net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence
      completeBinding(
          net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence descriptor,
          RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(publishedSet.terminalEvidenceBytes());
    var release = terminal.releaseContent();
    var participants =
        release.participantDigests().stream()
            .map(
                participant ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant.participantKey(),
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        publishedSet.sourceCommitId().toString(),
                        participant.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participant.participantKey(), 3),
                        participant.abilitySchemaDigest() != null,
                        participant.abilitySchemaDigest()))
            .toList();
    var evidence =
        AuthoredWorldReleaseAttestationEvidence.createClosureSelector(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            publishedSet.target().canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            publishedSet.publishWorkflowId(),
            publishedSet.sourceCommitId().toString(),
            participants,
            release.manifestHash(),
            release.manifestSchemaVersion(),
            release.requiredManifestAssetKeys(),
            release.artifactDigests(),
            release.commandDefinitions(),
            descriptor.generationConfigRevision(),
            release.worldStartLocationEvidence());
    return new net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence(
        descriptor, evidence);
  }

  private static void pinLaunch(
      DSLContext dsl,
      TransactionTemplate transactions,
      StartSessionPostAuthorizationExecutionTuple tuple,
      AuthoredWorldSourceEvidence source,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet,
      CompleteLaunchBindingEvidence binding,
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim,
      WorldAuthoredSourceIntakeReceipt intake) {
    var attempts = new GameSessionStartSessionOperatorAttemptRepository(dsl, Duration.ofMinutes(5));
    var selections = new GameSessionStartSessionTemplateAssociationRepository(dsl, attempts);
    var descriptors = new GameSessionStartSessionLaunchDescriptorRepository(dsl, selections);
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(publishedSet.terminalEvidenceBytes());
    var release = terminal.releaseContent();
    var world = release.worldStartLocationEvidence();
    var worldRequest = world.request();
    var receipt =
        new net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
            1,
            NAMESPACE,
            intake.intakeRequestId(),
            intake.operationId(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            intake.requestDigest(),
            intake.receiptDigest(),
            source);
    var selected =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Association(
            source.canonicalTenantId(),
            binding.descriptor().gameTemplateId(),
            publishedSet.target().canonicalVersionId(),
            publishedSet.sourceCommitId(),
            publishedSet.publishWorkflowId(),
            GameDesignPublicationOperationBinding.fromStored(publishedSet.operationBytes())
                .account()
                .input()
                .selection()
                .digest(),
            "sha256:" + "b".repeat(64),
            source.targetNamespace(),
            worldRequest.intakeRequestId(),
            receipt.operationId(),
            source.operationId(),
            source.worldSlug(),
            source.evidenceDigest(),
            new net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec
                .ByIdReadRequest(
                1,
                source.targetNamespace(),
                id("world-source-read:" + tuple.controlPlaneRequestId()),
                worldRequest.intakeRequestId(),
                source.canonicalTenantId()),
            receipt);
    var proto =
        net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
            .setId(binding.descriptor().releaseBundleId())
            .setVersionId(binding.descriptor().versionId())
            .setVersionNumber(release.versionNumber())
            .setAttestationSchemaVersion(release.attestationSchemaVersion())
            .setPublishWorkflowId(release.publishWorkflowId())
            .setManifestHash(release.manifestHash())
            .setManifestSchemaVersion(release.manifestSchemaVersion())
            .setGenerationConfigRevision(release.generationConfigRevision())
            .setPublishedReleaseBundleRef(release.publishedReleaseBundleRef())
            .setCanonicalTenantId(source.canonicalTenantId().toString())
            .setCanonicalVersionId(publishedSet.target().canonicalVersionId().toString());
    release
        .participantDigests()
        .forEach(
            participant -> {
              var item =
                  net.firedevops.firemud.gamedesign.v1.ParticipantDigest.newBuilder()
                      .setParticipantKey(participant.participantKey())
                      .setScopeValue(participant.scopeValue())
                      .setAppliedCommitId(participant.appliedCommitId())
                      .setContentDigest(participant.contentDigest())
                      .setDigestSchemaVersion(participant.digestSchemaVersion());
              if (participant.abilitySchemaDigest() != null)
                item.setAbilitySchemaDigest(participant.abilitySchemaDigest());
              proto.addParticipantDigests(item);
            });
    var initialRequest =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Request(
            1,
            source.targetNamespace(),
            id("first-selection:" + tuple.controlPlaneRequestId()),
            tuple.canonicalBytes(),
            claim.ownerAttemptId(),
            claim.ownerFence(),
            new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
                .InitialConfigured());
    var initial =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Result(
            initialRequest,
            selected,
            proto.build(),
            world,
            publishedSet.publicationVersionStateEpoch());
    transactions.execute(status -> selections.pinInitialOrValidateExactReplay(claim, initial));
    var replayRequest =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Request(
            1,
            source.targetNamespace(),
            initialRequest.readRequestId(),
            tuple.canonicalBytes(),
            claim.ownerAttemptId(),
            claim.ownerFence(),
            new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
                .ExactReplay(
                selected.canonicalVersionId(),
                selected.selectedCommitId(),
                selected.publishWorkflowId(),
                selected.associationDigest()));
    var replay =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Result(replayRequest, selected, proto.build(), world, initial.phaseEpoch());
    var resolved =
        new net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec.Resolved(
            replay,
            new net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec
                .DescriptorOutcome(binding.descriptor()));
    transactions.execute(status -> descriptors.pin(claim, resolved));
  }

  private record OwnerFixture(
      AuthoredWorldSourceEvidence source,
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version) {}
}
