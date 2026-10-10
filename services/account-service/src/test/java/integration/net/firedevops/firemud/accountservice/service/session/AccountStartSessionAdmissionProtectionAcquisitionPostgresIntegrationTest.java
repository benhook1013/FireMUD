package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.Candidate;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture.IssuedCreator;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.KeyMaterial;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.Snapshot;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionSettlement;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalResult;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL proof that the real Account issuer, signed actor, original capture, redemption,
 * currentness callback, World participation and V134 storage compose into admission protection and
 * exact terminal settlement. Logging claim evidence, the Game Session current-attempt client, and
 * World/Game Session hold/terminal values are stipulated test collaborators. This proves no genuine
 * upstream producer, authenticated cross-service transport, Game Session terminal owner
 * transaction, runtime registration, or activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountStartSessionAdmissionProtectionAcquisitionPostgresIntegrationTest {
  private static final String NAMESPACE = "control-ui-owner-proof";
  private static final String LOGGING_PEER_URI =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/logging-admin-service";
  private static final String GAME_SESSION_PEER_URI =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/game-session-service";
  private static final String WORLD_PEER_URI =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/world-management-service";
  private static final long ORIGINAL_GAME_TEMPLATE_ID = 42L;
  private static final long GAME_SESSION_OWNER_FENCE = 47L;
  private static final long WORLD_EXECUTION_FENCE = 73L;
  private static final String WORLD_SLUG = "owner-proof-world";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("admission-protection-primary")
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
  static final GenericContainer<?> REDIS_REPLICA =
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
              "admission-protection-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void realAccountAcquisitionAndExactRetryPreserveProtectionCaptureAndFiniteLease()
      throws Exception {
    try (Prepared prepared = prepare(Duration.ofSeconds(90))) {
      var service = prepared.acquisitionService(prepared.account.manager);

      AccountStartSessionAdmissionProtectionEvidence first = prepared.acquire(service);
      assertProtectionRow(prepared, first);
      assertThat(first.accountProtectionId()).isNotEqualTo(new UUID(0L, 0L));
      assertThat(first.accountProtectionFence()).isPositive();
      assertThat(first.request().originalLeaseExpiresAt())
          .isEqualTo(prepared.originalObservation.originalLeaseExpiresAt());
      assertThat(first.request().accountWorldParticipationId())
          .isEqualTo(prepared.participation.participationId());
      assertThat(first.request().accountWorldParticipationFence())
          .isEqualTo(prepared.participation.participationFence());
      assertThat(first.originalSourceCaptureReferenceBytes())
          .containsExactly(prepared.capture.canonicalBytes());
      assertThat(first.originalSourceCaptureReferenceSha256())
          .isEqualTo(prepared.capture.canonicalSha256());
      assertThat(encoded(first.sourceEvidenceVector()))
          .containsExactlyElementsOf(encoded(prepared.participation.sources()));

      AccountStartSessionAdmissionProtectionEvidence exactRetry = prepared.acquire(service);
      assertThat(exactRetry.accountProtectionId()).isEqualTo(first.accountProtectionId());
      assertThat(exactRetry.accountProtectionFence()).isEqualTo(first.accountProtectionFence());
      assertThat(exactRetry.canonicalBytes()).containsExactly(first.canonicalBytes());
      assertThat(exactRetry.request().originalLeaseExpiresAt())
          .isEqualTo(first.request().originalLeaseExpiresAt());
      assertThat(encoded(exactRetry.sourceEvidenceVector()))
          .containsExactlyElementsOf(encoded(first.sourceEvidenceVector()));
      assertThat(rowCount(prepared.account.dsl, "account_start_session_admission_protections"))
          .isEqualTo(1L);
      assertThat(prepared.reads).hasSize(2);
      assertThat(prepared.reads.get(0).readRequestId())
          .isNotEqualTo(prepared.reads.get(1).readRequestId());
    }
  }

  @Test
  void lostIndependentReadbackAcknowledgementRetriesTheCommittedProtectionExactly()
      throws Exception {
    try (Prepared prepared = prepare(Duration.ofSeconds(90))) {
      var readbackManager = new CommitThenLoseOneAcknowledgement(prepared.account.manager);
      var service = prepared.acquisitionService(readbackManager);

      assertThatThrownBy(() -> prepared.acquire(service))
          .isInstanceOf(LostReadbackAcknowledgement.class);
      Record committed = protectionRow(prepared);
      byte[] committedRequest = committed.get("request_binding_bytes", byte[].class);
      UUID committedId = committed.get("protection_id", UUID.class);
      Long committedFence = committed.get("protection_fence", Long.class);
      OffsetDateTime committedExpiry =
          committed.get("original_lease_expires_at", OffsetDateTime.class);
      assertThat(committedId).isNotNull();
      assertThat(committedFence).isNotNull().isPositive();
      assertThat(committedExpiry).isNotNull();
      assertThat(readbackManager.didLoseAcknowledgement()).isTrue();
      assertThat(rowCount(prepared.account.dsl, "account_start_session_admission_protections"))
          .isEqualTo(1L);

      AccountStartSessionAdmissionProtectionEvidence committedReadback =
          readCurrentProtection(prepared);
      assertProtectionRow(prepared, committedReadback);

      AccountStartSessionAdmissionProtectionEvidence retry = prepared.acquire(service);
      assertThat(retry.accountProtectionId()).isEqualTo(committedId);
      assertThat(retry.accountProtectionFence()).isEqualTo(committedFence);
      assertThat(retry.canonicalBytes()).containsExactly(committedReadback.canonicalBytes());
      assertThat(retry.request().canonicalBytes()).containsExactly(committedRequest);
      assertThat(retry.request().originalLeaseExpiresAt()).isEqualTo(committedExpiry.toInstant());
      assertThat(encoded(retry.sourceEvidenceVector()))
          .containsExactlyElementsOf(encoded(prepared.participation.sources()));
      assertProtectionRow(prepared, retry);
      assertThat(rowCount(prepared.account.dsl, "account_start_session_admission_protections"))
          .isEqualTo(1L);
      assertThat(prepared.reads).hasSize(2);
      assertThat(prepared.reads.get(0).readRequestId())
          .isNotEqualTo(prepared.reads.get(1).readRequestId());
    }
  }

  @Test
  void changedRealAccountSourceAfterSettledWorldGapDeniesWithoutAdmissionInsert() throws Exception {
    try (Prepared prepared = prepare(Duration.ofSeconds(90))) {
      prepared.settleWorldWithStipulatedTerminal();
      Account changed = prepared.account.tx(prepared.account::changeRoleToAdmin);
      assertThat(changed.getRole()).isEqualTo("admin");

      assertThatThrownBy(
              () -> prepared.acquire(prepared.acquisitionService(prepared.account.manager)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Exact current authenticated initial creator required");

      assertThat(rowCount(prepared.account.dsl, "account_start_session_admission_protections"))
          .isZero();
      assertThat(
              rowCount(prepared.account.dsl, "account_start_session_admission_protection_sources"))
          .isZero();
      verify(prepared.gameSessionClient, times(1)).read(any(Request.class));
    }
  }

  @Test
  void settledWorldAndExpiredOriginalLeaseDoNotReleasePendingAccountSourceProtection()
      throws Exception {
    try (Prepared prepared = prepare(Duration.ofSeconds(10))) {
      AccountStartSessionAdmissionProtectionEvidence protection =
          prepared.acquire(prepared.acquisitionService(prepared.account.manager));
      assertProtectionRow(prepared, protection);

      WorldStartSessionExecutionTerminal stipulatedTerminal = terminal(prepared.participation);
      prepared.settleWorldWithStipulatedTerminal();
      var worldSettlement =
          prepared.account.tx(
              () ->
                  prepared
                      .participationRepository
                      .findSettlementExact(
                          prepared.participation.participationId(),
                          prepared.participation.participationFence())
                      .orElseThrow());
      assertThat(worldSettlement.outcome())
          .isEqualTo(WorldStartSessionExecutionTerminal.Outcome.COMMITTED);
      assertThat(worldSettlement.terminalBytes())
          .containsExactly(stipulatedTerminal.canonicalBytes());
      assertThat(worldSettlement.terminalDigest()).isEqualTo(stipulatedTerminal.digest());

      Instant originalLeaseExpiry = protection.request().originalLeaseExpiresAt();
      awaitDatabaseTime(
          prepared.account.dsl, originalLeaseExpiry.plusMillis(100), Duration.ofSeconds(15));
      assertThat(databaseNow(prepared.account.dsl)).isAfter(originalLeaseExpiry);

      var historicalProtectionRepository =
          new AccountStartSessionAdmissionProtectionRepository(prepared.account.dsl);
      AccountStartSessionAdmissionProtectionEvidence historical =
          prepared.account.tx(
              () ->
                  historicalProtectionRepository
                      .findHistoricalExact(
                          protection.accountProtectionId(), protection.accountProtectionFence())
                      .orElseThrow());
      assertThat(historical.canonicalBytes()).containsExactly(protection.canonicalBytes());
      assertThat(historical.accountProtectionId()).isEqualTo(protection.accountProtectionId());
      assertThat(historical.accountProtectionFence())
          .isEqualTo(protection.accountProtectionFence());
      assertThat(historical.request().canonicalBytes())
          .containsExactly(protection.request().canonicalBytes());
      assertThat(historical.originalSourceCaptureReferenceBytes())
          .containsExactly(protection.originalSourceCaptureReferenceBytes());
      assertThat(encoded(historical.sourceEvidenceVector()))
          .containsExactlyElementsOf(encoded(protection.sourceEvidenceVector()));

      String roleBefore =
          prepared
              .account
              .accounts
              .findById(prepared.account.account.getId())
              .orElseThrow()
              .getRole();
      List<String> authorityGenerationsBefore = authorityGenerationSnapshot(prepared.account.dsl);
      assertThatThrownBy(() -> prepared.account.tx(prepared.account::changeRoleToAdmin))
          .isInstanceOf(RuntimeException.class)
          .satisfies(
              failure -> {
                Throwable rootCause = failure;
                while (rootCause.getCause() != null) rootCause = rootCause.getCause();
                assertThat(rootCause)
                    .isInstanceOfSatisfying(
                        org.postgresql.util.PSQLException.class,
                        postgresFailure -> {
                          assertThat(postgresFailure.getSQLState()).isEqualTo("55P03");
                          var serverError = postgresFailure.getServerErrorMessage();
                          if (serverError == null) {
                            throw new AssertionError(
                                "Pending-source denial lacks PostgreSQL server evidence");
                          }
                          assertThat(serverError.getMessage())
                              .isEqualTo(
                                  "Original StartSession admission protection remains pending "
                                      + "for a required source");
                        });
              });

      assertThat(
              prepared
                  .account
                  .accounts
                  .findById(prepared.account.account.getId())
                  .orElseThrow()
                  .getRole())
          .isEqualTo(roleBefore);
      assertThat(authorityGenerationSnapshot(prepared.account.dsl))
          .containsExactlyElementsOf(authorityGenerationsBefore);
      assertProtectionRow(prepared, protection);
      assertThat(
              rowCount(
                  prepared.account.dsl, "account_start_session_admission_protection_settlements"))
          .isZero();
    }
  }

  @Test
  void exactHistoricalSettlementAfterExpiryReleasesSourcesOnlyAfterCommit() throws Exception {
    try (Prepared prepared = prepare(Duration.ofSeconds(10))) {
      AccountStartSessionAdmissionProtectionEvidence protection =
          prepared.acquire(prepared.acquisitionService(prepared.account.manager));
      prepared.settleWorldWithStipulatedTerminal();
      Instant expiry = protection.request().originalLeaseExpiresAt();
      awaitDatabaseTime(prepared.account.dsl, expiry.plusMillis(100), Duration.ofSeconds(15));

      var repository = new AccountStartSessionAdmissionProtectionRepository(prepared.account.dsl);
      var settlement =
          terminalSettlement(
              protection,
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
              Instant.parse("2026-10-10T04:05:06.123456789Z"));
      CountDownLatch receiptInserted = new CountDownLatch(1);
      CountDownLatch allowCommit = new CountDownLatch(1);
      var executor = Executors.newFixedThreadPool(2);
      try {
        var settling =
            executor.submit(
                () ->
                    prepared.account.transactions.execute(
                        ignored -> {
                          repository.settleExact(settlement);
                          receiptInserted.countDown();
                          awaitLatch(allowCommit);
                          return null;
                        }));
        assertThat(receiptInserted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(
                rowCount(
                    prepared.account.dsl, "account_start_session_admission_protection_settlements"))
            .isZero();

        var blockedWriter =
            executor.submit(
                () -> {
                  try {
                    return prepared.account.tx(prepared.account::changeRoleToAdmin);
                  } catch (RuntimeException failure) {
                    return failure;
                  }
                });
        Object beforeCommitResult = blockedWriter.get(20, TimeUnit.SECONDS);
        assertThat(beforeCommitResult).isInstanceOf(RuntimeException.class);
        assertPostgresSqlState((RuntimeException) beforeCommitResult, "55P03");
        assertThat(
                prepared
                    .account
                    .accounts
                    .findById(prepared.account.account.getId())
                    .orElseThrow()
                    .getRole())
            .isEqualTo(prepared.account.account.getRole());

        allowCommit.countDown();
        settling.get(20, TimeUnit.SECONDS);
      } finally {
        allowCommit.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }

      var stored =
          prepared.account.tx(
              () ->
                  repository
                      .findSettlementExact(
                          protection.accountProtectionId(), protection.accountProtectionFence())
                      .orElseThrow());
      assertThat(stored.settlement().canonicalBytes()).containsExactly(settlement.canonicalBytes());
      assertThat(stored.settlement().protectionEvidence().canonicalBytes())
          .containsExactly(protection.canonicalBytes());
      assertThat(stored.outcome())
          .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
      assertThat(stored.settledAt()).isNotNull();

      Account changed = prepared.account.tx(prepared.account::changeRoleToAdmin);
      assertThat(changed.getRole()).isEqualTo("admin");
      assertThat(
              rowCount(
                  prepared.account.dsl, "account_start_session_admission_protection_settlements"))
          .isEqualTo(1L);
    }
  }

  @Test
  void settlementInsertGuardRejectsMalformedBindingDigestPendingAndNoncanonicalInstant()
      throws Exception {
    try (Prepared prepared = prepare(Duration.ofSeconds(90))) {
      AccountStartSessionAdmissionProtectionEvidence protection =
          prepared.acquire(prepared.acquisitionService(prepared.account.manager));
      var committed =
          terminalSettlement(
              protection,
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
              Instant.parse("2026-10-10T04:05:06.123456789Z"));
      byte[] exactProofBytes = committed.result().canonicalBytes();

      assertRawSettlementRejected(
          prepared,
          "COMMITTED",
          settlementEnvelope(
              protection.canonicalBytes(),
              exactProofBytes,
              "account-start-session-admission-protection-settlement/v2"),
          settlementDigest(
              settlementEnvelope(
                  protection.canonicalBytes(),
                  exactProofBytes,
                  "account-start-session-admission-protection-settlement/v2")));

      byte[] changedEvidence = protection.canonicalBytes();
      String changedEvidenceJson =
          new String(changedEvidence, StandardCharsets.UTF_8)
              .replace(
                  "\"accountProtectionFence\":\"" + protection.accountProtectionFence() + "\"",
                  "\"accountProtectionFence\":\""
                      + (protection.accountProtectionFence() + 1)
                      + "\"");
      byte[] changedEvidenceBytes = Rfc8785CanonicalJson.canonicalizeUtf8(changedEvidenceJson);
      assertThat(changedEvidenceBytes).isNotEqualTo(protection.canonicalBytes());
      byte[] changedBindingEnvelope = settlementEnvelope(changedEvidenceBytes, exactProofBytes);
      assertRawSettlementRejected(
          prepared, "COMMITTED", changedBindingEnvelope, settlementDigest(changedBindingEnvelope));

      byte[] validEnvelope = committed.canonicalBytes();
      assertRawSettlementRejected(prepared, "COMMITTED", validEnvelope, "sha256:" + "0".repeat(64));

      byte[] pendingProof =
          ownerProofBytes(
              protection,
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
              null,
              null,
              null,
              false,
              null);
      byte[] pendingEnvelope = settlementEnvelope(protection.canonicalBytes(), pendingProof);
      assertRawSettlementRejected(
          prepared, "COMMITTED", pendingEnvelope, settlementDigest(pendingEnvelope));

      byte[] noncanonicalTimestampProof =
          ownerProofBytesWithTerminalText(
              protection,
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
              "2026-10-10T04:05:06.123000Z");
      byte[] noncanonicalTimestampEnvelope =
          settlementEnvelope(protection.canonicalBytes(), noncanonicalTimestampProof);
      assertRawSettlementRejected(
          prepared,
          "COMMITTED",
          noncanonicalTimestampEnvelope,
          settlementDigest(noncanonicalTimestampEnvelope));

      var stored =
          prepared.account.tx(
              () ->
                  new AccountStartSessionAdmissionProtectionRepository(prepared.account.dsl)
                      .settleExact(committed));
      assertThat(stored.settlement().canonicalBytes()).containsExactly(committed.canonicalBytes());

      var aborted =
          terminalSettlement(
              protection,
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
              Instant.parse("2026-10-10T04:05:07.123456789Z"));
      byte[] conflictingEnvelope = aborted.canonicalBytes();
      assertRawSettlementRejected(
          prepared, "ABORTED", conflictingEnvelope, settlementDigest(conflictingEnvelope));
      var exactRetry =
          prepared.account.tx(
              () ->
                  new AccountStartSessionAdmissionProtectionRepository(prepared.account.dsl)
                      .findSettlementExact(
                          protection.accountProtectionId(), protection.accountProtectionFence())
                      .orElseThrow());
      assertThat(exactRetry.settlement().canonicalBytes())
          .containsExactly(committed.canonicalBytes());
      assertThat(
              rowCount(
                  prepared.account.dsl, "account_start_session_admission_protection_settlements"))
          .isEqualTo(1L);
    }
  }

  @Test
  void exactAbortedSettlementRetryAndReadbackPreserveTheFirstReceipt() throws Exception {
    try (Prepared prepared = prepare(Duration.ofSeconds(90))) {
      AccountStartSessionAdmissionProtectionEvidence protection =
          prepared.acquire(prepared.acquisitionService(prepared.account.manager));
      var repository = new AccountStartSessionAdmissionProtectionRepository(prepared.account.dsl);
      var settlement =
          terminalSettlement(
              protection,
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
              Instant.parse("2026-10-10T04:05:08.123456789Z"));

      var first = prepared.account.tx(() -> repository.settleExact(settlement));
      var exactRetry = prepared.account.tx(() -> repository.settleExact(settlement));
      var historicalReadback =
          prepared.account.tx(
              () ->
                  repository
                      .findSettlementExact(
                          protection.accountProtectionId(), protection.accountProtectionFence())
                      .orElseThrow());

      assertThat(first.settlement().canonicalBytes()).containsExactly(settlement.canonicalBytes());
      assertThat(exactRetry.settlement().canonicalBytes())
          .containsExactly(settlement.canonicalBytes());
      assertThat(historicalReadback.settlement().canonicalBytes())
          .containsExactly(settlement.canonicalBytes());
      assertThat(historicalReadback.settlement().digest()).isEqualTo(settlement.digest());
      assertThat(historicalReadback.protectionId()).isEqualTo(protection.accountProtectionId());
      assertThat(historicalReadback.protectionFence())
          .isEqualTo(protection.accountProtectionFence());
      assertThat(historicalReadback.outcome())
          .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
      assertThat(historicalReadback.settlement().protectionEvidence().canonicalBytes())
          .containsExactly(protection.canonicalBytes());
      assertProtectionRow(prepared, protection);
      assertThat(
              rowCount(
                  prepared.account.dsl, "account_start_session_admission_protection_settlements"))
          .isEqualTo(1L);
    }
  }

  @Test
  void leaseExpiryDuringRealOriginalActorIssuanceLockWaitDeniesDespiteTimelyRemoteRead()
      throws Exception {
    try (Prepared prepared = prepare(Duration.ofSeconds(10))) {
      Instant leaseExpiry = prepared.originalObservation.originalLeaseExpiresAt();
      UUID controlUiOperationId =
          prepared
              .account
              .dsl
              .fetchSingle(
                  "SELECT control_ui_operation_id FROM account_start_session_authority_captures "
                      + "WHERE control_plane_request_id = ?",
                  prepared.originalTuple.controlPlaneRequestId())
              .get("control_ui_operation_id", UUID.class);
      assertThat(controlUiOperationId).isNotNull();

      CountDownLatch issuanceRowLocked = new CountDownLatch(1);
      CountDownLatch releaseIssuanceRow = new CountDownLatch(1);
      var executor = Executors.newFixedThreadPool(2);
      try {
        var lockHolder =
            executor.submit(
                () ->
                    prepared.account.tx(
                        () -> {
                          prepared.account.dsl.fetchSingle(
                              "SELECT operation_id FROM account_control_ui_issuance_operations "
                                  + "WHERE operation_id = ? FOR UPDATE",
                              controlUiOperationId);
                          issuanceRowLocked.countDown();
                          awaitLatch(releaseIssuanceRow);
                          return null;
                        }));
        assertThat(issuanceRowLocked.await(10, TimeUnit.SECONDS)).isTrue();

        var service = prepared.acquisitionService(prepared.account.manager);
        var acquisition = executor.submit(() -> prepared.acquire(service));
        assertThat(prepared.remoteRead.await(10, TimeUnit.SECONDS)).isTrue();
        Instant remoteReadAt = prepared.remoteReadAt.get();
        assertThat(remoteReadAt).isNotNull().isBefore(leaseExpiry);

        awaitDatabaseTime(
            prepared.account.dsl, leaseExpiry.plusMillis(100), Duration.ofSeconds(15));
        Instant afterLeaseExpiry = databaseNow(prepared.account.dsl);
        Record originalAuthorization =
            prepared.account.dsl.fetchSingle(
                "SELECT reference_expires_at FROM account_start_session_operator_authorizations "
                    + "WHERE control_plane_request_id = ?",
                prepared.originalTuple.controlPlaneRequestId());
        OffsetDateTime referenceExpiry =
            originalAuthorization.get("reference_expires_at", OffsetDateTime.class);
        assertThat(afterLeaseExpiry).isAfterOrEqualTo(leaseExpiry);
        assertThat(referenceExpiry)
            .isNotNull()
            .satisfies(value -> assertThat(value.toInstant()).isAfter(afterLeaseExpiry));

        releaseIssuanceRow.countDown();
        ExecutionException acquisitionFailure;
        try {
          acquisition.get(20, TimeUnit.SECONDS);
          throw new AssertionError(
              "Expired original admission lease unexpectedly acquired protection");
        } catch (ExecutionException failure) {
          acquisitionFailure = failure;
        }
        Throwable rootCause = acquisitionFailure;
        while (rootCause.getCause() != null) rootCause = rootCause.getCause();
        assertThat(rootCause)
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                postgresFailure -> {
                  assertThat(postgresFailure.getSQLState()).isEqualTo("23514");
                  var serverError = postgresFailure.getServerErrorMessage();
                  if (serverError == null) {
                    throw new AssertionError("Expiry denial lacks PostgreSQL server evidence");
                  }
                  assertThat(serverError.getMessage())
                      .isEqualTo("Original StartSession admission protection is not current");
                });
        lockHolder.get(10, TimeUnit.SECONDS);
      } finally {
        releaseIssuanceRow.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }

      assertThat(rowCount(prepared.account.dsl, "account_start_session_admission_protections"))
          .isZero();
      assertThat(
              rowCount(prepared.account.dsl, "account_start_session_admission_protection_sources"))
          .isZero();
      assertThat(prepared.reads).hasSize(1);
    }
  }

  private Prepared prepare(Duration observationLease) throws Exception {
    var controlUi =
        new AccountControlUiOriginalOrderFixture(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            REDIS.getHost(),
            REDIS.getMappedPort(6379),
            temporary);
    try {
      IssuedCreator creator = controlUi.issueCreator();
      var account = creator.sources();
      var tupleId = "admission-protection-" + UUID.randomUUID();
      UUID reservationOwnerId = UUID.randomUUID();
      long reservationClaimFence = 31L;
      UUID gameSessionAttemptId = UUID.randomUUID();
      UUID gameSessionMutationId = UUID.randomUUID();
      UUID gameInstanceId = UUID.randomUUID();
      String preparationJson =
          preparationInput(NAMESPACE, account.tenant, tupleId, gameInstanceId, WORLD_SLUG);
      String preparationDigest = digest(preparationJson);

      var preAuthorizationTuple =
          StartSessionPreAuthorizationReservationTuple.createHuman(
              tupleId,
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
                  "test-only Account admission protection producer proof"));

      var claimClient = mock(StartSessionReservationEvidenceClient.class);
      Instant claimNow = Instant.now();
      var claim =
          ReadCurrentClaimEvidenceResponse.newBuilder()
              .setControlPlaneRequestId(preAuthorizationTuple.controlPlaneRequestId())
              .setPreAuthorizationTupleJson(
                  com.google.protobuf.ByteString.copyFrom(
                      preAuthorizationTuple.canonicalJson(), StandardCharsets.UTF_8))
              .setMutationDigest(preAuthorizationTuple.mutationDigest())
              .setReservationOwnerId(reservationOwnerId.toString())
              .setReservationClaimFence(reservationClaimFence)
              .setClaimOwnerId(reservationOwnerId.toString())
              .setClaimFence(reservationClaimFence)
              .setObservedAtEpochMillis(claimNow.minusSeconds(1).toEpochMilli())
              .setClaimExpiresAtEpochMillis(claimNow.plus(Duration.ofMinutes(5)).toEpochMilli())
              .setPurpose(
                  StartSessionReservationEvidencePurpose
                      .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE)
              .build();
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
      var operatorAuthorization =
          new AccountStartSessionOperatorAuthorizationService(
              creator.actors(),
              new AccountControlUiIssuanceRepository(account.dsl),
              captureRepository,
              claimClient,
              operatorRepository,
              fingerprintKeys(),
              responseCryptography(temporary),
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
                      GAME_SESSION_OWNER_FENCE)));

      Instant unadjustedLeaseExpiry =
          databaseNow(account.dsl).truncatedTo(ChronoUnit.MICROS).plus(observationLease);
      long nanosWithinMillisecond = unadjustedLeaseExpiry.getNano() % 1_000_000L;
      final Instant leaseExpiry =
          unadjustedLeaseExpiry.plusNanos(456_000L - nanosWithinMillisecond);
      byte[] projection = StartSessionAccountRedemptionProjection.fromOriginalTuple(originalTuple);
      var originalObservationRequest =
          new Request(
              UUID.randomUUID(),
              NAMESPACE,
              originalTuple.canonicalBytes(),
              gameSessionAttemptId,
              gameSessionMutationId,
              GAME_SESSION_OWNER_FENCE);
      var originalObservation = new Result(originalObservationRequest, leaseExpiry, projection);

      var participationRepository =
          new AccountStartSessionWorldParticipationRepository(account.dsl);
      var attemptEvidenceRepository =
          new AccountStartSessionWorldOriginalAttemptEvidenceRepository(account.dsl);
      var capture = new AtomicReference<AccountStartSessionAuthorityCapture>();
      var participation =
          withWorldCurrentness(
              operatorAuthorization,
              originalTuple,
              gameSessionAttemptId,
              GAME_SESSION_OWNER_FENCE,
              currentCapture -> {
                capture.set(currentCapture);
                var candidate =
                    new Candidate(
                        originalTuple,
                        currentCapture,
                        gameInstanceId,
                        gameSessionAttemptId,
                        GAME_SESSION_OWNER_FENCE,
                        preparationJson,
                        preparationDigest);
                StoredParticipation stored = participationRepository.createOrReadExact(candidate);
                attemptEvidenceRepository.retainCurrentExact(stored, originalObservation);
                return stored;
              });

      var holdRequest =
          new WorldCanonicalInitialAdmissionHold.Request(
              NAMESPACE,
              account.tenant,
              WORLD_SLUG,
              UUID.randomUUID(),
              UUID.randomUUID(),
              "SHARED",
              gameInstanceId,
              UUID.randomUUID(),
              1L,
              "initial-admission-" + UUID.randomUUID(),
              sha256Hex("stipulated-world-admission-request".getBytes(StandardCharsets.UTF_8)),
              WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
              1L,
              null);
      var holdIdentity =
          new WorldCanonicalInitialAdmissionHold.HoldIdentity(
              holdRequest, UUID.randomUUID(), UUID.randomUUID());
      var acquisitionRequest =
          new AccountStartSessionAdmissionProtectionAcquisitionService.AcquisitionRequest(
              originalTuple.canonicalBytes(),
              gameSessionMutationId,
              gameSessionAttemptId,
              GAME_SESSION_OWNER_FENCE,
              holdIdentity);

      var gameSessionClient = mock(OriginalStartSessionCurrentAttemptClient.class);
      var reads = new java.util.concurrent.CopyOnWriteArrayList<Request>();
      var remoteRead = new CountDownLatch(1);
      var remoteReadAt = new AtomicReference<Instant>();
      when(gameSessionClient.read(any(Request.class)))
          .thenAnswer(
              invocation -> {
                Request request = invocation.getArgument(0);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                reads.add(request);
                remoteReadAt.set(databaseNow(account.dsl));
                remoteRead.countDown();
                return new Result(request, leaseExpiry, projection);
              });

      return new Prepared(
          controlUi,
          creator,
          account,
          operatorAuthorization,
          originalTuple,
          capture.get(),
          participationRepository,
          attemptEvidenceRepository,
          participation,
          gameSessionMutationId,
          gameSessionAttemptId,
          originalObservation,
          acquisitionRequest,
          gameSessionClient,
          reads,
          remoteRead,
          remoteReadAt);
    } catch (Exception | Error failure) {
      controlUi.close();
      throw failure;
    }
  }

  private static void assertProtectionRow(
      Prepared prepared, AccountStartSessionAdmissionProtectionEvidence evidence) {
    Record row = protectionRow(prepared);
    assertThat(row.get("protection_id", UUID.class)).isEqualTo(evidence.accountProtectionId());
    assertThat(row.get("protection_fence", Long.class))
        .isEqualTo(evidence.accountProtectionFence());
    assertThat(row.get("original_post_authorization_tuple", byte[].class))
        .containsExactly(prepared.originalTuple.canonicalBytes());
    assertThat(row.get("account_redemption_projection", byte[].class))
        .containsExactly(
            StartSessionAccountRedemptionProjection.fromOriginalTuple(prepared.originalTuple));
    OffsetDateTime storedLeaseExpiry = row.get("original_lease_expires_at", OffsetDateTime.class);
    Instant originalLeaseExpiry = prepared.originalObservation.originalLeaseExpiresAt();
    assertThat(evidence.request().originalLeaseExpiresAt()).isEqualTo(originalLeaseExpiry);
    assertThat(storedLeaseExpiry.toInstant()).isEqualTo(originalLeaseExpiry);
    assertThat(storedLeaseExpiry.getNano() % 1_000).isZero();
    assertThat(storedLeaseExpiry.getNano() % 1_000_000).isEqualTo(456_000);
    Record retainedObservation =
        prepared.account.dsl.fetchSingle(
            "SELECT original_lease_expires_at "
                + "FROM account_start_session_world_original_attempt_evidence "
                + "WHERE participation_id = ?",
            prepared.participation.participationId());
    assertThat(
            retainedObservation.get("original_lease_expires_at", OffsetDateTime.class).toInstant())
        .isEqualTo(originalLeaseExpiry);
    assertThat(row.get("account_world_participation_id", UUID.class))
        .isEqualTo(prepared.participation.participationId());
    assertThat(row.get("account_world_participation_fence", Long.class))
        .isEqualTo(prepared.participation.participationFence());
    assertThat(row.get("request_binding_bytes", byte[].class))
        .containsExactly(evidence.request().canonicalBytes());
    assertThat(row.get("producer_xid", Long.class)).isNotNull().isPositive();

    List<Record> sourceRows =
        prepared.account.dsl.fetch(
            "SELECT source_key, source_evidence FROM "
                + "account_start_session_admission_protection_sources "
                + "WHERE protection_id = ? ORDER BY source_key",
            evidence.accountProtectionId());
    assertThat(sourceRows).hasSize(prepared.captureSources().size());
    assertThat(
            sourceRows.stream()
                .map(
                    rowValue ->
                        Base64.getEncoder()
                            .encodeToString(rowValue.get("source_evidence", byte[].class))))
        .containsExactlyElementsOf(
            prepared.captureSources().stream()
                .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
                .toList());
    assertThat(encoded(evidence.sourceEvidenceVector()))
        .containsExactlyElementsOf(encoded(prepared.captureSources()));
  }

  private static Record protectionRow(Prepared prepared) {
    return prepared.account.dsl.fetchSingle(
        "SELECT * FROM account_start_session_admission_protections "
            + "WHERE control_plane_request_id = ?",
        prepared.originalTuple.controlPlaneRequestId());
  }

  private static AccountStartSessionAdmissionProtectionEvidence readCurrentProtection(
      Prepared prepared) {
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            prepared.originalTuple.canonicalBytes(),
            prepared.originalObservation.accountRedemptionProjection(),
            prepared.gameSessionMutationId,
            prepared.gameSessionAttemptId,
            GAME_SESSION_OWNER_FENCE,
            prepared.originalObservation.originalLeaseExpiresAt(),
            prepared.participation.participationId(),
            prepared.participation.participationFence(),
            prepared.acquisitionRequest.worldHoldIdentity());
    var repository = new AccountStartSessionAdmissionProtectionRepository(prepared.account.dsl);
    return prepared.account.tx(
        () ->
            repository
                .findCurrentExact(request)
                .orElseThrow(
                    () -> new IllegalStateException("Committed protection readback missing")));
  }

  private static AccountStartSessionAdmissionProtectionSettlement terminalSettlement(
      AccountStartSessionAdmissionProtectionEvidence protection,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome,
      Instant terminalAt) {
    var hold = protection.request().worldAdmissionHoldIdentity();
    var proof =
        switch (outcome) {
          case COMMITTED ->
              new GameSessionCanonicalInitialAdmissionOwnerProof(
                  hold, outcome, 1L, 23L, "sha256:" + "d".repeat(64), false, terminalAt);
          case ABORTED ->
              new GameSessionCanonicalInitialAdmissionOwnerProof(
                  hold, outcome, null, null, "sha256:" + "d".repeat(64), true, terminalAt);
          case PENDING -> throw new IllegalArgumentException("Pending is not terminal evidence");
        };
    var result =
        new OriginalStartSessionAdmissionTerminalResult(
            new OriginalStartSessionAdmissionTerminalRequest(protection.canonicalBytes()), proof);
    return AccountStartSessionAdmissionProtectionSettlement.create(result);
  }

  private static byte[] ownerProofBytes(
      AccountStartSessionAdmissionProtectionEvidence protection,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome,
      String pointerVersion,
      String auditEventId,
      String proofDigest,
      boolean positiveDurableAbort,
      String terminalAt) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        output, "game-session-canonical-initial-admission-owner-proof/v1");
    DraftAuthorizationFenceBinding.frame(
        output, protection.request().worldAdmissionHoldIdentity().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(output, outcome.name());
    frameOptional(output, pointerVersion);
    frameOptional(output, auditEventId);
    frameOptional(output, proofDigest);
    DraftAuthorizationFenceBinding.frame(output, positiveDurableAbort ? "true" : "false");
    frameOptional(output, terminalAt);
    return output.toByteArray();
  }

  private static byte[] ownerProofBytesWithTerminalText(
      AccountStartSessionAdmissionProtectionEvidence protection,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome,
      String terminalAt) {
    return ownerProofBytes(
        protection, outcome, "1", "23", "sha256:" + "d".repeat(64), false, terminalAt);
  }

  private static void frameOptional(ByteArrayOutputStream output, String value) {
    if (value == null) {
      DraftAuthorizationFenceBinding.frame(output, "ABSENT");
    } else {
      DraftAuthorizationFenceBinding.frame(output, "PRESENT");
      DraftAuthorizationFenceBinding.frame(output, value);
    }
  }

  private static byte[] settlementEnvelope(byte[] evidence, byte[] ownerProof) {
    return settlementEnvelope(
        evidence, ownerProof, "account-start-session-admission-protection-settlement/v1");
  }

  private static byte[] settlementEnvelope(byte[] evidence, byte[] ownerProof, String schema) {
    String canonical =
        "{\"canonicalAccountProtectionEvidenceBytesBase64\":\""
            + Base64.getEncoder().encodeToString(evidence)
            + "\",\"canonicalGameSessionOwnerProofBytesBase64\":\""
            + Base64.getEncoder().encodeToString(ownerProof)
            + "\",\"schema\":\""
            + schema
            + "\"}";
    return canonical.getBytes(StandardCharsets.UTF_8);
  }

  private static String settlementDigest(byte[] bytes) {
    return "sha256:" + sha256Hex(bytes);
  }

  private static void assertRawSettlementRejected(
      Prepared prepared, String outcome, byte[] bytes, String digest) {
    long settlementCountBefore =
        rowCount(prepared.account.dsl, "account_start_session_admission_protection_settlements");
    assertThatThrownBy(
            () ->
                prepared.account.tx(
                    () -> {
                      prepared.account.dsl.execute(
                          "INSERT INTO account_start_session_admission_protection_settlements "
                              + "(protection_id, outcome, terminal_bytes, terminal_digest) "
                              + "VALUES (?, ?, ?, ?)",
                          protectionRow(prepared).get("protection_id", UUID.class),
                          outcome,
                          bytes,
                          digest);
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class)
        .satisfies(failure -> assertPostgresSqlState((RuntimeException) failure, "23514"));
    assertThat(
            rowCount(
                prepared.account.dsl, "account_start_session_admission_protection_settlements"))
        .isEqualTo(settlementCountBefore);
  }

  private static void assertPostgresSqlState(RuntimeException failure, String expectedState) {
    Throwable rootCause = failure;
    while (rootCause.getCause() != null) rootCause = rootCause.getCause();
    assertThat(rootCause)
        .isInstanceOfSatisfying(
            org.postgresql.util.PSQLException.class,
            postgresFailure -> assertThat(postgresFailure.getSQLState()).isEqualTo(expectedState));
  }

  private static long rowCount(DSLContext dsl, String table) {
    return dsl.fetchSingle("SELECT count(*)::bigint AS row_count FROM " + table)
        .get("row_count", Long.class);
  }

  private static List<String> authorityGenerationSnapshot(DSLContext dsl) {
    return dsl
        .fetch(
            "SELECT scope_kind || '|' || COALESCE(issuer_id, '') || '|' "
                + "|| COALESCE(account_uuid::TEXT, '') || '|' "
                + "|| COALESCE(tenant_uuid::TEXT, '') || '|' "
                + "|| generation::TEXT || '|' || source_version::TEXT AS generation_state "
                + "FROM account_authority_generations "
                + "ORDER BY scope_kind, issuer_id NULLS FIRST, account_uuid NULLS FIRST, "
                + "tenant_uuid NULLS FIRST")
        .stream()
        .map(row -> row.get("generation_state", String.class))
        .toList();
  }

  private static List<String> encoded(List<SourceEvidence> sources) {
    return sources.stream()
        .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
        .toList();
  }

  private static <T> T withWorldCurrentness(
      AccountStartSessionOperatorAuthorizationService issuer,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      UUID gameSessionAttemptId,
      long gameSessionOwnerFence,
      Function<AccountStartSessionAuthorityCapture, T> callback) {
    try (var ignored = AccountControlUiOwnerSourcesFixture.withPeer(WORLD_PEER_URI)) {
      return issuer.withCurrentWorldReceivingParticipationCurrentness(
          originalTuple.canonicalBytes(), gameSessionAttemptId, gameSessionOwnerFence, callback);
    }
  }

  private static <T> T withPeer(String peerUri, java.util.concurrent.Callable<T> action) {
    try (var ignored = AccountControlUiOwnerSourcesFixture.withPeer(peerUri)) {
      return action.call();
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Test peer-scoped Account action failed", failure);
    }
  }

  private static AccountStartSessionAdmissionProtectionEvidence acquire(
      Prepared prepared, AccountStartSessionAdmissionProtectionAcquisitionService service) {
    return withPeer(GAME_SESSION_PEER_URI, () -> service.acquire(prepared.acquisitionRequest));
  }

  private static void awaitDatabaseTime(DSLContext dsl, Instant deadline, Duration timeout)
      throws InterruptedException {
    long stopAt = System.nanoTime() + timeout.toNanos();
    while (databaseNow(dsl).isBefore(deadline)) {
      if (System.nanoTime() >= stopAt) {
        throw new IllegalStateException("Database clock did not reach the original lease expiry");
      }
      TimeUnit.MILLISECONDS.sleep(20);
    }
  }

  private static Instant databaseNow(DSLContext dsl) {
    Record row = dsl.fetchSingle("SELECT clock_timestamp() AS database_now");
    OffsetDateTime value = row.get("database_now", OffsetDateTime.class);
    return value.toInstant();
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to release the Account issuance row");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while holding the Account issuance row", interrupted);
    }
  }

  private static String preparationInput(
      String namespace, UUID tenantId, String requestId, UUID gameInstanceId, String worldSlug)
      throws Exception {
    String descriptorJson =
        JSON.writeValueAsString(Map.of("gameTemplateId", ORIGINAL_GAME_TEMPLATE_ID));
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

  private static String digest(String value) {
    return "sha256:" + sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256Hex(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
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

  private static WorldStartSessionExecutionTerminal terminal(StoredParticipation participation) {
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
        WORLD_EXECUTION_FENCE,
        WorldStartSessionExecutionTerminal.Outcome.COMMITTED);
  }

  private record Prepared(
      AccountControlUiOriginalOrderFixture controlUi,
      IssuedCreator creator,
      AccountControlUiOwnerSourcesFixture account,
      AccountStartSessionOperatorAuthorizationService operatorAuthorization,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      AccountStartSessionAuthorityCapture capture,
      AccountStartSessionWorldParticipationRepository participationRepository,
      AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptEvidenceRepository,
      StoredParticipation participation,
      UUID gameSessionMutationId,
      UUID gameSessionAttemptId,
      Result originalObservation,
      AccountStartSessionAdmissionProtectionAcquisitionService.AcquisitionRequest
          acquisitionRequest,
      OriginalStartSessionCurrentAttemptClient gameSessionClient,
      List<Request> reads,
      CountDownLatch remoteRead,
      AtomicReference<Instant> remoteReadAt)
      implements AutoCloseable {
    AccountStartSessionAdmissionProtectionAcquisitionService acquisitionService(
        PlatformTransactionManager readbackManager) {
      return new AccountStartSessionAdmissionProtectionAcquisitionService(
          operatorAuthorization,
          participationRepository,
          attemptEvidenceRepository,
          new AccountStartSessionAdmissionProtectionRepository(account.dsl),
          gameSessionClient,
          readbackManager,
          NAMESPACE);
    }

    AccountStartSessionAdmissionProtectionEvidence acquire(
        AccountStartSessionAdmissionProtectionAcquisitionService service) {
      return AccountStartSessionAdmissionProtectionAcquisitionPostgresIntegrationTest.acquire(
          this, service);
    }

    List<SourceEvidence> captureSources() {
      return participation.sources();
    }

    void settleWorldWithStipulatedTerminal() {
      account.tx(() -> participationRepository.settleExact(terminal(participation)));
    }

    @Override
    public void close() {
      controlUi.close();
    }
  }

  private static final class CommitThenLoseOneAcknowledgement
      implements PlatformTransactionManager {
    private final PlatformTransactionManager delegate;
    private final AtomicBoolean loseNextCommit = new AtomicBoolean(true);
    private final AtomicBoolean lostAcknowledgement = new AtomicBoolean();

    private CommitThenLoseOneAcknowledgement(PlatformTransactionManager delegate) {
      this.delegate = delegate;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      return delegate.getTransaction(definition);
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
      delegate.commit(status);
      if (loseNextCommit.compareAndSet(true, false)) {
        lostAcknowledgement.set(true);
        throw new LostReadbackAcknowledgement();
      }
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
      delegate.rollback(status);
    }

    boolean didLoseAcknowledgement() {
      return lostAcknowledgement.get();
    }
  }

  private static final class LostReadbackAcknowledgement extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
