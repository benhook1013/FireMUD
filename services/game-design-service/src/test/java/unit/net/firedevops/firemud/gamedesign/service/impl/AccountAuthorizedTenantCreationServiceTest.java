package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.gamedesign.client.AccountTenantCreationCurrentAuthorizationClient;
import net.firedevops.firemud.gamedesign.client.AccountTenantCreationCurrentAuthorizationClient.VerifiedAuthorization;
import net.firedevops.firemud.gamedesign.repository.AccountAuthorizedTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.AccountAuthorizedTenantCreationRepository.AuthorizedDispatch;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeSnapshot;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OutcomeStatus;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.service.impl.AccountAuthorizedTenantCreationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountAuthorizedTenantCreationServiceTest {
  private static final String NAMESPACE = "creation-owner-test";
  private static final UUID CREATE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CREATION_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID TENANT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID AUTHORIZATION_READ_ID =
      UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final String SOURCE_KEY = "fresh-owner-key-91";
  private static final String NAME = "Fresh Realm";
  private static final String DESCRIPTION = "Account-authorized creation fixture";
  private static final String ACCOUNT_DIGEST = "sha256:" + "b".repeat(64);
  private static final String REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
  private static final Binding BINDING =
      new Binding(
          NAMESPACE, REQUEST_ID, ACCOUNT_OPERATION_ID, ACCOUNT_ID, REQUEST_DIGEST, ACCOUNT_DIGEST);

  private final AccountTenantCreationCurrentAuthorizationClient authorizationClient =
      mock(AccountTenantCreationCurrentAuthorizationClient.class);
  private final GameTenantCreationRepository creationRepository =
      mock(GameTenantCreationRepository.class);
  private final GameTenantCreationOriginalOutcomeRepository outcomeRepository =
      mock(GameTenantCreationOriginalOutcomeRepository.class);
  private final AccountAuthorizedTenantCreationRepository dispatchRepository =
      mock(AccountAuthorizedTenantCreationRepository.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void changedCandidateDigestIsRejectedBeforeOwnerReadsOrAccountCallback() {
    Binding changed =
        new Binding(
            NAMESPACE,
            REQUEST_ID,
            ACCOUNT_OPERATION_ID,
            ACCOUNT_ID,
            "sha256:" + "c".repeat(64),
            ACCOUNT_DIGEST);

    assertThatThrownBy(
            () ->
                service()
                    .create(
                        new AccountAuthorizedTenantCreationService.CandidateRequest(
                            CREATE_ID, changed, SOURCE_KEY, NAME, DESCRIPTION)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact Account original request digest");
    verifyNoInteractions(
        authorizationClient, creationRepository, outcomeRepository, dispatchRepository);
  }

  @Test
  void exactTerminalCreatedReplayReturnsReceiptWithoutNewAccountAuthorizationOrWrites() {
    FreshTenantCreationEvidence receipt = receipt();
    when(outcomeRepository.readCommitted(BINDING)).thenReturn(Optional.of(createdSnapshot()));
    when(creationRepository.read(REQUEST_ID, NAMESPACE)).thenReturn(Optional.of(receipt));

    FreshTenantCreationEvidence replay = service().create(candidate());

    assertThat(replay).isEqualTo(receipt);
    verify(outcomeRepository).readCommitted(BINDING);
    verify(creationRepository).read(REQUEST_ID, NAMESPACE);
    verifyNoInteractions(authorizationClient, dispatchRepository);
  }

  @Test
  void exactCurrentAccountAuthorityPrecedesBoundedAtomicOwnerTransaction() {
    FreshTenantCreationEvidence receipt = receipt();
    VerifiedAuthorization authorization = prepareFreshCreation();
    long validBefore = authorization.validBeforeEpochMillis();
    when(authorizationClient.resolveCurrent(BINDING)).thenReturn(authorization);

    FreshTenantCreationEvidence created = service().create(candidate());

    assertThat(created).isEqualTo(receipt);
    var order =
        inOrder(authorizationClient, dispatchRepository, outcomeRepository, creationRepository);
    order.verify(authorizationClient).resolveCurrent(BINDING);
    order.verify(dispatchRepository).applyLocalWriteTimeouts();
    order.verify(dispatchRepository).isAuthorizationCurrent(validBefore);
    order.verify(outcomeRepository).claimLocked(BINDING);
    order
        .verify(creationRepository)
        .createCandidate(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
    order.verify(outcomeRepository).markCreatedLocked(BINDING, receipt);
    order
        .verify(dispatchRepository)
        .recordAuthorizedDispatch(BINDING, AUTHORIZATION_READ_ID, validBefore);
    verify(transactionManager).commit(any(TransactionStatus.class));
  }

  @Test
  void transientReadFailuresRecoverBeforeExactlyOneOwnerWrite() {
    VerifiedAuthorization authorization = prepareFreshCreation();
    AtomicInteger attempts = new AtomicInteger();
    when(authorizationClient.resolveCurrent(BINDING))
        .thenAnswer(
            invocation -> {
              assertOutsideSql();
              int attempt = attempts.incrementAndGet();
              if (attempt == 1) throw Status.UNAVAILABLE.asRuntimeException();
              if (attempt == 2) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
              return authorization;
            });

    assertThat(service().create(candidate())).isEqualTo(receipt());

    var order = inOrder(authorizationClient, dispatchRepository, creationRepository);
    order.verify(authorizationClient, times(3)).resolveCurrent(BINDING);
    order.verify(dispatchRepository).applyLocalWriteTimeouts();
    order
        .verify(creationRepository)
        .createCandidate(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
    verify(transactionManager).getTransaction(any(TransactionDefinition.class));
    verify(transactionManager).commit(any(TransactionStatus.class));
    verify(outcomeRepository).claimLocked(BINDING);
    verify(creationRepository)
        .createCandidate(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
    verify(dispatchRepository)
        .recordAuthorizedDispatch(
            BINDING, AUTHORIZATION_READ_ID, authorization.validBeforeEpochMillis());
  }

  @Test
  void transientReadExhaustionWithoutIncomingDeadlineStopsAfterThreeAttemptsBeforeWrites() {
    StatusRuntimeException unavailable = Status.UNAVAILABLE.asRuntimeException();
    when(authorizationClient.resolveCurrent(BINDING))
        .thenAnswer(
            invocation -> {
              assertOutsideSql();
              throw unavailable;
            });

    assertThatThrownBy(() -> service().create(candidate())).isSameAs(unavailable);

    verify(authorizationClient, times(3)).resolveCurrent(BINDING);
    assertNoOwnerWrites();
  }

  @Test
  void definitiveAndUnexpectedReadFailuresRemainSingleShot() {
    for (RuntimeException failure :
        new RuntimeException[] {
          Status.FAILED_PRECONDITION.asRuntimeException(),
          Status.PERMISSION_DENIED.asRuntimeException(),
          Status.INTERNAL.asRuntimeException(),
          new IllegalStateException("Unexpected current-authorization failure")
        }) {
      clearInvocations(authorizationClient);
      doThrow(failure).when(authorizationClient).resolveCurrent(BINDING);
      assertThatThrownBy(() -> service().create(candidate())).isSameAs(failure);
      verify(authorizationClient).resolveCurrent(BINDING);
      assertNoOwnerWrites();
    }
  }

  @Test
  void incomingDeadlineBelowOneSecondPreventsExtraReadAttempt() {
    assertDeadlinePreventsRetry(999);
  }

  @Test
  void expiredIncomingDeadlinePreventsExtraReadAttempt() {
    assertDeadlinePreventsRetry(-1);
  }

  @Test
  void incomingDeadlineBecomingShortDuringReadPreventsRetry() {
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    AtomicLong nanos = new AtomicLong();
    Deadline deadline =
        Deadline.after(
            2,
            TimeUnit.SECONDS,
            new Deadline.Ticker() {
              @Override
              public long nanoTime() {
                return nanos.get();
              }
            });
    StatusRuntimeException unavailable = Status.UNAVAILABLE.asRuntimeException();
    when(authorizationClient.resolveCurrent(BINDING))
        .thenAnswer(
            invocation -> {
              assertOutsideSql();
              nanos.set(TimeUnit.MILLISECONDS.toNanos(1500));
              throw unavailable;
            });
    try (var incoming = Context.current().withDeadline(deadline, scheduler)) {
      incoming.run(
          () -> assertThatThrownBy(() -> service().create(candidate())).isSameAs(unavailable));
      verify(authorizationClient).resolveCurrent(BINDING);
      assertNoOwnerWrites();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void incomingCancellationDuringReadPreventsRetry() {
    StatusRuntimeException unavailable = Status.UNAVAILABLE.asRuntimeException();
    try (var incoming = Context.current().withCancellation()) {
      when(authorizationClient.resolveCurrent(BINDING))
          .thenAnswer(
              invocation -> {
                assertOutsideSql();
                incoming.cancel(null);
                throw unavailable;
              });
      incoming.run(
          () -> assertThatThrownBy(() -> service().create(candidate())).isSameAs(unavailable));
      verify(authorizationClient).resolveCurrent(BINDING);
      assertNoOwnerWrites();
    }
  }

  private void assertDeadlinePreventsRetry(long remainingMillis) {
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    Deadline deadline =
        Deadline.after(
            remainingMillis,
            TimeUnit.MILLISECONDS,
            new Deadline.Ticker() {
              @Override
              public long nanoTime() {
                return 0L;
              }
            });
    StatusRuntimeException unavailable = Status.UNAVAILABLE.asRuntimeException();
    when(authorizationClient.resolveCurrent(BINDING))
        .thenAnswer(
            invocation -> {
              assertOutsideSql();
              throw unavailable;
            });
    try (var incoming = Context.current().withDeadline(deadline, scheduler)) {
      incoming.run(
          () -> assertThatThrownBy(() -> service().create(candidate())).isSameAs(unavailable));
      verify(authorizationClient).resolveCurrent(BINDING);
      assertNoOwnerWrites();
    } finally {
      scheduler.shutdownNow();
    }
  }

  private VerifiedAuthorization prepareFreshCreation() {
    FreshTenantCreationEvidence receipt = receipt();
    long validBefore = System.currentTimeMillis() + 60_000L;
    VerifiedAuthorization authorization = mock(VerifiedAuthorization.class);
    when(authorization.binding()).thenReturn(BINDING);
    when(authorization.readRequestId()).thenReturn(AUTHORIZATION_READ_ID);
    when(authorization.validBeforeEpochMillis()).thenReturn(validBefore);
    when(outcomeRepository.readCommitted(BINDING))
        .thenReturn(Optional.empty(), Optional.of(createdSnapshot()));
    when(creationRepository.read(REQUEST_ID, NAMESPACE))
        .thenReturn(Optional.empty(), Optional.of(receipt));
    when(dispatchRepository.isAuthorizationCurrent(validBefore)).thenReturn(true);
    when(outcomeRepository.claimLocked(BINDING)).thenReturn(pendingSnapshot());
    when(creationRepository.createCandidate(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION))
        .thenReturn(receipt);
    when(outcomeRepository.markCreatedLocked(BINDING, receipt)).thenReturn(createdSnapshot());
    when(dispatchRepository.readCommitted(BINDING))
        .thenReturn(
            Optional.of(new AuthorizedDispatch(BINDING, AUTHORIZATION_READ_ID, validBefore)));
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return mock(TransactionStatus.class);
            });
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactionManager)
        .commit(any(TransactionStatus.class));
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactionManager)
        .rollback(any(TransactionStatus.class));
    return authorization;
  }

  private void assertNoOwnerWrites() {
    verifyNoInteractions(dispatchRepository, transactionManager);
    verify(outcomeRepository, never()).claimLocked(any());
    verify(outcomeRepository, never()).markCreatedLocked(any(), any());
    verify(creationRepository, never()).createCandidate(any(), any(), any(), any(), any());
  }

  private static void assertOutsideSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  @Test
  void expiredFreshDatabaseCheckRefusesBeforeOriginalClaimOrV30Write() {
    long validBefore = System.currentTimeMillis() + 60_000L;
    VerifiedAuthorization authorization = mock(VerifiedAuthorization.class);
    when(authorization.binding()).thenReturn(BINDING);
    when(authorization.readRequestId()).thenReturn(AUTHORIZATION_READ_ID);
    when(authorization.validBeforeEpochMillis()).thenReturn(validBefore);
    when(authorizationClient.resolveCurrent(BINDING)).thenReturn(authorization);
    when(outcomeRepository.readCommitted(BINDING)).thenReturn(Optional.empty());
    when(creationRepository.read(REQUEST_ID, NAMESPACE)).thenReturn(Optional.empty());
    when(dispatchRepository.isAuthorizationCurrent(validBefore)).thenReturn(false);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(mock(TransactionStatus.class));

    assertThatThrownBy(() -> service().create(candidate()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expired before owner writes");
    verify(dispatchRepository).applyLocalWriteTimeouts();
    verify(dispatchRepository).isAuthorizationCurrent(validBefore);
    verify(outcomeRepository, never()).claimLocked(any());
    verify(creationRepository, never()).createCandidate(any(), any(), any(), any(), any());
    verify(transactionManager).rollback(any(TransactionStatus.class));
    verify(transactionManager, never()).commit(any(TransactionStatus.class));
  }

  @Test
  void definitiveNotCreatedOriginalCannotReachAccountCallbackOrReopen() {
    OriginalOutcomeSnapshot closed =
        new OriginalOutcomeSnapshot(BINDING, OutcomeStatus.NOT_CREATED, null, null);
    when(outcomeRepository.readCommitted(BINDING)).thenReturn(Optional.of(closed));

    assertThatThrownBy(() -> service().create(candidate()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot reopen");
    verifyNoInteractions(authorizationClient, creationRepository, dispatchRepository);
  }

  private AccountAuthorizedTenantCreationService service() {
    return new AccountAuthorizedTenantCreationService(
        authorizationClient,
        creationRepository,
        outcomeRepository,
        dispatchRepository,
        transactionManager);
  }

  @Test
  void explicitClosureUsesExistingIdentityLockedOwnerWithoutCreationOrCurrentAuthorization() {
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    service().closeOriginal(BINDING);
    verify(transactionManager)
        .getTransaction(
            org.mockito.ArgumentMatchers.argThat(
                definition ->
                    definition.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED
                        && !definition.isReadOnly()));
    verify(dispatchRepository).applyLocalWriteTimeouts();
    verify(outcomeRepository).closeNotCreatedLocked(BINDING);
    verifyNoInteractions(authorizationClient, creationRepository);
  }

  @Test
  void alreadyCreatedClosureRefusalPropagatesWithoutCreation() {
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    when(outcomeRepository.closeNotCreatedLocked(BINDING))
        .thenThrow(new IllegalStateException("Created original cannot close"));
    assertThatThrownBy(() -> service().closeOriginal(BINDING))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(authorizationClient, creationRepository);
    verify(outcomeRepository, never()).markCreatedLocked(any(), any());
    verify(transactionManager).rollback(any(TransactionStatus.class));
    verify(transactionManager, never()).commit(any(TransactionStatus.class));
  }

  private static AccountAuthorizedTenantCreationService.CandidateRequest candidate() {
    return new AccountAuthorizedTenantCreationService.CandidateRequest(
        CREATE_ID, BINDING, SOURCE_KEY, NAME, DESCRIPTION);
  }

  private static FreshTenantCreationEvidence receipt() {
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        REQUEST_ID,
        CREATION_OPERATION_ID,
        REQUEST_DIGEST,
        TENANT_ID,
        91L,
        SOURCE_KEY,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            REQUEST_ID,
            CREATION_OPERATION_ID,
            REQUEST_DIGEST,
            TENANT_ID,
            91L,
            SOURCE_KEY,
            "NEW_GAME_ROW"));
  }

  private static OriginalOutcomeSnapshot pendingSnapshot() {
    return new OriginalOutcomeSnapshot(BINDING, OutcomeStatus.PENDING, null, null);
  }

  private static OriginalOutcomeSnapshot createdSnapshot() {
    FreshTenantCreationEvidence receipt = receipt();
    return new OriginalOutcomeSnapshot(
        BINDING, OutcomeStatus.CREATED, receipt.operationId(), receipt.evidenceDigest());
  }
}
