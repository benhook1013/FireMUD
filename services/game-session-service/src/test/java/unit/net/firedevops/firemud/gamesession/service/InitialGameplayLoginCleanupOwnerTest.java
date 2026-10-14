package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.Disposition;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.ProofKind;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginJournalSnapshot;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginAttemptClosure.AcknowledgedClosure;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCleanupOwner.CleanupObservation;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCleanupOwner.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled journal/closure/client interface proof, not PostgreSQL, Redis, mTLS or custody proof.
 */
class InitialGameplayLoginCleanupOwnerTest {
  private static final InitialGameplayLoginAttemptCoordinates ORIGINAL =
      new InitialGameplayLoginAttemptCoordinates(uuid(1), uuid(2), 7);
  private static final UUID OPERATION = uuid(3);

  @AfterEach
  void clearTransactions() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void reservationRequiresCommitAndIndependentReadbackAndDoesNotDispatchAuthentication() {
    Fixture fixture = new Fixture();
    assertThat(fixture.owner.reserve(ORIGINAL)).isEqualTo(fixture.snapshot);
    assertThat(fixture.events).containsExactly("reserve", "commit", "read");
    verifyNoInteractions(fixture.closure, fixture.account);
  }

  @ParameterizedTest
  @ValueSource(strings = {"commit", "readback", "changed"})
  void failedReservationNeverSuppliesACommittedReservation(String failure) {
    Fixture fixture = new Fixture();
    if (failure.equals("commit")) {
      fixture.transactions.failCommit = true;
    } else if (failure.equals("readback")) {
      doReturn(Optional.empty()).when(fixture.journal).readCommitted(ORIGINAL);
    } else {
      doReturn(
              Optional.of(
                  snapshot(
                      new InitialGameplayLoginAttemptCoordinates(uuid(8), uuid(2), 7),
                      false,
                      Optional.empty(),
                      Optional.empty(),
                      Optional.empty())))
          .when(fixture.journal)
          .readCommitted(ORIGINAL);
    }
    assertThatThrownBy(() -> fixture.owner.reserve(ORIGINAL))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(fixture.closure, fixture.account);
  }

  @Test
  void commitsAbandonmentBeforeQualifiedClosureAndFrozenRequestBeforeAccountOutsideTransactions() {
    Fixture fixture = new Fixture();
    fixture.response = receipt(Disposition.RETIRED);
    var observation = fixture.owner.abandonAndRetire(ORIGINAL);
    assertThat(observation.status()).isEqualTo(Status.ACCOUNT_RETIRED);
    assertThat(fixture.events)
        .containsExactly(
            "abandon", "commit", "read", "closure", "freeze", "commit", "read", "account",
            "receipt", "commit", "read");
    assertThat(fixture.snapshot.abandoned()).isTrue();
    assertThat(fixture.snapshot.frozenRetirementRequest().orElseThrow().hasTokenWitness())
        .isFalse();
    assertThat(fixture.snapshot.retainedReceipt()).contains(fixture.response);
    verify(fixture.account, never()).authenticate(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"unavailable", "lost-ack", "absent", "changed", "reset"})
  void unqualifiedClosureRetainsAbandonmentAndCannotDispatchAccount(String reason) {
    Fixture fixture = new Fixture();
    if (reason.equals("changed")) {
      doAnswer(
              invocation -> {
                Function<AcknowledgedClosure, CleanupObservation> continuation =
                    invocation.getArgument(1);
                return Optional.of(
                    continuation.apply(
                        new AcknowledgedClosure(
                            new InitialGameplayLoginAttemptCoordinates(uuid(8), uuid(9), 8))));
              })
          .when(fixture.closure)
          .withAcknowledgedClosure(any(), any());
    } else if (reason.equals("absent") || reason.equals("lost-ack")) {
      doReturn(Optional.empty()).when(fixture.closure).withAcknowledgedClosure(any(), any());
    } else {
      doThrow(new IllegalStateException("Controlled unqualified closure"))
          .when(fixture.closure)
          .withAcknowledgedClosure(any(), any());
    }
    assertThat(fixture.owner.abandonAndRetire(ORIGINAL).status()).isEqualTo(Status.HELD);
    assertThat(fixture.snapshot.abandoned()).isTrue();
    assertThat(fixture.snapshot.frozenRetirementRequest()).isEmpty();
    verifyNoInteractions(fixture.account);
  }

  @ParameterizedTest
  @ValueSource(strings = {"abandon-commit", "abandon-read", "freeze-commit", "freeze-read"})
  void uncertainDurableBoundaryStopsItsFollowingDispatch(String failure) {
    Fixture fixture = new Fixture();
    if (failure.equals("abandon-commit")) {
      fixture.transactions.failCommit = true;
    } else if (failure.equals("abandon-read")) {
      doReturn(Optional.empty()).when(fixture.journal).readCommitted(ORIGINAL);
    } else if (failure.equals("freeze-commit")) {
      fixture.transactions.failAtCommit = 2;
    } else {
      doAnswer(
              invocation ->
                  fixture.snapshot.frozenRetirementRequest().isPresent()
                      ? Optional.empty()
                      : Optional.of(fixture.snapshot))
          .when(fixture.journal)
          .readCommitted(ORIGINAL);
    }
    assertThat(fixture.owner.abandonAndRetire(ORIGINAL).status()).isEqualTo(Status.HELD);
    verifyNoInteractions(fixture.account);
    if (failure.startsWith("abandon")) {
      verifyNoInteractions(fixture.closure);
    }
  }

  @Test
  void secretLossUsesOnlyOriginalCoordinatesAndKeepsAccountPendingAcrossRedrives() {
    Fixture fixture = new Fixture();
    var first = fixture.owner.abandonAndRetire(ORIGINAL);
    var second = fixture.owner.abandonAndRetire(ORIGINAL);
    assertThat(first.status()).isEqualTo(Status.ACCOUNT_PENDING);
    assertThat(second.status()).isEqualTo(Status.ACCOUNT_PENDING);
    assertThat(second.priorRetainedReceipt()).contains(fixture.response);
    assertThat(fixture.snapshot.abandoned()).isTrue();
    assertThat(fixture.snapshot.verifiedWitness()).isEmpty();
    verify(fixture.account, times(2))
        .retireInitialGameplayLogin(InitialGameplayLoginJournalSnapshot.request(ORIGINAL, null));
    verify(fixture.account, never()).authenticate(any());
  }

  @Test
  void witnessAbsenceFreezesBeforeLostDispatchAndLateEvidenceCannotUpgradeRetry() {
    Fixture fixture = new Fixture();
    doThrow(new IllegalStateException("Controlled lost response"))
        .when(fixture.account)
        .retireInitialGameplayLogin(any());
    assertThat(fixture.owner.abandonAndRetire(ORIGINAL).status()).isEqualTo(Status.HELD);
    RetireInitialGameplayLoginRequest originalRequest =
        fixture.snapshot.frozenRetirementRequest().orElseThrow();
    fixture.snapshot =
        snapshot(
            ORIGINAL,
            true,
            Optional.of(witness()),
            fixture.snapshot.frozenRetirementRequest(),
            Optional.empty());
    doReturn(fixture.response).when(fixture.account).retireInitialGameplayLogin(any());
    assertThat(fixture.owner.abandonAndRetire(ORIGINAL).status()).isEqualTo(Status.ACCOUNT_PENDING);
    assertThat(fixture.snapshot.verifiedWitness()).contains(witness());
    assertThat(fixture.snapshot.frozenRetirementRequest()).contains(originalRequest);
    assertThat(originalRequest.hasTokenWitness()).isFalse();
    verify(fixture.account, times(2)).retireInitialGameplayLogin(originalRequest);
  }

  @Test
  void completeWitnessFreezesWithoutRetainingCredentialOrAuthenticationResponse() {
    Fixture fixture = new Fixture();
    fixture.snapshot =
        snapshot(ORIGINAL, false, Optional.of(witness()), Optional.empty(), Optional.empty());
    assertThat(fixture.owner.abandonAndRetire(ORIGINAL).status()).isEqualTo(Status.ACCOUNT_PENDING);
    assertThat(fixture.snapshot.frozenRetirementRequest().orElseThrow().getTokenWitness())
        .isEqualTo(witness());
    verify(fixture.account)
        .retireInitialGameplayLogin(
            InitialGameplayLoginJournalSnapshot.request(ORIGINAL, witness()));
  }

  @Test
  void terminalRedriveCallsAccountAgainRatherThanReplayingTheJournalReceipt() {
    Fixture fixture = new Fixture();
    fixture.response = receipt(Disposition.RETIRED);
    assertThat(fixture.owner.abandonAndRetire(ORIGINAL).status()).isEqualTo(Status.ACCOUNT_RETIRED);
    fixture.events.clear();
    var redrive = fixture.owner.abandonAndRetire(ORIGINAL);
    assertThat(redrive.priorRetainedReceipt()).contains(fixture.response);
    assertThat(redrive.currentCallReceipt()).contains(fixture.response);
    assertThat(fixture.events).contains("closure", "account");
    // The existing client/Account contract owns real marker reassertion; this proves it is invoked.
    verify(fixture.account, times(2)).retireInitialGameplayLogin(any());
    verify(fixture.closure, times(2)).withAcknowledgedClosure(any(), any());
  }

  @Test
  void unavailableTerminalRedriveKeepsPriorReceiptSeparateFromCurrentAvailability() {
    Fixture fixture = new Fixture();
    fixture.response = receipt(Disposition.RETIRED);
    fixture.owner.abandonAndRetire(ORIGINAL);
    doThrow(new IllegalStateException("Unavailable"))
        .when(fixture.account)
        .retireInitialGameplayLogin(any());
    var redrive = fixture.owner.abandonAndRetire(ORIGINAL);
    assertThat(redrive.status()).isEqualTo(Status.HELD);
    assertThat(redrive.priorRetainedReceipt()).contains(fixture.response);
    assertThat(redrive.currentCallReceipt()).isEmpty();
    assertThat(fixture.snapshot.retainedReceipt()).contains(fixture.response);
  }

  @ParameterizedTest
  @ValueSource(strings = {"operation", "coordinates", "pair", "pending-downgrade"})
  void changedAccountReplayDoesNotReplaceTheOriginalTerminalReceipt(String change) {
    Fixture fixture = new Fixture();
    fixture.response = receipt(Disposition.RETIRED);
    fixture.owner.abandonAndRetire(ORIGINAL);
    var retained = fixture.snapshot.retainedReceipt();
    fixture.response =
        switch (change) {
          case "operation" ->
              new InitialGameplayLoginDisposalResult(
                  uuid(1), uuid(2), uuid(9), Disposition.RETIRED, ProofKind.PHYSICAL_TOKEN_DENIAL);
          case "coordinates" ->
              new InitialGameplayLoginDisposalResult(
                  uuid(8),
                  uuid(2),
                  OPERATION,
                  Disposition.RETIRED,
                  ProofKind.PHYSICAL_TOKEN_DENIAL);
          case "pair" -> receipt(Disposition.NO_TOKEN);
          case "pending-downgrade" -> receipt(Disposition.PENDING);
          default -> throw new AssertionError(change);
        };
    assertThat(fixture.owner.abandonAndRetire(ORIGINAL).status()).isEqualTo(Status.HELD);
    assertThat(fixture.snapshot.retainedReceipt()).isEqualTo(retained);
    verify(fixture.journal, times(1)).recordDisposalReceipt(any(), any());
  }

  @Test
  void receiptCommitLossDoesNotReportSuccessfulCurrentCompletion() {
    Fixture fixture = new Fixture();
    fixture.response = receipt(Disposition.RETIRED);
    fixture.transactions.failAtCommit = 3;
    var observation = fixture.owner.abandonAndRetire(ORIGINAL);
    assertThat(observation.status()).isEqualTo(Status.HELD);
    assertThat(observation.currentCallReceipt()).isEmpty();
    verify(fixture.account).retireInitialGameplayLogin(any());
  }

  @Test
  void ambientTransactionCannotSpanRemoteCalls() {
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> fixture.owner.abandonAndRetire(ORIGINAL))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> fixture.owner.reserve(ORIGINAL))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(fixture.journal, fixture.closure, fixture.account);
  }

  private static InitialGameplayLoginJournalSnapshot snapshot(
      InitialGameplayLoginAttemptCoordinates coordinates,
      boolean abandoned,
      Optional<InitialGameplayLoginTokenWitness> witness,
      Optional<RetireInitialGameplayLoginRequest> request,
      Optional<InitialGameplayLoginDisposalResult> receipt) {
    return new InitialGameplayLoginJournalSnapshot(
        coordinates, abandoned, witness, request, receipt);
  }

  private static InitialGameplayLoginTokenWitness witness() {
    return InitialGameplayLoginTokenWitness.newBuilder()
        .setTokenJti(uuid(4).toString())
        .setTokenSha256("a".repeat(64))
        .setTokenGeneration(1)
        .setIssuanceFence(19)
        .build();
  }

  private static InitialGameplayLoginDisposalResult receipt(Disposition disposition) {
    return new InitialGameplayLoginDisposalResult(
        ORIGINAL.requestId(),
        ORIGINAL.callerContextId(),
        OPERATION,
        disposition,
        switch (disposition) {
          case PENDING -> ProofKind.NONE;
          case RETIRED -> ProofKind.PHYSICAL_TOKEN_DENIAL;
          case NO_TOKEN -> ProofKind.NO_ISSUANCE_BINDING;
        });
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("10000000-0000-4000-8000-%012d".formatted(suffix));
  }

  private static final class Fixture {
    private final List<String> events = new ArrayList<>();
    private final InitialGameplayLoginCoordinationJournalRepository journal =
        mock(InitialGameplayLoginCoordinationJournalRepository.class);
    private final InitialGameplayLoginAttemptClosure closure =
        mock(InitialGameplayLoginAttemptClosure.class);
    private final CanonicalGameplayLoginAccountClient account =
        mock(CanonicalGameplayLoginAccountClient.class);
    private final RecordingTransactions transactions = new RecordingTransactions(events);
    private final InitialGameplayLoginCleanupOwner owner =
        new InitialGameplayLoginCleanupOwner(journal, closure, account, transactions);
    private InitialGameplayLoginJournalSnapshot snapshot =
        snapshot(ORIGINAL, false, Optional.empty(), Optional.empty(), Optional.empty());
    private InitialGameplayLoginDisposalResult response = receipt(Disposition.PENDING);

    private Fixture() {
      when(journal.reserve(ORIGINAL))
          .thenAnswer(
              invocation -> {
                assertTransaction();
                events.add("reserve");
                return snapshot;
              });
      when(journal.readCommitted(ORIGINAL))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                events.add("read");
                return Optional.of(snapshot);
              });
      when(journal.abandon(ORIGINAL))
          .thenAnswer(
              invocation -> {
                assertTransaction();
                events.add("abandon");
                snapshot =
                    snapshot(
                        ORIGINAL,
                        true,
                        snapshot.verifiedWitness(),
                        snapshot.frozenRetirementRequest(),
                        snapshot.retainedReceipt());
                return snapshot;
              });
      when(journal.freezeRetirementRequest(ORIGINAL))
          .thenAnswer(
              invocation -> {
                assertTransaction();
                events.add("freeze");
                snapshot =
                    snapshot(
                        ORIGINAL,
                        true,
                        snapshot.verifiedWitness(),
                        Optional.of(
                            snapshot
                                .frozenRetirementRequest()
                                .orElseGet(
                                    () ->
                                        InitialGameplayLoginJournalSnapshot.request(
                                            ORIGINAL, snapshot.verifiedWitness().orElse(null)))),
                        snapshot.retainedReceipt());
                return snapshot;
              });
      when(journal.recordDisposalReceipt(any(), any()))
          .thenAnswer(
              invocation -> {
                assertTransaction();
                events.add("receipt");
                snapshot =
                    snapshot(
                        ORIGINAL,
                        true,
                        snapshot.verifiedWitness(),
                        snapshot.frozenRetirementRequest(),
                        Optional.of(invocation.getArgument(1)));
                return snapshot;
              });
      when(closure.withAcknowledgedClosure(any(), any()))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(snapshot.abandoned()).isTrue();
                events.add("closure");
                Function<AcknowledgedClosure, CleanupObservation> continuation =
                    invocation.getArgument(1);
                return Optional.of(continuation.apply(new AcknowledgedClosure(ORIGINAL)));
              });
      when(account.retireInitialGameplayLogin(any()))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(snapshot.frozenRetirementRequest()).contains(invocation.getArgument(0));
                events.add("account");
                return response;
              });
    }
  }

  private static void assertTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private static final class RecordingTransactions implements PlatformTransactionManager {
    private final List<String> events;
    private int commits;
    private int failAtCommit;
    private boolean failCommit;

    private RecordingTransactions(List<String> events) {
      this.events = events;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      TransactionDefinition effectiveDefinition =
          definition == null ? new DefaultTransactionDefinition() : definition;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          effectiveDefinition.getIsolationLevel());
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(
          effectiveDefinition.isReadOnly());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commits++;
      TransactionSynchronizationManager.clear();
      if (failCommit || commits == failAtCommit) {
        throw new IllegalStateException("Controlled lost COMMIT acknowledgement");
      }
      events.add("commit");
    }

    @Override
    public void rollback(TransactionStatus status) {
      TransactionSynchronizationManager.clear();
    }
  }
}
