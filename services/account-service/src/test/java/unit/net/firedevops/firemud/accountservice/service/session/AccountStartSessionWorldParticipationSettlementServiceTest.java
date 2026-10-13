package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import java.io.IOException;
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
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredSettlement;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadClient;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Composition seams only: authenticated peer and exact owner terminal are stipulated here. */
class AccountStartSessionWorldParticipationSettlementServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID GAME_SESSION_ATTEMPT_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final long PARTICIPATION_FENCE = 31L;
  private static final long GAME_SESSION_FENCE = 37L;
  private static final String PREPARATION_INPUT =
      "{ \"selected\" : \"immutable source\", \"revision\": 3 }\n";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AccountStartSessionWorldParticipationRepository repository =
      mock(AccountStartSessionWorldParticipationRepository.class);
  private final WorldStartSessionExecutionTerminalReadClient world =
      mock(WorldStartSessionExecutionTerminalReadClient.class);
  private final OwnerTransactions transactions = new OwnerTransactions();
  private final AccountStartSessionWorldParticipationSettlementService service =
      new AccountStartSessionWorldParticipationSettlementService(
          repository, world, transactions, NAMESPACE);

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesExactWorldPeerWithoutEndUserBeforeLookupIdentityOrOwnerAccess() {
    assertCode(Status.Code.UNAUTHENTICATED, () -> service.settle(null, 0));
    for (String workload : List.of("account-service", "game-session-service")) {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          () -> peer(NAMESPACE, workload, () -> service.settle(null, 0)));
    }
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer("other", "world-management-service", () -> service.settle(null, 0)));
    SessionContext.setContext("123", List.of(), Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer(NAMESPACE, "world-management-service", () -> service.settle(null, 0)));
    verifyNoInteractions(repository, world);
    assertThat(transactions.begins).isZero();
  }

  @Test
  void deniesAmbientSqlOrSynchronizationBeforeIdentityValidation() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.settle(null, 0)));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.settle(null, 0)));
    verifyNoInteractions(repository, world);
    assertThat(transactions.begins).isZero();
  }

  @Test
  void derivesExactReadFromRetainedParticipationAndCommitsThenReadsBackBothOutcomes() {
    for (WorldStartSessionExecutionTerminal.Outcome outcome :
        WorldStartSessionExecutionTerminal.Outcome.values()) {
      reset(repository, world);
      Fixture fixture = fixture();
      WorldStartSessionExecutionTerminal terminal = fixture.terminal(outcome);
      StoredSettlement expected = fixture.settlement(terminal);
      when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
          .thenReturn(Optional.of(fixture.participation));
      when(repository.findSettlementExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
          .thenReturn(Optional.empty(), Optional.of(expected));
      when(world.read(any()))
          .thenAnswer(
              call -> {
                assertNoSql();
                WorldStartSessionExecutionTerminalReadRequest request = call.getArgument(0);
                assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
                assertThat(request.originalPostAuthorizationTuple())
                    .containsExactly(fixture.participation.originalPostAuthorizationTuple());
                assertThat(request.accountWorldParticipationId()).isEqualTo(PARTICIPATION_ID);
                assertThat(request.accountWorldParticipationFence()).isEqualTo(PARTICIPATION_FENCE);
                assertThat(request.gameSessionOwnerAttemptId()).isEqualTo(GAME_SESSION_ATTEMPT_ID);
                assertThat(request.gameSessionOwnerFence()).isEqualTo(GAME_SESSION_FENCE);
                assertThat(request.canonicalGameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
                assertThat(request.preparationInputJson()).isEqualTo(PREPARATION_INPUT);
                assertThat(request.preparationInputDigest())
                    .isEqualTo(fixture.participation.preparationInputDigest());
                return terminal;
              });
      when(repository.settleExact(any()))
          .thenAnswer(
              call -> {
                assertWritableTransaction();
                assertThat(call.getArgument(0, WorldStartSessionExecutionTerminal.class).outcome())
                    .isEqualTo(outcome);
                return expected;
              });

      int priorCommits = transactions.commits;
      StoredSettlement actual =
          authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE));
      assertThat(actual.terminalBytes()).containsExactly(expected.terminalBytes());
      assertThat(actual.outcome()).isEqualTo(outcome);
      assertThat(actual.worldExecutionFence()).isEqualTo(terminal.worldExecutionFence());
      assertThat(transactions.commits - priorCommits).isEqualTo(3);
      assertThat(authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE)))
          .isSameAs(expected);
      assertThat(transactions.commits - priorCommits).isEqualTo(4);
      ArgumentCaptor<WorldStartSessionExecutionTerminal> terminalCaptor =
          ArgumentCaptor.forClass(WorldStartSessionExecutionTerminal.class);
      verify(repository).settleExact(terminalCaptor.capture());
      assertThat(terminalCaptor.getValue().canonicalBytes())
          .containsExactly(terminal.canonicalBytes());
      verify(world).read(any());
      assertNoSql();
    }
  }

  @Test
  void alreadySettledHistoricalReceiptRecoversWithoutWorldOrCurrentActorChecks() {
    Fixture fixture = fixture();
    var terminal = fixture.terminal(WorldStartSessionExecutionTerminal.Outcome.COMMITTED);
    StoredSettlement receipt = fixture.settlement(terminal);
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.of(fixture.participation));
    when(repository.findSettlementExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.of(receipt));

    assertThat(authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE)))
        .isSameAs(receipt);
    verifyNoInteractions(world);
    assertThat(transactions.begins).isEqualTo(1);
    assertThat(transactions.commits).isEqualTo(1);
  }

  @Test
  void absentOrUnavailableWorldTerminalNeverSettlesParticipation() {
    Fixture fixture = fixture();
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.of(fixture.participation));
    when(repository.findSettlementExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.empty());
    when(world.read(any())).thenReturn(null);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE)));
    verify(repository, org.mockito.Mockito.never()).settleExact(any());
    assertThat(transactions.commits).isEqualTo(1);

    doThrow(Status.UNAVAILABLE.asRuntimeException()).when(world).read(any());
    assertCode(
        Status.Code.UNAVAILABLE,
        () -> authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE)));
    verify(repository, org.mockito.Mockito.never()).settleExact(any());
    assertThat(transactions.commits).isEqualTo(2);
  }

  @Test
  void exactCodecRejectsSubstitutedWorldTerminalAndNoSqlOccursAcrossNetwork() {
    Fixture fixture = fixture();
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.of(fixture.participation));
    when(repository.findSettlementExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.empty());
    var substituted =
        new WorldStartSessionExecutionTerminal(
            fixture.participation.originalPostAuthorizationTuple(),
            PARTICIPATION_ID,
            PARTICIPATION_FENCE,
            GAME_SESSION_ATTEMPT_ID,
            GAME_SESSION_FENCE,
            NAMESPACE,
            fixture.participation.canonicalTenantId(),
            fixture.participation.controlPlaneRequestId(),
            GAME_INSTANCE_ID,
            digest("substituted preparation"),
            "substituted preparation",
            41L,
            WorldStartSessionExecutionTerminal.Outcome.COMMITTED);
    when(world.read(any()))
        .thenAnswer(
            call -> {
              assertNoSql();
              return substituted;
            });
    assertThatThrownBy(
            () -> authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete original StartSession identity");
    verify(repository, org.mockito.Mockito.never()).settleExact(any());
    assertThat(transactions.commits).isEqualTo(1);
  }

  @Test
  void targetNamespaceMismatchFailsBeforeWorldCallAndExpiredOriginalEvidenceDoesNotGateHistory() {
    Fixture fixture = fixture();
    StoredParticipation otherNamespace =
        new StoredParticipation(
            fixture.participation.participationId(),
            fixture.participation.participationFence(),
            fixture.participation.controlPlaneRequestId(),
            fixture.participation.originalPostAuthorizationTuple(),
            "other-runtime",
            fixture.participation.canonicalTenantId(),
            fixture.participation.canonicalGameInstanceId(),
            fixture.participation.gameSessionOwnerAttemptId(),
            fixture.participation.gameSessionOwnerFence(),
            fixture.participation.preparationInputJson(),
            fixture.participation.preparationInputDigest(),
            fixture.participation.producerXid(),
            fixture.participation.createdAt(),
            fixture.participation.sources());
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.of(otherNamespace));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE)));
    verifyNoInteractions(world);
    assertThat(transactions.commits).isEqualTo(0);

    // The persisted tuple's bundle is intentionally expired. Historical exact settlement
    // depends on immutable owner readback and must not invoke current actor/JTI or expiry checks.
    Fixture expired = fixture(true);
    var terminal = expired.terminal(WorldStartSessionExecutionTerminal.Outcome.ABORTED);
    StoredSettlement receipt = expired.settlement(terminal);
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.of(expired.participation));
    when(repository.findSettlementExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.empty(), Optional.of(receipt));
    when(world.read(any())).thenReturn(terminal);
    when(repository.settleExact(any())).thenReturn(receipt);
    assertThat(authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE)))
        .isSameAs(receipt);
  }

  @Test
  void readbackMismatchAndSettlementCommitFailureDoNotReturnSuccess() {
    Fixture fixture = fixture();
    var terminal = fixture.terminal(WorldStartSessionExecutionTerminal.Outcome.COMMITTED);
    StoredSettlement first = fixture.settlement(terminal);
    StoredSettlement changed =
        new StoredSettlement(
            first.participationId(),
            first.outcome(),
            first.worldExecutionFence() + 1,
            first.terminalBytes(),
            first.terminalDigest(),
            first.settledAt());
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.of(fixture.participation));
    when(repository.findSettlementExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.empty(), Optional.of(changed));
    when(world.read(any())).thenReturn(terminal);
    when(repository.settleExact(any())).thenReturn(first);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE)));
    assertThat(transactions.commits).isEqualTo(3);

    reset(repository, world);
    when(repository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.of(fixture.participation));
    when(repository.findSettlementExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenReturn(Optional.empty());
    when(world.read(any())).thenReturn(terminal);
    when(repository.settleExact(any())).thenReturn(first);
    transactions.failCommitAt = transactions.commits + 2;
    assertThatThrownBy(
            () -> authorized(() -> service.settle(PARTICIPATION_ID, PARTICIPATION_FENCE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic commit failure");
    assertThat(transactions.begins).isEqualTo(5);
  }

  private static void assertWritableTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(java.sql.Connection.TRANSACTION_READ_COMMITTED);
  }

  private static void assertNoSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
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

  private static void assertCode(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
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

  private static Fixture fixture() {
    return fixture(false);
  }

  private static Fixture fixture(boolean expiredBundle) {
    UUID tenantId = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
    UUID actorId = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
    UUID targetOwner = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    UUID reservationOwner = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
    String requestId = "world-participation-settlement-original-request";
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            actorId,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenantId, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, targetOwner),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "StartSession terminal settlement test"));
    var reference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
    StartSessionPostAuthorizationExecutionTuple original =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            "spiffe://firemud/ns/world-runtime/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            reservationOwner,
            19L,
            authorityBundle(preTuple, expiredBundle),
            reference);
    byte[] tupleBytes = original.canonicalBytes();
    StoredParticipation participation =
        new StoredParticipation(
            PARTICIPATION_ID,
            PARTICIPATION_FENCE,
            requestId,
            tupleBytes,
            NAMESPACE,
            tenantId,
            GAME_INSTANCE_ID,
            GAME_SESSION_ATTEMPT_ID,
            GAME_SESSION_FENCE,
            PREPARATION_INPUT,
            digest(PREPARATION_INPUT),
            987L,
            OffsetDateTime.parse("2026-10-09T00:00:00Z"),
            List.of());
    return new Fixture(participation);
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple, boolean expired) {
    String tenantId = tuple.action().scope().tenantId().toString();
    String actorId = tuple.actor().accountId().toString();
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
    Map<String, Object> identity =
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
    Map<String, Object> evidence =
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
                Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                "actionFamily",
                tuple.actionFamily(),
                "applicableAccountId",
                actorId,
                "applicableTenantId",
                tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            identity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Fixture(StoredParticipation participation) {
    WorldStartSessionExecutionTerminal terminal(
        WorldStartSessionExecutionTerminal.Outcome outcome) {
      return new WorldStartSessionExecutionTerminal(
          participation.originalPostAuthorizationTuple(),
          PARTICIPATION_ID,
          PARTICIPATION_FENCE,
          GAME_SESSION_ATTEMPT_ID,
          GAME_SESSION_FENCE,
          NAMESPACE,
          participation.canonicalTenantId(),
          participation.controlPlaneRequestId(),
          GAME_INSTANCE_ID,
          participation.preparationInputDigest(),
          PREPARATION_INPUT,
          41L,
          outcome);
    }

    StoredSettlement settlement(WorldStartSessionExecutionTerminal terminal) {
      return new StoredSettlement(
          PARTICIPATION_ID,
          terminal.outcome(),
          terminal.worldExecutionFence(),
          terminal.canonicalBytes(),
          terminal.digest(),
          OffsetDateTime.parse("2026-10-09T00:01:00Z"));
    }
  }

  private static final class OwnerTransactions extends AbstractPlatformTransactionManager {
    private static final long serialVersionUID = 1L;
    int begins;
    int commits;
    int failCommitAt = -1;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
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
      if (commits == failCommitAt) throw new IllegalStateException("synthetic commit failure");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
