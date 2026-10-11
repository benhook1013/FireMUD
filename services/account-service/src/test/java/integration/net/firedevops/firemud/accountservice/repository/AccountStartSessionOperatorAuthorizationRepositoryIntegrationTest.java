package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.IssuanceCandidate;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.RedemptionRequest;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class AccountStartSessionOperatorAuthorizationRepositoryIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private final Set<String> schemas = new HashSet<>();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropTestOwnedSchemas() {
    JdbcTemplate jdbc = new JdbcTemplate(POSTGRES.dataSource());
    for (String schema : schemas) {
      if (!schema.matches("account_start_session_auth_[a-f0-9]{32}")) {
        throw new IllegalStateException("Refusing to dispose an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    schemas.clear();
  }

  @Test
  void realPostgresCreateRetryAndRedemptionRetainTheOriginalAuthorityRecord() {
    Context context = context();
    IssuanceCandidate candidate = candidate(context, tuple());

    var created = context.inTransaction(() -> context.repository.createOrReadExact(candidate));
    IssuanceCandidate differentOutput = withDifferentOutputs(candidate);
    var duplicate =
        context.inTransaction(() -> context.repository.createOrReadExact(differentOutput));

    assertThat(created.created()).isTrue();
    assertThat(duplicate.created()).isFalse();
    assertThat(duplicate.issuance().issuanceOperationId())
        .isEqualTo(candidate.issuanceOperationId());
    assertThat(duplicate.issuance().encryptedResponseEnvelope())
        .containsExactly(candidate.encryptedResponseEnvelope());
    assertThat(duplicate.issuance().responseEnvelopeErasedAt()).isNull();
    assertThat(duplicate.issuance().bundleReference()).isEqualTo(candidate.bundleReference());
    var persistedBeforeRedemption =
        context.inTransaction(
            () -> context.repository.findByControlPlaneRequestId(REQUEST_ID).orElseThrow());
    assertThat(persistedBeforeRedemption.status())
        .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.ISSUED);

    RedemptionRequest request = redemption(candidate, OWNER_ATTEMPT_ID, 11L);
    var redeemed = context.inTransaction(() -> context.repository.redeemExact(request));
    var replay = context.inTransaction(() -> context.repository.redeemExact(request));
    assertThat(redeemed.replay()).isFalse();
    assertThat(replay.replay()).isTrue();
    assertThat(replay.authorityEvidenceBundle()).containsExactly(bytes("authority-bundle-v1"));
    var retainedAfterRedemption =
        context.inTransaction(
            () -> context.repository.findByControlPlaneRequestId(REQUEST_ID).orElseThrow());
    assertThat(retainedAfterRedemption.bundleReference()).isEqualTo(candidate.bundleReference());
    assertThat(retainedAfterRedemption.bundleReference().linearization()).isEqualTo("123456789");
    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "UPDATE account_start_session_operator_authorizations "
                                + "SET bundle_linearization = ? WHERE control_plane_request_id = ?",
                            "123456788",
                            REQUEST_ID)))
        .isInstanceOf(IntegrityConstraintViolationException.class);
    assertThat(
            context.inTransaction(
                () ->
                    context
                        .repository
                        .findByControlPlaneRequestId(REQUEST_ID)
                        .orElseThrow()
                        .status()))
        .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.REDEEMED);
  }

  @Test
  void realPostgresAllowsEqualIssueAndRedemptionTimestampsAtMillisecondPrecision() {
    Context context = context();
    IssuanceCandidate candidate = candidate(context, tuple());
    context.inTransaction(() -> context.repository.createOrReadExact(candidate));

    RedemptionRequest request = redemption(candidate, OWNER_ATTEMPT_ID, 11L, candidate.issuedAt());
    var redeemed = context.inTransaction(() -> context.repository.redeemExact(request));

    assertThat(redeemed.replay()).isFalse();
    var persisted =
        context.inTransaction(
            () -> context.repository.findByControlPlaneRequestId(REQUEST_ID).orElseThrow());
    assertThat(persisted.redeemedAt()).isEqualTo(candidate.issuedAt());
    assertThat(persisted.status())
        .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.REDEEMED);
  }

  @Test
  void realPostgresRejectsRedemptionTimestampEarlierThanIssuance() {
    Context context = context();
    IssuanceCandidate candidate = candidate(context, tuple());
    context.inTransaction(() -> context.repository.createOrReadExact(candidate));

    RedemptionRequest request =
        redemption(candidate, OWNER_ATTEMPT_ID, 11L, candidate.issuedAt().minusMillis(1));
    assertThatThrownBy(() -> context.inTransaction(() -> context.repository.redeemExact(request)))
        .isInstanceOf(IntegrityConstraintViolationException.class);

    var persisted =
        context.inTransaction(
            () -> context.repository.findByControlPlaneRequestId(REQUEST_ID).orElseThrow());
    assertThat(persisted.status())
        .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.ISSUED);
    assertThat(persisted.redeemedAt()).isNull();
  }

  @Test
  void realPostgresAcceptsCanonicalNfcRequestIdAt128Utf8BytesAndRejectsOversizeBeforeInsert() {
    Context context = context();
    String boundedRequestId = "é".repeat(64);
    StartSessionPreAuthorizationReservationTuple boundaryTuple =
        tuple(boundedRequestId, "128-byte NFC request ID boundary");
    IssuanceCandidate candidate = candidate(context, boundaryTuple, boundedRequestId);

    var created = context.inTransaction(() -> context.repository.createOrReadExact(candidate));
    var retry = context.inTransaction(() -> context.repository.createOrReadExact(candidate));
    assertThat(created.created()).isTrue();
    assertThat(retry.created()).isFalse();
    assertThat(retry.issuance().controlPlaneRequestId()).isEqualTo(boundedRequestId);
    assertThat(
            context.inTransaction(
                () -> context.repository.findByControlPlaneRequestId(boundedRequestId)))
        .isPresent();

    String oversizedRequestId = "é".repeat(65);
    String malformedTupleJson =
        boundaryTuple
            .canonicalJson()
            .replace(
                "\"requestId\":\"" + boundedRequestId + "\"",
                "\"requestId\":\"" + oversizedRequestId + "\"");
    assertThat(malformedTupleJson).contains(oversizedRequestId);
    IssuanceCandidate oversized =
        candidate(
            context, boundedRequestId, bytes(malformedTupleJson), boundaryTuple.mutationDigest());
    int before = context.countRows();
    assertThatThrownBy(
            () -> context.inTransaction(() -> context.repository.createOrReadExact(oversized)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(context.countRows()).isEqualTo(before);
  }

  @Test
  void realPostgresAcceptsSharedRaw64DigestAndRejectsPrefixedMalformedOrChangedDigests() {
    Context context = context();
    StartSessionPreAuthorizationReservationTuple tuple = tuple();
    IssuanceCandidate canonical = candidate(context, tuple);

    assertThat(canonical.mutationDigest()).matches("[0-9a-f]{64}");
    assertThat(canonical.mutationDigest()).doesNotStartWith("sha256:");
    var created = context.inTransaction(() -> context.repository.createOrReadExact(canonical));
    assertThat(created.created()).isTrue();
    assertThat(created.issuance().mutationDigest()).isEqualTo(tuple.mutationDigest());

    String digest = canonical.mutationDigest();
    String changedDigest = (digest.charAt(0) == '0' ? "1" : "0") + digest.substring(1);
    for (String invalidDigest : new String[] {"sha256:" + digest, "not-a-digest", changedDigest}) {
      IssuanceCandidate invalid =
          candidate(
              context,
              canonical.controlPlaneRequestId(),
              canonical.preAuthorizationTuple(),
              invalidDigest);
      int rowsBeforeInvalidDigest = context.countRows();

      assertThatThrownBy(
              () -> context.inTransaction(() -> context.repository.createOrReadExact(invalid)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(context.countRows()).isEqualTo(rowsBeforeInvalidDigest);
    }
  }

  @Test
  void realPostgresAndRepositoryAcceptOnlyThe160KiBEnvelopeStorageBound() {
    Context context = context();
    IssuanceCandidate base = candidate(context, tuple());
    byte[] maximumEnvelope = new byte[160 * 1024];
    Arrays.fill(maximumEnvelope, (byte) 0x5a);
    IssuanceCandidate maximum = withEnvelope(base, maximumEnvelope);

    var created = context.inTransaction(() -> context.repository.createOrReadExact(maximum));
    assertThat(created.created()).isTrue();
    assertThat(created.issuance().encryptedResponseEnvelope()).containsExactly(maximumEnvelope);

    IssuanceCandidate overLimit =
        candidateWithUniqueIdentity(
            context, new byte[(160 * 1024) + 1], "repository and SQL envelope bound + 1");
    int rowCountBeforeRejectedCandidates = context.countRows();
    assertThatThrownBy(
            () -> context.inTransaction(() -> context.repository.createOrReadExact(overLimit)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("encrypted response envelope");
    assertThat(context.countRows()).isEqualTo(rowCountBeforeRejectedCandidates);

    assertThatThrownBy(() -> insertV126Row(context, overLimit))
        .isInstanceOf(IntegrityConstraintViolationException.class)
        .hasMessageContaining("account_start_session_operator_authz_envelope_size_bound");
    assertThat(context.countRows()).isEqualTo(rowCountBeforeRejectedCandidates);
  }

  @Test
  void realPostgresFingerprintConstraintAllowsMaximumKeyIdAndRejectsDot() {
    Context context = context();
    IssuanceCandidate maximumKeyId =
        withFingerprint(
            candidate(context, tuple()),
            "arfp/v1/" + "k".repeat(64) + "/" + "a".repeat(64),
            ISSUANCE_OPERATION_ID);

    assertThat(maximumKeyId.authorizationReferenceFingerprint()).hasSize(137);
    assertThat(
            context
                .inTransaction(() -> context.repository.createOrReadExact(maximumKeyId))
                .created())
        .isTrue();
    assertThat(
            context
                .inTransaction(
                    () ->
                        context.repository.redeemExact(
                            redemption(maximumKeyId, OWNER_ATTEMPT_ID, 11L)))
                .replay())
        .isFalse();

    String dottedRequestId = REQUEST_ID + "-dot-key";
    StartSessionPreAuthorizationReservationTuple dottedTuple =
        tuple(dottedRequestId, "invalid fingerprint key ID database constraint");
    IssuanceCandidate dottedKeyId =
        withFingerprint(
            candidate(context, dottedTuple, dottedRequestId),
            "arfp/v1/key.with.dot/" + "b".repeat(64),
            UUID.randomUUID());

    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "INSERT INTO account_start_session_operator_authorizations "
                                + "(control_plane_request_id, pre_authorization_tuple, "
                                + "mutation_digest, issuance_workload_uri, reservation_owner_id, "
                                + "reservation_claim_fence, "
                                + "issuance_operation_id, issuance_fence, bundle_version, "
                                + "bundle_source_version, bundle_source_fence, "
                                + "bundle_linearization, authority_evidence_bundle, "
                                + "authorization_reference_fingerprint, "
                                + "encrypted_response_envelope, issued_at, reference_expires_at, "
                                + "response_envelope_expires_at, status) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                                + "'ISSUED')",
                            dottedKeyId.controlPlaneRequestId(),
                            dottedKeyId.preAuthorizationTuple(),
                            dottedKeyId.mutationDigest(),
                            dottedKeyId.issuanceWorkloadUri(),
                            dottedKeyId.reservationOwnerId(),
                            dottedKeyId.reservationClaimFence(),
                            dottedKeyId.issuanceOperationId(),
                            dottedKeyId.issuanceFence(),
                            dottedKeyId.bundleReference().bundleVersion(),
                            dottedKeyId.bundleReference().sourceVersion(),
                            dottedKeyId.bundleReference().sourceFence(),
                            dottedKeyId.bundleReference().linearization(),
                            dottedKeyId.authorityEvidenceBundle(),
                            dottedKeyId.authorizationReferenceFingerprint(),
                            dottedKeyId.encryptedResponseEnvelope(),
                            OffsetDateTime.ofInstant(dottedKeyId.issuedAt(), ZoneOffset.UTC),
                            OffsetDateTime.ofInstant(
                                dottedKeyId.referenceExpiresAt(), ZoneOffset.UTC),
                            OffsetDateTime.ofInstant(
                                dottedKeyId.responseEnvelopeExpiresAt(), ZoneOffset.UTC))))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(context.countRows()).isEqualTo(1);
  }

  @Test
  void concurrentExactIssuanceRetriesReturnOneOriginalOutput() throws Exception {
    Context context = context();
    IssuanceCandidate firstCandidate = candidate(context, tuple());
    IssuanceCandidate secondCandidate = withDifferentOutputs(firstCandidate);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult> first =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting to race exact issuance retry");
                }
                return context.inTransaction(
                    () -> context.repository.createOrReadExact(firstCandidate));
              });
      Future<AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult> second =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting to race exact issuance retry");
                }
                return context.inTransaction(
                    () -> context.repository.createOrReadExact(secondCandidate));
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var firstResult = first.get(10, TimeUnit.SECONDS);
      var secondResult = second.get(10, TimeUnit.SECONDS);

      assertThat(
              List.of(firstResult, secondResult).stream()
                  .filter(
                      AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult
                          ::created)
                  .toList())
          .hasSize(1);
      assertThat(firstResult.issuance().issuanceOperationId())
          .isEqualTo(secondResult.issuance().issuanceOperationId());
      assertThat(firstResult.issuance().encryptedResponseEnvelope())
          .containsExactly(secondResult.issuance().encryptedResponseEnvelope());
      assertThat(firstResult.issuance().issuanceOperationId())
          .isIn(firstCandidate.issuanceOperationId(), secondCandidate.issuanceOperationId());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void changedTupleOrReservationBindingConflictsAndMalformedTupleNeverWrites() {
    Context context = context();
    IssuanceCandidate candidate = candidate(context, tuple());
    context.inTransaction(() -> context.repository.createOrReadExact(candidate));
    StartSessionPreAuthorizationReservationTuple changedTuple = tuple("changed audit reason");
    IssuanceCandidate changedTupleCandidate =
        copyCandidate(
            candidate,
            canonicalBytes(changedTuple),
            changedTuple.mutationDigest(),
            RESERVATION_OWNER_ID,
            candidate.reservationClaimFence());
    IssuanceCandidate changedClaim =
        copyCandidate(
            candidate,
            candidate.preAuthorizationTuple(),
            candidate.mutationDigest(),
            RESERVATION_OWNER_ID,
            candidate.reservationClaimFence() + 1L);

    assertThatThrownBy(
            () ->
                context.inTransaction(
                    () -> context.repository.createOrReadExact(changedTupleCandidate)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
    assertThatThrownBy(
            () -> context.inTransaction(() -> context.repository.createOrReadExact(changedClaim)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");

    int beforeMalformed = context.countRows();
    IssuanceCandidate malformed =
        copyCandidate(
            candidate,
            bytes("{malformed"),
            candidate.mutationDigest(),
            candidate.reservationOwnerId(),
            candidate.reservationClaimFence());
    assertThatThrownBy(
            () -> context.inTransaction(() -> context.repository.createOrReadExact(malformed)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(context.countRows()).isEqualTo(beforeMalformed);
  }

  @Test
  void twoConcurrentDifferentOwnerAttemptsCanConsumeOnlyOneReference() throws Exception {
    Context context = context();
    IssuanceCandidate candidate = candidate(context, tuple());
    context.inTransaction(() -> context.repository.createOrReadExact(candidate));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> first =
          executor.submit(() -> raceRedemption(context, candidate, ready, start, 12L));
      Future<Object> second =
          executor.submit(() -> raceRedemption(context, candidate, ready, start, 13L));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      Object firstResult = first.get(10, TimeUnit.SECONDS);
      Object secondResult = second.get(10, TimeUnit.SECONDS);
      List<Object> results = List.of(firstResult, secondResult);
      assertThat(
              results.stream()
                  .filter(
                      AccountStartSessionOperatorAuthorizationRepository.RedemptionResult.class
                          ::isInstance)
                  .toList())
          .hasSize(1);
      assertThat(results.stream().filter(IllegalStateException.class::isInstance).toList())
          .hasSize(1);
      var retained =
          context.inTransaction(
              () -> context.repository.findByControlPlaneRequestId(REQUEST_ID).orElseThrow());
      assertThat(retained.status())
          .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.REDEEMED);
      assertThat(retained.redemptionOwnerAttemptId())
          .isIn(
              UUID.fromString("c0df9691-cba5-4274-a49d-0bc7b2158ef7"),
              UUID.fromString("c0df9691-cba5-4274-a49d-0bc7b2158ef8"));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void expiredReferenceCannotBeConsumedAndSqlGuardsRetainEvidence() {
    Context context = context();
    IssuanceCandidate candidate = candidate(context, tuple());
    context.inTransaction(() -> context.repository.createOrReadExact(candidate));
    RedemptionRequest expired =
        new RedemptionRequest(
            candidate.controlPlaneRequestId(),
            candidate.preAuthorizationTuple(),
            candidate.authorizationReferenceFingerprint(),
            candidate.reservationOwnerId(),
            candidate.reservationClaimFence(),
            OWNER_URI,
            OWNER_ATTEMPT_ID,
            11L,
            candidate.authorityEvidenceBundle(),
            candidate.referenceExpiresAt());
    assertThatThrownBy(() -> context.inTransaction(() -> context.repository.redeemExact(expired)))
        .isInstanceOf(
            AccountStartSessionOperatorAuthorizationRepository.ReferenceExpiredException.class);

    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "UPDATE account_start_session_operator_authorizations "
                                + "SET authority_evidence_bundle = ? WHERE control_plane_request_id = ?",
                            bytes("rewritten"),
                            REQUEST_ID)))
        .isInstanceOf(IntegrityConstraintViolationException.class);
    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "DELETE FROM account_start_session_operator_authorizations "
                                + "WHERE control_plane_request_id = ?",
                            REQUEST_ID)))
        .isInstanceOf(IntegrityConstraintViolationException.class);
    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "TRUNCATE account_start_session_operator_authorizations")))
        .isInstanceOf(IntegrityConstraintViolationException.class);

    var retained =
        context.inTransaction(
            () -> context.repository.findByControlPlaneRequestId(REQUEST_ID).orElseThrow());
    assertThat(retained.status())
        .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.ISSUED);
    assertThat(retained.authorityEvidenceBundle())
        .containsExactly(candidate.authorityEvidenceBundle());
  }

  @Test
  void responseEnvelopeErasureUsesDatabaseWallClockAndPreservesRedeemedCompactEvidence() {
    Context context = context();
    Instant databaseNow = canonicalDatabaseNow(context);
    IssuanceCandidate candidate =
        candidate(
            tuple(),
            databaseNow.minusSeconds(2),
            databaseNow.plusSeconds(10),
            databaseNow.plusSeconds(12));
    context.inTransaction(() -> context.repository.createOrReadExact(candidate));

    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "UPDATE account_start_session_operator_authorizations "
                                + "SET encrypted_response_envelope = NULL "
                                + "WHERE control_plane_request_id = ?",
                            candidate.controlPlaneRequestId())))
        .isInstanceOf(IntegrityConstraintViolationException.class);

    var erased =
        context.inTransaction(
            () -> {
              assertThat(
                      ((OffsetDateTime)
                              context.transactionDsl.fetchValue("SELECT transaction_timestamp()"))
                          .toInstant())
                  .isBefore(candidate.responseEnvelopeExpiresAt());
              var redeemed =
                  context.repository.redeemExact(
                      redemption(
                          candidate, OWNER_ATTEMPT_ID, 11L, candidate.issuedAt().plusSeconds(1)));
              assertThat(redeemed.replay()).isFalse();
              assertThatThrownBy(
                      () ->
                          context.repository.eraseExpiredResponseEnvelope(
                              candidate.controlPlaneRequestId()))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("evidence unavailable");

              waitUntilDatabaseTime(context, candidate.responseEnvelopeExpiresAt());
              return context.repository.eraseExpiredResponseEnvelope(
                  candidate.controlPlaneRequestId());
            });

    assertThat(erased.erasedNow()).isTrue();
    assertThat(erased.issuance().encryptedResponseEnvelope()).isNull();
    assertThat(erased.issuance().responseEnvelopeErasedAt())
        .isNotNull()
        .isAfterOrEqualTo(candidate.responseEnvelopeExpiresAt());
    assertThat(erased.issuance().status())
        .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.REDEEMED);
    assertThat(erased.issuance().controlPlaneRequestId())
        .isEqualTo(candidate.controlPlaneRequestId());
    assertThat(erased.issuance().preAuthorizationTuple())
        .containsExactly(candidate.preAuthorizationTuple());
    assertThat(erased.issuance().mutationDigest()).isEqualTo(candidate.mutationDigest());
    assertThat(erased.issuance().authorizationReferenceFingerprint())
        .isEqualTo(candidate.authorizationReferenceFingerprint());
    assertThat(erased.issuance().authorityEvidenceBundle())
        .containsExactly(candidate.authorityEvidenceBundle());
    assertThat(erased.issuance().redemptionOwnerAttemptId()).isEqualTo(OWNER_ATTEMPT_ID);
    assertThat(erased.issuance().redemptionOwnerFence()).isEqualTo(11L);
    assertThat(erased.issuance().redemptionReferenceFingerprint())
        .isEqualTo(candidate.authorizationReferenceFingerprint());
    assertThat(erased.issuance().redemptionAuthorityEvidenceBundle())
        .containsExactly(candidate.authorityEvidenceBundle());

    var retry =
        context.inTransaction(
            () ->
                context.repository.eraseExpiredResponseEnvelope(candidate.controlPlaneRequestId()));
    assertThat(retry.erasedNow()).isFalse();
    assertThat(retry.issuance().responseEnvelopeErasedAt())
        .isEqualTo(erased.issuance().responseEnvelopeErasedAt());

    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "UPDATE account_start_session_operator_authorizations "
                                + "SET encrypted_response_envelope = ? "
                                + "WHERE control_plane_request_id = ?",
                            candidate.encryptedResponseEnvelope(),
                            candidate.controlPlaneRequestId())))
        .isInstanceOf(IntegrityConstraintViolationException.class);
    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "UPDATE account_start_session_operator_authorizations "
                                + "SET redemption_owner_fence = redemption_owner_fence + 1 "
                                + "WHERE control_plane_request_id = ?",
                            candidate.controlPlaneRequestId())))
        .isInstanceOf(IntegrityConstraintViolationException.class);

    Instant expiredAt = canonicalDatabaseNow(context);
    StartSessionPreAuthorizationReservationTuple expiredTuple =
        tuple("start-session/operator/" + UUID.randomUUID(), "expired erase-state guard test");
    IssuanceCandidate expiredUnredeemed = expiredCandidate(expiredTuple, expiredAt);
    context.inTransaction(() -> context.repository.createOrReadExact(expiredUnredeemed));
    Instant redeemedAt = expiredUnredeemed.issuedAt().plusSeconds(1);
    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "UPDATE account_start_session_operator_authorizations "
                                + "SET encrypted_response_envelope = NULL, status = 'REDEEMED', "
                                + "redemption_redeemer_workload_uri = ?, "
                                + "redemption_owner_attempt_id = ?, redemption_owner_fence = ?, "
                                + "redeemed_at = CAST(? AS TIMESTAMPTZ), "
                                + "redemption_reference_fingerprint = ?, "
                                + "redemption_authority_evidence_bundle = ? "
                                + "WHERE control_plane_request_id = ?",
                            OWNER_URI,
                            OWNER_ATTEMPT_ID,
                            11L,
                            OffsetDateTime.ofInstant(redeemedAt, ZoneOffset.UTC),
                            expiredUnredeemed.authorizationReferenceFingerprint(),
                            expiredUnredeemed.authorityEvidenceBundle(),
                            expiredUnredeemed.controlPlaneRequestId())))
        .isInstanceOf(IntegrityConstraintViolationException.class);

    var retained =
        context.inTransaction(
            () ->
                context
                    .repository
                    .findByControlPlaneRequestId(candidate.controlPlaneRequestId())
                    .orElseThrow());
    assertThat(retained.encryptedResponseEnvelope()).isNull();
    assertThat(retained.responseEnvelopeErasedAt())
        .isEqualTo(erased.issuance().responseEnvelopeErasedAt());
    assertThat(retained.status())
        .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.REDEEMED);
    assertThat(retained.redemptionOwnerAttemptId()).isEqualTo(OWNER_ATTEMPT_ID);
  }

  @Test
  void v126RetainedEnvelopeSurvivesAdditiveErasureMigration() {
    Context context = contextAt("126");
    IssuanceCandidate candidate = candidate(context, tuple());
    insertV126Row(context, candidate);

    Flyway.configure()
        .dataSource(context.dataSource)
        .schemas(context.schema)
        .defaultSchema(context.schema)
        .placeholders(Map.of("serviceSchema", context.schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    var retained =
        context.inTransaction(
            () -> context.repository.findByControlPlaneRequestId(REQUEST_ID).orElseThrow());
    assertThat(retained.encryptedResponseEnvelope())
        .containsExactly(candidate.encryptedResponseEnvelope());
    assertThat(retained.responseEnvelopeErasedAt()).isNull();
    assertThat(retained.preAuthorizationTuple()).containsExactly(candidate.preAuthorizationTuple());
    assertThat(retained.authorizationReferenceFingerprint())
        .isEqualTo(candidate.authorizationReferenceFingerprint());
  }

  @Test
  void v1262RetainedMaximumLegacyEnvelopeSurvivesForwardSizeMigration() {
    Context context = contextAt("126.2");
    IssuanceCandidate base = candidate(context, tuple());
    byte[] retainedLegacyEnvelope = new byte[64 * 1024];
    Arrays.fill(retainedLegacyEnvelope, (byte) 0x2d);
    IssuanceCandidate legacy = withEnvelope(base, retainedLegacyEnvelope);
    insertV126Row(context, legacy);

    Flyway.configure()
        .dataSource(context.dataSource)
        .schemas(context.schema)
        .defaultSchema(context.schema)
        .placeholders(Map.of("serviceSchema", context.schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    var retained =
        context.inTransaction(
            () -> context.repository.findByControlPlaneRequestId(REQUEST_ID).orElseThrow());
    assertThat(retained.encryptedResponseEnvelope()).containsExactly(retainedLegacyEnvelope);
    assertThat(retained.responseEnvelopeErasedAt()).isNull();
    assertThat(retained.preAuthorizationTuple()).containsExactly(legacy.preAuthorizationTuple());
    assertThat(retained.authorityEvidenceBundle())
        .containsExactly(legacy.authorityEvidenceBundle());
    assertThat(retained.status())
        .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.ISSUED);

    assertThatThrownBy(
            () ->
                context.inTransactionWithoutResult(
                    () ->
                        context.transactionDsl.execute(
                            "UPDATE account_start_session_operator_authorizations "
                                + "SET authority_evidence_bundle = ? "
                                + "WHERE control_plane_request_id = ?",
                            bytes("changed-retained-evidence"),
                            legacy.controlPlaneRequestId())))
        .isInstanceOf(IntegrityConstraintViolationException.class);
    assertThat(
            context.inTransaction(
                () ->
                    context
                        .repository
                        .findByControlPlaneRequestId(legacy.controlPlaneRequestId())
                        .orElseThrow()
                        .encryptedResponseEnvelope()))
        .containsExactly(retainedLegacyEnvelope);
  }

  @Test
  void v1263FailsClosedWhenLegacyEnvelopeSizeGuardIsAmbiguous() {
    Context context = contextAt("126.2");
    context.adminDsl.execute(
        "ALTER TABLE account_start_session_operator_authorizations "
            + "ADD CONSTRAINT account_start_session_operator_authorizations_duplicate_size_guard "
            + "CHECK (octet_length(encrypted_response_envelope) BETWEEN 1 AND 65536)");
    assertThat(legacyEnvelopeSizeGuardCount(context)).isEqualTo(2L);

    assertThatThrownBy(
            () ->
                Flyway.configure()
                    .dataSource(context.dataSource)
                    .schemas(context.schema)
                    .defaultSchema(context.schema)
                    .placeholders(Map.of("serviceSchema", context.schema))
                    .locations("classpath:db/migration")
                    .load()
                    .migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("V126.3");

    assertThat(legacyEnvelopeSizeGuardCount(context)).isEqualTo(2L);
  }

  private long legacyEnvelopeSizeGuardCount(Context context) {
    var countRecord =
        Objects.requireNonNull(
            context.adminDsl.fetchOne(
                "SELECT count(*) "
                    + "FROM pg_constraint c "
                    + "JOIN pg_attribute a ON a.attrelid = c.conrelid "
                    + "AND a.attname = 'encrypted_response_envelope' AND NOT a.attisdropped "
                    + "WHERE c.conrelid = 'account_start_session_operator_authorizations'::regclass "
                    + "AND c.contype = 'c' AND c.convalidated "
                    + "AND c.conkey = ARRAY[a.attnum]::SMALLINT[] "
                    + "AND lower(replace(regexp_replace(pg_get_expr(c.conbin, c.conrelid), "
                    + "'[[:space:]()]', '', 'g'), '\"', '')) IN ("
                    + "'octet_lengthencrypted_response_envelopebetween1and65536', "
                    + "'octet_lengthencrypted_response_envelope>=1andoctet_lengthencrypted_response_envelope<=65536')"),
            "Expected a count row for legacy envelope size guards");
    return Objects.requireNonNull(
        countRecord.get(0, Long.class), "Expected a non-null legacy envelope guard count");
  }

  private Context context() {
    return contextAt(null);
  }

  private Context contextAt(String targetVersion) {
    String schema = "account_start_session_auth_" + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
    var migration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (targetVersion != null) {
      migration.target(org.flywaydb.core.api.MigrationVersion.fromVersion(targetVersion));
    }
    migration.load().migrate();
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DSLContext adminDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(
        transactionDsl,
        adminDsl,
        transaction,
        new AccountStartSessionOperatorAuthorizationRepository(transactionDsl),
        dataSource,
        schema);
  }

  private static void insertV126Row(Context context, IssuanceCandidate candidate) {
    context.adminDsl.execute(
        "INSERT INTO account_start_session_operator_authorizations "
            + "(control_plane_request_id, pre_authorization_tuple, mutation_digest, "
            + "issuance_workload_uri, reservation_owner_id, reservation_claim_fence, "
            + "issuance_operation_id, issuance_fence, bundle_version, bundle_source_version, "
            + "bundle_source_fence, bundle_linearization, authority_evidence_bundle, "
            + "authorization_reference_fingerprint, encrypted_response_envelope, issued_at, "
            + "reference_expires_at, response_envelope_expires_at, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), "
            + "CAST(? AS TIMESTAMPTZ), 'ISSUED')",
        candidate.controlPlaneRequestId(),
        candidate.preAuthorizationTuple(),
        candidate.mutationDigest(),
        candidate.issuanceWorkloadUri(),
        candidate.reservationOwnerId(),
        candidate.reservationClaimFence(),
        candidate.issuanceOperationId(),
        candidate.issuanceFence(),
        candidate.bundleReference().bundleVersion(),
        candidate.bundleReference().sourceVersion(),
        candidate.bundleReference().sourceFence(),
        candidate.bundleReference().linearization(),
        candidate.authorityEvidenceBundle(),
        candidate.authorizationReferenceFingerprint(),
        candidate.encryptedResponseEnvelope(),
        OffsetDateTime.ofInstant(candidate.issuedAt(), ZoneOffset.UTC),
        OffsetDateTime.ofInstant(candidate.referenceExpiresAt(), ZoneOffset.UTC),
        OffsetDateTime.ofInstant(candidate.responseEnvelopeExpiresAt(), ZoneOffset.UTC));
  }

  private static Instant databaseNow(Context context) {
    return Objects.requireNonNull(
            (OffsetDateTime) context.adminDsl.fetchValue("SELECT clock_timestamp()"))
        .toInstant();
  }

  private static Instant canonicalDatabaseNow(Context context) {
    return databaseNow(context).truncatedTo(ChronoUnit.MILLIS);
  }

  private static void waitUntilDatabaseTime(Context context, Instant boundary) {
    long timeoutNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (databaseNow(context).isBefore(boundary)) {
      if (System.nanoTime() >= timeoutNanos) {
        throw new IllegalStateException("Timed out waiting for PostgreSQL wall clock boundary");
      }
      try {
        Thread.sleep(10L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted waiting for PostgreSQL wall clock boundary", interrupted);
      }
    }
  }

  private static Object raceRedemption(
      Context context,
      IssuanceCandidate candidate,
      CountDownLatch ready,
      CountDownLatch start,
      long ownerFence) {
    ready.countDown();
    try {
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to race redemption");
      }
      UUID attemptId =
          ownerFence == 12L
              ? UUID.fromString("c0df9691-cba5-4274-a49d-0bc7b2158ef7")
              : UUID.fromString("c0df9691-cba5-4274-a49d-0bc7b2158ef8");
      return context.inTransaction(
          () -> context.repository.redeemExact(redemption(candidate, attemptId, ownerFence)));
    } catch (IllegalStateException conflictOrUnavailable) {
      return conflictOrUnavailable;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new IllegalStateException("Interrupted while waiting to race redemption", interrupted);
    }
  }

  private static IssuanceCandidate withDifferentOutputs(IssuanceCandidate original) {
    return new IssuanceCandidate(
        original.controlPlaneRequestId(),
        original.preAuthorizationTuple(),
        original.mutationDigest(),
        original.issuanceWorkloadUri(),
        original.reservationOwnerId(),
        original.reservationClaimFence(),
        UUID.randomUUID(),
        original.issuanceFence() + 1L,
        bundleReference(original.issuanceFence() + 1L),
        bytes("different bundle"),
        "arfp/v1/key-2/" + "b".repeat(64),
        bytes("different encrypted response"),
        original.issuedAt().plusSeconds(1),
        original.referenceExpiresAt().plusSeconds(1),
        original.responseEnvelopeExpiresAt().plusSeconds(1));
  }

  private static IssuanceCandidate withFingerprint(
      IssuanceCandidate original, String fingerprint, UUID operationId) {
    return new IssuanceCandidate(
        original.controlPlaneRequestId(),
        original.preAuthorizationTuple(),
        original.mutationDigest(),
        original.issuanceWorkloadUri(),
        original.reservationOwnerId(),
        original.reservationClaimFence(),
        operationId,
        original.issuanceFence(),
        original.bundleReference(),
        original.authorityEvidenceBundle(),
        fingerprint,
        original.encryptedResponseEnvelope(),
        original.issuedAt(),
        original.referenceExpiresAt(),
        original.responseEnvelopeExpiresAt());
  }

  private static IssuanceCandidate copyCandidate(
      IssuanceCandidate original,
      byte[] tuple,
      String digest,
      UUID reservationOwner,
      long reservationFence) {
    return new IssuanceCandidate(
        original.controlPlaneRequestId(),
        tuple,
        digest,
        original.issuanceWorkloadUri(),
        reservationOwner,
        reservationFence,
        original.issuanceOperationId(),
        original.issuanceFence(),
        original.bundleReference(),
        original.authorityEvidenceBundle(),
        original.authorizationReferenceFingerprint(),
        original.encryptedResponseEnvelope(),
        original.issuedAt(),
        original.referenceExpiresAt(),
        original.responseEnvelopeExpiresAt());
  }

  private static IssuanceCandidate candidate(
      Context context, StartSessionPreAuthorizationReservationTuple tuple) {
    return candidate(context, tuple, REQUEST_ID);
  }

  private static IssuanceCandidate candidate(
      Context context, StartSessionPreAuthorizationReservationTuple tuple, String requestId) {
    return candidate(context, requestId, canonicalBytes(tuple), tuple.mutationDigest());
  }

  private static IssuanceCandidate candidate(
      Context context, String requestId, byte[] tupleBytes, String digest) {
    Instant issuedAt = canonicalDatabaseNow(context).minusSeconds(2);
    return new IssuanceCandidate(
        requestId,
        tupleBytes,
        digest,
        ISSUER_URI,
        RESERVATION_OWNER_ID,
        3L,
        ISSUANCE_OPERATION_ID,
        7L,
        bundleReference(7L),
        bytes("authority-bundle-v1"),
        "arfp/v1/key-1/" + "a".repeat(64),
        bytes("encrypted-original-response-envelope"),
        issuedAt,
        issuedAt.plusSeconds(180),
        issuedAt.plusSeconds(210));
  }

  private static IssuanceCandidate withEnvelope(
      IssuanceCandidate source, byte[] encryptedResponseEnvelope) {
    return new IssuanceCandidate(
        source.controlPlaneRequestId(),
        source.preAuthorizationTuple(),
        source.mutationDigest(),
        source.issuanceWorkloadUri(),
        source.reservationOwnerId(),
        source.reservationClaimFence(),
        source.issuanceOperationId(),
        source.issuanceFence(),
        source.bundleReference(),
        source.authorityEvidenceBundle(),
        source.authorizationReferenceFingerprint(),
        encryptedResponseEnvelope,
        source.issuedAt(),
        source.referenceExpiresAt(),
        source.responseEnvelopeExpiresAt());
  }

  private static IssuanceCandidate candidateWithUniqueIdentity(
      Context context, byte[] encryptedResponseEnvelope, String auditReason) {
    String requestId = "start-session/operator/" + UUID.randomUUID();
    var tuple = tuple(requestId, auditReason);
    IssuanceCandidate base = candidate(context, tuple, requestId);
    String randomHex =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    return new IssuanceCandidate(
        base.controlPlaneRequestId(),
        base.preAuthorizationTuple(),
        base.mutationDigest(),
        base.issuanceWorkloadUri(),
        base.reservationOwnerId(),
        base.reservationClaimFence(),
        UUID.randomUUID(),
        base.issuanceFence(),
        base.bundleReference(),
        base.authorityEvidenceBundle(),
        "arfp/v1/key-1/" + randomHex,
        encryptedResponseEnvelope,
        base.issuedAt(),
        base.referenceExpiresAt(),
        base.responseEnvelopeExpiresAt());
  }

  private static IssuanceCandidate candidate(
      StartSessionPreAuthorizationReservationTuple tuple,
      Instant issuedAt,
      Instant referenceExpiresAt,
      Instant responseEnvelopeExpiresAt) {
    return new IssuanceCandidate(
        tuple.controlPlaneRequestId(),
        canonicalBytes(tuple),
        tuple.mutationDigest(),
        ISSUER_URI,
        RESERVATION_OWNER_ID,
        3L,
        ISSUANCE_OPERATION_ID,
        7L,
        bundleReference(7L),
        bytes("authority-bundle-v1"),
        "arfp/v1/key-1/" + "a".repeat(64),
        bytes("encrypted-original-response-envelope"),
        issuedAt,
        referenceExpiresAt,
        responseEnvelopeExpiresAt);
  }

  private static IssuanceCandidate expiredCandidate(
      StartSessionPreAuthorizationReservationTuple tuple, Instant databaseNow) {
    String uniqueFingerprint =
        "arfp/v1/key-1/"
            + UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    return new IssuanceCandidate(
        tuple.controlPlaneRequestId(),
        canonicalBytes(tuple),
        tuple.mutationDigest(),
        ISSUER_URI,
        RESERVATION_OWNER_ID,
        3L,
        UUID.randomUUID(),
        8L,
        bundleReference(8L),
        bytes("expired-authority-bundle-v1"),
        uniqueFingerprint,
        bytes("expired-original-response-envelope"),
        databaseNow.minusSeconds(240),
        databaseNow.minusSeconds(60),
        databaseNow.minusSeconds(30));
  }

  private static RedemptionRequest redemption(
      IssuanceCandidate candidate, UUID ownerAttemptId, long ownerFence) {
    return redemption(candidate, ownerAttemptId, ownerFence, candidate.issuedAt().plusSeconds(1));
  }

  private static RedemptionRequest redemption(
      IssuanceCandidate candidate, UUID ownerAttemptId, long ownerFence, Instant now) {
    return new RedemptionRequest(
        candidate.controlPlaneRequestId(),
        candidate.preAuthorizationTuple(),
        candidate.authorizationReferenceFingerprint(),
        candidate.reservationOwnerId(),
        candidate.reservationClaimFence(),
        OWNER_URI,
        ownerAttemptId,
        ownerFence,
        candidate.authorityEvidenceBundle(),
        now);
  }

  private static StartSessionPreAuthorizationReservationTuple tuple() {
    return tuple("StartSession Account authorization repository integration test");
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(String auditReason) {
    return tuple(REQUEST_ID, auditReason);
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(
      String controlPlaneRequestId, String auditReason) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(17L, TARGET_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            auditReason);
    return StartSessionPreAuthorizationReservationTuple.createHuman(
        controlPlaneRequestId, ACTOR_ID, action);
  }

  private static byte[] canonicalBytes(StartSessionPreAuthorizationReservationTuple tuple) {
    return tuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static BundleReference bundleReference(long sourceFence) {
    return new BundleReference(
        "authorityEvidenceBundle/v1", "12", Long.toString(sourceFence), "123456789");
  }

  private static final UUID TENANT_ID = UUID.fromString("6d1e5ce5-6127-4d35-88b8-7a6f40692038");
  private static final UUID TARGET_ACCOUNT_ID =
      UUID.fromString("1df91ae6-5125-4e47-9f1d-2e45eab8a4a0");
  private static final UUID ACTOR_ID = UUID.fromString("d888ddc4-4a62-4b25-9bab-c4f94860c2ca");
  private static final String REQUEST_ID =
      "start-session/operator/2355f292-ab9c-458c-8d52-aef18b9254e8";
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("ca1b63bf-f09f-4a55-96bf-a1fd2e22349e");
  private static final UUID ISSUANCE_OPERATION_ID =
      UUID.fromString("37b1f37d-05d2-4aab-8ed9-c01acbf6606e");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("c0df9691-cba5-4274-a49d-0bc7b2158ef7");
  private static final String ISSUER_URI = "spiffe://firemud/ns/prod/sa/logging-admin-service";
  private static final String OWNER_URI = "spiffe://firemud/ns/prod/sa/game-session-service";

  private static final class Context {
    private final DSLContext transactionDsl;
    private final DSLContext adminDsl;
    private final TransactionTemplate transaction;
    private final AccountStartSessionOperatorAuthorizationRepository repository;
    private final DriverManagerDataSource dataSource;
    private final String schema;

    Context(
        DSLContext transactionDsl,
        DSLContext adminDsl,
        TransactionTemplate transaction,
        AccountStartSessionOperatorAuthorizationRepository repository,
        DriverManagerDataSource dataSource,
        String schema) {
      this.transactionDsl = transactionDsl;
      this.adminDsl = adminDsl;
      this.transaction = transaction;
      this.repository = repository;
      this.dataSource = dataSource;
      this.schema = schema;
    }

    <T> T inTransaction(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }

    void inTransactionWithoutResult(Runnable action) {
      transaction.executeWithoutResult(ignored -> action.run());
    }

    int countRows() {
      return Objects.requireNonNull(
              adminDsl.fetchOne(
                  "SELECT count(*) FROM account_start_session_operator_authorizations"),
              "Missing Account StartSession authorization row-count readback")
          .get(0, Integer.class);
    }
  }
}
