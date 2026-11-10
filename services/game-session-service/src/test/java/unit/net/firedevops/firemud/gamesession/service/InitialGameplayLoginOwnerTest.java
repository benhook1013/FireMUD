package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult.NonSecretEvidence;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginJournalSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

/** Source ordering and owned-buffer proof; no Account, Redis, wire, or admission claim. */
class InitialGameplayLoginOwnerTest {
  private static final UUID ACCOUNT = UUID.fromString("2bba6eb7-4874-47e1-af9d-caa9e2d5ef72");
  private static final UUID JTI = UUID.fromString("85c3976c-ef18-4e0d-9f7d-0466854973c4");

  @Test
  void commitsCoordinatesCreatesPendingAuthenticatesRecordsWitnessThenInstallsExactResult() {
    var success = successfulOutcome();
    var fixture = success.fixture();
    var outcome = success.outcome();
    var result = success.result();
    var secret = success.secret();
    var coordinates = outcome.coordinates();
    AuthenticateRequest request = fixture.accountRequest;

    assertThat(outcome.status())
        .isEqualTo(InitialGameplayLoginOwner.Status.INSTALLED_NON_ADMITTING);
    assertThat(outcome.failureCode()).isEmpty();
    assertThat(outcome.toString()).doesNotContain("private-secret");
    assertThat(coordinates.requestId()).isNotEqualTo(coordinates.callerContextId());
    assertThat(coordinates.requestId().version()).isEqualTo(4);
    assertThat(coordinates.callerContextId().version()).isEqualTo(4);
    assertThat(coordinates.expectedAttemptFence()).isEqualTo(1);
    assertThat(request.getRequestId()).isEqualTo(coordinates.requestId().toString());
    assertThat(request.getSourceContext().getCallerContextId())
        .isEqualTo(coordinates.callerContextId().toString());
    assertThat(request.getSourceContext().getTransportClassValue()).isEqualTo(1);
    assertThat(request.getPassword()).isEqualTo("original-private-secret");
    assertThat(secret).containsOnly('\0');
    verify(result, never()).close();
    var transferredResult = outcome.takePrivateResult().orElseThrow();
    assertThat(transferredResult).isSameAs(result);
    outcome.close();
    verify(result, never()).close();
    transferredResult.close();
    verify(result).close();
    InOrder order = inOrder(fixture.cleanup, fixture.contexts, fixture.account);
    order.verify(fixture.cleanup).reserve(coordinates);
    order.verify(fixture.contexts).createPending(coordinates);
    order.verify(fixture.account).authenticate(any(AuthenticateRequest.class), any());
    order.verify(fixture.cleanup).recordVerifiedResult(coordinates, result);
    order.verify(fixture.contexts).install(coordinates, result);
    verify(fixture.cleanup, never()).abandonAndRetire(coordinates);
  }

  @Test
  void closingInstalledOutcomeBeforeTransferWipesAndCleansTheExactOriginal() {
    var success = successfulOutcome();
    var outcome = success.outcome();
    var result = success.result();

    outcome.close();

    verify(result).close();
    verify(success.fixture().cleanup).abandonAndRetire(outcome.coordinates());
    assertThat(outcome.custodyLossCleanup().orElseThrow().status())
        .isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
    org.assertj.core.api.Assertions.assertThatThrownBy(outcome::takePrivateResult)
        .isInstanceOf(IllegalStateException.class);
    assertThat(success.secret()).containsOnly('\0');
  }

  @Test
  void transferredResultAndOutcomeCloseLeaveCleanupOwnershipToExplicitLossCallback() {
    var success = successfulOutcome();
    var outcome = success.outcome();
    var result = success.result();
    var coordinates = outcome.coordinates();
    var transferred = outcome.takePrivateResult().orElseThrow();

    outcome.close();

    assertThat(transferred).isSameAs(result);
    verify(result, never()).close();
    verify(success.fixture().cleanup, never()).abandonAndRetire(any());

    doThrow(new IllegalStateException("qualified cleanup unavailable"))
        .when(success.fixture().cleanup)
        .abandonAndRetire(coordinates);
    var cleanup = outcome.abandonOriginalAfterCustodyLoss();

    assertThat(cleanup.coordinates()).isEqualTo(coordinates);
    assertThat(cleanup.status()).isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
    verify(result).close();
    verify(success.fixture().cleanup).abandonAndRetire(coordinates);
  }

  @Test
  void ownedBorrowPinsExactResultAndRefusesReentrantLifecycleChanges() {
    var success = successfulOutcome();
    var outcome = success.outcome();
    var value =
        outcome.withOwnedPrivateResult(
            result -> {
              assertThat(result).isSameAs(success.result());
              assertThatThrownBy(outcome::close).isInstanceOf(IllegalStateException.class);
              assertThatThrownBy(result::close).isInstanceOf(IllegalStateException.class);
              assertThat(result.compactJwtBytes()).isNotEmpty();
              assertThatThrownBy(outcome::takePrivateResult)
                  .isInstanceOf(IllegalStateException.class);
              assertThatThrownBy(outcome::abandonOriginalAfterCustodyLoss)
                  .isInstanceOf(IllegalStateException.class);
              assertThatThrownBy(() -> outcome.withOwnedPrivateResult(nested -> true))
                  .isInstanceOf(IllegalStateException.class);
              return "non-admitting";
            });
    assertThat(value).isEqualTo("non-admitting");
    assertThat(success.result().compactJwtBytes()).isNotEmpty();
    verify(success.fixture().cleanup, never()).abandonAndRetire(any());
    org.mockito.Mockito.clearInvocations(success.result());
    outcome.close();
    verify(success.result()).close();
    verify(success.fixture().cleanup).abandonAndRetire(outcome.coordinates());
    assertThatThrownBy(() -> outcome.withOwnedPrivateResult(result -> true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void callbackFailureReleasesBorrowWithoutTransferringOrRetiringTheResult() {
    var success = successfulOutcome();
    var outcome = success.outcome();
    assertThatThrownBy(
            () ->
                outcome.withOwnedPrivateResult(
                    result -> {
                      throw new IllegalStateException("controlled callback failure");
                    }))
        .hasMessage("controlled callback failure");
    assertThat(outcome.<Boolean>withOwnedPrivateResult(result -> result == success.result()))
        .isTrue();
    assertThat(outcome.takePrivateResult()).containsSame(success.result());
    assertThatThrownBy(() -> outcome.withOwnedPrivateResult(result -> true))
        .isInstanceOf(IllegalStateException.class);
    outcome.abandonOriginalAfterCustodyLoss();
    verify(success.result()).close();
  }

  @Test
  void concurrentCloseWaitsForOwnedBorrowThenRetiresExactOriginal() throws Exception {
    var success = successfulOutcome();
    var outcome = success.outcome();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closing = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var borrowed =
          executor.submit(
              () ->
                  outcome.withOwnedPrivateResult(
                      result -> {
                        entered.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("test timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return result == success.result();
                      }));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var closed =
            executor.submit(
                () -> {
                  closing.countDown();
                  outcome.close();
                });
        assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> closed.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        verify(success.result(), never()).close();
        verify(success.fixture().cleanup, never()).abandonAndRetire(any());
        release.countDown();
        assertThat(borrowed.get(5, TimeUnit.SECONDS)).isTrue();
        closed.get(5, TimeUnit.SECONDS);
        verify(success.result()).close();
        verify(success.fixture().cleanup).abandonAndRetire(outcome.coordinates());
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"transfer", "abandon", "direct-close"})
  void lifecycleDrainKeepsBorrowedBytesLiveAndBlocksNewBorrows(String operation) throws Exception {
    var success = successfulOutcome();
    var outcome = success.outcome();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var lifecycleThread = new AtomicReference<Thread>();
    byte[] expected = success.result().compactJwtBytes();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var borrowed =
          executor.submit(
              () ->
                  outcome.withOwnedPrivateResult(
                      result -> {
                        assertThat(result).isSameAs(success.result());
                        entered.countDown();
                        await(release);
                        assertThat(result.compactJwtBytes()).containsExactly(expected);
                        return true;
                      }));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var mutation =
            executor.submit(
                () -> {
                  lifecycleThread.set(Thread.currentThread());
                  return switch (operation) {
                    case "transfer" -> outcome.takePrivateResult().orElseThrow();
                    case "abandon" -> outcome.abandonOriginalAfterCustodyLoss();
                    default -> {
                      success.result().close();
                      yield true;
                    }
                  };
                });
        awaitWaiting(lifecycleThread);
        assertThatThrownBy(() -> outcome.withOwnedPrivateResult(result -> true))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mutation.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        verify(success.fixture().cleanup, never()).abandonAndRetire(any());
        release.countDown();
        assertThat(borrowed.get(5, TimeUnit.SECONDS)).isTrue();
        Object result = mutation.get(5, TimeUnit.SECONDS);
        if (operation.equals("transfer")) {
          assertThat(result).isSameAs(success.result());
          assertThat(success.result().compactJwtBytes()).containsExactly(expected);
          outcome.close();
          verify(success.fixture().cleanup, never()).abandonAndRetire(any());
          outcome.abandonOriginalAfterCustodyLoss();
        } else if (operation.equals("direct-close")) {
          outcome.close();
        }
        assertThatThrownBy(() -> success.result().compactJwtBytes())
            .isInstanceOf(IllegalStateException.class);
        verify(success.fixture().cleanup).abandonAndRetire(outcome.coordinates());
      } finally {
        release.countDown();
      }
    } finally {
      Arrays.fill(expected, (byte) 0);
      outcome.close();
    }
  }

  @Test
  void heldOriginalCleanupRetriesOutsideOutcomeMonitorAndRefusesReentrantMutation()
      throws Exception {
    var success = successfulOutcome();
    var outcome = success.outcome();
    var accessed = new AtomicBoolean();
    try (var executor = Executors.newSingleThreadExecutor()) {
      when(success.fixture().cleanup.abandonAndRetire(outcome.coordinates()))
          .thenAnswer(
              call -> {
                assertThatThrownBy(outcome::close).isInstanceOf(IllegalStateException.class);
                accessed.set(
                    executor
                        .submit(() -> outcome.custodyLossCleanup().isEmpty())
                        .get(5, TimeUnit.SECONDS));
                return held(outcome.coordinates());
              });
      outcome.close();
      assertThat(accessed).isTrue();
      assertThat(outcome.custodyLossCleanup().orElseThrow().status())
          .isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
      org.mockito.Mockito.doReturn(
              new InitialGameplayLoginCleanupOwner.CleanupObservation(
                  outcome.coordinates(),
                  InitialGameplayLoginCleanupOwner.Status.ACCOUNT_PENDING,
                  Optional.empty(),
                  Optional.empty()))
          .when(success.fixture().cleanup)
          .abandonAndRetire(outcome.coordinates());
      assertThat(outcome.abandonOriginalAfterCustodyLoss().status())
          .isEqualTo(InitialGameplayLoginCleanupOwner.Status.ACCOUNT_PENDING);
      verify(success.fixture().cleanup, times(2)).abandonAndRetire(outcome.coordinates());
      verify(success.result()).close();
    }
  }

  @Test
  void equalReconstructedEvidenceCannotReplaceClosedGenuineOwnedResult() {
    var success = successfulOutcome();
    try (var reconstructed = controlledPrivateResult(success.outcome().coordinates())) {
      assertThat(reconstructed.evidence()).isEqualTo(success.result().evidence());
      assertThat(reconstructed).isNotSameAs(success.result());
      success.result().close();
      assertThatThrownBy(() -> success.outcome().withOwnedPrivateResult(result -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(success.outcome().takePrivateResult()).containsSame(success.result());
      assertThat(reconstructed.compactJwtBytes()).isNotEmpty();
      success.outcome().abandonOriginalAfterCustodyLoss();
      verify(success.fixture().cleanup).abandonAndRetire(success.outcome().coordinates());
    }
  }

  @Test
  void directResultBorrowRefusesOwnerReentrancyWithoutChangingOwnedState() {
    var success = successfulOutcome();
    success
        .result()
        .withLivePrivateResult(
            result -> {
              assertThatThrownBy(success.outcome()::close)
                  .isInstanceOf(IllegalStateException.class);
              assertThatThrownBy(success.outcome()::takePrivateResult)
                  .isInstanceOf(IllegalStateException.class);
              assertThatThrownBy(success.outcome()::abandonOriginalAfterCustodyLoss)
                  .isInstanceOf(IllegalStateException.class);
              assertThat(result.compactJwtBytes()).isNotEmpty();
              verify(success.fixture().cleanup, never()).abandonAndRetire(any());
              return true;
            });
    assertThat(
            success.outcome().<Boolean>withOwnedPrivateResult(result -> result == success.result()))
        .isTrue();
    success.outcome().close();
    verify(success.fixture().cleanup).abandonAndRetire(success.outcome().coordinates());
    assertThatThrownBy(() -> success.result().compactJwtBytes())
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void directResultPinCannotWaitBehindOutcomeDrainThatIsWaitingForTheSamePin(boolean transferred)
      throws Exception {
    var success = successfulOutcome();
    if (transferred)
      assertThat(success.outcome().takePrivateResult()).containsSame(success.result());
    var entered = new CountDownLatch(1);
    var attemptLifecycle = new CountDownLatch(1);
    var closingThread = new AtomicReference<Thread>();
    var borrowed = new CompletableFuture<Boolean>();
    var closed = new CompletableFuture<Void>();
    // Daemon workers keep this logical-deadlock regression bounded even if the cycle returns.
    Thread.ofPlatform()
        .daemon()
        .start(
            () -> {
              try {
                borrowed.complete(
                    success
                        .result()
                        .withLivePrivateResult(
                            result -> {
                              entered.countDown();
                              await(attemptLifecycle);
                              assertThatThrownBy(success.outcome()::close)
                                  .isInstanceOf(IllegalStateException.class);
                              assertThatThrownBy(success.outcome()::takePrivateResult)
                                  .isInstanceOf(IllegalStateException.class);
                              assertThatThrownBy(success.outcome()::abandonOriginalAfterCustodyLoss)
                                  .isInstanceOf(IllegalStateException.class);
                              assertThat(result.compactJwtBytes()).isNotEmpty();
                              return true;
                            }));
              } catch (Throwable failure) {
                borrowed.completeExceptionally(failure);
              }
            });
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.ofPlatform()
        .daemon()
        .start(
            () -> {
              closingThread.set(Thread.currentThread());
              try {
                if (transferred) success.outcome().abandonOriginalAfterCustodyLoss();
                else success.outcome().close();
                closed.complete(null);
              } catch (Throwable failure) {
                closed.completeExceptionally(failure);
              }
            });
    try {
      awaitWaiting(closingThread);
      assertThat(closed).isNotDone();
      attemptLifecycle.countDown();
      assertThat(borrowed.get(5, TimeUnit.SECONDS)).isTrue();
      closed.get(5, TimeUnit.SECONDS);
      verify(success.fixture().cleanup).abandonAndRetire(success.outcome().coordinates());
      assertThatThrownBy(() -> success.result().compactJwtBytes())
          .isInstanceOf(IllegalStateException.class);
    } finally {
      attemptLifecycle.countDown();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Test gate interrupted", failure);
    }
  }

  private static void awaitWaiting(AtomicReference<Thread> thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.get() == null || thread.get().getState() != Thread.State.WAITING) {
      if (System.nanoTime() >= deadline) throw new IllegalStateException("Lifecycle did not drain");
      Thread.sleep(1);
    }
  }

  private static SuccessfulOutcome successfulOutcome() {
    var fixture = new Fixture();
    var secret = "original-private-secret".toCharArray();
    var credentials = presentation(secret);
    var result = new AtomicReference<CanonicalGameplayLoginResult>();
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any()))
        .thenAnswer(
            invocation -> {
              fixture.accountRequest = invocation.getArgument(0);
              var retained = spy(controlledPrivateResult(fixture.coordinates));
              result.set(retained);
              return retained;
            });
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.cleanup.recordVerifiedResult(any(), any()))
        .thenAnswer(
            invocation ->
                snapshotWithWitness(invocation.getArgument(0), invocation.getArgument(1)));
    when(fixture.contexts.install(any(), any()))
        .thenAnswer(invocation -> Optional.of(installed(invocation.getArgument(0))));
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(credentials);
    return new SuccessfulOutcome(fixture, outcome, result.get(), secret);
  }

  /** Controlled private bytes only: this unit fixture supplies no authenticated Account origin. */
  private static CanonicalGameplayLoginResult controlledPrivateResult(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    try {
      var constructor =
          CanonicalGameplayLoginResult.class.getDeclaredConstructor(
              byte[].class, NonSecretEvidence.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          "controlled-private-token".getBytes(StandardCharsets.US_ASCII), evidence(coordinates));
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Controlled result fixture unavailable", failure);
    }
  }

  @Test
  void uncertainPendingCreationSelectsOriginalCleanupWithoutAccountDispatch() {
    var fixture = new Fixture();
    var secret = "still-private".toCharArray();
    var credentials = presentation(secret);
    when(fixture.contexts.createPending(any())).thenReturn(Optional.empty());
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(credentials);

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(outcome.cleanupStatus()).isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verifyNoInteractions(fixture.account);
    verify(fixture.contexts, never()).install(any(), any());
  }

  @Test
  void ambiguousAuthenticateFailureSelectsCleanupAndNeverInstalls() {
    var fixture = new Fixture();
    var secret = "transient-secret".toCharArray();
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(presentation(secret));

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(outcome.failureCode()).contains("UNAVAILABLE");
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verify(fixture.contexts, never()).install(any(), any());
  }

  @Test
  void callerCancellationWhileCredentialCustodyIsCurrentRemainsCancelled() {
    var fixture = new Fixture();
    var credentials = presentation("transient-secret".toCharArray());
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any()))
        .thenThrow(Status.CANCELLED.asRuntimeException());
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(credentials);

    assertThat(outcome.failureCode()).contains("CANCELLED");
    assertThat(credentials.isCurrent()).isFalse();
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
  }

  @Test
  void cancelledAccountCallAfterCredentialCustodyLossReportsCustodyLost() {
    var fixture = new Fixture();
    var credentials = presentation("transient-secret".toCharArray());
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any()))
        .thenAnswer(
            invocation -> {
              credentials.close();
              throw Status.CANCELLED.asRuntimeException();
            });
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(credentials);

    assertThat(outcome.failureCode()).contains("CUSTODY_LOST");
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
  }

  @Test
  void lostCustodyAfterVerifiedAccountResponseRecordsWitnessThenCleansWithoutInstall() {
    var fixture = new Fixture();
    var secret = "transient-secret".toCharArray();
    var credentials = presentation(secret);
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenAnswer(invocation -> evidence(fixture.coordinates));
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any()))
        .thenAnswer(
            invocation -> {
              credentials.close();
              return result;
            });
    when(fixture.cleanup.recordVerifiedResult(any(), any()))
        .thenAnswer(
            invocation ->
                snapshotWithWitness(invocation.getArgument(0), invocation.getArgument(1)));
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(credentials);

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(outcome.failureCode()).contains("CUSTODY_LOST");
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).recordVerifiedResult(any(), any());
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verify(fixture.contexts, never()).install(any(), any());
    verify(result).close();
  }

  @Test
  void unavailableWitnessReadbackStillUsesExactOriginalCleanupFreeze() {
    var fixture = new Fixture();
    var secret = "transient-secret".toCharArray();
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenAnswer(invocation -> evidence(fixture.coordinates));
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any())).thenReturn(result);
    when(fixture.cleanup.recordVerifiedResult(any(), any()))
        .thenThrow(new IllegalStateException("journal readback unavailable"));
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(presentation(secret));

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).recordVerifiedResult(outcome.coordinates(), result);
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verify(fixture.contexts, never()).install(any(), any());
    verify(result).close();
  }

  @Test
  void failedExactInstallClosesOriginalAndRetiresFromTheSameInvocationResult() {
    var fixture = new Fixture();
    var secret = "transient-secret".toCharArray();
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenAnswer(invocation -> evidence(fixture.coordinates));
    when(fixture.contexts.createPending(any()))
        .thenAnswer(
            invocation ->
                Optional.of(InitialGameplayLoginContextState.pending(invocation.getArgument(0))));
    when(fixture.account.authenticate(any(AuthenticateRequest.class), any())).thenReturn(result);
    when(fixture.cleanup.recordVerifiedResult(any(), any()))
        .thenAnswer(
            invocation ->
                snapshotWithWitness(invocation.getArgument(0), invocation.getArgument(1)));
    when(fixture.contexts.install(any(), any())).thenReturn(Optional.empty());
    when(fixture.cleanup.abandonAndRetire(any()))
        .thenAnswer(invocation -> held(invocation.getArgument(0)));

    var outcome = fixture.owner.receive(presentation(secret));

    assertThat(outcome.status()).isEqualTo(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    assertThat(secret).containsOnly('\0');
    verify(fixture.cleanup).recordVerifiedResult(any(), any());
    verify(fixture.cleanup).abandonAndRetire(outcome.coordinates());
    verify(result).close();
  }

  private static InitialGameplayLoginCredentialPresentation presentation(char[] secret) {
    return new InitialGameplayLoginCredentialPresentation(
        "player@example.test", "203.0.113.10", secret);
  }

  private static InitialGameplayLoginJournalSnapshot snapshot(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    return new InitialGameplayLoginJournalSnapshot(
        coordinates, false, Optional.empty(), Optional.empty(), Optional.empty());
  }

  private static InitialGameplayLoginJournalSnapshot snapshotWithWitness(
      InitialGameplayLoginAttemptCoordinates coordinates, CanonicalGameplayLoginResult result) {
    var evidence = result.evidence();
    var witness =
        InitialGameplayLoginTokenWitness.newBuilder()
            .setTokenJti(evidence.tokenJti().toString())
            .setTokenSha256(evidence.tokenSha256())
            .setTokenGeneration(Long.parseLong(evidence.tokenGeneration()))
            .setIssuanceFence(Long.parseLong(evidence.issuanceFence()))
            .build();
    return new InitialGameplayLoginJournalSnapshot(
        coordinates, false, Optional.of(witness), Optional.empty(), Optional.empty());
  }

  private static NonSecretEvidence evidence(InitialGameplayLoginAttemptCoordinates coordinates) {
    return new NonSecretEvidence(
        ACCOUNT,
        coordinates.requestId(),
        coordinates.callerContextId(),
        JTI,
        "a".repeat(64),
        "game-session-account-delegation/v1",
        1000,
        1000,
        1200,
        "1",
        "3",
        ByteString.EMPTY,
        ByteString.EMPTY,
        ByteString.EMPTY);
  }

  private static InitialGameplayLoginContextState installed(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    var state = mock(InitialGameplayLoginContextState.class);
    when(state.state()).thenReturn(InitialGameplayLoginContextState.State.INSTALLED);
    when(state.coordinates()).thenReturn(coordinates);
    return state;
  }

  private static InitialGameplayLoginCleanupOwner.CleanupObservation held(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    return new InitialGameplayLoginCleanupOwner.CleanupObservation(
        coordinates,
        InitialGameplayLoginCleanupOwner.Status.HELD,
        Optional.<InitialGameplayLoginDisposalResult>empty(),
        Optional.<InitialGameplayLoginDisposalResult>empty());
  }

  private static final class Fixture {
    private final InitialGameplayLoginContextRedisClient contexts =
        mock(InitialGameplayLoginContextRedisClient.class);
    private final InitialGameplayLoginCleanupOwner cleanup =
        mock(InitialGameplayLoginCleanupOwner.class);
    private final CanonicalGameplayLoginAccountClient account =
        mock(CanonicalGameplayLoginAccountClient.class);
    private final InitialGameplayLoginOwner owner =
        new InitialGameplayLoginOwner(contexts, cleanup, account);
    private InitialGameplayLoginAttemptCoordinates coordinates;
    private AuthenticateRequest accountRequest;

    private Fixture() {
      when(cleanup.reserve(any()))
          .thenAnswer(
              invocation -> {
                coordinates = invocation.getArgument(0);
                return snapshot(coordinates);
              });
      when(account.authenticate(any(AuthenticateRequest.class), any()))
          .thenAnswer(
              invocation -> {
                accountRequest = invocation.getArgument(0);
                return null;
              });
    }
  }

  private record SuccessfulOutcome(
      Fixture fixture,
      InitialGameplayLoginOwner.Outcome outcome,
      CanonicalGameplayLoginResult result,
      char[] secret) {}
}
