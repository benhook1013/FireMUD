package integration.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftTerminalReconciliationService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Component fixtures stipulate authenticated current-source capture and authenticated definitive
 * owner readback. They do not implement or prove those absent producers, RPC or source-writer
 * gates.
 */
@Testcontainers(disabledWithoutDocker = true)
class DraftAuthorizationFencePostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void twoOwnerConsumerPersistsWorldOnceAndRecoversMissingGameDesignOnRetry() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding();
    tx(context, () -> context.repository().reserve(binding));
    tx(context, () -> context.repository().claimCommitOrder(binding));
    SourceChange change = change(binding);
    assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
    OwnerReadback worldAbort =
        ownerReadback(binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {31});
    OwnerReadback gameDesignAbort =
        ownerReadback(binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {32});
    GameDesignDraftTerminalReadClient gameDesignClient =
        mock(GameDesignDraftTerminalReadClient.class);
    WorldDraftTerminalReadClient worldClient = mock(WorldDraftTerminalReadClient.class);
    when(gameDesignClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction();
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              assertThat(request.originalAccountBinding())
                  .containsExactly(binding.canonicalBytes());
              return new GameDesignDraftTerminalReadEvidence(request, Optional.empty());
            })
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction();
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              assertThat(request.originalAccountBinding())
                  .containsExactly(binding.canonicalBytes());
              return new GameDesignDraftTerminalReadEvidence(request, Optional.of(gameDesignAbort));
            });
    when(worldClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction();
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              assertThat(request.originalAccountBinding())
                  .containsExactly(binding.canonicalBytes());
              return new WorldDraftTerminalReadEvidence(request, Optional.of(worldAbort));
            });
    var service =
        new AccountDraftTerminalReconciliationService(
            context.repository(),
            context.transaction().getTransactionManager(),
            gameDesignClient,
            worldClient,
            "firemud-test");

    assertThat(service.reconcile(binding.operationId())).contains(Settlement.PENDING);
    assertThat(tx(context, () -> context.repository().readOwnerResult(binding, Owner.WORLD)))
        .get()
        .satisfies(
            stored -> assertThat(stored.readback()).containsExactly(worldAbort.canonicalBytes()));
    assertThat(tx(context, () -> context.repository().readOwnerResult(binding, Owner.GAME_DESIGN)))
        .isEmpty();
    assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isFalse();

    assertThat(service.reconcile(binding.operationId())).contains(Settlement.FAILED_NONPUBLICATION);
    assertThat(tx(context, () -> context.repository().readOwnerResult(binding, Owner.WORLD)))
        .get()
        .satisfies(
            stored -> assertThat(stored.readback()).containsExactly(worldAbort.canonicalBytes()));
    assertThat(tx(context, () -> context.repository().readOwnerResult(binding, Owner.GAME_DESIGN)))
        .get()
        .satisfies(
            stored ->
                assertThat(stored.readback()).containsExactly(gameDesignAbort.canonicalBytes()));
    assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isTrue();
    verify(gameDesignClient, times(2)).read(any());
    verify(worldClient).read(any());
  }

  @Test
  void mixedCommitOrderPreservesOriginalWorldCommitAndTerminalConsumerRetry() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding();
    tx(context, () -> context.repository().reserve(binding));
    tx(context, () -> context.repository().claimCommitOrder(binding));
    SourceChange change = change(binding);
    assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
    OwnerReadback originalWorldCommit =
        ownerReadback(binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {41, 42});
    OwnerReadback gameDesignAbort =
        ownerReadback(binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {43});
    GameDesignDraftTerminalReadClient gameDesignClient =
        mock(GameDesignDraftTerminalReadClient.class);
    WorldDraftTerminalReadClient worldClient = mock(WorldDraftTerminalReadClient.class);
    when(gameDesignClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction();
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(request, Optional.of(gameDesignAbort));
            });
    when(worldClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction();
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return worldEvidence(request, originalWorldCommit);
            });
    var service =
        new AccountDraftTerminalReconciliationService(
            context.repository(),
            context.transaction().getTransactionManager(),
            gameDesignClient,
            worldClient,
            "firemud-test");

    assertThat(service.reconcile(binding.operationId())).contains(Settlement.FAILED_NONPUBLICATION);
    assertThat(tx(context, () -> context.repository().readOwnerResult(binding, Owner.WORLD)))
        .get()
        .satisfies(
            stored ->
                assertThat(stored.readback())
                    .containsExactly(originalWorldCommit.canonicalBytes()));
    assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isTrue();
    assertThat(service.reconcile(binding.operationId())).contains(Settlement.FAILED_NONPUBLICATION);
    verify(gameDesignClient).read(any());
    verify(worldClient).read(any());
  }

  @Test
  void concurrentExactConsumerRetriesReturnTheSameOriginalSettlement() throws Exception {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding();
    tx(context, () -> context.repository().reserve(binding));
    tx(context, () -> context.repository().claimCommitOrder(binding));
    OwnerReadback worldAbort =
        ownerReadback(binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {51});
    OwnerReadback gameDesignAbort =
        ownerReadback(binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {52});
    CountDownLatch concurrentWorldReads = new CountDownLatch(2);
    GameDesignDraftTerminalReadClient gameDesignClient =
        mock(GameDesignDraftTerminalReadClient.class);
    WorldDraftTerminalReadClient worldClient = mock(WorldDraftTerminalReadClient.class);
    when(gameDesignClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction();
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(request, Optional.of(gameDesignAbort));
            });
    when(worldClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction();
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              concurrentWorldReads.countDown();
              await(concurrentWorldReads);
              return new WorldDraftTerminalReadEvidence(request, Optional.of(worldAbort));
            });
    var service =
        new AccountDraftTerminalReconciliationService(
            context.repository(),
            context.transaction().getTransactionManager(),
            gameDesignClient,
            worldClient,
            "firemud-test");

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> reconcileAttempt(service, binding.operationId()));
      var second = executor.submit(() -> reconcileAttempt(service, binding.operationId()));
      List<ReconciliationAttempt> attempts =
          List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
      assertThat(attempts)
          .allSatisfy(
              attempt -> {
                assertThat(attempt.failure()).isNull();
                assertThat(attempt.settlement()).isEqualTo(Settlement.FAILED_NONPUBLICATION);
              });
    }
    assertThat(tx(context, () -> context.repository().readOwnerResult(binding, Owner.WORLD)))
        .get()
        .satisfies(
            stored -> assertThat(stored.readback()).containsExactly(worldAbort.canonicalBytes()));
    assertThat(tx(context, () -> context.repository().readOwnerResult(binding, Owner.GAME_DESIGN)))
        .get()
        .satisfies(
            stored ->
                assertThat(stored.readback()).containsExactly(gameDesignAbort.canonicalBytes()));
  }

  @Test
  void concurrentConflictingOwnerEvidenceRejectsRetryAndPreservesWinningBytes() throws Exception {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding();
    tx(context, () -> context.repository().reserve(binding));
    tx(context, () -> context.repository().claimCommitOrder(binding));
    OwnerReadback gameDesignAbort =
        ownerReadback(binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {61});
    CountDownLatch concurrentWorldReads = new CountDownLatch(2);
    AtomicInteger resultSequence = new AtomicInteger();
    Map<UUID, OwnerReadback> returnedWorldResults = new ConcurrentHashMap<>();
    GameDesignDraftTerminalReadClient gameDesignClient =
        mock(GameDesignDraftTerminalReadClient.class);
    WorldDraftTerminalReadClient worldClient = mock(WorldDraftTerminalReadClient.class);
    when(gameDesignClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction();
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(request, Optional.of(gameDesignAbort));
            });
    when(worldClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction();
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              int resultNumber = resultSequence.incrementAndGet();
              OwnerReadback worldAbort =
                  ownerReadback(
                      binding,
                      Owner.WORLD,
                      Outcome.DEFINITIVELY_ABORTED,
                      new byte[] {(byte) (60 + resultNumber)});
              returnedWorldResults.put(request.readRequestId(), worldAbort);
              concurrentWorldReads.countDown();
              await(concurrentWorldReads);
              return new WorldDraftTerminalReadEvidence(request, Optional.of(worldAbort));
            });
    var service =
        new AccountDraftTerminalReconciliationService(
            context.repository(),
            context.transaction().getTransactionManager(),
            gameDesignClient,
            worldClient,
            "firemud-test");

    List<ReconciliationAttempt> attempts;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> reconcileAttempt(service, binding.operationId()));
      var second = executor.submit(() -> reconcileAttempt(service, binding.operationId()));
      attempts = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    }
    List<ReconciliationAttempt> successes =
        attempts.stream().filter(attempt -> attempt.failure() == null).toList();
    List<ReconciliationAttempt> rejected =
        attempts.stream().filter(attempt -> attempt.failure() != null).toList();
    assertThat(successes).hasSize(1);
    assertThat(successes.getFirst().settlement()).isEqualTo(Settlement.FAILED_NONPUBLICATION);
    assertThat(rejected).hasSize(1);
    assertThat(rejected.getFirst().failure())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Concurrent owner recovery changed immutable evidence");
    byte[] storedWorldReadback =
        tx(context, () -> context.repository().readOwnerResult(binding, Owner.WORLD))
            .orElseThrow()
            .readback();
    assertThat(returnedWorldResults.values())
        .anySatisfy(
            returned -> assertThat(returned.canonicalBytes()).containsExactly(storedWorldReadback));
    assertThat(tx(context, () -> context.repository().readOwnerResult(binding, Owner.GAME_DESIGN)))
        .get()
        .satisfies(
            stored ->
                assertThat(stored.readback()).containsExactly(gameDesignAbort.canonicalBytes()));
  }

  @Test
  void revokeBeforeCommitDeniesDelayedRequestsUntilBothDefinitiveAborts() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding();
    SourceChange change = change(binding);
    tx(context, () -> context.repository().reserve(binding));
    assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
    assertThat(tx(context, () -> context.repository().read(binding)).ordering())
        .isEqualTo(Ordering.REVOKE_ORDER);
    assertThatThrownBy(() -> tx(context, () -> context.repository().claimCommitOrder(binding)))
        .isInstanceOf(IllegalStateException.class);
    owner(context, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {1});
    assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isFalse();
    owner(context, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {2});
    assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isTrue();
    tx(
        context,
        () -> {
          context.repository().markSourceCommitted(change);
          return null;
        });
    assertThatThrownBy(() -> tx(context, () -> context.repository().claimCommitOrder(binding)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void worldCommittedAndGameDesignUnknownSurviveRestartAndBlockSourceUntilExactBothReadbacks() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding();
    tx(context, () -> context.repository().reserve(binding));
    var ordered = tx(context, () -> context.repository().claimCommitOrder(binding));
    SourceChange change = change(binding);
    assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
    owner(context, binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {4});
    Context restarted =
        new Context(
            context.dsl(),
            new DraftAuthorizationFenceRepository(context.dsl()),
            context.transaction());
    assertThat(tx(restarted, () -> restarted.repository().sourceMutationPermitted(change)))
        .isFalse();
    assertThat(tx(restarted, () -> restarted.repository().readSettlement(binding)))
        .isEqualTo(Settlement.PENDING);
    assertThat(
            tx(restarted, () -> restarted.repository().readOwnerResult(binding, Owner.GAME_DESIGN)))
        .isEmpty();
    assertSourceGuardBlocked(restarted, change);
    // Missing GD evidence models timeout/unavailable/absent owner row: no terminality is
    // synthesized.
    assertThat(tx(restarted, () -> restarted.repository().read(binding)).orderedAt())
        .isEqualTo(ordered.orderedAt());
    owner(restarted, binding, Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {5});
    assertThat(tx(restarted, () -> restarted.repository().readSettlement(binding)))
        .isEqualTo(Settlement.COMMITTED);
    assertThat(tx(restarted, () -> restarted.repository().sourceMutationPermitted(change)))
        .isTrue();
    tx(
        restarted,
        () -> {
          restarted.repository().markSourceCommitted(change);
          return null;
        });
    assertThat(tx(restarted, () -> restarted.repository().requestSourceChange(change))).isTrue();
    assertThat(tx(restarted, () -> restarted.repository().claimCommitOrder(binding)).orderedAt())
        .isEqualTo(ordered.orderedAt());
    owner(restarted, binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {4});
    assertThatThrownBy(
            () -> owner(restarted, binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {9}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void commitOrderWithBothDefinitiveAbortsReleasesOriginalWaitingIntentWithoutReopening() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding();
    tx(context, () -> context.repository().reserve(binding));
    var ordered = tx(context, () -> context.repository().claimCommitOrder(binding));
    SourceChange change = change(binding);
    assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
    owner(context, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {21});
    assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isFalse();
    owner(context, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {22});
    assertThat(tx(context, () -> context.repository().readSettlement(binding)))
        .isEqualTo(Settlement.FAILED_NONPUBLICATION);
    assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isTrue();
    var waiting = tx(context, () -> context.repository().readSourceChange(change));
    tx(
        context,
        () -> {
          context.repository().markSourceCommitted(change);
          return null;
        });
    var committed = tx(context, () -> context.repository().readSourceChange(change));
    assertThat(committed.status()).isEqualTo("SOURCE_COMMITTED");
    assertThat(committed.requestedAt()).isEqualTo(waiting.requestedAt());
    assertThat(committed.binding()).containsExactly(waiting.binding());
    tx(
        context,
        () -> {
          context.repository().markSourceCommitted(change);
          return null;
        });
    var replay = tx(context, () -> context.repository().readSourceChange(change));
    assertThat(replay.committedAt()).isEqualTo(committed.committedAt());
    assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isFalse();
    assertThat(tx(context, () -> context.repository().read(binding)).ordering())
        .isEqualTo(Ordering.COMMIT_ORDER);
    assertThat(tx(context, () -> context.repository().read(binding)).orderedAt())
        .isEqualTo(ordered.orderedAt());
    owner(context, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {21});
    assertThatThrownBy(
            () -> owner(context, binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {23}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bothMixedDirectionsFailNonpublicationAndReleaseOnlyAfterBothExactReadbacks() {
    for (Outcome worldOutcome : List.of(Outcome.COMMITTED, Outcome.DEFINITIVELY_ABORTED)) {
      Context context = context();
      DraftAuthorizationFenceBinding binding = binding();
      tx(context, () -> context.repository().reserve(binding));
      var ordered = tx(context, () -> context.repository().claimCommitOrder(binding));
      SourceChange change = change(binding);
      assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
      var waiting = tx(context, () -> context.repository().readSourceChange(change));
      owner(context, binding, Owner.WORLD, worldOutcome, new byte[] {1});
      var originalWorld =
          tx(context, () -> context.repository().readOwnerResult(binding, Owner.WORLD))
              .orElseThrow();
      assertThat(tx(context, () -> context.repository().readSettlement(binding)))
          .isEqualTo(Settlement.PENDING);
      assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isFalse();
      assertSourceGuardBlocked(context, change);
      Outcome gameDesignOutcome =
          worldOutcome == Outcome.COMMITTED ? Outcome.DEFINITIVELY_ABORTED : Outcome.COMMITTED;
      owner(context, binding, Owner.GAME_DESIGN, gameDesignOutcome, new byte[] {2});
      assertThat(tx(context, () -> context.repository().readSettlement(binding)))
          .isEqualTo(Settlement.FAILED_NONPUBLICATION);
      assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isTrue();
      assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isTrue();
      tx(
          context,
          () -> {
            context.repository().markSourceCommitted(change);
            return null;
          });
      var committed = tx(context, () -> context.repository().readSourceChange(change));
      assertThat(committed.status()).isEqualTo("SOURCE_COMMITTED");
      assertThat(committed.binding()).containsExactly(waiting.binding());
      assertThat(committed.requestedAt()).isEqualTo(waiting.requestedAt());
      assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isFalse();
      assertThat(tx(context, () -> context.repository().read(binding)).ordering())
          .isEqualTo(Ordering.COMMIT_ORDER);
      assertThat(tx(context, () -> context.repository().read(binding)).orderedAt())
          .isEqualTo(ordered.orderedAt());
      owner(context, binding, Owner.WORLD, worldOutcome, new byte[] {1});
      var replay =
          tx(context, () -> context.repository().readOwnerResult(binding, Owner.WORLD))
              .orElseThrow();
      assertThat(replay.outcome()).isEqualTo(originalWorld.outcome());
      assertThat(replay.readback()).containsExactly(originalWorld.readback());
      assertThat(replay.recordedAt()).isEqualTo(originalWorld.recordedAt());
      assertThatThrownBy(() -> owner(context, binding, Owner.WORLD, worldOutcome, new byte[] {9}))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> owner(context, binding, Owner.WORLD, gameDesignOutcome, new byte[] {1}))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void revokeOrderWithEitherMixedDirectionCannotAuthorizeSourceMutation() {
    for (Outcome worldOutcome : List.of(Outcome.COMMITTED, Outcome.DEFINITIVELY_ABORTED)) {
      Context context = context();
      DraftAuthorizationFenceBinding binding = binding();
      tx(context, () -> context.repository().reserve(binding));
      SourceChange change = change(binding);
      assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
      owner(context, binding, Owner.WORLD, worldOutcome, new byte[] {1});
      owner(
          context,
          binding,
          Owner.GAME_DESIGN,
          worldOutcome == Outcome.COMMITTED ? Outcome.DEFINITIVELY_ABORTED : Outcome.COMMITTED,
          new byte[] {2});
      assertThat(tx(context, () -> context.repository().read(binding)).ordering())
          .isEqualTo(Ordering.REVOKE_ORDER);
      assertThat(tx(context, () -> context.repository().readSettlement(binding)))
          .isEqualTo(Settlement.PENDING);
      assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
      assertThat(tx(context, () -> context.repository().sourceMutationPermitted(change))).isFalse();
      assertThatThrownBy(
              () ->
                  tx(
                      context,
                      () -> {
                        context.repository().markSourceCommitted(change);
                        return null;
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertSourceGuardBlocked(context, change);
    }
  }

  @Test
  void changedCompleteBindingsConflictWithoutRewritingOriginal() {
    Context context = context();
    DraftAuthorizationFenceBinding original = binding();
    var stored = tx(context, () -> context.repository().reserve(original));
    assertThat(tx(context, () -> context.repository().readSettlement(original)))
        .isEqualTo(Settlement.PENDING);
    for (int field = 0; field < 6; field++) {
      DraftAuthorizationFenceBinding changed = changed(original, field);
      assertThatThrownBy(() -> tx(context, () -> context.repository().reserve(changed)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var replay = tx(context, () -> context.repository().reserve(original));
    assertThat(replay.binding()).containsExactly(stored.binding());
    assertThat(replay.reservedAt()).isEqualTo(stored.reservedAt());
  }

  @Test
  void inconsistentOrMalformedCompleteBindingsFailBeforeReservationStorage() {
    Context context = context();
    DraftAuthorizationFenceBinding b = binding();
    for (int field = 0; field < 9; field++) {
      int changedField = field;
      byte[] bytes = field == 6 ? new byte[] {11} : b.gameDesignBinding();
      if (field == 8) {
        bytes =
            new String(bytes, StandardCharsets.UTF_8)
                .replace("\"revisionOrder\":\"0\"", "\"revisionOrder\":\"1\"")
                .getBytes(StandardCharsets.UTF_8);
      }
      byte[] gameDesign = bytes;
      byte[] input = field == 5 ? new byte[] {10} : gameDesign;
      String digest =
          field == 7
              ? DraftAuthorizationFenceBinding.digest(new byte[] {12})
              : DraftAuthorizationFenceBinding.digest(input);
      assertThatThrownBy(
              () ->
                  tx(
                      context,
                      () ->
                          context
                              .repository()
                              .reserve(
                                  new DraftAuthorizationFenceBinding(
                                      b.operationId(),
                                      changedField == 0 ? UUID.randomUUID() : b.requestId(),
                                      changedField == 1 ? UUID.randomUUID() : b.commitId(),
                                      b.fenceId(),
                                      b.actorAccountId(),
                                      changedField == 2 ? UUID.randomUUID() : b.tenantId(),
                                      changedField == 3 ? UUID.randomUUID() : b.versionId(),
                                      changedField == 4 ? "different/base" : b.baseCommitId(),
                                      b.expectedDraftEpoch(),
                                      gameDesign,
                                      input,
                                      digest,
                                      b.sources()))))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_fences")))
          .isZero();
      assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_sources")))
          .isZero();
    }
    var stored = tx(context, () -> context.repository().reserve(b));
    assertThat(tx(context, () -> context.repository().reserve(b)).binding())
        .containsExactly(stored.binding());
  }

  @Test
  void rollbackPreservesReservationAndIntentTogetherAndDisjointSourcesDoNotConflict() {
    Context context = context();
    DraftAuthorizationFenceBinding original = binding();
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> {
                      context.repository().reserve(original);
                      context.repository().requestSourceChange(change(original));
                      throw new IllegalStateException("injected rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> tx(context, () -> context.repository().read(original)))
        .isInstanceOf(IllegalArgumentException.class);
    tx(context, () -> context.repository().reserve(original));
    tx(context, () -> context.repository().requestSourceChange(change(original)));
    DraftAuthorizationFenceBinding independent = binding();
    tx(context, () -> context.repository().reserve(independent));
    assertThat(tx(context, () -> context.repository().claimCommitOrder(independent)).ordering())
        .isEqualTo(Ordering.COMMIT_ORDER);
  }

  @Test
  void distinctPendingChangesCannotRetainTwoOldCapturesForTheSameSource() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding();
    SourceChange first = change(binding);
    SourceChange second = change(binding);
    tx(context, () -> context.repository().reserve(binding));
    tx(context, () -> context.repository().claimCommitOrder(binding));
    assertThat(tx(context, () -> context.repository().requestSourceChange(first))).isFalse();
    var original = tx(context, () -> context.repository().readSourceChange(first));

    assertThatThrownBy(() -> tx(context, () -> context.repository().requestSourceChange(second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already pending");
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isEqualTo(1);
    assertThat(tx(context, () -> context.repository().requestSourceChange(first))).isFalse();
    var exactRetry = tx(context, () -> context.repository().readSourceChange(first));
    assertThat(exactRetry.binding()).containsExactly(original.binding());
    assertThat(exactRetry.requestedAt()).isEqualTo(original.requestedAt());

    // This storage fixture stipulates definitive owner evidence; it does not authenticate it.
    owner(context, binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {1});
    owner(context, binding, Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {2});
    tx(
        context,
        () -> {
          context.repository().markSourceCommitted(first);
          return null;
        });
    assertThat(tx(context, () -> context.repository().requestSourceChange(second))).isTrue();
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isEqualTo(2);
  }

  @Test
  void concurrentDistinctSourceClaimsAdmitOnlyOnePendingCaptureWithoutBlockingDisjointSources()
      throws Exception {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding();
    SourceChange first = change(binding);
    SourceChange second = change(binding);
    tx(context, () -> context.repository().reserve(binding));
    tx(context, () -> context.repository().claimCommitOrder(binding));
    CountDownLatch firstClaimed = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstTask =
          executor.submit(
              () ->
                  tx(
                      context,
                      () -> {
                        boolean permitted = context.repository().requestSourceChange(first);
                        firstClaimed.countDown();
                        await(secondStarted);
                        return permitted;
                      }));
      var secondTask =
          executor.submit(
              () -> {
                await(firstClaimed);
                secondStarted.countDown();
                assertThatThrownBy(
                        () -> tx(context, () -> context.repository().requestSourceChange(second)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already pending");
              });
      assertThat(firstTask.get(15, TimeUnit.SECONDS)).isFalse();
      secondTask.get(15, TimeUnit.SECONDS);
    }
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isEqualTo(1);
    assertThat(tx(context, () -> context.repository().requestSourceChange(first))).isFalse();
    SourceChange disjoint = change(binding());
    assertThat(tx(context, () -> context.repository().requestSourceChange(disjoint))).isTrue();
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isEqualTo(2);
  }

  @Test
  void concurrentCommitOrderAndRevocationChooseExactlyOneDurableOrdering() throws Exception {
    for (boolean commitWins : List.of(true, false)) {
      Context context = context();
      DraftAuthorizationFenceBinding binding = binding();
      SourceChange change = change(binding);
      tx(context, () -> context.repository().reserve(binding));
      CountDownLatch firstOrdered = new CountDownLatch(1);
      CountDownLatch secondStarted = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var first =
            executor.submit(
                () ->
                    tx(
                        context,
                        () -> {
                          if (commitWins) {
                            context.repository().claimCommitOrder(binding);
                          } else {
                            context.repository().requestSourceChange(change);
                          }
                          firstOrdered.countDown();
                          await(secondStarted);
                          return null;
                        }));
        var second =
            executor.submit(
                () -> {
                  await(firstOrdered);
                  secondStarted.countDown();
                  if (commitWins) {
                    return tx(context, () -> context.repository().requestSourceChange(change));
                  }
                  assertThatThrownBy(
                          () -> tx(context, () -> context.repository().claimCommitOrder(binding)))
                      .isInstanceOf(IllegalStateException.class);
                  return false;
                });
        first.get(15, TimeUnit.SECONDS);
        assertThat(second.get(15, TimeUnit.SECONDS)).isFalse();
      }
      assertThat(tx(context, () -> context.repository().read(binding)).ordering())
          .isEqualTo(commitWins ? Ordering.COMMIT_ORDER : Ordering.REVOKE_ORDER);
    }
  }

  @Test
  void originalBindingSurvivesRestartAndAbsenceRemainsUnknownUnderOwnerTransaction() {
    Context context = context();
    var binding = binding();
    tx(context, () -> context.repository().reserve(binding));
    Context restarted =
        new Context(
            context.dsl(),
            new DraftAuthorizationFenceRepository(context.dsl()),
            context.transaction());
    var restored =
        tx(restarted, () -> restarted.repository().readOriginalBinding(binding.operationId()))
            .orElseThrow();
    assertThat(restored.canonicalBytes()).containsExactly(binding.canonicalBytes());
    assertThat(tx(restarted, () -> restarted.repository().readOriginalBinding(UUID.randomUUID())))
        .isEmpty();
    assertThatThrownBy(() -> restarted.repository().readOriginalBinding(binding.operationId()))
        .isInstanceOf(IllegalStateException.class);
    restarted.transaction().setReadOnly(true);
    try {
      assertThatThrownBy(
              () ->
                  tx(
                      restarted,
                      () -> restarted.repository().readOriginalBinding(binding.operationId())))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      restarted.transaction().setReadOnly(false);
    }
    assertThatThrownBy(
            () -> tx(restarted, () -> restarted.repository().readUnresolvedOperations(null, 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> tx(restarted, () -> restarted.repository().readUnresolvedOperations(null, 101)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void discoveryPagesOriginalPendingOperationsAndExcludesEverySettledVector() {
    Context context = context();
    var reserved = binding();
    var committedUnknown = binding();
    var revokedUnknown = binding();
    var revokedContradiction = binding();
    var committed = binding();
    var mixed = binding();
    var aborted = binding();
    var revokedAborted = binding();
    List<DraftAuthorizationFenceBinding> all =
        List.of(
            reserved,
            committedUnknown,
            revokedUnknown,
            revokedContradiction,
            committed,
            mixed,
            aborted,
            revokedAborted);
    // One timestamp tests the UUID tie-breaker rather than relying on clock resolution.
    tx(
        context,
        () -> {
          for (var b : all) context.repository().reserve(b);
          return null;
        });
    for (var b : List.of(committedUnknown, committed, mixed, aborted)) {
      tx(context, () -> context.repository().claimCommitOrder(b));
    }
    for (var b : List.of(revokedUnknown, revokedContradiction, revokedAborted)) {
      tx(context, () -> context.repository().requestSourceChange(change(b)));
    }
    owner(context, committedUnknown, Owner.WORLD, Outcome.COMMITTED, new byte[] {1});
    owner(context, revokedContradiction, Owner.WORLD, Outcome.COMMITTED, new byte[] {1});
    owner(
        context,
        revokedContradiction,
        Owner.GAME_DESIGN,
        Outcome.DEFINITIVELY_ABORTED,
        new byte[] {2});
    for (var b : List.of(committed, mixed, aborted, revokedAborted)) {
      owner(
          context,
          b,
          Owner.WORLD,
          b == committed || b == mixed ? Outcome.COMMITTED : Outcome.DEFINITIVELY_ABORTED,
          new byte[] {1});
      owner(
          context,
          b,
          Owner.GAME_DESIGN,
          b == committed ? Outcome.COMMITTED : Outcome.DEFINITIVELY_ABORTED,
          new byte[] {2});
    }
    var expected =
        List.of(reserved, committedUnknown, revokedUnknown, revokedContradiction).stream()
            .sorted(java.util.Comparator.comparing(b -> b.operationId().toString()))
            .toList();
    var first = tx(context, () -> context.repository().readUnresolvedOperations(null, 2));
    var second =
        tx(
            context,
            () -> context.repository().readUnresolvedOperations(first.getLast().cursor(), 2));
    assertThat(first).hasSize(2);
    assertThat(second).hasSize(2);
    var combined = java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    assertThat(combined.stream().map(item -> item.binding().operationId()).toList())
        .containsExactlyElementsOf(
            expected.stream().map(DraftAuthorizationFenceBinding::operationId).toList());
    for (int i = 0; i < expected.size(); i++) {
      assertThat(combined.get(i).binding().canonicalBytes())
          .containsExactly(expected.get(i).canonicalBytes());
    }
    assertThat(
            tx(
                context,
                () -> context.repository().readUnresolvedOperations(second.getLast().cursor(), 2)))
        .isEmpty();
    owner(context, committedUnknown, Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {2});
    assertThat(tx(context, () -> context.repository().readUnresolvedOperations(null, 100)))
        .extracting(item -> item.binding().operationId())
        .doesNotContain(committedUnknown.operationId());
  }

  @Test
  void originalRecoveryRejectsMalformedBytesAndEveryConflictingIdentityColumn() {
    for (int field = 0; field < 5; field++) {
      Context context = context();
      var b = binding();
      UUID operation = field == 0 ? UUID.randomUUID() : b.operationId();
      UUID request = field == 1 ? UUID.randomUUID() : b.requestId();
      UUID commit = field == 2 ? UUID.randomUUID() : b.commitId();
      UUID fence = field == 3 ? UUID.randomUUID() : b.fenceId();
      byte[] stored = field == 4 ? new byte[] {1} : b.canonicalBytes();
      // The production guard permits first insertion of opaque bytes; recovery must verify them.
      tx(
          context,
          () ->
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_draft_authorization_fences"
                          + " (operation_id, request_id, commit_id, fence_id, binding, ordering)"
                          + " VALUES (?, ?, ?, ?, ?, 'RESERVED')",
                      operation,
                      request,
                      commit,
                      fence,
                      stored));
      assertThatThrownBy(
              () -> tx(context, () -> context.repository().readOriginalBinding(operation)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> tx(context, () -> context.repository().readUnresolvedOperations(null, 10)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void ownerRecoveryRejectsReadbackThatContradictsItsOutcomeColumn() {
    Context context = context();
    var b = binding();
    tx(context, () -> context.repository().reserve(b));
    tx(context, () -> context.repository().claimCommitOrder(b));
    var readback =
        new OwnerReadback(
            Owner.WORLD,
            Outcome.COMMITTED,
            b.operationId(),
            b.commitId(),
            b.fenceId(),
            b.inputDigest(),
            b.canonicalBytes(),
            new byte[] {1});
    tx(
        context,
        () ->
            context
                .dsl()
                .execute(
                    "INSERT INTO account_draft_authorization_owner_readbacks"
                        + " (operation_id, owner, outcome, readback) VALUES (?, 'WORLD', 'DEFINITIVELY_ABORTED', ?)",
                    b.operationId(),
                    readback.canonicalBytes()));
    assertThatThrownBy(
            () -> tx(context, () -> context.repository().readOwnerResult(b, Owner.WORLD)))
        .isInstanceOf(IllegalStateException.class);
  }

  private void assertSourceGuardBlocked(Context context, SourceChange change) {
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_draft_authorization_source_changes"
                                    + " SET status = 'SOURCE_COMMITTED', committed_at = CURRENT_TIMESTAMP"
                                    + " WHERE change_id = ?",
                                change.changeId())))
        .isInstanceOf(DataAccessException.class);
  }

  private Context context() {
    String schema = "draft_fence_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(postgres.getJdbcUrl());
    source.setUsername(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(dsl, new DraftAuthorizationFenceRepository(dsl), transaction);
  }

  private DraftAuthorizationFenceBinding binding() {
    UUID account = UUID.randomUUID();
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            account.toString(),
            "3",
            "5",
            "stream/" + account,
            "2",
            new byte[] {7});
    DraftCommitBinding complete =
        completeBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "opaque/base:9007199254740993",
            "world-payload",
            UUID.randomUUID());
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        account,
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "2",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(source));
  }

  private DraftAuthorizationFenceBinding changed(DraftAuthorizationFenceBinding b, int field) {
    SourceEvidence prior = b.sources().getFirst();
    SourceEvidence changedSource =
        new SourceEvidence(
            prior.kind(),
            prior.scopeId(),
            "4",
            prior.sourceVersion(),
            prior.checkpointStream(),
            prior.checkpointSequence(),
            prior.evidence());
    DraftCommitBinding priorComplete =
        DraftCommitBinding.fromStored(
            new String(b.gameDesignBinding(), StandardCharsets.UTF_8), b.inputDigest());
    DraftCommitBinding complete =
        completeBinding(
            field == 1 ? UUID.randomUUID() : b.tenantId(),
            field == 2 ? UUID.randomUUID() : b.versionId(),
            b.requestId(),
            b.commitId(),
            b.baseCommitId(),
            field == 4 ? "changed-world-payload" : priorComplete.revisions().getFirst().payload(),
            field == 5 ? UUID.randomUUID() : priorComplete.revisions().getFirst().revisionId());
    return new DraftAuthorizationFenceBinding(
        b.operationId(),
        b.requestId(),
        b.commitId(),
        b.fenceId(),
        field == 0 ? UUID.randomUUID() : b.actorAccountId(),
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        b.baseCommitId(),
        b.expectedDraftEpoch(),
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        field == 3 ? List.of(changedSource) : b.sources());
  }

  private DraftCommitBinding completeBinding(
      UUID tenant,
      UUID version,
      UUID request,
      UUID commit,
      String base,
      String payload,
      UUID revision) {
    return DraftCommitBinding.create(
        new TargetProof(tenant, version, 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW"),
        request,
        commit,
        base,
        List.of(
            new RevisionPayload("0", revision, DraftCommitBinding.Owner.WORLD_MANAGEMENT, payload)),
        List.of(
            new AffectedUnit(
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                "region",
                "region-1",
                "aggregate",
                "region-1",
                "9007199254740999")));
  }

  private SourceChange change(DraftAuthorizationFenceBinding binding) {
    return new SourceChange(UUID.randomUUID(), binding.sources(), new byte[] {12});
  }

  private void owner(
      Context context,
      DraftAuthorizationFenceBinding b,
      Owner owner,
      Outcome outcome,
      byte[] bytes) {
    OwnerReadback readback = ownerReadback(b, owner, outcome, bytes);
    tx(
        context,
        () -> {
          context.repository().recordOwnerReadback(b, readback);
          return null;
        });
  }

  private static OwnerReadback ownerReadback(
      DraftAuthorizationFenceBinding binding, Owner owner, Outcome outcome, byte[] result) {
    return new OwnerReadback(
        owner,
        outcome,
        binding.operationId(),
        binding.commitId(),
        binding.fenceId(),
        binding.inputDigest(),
        binding.canonicalBytes(),
        result);
  }

  private static WorldDraftTerminalReadEvidence worldEvidence(
      WorldDraftTerminalReadEvidence.Request request, OwnerReadback readback) {
    if (readback.outcome() != Outcome.COMMITTED) {
      return new WorldDraftTerminalReadEvidence(request, Optional.of(readback));
    }
    WorldDraftTerminalReadEvidence response = mock(WorldDraftTerminalReadEvidence.class);
    when(response.request()).thenReturn(request);
    when(response.ownerReadback()).thenReturn(Optional.of(readback));
    return response;
  }

  private static void assertOutsideOwnerTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static ReconciliationAttempt reconcileAttempt(
      AccountDraftTerminalReconciliationService service, UUID operationId) {
    try {
      return new ReconciliationAttempt(service.reconcile(operationId).orElseThrow(), null);
    } catch (RuntimeException failure) {
      return new ReconciliationAttempt(null, failure);
    }
  }

  private static <T> T tx(Context context, Supplier<T> work) {
    return context.transaction().execute(status -> work.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Ordering fixture timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private record Context(
      DSLContext dsl,
      DraftAuthorizationFenceRepository repository,
      TransactionTemplate transaction) {}

  private record ReconciliationAttempt(Settlement settlement, RuntimeException failure) {}
}
