package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import com.google.protobuf.Empty;
import com.google.protobuf.util.JsonFormat;
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
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementInput;
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
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
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
 * preparation owner directly, not assembly.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldOriginalStartSessionPreparationOwnerCompositionCrossServiceTest {
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
      new PostgreSQLContainer<>(TestContainerImages.postgres());

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

  private enum Refusal {
    NONE,
    MISMATCHED_ATTEMPT,
    EXPIRED_ATTEMPT,
    CHANGED_ACTIVATION_ATTEMPT,
    EXPIRED_AFTER_ACTIVATION_PROTECTION_ACQUISITION
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
    if (abortCanonicalInitialAdmission && !exerciseCoupledCanonicalInitialAdmission) {
      throw new IllegalArgumentException("Admission abort proof requires the coupled owner path");
    }
    UUID tenant = UUID.randomUUID();
    try (var account =
            new AccountControlUiOriginalOrderFixture(
                ACCOUNT.getJdbcUrl(),
                ACCOUNT.getUsername(),
                ACCOUNT.getPassword(),
                REDIS.getHost(),
                REDIS.getMappedPort(6379),
                proofDirectory.resolve("account"),
                tenant);
        var world = database(WORLD, "world-management-service", true);
        var gs = database(GS, "game-session-service", false)) {
      var issuer =
          new AccountOriginalStartSessionAuthorizationProofFixture(
              account, proofDirectory.resolve("issuer"), NAMESPACE);
      Duration lease =
          refusal == Refusal.EXPIRED_ATTEMPT
              ? Duration.ofSeconds(30)
              : refusal == Refusal.EXPIRED_AFTER_ACTIVATION_PROTECTION_ACQUISITION
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

      var preparedInput = preparationInputs(world, gs, tuple, claim.get(), tenant);
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
      var operation =
          new WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput(
              tuple.canonicalBytes(),
              claim.get().ownerMutationId(),
              input.gameSessionOwnerAttemptId(),
              claim.get().ownerFence());
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
      try (var currentAttempt =
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

        int accountPort = availablePort();
        int worldPort = availablePort();
        var accountEndpoints = new ServiceEndpointsProperties();
        accountEndpoints.setAccountService("localhost:" + accountPort);
        var worldEndpoints = new ServiceEndpointsProperties();
        worldEndpoints.setWorldManagementService("localhost:" + worldPort);
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
                accountPort,
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
          var association =
              new WorldCanonicalInstanceAssociationRepository(
                  world.dsl(),
                  new WorldCompleteLaunchBindingRepository(world.dsl()),
                  new WorldAuthoredSourceIntakeRepository(world.dsl()),
                  new WorldAuthoredVersionIdentityRepository(world.dsl()));
          var repository =
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
                              world.dsl(), new WorldAuthoredSourceIntakeRepository(world.dsl())),
                          JSON),
                      digestService(world.dsl())),
                  Duration.ofSeconds(10));
          var verifier = new WorldStartSessionAccountParticipationVerifier(NAMESPACE, acquisition);
          var identity = new AtomicReference<WorldCanonicalInstanceExecutionIdentity>();
          var service =
              new WorldCanonicalInstancePreparationService(
                  repository,
                  (WorldCanonicalInstancePreparationService
                          .OriginalOperationCommitAuthorityVerifier)
                      (supplied, suppliedOperation) -> {
                        var held = verifier.verifyAndHold(supplied, suppliedOperation);
                        identity.set(held.executionIdentity());
                        return held;
                      },
                  new WorldCanonicalInstanceOriginalOperationRecoveryVerifier(
                      historicalAccount, historicalGs));
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
                                    service.recoverExactTerminal(identity.get()).orElseThrow());
                              else result.set(service.prepare(input, operation));
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
          var activationService = new WorldCanonicalInstanceActivationService(activationRepository);
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
                      new WorldStartSessionExecutionTerminalReadGrpcService(service, NAMESPACE),
                      new WorldOriginalStartSessionProtectedActivationGrpcService(
                          protectedActivationService, NAMESPACE),
                      new WorldOriginalStartSessionActivationTerminalReadGrpcService(
                          activationService, NAMESPACE)));
          if (canonicalHoldOwner != null) {
            worldReceivers.add(
                new WorldCanonicalInitialAdmissionHoldGrpcService(canonicalHoldOwner, NAMESPACE));
          }
          Server worldServer =
              buildServer(
                  "world-management-service",
                  pki,
                  worldPort,
                  () -> socket,
                  worldReceivers.toArray(BindableService[]::new));
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
            Map<String, List<String>> worldBefore =
                rows(
                    world.dsl(),
                    "world_instance",
                    "world_canonical_instance_association",
                    "world_canonical_instance_preparation",
                    "world_canonical_instance_execution",
                    "world_canonical_instance_activation_fence");
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
            var after = rows(world.dsl(), worldBefore.keySet().toArray(String[]::new));
            var protectedAccount =
                rows(issuer.sourceDsl(), accountBefore.keySet().toArray(String[]::new));
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
            var participationSettlement =
                worldParticipationSettlement.settle(
                    new AccountStartSessionWorldParticipationSettlementInput(
                        NAMESPACE,
                        UUID.randomUUID(),
                        identity.get().accountWorldParticipationId(),
                        identity.get().accountWorldParticipationFence()));
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
                assertThatThrownBy(() -> activationRepository.activateProtected(protection, held))
                    .isInstanceOfSatisfying(
                        TransientDataAccessResourceException.class,
                        failure -> assertSuppressedTerminalReadbackFailure(failure));
              } else {
                assertThatThrownBy(() -> activationRepository.activateProtected(protection, held))
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
                                + "FROM world_canonical_instance_activation_fence"))
                .hasSize(1);
            assertThat(
                    world
                        .dsl()
                        .fetch(
                            "SELECT activation_request_id "
                                + "FROM world_canonical_instance_activation_operation"))
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
                  abortCanonicalInitialAdmission);
            }
            // Current Game Session association remains STARTING; this is not admission or delivery.
          } finally {
            channel.shutdownNow();
            assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            shutdown(worldServer);
          }
        } finally {
          shutdown(accountServer);
        }
      } finally {
        shutdown(gsServer);
      }
    }
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
    return Objects.requireNonNull(proxy.getTargetDataSource());
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
      boolean abortCanonicalInitialAdmission)
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

        var abortedWorldTerminal = worldHoldOwner.settleFromAuthenticatedOwnerRead(identity);
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

        try (var accountTerminalRead =
            new GrpcAccountInitialAdmissionSettlementReadClient(
                gsEndpoints, pki.client("account-service"), factory, NAMESPACE)) {
          accountTerminalRead.init();
          var accountOwnerRead =
              accountTerminalRead.read(
                  new AccountInitialAdmissionSettlementReadInput(
                      UUID.randomUUID(), accountProtection));
          assertThat(accountOwnerRead.terminal()).isEqualTo(abortedWorldTerminal);
          assertThat(accountOwnerRead.terminal().outcome())
              .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
          var accountSettlementOwner =
              new AccountStartSessionAdmissionProtectionSettlementService(
                  new AccountStartSessionAdmissionProtectionRepository(issuer.sourceDsl()),
                  accountTerminalRead,
                  accountManager(issuer.sourceDsl()),
                  NAMESPACE);
          var accountSettlement =
              Context.current()
                  .withValue(GrpcPeerIdentity.CONTEXT_KEY, gameSessionPeer)
                  .call(
                      () ->
                          accountSettlementOwner.settle(
                              accountProtection.accountProtectionId(),
                              accountProtection.accountProtectionFence()));
          var accountSettlementReplay =
              Context.current()
                  .withValue(GrpcPeerIdentity.CONTEXT_KEY, gameSessionPeer)
                  .call(
                      () ->
                          accountSettlementOwner.settle(
                              accountProtection.accountProtectionId(),
                              accountProtection.accountProtectionFence()));
          assertThat(accountSettlement.protection().canonicalBytes())
              .containsExactly(accountProtection.canonicalBytes());
          assertThat(accountSettlement.terminal()).isEqualTo(abortedWorldTerminal);
          assertThat(accountSettlementReplay.canonicalBytes())
              .containsExactly(accountSettlement.canonicalBytes());
          var storedSettlement =
              issuer
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT outcome,terminal_bytes,terminal_digest "
                          + "FROM account_start_session_admission_protection_settlements "
                          + "WHERE protection_id=?",
                      accountProtection.accountProtectionId());
          assertThat(storedSettlement.get("outcome", String.class)).isEqualTo("ABORTED");
          assertThat(storedSettlement.get("terminal_bytes", byte[].class))
              .containsExactly(accountSettlement.canonicalBytes());
          assertThat(storedSettlement.get("terminal_digest", String.class))
              .isEqualTo(accountSettlement.digest());
        }

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
          worldHoldOwner.settleFromAuthenticatedOwnerRead(identity);
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

      try (var accountTerminalRead =
          new GrpcAccountInitialAdmissionSettlementReadClient(
              gsEndpoints, pki.client("account-service"), factory, NAMESPACE)) {
        accountTerminalRead.init();
        var accountOwnerRead =
            accountTerminalRead.read(
                new AccountInitialAdmissionSettlementReadInput(
                    UUID.randomUUID(), accountProtection));
        assertThat(accountOwnerRead.terminal()).isEqualTo(worldTerminal);
        var accountSettlementOwner =
            new AccountStartSessionAdmissionProtectionSettlementService(
                new AccountStartSessionAdmissionProtectionRepository(issuer.sourceDsl()),
                accountTerminalRead,
                accountManager(issuer.sourceDsl()),
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
        assertThat(accountSettlement.terminal()).isEqualTo(worldTerminal);
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
            issuer
                .sourceDsl()
                .fetchSingle(
                    "SELECT outcome,terminal_bytes,terminal_digest "
                        + "FROM account_start_session_admission_protection_settlements "
                        + "WHERE protection_id=?",
                    accountProtection.accountProtectionId());
        assertThat(storedSettlement.get("outcome", String.class)).isEqualTo("COMMITTED");
        assertThat(storedSettlement.get("terminal_bytes", byte[].class))
            .containsExactly(accountSettlement.canonicalBytes());
        assertThat(storedSettlement.get("terminal_digest", String.class))
            .isEqualTo(accountSettlement.digest());

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
      }

      var finalWorldState = worldHoldOwner.readState(identity, lifecycleRequest).orElseThrow();
      assertThat(finalWorldState.holdStatus().name()).isEqualTo("COMMITTED");
      composeActualEntityWorldRegionPrerequisite(
          gs,
          world,
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

  /**
   * Extends the retained 2973 COMMITTED hold through actual Entity assignment, World placement,
   * authenticated current World reads, and the selected Game Session first-NEW region owners.
   * Account staging and published-policy evidence plus the one-use run capability remain controlled
   * local test inputs; the selected OPEN route and terminal are read from actual Game Session SQL.
   */
  private static void composeActualEntityWorldRegionPrerequisite(
      Database gs,
      Database world,
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

    try (var entityDatabase = database(GS, "entity-management-service", false)) {
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
    var runtimeReference =
        Mockito.mock(CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference.class);
    var runtimeEntityId =
        Mockito.mock(CanonicalPreseededActorRuntimeReferenceClient.RuntimeEntityId.class);
    when(runtimeEntityId.tenantId()).thenReturn(currentTarget.canonicalTenantId());
    when(runtimeEntityId.gameInstanceId())
        .thenReturn(currentTarget.canonicalGameInstanceId().toString());
    when(runtimeEntityId.value()).thenReturn(900_002L);
    when(runtimeReference.workloadNamespace()).thenReturn(currentTarget.targetNamespace());
    when(runtimeReference.runtimeEntityId()).thenReturn(runtimeEntityId);
    when(runtimeReference.projectionGeneration()).thenReturn(2L);
    when(runtimeReference.projectionFence()).thenReturn(2L);
    when(runtimeReference.assignmentUuid()).thenReturn(assignmentId);
    when(runtimeReference.canonicalAccountUuid())
        .thenReturn(UUID.fromString(secondRequest.getCanonicalAccountUuid()));
    when(runtimeReference.characterUuid()).thenReturn(characterId);
    when(runtimeReference.intentDigest()).thenReturn(secondRequest.getAssignmentIntentDigest());
    when(runtimeReference.playableStateNamespaceId())
        .thenReturn(currentTarget.playableStateNamespaceId());
    when(runtimeReference.target()).thenReturn(toWire(assignmentTarget));
    when(runtimeReference.publishedOwnerProofDigest()).thenReturn(currentTarget.ownerProofDigest());
    when(runtimeReference.corePayload())
        .thenReturn(
            net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
                .newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Controlled Second Region Player")
                .build());
    var secondEntityReferenceSelector =
        ReadPreseededActorRuntimeReferenceRequest.newBuilder()
            .setSchemaVersion(1)
            .setAssignmentUuid(secondRequest.getAssignmentUuid())
            .setCanonicalAccountUuid(secondRequest.getCanonicalAccountUuid())
            .setExpectedTarget(secondRequest.getExpectedTarget())
            .setCharacterUuid(secondRequest.getCharacterUuid())
            .setIntentDigest(secondRequest.getAssignmentIntentDigest())
            .build();
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

  private static WorldCanonicalInstancePreparation.Input preparationInputs(
      Database world,
      Database gs,
      StartSessionPostAuthorizationExecutionTuple tuple,
      GameSessionStartSessionOperatorAttemptRepository.AttemptClaim claim,
      UUID tenant)
      throws Exception {
    var intakeRepository = new WorldAuthoredSourceIntakeRepository(world.dsl());
    var versionRepository = new WorldAuthoredVersionIdentityRepository(world.dsl());
    var source = controlledSource("controlled-upstream", tenant);
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
            "PRESEEDED_ONLY",
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
    var identity = pki.server(service);
    var builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
            .sslContext(
                GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                    .trustManager(pki.ca().toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
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

  private static DataSourceTransactionManager accountManager(DSLContext account) {
    // Reuse this fixture's actual schema datasource; no second Account schema or owner rows.
    var provider =
        (org.jooq.impl.DataSourceConnectionProvider) account.configuration().connectionProvider();
    var source = (TransactionAwareDataSourceProxy) provider.dataSource();
    return new DataSourceTransactionManager(Objects.requireNonNull(source.getTargetDataSource()));
  }

  private static int availablePort() throws java.io.IOException {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
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
    migrations.load().migrate();
    var manager = new DataSourceTransactionManager(source);
    var write = new TransactionTemplate(manager);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Database(
        schema,
        DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES),
        manager,
        write);
  }

  private record Database(
      String schema,
      DSLContext dsl,
      DataSourceTransactionManager manager,
      TransactionTemplate write)
      implements AutoCloseable {
    @Override
    public void close() {
      dsl.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
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
            .setRegion(RegionDesignMutation.newBuilder().setName("Selected region"))
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
                    .setDirection("EAST"))
            .build());
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
      String policyJson =
          RealmPolicySource.canonical(
              Map.of(
                  "schemaVersion",
                  1,
                  "worldSlug",
                  source.worldSlug(),
                  "worldDisplayName",
                  source.worldDisplayName(),
                  "realmSlug",
                  request.realmSlug(),
                  "realmDisplayName",
                  request.realmDisplayName(),
                  "visible",
                  request.visible(),
                  "publicProduction",
                  request.publicProduction(),
                  "stateScope",
                  request.stateScope(),
                  "entryPolicy",
                  request.characterCreationPolicy()));
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
            .map(
                kind ->
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
                        ("TEST_ONLY-" + kind).getBytes(StandardCharsets.UTF_8)))
            .toList());
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
