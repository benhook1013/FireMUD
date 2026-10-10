package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountOriginalStartSessionAuthorizationProofFixture.OwnerAttemptLifecycle;
import net.firedevops.firemud.accountservice.service.session.AccountOriginalStartSessionAuthorizationProofFixture.OwnerClaim;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService.AcquisitionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.GrpcOriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.service.impl.OriginalStartSessionCurrentAttemptReadGrpcService;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse;
import net.firedevops.firemud.loggingadmin.StartSessionReservationMutationTestFixtures;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimEvidence;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimPurpose;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import net.firedevops.firemud.loggingadmin.service.impl.StartSessionReservationEvidenceGrpcService;
import net.firedevops.firemud.loggingadmin.service.impl.StartSessionReservationEvidenceLeafApproval;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical Logging claim-read, Account issuer, and World acquisition proof. The Logging mutation
 * capability is provided only by its run-owned-PostgreSQL test fixture, and the Account issuer and
 * World caller remain in-process test seams; this point-in-time read does not prove continuous
 * cross-service protection of later Account or Game Session commits.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountOriginalStartSessionWorldAcquisitionPostgresIntegrationTest {
  private static final String NAMESPACE = "original-world-acquisition-proof";
  private static final String WORLD_PEER_URI =
      "spiffe://firemud/ns/original-world-acquisition-proof/sa/world-management-service";
  private static final String LOGGING_READ_METHOD =
      "logging_admin.v1.StartSessionReservationEvidenceService/ReadCurrentClaimEvidence";
  private static final long GAME_TEMPLATE_ID = 42L;
  private static final Network NETWORK = Network.newNetwork();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> gameSessionPostgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> loggingPostgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("original-world-acquisition-primary")
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
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
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
              "original-world-acquisition-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void realOriginalIssueRedemptionAndAcquisitionRetainImmutableObservationAndSources()
      throws Exception {
    var pki =
        TestPki.create(Files.createDirectories(temporary.resolve("original-start-session-pki")));
    var loggingStore = loggingReservationStore();
    var loggingReadRequests = new CopyOnWriteArrayList<ReadCurrentClaimEvidenceRequest>();
    var loggingReadResponses = new CopyOnWriteArrayList<ReadCurrentClaimEvidenceResponse>();
    Path approvedAccountLeaves = temporary.resolve("test-only-approved-account-leaves.txt");
    Files.writeString(
        approvedAccountLeaves,
        "active=" + certificateSha256(pki.accountClient().certificate()) + "\n",
        StandardCharsets.US_ASCII);
    Server loggingServer =
        loggingClaimServer(
            pki,
            loggingStore.service(),
            approvedAccountLeaves,
            loggingReadRequests,
            loggingReadResponses);
    try {
      var claimClient = currentClaimGrpcClient(loggingServer.getPort(), pki);
      try (claimClient) {
        // Invoke the production client's Spring @PostConstruct lifecycle for this direct fixture.
        ReflectionTestUtils.invokeMethod(claimClient, "init");
        var gameSessionStore = gameSessionAttemptStore();
        var readService =
            new OriginalStartSessionCurrentAttemptReadGrpcService(
                gameSessionStore.repository(), gameSessionStore.transactionManager(), NAMESPACE);
        Server gameSessionServer = currentAttemptServer(pki, readService);
        try (var grpcClient = currentAttemptGrpcClient(gameSessionServer.getPort(), pki)) {
          grpcClient.init();
          try (var controlUi =
              new AccountControlUiOriginalOrderFixture(
                  postgres.getJdbcUrl(),
                  postgres.getUsername(),
                  postgres.getPassword(),
                  redis.getHost(),
                  redis.getMappedPort(6379),
                  temporary)) {
            prepareOriginalCreator(controlUi);
            var proof =
                new AccountOriginalStartSessionAuthorizationProofFixture(
                    controlUi, temporary, NAMESPACE, claimClient);
            UUID accountId = proof.creatorAccountId();
            UUID tenantId = controlUi.tenantId();
            String controlPlaneRequestId = "original-world-acquisition-" + UUID.randomUUID();
            UUID gameInstanceId = UUID.randomUUID();
            AtomicReference<AttemptClaim> reservedGameSessionClaim = new AtomicReference<>();
            AtomicReference<OwnerClaim> gameSessionOwnerClaim = new AtomicReference<>();
            AtomicReference<byte[]> reservedTupleBytes = new AtomicReference<>();

            var action =
                new StartSessionOperatorAction(
                    StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                    StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                    new StartSessionOperatorAction.Scope(tenantId, NAMESPACE),
                    new StartSessionOperatorAction.Target(GAME_TEMPLATE_ID, accountId),
                    StartSessionOperatorAction.ExpectedVersion.ABSENT,
                    new StartSessionOperatorAction.Mutation(
                        StartSessionOperatorAction.ClientIp.absent()),
                    "real Account original World acquisition integration proof");
            StartSessionPreAuthorizationReservationTuple loggingTuple;
            SessionContext.setContext(
                accountId.toString(),
                List.of(),
                Map.of(tenantId.toString(), List.of("tenantAdmin")),
                false,
                null,
                null);
            try {
              loggingTuple =
                  StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
                      controlPlaneRequestId, action);
            } finally {
              SessionContext.clear();
            }
            var commonTuple =
                net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
                    .fromCanonicalJson(loggingTuple.canonicalJson());
            assertThat(loggingTuple.isAuthorityDerived()).isTrue();
            assertThat(loggingTuple.actor().accountId()).isEqualTo(accountId);

            var defaultRepository =
                new StartSessionPreAuthorizationReservationRepository(loggingStore.dsl());
            var defaultService =
                new StartSessionPreAuthorizationReservationService(defaultRepository);
            assertThatThrownBy(() -> defaultService.acquire(loggingTuple))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("mutations are disabled for production construction");
            assertThatThrownBy(
                    () ->
                        defaultRepository.acquire(
                            loggingTuple,
                            UUID.randomUUID(),
                            System.currentTimeMillis(),
                            System.currentTimeMillis()
                                + StartSessionPreAuthorizationReservationService.CLAIM_TTL
                                    .toMillis()))
                .isInstanceOf(UnsupportedOperationException.class);

            var acquired = loggingStore.service().acquire(loggingTuple);
            assertThat(acquired.newlyAcquired()).isTrue();
            assertThat(acquired.claim()).isNotNull();
            assertThat(acquired.snapshot().state()).isEqualTo(State.RESERVED);
            assertThat(acquired.snapshot().phase()).isEqualTo(Phase.ACCOUNT_AUTHORIZATION);
            ClaimEvidence claim = acquired.claim().claimEvidence();
            assertThat(claim.reservationOwnerId()).isEqualTo(claim.currentClaimOwnerId());
            assertThat(claim.reservationClaimFence()).isEqualTo(1L);
            assertThat(claim.currentClaimFence()).isEqualTo(1L);

            var duplicateAcquire = loggingStore.service().acquire(loggingTuple);
            assertThat(duplicateAcquire.newlyAcquired()).isFalse();
            assertThat(duplicateAcquire.claim()).isNull();
            assertThat(duplicateAcquire.snapshot()).isEqualTo(acquired.snapshot());
            assertThat(loggingReadRequests).isEmpty();

            var pending = loggingStore.service().markAuthorizationPending(acquired.claim());
            assertThat(pending.mayDispatchAccountAuthorization()).isTrue();
            assertThat(pending.snapshot().phase()).isEqualTo(Phase.ACCOUNT_AUTHORIZATION);
            assertThat(pending.snapshot().state()).isEqualTo(State.AUTHORIZATION_PENDING);
            assertThat(pending.snapshot().claimState()).isEqualTo(ClaimState.ACTIVE);
            assertThat(pending.snapshot().claimPurpose()).isEqualTo(ClaimPurpose.ORIGINAL);
            assertThat(pending.snapshot().reservationClaimFence())
                .isEqualTo(claim.reservationClaimFence());
            assertThat(pending.snapshot().claimFence()).isEqualTo(claim.currentClaimFence());
            assertThat(pending.snapshot().claimExpiresAtEpochMillis())
                .isGreaterThan(System.currentTimeMillis());
            Record pendingRow = loggingStore.reservationRow(controlPlaneRequestId);
            assertThat(pendingRow.get("pre_authorization_tuple_json", String.class))
                .isEqualTo(loggingTuple.canonicalJson());
            assertThat(pendingRow.get("mutation_digest", String.class))
                .isEqualTo(loggingTuple.mutationDigest());
            assertThat(pendingRow.get("phase", String.class)).isEqualTo("ACCOUNT_AUTHORIZATION");
            assertThat(pendingRow.get("state", String.class)).isEqualTo("AUTHORIZATION_PENDING");
            assertThat(pendingRow.get("reservation_owner_id", UUID.class))
                .isEqualTo(claim.reservationOwnerId());
            assertThat(pendingRow.get("reservation_claim_fence", Long.class))
                .isEqualTo(claim.reservationClaimFence());
            assertThat(pendingRow.get("claim_owner_id", UUID.class))
                .isEqualTo(claim.currentClaimOwnerId());
            assertThat(pendingRow.get("claim_fence", Long.class))
                .isEqualTo(claim.currentClaimFence());
            assertThat(pendingRow.get("claim_state", String.class)).isEqualTo("ACTIVE");
            assertThat(pendingRow.get("claim_purpose", String.class)).isEqualTo("ORIGINAL");
            assertThat(
                    pendingRow.get("claim_expires_at_epoch_ms", Long.class)
                        - pendingRow.get("created_at_epoch_ms", Long.class))
                .isEqualTo(StartSessionPreAuthorizationReservationService.CLAIM_TTL.toMillis());
            String pendingReservationRow = loggingStore.reservationRowJson(controlPlaneRequestId);

            // The winning transition is the only authorization dispatch point. This direct Account
            // fixture call follows immediately; a duplicate acquisition above had no claim to
            // dispatch.
            var original =
                proof.issueAndRedeemOriginal(
                    controlPlaneRequestId,
                    action,
                    claim.reservationOwnerId(),
                    claim.reservationClaimFence(),
                    new OwnerAttemptLifecycle() {
                      @Override
                      public OwnerClaim reserve(StartSessionPostAuthorizationExecutionTuple tuple) {
                        AttemptClaim claim = gameSessionStore.reserve(tuple);
                        reservedGameSessionClaim.set(claim);
                        reservedTupleBytes.set(tuple.canonicalBytes());
                        OwnerClaim ownerClaim =
                            new OwnerClaim(
                                claim.ownerAttemptId(),
                                claim.ownerMutationId(),
                                claim.ownerFence());
                        gameSessionOwnerClaim.set(ownerClaim);
                        return ownerClaim;
                      }

                      @Override
                      public void attachAccountRedemptionProjection(
                          OwnerClaim ownerClaim,
                          StartSessionPostAuthorizationExecutionTuple tuple) {
                        AttemptClaim claim =
                            java.util.Objects.requireNonNull(
                                reservedGameSessionClaim.get(),
                                "exact Game Session reservation must precede Account redemption");
                        assertThat(ownerClaim.ownerAttemptId()).isEqualTo(claim.ownerAttemptId());
                        assertThat(ownerClaim.ownerMutationId()).isEqualTo(claim.ownerMutationId());
                        assertThat(ownerClaim.ownerFence()).isEqualTo(claim.ownerFence());
                        assertThat(tuple.canonicalBytes())
                            .containsExactly(reservedTupleBytes.get());
                        var authority =
                            StartSessionAuthorityEvidenceBundle.decode(
                                tuple.authorityEvidenceBundleBytes());
                        var projection =
                            new AccountRedemptionProjection(
                                tuple.authorizationReferenceFingerprint(),
                                tuple.authorityEvidenceBundleBytes(),
                                authority.issuanceOperationId(),
                                Long.parseLong(tuple.issuanceFence()));
                        assertThat(projection.canonicalBytes())
                            .containsExactly(
                                StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));
                        var attached = gameSessionStore.attach(claim, projection);
                        assertThat(attached.accountRedemptionProjection())
                            .containsExactly(projection.canonicalBytes());
                      }
                    });
            assertThat(original.originalTuple().preAuthorizationTuple().canonicalJson())
                .isEqualTo(loggingTuple.canonicalJson());
            assertThat(original.originalTuple().preAuthorizationTuple().canonicalJson())
                .isEqualTo(commonTuple.canonicalJson());
            assertThat(loggingReadRequests).hasSize(1);
            assertThat(loggingReadResponses).hasSize(1);
            ReadCurrentClaimEvidenceRequest readRequest = loggingReadRequests.getFirst();
            ReadCurrentClaimEvidenceResponse readResponse = loggingReadResponses.getFirst();
            assertThat(readRequest.getControlPlaneRequestId()).isEqualTo(controlPlaneRequestId);
            assertThat(readRequest.getPreAuthorizationTupleJson().toByteArray())
                .containsExactly(loggingTuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
            assertThat(
                    StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
                            readRequest.getPreAuthorizationTupleJson().toStringUtf8())
                        .mutationDigest())
                .isEqualTo(loggingTuple.mutationDigest());
            assertThat(readRequest.getReservationOwnerId())
                .isEqualTo(claim.reservationOwnerId().toString());
            assertThat(readRequest.getReservationClaimFence())
                .isEqualTo(claim.reservationClaimFence());
            assertThat(readRequest.getClaimOwnerId())
                .isEqualTo(claim.currentClaimOwnerId().toString());
            assertThat(readRequest.getClaimFence()).isEqualTo(claim.currentClaimFence());
            assertThat(readRequest.getPurpose())
                .isEqualTo(
                    StartSessionReservationEvidencePurpose
                        .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE);
            assertThat(readResponse.getControlPlaneRequestId()).isEqualTo(controlPlaneRequestId);
            assertThat(readResponse.getPreAuthorizationTupleJson().toByteArray())
                .containsExactly(loggingTuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
            assertThat(readResponse.getMutationDigest()).isEqualTo(loggingTuple.mutationDigest());
            assertThat(readResponse.getReservationOwnerId())
                .isEqualTo(claim.reservationOwnerId().toString());
            assertThat(readResponse.getReservationClaimFence())
                .isEqualTo(claim.reservationClaimFence());
            assertThat(readResponse.getClaimOwnerId())
                .isEqualTo(claim.currentClaimOwnerId().toString());
            assertThat(readResponse.getClaimFence()).isEqualTo(claim.currentClaimFence());
            assertThat(readResponse.getPurpose())
                .isEqualTo(
                    StartSessionReservationEvidencePurpose
                        .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE);
            assertThat(readResponse.getClaimExpiresAtEpochMillis())
                .isEqualTo(pending.snapshot().claimExpiresAtEpochMillis());
            assertThat(readResponse.getObservedAtEpochMillis())
                .isLessThan(readResponse.getClaimExpiresAtEpochMillis());
            var persistedPending = loggingStore.service().findExact(loggingTuple).orElseThrow();
            assertThat(persistedPending).isEqualTo(pending.snapshot());
            assertThat(persistedPending.phase()).isEqualTo(Phase.ACCOUNT_AUTHORIZATION);
            assertThat(persistedPending.state()).isEqualTo(State.AUTHORIZATION_PENDING);
            assertThat(persistedPending.reservationClaimFence())
                .isEqualTo(claim.reservationClaimFence());
            assertThat(persistedPending.claimFence()).isEqualTo(claim.currentClaimFence());
            assertThat(persistedPending.claimExpiresAtEpochMillis())
                .isEqualTo(pending.snapshot().claimExpiresAtEpochMillis());
            assertThat(persistedPending.claimPurpose()).isEqualTo(ClaimPurpose.ORIGINAL);
            assertThat(loggingStore.reservationRowJson(controlPlaneRequestId))
                .isEqualTo(pendingReservationRow);
            var duplicateAfterIssue = loggingStore.service().acquire(loggingTuple);
            assertThat(duplicateAfterIssue.newlyAcquired()).isFalse();
            assertThat(duplicateAfterIssue.claim()).isNull();
            assertThat(duplicateAfterIssue.snapshot()).isEqualTo(persistedPending);
            assertThat(loggingReadRequests)
                .as("an exact duplicate observes the row without a second Account claim read")
                .hasSize(1);
            assertThat(loggingStore.reservationRowJson(controlPlaneRequestId))
                .isEqualTo(pendingReservationRow);
            OwnerClaim ownerClaim =
                java.util.Objects.requireNonNull(
                    gameSessionOwnerClaim.get(),
                    "actual Game Session owner claim must be retained");
            UUID gameSessionAttemptId = ownerClaim.ownerAttemptId();
            UUID gameSessionMutationId = ownerClaim.ownerMutationId();
            long gameSessionOwnerFence = ownerClaim.ownerFence();
            assertThat(original.gameSessionOwnerAttemptId()).isEqualTo(gameSessionAttemptId);
            assertThat(original.gameSessionOwnerFence()).isEqualTo(gameSessionOwnerFence);
            assertThat(original.originalTuple().preAuthorizationTuple().targetOwner())
                .isEqualTo("game-session-service");

            byte[] redemptionProjection =
                StartSessionAccountRedemptionProjection.fromOriginalTuple(original.originalTuple());
            List<Request> currentAttemptReads = new ArrayList<>();
            List<Result> currentAttemptResults = new ArrayList<>();
            OriginalStartSessionCurrentAttemptClient currentAttemptClient =
                request -> {
                  currentAttemptReads.add(request);
                  Result observation = grpcClient.read(request);
                  currentAttemptResults.add(observation);
                  return observation;
                };
            var acquisitionService =
                proof.worldParticipationAcquisitionService(currentAttemptClient);
            String preparationInput =
                preparationInput(
                    NAMESPACE, tenantId, controlPlaneRequestId, gameInstanceId, GAME_TEMPLATE_ID);
            var acquisitionRequest =
                new AcquisitionRequest(
                    original.originalTuple().canonicalBytes(),
                    gameSessionMutationId,
                    gameSessionAttemptId,
                    gameSessionOwnerFence,
                    gameInstanceId,
                    preparationInput,
                    digest(preparationInput));

            String gameSessionRowBeforeReads =
                gameSessionStore.ownerAttemptRowJson(controlPlaneRequestId);
            var first = withWorldPeer(() -> acquisitionService.acquire(acquisitionRequest));
            StoredParticipation firstParticipation = first.participation();
            StoredEvidence firstObservation = first.originalAttemptEvidence();
            Result firstGameSessionObservation = currentAttemptResults.getFirst();
            assertThat(firstParticipation.participationId()).isNotEqualTo(new UUID(0L, 0L));
            assertThat(firstParticipation.participationFence()).isPositive();
            assertThat(firstParticipation.originalPostAuthorizationTuple())
                .containsExactly(original.originalTuple().canonicalBytes());
            assertThat(firstParticipation.gameSessionOwnerAttemptId())
                .isEqualTo(gameSessionAttemptId);
            assertThat(firstParticipation.gameSessionOwnerFence()).isEqualTo(gameSessionOwnerFence);
            assertThat(firstParticipation.sources()).isNotEmpty();
            assertThat(firstObservation.gameSessionOwnerMutationId())
                .isEqualTo(gameSessionMutationId);
            assertThat(firstObservation.originalLeaseExpiresAt())
                .isEqualTo(firstGameSessionObservation.originalLeaseExpiresAt());
            assertThat(firstGameSessionObservation.phaseState())
                .isEqualTo("OWNER_EXECUTION_PENDING");
            assertThat(firstGameSessionObservation.request())
                .isEqualTo(currentAttemptReads.getFirst());
            assertThat(firstGameSessionObservation.request().canonicalPostAuthorizationTuple())
                .containsExactly(original.originalTuple().canonicalBytes());
            assertThat(firstGameSessionObservation.request().expectedOwnerAttemptId())
                .isEqualTo(gameSessionAttemptId);
            assertThat(firstGameSessionObservation.request().expectedOwnerMutationId())
                .isEqualTo(gameSessionMutationId);
            assertThat(firstGameSessionObservation.request().expectedOwnerFence())
                .isEqualTo(gameSessionOwnerFence);
            assertThat(firstGameSessionObservation.accountRedemptionProjection())
                .containsExactly(redemptionProjection);
            assertThat(firstGameSessionObservation.originalLeaseExpiresAt()).isAfter(Instant.now());
            assertThat(firstGameSessionObservation.originalLeaseExpiresAt())
                .isBefore(original.accountReferenceExpiresAt());
            Record actualGameSessionRow = gameSessionStore.ownerAttemptRow(controlPlaneRequestId);
            assertThat(actualGameSessionRow.get("target_namespace", String.class))
                .isEqualTo(NAMESPACE);
            assertThat(actualGameSessionRow.get("control_plane_request_id", String.class))
                .isEqualTo(controlPlaneRequestId);
            assertThat(actualGameSessionRow.get("owner_attempt_id", UUID.class))
                .isEqualTo(gameSessionAttemptId);
            assertThat(actualGameSessionRow.get("owner_mutation_id", UUID.class))
                .isEqualTo(gameSessionMutationId);
            assertThat(actualGameSessionRow.get("owner_fence", Long.class))
                .isEqualTo(gameSessionOwnerFence);
            assertThat(actualGameSessionRow.get("phase_state", String.class))
                .isEqualTo("OWNER_EXECUTION_PENDING");
            assertThat(actualGameSessionRow.get("post_authorization_execution_tuple", byte[].class))
                .containsExactly(original.originalTuple().canonicalBytes());
            assertThat(actualGameSessionRow.get("account_redemption_projection", byte[].class))
                .containsExactly(redemptionProjection);
            assertThat(actualGameSessionRow.get("lease_expires_at", OffsetDateTime.class))
                .isEqualTo(
                    OffsetDateTime.ofInstant(
                        firstGameSessionObservation.originalLeaseExpiresAt(), ZoneOffset.UTC));
            assertThat(firstObservation.originalResponseBytes())
                .containsExactly(
                    OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
                            firstGameSessionObservation)
                        .toByteArray());

            DSLContext dsl = proof.sourceDsl();
            assertPersistedAcquisition(
                dsl, original.originalTuple(), firstParticipation, firstObservation);

            // The actual Game Session observation is repeated with a newly generated correlation.
            // Account's exact-operation retry must preserve the first bytes and participation
            // fence.
            var exactRetry = withWorldPeer(() -> acquisitionService.acquire(acquisitionRequest));
            assertThat(currentAttemptReads).hasSize(2);
            assertThat(currentAttemptReads.get(0).readRequestId())
                .isNotEqualTo(currentAttemptReads.get(1).readRequestId());
            assertThat(currentAttemptResults).hasSize(2);
            assertThat(currentAttemptResults.get(1).request())
                .isEqualTo(currentAttemptReads.get(1));
            assertThat(currentAttemptResults.get(1).accountRedemptionProjection())
                .containsExactly(redemptionProjection);
            assertThat(currentAttemptResults.get(1).originalLeaseExpiresAt())
                .isEqualTo(firstGameSessionObservation.originalLeaseExpiresAt());
            assertThat(exactRetry.participation().participationId())
                .isEqualTo(firstParticipation.participationId());
            assertThat(exactRetry.participation().participationFence())
                .isEqualTo(firstParticipation.participationFence());
            assertThat(exactRetry.originalAttemptEvidence().originalResponseBytes())
                .containsExactly(firstObservation.originalResponseBytes());
            assertThat(exactRetry.originalAttemptEvidence().result().request().readRequestId())
                .isEqualTo(currentAttemptReads.get(0).readRequestId());
            assertPersistedAcquisition(
                dsl, original.originalTuple(), firstParticipation, firstObservation);
            assertThat(gameSessionStore.ownerAttemptRowJson(controlPlaneRequestId))
                .isEqualTo(gameSessionRowBeforeReads);

            int retainedParticipationCount =
                dsl.fetchCount(DSL.table("account_start_session_world_participations"));
            int retainedSourceCount =
                dsl.fetchCount(DSL.table("account_start_session_world_participation_sources"));
            int retainedObservationCount =
                dsl.fetchCount(DSL.table("account_start_session_world_original_attempt_evidence"));

            var changedTuple =
                StartSessionPostAuthorizationExecutionTuple.createHuman(
                    original.originalTuple().preAuthorizationTuple(),
                    original.originalTuple().authenticatedWorkloadIdentity(),
                    "arfp/v1/changed-proof/" + "c".repeat(64),
                    original.originalTuple().reservationOwnerId(),
                    original.originalTuple().reservationClaimFence(),
                    original.originalTuple().authorityEvidenceBundleBytes(),
                    original.originalTuple().bundleReference());
            var changedTupleRequest =
                new AcquisitionRequest(
                    changedTuple.canonicalBytes(),
                    gameSessionMutationId,
                    gameSessionAttemptId,
                    gameSessionOwnerFence,
                    gameInstanceId,
                    preparationInput,
                    digest(preparationInput));
            assertFailedPrecondition(
                () -> withWorldPeer(() -> acquisitionService.acquire(changedTupleRequest)));

            var changedOwnerFenceRequest =
                new AcquisitionRequest(
                    original.originalTuple().canonicalBytes(),
                    gameSessionMutationId,
                    gameSessionAttemptId,
                    gameSessionOwnerFence + 1,
                    gameInstanceId,
                    preparationInput,
                    digest(preparationInput));
            assertFailedPrecondition(
                () -> withWorldPeer(() -> acquisitionService.acquire(changedOwnerFenceRequest)));
            assertThat(dsl.fetchCount(DSL.table("account_start_session_world_participations")))
                .isEqualTo(retainedParticipationCount);
            assertThat(
                    dsl.fetchCount(DSL.table("account_start_session_world_participation_sources")))
                .isEqualTo(retainedSourceCount);
            assertThat(
                    dsl.fetchCount(
                        DSL.table("account_start_session_world_original_attempt_evidence")))
                .isEqualTo(retainedObservationCount);
            assertThat(gameSessionStore.ownerAttemptRowJson(controlPlaneRequestId))
                .isEqualTo(gameSessionRowBeforeReads);

            var sourceFixture = controlUi.preparedOriginalCreator().sources();
            String originalRole =
                dsl.fetchSingle("SELECT role FROM accounts WHERE account_uuid = ?", accountId)
                    .get(0, String.class);
            assertThatThrownBy(sourceFixture::changeRoleToAdmin)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(
                    "StartSession World participation remains pending for a required source");
            assertThat(
                    dsl.fetchSingle("SELECT role FROM accounts WHERE account_uuid = ?", accountId)
                        .get(0, String.class))
                .isEqualTo(originalRole);
          }
        } finally {
          stopCurrentAttemptServer(gameSessionServer);
        }
      }
    } finally {
      stopLoggingClaimServer(loggingServer);
    }
  }

  private static GameSessionAttemptStore gameSessionAttemptStore() throws Exception {
    String schema = "account_current_attempt_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(gameSessionPostgres.getJdbcUrl());
    dataSource.setUsername(gameSessionPostgres.getUsername());
    dataSource.setPassword(gameSessionPostgres.getPassword());
    dataSource.setSchema(schema);
    Path migrationDirectory = gameSessionMigrationDirectory();
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations("filesystem:" + migrationDirectory)
        .load()
        .migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    String latestMigration =
        java.util.Objects.requireNonNull(
            java.util.Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT version FROM flyway_schema_history WHERE success "
                            + "ORDER BY installed_rank DESC LIMIT 1"),
                    "Game Session migration history has no successful version")
                .get("version", String.class));
    assertThat(latestMigration).isEqualTo("35");
    var transactionManager = new DataSourceTransactionManager(dataSource);
    var transactions = new TransactionTemplate(transactionManager);
    return new GameSessionAttemptStore(
        dsl,
        transactionManager,
        transactions,
        new GameSessionStartSessionOperatorAttemptRepository(dsl, Duration.ofSeconds(30)));
  }

  private static LoggingReservationStore loggingReservationStore() throws Exception {
    String schema = "account_logging_claim_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(loggingPostgres.getJdbcUrl());
    dataSource.setUsername(loggingPostgres.getUsername());
    dataSource.setPassword(loggingPostgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations("filesystem:" + loggingMigrationDirectory())
        .target(MigrationVersion.fromVersion("4"))
        .load()
        .migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    assertThat(
            dsl.fetch(
                    "SELECT version FROM flyway_schema_history WHERE success "
                        + "ORDER BY installed_rank")
                .getValues("version", String.class))
        .containsExactly("1", "2", "3", "4");
    var mutationScope =
        StartSessionReservationMutationTestFixtures.forRunOwnedPostgres(loggingPostgres, schema);
    return new LoggingReservationStore(dsl, mutationScope.service());
  }

  private static Path loggingMigrationDirectory() throws IOException {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      Path candidate =
          current.resolve("services/logging-admin-service/src/main/resources/db/migration");
      if (Files.isDirectory(candidate)) return candidate.toRealPath();
      Path siblingService =
          current.resolve("../logging-admin-service/src/main/resources/db/migration").normalize();
      if (Files.isDirectory(siblingService)) return siblingService.toRealPath();
      current = current.getParent();
    }
    throw new IOException(
        "Could not locate the Logging/Admin migration directory from this checkout");
  }

  private static Server loggingClaimServer(
      TestPki pki,
      StartSessionPreAuthorizationReservationService reservationService,
      Path approvedAccountLeaves,
      List<ReadCurrentClaimEvidenceRequest> requests,
      List<ReadCurrentClaimEvidenceResponse> responses)
      throws Exception {
    StartSessionReservationEvidenceGrpcService receiver =
        new StartSessionReservationEvidenceGrpcService(
            reservationService,
            new StartSessionReservationEvidenceLeafApproval(approvedAccountLeaves.toString()),
            NAMESPACE) {
          @Override
          public void readCurrentClaimEvidence(
              ReadCurrentClaimEvidenceRequest request,
              StreamObserver<ReadCurrentClaimEvidenceResponse> responseObserver) {
            requests.add(request);
            super.readCurrentClaimEvidence(
                request,
                new StreamObserver<>() {
                  @Override
                  public void onNext(ReadCurrentClaimEvidenceResponse response) {
                    responses.add(response);
                    responseObserver.onNext(response);
                  }

                  @Override
                  public void onError(Throwable failure) {
                    responseObserver.onError(failure);
                  }

                  @Override
                  public void onCompleted() {
                    responseObserver.onCompleted();
                  }
                });
          }
        };
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(
                    pki.loggingAdminServer().certificate().toFile(),
                    pki.loggingAdminServer().privateKey().toFile())
                .trustManager(pki.caCertificate().toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                receiver,
                new AuthTokenInterceptor(
                    new JwtUtil("a".repeat(64), 3_600_000L), Set.of(LOGGING_READ_METHOD)),
                new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static StartSessionReservationEvidenceClient currentClaimGrpcClient(
      int port, TestPki pki) {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setLoggingAdminService("localhost:" + port);
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(pki.accountClient().certificate().toString());
    tls.setPrivateKey(pki.accountClient().privateKey().toString());
    tls.setCaCert(pki.caCertificate().toString());
    return new StartSessionReservationEvidenceClient(
        endpoints,
        tls,
        new GrpcChannelFactory(),
        net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer.noop());
  }

  private static void stopLoggingClaimServer(Server server) throws InterruptedException {
    server.shutdownNow();
    if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Loopback Logging/Admin gRPC server did not terminate");
    }
  }

  private static Path gameSessionMigrationDirectory() throws IOException {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      Path candidate =
          current.resolve("services/game-session-service/src/main/resources/db/migration");
      if (Files.isDirectory(candidate)) return candidate.toRealPath();
      Path siblingService =
          current.resolve("../game-session-service/src/main/resources/db/migration").normalize();
      if (Files.isDirectory(siblingService)) return siblingService.toRealPath();
      current = current.getParent();
    }
    throw new IOException(
        "Could not locate the Game Session migration directory from this checkout");
  }

  private record LoggingReservationStore(
      DSLContext dsl, StartSessionPreAuthorizationReservationService service) {
    String reservationRowJson(String requestId) {
      return java.util.Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT to_jsonb(reservation)::text FROM "
                      + "start_session_pre_authorization_reservations AS reservation "
                      + "WHERE control_plane_request_id = ?",
                  requestId),
              "Logging reservation row")
          .get(0, String.class);
    }

    Record reservationRow(String requestId) {
      return dsl.fetchOne(
          "SELECT * FROM start_session_pre_authorization_reservations "
              + "WHERE control_plane_request_id = ?",
          requestId);
    }
  }

  private static Server currentAttemptServer(
      TestPki pki, OriginalStartSessionCurrentAttemptReadGrpcService readService) throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(
                    pki.gameSessionServer().certificate().toFile(),
                    pki.gameSessionServer().privateKey().toFile())
                .trustManager(pki.caCertificate().toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(readService, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static GrpcOriginalStartSessionCurrentAttemptClient currentAttemptGrpcClient(
      int port, TestPki pki) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("localhost:" + port);
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(pki.accountClient().certificate().toString());
    tls.setPrivateKey(pki.accountClient().privateKey().toString());
    tls.setCaCert(pki.caCertificate().toString());
    return new GrpcOriginalStartSessionCurrentAttemptClient(
        endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
  }

  private static void stopCurrentAttemptServer(Server server) throws InterruptedException {
    server.shutdownNow();
    if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Loopback Game Session gRPC server did not terminate");
    }
  }

  private record GameSessionAttemptStore(
      DSLContext dsl,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      GameSessionStartSessionOperatorAttemptRepository repository) {
    AttemptClaim reserve(StartSessionPostAuthorizationExecutionTuple tuple) {
      var reservation =
          java.util.Objects.requireNonNull(
              transactions.execute(status -> repository.reserve(tuple)),
              "Game Session owner attempt reservation result");
      assertThat(reservation.disposition())
          .isEqualTo(
              GameSessionStartSessionOperatorAttemptRepository.ReservationDisposition
                  .CLAIM_CREATED);
      assertThat(reservation.snapshot().postAuthorizationExecutionTuple())
          .containsExactly(tuple.canonicalBytes());
      return reservation.claim().orElseThrow();
    }

    GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot attach(
        AttemptClaim claim, AccountRedemptionProjection projection) {
      return java.util.Objects.requireNonNull(
          transactions.execute(
              status -> repository.attachAccountRedemptionProjection(claim, projection)),
          "Game Session projection attachment snapshot");
    }

    String ownerAttemptRowJson(String requestId) {
      Record row =
          dsl.fetchOne(
              "SELECT to_jsonb(attempt)::text FROM "
                  + "game_session_start_session_operator_attempt AS attempt "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              requestId);
      return java.util.Objects.requireNonNull(
          java.util.Objects.requireNonNull(row, "Game Session owner attempt row")
              .get(0, String.class));
    }

    Record ownerAttemptRow(String requestId) {
      return dsl.fetchOne(
          "SELECT * FROM game_session_start_session_operator_attempt "
              + "WHERE target_namespace = ? AND control_plane_request_id = ?",
          NAMESPACE,
          requestId);
    }
  }

  private record TestIdentity(Path certificate, Path privateKey) {}

  /** Test-only identities for the Account-to-Logging and Account-to-Game-Session mTLS proofs. */
  private record TestPki(
      Path caCertificate,
      TestIdentity gameSessionServer,
      TestIdentity loggingAdminServer,
      TestIdentity accountClient) {
    private static final String PASSWORD = "test-only-current-attempt-password";

    static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("current-attempt-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD current-attempt integration test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          caStore.toString(),
          "-storepass",
          PASSWORD,
          "-keypass",
          PASSWORD);
      Path caFile = directory.resolve("current-attempt-ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-file",
          caFile.toString(),
          "-rfc");
      return new TestPki(
          caFile,
          issueIdentity(
              directory,
              caStore,
              "game-session-server",
              "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service",
              true),
          issueIdentity(
              directory,
              caStore,
              "logging-admin-server",
              "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
              true),
          issueIdentity(
              directory,
              caStore,
              "account-client",
              "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service",
              false));
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, String alias, String workloadUri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
      String extendedKeyUsage = server ? "serverAuth" : "clientAuth";
      runKeytool(
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=" + alias,
          "-validity",
          "30",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + extendedKeyUsage,
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          PASSWORD,
          "-keypass",
          PASSWORD);
      runKeytool(
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-file",
          request.toString(),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-gencert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-infile",
          request.toString(),
          "-outfile",
          certificate.toString(),
          "-validity",
          "30",
          "-rfc",
          "-ext",
          "BC=ca:false",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + extendedKeyUsage,
          "-ext",
          "SAN=" + san);

      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, PASSWORD.toCharArray());
      }
      byte[] encodedPrivateKey =
          java.util.Objects.requireNonNull(
                  keyStore.getKey(alias, PASSWORD.toCharArray()),
                  "Expected ephemeral current-attempt private key")
              .getEncoded();
      Path privateKey = directory.resolve(alias + ".key");
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encodedPrivateKey);
      Files.writeString(
          privateKey,
          "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n",
          StandardCharsets.US_ASCII);
      java.util.Arrays.fill(encodedPrivateKey, (byte) 0);
      return new TestIdentity(certificate, privateKey);
    }

    private static void runKeytool(String... arguments) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      var command = new ArrayList<String>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Ephemeral current-attempt test certificate timed out");
      }
      process.getInputStream().transferTo(output);
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "Current-attempt test keytool failed: " + output.toString(StandardCharsets.UTF_8));
      }
    }
  }

  private static String certificateSha256(Path certificate) throws Exception {
    try (var input = Files.newInputStream(certificate)) {
      var parsed = CertificateFactory.getInstance("X.509").generateCertificate(input);
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(parsed.getEncoded()));
    }
  }

  private static void assertPersistedAcquisition(
      DSLContext dsl,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      StoredParticipation participation,
      StoredEvidence observation) {
    assertThat(dsl.fetchCount(DSL.table("account_start_session_world_participations")))
        .isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("account_start_session_world_participation_sources")))
        .isEqualTo(participation.sources().size());
    assertThat(dsl.fetchCount(DSL.table("account_start_session_world_original_attempt_evidence")))
        .isEqualTo(1);

    Record participationRow =
        dsl.fetchOne(
            "SELECT * FROM account_start_session_world_participations WHERE participation_id = ?",
            participation.participationId());
    assertThat(participationRow).isNotNull();
    assertThat(participationRow.get("participation_fence", Long.class))
        .isEqualTo(participation.participationFence());
    assertThat(participationRow.get("original_post_authorization_tuple", byte[].class))
        .containsExactly(originalTuple.canonicalBytes());
    assertThat(participationRow.get("game_session_owner_attempt_id", UUID.class))
        .isEqualTo(participation.gameSessionOwnerAttemptId());
    assertThat(participationRow.get("game_session_owner_fence", Long.class))
        .isEqualTo(participation.gameSessionOwnerFence());
    assertThat(participationRow.get("preparation_input_json", String.class))
        .isEqualTo(participation.preparationInputJson());
    assertThat(participationRow.get("preparation_input_digest", String.class))
        .isEqualTo(participation.preparationInputDigest());

    var sourceRows =
        dsl.fetch(
            "SELECT source_key, source_evidence FROM account_start_session_world_participation_sources "
                + "WHERE participation_id = ? ORDER BY source_key",
            participation.participationId());
    var expectedSources =
        participation.sources().stream()
            .sorted(Comparator.comparing(source -> source.key()))
            .toList();
    assertThat(sourceRows).hasSize(expectedSources.size());
    for (int index = 0; index < expectedSources.size(); index++) {
      assertThat(sourceRows.get(index).get("source_key", String.class))
          .isEqualTo(expectedSources.get(index).key());
      assertThat(sourceRows.get(index).get("source_evidence", byte[].class))
          .containsExactly(expectedSources.get(index).canonicalBytes());
    }

    Record evidenceRow =
        dsl.fetchOne(
            "SELECT game_session_owner_mutation_id, game_session_owner_attempt_id, "
                + "game_session_owner_fence, original_lease_expires_at, original_response_bytes, "
                + "original_response_digest FROM account_start_session_world_original_attempt_evidence "
                + "WHERE participation_id = ?",
            participation.participationId());
    assertThat(evidenceRow).isNotNull();
    assertThat(evidenceRow.get("game_session_owner_mutation_id", UUID.class))
        .isEqualTo(observation.gameSessionOwnerMutationId());
    assertThat(evidenceRow.get("game_session_owner_attempt_id", UUID.class))
        .isEqualTo(participation.gameSessionOwnerAttemptId());
    assertThat(evidenceRow.get("game_session_owner_fence", Long.class))
        .isEqualTo(participation.gameSessionOwnerFence());
    assertThat(evidenceRow.get("original_lease_expires_at", OffsetDateTime.class))
        .isEqualTo(OffsetDateTime.ofInstant(observation.originalLeaseExpiresAt(), ZoneOffset.UTC));
    assertThat(evidenceRow.get("original_response_bytes", byte[].class))
        .containsExactly(observation.originalResponseBytes());
    assertThat(evidenceRow.get("original_response_digest", String.class))
        .isEqualTo(observation.originalResponseDigest());

    try {
      var decoded =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
              observation.result().request(),
              ReadOriginalStartSessionCurrentAttemptResponse.parseFrom(
                  evidenceRow.get("original_response_bytes", byte[].class)));
      assertThat(decoded.request().readRequestId())
          .isEqualTo(observation.result().request().readRequestId());
      assertThat(decoded.accountRedemptionProjection())
          .containsExactly(observation.result().accountRedemptionProjection());
      assertThat(decoded.originalLeaseExpiresAt()).isEqualTo(observation.originalLeaseExpiresAt());
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw new AssertionError(
          "Retained current-attempt response must be a complete wire value", malformed);
    }
  }

  private static void prepareOriginalCreator(AccountControlUiOriginalOrderFixture controlUi) {
    UUID versionId = UUID.randomUUID();
    String tenantKey = "world-acquisition-proof-tenant";
    var binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                controlUi.tenantId(), versionId, 17L, tenantKey, 23L, tenantKey, "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULES",
                    versionId.toString(),
                    "GAMEPLAY_RULES",
                    "all",
                    "0")));
    controlUi.prepareOriginalDraftOrder(binding, NAMESPACE);
  }

  private static String preparationInput(
      String namespace, UUID tenantId, String requestId, UUID gameInstanceId, long templateId)
      throws Exception {
    String descriptorJson = JSON.writeValueAsString(Map.of("gameTemplateId", templateId));
    return JSON.writeValueAsString(
        Map.of(
            "schemaVersion",
            1,
            "identity",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                "original-world-acquisition-proof"),
            "gameSessionReadRequest",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                "original-world-acquisition-proof"),
            "gameSessionReadEvidence",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                "original-world-acquisition-proof",
                "descriptorJson",
                descriptorJson),
            "launchBinding",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "worldSlug",
                "original-world-acquisition-proof")));
  }

  private static String digest(String value) throws Exception {
    return "sha256:"
        + HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static void assertFailedPrecondition(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
  }

  private static <T> T withWorldPeer(java.util.function.Supplier<T> action) {
    try (var ignored = AccountControlUiOwnerSourcesFixture.withPeer(WORLD_PEER_URI)) {
      return action.get();
    }
  }
}
