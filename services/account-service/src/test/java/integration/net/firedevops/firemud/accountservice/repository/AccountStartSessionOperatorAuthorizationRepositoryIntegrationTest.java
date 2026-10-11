package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.IssuanceCandidate;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.RedemptionRequest;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
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
      if (!schema.matches("account_start_session_[a-f0-9]{32}")) {
        throw new IllegalStateException("Refusing to dispose an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    schemas.clear();
  }

  @Test
  void realPostgresCreateRetryAndRedemptionRetainTheOriginalAuthorityRecord() {
    Context context = context();
    IssuanceCandidate candidate = candidate(tuple());
    assertThat(candidate.issuanceFence())
        .isNotEqualTo(Long.parseLong(candidate.bundleReference().sourceFence()));

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
    assertThat(replay.authorityEvidenceBundle())
        .containsExactly(candidate.authorityEvidenceBundle());
    var retainedAfterRedemption =
        context.inTransaction(
            () -> context.repository.findByControlPlaneRequestId(REQUEST_ID).orElseThrow());
    assertThat(retainedAfterRedemption.bundleReference()).isEqualTo(candidate.bundleReference());
    assertThat(retainedAfterRedemption.bundleReference().linearization()).isEqualTo("123456789");
    assertImmutableGuardRejected(
        () ->
            context.inTransactionWithoutResult(
                () ->
                    context.transactionDsl.execute(
                        "UPDATE account_start_session_operator_authorizations "
                            + "SET bundle_linearization = ? WHERE control_plane_request_id = ?",
                        "123456788",
                        REQUEST_ID)),
        "StartSession authorization evidence is immutable except one exact redemption");
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
  void realPostgresAcceptsCanonicalNfcRequestIdAt128Utf8BytesAndRejectsOversizeBeforeInsert() {
    Context context = context();
    String boundedRequestId = "é".repeat(64);
    StartSessionPreAuthorizationReservationTuple boundaryTuple =
        tuple(boundedRequestId, "128-byte NFC request ID boundary");
    IssuanceCandidate candidate = candidate(boundaryTuple, boundedRequestId);

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
    IssuanceCandidate boundaryCandidate = candidate(boundaryTuple, boundedRequestId);
    IssuanceCandidate oversized =
        new IssuanceCandidate(
            boundedRequestId,
            bytes(malformedTupleJson),
            boundaryTuple.mutationDigest(),
            boundaryCandidate.issuanceWorkloadUri(),
            boundaryCandidate.reservationOwnerId(),
            boundaryCandidate.reservationClaimFence(),
            boundaryCandidate.issuanceOperationId(),
            boundaryCandidate.issuanceFence(),
            boundaryCandidate.bundleReference(),
            boundaryCandidate.authorityEvidenceBundle(),
            boundaryCandidate.authorizationReferenceFingerprint(),
            boundaryCandidate.encryptedResponseEnvelope(),
            boundaryCandidate.issuedAt(),
            boundaryCandidate.referenceExpiresAt(),
            boundaryCandidate.responseEnvelopeExpiresAt());
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
    IssuanceCandidate canonical = candidate(tuple);

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
              canonical.controlPlaneRequestId(), canonical.preAuthorizationTuple(), invalidDigest);
      int rowsBeforeInvalidDigest = context.countRows();

      assertThatThrownBy(
              () -> context.inTransaction(() -> context.repository.createOrReadExact(invalid)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(context.countRows()).isEqualTo(rowsBeforeInvalidDigest);
    }
  }

  @Test
  void realPostgresFingerprintConstraintAllowsMaximumKeyIdAndRejectsDot() {
    Context context = context();
    IssuanceCandidate maximumKeyId =
        withFingerprint(
            candidate(tuple()),
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
            candidate(dottedTuple, dottedRequestId),
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
    IssuanceCandidate firstCandidate = candidate(tuple());
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
    IssuanceCandidate candidate = candidate(tuple());
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
    IssuanceCandidate candidate = candidate(tuple());
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
    IssuanceCandidate candidate = candidate(tuple());
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

    assertImmutableGuardRejected(
        () ->
            context.inTransactionWithoutResult(
                () ->
                    context.transactionDsl.execute(
                        "UPDATE account_start_session_operator_authorizations "
                            + "SET authority_evidence_bundle = ? WHERE control_plane_request_id = ?",
                        bytes("rewritten"),
                        REQUEST_ID)),
        "StartSession authorization evidence is immutable except one exact redemption");
    assertImmutableGuardRejected(
        () ->
            context.inTransactionWithoutResult(
                () ->
                    context.transactionDsl.execute(
                        "DELETE FROM account_start_session_operator_authorizations "
                            + "WHERE control_plane_request_id = ?",
                        REQUEST_ID)),
        "StartSession operator authorization evidence cannot be deleted");
    assertImmutableGuardRejected(
        () ->
            context.inTransactionWithoutResult(
                () ->
                    context.transactionDsl.execute(
                        "TRUNCATE account_start_session_operator_authorizations")),
        "StartSession operator authorization evidence cannot be truncated");

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
    Instant databaseNow = databaseNow(context);
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

    Instant expiredAt = databaseNow(context);
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
    IssuanceCandidate candidate = candidate(tuple());
    insertV126Row(context, candidate);

    Flyway.configure()
        .dataSource(context.dataSource)
        .schemas(context.schema)
        .defaultSchema(context.schema)
        .placeholders(Map.of("serviceSchema", context.schema))
        .locations("classpath:db/migration")
        .target("126")
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

  private Context context() {
    return contextAt(null);
  }

  private Context contextAt(String targetVersion) {
    String schema = "account_start_session_" + UUID.randomUUID().toString().replace("-", "");
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
    UUID operationId = UUID.randomUUID();
    BundleReference reference =
        bundleReference(Long.parseLong(original.bundleReference().sourceFence()) + 1L);
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
            new String(original.preAuthorizationTuple(), StandardCharsets.UTF_8));
    Instant expiresAt = original.referenceExpiresAt().plusSeconds(1);
    long issuanceFence = original.issuanceFence() + 1L;
    return new IssuanceCandidate(
        original.controlPlaneRequestId(),
        original.preAuthorizationTuple(),
        original.mutationDigest(),
        original.issuanceWorkloadUri(),
        original.reservationOwnerId(),
        original.reservationClaimFence(),
        operationId,
        issuanceFence,
        reference,
        authorityBundle(tuple, operationId, issuanceFence, expiresAt, reference),
        "arfp/v1/key-2/" + "b".repeat(64),
        bytes("different encrypted response"),
        original.issuedAt().plusSeconds(1),
        expiresAt,
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

  private static IssuanceCandidate candidate(StartSessionPreAuthorizationReservationTuple tuple) {
    return candidate(tuple, REQUEST_ID);
  }

  private static IssuanceCandidate candidate(
      StartSessionPreAuthorizationReservationTuple tuple, String requestId) {
    return candidate(requestId, canonicalBytes(tuple), tuple.mutationDigest());
  }

  private static IssuanceCandidate candidate(String requestId, byte[] tupleBytes, String digest) {
    return new IssuanceCandidate(
        requestId,
        tupleBytes,
        digest,
        ISSUER_URI,
        RESERVATION_OWNER_ID,
        3L,
        ISSUANCE_OPERATION_ID,
        31L,
        bundleReference(7L),
        authorityBundle(
            tupleFromBytes(tupleBytes),
            ISSUANCE_OPERATION_ID,
            31L,
            Instant.parse("2026-10-09T00:03:00Z"),
            bundleReference(7L)),
        "arfp/v1/key-1/" + "a".repeat(64),
        bytes("encrypted-original-response-envelope"),
        Instant.parse("2026-10-09T00:00:00Z"),
        Instant.parse("2026-10-09T00:03:00Z"),
        Instant.parse("2026-10-09T00:03:30Z"));
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
        31L,
        bundleReference(7L),
        authorityBundle(tuple, ISSUANCE_OPERATION_ID, 31L, referenceExpiresAt, bundleReference(7L)),
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
        ISSUANCE_OPERATION_ID,
        31L,
        bundleReference(7L),
        authorityBundle(
            tuple, ISSUANCE_OPERATION_ID, 31L, databaseNow.minusSeconds(60), bundleReference(7L)),
        uniqueFingerprint,
        bytes("expired-original-response-envelope"),
        databaseNow.minusSeconds(240),
        databaseNow.minusSeconds(60),
        databaseNow.minusSeconds(30));
  }

  private static RedemptionRequest redemption(
      IssuanceCandidate candidate, UUID ownerAttemptId, long ownerFence) {
    return redemption(candidate, ownerAttemptId, ownerFence, Instant.parse("2026-10-09T00:00:01Z"));
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

  private static StartSessionPreAuthorizationReservationTuple tupleFromBytes(byte[] tupleBytes) {
    return StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
        new String(tupleBytes, StandardCharsets.UTF_8));
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID operationId,
      long issuanceFence,
      Instant expiresAt,
      BundleReference reference) {
    String tenantId = tuple.action().scope().tenantId().toString();
    String actorId = tuple.actor().accountId().toString();
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 1L,
            "tenantAuthorityGeneration", Map.of(tenantId, 1L),
            "membershipAuthorityGeneration", Map.of(tenantId, 1L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
                Map.of(
                    "scope",
                    Map.of(
                        "tenantId",
                        tenantId,
                        "targetNamespace",
                        tuple.action().scope().targetNamespace()),
                    "actionFamily",
                    tuple.actionFamily(),
                    "applicableAccountId",
                    actorId,
                    "applicableTenantId",
                    tenantId),
            "accountProjectionEvidence",
                Map.of(
                    "sourceType",
                    "ACCOUNT",
                    "sourceEvidenceId",
                    "sha256:" + "a".repeat(64),
                    "sourceEvidenceVersion",
                    reference.sourceVersion(),
                    "projectionStatus",
                    "CURRENT",
                    "evaluatedAt",
                    Instant.parse("2026-10-09T00:00:00Z").toString(),
                    "expiresAt",
                    expiresAt.toString()),
            "issuanceOperationIdentity",
                Map.of(
                    "issuanceOperationId", operationId.toString(),
                    "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                    "actionFamilyRequestIdentity",
                        Map.of(
                            "requestIdentityKind",
                            "controlPlaneRequestId",
                            "requestId",
                            tuple.controlPlaneRequestId()),
                    "mutationDigest", tuple.mutationDigest()),
            "issuanceKind", "human_operator",
            "authorityTuple", authorityTuple,
            "membershipVersion", Map.of(tenantId, 2L),
            "issuanceFence", Long.toString(issuanceFence),
            "issuanceEvidence",
                Map.of(
                    "evidenceType",
                    StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                    "actorAccountId",
                    actorId,
                    "controlUiTokenJti",
                    UUID.fromString("2e19f788-10d2-4f33-aed6-9a46db136dcc").toString(),
                    "role",
                    "tenantAdmin",
                    "accountGeneration",
                    "1",
                    "tenantGeneration",
                    "1"));
    return AccountControlUiAuthority.canonical(bundle);
  }

  private static void assertImmutableGuardRejected(
      ThrowingCallable action, String expectedDiagnostic) {
    assertThatThrownBy(action)
        .isInstanceOf(IntegrityConstraintViolationException.class)
        .satisfies(
            failure -> {
              SQLException sqlFailure = findSqlException(failure);
              assertThat(sqlFailure.getSQLState()).isEqualTo("23514");
              if (!(sqlFailure instanceof org.postgresql.util.PSQLException postgresFailure)) {
                throw new AssertionError(
                    "Immutable guard rejection must be a PostgreSQL exception", sqlFailure);
              }
              var serverError = postgresFailure.getServerErrorMessage();
              assertThat(serverError).isNotNull();
              assertThat(serverError.getMessage()).isEqualTo(expectedDiagnostic);
            });
  }

  private static SQLException findSqlException(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlFailure) return sqlFailure;
    }
    throw new AssertionError("Immutable guard rejection did not retain its SQLException", failure);
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
      var row =
          Objects.requireNonNull(
              adminDsl.fetchOne(
                  "SELECT count(*) FROM account_start_session_operator_authorizations"),
              "COUNT query must return a row");
      return Objects.requireNonNull(row.get(0, Integer.class), "COUNT query must return a value");
    }
  }
}
