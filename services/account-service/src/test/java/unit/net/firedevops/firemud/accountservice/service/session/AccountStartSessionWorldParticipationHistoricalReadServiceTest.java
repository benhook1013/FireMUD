package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Mocked owner-storage boundary only; no PostgreSQL or authenticated Game Session proof. */
class AccountStartSessionWorldParticipationHistoricalReadServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID READ_ID = uuid("d1f41bfb-265a-4f3b-8b2a-124cba20ce43");
  private static final UUID GAME_SESSION_ATTEMPT_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final long PARTICIPATION_FENCE = 31L;
  private static final long GAME_SESSION_FENCE = 37L;
  private static final String PREPARATION_INPUT =
      "{ \"historical\" : \"retained exactly\", \"revision\": 1 }\n";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AccountStartSessionWorldParticipationRepository repository =
      mock(AccountStartSessionWorldParticipationRepository.class);
  private final OwnerTransactions transactions = new OwnerTransactions();
  private final AccountStartSessionWorldParticipationHistoricalReadService service =
      new AccountStartSessionWorldParticipationHistoricalReadService(
          repository, transactions, NAMESPACE);

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesExactWorldWorkloadAndNoEndUserBeforeRequestOrStorageAccess() {
    assertCode(Status.Code.UNAUTHENTICATED, () -> service.read(null));
    for (String workload : List.of("account-service", "game-session-service")) {
      assertCode(
          Status.Code.PERMISSION_DENIED, () -> peer(NAMESPACE, workload, () -> service.read(null)));
    }
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer("other-runtime", "world-management-service", () -> service.read(null)));
    SessionContext.setContext("123", List.of(), Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer(NAMESPACE, "world-management-service", () -> service.read(null)));
    verifyNoInteractions(repository);
    assertThat(transactions.begins).isZero();
  }

  @Test
  void deniesAmbientSqlOrSynchronizationBeforeValidatingRequestOrStartingOwnerTransaction() {
    var request = request();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.read(request)));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.read(request)));
    verifyNoInteractions(repository);
    assertThat(transactions.begins).isZero();
  }

  @Test
  void readsExactHistoryAfterIngressExpiryAndPriorSettlementWithoutCurrentnessOrMutation()
      throws Exception {
    StoredParticipation expired = participation(true);
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenAnswer(
            call -> {
              assertWritableReadCommittedTransaction();
              return Optional.of(expired);
            });
    // A prior terminal receipt is deliberately present; historical participation reads do not
    // require it, rewrite it, or treat its outcome as fresh execution permission.
    when(repository.findSettlementExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(
            Optional.of(
                mock(AccountStartSessionWorldParticipationRepository.StoredSettlement.class)));

    var evidence = authorized(() -> service.read(request()));

    assertThat(evidence.request()).isEqualTo(request());
    assertThat(evidence.originalPostAuthorizationTuple())
        .containsExactly(expired.originalPostAuthorizationTuple());
    assertThat(evidence.gameSessionOwnerAttemptId()).isEqualTo(GAME_SESSION_ATTEMPT_ID);
    assertThat(evidence.gameSessionOwnerFence()).isEqualTo(GAME_SESSION_FENCE);
    assertThat(evidence.canonicalGameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
    assertThat(evidence.preparationInputJson()).isEqualTo(PREPARATION_INPUT);
    assertThat(evidence.preparationInputDigest()).isEqualTo(digest(PREPARATION_INPUT));
    assertThat(transactions.begins).isEqualTo(1);
    assertThat(transactions.commits).isEqualTo(1);
    assertNoSql();
    verify(repository).findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void absentMismatchedNamespaceAndMismatchedRetainedIdentityFailClosed() {
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.empty());
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.read(request())));
    assertThat(transactions.commits).isZero();
    assertThat(transactions.rollbacks).isEqualTo(1);

    TransactionSynchronizationManager.clear();
    transactions.resetCounts();
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(
            Optional.of(
                participation(false, "other-runtime", PARTICIPATION_ID, PARTICIPATION_FENCE)));
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.read(request())));
    assertThat(transactions.commits).isEqualTo(1);

    TransactionSynchronizationManager.clear();
    transactions.resetCounts();
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(
            Optional.of(
                participation(false, NAMESPACE, PARTICIPATION_ID, PARTICIPATION_FENCE + 1L)));
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.read(request())));
    assertThat(transactions.commits).isEqualTo(1);

    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            authorized(
                () ->
                    service.read(
                        new AccountStartSessionWorldParticipationHistoricalReadRequest(
                            READ_ID, "other-runtime", PARTICIPATION_ID, PARTICIPATION_FENCE))));
    verify(repository, org.mockito.Mockito.times(3))
        .findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE);
  }

  private AccountStartSessionWorldParticipationHistoricalReadRequest request() {
    return new AccountStartSessionWorldParticipationHistoricalReadRequest(
        READ_ID, NAMESPACE, PARTICIPATION_ID, PARTICIPATION_FENCE);
  }

  private static StoredParticipation participation(boolean expired) {
    return participation(expired, NAMESPACE, PARTICIPATION_ID, PARTICIPATION_FENCE);
  }

  private static StoredParticipation participation(
      boolean expired, String namespace, UUID participationId, long participationFence) {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple(namespace, expired);
    return new StoredParticipation(
        participationId,
        participationFence,
        tuple.controlPlaneRequestId(),
        tuple.canonicalBytes(),
        namespace,
        TENANT,
        GAME_INSTANCE_ID,
        GAME_SESSION_ATTEMPT_ID,
        GAME_SESSION_FENCE,
        PREPARATION_INPUT,
        digest(PREPARATION_INPUT),
        987L,
        OffsetDateTime.parse("2026-10-09T00:00:00Z"),
        List.of());
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(
      String namespace, boolean expired) {
    UUID actor = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
    UUID targetOwner = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    UUID reservationOwner = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
    String requestId = "historical-participation-original-request";
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            actor,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, namespace),
                new StartSessionOperatorAction.Target(91L, targetOwner),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "mocked historical Account read boundary"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        reservationOwner,
        19L,
        authorityBundle(preTuple, expired),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple, boolean expired) {
    String actorId = tuple.actor().accountId().toString();
    String tenantId = tuple.action().scope().tenantId().toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType",
            "ACCOUNT",
            "sourceEvidenceId",
            "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion",
            "17",
            "projectionStatus",
            "CURRENT",
            "evaluatedAt",
            expired ? "2020-01-01T00:00:00Z" : "2026-10-09T00:00:00Z",
            "expiresAt",
            expired ? "2020-01-02T00:00:00Z" : "2026-10-10T00:00:00Z");
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
            "tenantAuthorityGeneration", Map.of(tenantId, 3L),
            "membershipAuthorityGeneration", Map.of(tenantId, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> human =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", actorId,
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
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
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            human);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static <T> T authorized(Supplier<T> action) {
    return peer(NAMESPACE, "world-management-service", action);
  }

  private static <T> T peer(String namespace, String workload, Supplier<T> action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var prior = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(prior);
    }
  }

  private static void assertWritableReadCommittedTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private static void assertNoSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static void assertCode(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static String digest(String value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");

  private static final class OwnerTransactions extends AbstractPlatformTransactionManager {
    private int begins;
    private int commits;
    private int rollbacks;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.isReadOnly()).isFalse();
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }

    void resetCounts() {
      begins = 0;
      commits = 0;
      rollbacks = 0;
    }
  }
}
