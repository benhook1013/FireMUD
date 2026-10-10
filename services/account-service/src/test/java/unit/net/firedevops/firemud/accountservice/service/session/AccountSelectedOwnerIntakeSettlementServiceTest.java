package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.sourceintake.AccountSelectedOwnerIntakeSettlementReceipt;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked Account transaction and owner-read ordering only; not genuine producer or SQL proof. */
class AccountSelectedOwnerIntakeSettlementServiceTest {
  private static final String NAMESPACE = "test";
  private static final SelectedOwnerIntakeAuthorizationBinding ORIGINAL =
      AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.AUTOMATION_SCRIPTING);

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void readsAutomationOutsideSqlThenCommitsAndReadsBackInIndependentTransactions() {
    Harness harness = new Harness(NAMESPACE);
    AutomationEmptySelectedSourceIntakeReceipt ownerReceipt = ownerReceipt(ORIGINAL, "winner");
    AtomicReference<AccountSelectedOwnerIntakeSettlementReceipt> stored = new AtomicReference<>();
    AtomicInteger settlementReads = new AtomicInteger();

    doAnswer(
            ignored -> {
              assertWritableReadCommittedTransaction();
              harness.steps.add("validate-original-authorization");
              return null;
            })
        .when(harness.repository)
        .readFinalAuthorization(ORIGINAL);
    when(harness.repository.findSettlement(ORIGINAL))
        .thenAnswer(
            ignored -> {
              assertWritableReadCommittedTransaction();
              int read = settlementReads.incrementAndGet();
              harness.steps.add(read == 1 ? "prior-settlement-lookup" : "post-commit-readback");
              return read == 1 ? Optional.empty() : Optional.of(stored.get());
            });
    when(harness.automationClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertNoSql();
              harness.steps.add("remote-automation-terminal-read");
              AutomationSelectedSourceIntakeTerminalReadEvidence.Request request =
                  invocation.getArgument(0);
              assertThat(request.binding().canonicalBytes())
                  .containsExactly(ORIGINAL.canonicalBytes());
              assertThat(request.readRequestId())
                  .isNotIn(ORIGINAL.operationId(), ORIGINAL.fenceId(), ORIGINAL.intakeRequestId());
              return new AutomationSelectedSourceIntakeTerminalReadEvidence(request, ownerReceipt);
            });
    when(harness.repository.settleCommittedEmpty(any()))
        .thenAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              harness.steps.add("commit-exact-terminal-settlement");
              AutomationSelectedSourceIntakeTerminalReadEvidence evidence =
                  invocation.getArgument(0);
              assertThat(evidence.request().binding().canonicalBytes())
                  .containsExactly(ORIGINAL.canonicalBytes());
              AccountSelectedOwnerIntakeSettlementReceipt receipt =
                  AccountSelectedOwnerIntakeSettlementReceipt.create(evidence);
              stored.set(receipt);
              return receipt;
            });

    AccountSelectedOwnerIntakeSettlementReceipt actual =
        asGameDesign(() -> harness.service.settle(ORIGINAL));

    assertThat(actual).isSameAs(stored.get());
    assertThat(actual.authorizationBinding().canonicalBytes())
        .containsExactly(ORIGINAL.canonicalBytes());
    assertThat(actual.terminalEvidence().receipt().canonicalBytes())
        .containsExactly(ownerReceipt.canonicalBytes());
    assertThat(harness.steps)
        .containsExactly(
            "validate-original-authorization",
            "prior-settlement-lookup",
            "remote-automation-terminal-read",
            "commit-exact-terminal-settlement",
            "post-commit-readback");
    assertThat(harness.transactions.begins).isEqualTo(3);
    assertThat(harness.transactions.commits).isEqualTo(3);
    verify(harness.automationClient).read(any());
    verify(harness.repository).settleCommittedEmpty(any());
    assertNoSql();
  }

  @Test
  void priorExactSettlementIsReturnedWithoutAutomationContactOrSecondCommit() {
    Harness harness = new Harness(NAMESPACE);
    AutomationSelectedSourceIntakeTerminalReadEvidence.Request request =
        AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(NAMESPACE, ORIGINAL);
    AccountSelectedOwnerIntakeSettlementReceipt prior =
        AccountSelectedOwnerIntakeSettlementReceipt.create(
            new AutomationSelectedSourceIntakeTerminalReadEvidence(
                request, ownerReceipt(ORIGINAL, "retained")));
    when(harness.repository.findSettlement(ORIGINAL)).thenReturn(Optional.of(prior));

    AccountSelectedOwnerIntakeSettlementReceipt recovered =
        asGameDesign(() -> harness.service.settle(ORIGINAL));

    assertThat(recovered).isSameAs(prior);
    assertThat(harness.transactions.begins).isEqualTo(1);
    assertThat(harness.transactions.commits).isEqualTo(1);
    verify(harness.repository).readFinalAuthorization(ORIGINAL);
    verify(harness.repository).findSettlement(ORIGINAL);
    verifyNoInteractions(harness.automationClient);
    verify(harness.repository, never()).settleCommittedEmpty(any());
  }

  @Test
  void freshReadCorrelationKeepsAnAlreadyCommittedImmutableOwnerReceiptWinner() {
    Harness harness = new Harness(NAMESPACE);
    AutomationEmptySelectedSourceIntakeReceipt ownerReceipt = ownerReceipt(ORIGINAL, "same-owner");
    AtomicInteger settlementReads = new AtomicInteger();
    AtomicReference<AutomationSelectedSourceIntakeTerminalReadEvidence.Request> remoteRequest =
        new AtomicReference<>();
    AutomationSelectedSourceIntakeTerminalReadEvidence.Request winnerRequest =
        AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(NAMESPACE, ORIGINAL);
    AccountSelectedOwnerIntakeSettlementReceipt winner =
        AccountSelectedOwnerIntakeSettlementReceipt.create(
            new AutomationSelectedSourceIntakeTerminalReadEvidence(winnerRequest, ownerReceipt));
    when(harness.repository.findSettlement(ORIGINAL))
        .thenAnswer(
            ignored ->
                settlementReads.incrementAndGet() == 1 ? Optional.empty() : Optional.of(winner));
    when(harness.automationClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertNoSql();
              AutomationSelectedSourceIntakeTerminalReadEvidence.Request request =
                  invocation.getArgument(0);
              remoteRequest.set(request);
              return new AutomationSelectedSourceIntakeTerminalReadEvidence(request, ownerReceipt);
            });
    when(harness.repository.settleCommittedEmpty(any()))
        .thenAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              AutomationSelectedSourceIntakeTerminalReadEvidence evidence =
                  invocation.getArgument(0);
              assertThat(evidence.request().readRequestId())
                  .isEqualTo(remoteRequest.get().readRequestId());
              return winner;
            });

    AccountSelectedOwnerIntakeSettlementReceipt recovered =
        asGameDesign(() -> harness.service.settle(ORIGINAL));

    assertThat(remoteRequest.get().readRequestId()).isNotEqualTo(winnerRequest.readRequestId());
    assertThat(recovered).isSameAs(winner);
    assertThat(recovered.terminalEvidence().request().readRequestId())
        .isEqualTo(winnerRequest.readRequestId());
    assertThat(recovered.terminalEvidence().receipt().canonicalBytes())
        .containsExactly(ownerReceipt.canonicalBytes());
    assertThat(harness.transactions.begins).isEqualTo(3);
  }

  @Test
  void unavailableNullAndChangedOwnerTerminalEvidenceNeverReachesAccountSettlement() {
    Harness unavailable = preparedHarness();
    when(unavailable.automationClient.read(any()))
        .thenThrow(Status.UNAVAILABLE.withDescription("offline").asRuntimeException());
    assertStatus(
        Status.Code.UNAVAILABLE, () -> asGameDesign(() -> unavailable.service.settle(ORIGINAL)));
    verify(unavailable.repository, never()).settleCommittedEmpty(any());

    Harness missing = preparedHarness();
    when(missing.automationClient.read(any())).thenReturn(null);
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> asGameDesign(() -> missing.service.settle(ORIGINAL)));
    verify(missing.repository, never()).settleCommittedEmpty(any());

    Harness changedEcho = preparedHarness();
    AutomationEmptySelectedSourceIntakeReceipt ownerReceipt =
        ownerReceipt(ORIGINAL, "changed-echo");
    when(changedEcho.automationClient.read(any()))
        .thenAnswer(
            invocation -> {
              var request =
                  (AutomationSelectedSourceIntakeTerminalReadEvidence.Request)
                      invocation.getArgument(0);
              var changed =
                  new AutomationSelectedSourceIntakeTerminalReadEvidence.Request(
                      request.schemaVersion(),
                      request.targetNamespace(),
                      UUID.randomUUID(),
                      request.binding());
              return new AutomationSelectedSourceIntakeTerminalReadEvidence(changed, ownerReceipt);
            });
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> asGameDesign(() -> changedEcho.service.settle(ORIGINAL)));
    verify(changedEcho.repository, never()).settleCommittedEmpty(any());

    Harness corruptReceipt = preparedHarness();
    AutomationEmptySelectedSourceIntakeReceipt badReceipt =
        ownerReceipt(ORIGINAL, "corrupt-digest");
    when(badReceipt.receiptDigest()).thenReturn("sha256:" + "f".repeat(64));
    when(corruptReceipt.automationClient.read(any()))
        .thenAnswer(
            invocation ->
                new AutomationSelectedSourceIntakeTerminalReadEvidence(
                    invocation.getArgument(0), badReceipt));
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> asGameDesign(() -> corruptReceipt.service.settle(ORIGINAL)));
    verify(corruptReceipt.repository, never()).settleCommittedEmpty(any());

    assertThat(unavailable.transactions.begins).isEqualTo(1);
    assertThat(missing.transactions.begins).isEqualTo(1);
    assertThat(changedEcho.transactions.begins).isEqualTo(1);
    assertThat(corruptReceipt.transactions.begins).isEqualTo(1);
  }

  @Test
  void failedSettlementCommitDoesNotPretendToHaveReadBackTheUnknownOutcome() {
    Harness harness = preparedHarness();
    AutomationEmptySelectedSourceIntakeReceipt ownerReceipt =
        ownerReceipt(ORIGINAL, "commit-failure");
    when(harness.automationClient.read(any()))
        .thenAnswer(
            invocation ->
                new AutomationSelectedSourceIntakeTerminalReadEvidence(
                    invocation.getArgument(0), ownerReceipt));
    when(harness.repository.settleCommittedEmpty(any()))
        .thenAnswer(
            invocation ->
                AccountSelectedOwnerIntakeSettlementReceipt.create(invocation.getArgument(0)));
    harness.transactions.failCommitAt = 2;

    assertThatThrownBy(() -> asGameDesign(() -> harness.service.settle(ORIGINAL)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic unknown commit");

    verify(harness.repository).findSettlement(ORIGINAL);
    verify(harness.repository, times(1)).findSettlement(ORIGINAL);
    verify(harness.repository).settleCommittedEmpty(any());
    assertThat(harness.transactions.begins).isEqualTo(2);
    assertThat(harness.transactions.commits).isEqualTo(2);
  }

  @Test
  void missingOrDifferentLookupOnlyReadbackRemainsUnresolved() {
    Harness missing = preparedHarness();
    AutomationEmptySelectedSourceIntakeReceipt ownerReceipt = ownerReceipt(ORIGINAL, "readback");
    AccountSelectedOwnerIntakeSettlementReceipt committed =
        AccountSelectedOwnerIntakeSettlementReceipt.create(
            new AutomationSelectedSourceIntakeTerminalReadEvidence(
                AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(
                    NAMESPACE, ORIGINAL),
                ownerReceipt));
    when(missing.automationClient.read(any()))
        .thenAnswer(
            invocation ->
                new AutomationSelectedSourceIntakeTerminalReadEvidence(
                    invocation.getArgument(0), ownerReceipt));
    when(missing.repository.settleCommittedEmpty(any())).thenReturn(committed);
    when(missing.repository.findSettlement(ORIGINAL))
        .thenReturn(Optional.empty(), Optional.empty());
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> asGameDesign(() -> missing.service.settle(ORIGINAL)));
    assertThat(missing.transactions.begins).isEqualTo(3);

    Harness changed = preparedHarness();
    AutomationSelectedSourceIntakeTerminalReadEvidence.Request remoteRequest =
        AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(NAMESPACE, ORIGINAL);
    AutomationSelectedSourceIntakeTerminalReadEvidence.Request otherRequest =
        AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(NAMESPACE, ORIGINAL);
    AccountSelectedOwnerIntakeSettlementReceipt exactCommit =
        AccountSelectedOwnerIntakeSettlementReceipt.create(
            new AutomationSelectedSourceIntakeTerminalReadEvidence(remoteRequest, ownerReceipt));
    AccountSelectedOwnerIntakeSettlementReceipt substitutedReadback =
        AccountSelectedOwnerIntakeSettlementReceipt.create(
            new AutomationSelectedSourceIntakeTerminalReadEvidence(otherRequest, ownerReceipt));
    when(changed.automationClient.read(any()))
        .thenAnswer(
            invocation ->
                new AutomationSelectedSourceIntakeTerminalReadEvidence(
                    invocation.getArgument(0), ownerReceipt));
    when(changed.repository.settleCommittedEmpty(any())).thenReturn(exactCommit);
    when(changed.repository.findSettlement(ORIGINAL))
        .thenReturn(Optional.empty(), Optional.of(substitutedReadback));
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> asGameDesign(() -> changed.service.settle(ORIGINAL)));
    assertThat(changed.transactions.begins).isEqualTo(3);
  }

  @Test
  void wrongPeerEndUserAmbientSqlAndNonAutomationScopeAreRejectedBeforeRepositoryAccess() {
    Harness harness = new Harness(NAMESPACE);
    assertStatus(Status.Code.UNAUTHENTICATED, () -> harness.service.settle(ORIGINAL));

    asPeer(
        peer("test", "entity-management-service"),
        () -> assertStatus(Status.Code.PERMISSION_DENIED, () -> harness.service.settle(ORIGINAL)));
    asPeer(
        peer("other", "game-design-service"),
        () -> assertStatus(Status.Code.PERMISSION_DENIED, () -> harness.service.settle(ORIGINAL)));

    SessionContext.setContext("123", List.of(), Map.of());
    asGameDesign(
        () -> assertStatus(Status.Code.PERMISSION_DENIED, () -> harness.service.settle(ORIGINAL)));
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    asGameDesign(
        () ->
            assertStatus(Status.Code.FAILED_PRECONDITION, () -> harness.service.settle(ORIGINAL)));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    asGameDesign(
        () ->
            assertStatus(Status.Code.FAILED_PRECONDITION, () -> harness.service.settle(ORIGINAL)));
    TransactionSynchronizationManager.clear();

    SelectedOwnerIntakeAuthorizationBinding entityBinding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.ENTITY_MANAGEMENT);
    asGameDesign(
        () ->
            assertStatus(
                Status.Code.FAILED_PRECONDITION, () -> harness.service.settle(entityBinding)));

    Harness wrongBindingNamespace = new Harness("other-runtime");
    asPeer(
        peer("other-runtime", "game-design-service"),
        () ->
            assertStatus(
                Status.Code.FAILED_PRECONDITION,
                () -> wrongBindingNamespace.service.settle(ORIGINAL)));

    verifyNoInteractions(harness.repository, harness.automationClient);
    verifyNoInteractions(wrongBindingNamespace.repository, wrongBindingNamespace.automationClient);
    assertThat(harness.transactions.begins).isZero();
    assertThat(wrongBindingNamespace.transactions.begins).isZero();
  }

  private static Harness preparedHarness() {
    Harness harness = new Harness(NAMESPACE);
    when(harness.repository.findSettlement(ORIGINAL)).thenReturn(Optional.empty());
    return harness;
  }

  private static AutomationEmptySelectedSourceIntakeReceipt ownerReceipt(
      SelectedOwnerIntakeAuthorizationBinding binding, String marker) {
    AutomationEmptySelectedSourceIntakeReceipt receipt =
        Mockito.mock(AutomationEmptySelectedSourceIntakeReceipt.class);
    byte[] bytes =
        ("synthetic canonical COMMITTED_EMPTY receipt " + marker).getBytes(StandardCharsets.UTF_8);
    when(receipt.targetNamespace()).thenReturn(binding.targetNamespace());
    when(receipt.operationId()).thenReturn(binding.operationId());
    when(receipt.fenceId()).thenReturn(binding.fenceId());
    when(receipt.intakeRequestId()).thenReturn(binding.intakeRequestId());
    when(receipt.canonicalTenantId()).thenReturn(binding.tenantId());
    when(receipt.canonicalVersionId()).thenReturn(binding.versionId());
    when(receipt.selectedCommitId()).thenReturn(binding.selected().commitId());
    when(receipt.authorizationBindingBytes()).thenReturn(binding.canonicalBytes());
    when(receipt.authorizationBindingDigest()).thenReturn(binding.digest());
    when(receipt.canonicalBytes()).thenReturn(bytes);
    when(receipt.receiptDigest()).thenReturn(DraftAuthorizationFenceBinding.digest(bytes));
    when(receipt.outcome()).thenReturn("COMMITTED_EMPTY");
    return receipt;
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

  private static void asGameDesign(Runnable action) {
    asPeer(peer(NAMESPACE, "game-design-service"), action);
  }

  private static <T> T asGameDesign(java.util.function.Supplier<T> action) {
    return asPeer(peer(NAMESPACE, "game-design-service"), action);
  }

  private static void asPeer(GrpcPeerIdentity peer, Runnable action) {
    asPeer(
        peer,
        () -> {
          action.run();
          return null;
        });
  }

  private static <T> T asPeer(GrpcPeerIdentity peer, java.util.function.Supplier<T> action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    String uri = "spiffe://firemud/ns/" + namespace + "/sa/" + service;
    return GrpcPeerIdentity.parseUri(uri).orElseThrow();
  }

  private static final class Harness {
    final AccountSelectedOwnerIntakeSourceReservationRepository repository =
        Mockito.mock(AccountSelectedOwnerIntakeSourceReservationRepository.class);
    final AutomationSelectedSourceIntakeTerminalReadClient automationClient =
        Mockito.mock(AutomationSelectedSourceIntakeTerminalReadClient.class);
    final OwnerTransactions transactions = new OwnerTransactions();
    final List<String> steps = new ArrayList<>();
    final AccountSelectedOwnerIntakeSettlementService service;

    Harness(String namespace) {
      service =
          new AccountSelectedOwnerIntakeSettlementService(
              repository, automationClient, transactions, namespace);
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
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
