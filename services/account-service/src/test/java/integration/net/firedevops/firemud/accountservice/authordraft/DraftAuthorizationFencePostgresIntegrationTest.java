package integration.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChangeAbortReason;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Component fixtures stipulate authenticated current-source capture and authenticated definitive
 * owner readback. They do not implement or prove those absent producers, RPC or source-writer
 * gates.
 *
 * <p>Actual Account storage creation establishes fixture identity only. Supplied source counters
 * and payloads remain stipulated capture, never current authorization evidence.
 */
@Testcontainers(disabledWithoutDocker = true)
class DraftAuthorizationFencePostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void revokeBeforeCommitDeniesDelayedRequestsUntilBothDefinitiveAborts() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding(context);
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
  void abortedSourceChangeRetainsExactTerminalReasonAndCannotReopenOrBecomeMalformed() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding(context);
    SourceChange change = change(binding);
    tx(context, () -> context.repository().reserve(binding));
    assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
    assertThat(tx(context, () -> context.repository().sourceAbortPermitted(change))).isFalse();
    owner(context, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {31});
    owner(context, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {32});
    assertThat(tx(context, () -> context.repository().sourceAbortPermitted(change))).isTrue();

    tx(
        context,
        () -> {
          context.repository().markSourceAborted(change, SourceChangeAbortReason.EXPIRED);
          return null;
        });
    var aborted = tx(context, () -> context.repository().readSourceChange(change));
    assertThat(aborted.status()).isEqualTo("SOURCE_ABORTED");
    assertThat(aborted.committedAt()).isNull();
    assertThat(aborted.abortedAt()).isNotNull();
    assertThat(aborted.abortReason()).isEqualTo(SourceChangeAbortReason.EXPIRED);

    tx(
        context,
        () -> {
          context.repository().markSourceAborted(change, SourceChangeAbortReason.EXPIRED);
          return null;
        });
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> {
                      context
                          .repository()
                          .markSourceAborted(change, SourceChangeAbortReason.DEFINITIVE_ABORT);
                      return null;
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_draft_authorization_source_changes"
                                    + " SET status = 'WAITING', aborted_at = NULL, abort_reason = NULL"
                                    + " WHERE change_id = ?",
                                change.changeId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "INSERT INTO account_draft_authorization_source_changes"
                                    + " (change_id, binding, status, aborted_at, abort_reason)"
                                    + " VALUES (?, ?, 'SOURCE_ABORTED', NULL, NULL)",
                                UUID.randomUUID(),
                                new byte[] {1})))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void worldCommittedAndGameDesignUnknownSurviveRestartAndBlockSourceUntilExactBothReadbacks() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding(context);
    tx(context, () -> context.repository().reserve(binding));
    var ordered = tx(context, () -> context.repository().claimCommitOrder(binding));
    SourceChange change = change(binding);
    assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isFalse();
    owner(context, binding, Owner.WORLD, Outcome.COMMITTED, new byte[] {4});
    Context restarted =
        new Context(
            context.dsl(),
            context.dataSource(),
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
    DraftAuthorizationFenceBinding binding = binding(context);
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
      DraftAuthorizationFenceBinding binding = binding(context);
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
      DraftAuthorizationFenceBinding binding = binding(context);
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
    DraftAuthorizationFenceBinding original = binding(context);
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
    DraftAuthorizationFenceBinding b = binding(context);
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
    DraftAuthorizationFenceBinding original = binding(context);
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
    DraftAuthorizationFenceBinding independent = binding(context);
    tx(context, () -> context.repository().reserve(independent));
    assertThat(tx(context, () -> context.repository().claimCommitOrder(independent)).ordering())
        .isEqualTo(Ordering.COMMIT_ORDER);
  }

  @Test
  void distinctPendingChangesCannotRetainTwoOldCapturesForTheSameSource() {
    Context context = context();
    DraftAuthorizationFenceBinding binding = binding(context);
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
    DraftAuthorizationFenceBinding binding = binding(context);
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
    SourceChange disjoint = change(binding(context));
    assertThat(tx(context, () -> context.repository().requestSourceChange(disjoint))).isTrue();
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isEqualTo(2);
  }

  @Test
  void concurrentCommitOrderAndRevocationChooseExactlyOneDurableOrdering() throws Exception {
    for (boolean commitWins : List.of(true, false)) {
      Context context = context();
      DraftAuthorizationFenceBinding binding = binding(context);
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

  @Test
  void accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple() throws Exception {
    for (SourceKind kind : List.of(SourceKind.ACCOUNT, SourceKind.GLOBAL_ROLES)) {
      for (int isolation :
          List.of(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
        Context context = context();
        UUID account = binding(context).actorAccountId();
        UUID other = binding(context).actorAccountId();
        var before = context.dsl().fetch("SELECT * FROM accounts ORDER BY id");
        var authority = context.dsl().fetch("SELECT * FROM account_authority_generations");
        var fences = context.dsl().fetch("SELECT * FROM account_authority_issuance_fences");
        var sources = context.dsl().fetch("SELECT * FROM account_authority_source_records");
        var roles = context.dsl().fetch("SELECT * FROM account_global_role_sources");
        var events = context.dsl().fetch("SELECT * FROM account_authority_outbox_events");
        String originalVersion = accountVersion(context, account);
        String otherVersion = accountVersion(context, other);
        try (Connection stale = context.dataSource().getConnection()) {
          stale.setAutoCommit(false);
          stale.setTransactionIsolation(isolation);
          try (var statement = stale.createStatement()) {
            statement.executeQuery("SELECT id FROM accounts").close();
            var absent =
                statement.executeQuery(
                    "SELECT count(*) FROM account_draft_authorization_source_changes");
            assertThat(absent.next()).isTrue();
            assertThat(absent.getInt(1)).isZero();
            absent.close();
          }
          SourceChange change =
              new SourceChange(UUID.randomUUID(), List.of(source(kind, account)), new byte[] {12});
          assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isTrue();
          assertThat(accountVersion(context, account)).isNotEqualTo(originalVersion);
          assertThat(accountVersion(context, other)).isEqualTo(otherVersion);
          assertThat(context.dsl().fetch("SELECT * FROM accounts ORDER BY id")).isEqualTo(before);
          assertThat(context.dsl().fetch("SELECT * FROM account_authority_generations"))
              .isEqualTo(authority);
          assertThat(context.dsl().fetch("SELECT * FROM account_authority_issuance_fences"))
              .isEqualTo(fences);
          assertThat(context.dsl().fetch("SELECT * FROM account_authority_source_records"))
              .isEqualTo(sources);
          assertThat(context.dsl().fetch("SELECT * FROM account_global_role_sources"))
              .isEqualTo(roles);
          assertThat(context.dsl().fetch("SELECT * FROM account_authority_outbox_events"))
              .isEqualTo(events);
          try (var lock =
              stale.prepareStatement("SELECT id FROM accounts WHERE account_uuid = ? FOR SHARE")) {
            lock.setObject(1, account);
            assertThatThrownBy(lock::executeQuery)
                .isInstanceOf(SQLException.class)
                .extracting("SQLState")
                .isEqualTo("40001");
          }
          stale.rollback();
        }
      }
    }
  }

  @Test
  void producerLocksDeduplicatedAccountsInUuidOrderBeforeAnySourceLocks() throws Exception {
    Context context = context();
    List<UUID> accounts =
        List.of(binding(context).actorAccountId(), binding(context).actorAccountId()).stream()
            .sorted(java.util.Comparator.comparing(UUID::toString))
            .toList();
    UUID first = accounts.getFirst();
    UUID last = accounts.getLast();
    SourceChange change =
        new SourceChange(
            UUID.randomUUID(),
            List.of(
                source(SourceKind.ACCOUNT, last),
                source(SourceKind.GLOBAL_ROLES, first),
                source(SourceKind.GLOBAL_ROLES, last)),
            new byte[] {12});
    tx(
        context,
        () -> {
          for (SourceEvidence source : change.sources()) {
            context
                .dsl()
                .execute(
                    "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)",
                    source.key());
          }
          return null;
        });
    CountDownLatch started = new CountDownLatch(1);
    AtomicInteger waitingPid = new AtomicInteger();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var claimed =
          tx(
              context,
              () -> {
                context
                    .dsl()
                    .fetchOne("SELECT id FROM accounts WHERE account_uuid = ? FOR UPDATE", first);
                int blockerPid =
                    Objects.requireNonNull(
                            context.dsl().fetchOne("SELECT pg_backend_pid() AS pid"),
                            "Expected blocking PostgreSQL backend")
                        .get("pid", Integer.class);
                var task =
                    executor.submit(
                        () ->
                            tx(
                                context,
                                () -> {
                                  context.dsl().execute("SET LOCAL lock_timeout = '10s'");
                                  waitingPid.set(
                                      Objects.requireNonNull(
                                              context
                                                  .dsl()
                                                  .fetchOne("SELECT pg_backend_pid() AS pid"),
                                              "Expected waiting PostgreSQL backend")
                                          .get("pid", Integer.class));
                                  started.countDown();
                                  return context.repository().requestSourceChange(change);
                                }));
                await(started);
                awaitBlocked(context, waitingPid.get(), blockerPid);
                context
                    .dsl()
                    .fetchOne(
                        "SELECT id FROM accounts WHERE account_uuid = ? FOR UPDATE NOWAIT", last);
                for (SourceEvidence source : change.sources()) {
                  context
                      .dsl()
                      .fetchOne(
                          "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ? FOR UPDATE NOWAIT",
                          source.key());
                }
                return task;
              });
      assertThat(claimed.get(15, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(tx(context, () -> context.repository().requestSourceChange(change))).isTrue();
  }

  @Test
  void scopeInsertVersionsAccountBeforeTheExistingParentLockingTrigger() throws Exception {
    Context context = context();
    UUID account = binding(context).actorAccountId();
    UUID change = UUID.randomUUID();
    String key = "ACCOUNT:" + account;
    tx(
        context,
        () -> {
          context
              .dsl()
              .execute(
                  "INSERT INTO account_draft_authorization_source_changes (change_id, binding, status) VALUES (?, ?, 'WAITING')",
                  change,
                  new byte[] {12});
          context
              .dsl()
              .execute(
                  "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)",
                  key);
          return null;
        });
    CountDownLatch started = new CountDownLatch(1);
    AtomicInteger waitingPid = new AtomicInteger();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var inserted =
          tx(
              context,
              () -> {
                context
                    .dsl()
                    .fetchOne(
                        "SELECT change_id FROM account_draft_authorization_source_changes WHERE change_id = ? FOR UPDATE",
                        change);
                int blockerPid =
                    Objects.requireNonNull(
                            context.dsl().fetchOne("SELECT pg_backend_pid() AS pid"),
                            "Expected blocking PostgreSQL backend")
                        .get("pid", Integer.class);
                var task =
                    executor.submit(
                        () ->
                            tx(
                                context,
                                () -> {
                                  context.dsl().execute("SET LOCAL lock_timeout = '10s'");
                                  waitingPid.set(
                                      Objects.requireNonNull(
                                              context
                                                  .dsl()
                                                  .fetchOne("SELECT pg_backend_pid() AS pid"),
                                              "Expected waiting PostgreSQL backend")
                                          .get("pid", Integer.class));
                                  started.countDown();
                                  return context
                                      .dsl()
                                      .execute(
                                          "INSERT INTO account_draft_authorization_changed_scopes (change_id, source_key) VALUES (?, ?)",
                                          change,
                                          key);
                                }));
                await(started);
                awaitBlocked(context, waitingPid.get(), blockerPid);
                try (Connection observer = context.dataSource().getConnection();
                    var lock =
                        observer.prepareStatement(
                            "SELECT id FROM accounts WHERE account_uuid = ? FOR UPDATE NOWAIT")) {
                  lock.setObject(1, account);
                  assertThatThrownBy(lock::executeQuery)
                      .isInstanceOf(SQLException.class)
                      .extracting("SQLState")
                      .isEqualTo("55P03");
                } catch (SQLException failure) {
                  throw new IllegalStateException(failure);
                }
                return task;
              });
      assertThat(inserted.get(15, TimeUnit.SECONDS)).isEqualTo(1);
    }
  }

  @Test
  void applicableMissingOrMalformedAccountScopesDenyWithoutCreatingAccountOrIntent() {
    Context context = context();
    UUID absent = UUID.randomUUID();
    SourceChange missing =
        new SourceChange(
            UUID.randomUUID(), List.of(source(SourceKind.ACCOUNT, absent)), new byte[] {12});
    assertThatThrownBy(() -> tx(context, () -> context.repository().requestSourceChange(missing)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("persisted Account");
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_source_locks")))
        .isZero();
    for (String key :
        List.of(
            "ACCOUNT:" + absent,
            "GLOBAL_ROLES:" + absent,
            "ACCOUNT:42",
            "GLOBAL_ROLES:00000000-0000-0000-0000-000000000000",
            "ACCOUNT")) {
      assertThatThrownBy(() -> insertScope(context, key)).isInstanceOf(DataAccessException.class);
    }
    assertThat(context.dsl().fetchCount(DSL.table("accounts"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isZero();
    insertScope(context, "TENANT:" + absent);
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_changed_scopes")))
        .isEqualTo(1);
  }

  private void insertScope(Context context, String key) {
    tx(
        context,
        () -> {
          UUID change = UUID.randomUUID();
          context
              .dsl()
              .execute(
                  "INSERT INTO account_draft_authorization_source_changes (change_id, binding, status) VALUES (?, ?, 'WAITING')",
                  change,
                  new byte[] {12});
          context
              .dsl()
              .execute(
                  "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)",
                  key);
          context
              .dsl()
              .execute(
                  "INSERT INTO account_draft_authorization_changed_scopes (change_id, source_key) VALUES (?, ?)",
                  change,
                  key);
          return null;
        });
  }

  private static SourceEvidence source(SourceKind kind, UUID account) {
    // Stipulated capture is retained exactly; its counters/payload never establish authority.
    return new SourceEvidence(
        kind, account.toString(), "3", "5", "stream/" + account, "2", new byte[] {7});
  }

  private static String accountVersion(Context context, UUID account) {
    return Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT xmin::TEXT AS version FROM accounts WHERE account_uuid = ?", account),
            "Expected persisted Account tuple version")
        .get("version", String.class);
  }

  private static void awaitBlocked(Context context, int waitingPid, int blockerPid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          Objects.requireNonNull(
                  context
                      .dsl()
                      .fetchOne(
                          "SELECT ? = ANY(pg_blocking_pids(?)) AS blocked", blockerPid, waitingPid),
                  "Expected PostgreSQL blocking-state readback")
              .get("blocked", Boolean.class))) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("Source producer did not block on the first Account lock");
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
    return new Context(dsl, source, new DraftAuthorizationFenceRepository(dsl), transaction);
  }

  private DraftAuthorizationFenceBinding binding(Context context) {
    UUID account =
        tx(
            context,
            () -> {
              Account row = new Account();
              String suffix = UUID.randomUUID().toString();
              row.setUsername("draft-" + suffix);
              row.setEmail(suffix + "@example.test");
              row.setPasswordHash("synthetic-verifier");
              return new AccountRepository(context.dsl()).save(row).getAccountUuid();
            });
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
    OwnerReadback readback =
        new OwnerReadback(
            owner,
            outcome,
            b.operationId(),
            b.commitId(),
            b.fenceId(),
            b.inputDigest(),
            b.canonicalBytes(),
            bytes);
    tx(
        context,
        () -> {
          context.repository().recordOwnerReadback(b, readback);
          return null;
        });
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
      DriverManagerDataSource dataSource,
      DraftAuthorizationFenceRepository repository,
      TransactionTemplate transaction) {}
}
