package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.Empty;
import com.google.protobuf.util.JsonFormat;
import integration.net.firedevops.firemud.gamesession.repository.CanonicalHumanPublicationOwnerTestFixtures;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ForwardingServerCall;
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
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture;
import net.firedevops.firemud.accountservice.service.session.AccountOriginalStartSessionAuthorizationProofFixture;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationHistoricalReadGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationHistoricalReadService;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadClient;
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
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.GrpcOriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidenceReadClient;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
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
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import net.firedevops.firemud.gamesession.service.impl.HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.OriginalStartSessionCurrentAttemptReadGrpcService;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImpl;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Current-default owner composition with actual Account StartSession
 * issue/redemption/participation, GS SQL attempts and authenticated current-attempt reads, and
 * World guarded graph application, first-freeze Account-binding retention, PUBLISHED terminal
 * storage/readback, and preparation. The prior publication Account binding, GDS
 * operation/terminal/release and complete-launch inputs, logging claims, creator/environment/signer
 * provisioning, and ephemeral PKI are controlled upstream evidence. This proves World owner storage
 * with those stipulated inputs, not a genuine GDS producer or authority. No genuine Human
 * issuer/environment, registered endpoint, ACTIVE lifecycle, admission or Entity proof is asserted.
 * The small test-only World socket invokes the typed owner directly, not assembly.
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

  @Test
  void actualOwnersPrepareAndReplayOneExactOriginalOperation() throws Exception {
    compose(Refusal.NONE);
  }

  @Test
  void mismatchedOwnerAttemptCannotAcquireParticipationOrMutateWorld() throws Exception {
    compose(Refusal.MISMATCHED_ATTEMPT);
  }

  @Test
  void expiredActualOwnerLeaseCannotAcquireParticipationOrMutateWorld() throws Exception {
    compose(Refusal.EXPIRED_ATTEMPT);
  }

  private enum Refusal {
    NONE,
    MISMATCHED_ATTEMPT,
    EXPIRED_ATTEMPT
  }

  private void compose(Refusal refusal) throws Exception {
    UUID tenant = UUID.randomUUID();
    try (var account =
            new AccountControlUiOriginalOrderFixture(
                ACCOUNT.getJdbcUrl(),
                ACCOUNT.getUsername(),
                ACCOUNT.getPassword(),
                REDIS.getHost(),
                REDIS.getMappedPort(6379),
                temporary.resolve("account"),
                tenant);
        var world = database(WORLD, "world-management-service", true);
        var gs = database(GS, "game-session-service", false)) {
      var issuer =
          new AccountOriginalStartSessionAuthorizationProofFixture(
              account, temporary.resolve("issuer"), NAMESPACE);
      Duration lease =
          refusal == Refusal.EXPIRED_ATTEMPT ? Duration.ofSeconds(30) : Duration.ofMinutes(5);
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
      long reservationClaimFence = 9_007_199_254_740_993L;
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
      var pki = new WorldOriginalStartSessionPreparationProofPki(temporary.resolve("pki"));
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
      Server gsServer =
          server(
              "game-session-service",
              pki,
              capturingCurrentAttemptReceiver,
              new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(
                  new CanonicalGameInstanceLaunchAssociationRepository(gs.dsl(), NAMESPACE),
                  NAMESPACE));
      var gsEndpoints = new ServiceEndpointsProperties();
      gsEndpoints.setGameSessionService("localhost:" + gsServer.getPort());
      try (var currentAttempt =
          new GrpcOriginalStartSessionCurrentAttemptClient(
              gsEndpoints, pki.client("account-service"), factory, NAMESPACE)) {
        currentAttempt.init();
        if (refusal == Refusal.NONE) {
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
          assertThat(authenticatedCurrent.accountRedemptionProjection())
              .containsExactly(tuple.canonicalBytes());
        }

        Server accountServer =
            server(
                "account-service",
                pki,
                new AccountStartSessionWorldParticipationAcquisitionGrpcService(
                    issuer.worldParticipationAcquisitionService(currentAttempt), NAMESPACE),
                new AccountStartSessionWorldParticipationHistoricalReadGrpcService(
                    new AccountStartSessionWorldParticipationHistoricalReadService(
                        new AccountStartSessionWorldParticipationRepository(issuer.sourceDsl()),
                        accountManager(issuer.sourceDsl()),
                        NAMESPACE),
                    NAMESPACE));
        var accountEndpoints = new ServiceEndpointsProperties();
        accountEndpoints.setAccountService("localhost:" + accountServer.getPort());
        try (var acquisition =
                new AccountStartSessionWorldParticipationAcquisitionClient(
                    accountEndpoints, pki.client("world-management-service"), factory, NAMESPACE);
            var historicalAccount =
                new AccountStartSessionWorldParticipationHistoricalReadClient(
                    accountEndpoints, pki.client("world-management-service"), factory, NAMESPACE);
            var historicalGs =
                new HistoricalOriginalStartSessionOwnerEvidenceReadClient(
                    gsEndpoints, pki.client("world-management-service"), factory, NAMESPACE)) {
          acquisition.init();
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
          Server worldServer = server("world-management-service", pki, () -> socket);
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
                    "world_canonical_instance_execution");
            Map<String, List<String>> accountBefore =
                rows(
                    issuer.sourceDsl(),
                    "account_start_session_operator_authorizations",
                    "account_start_session_world_participations",
                    "account_start_session_world_original_attempt_evidence",
                    "account_start_session_world_participation_sources",
                    "account_start_session_world_participation_settlements");
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
            if (refusal == Refusal.NONE) {
              // Exercise lifecycle readback with boxed room IDs outside the Long cache range.
              world
                  .dsl()
                  .execute(
                      "SELECT setval((current_schema() || '.room_instance_id_seq')::regclass, GREATEST((SELECT last_value FROM room_instance_id_seq), 127), true)");
            }
            if (refusal != Refusal.NONE) {
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
            var read =
                new WorldCanonicalInstanceLifecycleReadRepository(
                        world.dsl(), world.manager(), association)
                    .read(
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
                            evidence.releaseAttestationEvidenceDigest()))
                    .orElseThrow();
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
            // Historical recovery uses only actual owner reads, without acquisition or renewal.
            // This case does not wait for expiry and establishes no ACTIVE/admission or settlement.
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
    var identity = pki.server(service);
    var builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                    .trustManager(pki.ca().toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
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

  private static void shutdown(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static Map<String, List<String>> rows(DSLContext dsl, String... tables) {
    var result = new TreeMap<String, List<String>>();
    for (String table : tables)
      result.put(
          table,
          dsl.fetch(
                  "SELECT row_to_json(t)::text AS body FROM "
                      + table
                      + " t ORDER BY row_to_json(t)::text")
              .getValues("body", String.class));
    return result;
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
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(locations.toArray(String[]::new))
        .load()
        .migrate();
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
