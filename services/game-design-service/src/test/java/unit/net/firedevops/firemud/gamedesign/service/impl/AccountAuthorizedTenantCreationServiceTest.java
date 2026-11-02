package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationRequest;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationResponse;
import net.firedevops.firemud.account.v1.TenantCreationCurrentAuthorizationReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.tenant.CurrentTenantCreationAuthorizationGrpcCodec;
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
import org.junit.jupiter.api.io.TempDir;
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

  private final List<Long> retryDelays = new ArrayList<>();
  private LongPredicate retryPause = delayMillis -> true;

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
    assertThat(retryDelays)
        .singleElement()
        .satisfies(delay -> assertThat(delay).isBetween(100L, 149L));

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
  void actualReadDeadlineExpiryRetriesWithinFiveSecondDispatchBeforeExactlyOneCreation(
      @TempDir Path directory) throws Exception {
    VerifiedAuthorization expected = prepareFreshCreation();
    long validBefore = expected.validBeforeEpochMillis();
    var readIds = new ConcurrentLinkedQueue<UUID>();
    var failures = new ConcurrentLinkedQueue<Status.Code>();
    var firstReadCancelled = new CountDownLatch(1);
    var successfulReadId = new AtomicReference<UUID>();
    var firstReadBudgetNanos = new AtomicLong();
    var remainingDispatchBudgetNanos = new AtomicLong();
    var endpoint =
        new TenantCreationCurrentAuthorizationReadServiceGrpc
            .TenantCreationCurrentAuthorizationReadServiceImplBase() {
          @Override
          public void resolveCurrentTenantCreationAuthorization(
              ResolveCurrentTenantCreationAuthorizationRequest request,
              StreamObserver<ResolveCurrentTenantCreationAuthorizationResponse> observer) {
            var decoded = CurrentTenantCreationAuthorizationGrpcCodec.decodeRequest(request);
            readIds.add(decoded.readRequestId());
            if (readIds.size() == 1) {
              firstReadBudgetNanos.set(
                  Objects.requireNonNull(Context.current().getDeadline())
                      .timeRemaining(TimeUnit.NANOSECONDS));
              ((ServerCallStreamObserver<ResolveCurrentTenantCreationAuthorizationResponse>)
                      observer)
                  .setOnCancelHandler(firstReadCancelled::countDown);
              // Send no response or error: the actual client-side read deadline must expire.
              return;
            }
            successfulReadId.set(decoded.readRequestId());
            observer.onNext(
                CurrentTenantCreationAuthorizationGrpcCodec.response(
                    decoded.readRequestId(), decoded.binding(), validBefore));
            observer.onCompleted();
          }
        };
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.createFile(directory.resolve("client.crt")).toString());
    tls.setPrivateKey(Files.createFile(directory.resolve("client.key")).toString());
    tls.setCaCert(Files.createFile(directory.resolve("ca.crt")).toString());
    var client =
        spy(
            new AccountTenantCreationCurrentAuthorizationClient(
                new ServiceEndpointsProperties(), tls, mock(GrpcChannelFactory.class), NAMESPACE));
    // Loopback transport controls timing; exact peer pinning has separate client wiring proof.
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .directExecutor()
            .addService(endpoint)
            .build()
            .start();
    var channel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .usePlaintext()
            .directExecutor()
            .build();
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try (client) {
      var stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      stubField.setAccessible(true);
      stubField.set(
          client, TenantCreationCurrentAuthorizationReadServiceGrpc.newBlockingStub(channel));
      var initialized =
          AbstractReloadingBlockingGrpcClient.class.getDeclaredField("explicitlyInitialized");
      initialized.setAccessible(true);
      initialized.setBoolean(client, true);
      doAnswer(
              invocation -> {
                try {
                  return invocation.callRealMethod();
                } catch (StatusRuntimeException failure) {
                  failures.add(failure.getStatus().getCode());
                  remainingDispatchBudgetNanos.set(
                      Objects.requireNonNull(Context.current().getDeadline())
                          .timeRemaining(TimeUnit.NANOSECONDS));
                  throw failure;
                }
              })
          .when(client)
          .resolveCurrent(BINDING);
      when(dispatchRepository.readCommitted(BINDING))
          .thenAnswer(
              invocation ->
                  Optional.of(
                      new AuthorizedDispatch(BINDING, successfulReadId.get(), validBefore)));

      try (var incoming = Context.current().withDeadlineAfter(5, TimeUnit.SECONDS, scheduler)) {
        incoming.run(() -> assertThat(service(client).create(candidate())).isEqualTo(receipt()));
        assertThat(incoming.isCancelled()).isFalse();
        assertThat(readIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(failures).containsExactly(Status.Code.DEADLINE_EXCEEDED);
        assertThat(firstReadBudgetNanos.get()).isBetween(1L, TimeUnit.MILLISECONDS.toNanos(1500));
        assertThat(remainingDispatchBudgetNanos.get())
            .isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
        assertThat(firstReadCancelled.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(retryDelays).isEmpty();
        verify(client, times(2)).resolveCurrent(BINDING);
        verify(creationRepository)
            .createCandidate(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
        verify(outcomeRepository).claimLocked(BINDING);
        verify(dispatchRepository)
            .recordAuthorizedDispatch(BINDING, successfulReadId.get(), validBefore);
        verify(transactionManager).commit(any(TransactionStatus.class));
      }
    } finally {
      scheduler.shutdownNow();
      channel.shutdownNow();
      server.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
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
    assertThat(retryDelays).hasSize(2).allSatisfy(delay -> assertThat(delay).isBetween(100L, 149L));
    assertNoOwnerWrites();
  }

  @Test
  void deadlineExceededReadRetriesHaveNoAddedDelay() {
    StatusRuntimeException deadlineExceeded = Status.DEADLINE_EXCEEDED.asRuntimeException();
    when(authorizationClient.resolveCurrent(BINDING)).thenThrow(deadlineExceeded);

    assertThatThrownBy(() -> service().create(candidate())).isSameAs(deadlineExceeded);

    verify(authorizationClient, times(3)).resolveCurrent(BINDING);
    assertThat(retryDelays).isEmpty();
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
      assertThat(retryDelays).isEmpty();
      assertNoOwnerWrites();
    }
  }

  @Test
  void incomingDeadlineBelowOneSecondPreventsExtraReadAttempt() {
    assertDeadlinePreventsRetry(999);
  }

  @Test
  void incomingDeadlineMustCoverDelayAsWellAsOneSecondReadGuard() {
    assertDeadlinePreventsRetry(1099);
    assertThat(retryDelays).isEmpty();
  }

  @Test
  void expiredIncomingDeadlinePreventsExtraReadAttempt() {
    assertDeadlinePreventsRetry(-1);
  }

  @Test
  void incomingDeadlineBecomingShortDuringReadPreventsRetry() {
    var scheduler = nonFiringDeadlineScheduler();
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

  @Test
  void sufficientIncomingDeadlineAllowsDelayAndExactlyOneCreation() {
    VerifiedAuthorization authorization = prepareFreshCreation();
    when(authorizationClient.resolveCurrent(BINDING))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException())
        .thenReturn(authorization);
    var scheduler = nonFiringDeadlineScheduler();
    AtomicLong nanos = new AtomicLong();
    Deadline deadline =
        Deadline.after(
            1150,
            TimeUnit.MILLISECONDS,
            new Deadline.Ticker() {
              @Override
              public long nanoTime() {
                return nanos.get();
              }
            });
    retryPause =
        delayMillis -> {
          nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(delayMillis));
          return true;
        };

    try (var incoming = Context.current().withDeadline(deadline, scheduler)) {
      incoming.run(() -> assertThat(service().create(candidate())).isEqualTo(receipt()));
      verify(authorizationClient, times(2)).resolveCurrent(BINDING);
      assertThat(retryDelays)
          .singleElement()
          .satisfies(delay -> assertThat(delay).isBetween(100L, 149L));
      verify(creationRepository)
          .createCandidate(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
      verify(transactionManager).commit(any(TransactionStatus.class));
    }
  }

  @Test
  void deadlineBecomingShortDuringDelayPreventsAnotherRead() {
    var scheduler = nonFiringDeadlineScheduler();
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
    when(authorizationClient.resolveCurrent(BINDING)).thenThrow(unavailable);
    retryPause =
        delayMillis -> {
          nanos.set(TimeUnit.MILLISECONDS.toNanos(1001));
          return true;
        };

    try (var incoming = Context.current().withDeadline(deadline, scheduler)) {
      incoming.run(
          () -> assertThatThrownBy(() -> service().create(candidate())).isSameAs(unavailable));
      verify(authorizationClient).resolveCurrent(BINDING);
      assertThat(retryDelays).hasSize(1);
      assertNoOwnerWrites();
    }
  }

  @Test
  void cancellationDuringDelayPreventsAnotherRead() {
    StatusRuntimeException unavailable = Status.UNAVAILABLE.asRuntimeException();
    when(authorizationClient.resolveCurrent(BINDING)).thenThrow(unavailable);
    try (var incoming = Context.current().withCancellation()) {
      retryPause =
          delayMillis -> {
            incoming.cancel(null);
            return true;
          };
      incoming.run(
          () -> assertThatThrownBy(() -> service().create(candidate())).isSameAs(unavailable));
      verify(authorizationClient).resolveCurrent(BINDING);
      assertThat(retryDelays).hasSize(1);
      assertNoOwnerWrites();
    }
  }

  @Test
  void interruptionDuringReadPreventsDelayAndAnotherReadWithoutClearingFlag() {
    StatusRuntimeException unavailable = Status.UNAVAILABLE.asRuntimeException();
    when(authorizationClient.resolveCurrent(BINDING))
        .thenAnswer(
            invocation -> {
              Thread.currentThread().interrupt();
              throw unavailable;
            });
    try {
      assertThatThrownBy(() -> service().create(candidate())).isSameAs(unavailable);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(authorizationClient).resolveCurrent(BINDING);
      assertThat(retryDelays).isEmpty();
      assertNoOwnerWrites();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void interruptionDuringDelayPreventsAnotherReadEvenWhenPauseReportsSuccess() {
    StatusRuntimeException unavailable = Status.UNAVAILABLE.asRuntimeException();
    when(authorizationClient.resolveCurrent(BINDING)).thenThrow(unavailable);
    retryPause =
        delayMillis -> {
          Thread.currentThread().interrupt();
          return true;
        };
    try {
      assertThatThrownBy(() -> service().create(candidate())).isSameAs(unavailable);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(authorizationClient).resolveCurrent(BINDING);
      assertThat(retryDelays).hasSize(1);
      assertNoOwnerWrites();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void productionPauseRestoresInterruptedFlagAndReportsAbort() throws Exception {
    var pause =
        AccountAuthorizedTenantCreationService.class.getDeclaredMethod(
            "pauseAuthorizationRetry", long.class);
    pause.setAccessible(true);
    try {
      Thread.currentThread().interrupt();
      assertThat(pause.invoke(null, 100L)).isEqualTo(false);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private void assertDeadlinePreventsRetry(long remainingMillis) {
    var scheduler = nonFiringDeadlineScheduler();
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
    }
  }

  private static ScheduledExecutorService nonFiringDeadlineScheduler() {
    // Context schedules once using its initial remaining time. Fake-ticker tests advance only
    // that ticker; a real scheduler would independently cancel them using elapsed wall time.
    var scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> scheduledCancellation = mock(ScheduledFuture.class);
    doReturn(scheduledCancellation)
        .when(scheduler)
        .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    return scheduler;
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
    return service(authorizationClient);
  }

  private AccountAuthorizedTenantCreationService service(
      AccountTenantCreationCurrentAuthorizationClient currentAuthorizationClient) {
    try {
      var constructor =
          AccountAuthorizedTenantCreationService.class.getDeclaredConstructor(
              AccountTenantCreationCurrentAuthorizationClient.class,
              GameTenantCreationRepository.class,
              GameTenantCreationOriginalOutcomeRepository.class,
              AccountAuthorizedTenantCreationRepository.class,
              PlatformTransactionManager.class,
              LongPredicate.class);
      constructor.setAccessible(true);
      LongPredicate pause =
          delayMillis -> {
            assertOutsideSql();
            retryDelays.add(delayMillis);
            return retryPause.test(delayMillis);
          };
      return constructor.newInstance(
          currentAuthorizationClient,
          creationRepository,
          outcomeRepository,
          dispatchRepository,
          transactionManager,
          pause);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
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
