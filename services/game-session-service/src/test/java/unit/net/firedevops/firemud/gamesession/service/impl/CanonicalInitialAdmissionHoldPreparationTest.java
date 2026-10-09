package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot.IntentState;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot.SourceBinding;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.service.impl.CanonicalInitialAdmissionHoldPreparation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalInitialAdmissionHoldPreparationTest {
  private static final String NAMESPACE = "hold-unit";
  private static final UUID TENANT = uuid(1);
  private static final UUID REALM = uuid(2);
  private static final UUID PLAYABLE_NAMESPACE = uuid(3);
  private static final UUID INSTANCE = uuid(4);
  private static final UUID VERSION = uuid(5);
  private static final String SHA256 = "sha256:" + "a".repeat(64);
  private static final Request HOLD_REQUEST = holdRequest("request-1");
  private static final WorldCanonicalInstanceLifecycleEvidence.Request LIFECYCLE_REQUEST =
      lifecycleRequest();

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void commitsIntentBeforeWorldAndAttachesInASeparateWritableReadCommittedTransaction()
      throws Exception {
    List<String> events = new ArrayList<>();
    Fixtures fixtures = new Fixtures(events);
    HoldIdentity identity = identity(HOLD_REQUEST, 7);
    when(fixtures.repository.reserve(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenAnswer(
            invocation -> {
              assertWritableTransaction();
              events.add("reserve");
              return snapshot(IntentState.PENDING_HOLD, null, HOLD_REQUEST, LIFECYCLE_REQUEST);
            });
    when(fixtures.worldClient.acquire(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              events.add("world");
              return identity;
            });
    when(fixtures.repository.attach(HOLD_REQUEST, identity))
        .thenAnswer(
            invocation -> {
              assertWritableTransaction();
              events.add("attach");
              return snapshot(IntentState.HOLD_ATTACHED, identity, HOLD_REQUEST, LIFECYCLE_REQUEST);
            });

    CanonicalInitialAdmissionIntentSnapshot result =
        fixtures.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST);

    assertThat(result.state()).isEqualTo(IntentState.HOLD_ATTACHED);
    assertThat(events)
        .containsExactly("begin", "reserve", "commit", "world", "begin", "attach", "commit");
    assertThat(fixtures.transactionManager.transactionDefinitions)
        .allSatisfy(
            definition -> {
              assertThat(definition.getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              assertThat(definition.getIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
              assertThat(definition.isReadOnly()).isFalse();
            });
  }

  @Test
  void reserveFailurePreventsWorldAcquisition() throws Exception {
    Fixtures fixtures = new Fixtures(new ArrayList<>());
    when(fixtures.repository.reserve(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenThrow(new IllegalStateException("reserve failed"));

    assertThatThrownBy(() -> fixtures.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .hasMessage("reserve failed");

    verifyNoInteractions(fixtures.worldClient);
    verify(fixtures.repository, never())
        .attach(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void reserveCommitFailurePreventsWorldAcquisitionAndAttachment() throws Exception {
    Fixtures fixtures = new Fixtures(new ArrayList<>(), true);
    when(fixtures.repository.reserve(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenReturn(snapshot(IntentState.PENDING_HOLD, null, HOLD_REQUEST, LIFECYCLE_REQUEST));

    assertThatThrownBy(() -> fixtures.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("reserve commit failed");

    verifyNoInteractions(fixtures.worldClient);
    verify(fixtures.repository, never())
        .attach(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    assertThat(fixtures.transactionManager.transactionDefinitions).hasSize(1);
  }

  @Test
  void remoteFailureLeavesPendingIntentAndRetryUsesTheSameExactRequest() throws Exception {
    Fixtures fixtures = new Fixtures(new ArrayList<>());
    HoldIdentity identity = identity(HOLD_REQUEST, 8);
    when(fixtures.repository.reserve(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenReturn(snapshot(IntentState.PENDING_HOLD, null, HOLD_REQUEST, LIFECYCLE_REQUEST));
    when(fixtures.worldClient.acquire(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenThrow(new IllegalStateException("World unavailable"))
        .thenReturn(identity);
    when(fixtures.repository.attach(HOLD_REQUEST, identity))
        .thenReturn(snapshot(IntentState.HOLD_ATTACHED, identity, HOLD_REQUEST, LIFECYCLE_REQUEST));

    assertThatThrownBy(() -> fixtures.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .hasMessage("World unavailable");
    assertThat(fixtures.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST).state())
        .isEqualTo(IntentState.HOLD_ATTACHED);

    verify(fixtures.repository, org.mockito.Mockito.times(2))
        .reserve(HOLD_REQUEST, LIFECYCLE_REQUEST);
    verify(fixtures.worldClient, org.mockito.Mockito.times(2))
        .acquire(HOLD_REQUEST, LIFECYCLE_REQUEST);
    verify(fixtures.repository).attach(HOLD_REQUEST, identity);
  }

  @Test
  void attachFailurePropagatesWithoutReplacingRequestOrCallingAnyCompensation() throws Exception {
    Fixtures fixtures = new Fixtures(new ArrayList<>());
    HoldIdentity identity = identity(HOLD_REQUEST, 9);
    when(fixtures.repository.reserve(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenReturn(snapshot(IntentState.PENDING_HOLD, null, HOLD_REQUEST, LIFECYCLE_REQUEST));
    when(fixtures.worldClient.acquire(HOLD_REQUEST, LIFECYCLE_REQUEST)).thenReturn(identity);
    when(fixtures.repository.attach(HOLD_REQUEST, identity))
        .thenThrow(new IllegalStateException("attach changed"));

    assertThatThrownBy(() -> fixtures.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .hasMessage("attach changed");

    verify(fixtures.worldClient).acquire(HOLD_REQUEST, LIFECYCLE_REQUEST);
    verify(fixtures.repository).attach(HOLD_REQUEST, identity);
    verify(fixtures.repository, never())
        .reserve(
            org.mockito.ArgumentMatchers.argThat(r -> r != HOLD_REQUEST),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void exactAttachedOrTerminalReplayReturnsHistoricalSnapshotWithoutWorldCall() throws Exception {
    for (IntentState state : List.of(IntentState.HOLD_ATTACHED, IntentState.TERMINAL)) {
      Fixtures fixtures = new Fixtures(new ArrayList<>());
      HoldIdentity retained = identity(HOLD_REQUEST, 10);
      CanonicalInitialAdmissionIntentSnapshot historical =
          snapshot(state, retained, HOLD_REQUEST, LIFECYCLE_REQUEST);
      when(fixtures.repository.reserve(HOLD_REQUEST, LIFECYCLE_REQUEST)).thenReturn(historical);

      assertThat(fixtures.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
          .isSameAs(historical);

      verifyNoInteractions(fixtures.worldClient);
      verify(fixtures.repository, never())
          .attach(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
  }

  @Test
  void wrongWorldIdentityAndChangedSnapshotTupleAreDenied() throws Exception {
    Fixtures wrongIdentity = new Fixtures(new ArrayList<>());
    when(wrongIdentity.repository.reserve(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenReturn(snapshot(IntentState.PENDING_HOLD, null, HOLD_REQUEST, LIFECYCLE_REQUEST));
    when(wrongIdentity.worldClient.acquire(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenReturn(identity(holdRequest("different-request"), 11));

    assertThatThrownBy(() -> wrongIdentity.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not echo");
    verify(wrongIdentity.repository, never())
        .attach(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    Fixtures changedSnapshot = new Fixtures(new ArrayList<>());
    when(changedSnapshot.repository.reserve(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenReturn(
            snapshot(
                IntentState.PENDING_HOLD,
                null,
                HOLD_REQUEST,
                lifecycleRequestWithChangedIdentity()));
    assertThatThrownBy(() -> changedSnapshot.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed the exact request tuple");
    verifyNoInteractions(changedSnapshot.worldClient);

    Fixtures changedAttach = new Fixtures(new ArrayList<>());
    HoldIdentity acquired = identity(HOLD_REQUEST, 12);
    when(changedAttach.repository.reserve(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .thenReturn(snapshot(IntentState.PENDING_HOLD, null, HOLD_REQUEST, LIFECYCLE_REQUEST));
    when(changedAttach.worldClient.acquire(HOLD_REQUEST, LIFECYCLE_REQUEST)).thenReturn(acquired);
    when(changedAttach.repository.attach(HOLD_REQUEST, acquired))
        .thenReturn(
            snapshot(
                IntentState.HOLD_ATTACHED,
                identity(HOLD_REQUEST, 14),
                HOLD_REQUEST,
                LIFECYCLE_REQUEST));
    assertThatThrownBy(() -> changedAttach.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact World hold identity");
  }

  @Test
  void ambientTransactionOrSynchronizationDeniesBeforeRepositoryOrWorldAccess() throws Exception {
    Fixtures transaction = new Fixtures(new ArrayList<>());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> transaction.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    verifyNoInteractions(transaction.repository, transaction.worldClient);

    TransactionSynchronizationManager.clear();
    Fixtures synchronization = new Fixtures(new ArrayList<>());
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> synchronization.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    verifyNoInteractions(synchronization.repository, synchronization.worldClient);
  }

  private static void assertWritableTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private static CanonicalInitialAdmissionIntentSnapshot snapshot(
      IntentState state,
      HoldIdentity identity,
      Request holdRequest,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    return new CanonicalInitialAdmissionIntentSnapshot(
        holdRequest,
        lifecycleRequest,
        state,
        identity,
        new SourceBinding(
            NAMESPACE,
            TENANT,
            "world",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            true,
            true,
            1L,
            uuid(20),
            SHA256,
            SHA256,
            uuid(21),
            1L,
            INSTANCE,
            1L,
            VERSION,
            1L,
            "control-request-1",
            "launch-descriptor-1",
            1L,
            1L,
            SHA256,
            SHA256,
            SHA256),
        java.time.Instant.parse("2026-01-01T00:00:00Z"),
        java.time.Instant.parse("2026-01-01T00:00:00Z"),
        state == IntentState.TERMINAL ? java.time.Instant.parse("2026-01-01T00:01:00Z") : null);
  }

  private static Request holdRequest(String requestId) {
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            NAMESPACE,
            TENANT,
            "world",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            2L,
            1L,
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            requestId);
    return new Request(
        NAMESPACE,
        TENANT,
        "world",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        2L,
        requestId,
        requestDigest,
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        1L,
        null);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest() {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        uuid(6),
        NAMESPACE,
        TENANT,
        "world",
        INSTANCE,
        PLAYABLE_NAMESPACE,
        "SHARED",
        true,
        "control-request-1",
        VERSION,
        SHA256,
        SHA256,
        SHA256);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request
      lifecycleRequestWithChangedIdentity() {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        uuid(7),
        NAMESPACE,
        TENANT,
        "world",
        INSTANCE,
        PLAYABLE_NAMESPACE,
        "SHARED",
        true,
        "control-request-1",
        VERSION,
        SHA256,
        SHA256,
        SHA256);
  }

  private static HoldIdentity identity(Request request, int id) {
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(request, uuid(id), uuid(id + 1));
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private static final class Fixtures {
    private final CanonicalInitialAdmissionIntentRepository repository =
        mock(CanonicalInitialAdmissionIntentRepository.class);
    private final WorldCanonicalInitialAdmissionHoldClient worldClient =
        mock(WorldCanonicalInitialAdmissionHoldClient.class);
    private final RecordingTransactionManager transactionManager;
    private final CanonicalInitialAdmissionHoldPreparation preparation;

    private Fixtures(List<String> events) {
      this(events, false);
    }

    private Fixtures(List<String> events, boolean failFirstCommit) {
      this.transactionManager = new RecordingTransactionManager(events, failFirstCommit);
      this.preparation =
          new CanonicalInitialAdmissionHoldPreparation(repository, worldClient, transactionManager);
    }
  }

  private static final class RecordingTransactionManager
      extends AbstractPlatformTransactionManager {
    private final List<String> events;
    private final List<TransactionDefinition> transactionDefinitions = new ArrayList<>();
    private final boolean failFirstCommit;
    private int commitCount;

    private RecordingTransactionManager(List<String> events, boolean failFirstCommit) {
      this.events = events;
      this.failFirstCommit = failFirstCommit;
    }

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      transactionDefinitions.add(definition);
      events.add("begin");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commitCount++;
      events.add("commit");
      if (failFirstCommit && commitCount == 1) {
        throw new IllegalStateException("reserve commit failed");
      }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      events.add("rollback");
    }
  }
}
