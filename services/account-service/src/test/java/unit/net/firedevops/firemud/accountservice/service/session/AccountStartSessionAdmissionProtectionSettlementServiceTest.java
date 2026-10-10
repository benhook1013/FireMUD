package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredSettlement;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionSettlement;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalReadClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalResult;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mocked producer/storage/transaction-control ordering only; not database or authenticated GS
 * proof.
 */
class AccountStartSessionAdmissionProtectionSettlementServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID PROTECTION_ID = uuid("6b763f1d-c5bc-4080-b499-d29debc0a7b8");
  private static final UUID OTHER_PROTECTION_ID = uuid("af5f4dc2-5a8e-4c1a-9413-66ec60a35d80");
  private static final Instant EXPIRED_LEASE = Instant.parse("2020-01-02T03:04:05.123456Z");
  private static final Instant TERMINAL_AT = Instant.parse("2026-10-09T10:20:30Z");
  private static final Instant SETTLED_AT = Instant.parse("2026-10-09T10:21:30Z");
  private static final String PROOF_DIGEST = "sha256:" + "d".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void settlesCommittedAndAbortedWithExactTransactionAndNetworkOrderingEvenAfterExpiry() {
    for (GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome :
        List.of(
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED)) {
      Harness harness = new Harness(NAMESPACE);
      Fixture fixture = fixture(NAMESPACE, PROTECTION_ID, 23L);
      OriginalStartSessionAdmissionTerminalResult terminal = fixture.result(outcome);
      AtomicReference<StoredSettlement> stored = new AtomicReference<>();
      AtomicInteger receiptReads = new AtomicInteger();

      when(harness.repository.findHistoricalExact(PROTECTION_ID, 23L))
          .thenAnswer(
              ignored -> {
                assertWritableReadCommittedTransaction();
                harness.steps.add("historical-protection");
                return Optional.of(fixture.evidence());
              });
      when(harness.repository.findSettlementExact(PROTECTION_ID, 23L))
          .thenAnswer(
              ignored -> {
                assertWritableReadCommittedTransaction();
                int read = receiptReads.incrementAndGet();
                harness.steps.add(read == 1 ? "prior-receipt" : "post-commit-readback");
                return read == 1 ? Optional.empty() : Optional.of(stored.get());
              });
      when(harness.client.read(any()))
          .thenAnswer(
              invocation -> {
                assertNoSql();
                harness.steps.add("remote-game-session-read");
                OriginalStartSessionAdmissionTerminalRequest request = invocation.getArgument(0);
                assertThat(request.canonicalBytes())
                    .containsExactly(fixture.evidence().canonicalBytes());
                return terminal;
              });
      when(harness.repository.settleExact(any()))
          .thenAnswer(
              invocation -> {
                assertWritableReadCommittedTransaction();
                harness.steps.add("persist-settlement");
                AccountStartSessionAdmissionProtectionSettlement settlement =
                    invocation.getArgument(0);
                assertThat(settlement.protectionEvidence().canonicalBytes())
                    .containsExactly(fixture.evidence().canonicalBytes());
                assertThat(settlement.ownerProof()).isEqualTo(terminal.ownerProof());
                StoredSettlement receipt = new StoredSettlement(settlement, SETTLED_AT);
                stored.set(receipt);
                return receipt;
              });

      StoredSettlement actual = harness.service.settle(PROTECTION_ID, 23L);

      assertThat(actual).isSameAs(stored.get());
      assertThat(actual.outcome()).isEqualTo(outcome);
      if (outcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
        assertThat(actual.settlement().ownerProof().committedPointerVersion()).isEqualTo(1L);
        assertThat(actual.settlement().ownerProof().auditEventId()).isPositive();
        assertThat(actual.settlement().ownerProof().positiveDurableAbort()).isFalse();
      } else {
        assertThat(actual.settlement().ownerProof().committedPointerVersion()).isNull();
        assertThat(actual.settlement().ownerProof().auditEventId()).isNull();
        assertThat(actual.settlement().ownerProof().positiveDurableAbort()).isTrue();
      }
      assertThat(actual.settlement().canonicalBytes())
          .containsExactly(stored.get().settlement().canonicalBytes());
      assertThat(harness.steps)
          .containsExactly(
              "historical-protection",
              "prior-receipt",
              "remote-game-session-read",
              "persist-settlement",
              "post-commit-readback");
      assertThat(harness.transactions.begins).isEqualTo(3);
      assertThat(harness.transactions.commits).isEqualTo(3);
      verify(harness.client).read(any());
      verify(harness.repository).settleExact(any());
      assertNoSql();
    }
  }

  @Test
  void alreadyRetainedReceiptMakesLostResponseRetryExactAndRemoteFree() {
    Harness harness = new Harness(NAMESPACE);
    Fixture fixture = fixture(NAMESPACE, PROTECTION_ID, 23L);
    StoredSettlement receipt =
        fixture.receipt(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED, SETTLED_AT);
    when(harness.repository.findHistoricalExact(PROTECTION_ID, 23L))
        .thenReturn(Optional.of(fixture.evidence()));
    when(harness.repository.findSettlementExact(PROTECTION_ID, 23L))
        .thenReturn(Optional.of(receipt));

    StoredSettlement recovered = harness.service.settle(PROTECTION_ID, 23L);

    assertThat(recovered).isSameAs(receipt);
    assertThat(harness.transactions.begins).isEqualTo(1);
    assertThat(harness.transactions.commits).isEqualTo(1);
    verifyNoInteractions(harness.client);
    verify(harness.repository, never()).settleExact(any());
  }

  @Test
  void endUserAmbientTransactionsAndInvalidIdentityAreRejectedBeforeRepositoryAccess() {
    Harness harness = new Harness(NAMESPACE);
    SessionContext.setContext("123", List.of(), Map.of());
    assertStatus(Status.Code.PERMISSION_DENIED, () -> harness.service.settle(null, 0L));
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertStatus(Status.Code.FAILED_PRECONDITION, () -> harness.service.settle(null, 0L));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertStatus(Status.Code.FAILED_PRECONDITION, () -> harness.service.settle(null, 0L));
    TransactionSynchronizationManager.clear();

    assertThatThrownBy(() -> harness.service.settle(new UUID(0L, 0L), 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> harness.service.settle(PROTECTION_ID, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(harness.repository, harness.client);
    assertThat(harness.transactions.begins).isZero();
  }

  @Test
  void retainedNamespaceMustMatchConfiguredAccountWorkloadNamespace() {
    Harness harness = new Harness(NAMESPACE);
    Fixture fixture = fixture("other-runtime", PROTECTION_ID, 23L);
    when(harness.repository.findHistoricalExact(PROTECTION_ID, 23L))
        .thenReturn(Optional.of(fixture.evidence()));

    assertStatus(Status.Code.PERMISSION_DENIED, () -> harness.service.settle(PROTECTION_ID, 23L));

    verifyNoInteractions(harness.client);
    verify(harness.repository, never()).findSettlementExact(any(), anyLong());
    assertThat(harness.transactions.commits).isZero();
  }

  @Test
  void unavailableNullOrChangedFullRemoteEchoNeverReachesSettlementInsert() {
    Fixture fixture = fixture(NAMESPACE, PROTECTION_ID, 23L);

    Harness unavailable = preparedHarness(fixture);
    when(unavailable.client.read(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
    assertStatus(Status.Code.UNAVAILABLE, () -> unavailable.service.settle(PROTECTION_ID, 23L));
    verify(unavailable.repository, never()).settleExact(any());

    Harness missing = preparedHarness(fixture);
    when(missing.client.read(any())).thenReturn(null);
    assertStatus(Status.Code.FAILED_PRECONDITION, () -> missing.service.settle(PROTECTION_ID, 23L));
    verify(missing.repository, never()).settleExact(any());

    Harness changedEcho = preparedHarness(fixture);
    Fixture substituted = fixture(NAMESPACE, OTHER_PROTECTION_ID, 24L);
    when(changedEcho.client.read(any()))
        .thenReturn(
            substituted.result(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED));
    assertStatus(
        Status.Code.FAILED_PRECONDITION, () -> changedEcho.service.settle(PROTECTION_ID, 23L));
    verify(changedEcho.repository, never()).settleExact(any());
    assertThat(unavailable.transactions.commits).isEqualTo(1);
    assertThat(missing.transactions.commits).isEqualTo(1);
    assertThat(changedEcho.transactions.commits).isEqualTo(1);
  }

  @Test
  void unknownSettlementCommitPropagatesAndRetryRecoversReceiptWithoutRemoteReplay() {
    Harness harness = new Harness(NAMESPACE);
    Fixture fixture = fixture(NAMESPACE, PROTECTION_ID, 23L);
    OriginalStartSessionAdmissionTerminalResult terminal =
        fixture.result(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    AtomicReference<StoredSettlement> receipt = new AtomicReference<>();
    AtomicInteger receiptReads = new AtomicInteger();
    when(harness.repository.findHistoricalExact(PROTECTION_ID, 23L))
        .thenReturn(Optional.of(fixture.evidence()));
    when(harness.repository.findSettlementExact(PROTECTION_ID, 23L))
        .thenAnswer(
            ignored ->
                receiptReads.incrementAndGet() == 1
                    ? Optional.empty()
                    : Optional.of(receipt.get()));
    when(harness.client.read(any())).thenReturn(terminal);
    when(harness.repository.settleExact(any()))
        .thenAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              receipt.set(new StoredSettlement(invocation.getArgument(0), SETTLED_AT));
              return receipt.get();
            });
    harness.transactions.failCommitAt = 2;

    assertThatThrownBy(() -> harness.service.settle(PROTECTION_ID, 23L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic unknown commit");
    assertThat(receiptReads.get()).isEqualTo(1);

    assertThat(harness.service.settle(PROTECTION_ID, 23L)).isSameAs(receipt.get());

    assertThat(receiptReads.get()).isEqualTo(2);
    assertThat(harness.transactions.begins).isEqualTo(3);
    verify(harness.client).read(any());
    verify(harness.repository).settleExact(any());
  }

  @Test
  void missingOrMismatchedPostCommitReadbackRemainsUnresolved() {
    Fixture fixture = fixture(NAMESPACE, PROTECTION_ID, 23L);
    assertReadbackDoesNotSucceed(fixture, Optional.empty(), 2, 1);
    StoredSettlement changedTime =
        fixture.receipt(
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            SETTLED_AT.plusSeconds(1));
    assertReadbackDoesNotSucceed(fixture, Optional.of(changedTime), 3, 0);
  }

  private static void assertReadbackDoesNotSucceed(
      Fixture fixture,
      Optional<StoredSettlement> readback,
      int expectedCommits,
      int expectedRollbacks) {
    Harness harness = new Harness(NAMESPACE);
    OriginalStartSessionAdmissionTerminalResult terminal =
        fixture.result(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    AtomicReference<StoredSettlement> committed = new AtomicReference<>();
    AtomicInteger receiptReads = new AtomicInteger();
    when(harness.repository.findHistoricalExact(PROTECTION_ID, 23L))
        .thenReturn(Optional.of(fixture.evidence()));
    when(harness.repository.findSettlementExact(PROTECTION_ID, 23L))
        .thenAnswer(ignored -> receiptReads.incrementAndGet() == 1 ? Optional.empty() : readback);
    when(harness.client.read(any())).thenReturn(terminal);
    when(harness.repository.settleExact(any()))
        .thenAnswer(
            invocation -> {
              StoredSettlement result = new StoredSettlement(invocation.getArgument(0), SETTLED_AT);
              committed.set(result);
              return result;
            });

    assertStatus(Status.Code.FAILED_PRECONDITION, () -> harness.service.settle(PROTECTION_ID, 23L));

    assertThat(committed.get()).isNotNull();
    verify(harness.repository).settleExact(any());
    assertThat(harness.transactions.begins).isEqualTo(3);
    assertThat(harness.transactions.commits).isEqualTo(expectedCommits);
    assertThat(harness.transactions.rollbacks).isEqualTo(expectedRollbacks);
  }

  private static Harness preparedHarness(Fixture fixture) {
    Harness harness = new Harness(NAMESPACE);
    when(harness.repository.findHistoricalExact(PROTECTION_ID, 23L))
        .thenReturn(Optional.of(fixture.evidence()));
    when(harness.repository.findSettlementExact(PROTECTION_ID, 23L)).thenReturn(Optional.empty());
    return harness;
  }

  private static void assertWritableReadCommittedTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
  }

  private static void assertNoSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static void assertStatus(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static Fixture fixture(String namespace, UUID protectionId, long protectionFence) {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple(namespace);
    WorldCanonicalInitialAdmissionHold.HoldIdentity hold = hold(tuple, namespace);
    AccountStartSessionAdmissionProtectionRequest request =
        AccountStartSessionAdmissionProtectionRequest.create(
            tuple.canonicalBytes(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31"),
            uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8"),
            21L,
            EXPIRED_LEASE,
            uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82"),
            22L,
            hold);
    byte[] captureReference =
        canonical(
            Map.of(
                "schema",
                AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
                "controlPlaneRequestId",
                tuple.controlPlaneRequestId(),
                "capturedAt",
                "2026-10-09T10:11:12.123Z",
                "bundleReference",
                Map.of(
                    "bundleVersion",
                    StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion",
                    "17",
                    "sourceFence",
                    "23",
                    "linearization",
                    "18446744073709551615"),
                "snapshotSha256",
                "a".repeat(64)));
    AccountStartSessionAdmissionProtectionEvidence evidence =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request,
            protectionId,
            protectionFence,
            captureReference,
            sha256(captureReference),
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT,
                    uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531").toString(),
                    "2",
                    "17",
                    null,
                    null,
                    "exact historical source evidence".getBytes(StandardCharsets.UTF_8))));
    return new Fixture(evidence);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(String namespace) {
    UUID tenant = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
    UUID actor = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "settlement-test",
            actor,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenant, namespace),
                new StartSessionOperatorAction.Target(
                    91L, uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "original StartSession admission settlement fixture"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
        19L,
        authorityBundle(preTuple, actor),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple, UUID actor) {
    UUID tenant = tuple.action().scope().tenantId();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenant.toString(), 3L),
            "membershipAuthorityGeneration", Map.of(tenant.toString(), 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", actor.toString(),
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    return canonical(
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of(
                    "tenantId",
                    tenant.toString(),
                    "targetNamespace",
                    tuple.action().scope().targetNamespace()),
                "actionFamily",
                tuple.actionFamily(),
                "applicableAccountId",
                actor.toString(),
                "applicableTenantId",
                tenant.toString()),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenant.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence));
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity hold(
      StartSessionPostAuthorizationExecutionTuple tuple, String namespace) {
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        new WorldCanonicalInitialAdmissionHold.Request(
            namespace,
            tuple.preAuthorizationTuple().action().scope().tenantId(),
            "earth",
            uuid("3916f423-2870-426a-a8aa-5e3f97412613"),
            uuid("54e6094e-11bb-4f4c-93ee-a52f715b530b"),
            "SHARED",
            uuid("a55b2e10-9a24-4adb-adb8-6fc66fe3b8e9"),
            uuid("d7280ec0-5979-4b62-8418-e9f139415184"),
            3L,
            tuple.controlPlaneRequestId(),
            "c".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null),
        uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c"),
        uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1"));
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(AccountStartSessionAdmissionProtectionEvidence evidence) {
    OriginalStartSessionAdmissionTerminalResult result(
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome) {
      var hold = evidence.request().worldAdmissionHoldIdentity();
      var proof =
          switch (outcome) {
            case COMMITTED ->
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    hold, outcome, 1L, 23L, PROOF_DIGEST, false, TERMINAL_AT);
            case ABORTED ->
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    hold, outcome, null, null, PROOF_DIGEST, true, TERMINAL_AT);
            case PENDING -> throw new IllegalArgumentException("Pending is not terminal evidence");
          };
      return new OriginalStartSessionAdmissionTerminalResult(
          new OriginalStartSessionAdmissionTerminalRequest(evidence.canonicalBytes()), proof);
    }

    StoredSettlement receipt(
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome, Instant settledAt) {
      return new StoredSettlement(
          AccountStartSessionAdmissionProtectionSettlement.create(result(outcome)), settledAt);
    }
  }

  private static final class Harness {
    final AccountStartSessionAdmissionProtectionRepository repository =
        mock(AccountStartSessionAdmissionProtectionRepository.class);
    final OriginalStartSessionAdmissionTerminalReadClient client =
        mock(OriginalStartSessionAdmissionTerminalReadClient.class);
    final OwnerTransactions transactions = new OwnerTransactions();
    final List<String> steps = new ArrayList<>();
    final AccountStartSessionAdmissionProtectionSettlementService service;

    Harness(String namespace) {
      service =
          new AccountStartSessionAdmissionProtectionSettlementService(
              repository, client, transactions, namespace);
    }
  }

  private static final class OwnerTransactions extends AbstractPlatformTransactionManager {
    private static final long serialVersionUID = 1L;
    int begins;
    int commits;
    int rollbacks;
    int failCommitAt = -1;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertNoSql();
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.isReadOnly()).isFalse();
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
      if (commits == failCommitAt) {
        throw new IllegalStateException("synthetic unknown commit");
      }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
