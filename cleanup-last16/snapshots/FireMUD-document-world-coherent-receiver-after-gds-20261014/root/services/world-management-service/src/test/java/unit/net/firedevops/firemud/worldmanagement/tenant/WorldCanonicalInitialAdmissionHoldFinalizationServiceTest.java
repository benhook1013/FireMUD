package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInitialAdmissionHoldFinalizationServiceTest {
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void committedNoPriorPointerRequiresFirstPointerVersionOne() {
    HoldIdentity identity = identity(request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null));
    var proof = committed(identity, 1L);
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationRepository.class);
    when(repository.finalizeTerminal(Mockito.eq(identity), Mockito.eq(proof), Mockito.any()))
        .thenReturn(proof);
    AtomicBoolean closed = new AtomicBoolean();
    var service =
        new WorldCanonicalInitialAdmissionHoldFinalizationService(
            repository, verifier(proof, closed));

    assertThat(
            service.finalizeHold(
                identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
        .isEqualTo(proof);
    assertThat(closed).isTrue();
    verify(repository).finalizeTerminal(Mockito.eq(identity), Mockito.eq(proof), Mockito.any());
  }

  @Test
  void expectedClosedRequiresExactNextPointerAndOverflowFailsClosed() {
    HoldIdentity identity = identity(request(InitialAdmissionOrigin.EXPECT_CLOSED, 13L));
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationRepository.class);
    var wrongVersionService =
        new WorldCanonicalInitialAdmissionHoldFinalizationService(
            repository, verifier(committed(identity, 15L), new AtomicBoolean()));

    assertThatThrownBy(
            () ->
                wrongVersionService.finalizeHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException.class)
        .hasMessageContaining("noncanonical pointer version");
    verifyNoInteractions(repository);

    HoldIdentity overflow = identity(request(InitialAdmissionOrigin.EXPECT_CLOSED, Long.MAX_VALUE));
    var overflowService =
        new WorldCanonicalInitialAdmissionHoldFinalizationService(
            repository, verifier(committed(overflow, 1L), new AtomicBoolean()));
    assertThatThrownBy(
            () ->
                overflowService.finalizeHold(
                    overflow, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException.class)
        .hasMessageContaining("overflow");
    verifyNoInteractions(repository);
  }

  @Test
  void positiveDurableAbortRemainsDistinctFromPointerCommit() {
    HoldIdentity identity = identity(request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null));
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
            null,
            null,
            "sha256:" + "c".repeat(64),
            true,
            Instant.parse("2026-10-07T01:02:03.123456Z"));
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationRepository.class);
    when(repository.finalizeTerminal(Mockito.eq(identity), Mockito.eq(proof), Mockito.any()))
        .thenReturn(proof);
    var service =
        new WorldCanonicalInitialAdmissionHoldFinalizationService(
            repository, verifier(proof, new AtomicBoolean()));

    assertThat(
            service.finalizeHold(
                identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED))
        .isEqualTo(proof);
    verify(repository).finalizeTerminal(Mockito.eq(identity), Mockito.eq(proof), Mockito.any());
  }

  @Test
  void pendingAndUnavailableOwnerProofCannotReleaseTheHold() {
    HoldIdentity identity = identity(request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null));
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationRepository.class);
    var service =
        new WorldCanonicalInitialAdmissionHoldFinalizationService(
            repository, WorldCanonicalInitialAdmissionHoldFinalizationService.denyAllVerifier());

    assertThatThrownBy(
            () ->
                service.finalizeHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException.class)
        .hasMessageContaining("PENDING");
    assertThatThrownBy(
            () ->
                service.finalizeHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException.class)
        .hasMessageContaining("unavailable");
    verifyNoInteractions(repository);
  }

  @Test
  void ambientTransactionsAreRejectedBeforeRemoteOwnerProofVerification() {
    HoldIdentity identity = identity(request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null));
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationRepository.class);
    var verifier =
        Mockito.mock(
            WorldCanonicalInitialAdmissionHoldFinalizationService.OwnerProofVerifier.class);
    var service = new WorldCanonicalInitialAdmissionHoldFinalizationService(repository, verifier);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                service.finalizeHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an ambient transaction");
    verify(verifier, never()).verifyAndHold(Mockito.any(), Mockito.any());
    verifyNoInteractions(repository);
  }

  @Test
  void observedPendingDoesNotReachTheWorldFinalizerAndClosesItsHeldObservation() {
    HoldIdentity identity = identity(request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null));
    var pending =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
            null,
            null,
            null,
            false,
            null);
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationRepository.class);
    AtomicBoolean closed = new AtomicBoolean();
    var service =
        new WorldCanonicalInitialAdmissionHoldFinalizationService(
            repository, observedVerifier(pending, closed));

    assertThat(service.observeAndFinalizeHold(identity)).isEmpty();
    assertThat(closed).isTrue();
    verifyNoInteractions(repository);
  }

  @Test
  void observedTerminalOutcomesUseTheExistingFinalizerAndRejectSubstitutedIdentity() {
    HoldIdentity identity = identity(request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null));
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationRepository.class);
    var committed = committed(identity, 1L);
    when(repository.finalizeTerminal(Mockito.eq(identity), Mockito.eq(committed), Mockito.any()))
        .thenReturn(committed);
    AtomicBoolean closed = new AtomicBoolean();
    var service =
        new WorldCanonicalInitialAdmissionHoldFinalizationService(
            repository, observedVerifier(committed, closed));

    assertThat(service.observeAndFinalizeHold(identity)).contains(committed);
    assertThat(closed).isTrue();
    verify(repository).finalizeTerminal(Mockito.eq(identity), Mockito.eq(committed), Mockito.any());

    HoldIdentity substituted =
        new HoldIdentity(
            identity.request(), uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"), identity.holdFence());
    var mismatchService =
        new WorldCanonicalInitialAdmissionHoldFinalizationService(
            repository, observedVerifier(committed(substituted, 1L), new AtomicBoolean()));
    assertThatThrownBy(() -> mismatchService.observeAndFinalizeHold(identity))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException.class)
        .hasMessageContaining("exact requested hold");
    verify(repository, Mockito.times(1))
        .finalizeTerminal(Mockito.eq(identity), Mockito.eq(committed), Mockito.any());

    var aborted =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
            null,
            null,
            "sha256:" + "d".repeat(64),
            true,
            Instant.parse("2026-10-08T01:02:03.123456Z"));
    when(repository.finalizeTerminal(Mockito.eq(identity), Mockito.eq(aborted), Mockito.any()))
        .thenReturn(aborted);
    var abortService =
        new WorldCanonicalInitialAdmissionHoldFinalizationService(
            repository, observedVerifier(aborted, new AtomicBoolean()));
    assertThat(abortService.observeAndFinalizeHold(identity)).contains(aborted);
    verify(repository).finalizeTerminal(Mockito.eq(identity), Mockito.eq(aborted), Mockito.any());
  }

  @Test
  void observedOwnerReadRejectsAmbientTransactionAndSynchronizationBeforeVerification() {
    HoldIdentity identity = identity(request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null));
    var repository = Mockito.mock(WorldCanonicalInitialAdmissionHoldFinalizationRepository.class);
    var verifier =
        Mockito.mock(
            WorldCanonicalInitialAdmissionHoldFinalizationService.OwnerProofVerifier.class);
    var service = new WorldCanonicalInitialAdmissionHoldFinalizationService(repository, verifier);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.observeAndFinalizeHold(identity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an ambient transaction");
    TransactionSynchronizationManager.setActualTransactionActive(false);

    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service.observeAndFinalizeHold(identity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an ambient transaction");
    TransactionSynchronizationManager.clearSynchronization();

    verify(verifier, never()).verifyAndHoldObserved(Mockito.any());
    verifyNoInteractions(repository);
  }

  private static WorldCanonicalInitialAdmissionHoldFinalizationService.OwnerProofVerifier verifier(
      GameSessionCanonicalInitialAdmissionOwnerProof proof, AtomicBoolean closed) {
    return (identity, outcome) ->
        new WorldCanonicalInitialAdmissionHoldFinalizationService.HeldOwnerProof() {
          @Override
          public GameSessionCanonicalInitialAdmissionOwnerProof proof() {
            return proof;
          }

          @Override
          public void requireHeld() {
            if (closed.get()) throw new IllegalStateException("held proof already closed");
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
  }

  private static WorldCanonicalInitialAdmissionHoldFinalizationService.OwnerProofVerifier
      observedVerifier(GameSessionCanonicalInitialAdmissionOwnerProof proof, AtomicBoolean closed) {
    return new WorldCanonicalInitialAdmissionHoldFinalizationService.OwnerProofVerifier() {
      @Override
      public WorldCanonicalInitialAdmissionHoldFinalizationService.HeldOwnerProof verifyAndHold(
          HoldIdentity identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome) {
        throw new AssertionError("Observed verification must not select an expected outcome");
      }

      @Override
      public WorldCanonicalInitialAdmissionHoldFinalizationService.HeldOwnerProof
          verifyAndHoldObserved(HoldIdentity identity) {
        return new WorldCanonicalInitialAdmissionHoldFinalizationService.HeldOwnerProof() {
          @Override
          public GameSessionCanonicalInitialAdmissionOwnerProof proof() {
            return proof;
          }

          @Override
          public void requireHeld() {
            if (closed.get()) throw new IllegalStateException("held proof already closed");
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
      }
    };
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof committed(
      HoldIdentity identity, long pointerVersion) {
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        pointerVersion,
        27L,
        "sha256:" + "a".repeat(64),
        false,
        Instant.parse("2026-10-07T01:02:03.123456Z"));
  }

  private static HoldIdentity identity(Request request) {
    return new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
  }

  private static Request request(InitialAdmissionOrigin origin, Long priorVersion) {
    return new Request(
        "prod",
        TENANT,
        "green-hollow",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        7L,
        "gs-initial-admission-17",
        "a".repeat(64),
        origin,
        12L,
        priorVersion);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
