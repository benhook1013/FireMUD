package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.IssuanceCandidate;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.RedemptionRequest;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountStartSessionOperatorAuthorizationRepositoryTest {
  private static final String REQUEST_ID =
      "start-session/operator/2355f292-ab9c-458c-8d52-aef18b9254e8";
  private static final UUID ACTOR_ID = UUID.fromString("d888ddc4-4a62-4b25-9bab-c4f94860c2ca");
  private static final UUID TENANT_ID = UUID.fromString("6d1e5ce5-6127-4d35-88b8-7a6f40692038");
  private static final UUID TARGET_ACCOUNT_ID =
      UUID.fromString("1df91ae6-5125-4e47-9f1d-2e45eab8a4a0");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("ca1b63bf-f09f-4a55-96bf-a1fd2e22349e");
  private static final UUID ISSUANCE_OPERATION_ID =
      UUID.fromString("37b1f37d-05d2-4aab-8ed9-c01acbf6606e");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("c0df9691-cba5-4274-a49d-0bc7b2158ef7");
  private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
  private static final String ISSUER_URI = "spiffe://firemud/ns/prod/sa/logging-admin-service";
  private static final String OWNER_URI = "spiffe://firemud/ns/prod/sa/game-session-service";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void timestampWritesAndExpiryPredicateUseExplicitPostgresTypes() {
    MockStore store = new MockStore();
    var repository = repository(store);
    var candidate = candidate(tuple());
    var created = inTransaction(() -> repository.createOrReadExact(candidate));
    var redeemed =
        inTransaction(
            () -> repository.redeemExact(redemption(candidate, OWNER_ATTEMPT_ID, 8L, OWNER_URI)));

    assertThat(created.issuance().issuedAt()).isEqualTo(candidate.issuedAt());
    assertThat(redeemed).isNotNull();
    assertThat(store.queries)
        .anySatisfy(
            sql ->
                assertThat(sql)
                    .contains(
                        "cast(? as timestamptz), cast(? as timestamptz), "
                            + "cast(? as timestamptz), 'issued'"))
        .anySatisfy(
            sql ->
                assertThat(sql)
                    .contains(
                        "redeemed_at = cast(? as timestamptz)",
                        "reference_expires_at > cast(? as timestamptz)"));
  }

  @Test
  void exactDuplicateReturnsOriginalOutputAndNeverReplacesIt() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);
    IssuanceCandidate first = candidate(tuple());
    UUID changedOperationId = UUID.randomUUID();
    long changedIssuanceFence = first.issuanceFence() + 1L;
    long changedSourceFence = Long.parseLong(first.bundleReference().sourceFence()) + 1L;
    BundleReference changedReference = bundleReference(changedSourceFence);
    IssuanceCandidate changedOutput =
        new IssuanceCandidate(
            first.controlPlaneRequestId(),
            first.preAuthorizationTuple(),
            first.mutationDigest(),
            first.issuanceWorkloadUri(),
            first.reservationOwnerId(),
            first.reservationClaimFence(),
            changedOperationId,
            changedIssuanceFence,
            changedReference,
            authorityBundle(
                tuple(),
                changedOperationId,
                changedIssuanceFence,
                first.referenceExpiresAt().plusSeconds(1),
                changedReference),
            fingerprint("e"),
            bytes("different encrypted envelope"),
            first.issuedAt().plusSeconds(1),
            first.referenceExpiresAt().plusSeconds(1),
            first.responseEnvelopeExpiresAt().plusSeconds(1));

    AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult created =
        inTransaction(() -> repository.createOrReadExact(first));
    AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult duplicate =
        inTransaction(() -> repository.createOrReadExact(changedOutput));

    assertThat(created.created()).isTrue();
    assertThat(duplicate.created()).isFalse();
    assertThat(first.issuanceFence())
        .isNotEqualTo(Long.parseLong(first.bundleReference().sourceFence()));
    assertThat(duplicate.issuance().issuanceOperationId()).isEqualTo(ISSUANCE_OPERATION_ID);
    assertThat(duplicate.issuance().issuanceFence()).isEqualTo(4L);
    assertThat(duplicate.issuance().bundleReference()).isEqualTo(first.bundleReference());
    assertThat(duplicate.issuance().authorizationReferenceFingerprint())
        .isEqualTo(fingerprint("a"));
    assertThat(duplicate.issuance().authorityEvidenceBundle())
        .containsExactly(first.authorityEvidenceBundle());
    assertThat(duplicate.issuance().encryptedResponseEnvelope())
        .containsExactly(bytes("ciphertext-envelope"));
    assertThat(store.insertCount.get()).isEqualTo(1);
    assertThat(store.lastLockSql).contains("for update");

    byte[] exposedTuple = duplicate.issuance().preAuthorizationTuple();
    exposedTuple[0] ^= 0x01;
    assertThat(duplicate.issuance().preAuthorizationTuple())
        .containsExactly(first.preAuthorizationTuple());
    assertThat(duplicate.issuance().toString())
        .doesNotContain("tuple-v1", "authorityEvidenceBundle/v1", "ciphertext-envelope");
  }

  @Test
  void fingerprintKeyIdUsesCanonicalAlphabetAndAllowsMaximumLength() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);
    String maximumKeyId = "k".repeat(64);
    String maximumFingerprint = "arfp/v1/" + maximumKeyId + "/" + "a".repeat(64);
    IssuanceCandidate maximumKeyIdCandidate =
        withFingerprint(candidate(tuple()), maximumFingerprint);

    assertThat(maximumKeyIdCandidate.authorizationReferenceFingerprint()).hasSize(137);
    assertThat(inTransaction(() -> repository.createOrReadExact(maximumKeyIdCandidate)).created())
        .isTrue();

    int callsBeforeMalformed = store.databaseCallCount.get();
    String dottedFingerprint = "arfp/v1/key.with.dot/" + "a".repeat(64);
    IssuanceCandidate dottedKeyIdCandidate = withFingerprint(candidate(tuple()), dottedFingerprint);
    assertThatThrownBy(
            () -> inTransaction(() -> repository.createOrReadExact(dottedKeyIdCandidate)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fingerprint is malformed");
    assertThat(store.databaseCallCount.get()).isEqualTo(callsBeforeMalformed);
  }

  @Test
  void bundleSourceVersionMustMatchWhileSourceFenceRemainsIndependentOfIssuanceFence() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);
    IssuanceCandidate valid = candidate(tuple());
    IssuanceCandidate mismatchedReference =
        new IssuanceCandidate(
            valid.controlPlaneRequestId(),
            valid.preAuthorizationTuple(),
            valid.mutationDigest(),
            valid.issuanceWorkloadUri(),
            valid.reservationOwnerId(),
            valid.reservationClaimFence(),
            valid.issuanceOperationId(),
            valid.issuanceFence(),
            new BundleReference(
                valid.bundleReference().bundleVersion(),
                "13",
                valid.bundleReference().sourceFence(),
                valid.bundleReference().linearization()),
            valid.authorityEvidenceBundle(),
            valid.authorizationReferenceFingerprint(),
            valid.encryptedResponseEnvelope(),
            valid.issuedAt(),
            valid.referenceExpiresAt(),
            valid.responseEnvelopeExpiresAt());

    assertThat(valid.issuanceFence())
        .isNotEqualTo(Long.parseLong(valid.bundleReference().sourceFence()));
    assertThatThrownBy(() -> inTransaction(() -> repository.createOrReadExact(mismatchedReference)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bundle reference");
    assertThat(store.databaseCallCount.get()).isZero();
  }

  @Test
  void changedCanonicalTupleConflictsWithoutReplacingTheOriginal() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);
    IssuanceCandidate original = candidate(tuple());
    inTransaction(() -> repository.createOrReadExact(original));

    StartSessionPreAuthorizationReservationTuple changedTuple = tuple("different reason");
    IssuanceCandidate changedBinding =
        new IssuanceCandidate(
            original.controlPlaneRequestId(),
            canonicalBytes(changedTuple),
            changedTuple.mutationDigest(),
            original.issuanceWorkloadUri(),
            original.reservationOwnerId(),
            original.reservationClaimFence(),
            UUID.randomUUID(),
            original.issuanceFence(),
            original.bundleReference(),
            original.authorityEvidenceBundle(),
            original.authorizationReferenceFingerprint(),
            original.encryptedResponseEnvelope(),
            original.issuedAt(),
            original.referenceExpiresAt(),
            original.responseEnvelopeExpiresAt());

    assertThatThrownBy(() -> inTransaction(() -> repository.createOrReadExact(changedBinding)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
    assertThat(store.insertCount.get()).isEqualTo(1);
    assertThat(store.row.tuple).containsExactly(original.preAuthorizationTuple());
  }

  @Test
  void acceptsSharedRaw64DigestAndRejectsPrefixedMalformedOrChangedDigestsBeforeSql() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);
    StartSessionPreAuthorizationReservationTuple tuple = tuple();
    IssuanceCandidate canonical = candidate(tuple);

    assertThat(canonical.mutationDigest()).matches("[0-9a-f]{64}");
    assertThat(canonical.mutationDigest()).doesNotStartWith("sha256:");
    var created = inTransaction(() -> repository.createOrReadExact(canonical));
    assertThat(created.created()).isTrue();
    assertThat(created.issuance().mutationDigest()).isEqualTo(tuple.mutationDigest());

    String digest = canonical.mutationDigest();
    String changedDigest = (digest.charAt(0) == '0' ? "1" : "0") + digest.substring(1);
    for (String invalidDigest : new String[] {"sha256:" + digest, "not-a-digest", changedDigest}) {
      IssuanceCandidate invalid =
          candidate(
              canonical.controlPlaneRequestId(), canonical.preAuthorizationTuple(), invalidDigest);
      int callsBeforeInvalidDigest = store.databaseCallCount.get();

      assertThatThrownBy(() -> inTransaction(() -> repository.createOrReadExact(invalid)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(store.databaseCallCount.get()).isEqualTo(callsBeforeInvalidDigest);
    }
    assertThat(store.insertCount.get()).isEqualTo(1);
  }

  @Test
  void canonicalNonUuidRequestIdRetriesAtExactMultibyteUtf8Bound() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);
    String boundedRequestId = "é".repeat(64);
    StartSessionPreAuthorizationReservationTuple boundaryTuple =
        tuple(boundedRequestId, "128-byte NFC request ID boundary");
    IssuanceCandidate candidate = candidate(boundaryTuple, boundedRequestId);

    AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult created =
        inTransaction(() -> repository.createOrReadExact(candidate));
    AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult retry =
        inTransaction(() -> repository.createOrReadExact(candidate));
    java.util.Optional<AccountStartSessionOperatorAuthorizationRepository.IssuanceRecord> read =
        inTransaction(() -> repository.findByControlPlaneRequestId(boundedRequestId));

    assertThat(created.created()).isTrue();
    assertThat(retry.created()).isFalse();
    assertThat(read).isPresent();
    assertThat(read.orElseThrow().controlPlaneRequestId()).isEqualTo(boundedRequestId);
    assertThat(store.insertCount.get()).isEqualTo(1);

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
    int callsBeforeMalformedTuple = store.databaseCallCount.get();
    assertThatThrownBy(() -> inTransaction(() -> repository.createOrReadExact(oversized)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.databaseCallCount.get()).isEqualTo(callsBeforeMalformedTuple);
  }

  @Test
  void redemptionConsumesOnceAndExactRetryReturnsSameAuthorityOnlyResult() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);
    IssuanceCandidate candidate = candidate(tuple());
    inTransaction(() -> repository.createOrReadExact(candidate));
    RedemptionRequest request = redemption(candidate, OWNER_ATTEMPT_ID, 8L, OWNER_URI);

    var first = inTransaction(() -> repository.redeemExact(request));
    var retry = inTransaction(() -> repository.redeemExact(request));

    assertThat(first.replay()).isFalse();
    assertThat(retry.replay()).isTrue();
    assertThat(first.authorizationReferenceFingerprint()).isEqualTo(fingerprint("a"));
    assertThat(retry.authorizationReferenceFingerprint())
        .isEqualTo(first.authorizationReferenceFingerprint());
    assertThat(first.authorityEvidenceBundle())
        .containsExactly(candidate.authorityEvidenceBundle());
    assertThat(retry.authorityEvidenceBundle()).containsExactly(first.authorityEvidenceBundle());
    assertThat(first.issuanceOperationId()).isEqualTo(ISSUANCE_OPERATION_ID);
    assertThat(store.redemptionUpdateCount.get()).isEqualTo(1);
    assertThat(first.toString()).doesNotContain("ciphertext-envelope");
  }

  @Test
  void aDifferentAttemptFenceOrRedeemerCannotReplayConsumedReference() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);
    IssuanceCandidate candidate = candidate(tuple());
    inTransaction(() -> repository.createOrReadExact(candidate));
    RedemptionRequest first = redemption(candidate, OWNER_ATTEMPT_ID, 8L, OWNER_URI);
    inTransaction(() -> repository.redeemExact(first));

    RedemptionRequest changedAttempt = redemption(candidate, UUID.randomUUID(), 8L, OWNER_URI);
    RedemptionRequest changedFence = redemption(candidate, OWNER_ATTEMPT_ID, 9L, OWNER_URI);
    RedemptionRequest changedRedeemer =
        redemption(
            candidate, OWNER_ATTEMPT_ID, 8L, "spiffe://firemud/ns/staging/sa/game-session-service");

    for (RedemptionRequest changed :
        new RedemptionRequest[] {changedAttempt, changedFence, changedRedeemer}) {
      assertThatThrownBy(() -> inTransaction(() -> repository.redeemExact(changed)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("conflicts");
    }
    assertThat(store.redemptionUpdateCount.get()).isEqualTo(1);
  }

  @Test
  void expiredReferenceIsNotConsumedAndMalformedTupleOrFencesFailBeforeSql() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);
    IssuanceCandidate candidate = candidate(tuple());
    inTransaction(() -> repository.createOrReadExact(candidate));

    RedemptionRequest expired =
        new RedemptionRequest(
            candidate.controlPlaneRequestId(),
            candidate.preAuthorizationTuple(),
            candidate.authorizationReferenceFingerprint(),
            RESERVATION_OWNER_ID,
            2L,
            OWNER_URI,
            OWNER_ATTEMPT_ID,
            8L,
            candidate.authorityEvidenceBundle(),
            candidate.referenceExpiresAt());
    assertThatThrownBy(() -> inTransaction(() -> repository.redeemExact(expired)))
        .isInstanceOf(
            AccountStartSessionOperatorAuthorizationRepository.ReferenceExpiredException.class);
    assertThat(store.row.status).isEqualTo("ISSUED");

    int operationsBeforeInvalidInput = store.databaseCallCount.get();
    RedemptionRequest invalidFence =
        new RedemptionRequest(
            candidate.controlPlaneRequestId(),
            candidate.preAuthorizationTuple(),
            candidate.authorizationReferenceFingerprint(),
            RESERVATION_OWNER_ID,
            0L,
            OWNER_URI,
            OWNER_ATTEMPT_ID,
            8L,
            candidate.authorityEvidenceBundle(),
            NOW);
    assertThatThrownBy(() -> inTransaction(() -> repository.redeemExact(invalidFence)))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] malformedTuple = bytes("not canonical tuple");
    RedemptionRequest invalidTuple =
        new RedemptionRequest(
            candidate.controlPlaneRequestId(),
            malformedTuple,
            candidate.authorizationReferenceFingerprint(),
            RESERVATION_OWNER_ID,
            2L,
            OWNER_URI,
            OWNER_ATTEMPT_ID,
            8L,
            candidate.authorityEvidenceBundle(),
            NOW);
    assertThatThrownBy(() -> inTransaction(() -> repository.redeemExact(invalidTuple)))
        .isInstanceOf(IllegalArgumentException.class);

    String unsupportedTupleJson =
        new String(candidate.preAuthorizationTuple(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("\"tupleSchemaVersion\":\"1\"", "\"tupleSchemaVersion\":\"2\"");
    RedemptionRequest unsupportedTuple =
        new RedemptionRequest(
            candidate.controlPlaneRequestId(),
            bytes(unsupportedTupleJson),
            candidate.authorizationReferenceFingerprint(),
            RESERVATION_OWNER_ID,
            2L,
            OWNER_URI,
            OWNER_ATTEMPT_ID,
            8L,
            candidate.authorityEvidenceBundle(),
            NOW);
    assertThatThrownBy(() -> inTransaction(() -> repository.redeemExact(unsupportedTuple)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.databaseCallCount.get()).isEqualTo(operationsBeforeInvalidInput);
  }

  @Test
  void ownerTransactionIsMandatoryBeforeAnySql() {
    MockStore store = new MockStore();
    AccountStartSessionOperatorAuthorizationRepository repository = repository(store);

    assertThatThrownBy(() -> repository.createOrReadExact(candidate(tuple())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active Account transaction");
    assertThat(store.databaseCallCount.get()).isZero();
  }

  private static AccountStartSessionOperatorAuthorizationRepository repository(MockStore store) {
    return new AccountStartSessionOperatorAuthorizationRepository(
        DSL.using(new MockConnection(store), SQLDialect.POSTGRES));
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
        2L,
        ISSUANCE_OPERATION_ID,
        4L,
        bundleReference(31L),
        authorityBundle(
            tupleFromBytes(tupleBytes),
            ISSUANCE_OPERATION_ID,
            4L,
            NOW.plusSeconds(180),
            bundleReference(31L)),
        fingerprint("a"),
        bytes("ciphertext-envelope"),
        NOW,
        NOW.plusSeconds(180),
        NOW.plusSeconds(210));
  }

  private static IssuanceCandidate withFingerprint(IssuanceCandidate original, String fingerprint) {
    return new IssuanceCandidate(
        original.controlPlaneRequestId(),
        original.preAuthorizationTuple(),
        original.mutationDigest(),
        original.issuanceWorkloadUri(),
        original.reservationOwnerId(),
        original.reservationClaimFence(),
        original.issuanceOperationId(),
        original.issuanceFence(),
        original.bundleReference(),
        original.authorityEvidenceBundle(),
        fingerprint,
        original.encryptedResponseEnvelope(),
        original.issuedAt(),
        original.referenceExpiresAt(),
        original.responseEnvelopeExpiresAt());
  }

  private static RedemptionRequest redemption(
      IssuanceCandidate candidate, UUID attemptId, long ownerFence, String redeemerUri) {
    return new RedemptionRequest(
        candidate.controlPlaneRequestId(),
        candidate.preAuthorizationTuple(),
        candidate.authorizationReferenceFingerprint(),
        candidate.reservationOwnerId(),
        candidate.reservationClaimFence(),
        redeemerUri,
        attemptId,
        ownerFence,
        candidate.authorityEvidenceBundle(),
        NOW.plusSeconds(1));
  }

  private static StartSessionPreAuthorizationReservationTuple tuple() {
    return tuple("StartSession durable operator authorization test");
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
    return tuple.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String fingerprint(String hexStart) {
    return "arfp/v1/key-1/" + hexStart + "0".repeat(63);
  }

  private static BundleReference bundleReference(long sourceFence) {
    return new BundleReference(
        "authorityEvidenceBundle/v1", "12", Long.toString(sourceFence), "123456789");
  }

  private static StartSessionPreAuthorizationReservationTuple tupleFromBytes(byte[] tupleBytes) {
    return StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
        new String(tupleBytes, java.nio.charset.StandardCharsets.UTF_8));
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
                    NOW.toString(),
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

  private static <T> T inTransaction(java.util.function.Supplier<T> action) {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      return action.get();
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  private static OffsetDateTime timestampBinding(Object value) {
    if (!(value instanceof String timestamp)) {
      throw new IllegalArgumentException("Mock SQL timestamp binding must be text");
    }
    if (timestamp.length() <= 10 || timestamp.charAt(10) != ' ') {
      throw new IllegalArgumentException(
          "Mock SQL timestamp binding must use its date-time separator");
    }
    return OffsetDateTime.parse(timestamp.substring(0, 10) + "T" + timestamp.substring(11));
  }

  private static final class MockStore implements MockDataProvider {
    private static final Field<String> REQUEST_ID =
        DSL.field("control_plane_request_id", String.class);
    private static final Field<byte[]> TUPLE = DSL.field("pre_authorization_tuple", byte[].class);
    private static final Field<String> DIGEST = DSL.field("mutation_digest", String.class);
    private static final Field<String> ISSUER = DSL.field("issuance_workload_uri", String.class);
    private static final Field<UUID> RESERVATION_OWNER =
        DSL.field("reservation_owner_id", UUID.class);
    private static final Field<Long> RESERVATION_FENCE =
        DSL.field("reservation_claim_fence", Long.class);
    private static final Field<UUID> OPERATION_ID = DSL.field("issuance_operation_id", UUID.class);
    private static final Field<Long> ISSUANCE_FENCE = DSL.field("issuance_fence", Long.class);
    private static final Field<String> BUNDLE_VERSION = DSL.field("bundle_version", String.class);
    private static final Field<String> BUNDLE_SOURCE_VERSION =
        DSL.field("bundle_source_version", String.class);
    private static final Field<String> BUNDLE_SOURCE_FENCE =
        DSL.field("bundle_source_fence", String.class);
    private static final Field<String> BUNDLE_LINEARIZATION =
        DSL.field("bundle_linearization", String.class);
    private static final Field<byte[]> BUNDLE =
        DSL.field("authority_evidence_bundle", byte[].class);
    private static final Field<String> FINGERPRINT =
        DSL.field("authorization_reference_fingerprint", String.class);
    private static final Field<byte[]> ENVELOPE =
        DSL.field("encrypted_response_envelope", byte[].class);
    private static final Field<OffsetDateTime> ISSUED_AT =
        DSL.field("issued_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> REFERENCE_EXPIRY =
        DSL.field("reference_expires_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> ENVELOPE_EXPIRY =
        DSL.field("response_envelope_expires_at", OffsetDateTime.class);
    private static final Field<String> STATUS = DSL.field("status", String.class);
    private static final Field<String> REDEEMER =
        DSL.field("redemption_redeemer_workload_uri", String.class);
    private static final Field<UUID> OWNER_ATTEMPT =
        DSL.field("redemption_owner_attempt_id", UUID.class);
    private static final Field<Long> OWNER_FENCE = DSL.field("redemption_owner_fence", Long.class);
    private static final Field<OffsetDateTime> REDEEMED_AT =
        DSL.field("redeemed_at", OffsetDateTime.class);
    private static final Field<String> REDEMPTION_FINGERPRINT =
        DSL.field("redemption_reference_fingerprint", String.class);
    private static final Field<byte[]> REDEMPTION_BUNDLE =
        DSL.field("redemption_authority_evidence_bundle", byte[].class);

    private final AtomicInteger databaseCallCount = new AtomicInteger();
    private final AtomicInteger insertCount = new AtomicInteger();
    private final AtomicInteger redemptionUpdateCount = new AtomicInteger();
    private final List<String> queries = new java.util.ArrayList<>();
    private String lastLockSql = "";
    private StoredRow row;

    @Override
    public MockResult[] execute(MockExecuteContext context) throws SQLException {
      databaseCallCount.incrementAndGet();
      String sql = context.sql().stripLeading().toLowerCase(java.util.Locale.ROOT);
      queries.add(sql);
      Object[] bind = context.bindings();
      if (sql.startsWith("insert into account_start_session_operator_authorizations")) {
        if (row == null) {
          row = StoredRow.fromInsert(bind);
          insertCount.incrementAndGet();
          return new MockResult[] {new MockResult(1)};
        }
        return new MockResult[] {new MockResult(0)};
      }
      if (sql.startsWith("update account_start_session_operator_authorizations")) {
        if (row == null || !"ISSUED".equals(row.status)) {
          return new MockResult[] {new MockResult(0)};
        }
        OffsetDateTime now = timestampBinding(bind[7]);
        if (!row.referenceExpiresAt.isAfter(now)) {
          return new MockResult[] {new MockResult(0)};
        }
        row.redeemer = (String) bind[0];
        row.ownerAttempt = (UUID) bind[1];
        row.ownerFence = (Long) bind[2];
        row.redeemedAt = timestampBinding(bind[3]);
        row.redemptionFingerprint = (String) bind[4];
        row.redemptionBundle = ((byte[]) bind[5]).clone();
        row.status = "REDEEMED";
        redemptionUpdateCount.incrementAndGet();
        return new MockResult[] {new MockResult(1)};
      }
      if (sql.startsWith("select ")) {
        if (sql.contains("for update")) {
          lastLockSql = sql;
        }
        return new MockResult[] {row == null ? emptyResult() : rowResult(row)};
      }
      throw new SQLException("Unexpected SQL in MockStore: " + sql);
    }

    private static MockResult emptyResult() {
      return new MockResult(0, result(null));
    }

    private static MockResult rowResult(StoredRow row) {
      return new MockResult(1, result(row));
    }

    private static Result<Record> result(StoredRow value) {
      Result<Record> result =
          DSL.using(SQLDialect.POSTGRES)
              .newResult(
                  REQUEST_ID,
                  TUPLE,
                  DIGEST,
                  ISSUER,
                  RESERVATION_OWNER,
                  RESERVATION_FENCE,
                  OPERATION_ID,
                  ISSUANCE_FENCE,
                  BUNDLE_VERSION,
                  BUNDLE_SOURCE_VERSION,
                  BUNDLE_SOURCE_FENCE,
                  BUNDLE_LINEARIZATION,
                  BUNDLE,
                  FINGERPRINT,
                  ENVELOPE,
                  ISSUED_AT,
                  REFERENCE_EXPIRY,
                  ENVELOPE_EXPIRY,
                  STATUS,
                  REDEEMER,
                  OWNER_ATTEMPT,
                  OWNER_FENCE,
                  REDEEMED_AT,
                  REDEMPTION_FINGERPRINT,
                  REDEMPTION_BUNDLE);
      if (value == null) {
        return result;
      }
      Record record = DSL.using(SQLDialect.POSTGRES).newRecord(result.fields());
      record.setValue(REQUEST_ID, value.requestId);
      record.setValue(TUPLE, value.tuple.clone());
      record.setValue(DIGEST, value.digest);
      record.setValue(ISSUER, value.issuer);
      record.setValue(RESERVATION_OWNER, value.reservationOwner);
      record.setValue(RESERVATION_FENCE, value.reservationFence);
      record.setValue(OPERATION_ID, value.operationId);
      record.setValue(ISSUANCE_FENCE, value.issuanceFence);
      record.setValue(BUNDLE_VERSION, value.bundleReference.bundleVersion());
      record.setValue(BUNDLE_SOURCE_VERSION, value.bundleReference.sourceVersion());
      record.setValue(BUNDLE_SOURCE_FENCE, value.bundleReference.sourceFence());
      record.setValue(BUNDLE_LINEARIZATION, value.bundleReference.linearization());
      record.setValue(BUNDLE, value.bundle.clone());
      record.setValue(FINGERPRINT, value.fingerprint);
      record.setValue(ENVELOPE, value.envelope.clone());
      record.setValue(ISSUED_AT, value.issuedAt);
      record.setValue(REFERENCE_EXPIRY, value.referenceExpiresAt);
      record.setValue(ENVELOPE_EXPIRY, value.envelopeExpiresAt);
      record.setValue(STATUS, value.status);
      record.setValue(REDEEMER, value.redeemer);
      record.setValue(OWNER_ATTEMPT, value.ownerAttempt);
      record.setValue(OWNER_FENCE, value.ownerFence);
      record.setValue(REDEEMED_AT, value.redeemedAt);
      record.setValue(REDEMPTION_FINGERPRINT, value.redemptionFingerprint);
      record.setValue(
          REDEMPTION_BUNDLE,
          value.redemptionBundle == null ? null : value.redemptionBundle.clone());
      result.add(record);
      return result;
    }
  }

  private static final class StoredRow {
    private final String requestId;
    private final byte[] tuple;
    private final String digest;
    private final String issuer;
    private final UUID reservationOwner;
    private final Long reservationFence;
    private final UUID operationId;
    private final Long issuanceFence;
    private final BundleReference bundleReference;
    private final byte[] bundle;
    private final String fingerprint;
    private final byte[] envelope;
    private final OffsetDateTime issuedAt;
    private final OffsetDateTime referenceExpiresAt;
    private final OffsetDateTime envelopeExpiresAt;
    private String status = "ISSUED";
    private String redeemer;
    private UUID ownerAttempt;
    private Long ownerFence;
    private OffsetDateTime redeemedAt;
    private String redemptionFingerprint;
    private byte[] redemptionBundle;

    private StoredRow(
        String requestId,
        byte[] tuple,
        String digest,
        String issuer,
        UUID reservationOwner,
        Long reservationFence,
        UUID operationId,
        Long issuanceFence,
        BundleReference bundleReference,
        byte[] bundle,
        String fingerprint,
        byte[] envelope,
        OffsetDateTime issuedAt,
        OffsetDateTime referenceExpiresAt,
        OffsetDateTime envelopeExpiresAt) {
      this.requestId = requestId;
      this.tuple = tuple.clone();
      this.digest = digest;
      this.issuer = issuer;
      this.reservationOwner = reservationOwner;
      this.reservationFence = reservationFence;
      this.operationId = operationId;
      this.issuanceFence = issuanceFence;
      this.bundleReference = bundleReference;
      this.bundle = bundle.clone();
      this.fingerprint = fingerprint;
      this.envelope = envelope.clone();
      this.issuedAt = issuedAt;
      this.referenceExpiresAt = referenceExpiresAt;
      this.envelopeExpiresAt = envelopeExpiresAt;
    }

    private static StoredRow fromInsert(Object[] bind) {
      return new StoredRow(
          (String) bind[0],
          (byte[]) bind[1],
          (String) bind[2],
          (String) bind[3],
          (UUID) bind[4],
          (Long) bind[5],
          (UUID) bind[6],
          (Long) bind[7],
          new BundleReference(
              (String) bind[8], (String) bind[9], (String) bind[10], (String) bind[11]),
          (byte[]) bind[12],
          (String) bind[13],
          (byte[]) bind[14],
          timestampBinding(bind[15]),
          timestampBinding(bind[16]),
          timestampBinding(bind[17]));
    }
  }
}
