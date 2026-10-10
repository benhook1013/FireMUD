package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.Candidate;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredSettlement;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.KeyMaterial;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.Snapshot;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL proof of Account's immutable World participation storage behind its actual signer,
 * current-actor, V128 capture, and original operator issuance/redemption owners.
 *
 * <p>The test stipulates Logging's claim response, the Game Session attempt/fence supplied to its
 * real Account redemption, and the World terminal producer value. Its in-process peer contexts do
 * not prove mTLS transport, authenticated Game Session production, a genuine World producer, or
 * runtime activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountStartSessionWorldParticipationPostgresIntegrationTest {
  private static final String NAMESPACE = "control-ui-owner-proof";
  private static final long ORIGINAL_GAME_TEMPLATE_ID = 42L;
  private static final String LOGGING_PEER_URI =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/logging-admin-service";
  private static final String GAME_SESSION_PEER_URI =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/game-session-service";
  private static final String WORLD_PEER_URI =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/world-management-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("world-participation-primary")
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
              "world-participation-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void realAccountCaptureAndRedemptionBackExactParticipationAndTypedSettlement() throws Exception {
    try (var controlUi =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var creator = controlUi.issueCreator();
      var account = creator.sources();
      var actorService = creator.actors();
      String requestId = "world-participation-" + UUID.randomUUID();
      UUID reservationOwnerId = UUID.randomUUID();
      long reservationClaimFence = 31L;
      UUID gameSessionAttemptId = UUID.randomUUID();
      long gameSessionOwnerFence = 47L;
      UUID gameInstanceId = UUID.randomUUID();
      String preparationInputJson =
          preparationInput(
              NAMESPACE,
              account.tenant,
              requestId,
              gameInstanceId,
              "owner-proof-world",
              ORIGINAL_GAME_TEMPLATE_ID);
      String preparationInputDigest = digest(preparationInputJson);

      var preAuthorizationTuple =
          StartSessionPreAuthorizationReservationTuple.createHuman(
              requestId,
              account.account.getAccountUuid(),
              new StartSessionOperatorAction(
                  StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                  StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                  new StartSessionOperatorAction.Scope(account.tenant, NAMESPACE),
                  new StartSessionOperatorAction.Target(
                      ORIGINAL_GAME_TEMPLATE_ID, account.account.getAccountUuid()),
                  StartSessionOperatorAction.ExpectedVersion.ABSENT,
                  new StartSessionOperatorAction.Mutation(
                      StartSessionOperatorAction.ClientIp.absent()),
                  "test-only StartSession World participation storage proof"));

      // This client response is explicitly stipulated Logging claim evidence. Account still runs
      // its real typed-claim checks, source capture, signing, currentness, issuance, and
      // redemption.
      var claimClient = mock(StartSessionReservationEvidenceClient.class);
      var claim =
          stipulatedLoggingClaim(
              preAuthorizationTuple, reservationOwnerId, reservationClaimFence, Instant.now());
      when(claimClient.readCurrentClaimEvidence(
              preAuthorizationTuple,
              reservationOwnerId,
              reservationClaimFence,
              reservationOwnerId,
              reservationClaimFence,
              StartSessionReservationEvidenceClient.Purpose.ISSUE))
          .thenReturn(claim);

      var operatorRepository = new AccountStartSessionOperatorAuthorizationRepository(account.dsl);
      var captureRepository = new AccountStartSessionAuthorityCaptureRepository(account.dsl);
      var fingerprintKeys = fingerprintKeys();
      var operatorResponseCryptography = responseCryptography(temporary);
      var operatorAuthorization =
          new AccountStartSessionOperatorAuthorizationService(
              actorService,
              new AccountControlUiIssuanceRepository(account.dsl),
              captureRepository,
              claimClient,
              operatorRepository,
              fingerprintKeys,
              operatorResponseCryptography,
              account.terms,
              account.manager,
              Clock.systemUTC(),
              new SecureRandom(),
              LOGGING_PEER_URI,
              GAME_SESSION_PEER_URI,
              Duration.ofMinutes(2),
              Duration.ofSeconds(30));

      var authorization =
          withPeer(
              LOGGING_PEER_URI,
              () ->
                  operatorAuthorization.issue(
                      new AccountStartSessionOperatorAuthorizationService.IssueRequest(
                          creator.compact(),
                          preAuthorizationTuple.canonicalJson().getBytes(StandardCharsets.UTF_8),
                          reservationOwnerId,
                          reservationClaimFence,
                          reservationOwnerId,
                          reservationClaimFence)));

      // The exact Account issuance result becomes the original post-authorization tuple. The
      // target owner is still Game Session; World is only the same-namespace receiver.
      var originalTuple =
          StartSessionPostAuthorizationExecutionTuple.createHuman(
              preAuthorizationTuple,
              LOGGING_PEER_URI,
              authorization.authorizationReferenceFingerprint(),
              reservationOwnerId,
              reservationClaimFence,
              authorization.authorityEvidenceBundle(),
              new BundleReference(
                  authorization.bundleReference().bundleVersion(),
                  authorization.bundleReference().sourceVersion(),
                  authorization.bundleReference().sourceFence(),
                  authorization.bundleReference().linearization()));
      assertThat(originalTuple.preAuthorizationTuple().targetOwner())
          .isEqualTo("game-session-service");

      // Account performs its real one-time redemption. The Game Session peer and attempt/fence
      // are stipulated test inputs, not evidence from an authenticated Game Session producer.
      withPeer(
          GAME_SESSION_PEER_URI,
          () ->
              operatorAuthorization.redeem(
                  new AccountStartSessionOperatorAuthorizationService.RedeemRequest(
                      preAuthorizationTuple.canonicalJson().getBytes(StandardCharsets.UTF_8),
                      authorization.operatorAuthorizationReference(),
                      authorization.authorizationReferenceFingerprint(),
                      reservationOwnerId,
                      reservationClaimFence,
                      gameSessionAttemptId,
                      gameSessionOwnerFence)));

      var redeemed =
          account.tx(() -> operatorRepository.findByControlPlaneRequestId(requestId).orElseThrow());
      assertThat(redeemed.status())
          .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.REDEEMED);
      assertThat(redeemed.redemptionOwnerAttemptId()).isEqualTo(gameSessionAttemptId);
      assertThat(redeemed.redemptionOwnerFence()).isEqualTo(gameSessionOwnerFence);
      assertThat(redeemed.issuanceFence()).isPositive();

      var repository = new AccountStartSessionWorldParticipationRepository(account.dsl);
      var initialCapture =
          new java.util.concurrent.atomic.AtomicReference<AccountStartSessionAuthorityCapture>();
      var initialCandidate = new java.util.concurrent.atomic.AtomicReference<Candidate>();
      StoredParticipation first =
          withWorldCurrentness(
              operatorAuthorization,
              originalTuple,
              gameSessionAttemptId,
              gameSessionOwnerFence,
              capture -> {
                assertActiveReadCommittedTransaction();
                initialCapture.set(capture);
                var candidate =
                    candidate(
                        originalTuple,
                        capture,
                        gameInstanceId,
                        gameSessionAttemptId,
                        gameSessionOwnerFence,
                        preparationInputJson,
                        preparationInputDigest);
                initialCandidate.set(candidate);
                return repository.createOrReadExact(candidate);
              });

      var capture = initialCapture.get();
      assertThat(capture).isNotNull();
      assertThat(capture.controlPlaneRequestId()).isEqualTo(requestId);
      assertThat(capture.sourceVersion()).isPositive();
      assertThat(capture.sourceFence()).isPositive();
      assertThat(account.dsl.fetchCount(DSL.table("account_start_session_authority_captures")))
          .isEqualTo(1);
      var captureRow =
          account.dsl.fetchOne(
              "SELECT source_version, source_fence, issuance_fence, "
                  + "issuance_fence_source_version, canonical_snapshot_bytes "
                  + "FROM account_start_session_authority_captures "
                  + "WHERE control_plane_request_id = ?",
              requestId);
      assertThat(captureRow).isNotNull();
      assertThat(captureRow.get("source_version", Long.class)).isEqualTo(capture.sourceVersion());
      assertThat(captureRow.get("source_fence", Long.class)).isEqualTo(capture.sourceFence());
      assertThat(captureRow.get("issuance_fence", Long.class)).isEqualTo(redeemed.issuanceFence());
      assertThat(captureRow.get("canonical_snapshot_bytes", byte[].class))
          .containsExactly(capture.snapshotBytes());
      Map<String, Object> captureSnapshot = captureSnapshot(capture);
      assertThat(captureSnapshot.get("issuanceFence"))
          .isEqualTo(Long.toString(redeemed.issuanceFence()));
      assertThat(captureSnapshot.get("issuanceFenceSourceVersion"))
          .isEqualTo(Long.toString(captureRow.get("issuance_fence_source_version", Long.class)));
      assertThat(captureSnapshot.get("controlUiSignerReceiptSha256").toString())
          .matches("[0-9a-f]{64}");

      assertThat(first.participationId()).isNotEqualTo(new UUID(0L, 0L));
      assertThat(first.participationFence()).isPositive();
      assertThat(first.producerXid()).isPositive();
      assertThat(first.originalPostAuthorizationTuple())
          .containsExactly(originalTuple.canonicalBytes());
      assertThat(first.targetNamespace()).isEqualTo(NAMESPACE);
      assertThat(first.canonicalTenantId()).isEqualTo(account.tenant);
      assertThat(first.canonicalGameInstanceId()).isEqualTo(gameInstanceId);
      assertThat(first.gameSessionOwnerAttemptId()).isEqualTo(gameSessionAttemptId);
      assertThat(first.gameSessionOwnerFence()).isEqualTo(gameSessionOwnerFence);
      assertThat(first.preparationInputJson()).isEqualTo(preparationInputJson);
      assertThat(first.preparationInputDigest()).isEqualTo(preparationInputDigest);
      assertThat(capturedSourceVector(capture))
          .containsExactlyElementsOf(
              first.sources().stream()
                  .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
                  .toList());

      StoredParticipation exactRetry =
          withWorldCurrentness(
              operatorAuthorization,
              originalTuple,
              gameSessionAttemptId,
              gameSessionOwnerFence,
              currentCapture -> {
                assertThat(currentCapture.canonicalBytes())
                    .containsExactly(capture.canonicalBytes());
                return repository.createOrReadExact(initialCandidate.get());
              });
      assertThat(exactRetry.participationId()).isEqualTo(first.participationId());
      assertThat(exactRetry.participationFence()).isEqualTo(first.participationFence());
      assertThat(exactRetry.originalPostAuthorizationTuple())
          .containsExactly(first.originalPostAuthorizationTuple());
      assertThat(
              exactRetry.sources().stream()
                  .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
                  .toList())
          .isEqualTo(
              first.sources().stream()
                  .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
                  .toList());

      var changedPreparation =
          preparationInput(
              NAMESPACE,
              account.tenant,
              requestId,
              gameInstanceId,
              "changed-owner-proof-world",
              ORIGINAL_GAME_TEMPLATE_ID);
      var changedCandidate =
          candidate(
              originalTuple,
              capture,
              gameInstanceId,
              gameSessionAttemptId,
              gameSessionOwnerFence,
              changedPreparation,
              digest(changedPreparation));
      assertThatThrownBy(
              () ->
                  withWorldCurrentness(
                      operatorAuthorization,
                      originalTuple,
                      gameSessionAttemptId,
                      gameSessionOwnerFence,
                      ignored -> repository.createOrReadExact(changedCandidate)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("StartSession World participation conflicts with its immutable binding");
      assertThatThrownBy(
              () ->
                  withWorldCurrentness(
                      operatorAuthorization,
                      originalTuple,
                      UUID.randomUUID(),
                      gameSessionOwnerFence,
                      ignored -> repository.createOrReadExact(initialCandidate.get())))
          .isInstanceOf(IllegalStateException.class);

      assertThatThrownBy(() -> repository.createOrReadExact(initialCandidate.get()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Writable Account READ_COMMITTED transaction required");

      long fenceSequenceBeforeHistoricalRead = participationFenceSequence(account.dsl);
      StoredParticipation historical =
          account.tx(
              () ->
                  repository
                      .findHistoricalExact(first.participationId(), first.participationFence())
                      .orElseThrow());
      long fenceSequenceAfterHistoricalRead = participationFenceSequence(account.dsl);
      assertThat(historical.participationId()).isEqualTo(first.participationId());
      assertThat(historical.participationFence()).isEqualTo(first.participationFence());
      assertThat(historical.originalPostAuthorizationTuple())
          .containsExactly(first.originalPostAuthorizationTuple());
      assertThat(
              historical.sources().stream()
                  .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
                  .toList())
          .isEqualTo(
              first.sources().stream()
                  .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
                  .toList());
      assertThat(fenceSequenceAfterHistoricalRead).isEqualTo(fenceSequenceBeforeHistoricalRead);
      var issuanceAfterHistoricalRead =
          account.tx(() -> operatorRepository.findByControlPlaneRequestId(requestId).orElseThrow());
      assertThat(issuanceAfterHistoricalRead.referenceExpiresAt())
          .isEqualTo(redeemed.referenceExpiresAt());
      assertThat(issuanceAfterHistoricalRead.responseEnvelopeExpiresAt())
          .isEqualTo(redeemed.responseEnvelopeExpiresAt());
      assertThat(account.tx(() -> repository.findHistoricalExact(UUID.randomUUID(), 1L))).isEmpty();

      var committedTerminal =
          terminal(first, WorldStartSessionExecutionTerminal.Outcome.COMMITTED, 73L);
      StoredSettlement committed = account.tx(() -> repository.settleExact(committedTerminal));
      StoredSettlement exactSettlementRetry =
          account.tx(() -> repository.settleExact(committedTerminal));
      assertThat(committed.participationId()).isEqualTo(first.participationId());
      assertThat(committed.outcome())
          .isEqualTo(WorldStartSessionExecutionTerminal.Outcome.COMMITTED);
      assertThat(committed.worldExecutionFence()).isEqualTo(73L);
      assertThat(exactSettlementRetry.terminalBytes()).containsExactly(committed.terminalBytes());
      assertThat(exactSettlementRetry.terminalDigest()).isEqualTo(committed.terminalDigest());
      assertThat(
              account
                  .tx(
                      () ->
                          repository
                              .findSettlementExact(
                                  first.participationId(), first.participationFence())
                              .orElseThrow())
                  .terminalBytes())
          .containsExactly(committed.terminalBytes());

      var conflictingTerminal =
          terminal(first, WorldStartSessionExecutionTerminal.Outcome.ABORTED, 74L);
      assertThatThrownBy(() -> account.tx(() -> repository.settleExact(conflictingTerminal)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("StartSession World participation conflicts with its immutable binding");
      assertThatThrownBy(
              () ->
                  account.dsl.execute(
                      "UPDATE account_start_session_world_participation_settlements "
                          + "SET outcome = 'ABORTED' WHERE participation_id = ?",
                      first.participationId()))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              account.tx(
                  () ->
                      repository
                          .findSettlementExact(first.participationId(), first.participationFence())
                          .orElseThrow()
                          .terminalBytes()))
          .containsExactly(committed.terminalBytes());

      var absentParentTerminal =
          new WorldStartSessionExecutionTerminal(
              first.originalPostAuthorizationTuple(),
              UUID.randomUUID(),
              first.participationFence() + 1L,
              first.gameSessionOwnerAttemptId(),
              first.gameSessionOwnerFence(),
              first.targetNamespace(),
              first.canonicalTenantId(),
              first.controlPlaneRequestId(),
              first.canonicalGameInstanceId(),
              first.preparationInputDigest(),
              first.preparationInputJson(),
              75L,
              WorldStartSessionExecutionTerminal.Outcome.COMMITTED);
      assertThatThrownBy(() -> account.tx(() -> repository.settleExact(absentParentTerminal)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Exact StartSession World participation evidence unavailable");
      assertThat(
              account.tx(
                  () ->
                      repository.findSettlementExact(
                          absentParentTerminal.accountWorldParticipationId(),
                          absentParentTerminal.accountWorldParticipationFence())))
          .isEmpty();

      // The terminal is a typed test collaborator. Its validity proves Account storage binding,
      // not a World producer, Game Session owner, transport peer, or execution result.
      assertThat(account.dsl.fetchCount(DSL.table("account_start_session_world_participations")))
          .isEqualTo(1);
      assertThat(
              account.dsl.fetchCount(
                  DSL.table("account_start_session_world_participation_sources")))
          .isEqualTo(first.sources().size());
      assertThat(
              account.dsl.fetchCount(
                  DSL.table("account_start_session_world_participation_settlements")))
          .isEqualTo(1);
    }
  }

  private static Candidate candidate(
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      AccountStartSessionAuthorityCapture capture,
      UUID gameInstanceId,
      UUID gameSessionAttemptId,
      long gameSessionOwnerFence,
      String preparationInputJson,
      String preparationInputDigest) {
    return new Candidate(
        originalTuple,
        capture,
        gameInstanceId,
        gameSessionAttemptId,
        gameSessionOwnerFence,
        preparationInputJson,
        preparationInputDigest);
  }

  /**
   * Stipulated structurally complete identity/descriptor envelope for Account storage validation.
   * The echoed World and Game Session fields are test input, not authenticated producer evidence.
   */
  private static String preparationInput(
      String namespace,
      UUID tenantId,
      String requestId,
      UUID gameInstanceId,
      String worldSlug,
      long gameTemplateId)
      throws Exception {
    String descriptorJson = JSON.writeValueAsString(Map.of("gameTemplateId", gameTemplateId));
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
                worldSlug),
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
                worldSlug),
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
                worldSlug,
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
                worldSlug)));
  }

  private static WorldStartSessionExecutionTerminal terminal(
      StoredParticipation participation,
      WorldStartSessionExecutionTerminal.Outcome outcome,
      long worldExecutionFence) {
    return new WorldStartSessionExecutionTerminal(
        participation.originalPostAuthorizationTuple(),
        participation.participationId(),
        participation.participationFence(),
        participation.gameSessionOwnerAttemptId(),
        participation.gameSessionOwnerFence(),
        participation.targetNamespace(),
        participation.canonicalTenantId(),
        participation.controlPlaneRequestId(),
        participation.canonicalGameInstanceId(),
        participation.preparationInputDigest(),
        participation.preparationInputJson(),
        worldExecutionFence,
        outcome);
  }

  private static <T> T withWorldCurrentness(
      AccountStartSessionOperatorAuthorizationService operatorAuthorization,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      UUID gameSessionAttemptId,
      long gameSessionOwnerFence,
      Function<AccountStartSessionAuthorityCapture, T> callback) {
    try (var ignored = AccountControlUiOwnerSourcesFixture.withPeer(WORLD_PEER_URI)) {
      return operatorAuthorization.withCurrentWorldReceivingParticipationCurrentness(
          originalTuple.canonicalBytes(), gameSessionAttemptId, gameSessionOwnerFence, callback);
    }
  }

  private static <T> T withPeer(String peerUri, java.util.concurrent.Callable<T> action) {
    try (var ignored = AccountControlUiOwnerSourcesFixture.withPeer(peerUri)) {
      return action.call();
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Test peer-scoped Account owner action failed", failure);
    }
  }

  private static ReadCurrentClaimEvidenceResponse stipulatedLoggingClaim(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      long reservationClaimFence,
      Instant now) {
    return ReadCurrentClaimEvidenceResponse.newBuilder()
        .setControlPlaneRequestId(tuple.controlPlaneRequestId())
        .setPreAuthorizationTupleJson(
            com.google.protobuf.ByteString.copyFrom(tuple.canonicalJson(), StandardCharsets.UTF_8))
        .setMutationDigest(tuple.mutationDigest())
        .setReservationOwnerId(reservationOwnerId.toString())
        .setReservationClaimFence(reservationClaimFence)
        .setClaimOwnerId(reservationOwnerId.toString())
        .setClaimFence(reservationClaimFence)
        .setObservedAtEpochMillis(now.minusSeconds(1).toEpochMilli())
        .setClaimExpiresAtEpochMillis(now.plus(Duration.ofMinutes(5)).toEpochMilli())
        .setPurpose(
            StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE)
        .build();
  }

  private static AccountOperatorAuthorizationFingerprintKeyring fingerprintKeys() {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 0x51);
    return new AccountOperatorAuthorizationFingerprintKeyring(
        () ->
            new Snapshot(
                new KeyMaterial("test-active", new SecretKeySpec(key, "HmacSHA256")), List.of()),
        Clock.systemUTC(),
        Duration.ofMinutes(2));
  }

  private static AccountResponseEnvelopeCryptography responseCryptography(Path temporary)
      throws Exception {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 0x26);
    Path root = Files.createDirectories(temporary.resolve("operator-response-envelope"));
    Files.writeString(
        root.resolve("keyring"),
        "firemud-account-response-envelope-keyring-v1\nactive test-response "
            + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            + "\n");
    return new AccountResponseEnvelopeCryptography(
        new AccountResponseEnvelopeKeyring(root.toString()), Clock.systemUTC(), new SecureRandom());
  }

  private static Map<String, Object> captureSnapshot(AccountStartSessionAuthorityCapture capture)
      throws Exception {
    return JSON.readValue(capture.snapshotBytes(), new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static List<String> capturedSourceVector(AccountStartSessionAuthorityCapture capture)
      throws Exception {
    return (List<String>) captureSnapshot(capture).get("sourceVector");
  }

  private static long participationFenceSequence(DSLContext dsl) {
    String sequence =
        java.util.Objects.requireNonNull(
            java.util.Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT pg_get_serial_sequence("
                            + "'account_start_session_world_participations', 'participation_fence')"))
                .get(0, String.class));
    return java.util.Objects.requireNonNull(
        java.util.Objects.requireNonNull(dsl.fetchOne("SELECT last_value FROM " + sequence))
            .get(0, Long.class));
  }

  private static String digest(String value) throws Exception {
    return "sha256:"
        + HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static void assertActiveReadCommittedTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
}
