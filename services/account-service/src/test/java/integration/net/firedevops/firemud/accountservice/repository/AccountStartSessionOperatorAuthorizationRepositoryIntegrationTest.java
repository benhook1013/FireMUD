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

  private Context context() {
    String schema = "account_start_session_" + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target("126")
        .load()
        .migrate();
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
        new AccountStartSessionOperatorAuthorizationRepository(transactionDsl));
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
        7L,
        bundleReference(31L),
        authorityBundle(
            tupleFromBytes(tupleBytes),
            ISSUANCE_OPERATION_ID,
            7L,
            Instant.parse("2026-10-09T00:03:00Z"),
            bundleReference(31L)),
        "arfp/v1/key-1/" + "a".repeat(64),
        bytes("encrypted-original-response-envelope"),
        Instant.parse("2026-10-09T00:00:00Z"),
        Instant.parse("2026-10-09T00:03:00Z"),
        Instant.parse("2026-10-09T00:03:30Z"));
  }

  private static RedemptionRequest redemption(
      IssuanceCandidate candidate, UUID ownerAttemptId, long ownerFence) {
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
        Instant.parse("2026-10-09T00:00:01Z"));
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

    Context(
        DSLContext transactionDsl,
        DSLContext adminDsl,
        TransactionTemplate transaction,
        AccountStartSessionOperatorAuthorizationRepository repository) {
      this.transactionDsl = transactionDsl;
      this.adminDsl = adminDsl;
      this.transaction = transaction;
      this.repository = repository;
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
