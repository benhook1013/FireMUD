package integration.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChangeAbortReason;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.test.TestContainerImages;
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
 * Real PostgreSQL raw-source and lock-order definitions. Complete external authenticated capture
 * and terminal owner RPC readbacks are explicitly test-only stipulated fixtures, NOT production
 * caller/source/World proof. These tests do not claim genuine issuance or COMMIT_ORDER provenance.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountControlUiSourceGuardPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void ownerSourceGuardPrerequisiteSchemaIsInstalledBeforeGuardBackfill() {
    Context c = context();
    for (String relation :
        List.of(
            "account_draft_authorization_source_locks",
            "account_draft_authorization_fences",
            "account_draft_authorization_sources",
            "account_draft_authorization_owner_readbacks",
            "account_draft_authorization_source_changes",
            "account_draft_authorization_changed_scopes",
            "account_tenant_creation_bootstrap_operations",
            "account_individual_creator_party_sources",
            "account_tenant_creator_party_history",
            "account_fresh_creator_party_association_operations",
            "account_hosted_terms_scopes",
            "account_hosted_terms_catalog_versions",
            "account_hosted_terms_publication_operations",
            "account_individual_hosted_terms_acceptances",
            "account_hosted_terms_environment_binding_heads",
            "account_hosted_terms_environment_bindings",
            "account_hosted_terms_environment_binding_publications")) {
      assertThat(c.dsl.fetchOne("SELECT to_regclass(?)::TEXT", relation).get(0, String.class))
          .as("migrated relation %s", relation)
          .isNotNull();
    }
    for (String function :
        List.of(
            "account_draft_authorization_immutable_guard()",
            "account_draft_authorization_is_settled(uuid)",
            "account_publication_authorization_source_sort_key(text)",
            "account_control_ui_source_keys(text,jsonb)",
            "account_control_ui_required_source_guard()")) {
      assertThat(c.dsl.fetchOne("SELECT to_regprocedure(?)::TEXT", function).get(0, String.class))
          .as("migrated function %s", function)
          .isNotNull();
    }
  }

  @Test
  void rawProtectedMutationCannotBypassOriginalFenceButNoOpAndUnrelatedAccountRemainWritable() {
    Context c = context();
    var binding = binding(c);
    c.tx(
        () -> {
          c.fences.reserve(binding);
          c.fences.claimCommitOrder(binding);
          return null;
        });
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE accounts SET password_hash = ? WHERE account_uuid = ?",
                            "test-only-bypass",
                            binding.actorAccountId())))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("every original owner settles");
    c.tx(
        () ->
            c.dsl.execute(
                "UPDATE accounts SET id = id WHERE account_uuid = ?", binding.actorAccountId()));
    // Username/email are not captured Account security/role fields. Existing protections still
    // apply.
    c.tx(
        () ->
            c.dsl.execute(
                "UPDATE accounts SET username = ? WHERE account_uuid = ?",
                "test-noop-metadata",
                binding.actorAccountId()));
    UUID unrelated = account(c);
    changePassword(c, unrelated, "test-only-unrelated-account-change");
    var ordering =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT ordering FROM account_draft_authorization_fences WHERE operation_id = ?",
                binding.operationId()),
            "Original authorization fence readback required");
    assertThat(ordering.get("ordering", String.class)).isEqualTo("COMMIT_ORDER");
  }

  @Test
  void exactAllOwnerSettlementUnblocksButOneOrMixedReadbackDoesNot() {
    for (Outcome outcome : Outcome.values()) {
      Context c = context();
      var binding = binding(c);
      c.tx(
          () -> {
            c.fences.reserve(binding);
            c.fences.claimCommitOrder(binding);
            return null;
          });
      terminal(c, binding, Owner.WORLD, outcome);
      assertThatThrownBy(() -> changePassword(c, binding.actorAccountId(), "test-only-blocked"))
          .isInstanceOf(DataAccessException.class);
      terminal(c, binding, Owner.GAME_DESIGN, outcome);
      changePassword(c, binding.actorAccountId(), "test-only-settled-change");
      var settlement =
          Objects.requireNonNull(
              c.dsl.fetchOne(
                  "SELECT account_draft_authorization_is_settled(?) AS settled",
                  binding.operationId()),
              "Exact owner settlement readback required");
      assertThat(settlement.get("settled", Boolean.class)).isTrue();
    }
    Context c = context();
    var binding = binding(c);
    c.tx(
        () -> {
          c.fences.reserve(binding);
          c.fences.claimCommitOrder(binding);
          return null;
        });
    terminal(c, binding, Owner.WORLD, Outcome.COMMITTED);
    terminal(c, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThatThrownBy(() -> changePassword(c, binding.actorAccountId(), "test-only-mixed-denied"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void sourceFirstContentionRollsBackRowsFirstProducerWithoutWaitingOrTerminalReceipt()
      throws Exception {
    Context c = context();
    var binding = binding(c);
    try (Connection writer = c.source.getConnection()) {
      writer.setAutoCommit(false);
      try (var statement =
          writer.prepareStatement(
              "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ? FOR UPDATE")) {
        statement.setString(1, binding.sources().getFirst().key());
        statement.executeQuery().close();
      }
      Instant started = Instant.now();
      assertThatThrownBy(
              () ->
                  c.tx(
                      () -> {
                        c.dsl.fetchOne(
                            "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE",
                            binding.actorAccountId());
                        c.fences.lockProducerSourcesNowait(binding.sources());
                        c.fences.reserve(binding);
                        return c.fences.claimCommitOrder(binding);
                      }))
          .isInstanceOf(DataAccessException.class);
      assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(2));
      assertThat(c.dsl.fetchCount(DSL.table("account_draft_authorization_fences"))).isZero();
      assertThat(c.dsl.fetchCount(DSL.table("account_draft_authorization_owner_readbacks")))
          .isZero();
      writer.rollback();
    }
    c.tx(
        () -> {
          c.fences.lockProducerSourcesNowait(binding.sources());
          c.fences.reserve(binding);
          return c.fences.claimCommitOrder(binding);
        });
    assertThatThrownBy(
            () -> changePassword(c, binding.actorAccountId(), "test-only-producer-first"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void waitingAndRevokeIntentCanStillBeRecordedWhileActualMutationStaysDenied() {
    Context c = context();
    var binding = binding(c);
    c.tx(() -> c.fences.reserve(binding));
    var intent =
        new DraftAuthorizationFenceRepository.SourceChange(
            UUID.randomUUID(), binding.sources(), new byte[] {11});
    assertThat(c.tx(() -> c.fences.requestSourceChange(intent))).isFalse();
    assertThat(c.tx(() -> c.fences.read(binding)).ordering())
        .isEqualTo(DraftAuthorizationFenceRepository.Ordering.REVOKE_ORDER);
    assertThatThrownBy(() -> changePassword(c, binding.actorAccountId(), "test-only-before-aborts"))
        .isInstanceOf(DataAccessException.class);
    terminal(c, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    terminal(c, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThat(c.tx(() -> c.fences.sourceMutationPermitted(intent))).isTrue();
    changePassword(c, binding.actorAccountId(), "test-only-after-aborts");
  }

  @Test
  void everyMixedOwnerOutcomePairingRemainsFencedUnderBothOrderings() {
    for (DraftAuthorizationFenceRepository.Ordering ordering :
        List.of(
            DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER,
            DraftAuthorizationFenceRepository.Ordering.REVOKE_ORDER)) {
      for (Outcome worldOutcome : Outcome.values()) {
        for (Outcome gameDesignOutcome : Outcome.values()) {
          if (worldOutcome == gameDesignOutcome) {
            continue;
          }
          Context c = context();
          var binding = binding(c);
          var change = sourceChange(binding);
          c.tx(
              () -> {
                c.fences.reserve(binding);
                if (ordering == DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER) {
                  c.fences.claimCommitOrder(binding);
                } else {
                  assertThat(c.fences.requestSourceChange(change)).isFalse();
                }
                return null;
              });
          terminal(c, binding, Owner.WORLD, worldOutcome);
          terminal(c, binding, Owner.GAME_DESIGN, gameDesignOutcome);

          assertThat(c.tx(() -> c.fences.readSettlement(binding)))
              .as("%s with WORLD %s and GAME_DESIGN %s", ordering, worldOutcome, gameDesignOutcome)
              .isEqualTo(DraftAuthorizationFenceRepository.Settlement.PENDING);
          assertThatThrownBy(
                  () -> changePassword(c, binding.actorAccountId(), "test-only-mixed-outcome"))
              .isInstanceOf(DataAccessException.class);
          if (ordering == DraftAuthorizationFenceRepository.Ordering.REVOKE_ORDER) {
            assertThat(c.tx(() -> c.fences.sourceMutationPermitted(change))).isFalse();
            assertThat(c.tx(() -> c.fences.sourceAbortPermitted(change))).isFalse();
          }
        }
      }
    }
  }

  @Test
  void overlappingSourceChangeClaimsObserveBackendWaitWhileDisjointSourcesProceed()
      throws Exception {
    Context c = context();
    var binding = binding(c);
    var firstChange = sourceChange(binding);
    var overlappingChange = sourceChange(binding);
    c.tx(() -> c.fences.reserve(binding));

    CountDownLatch firstIntentRecorded = new CountDownLatch(1);
    CountDownLatch releaseFirstIntent = new CountDownLatch(1);
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    AtomicInteger firstPid = new AtomicInteger();
    Thread firstClaim =
        new Thread(
            () -> {
              try {
                c.tx(
                    () -> {
                      firstPid.set(
                          Objects.requireNonNull(c.dsl.fetchOne("SELECT pg_backend_pid()"))
                              .get(0, Integer.class));
                      assertThat(c.fences.requestSourceChange(firstChange)).isFalse();
                      firstIntentRecorded.countDown();
                      await(releaseFirstIntent);
                      return null;
                    });
              } catch (Throwable failure) {
                firstFailure.set(failure);
                firstIntentRecorded.countDown();
              }
            },
            "draft-source-change-first-claim");
    firstClaim.start();

    assertThat(firstIntentRecorded.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(firstFailure.get()).isNull();

    CountDownLatch overlappingStarted = new CountDownLatch(1);
    AtomicInteger overlappingPid = new AtomicInteger();
    AtomicReference<Throwable> overlappingFailure = new AtomicReference<>();
    Thread overlappingClaim =
        startContender(
            c,
            overlappingStarted,
            overlappingPid,
            overlappingFailure,
            () -> c.fences.requestSourceChange(overlappingChange));
    try {
      assertThat(overlappingStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitBackendLock(c, overlappingPid.get(), List.of(firstPid.get()));

      var disjointBinding = binding(c);
      var disjointChange = sourceChange(disjointBinding);
      c.tx(() -> c.fences.reserve(disjointBinding));
      assertThat(c.tx(() -> c.fences.requestSourceChange(disjointChange))).isFalse();
      assertThat(c.tx(() -> c.fences.read(disjointBinding)).ordering())
          .isEqualTo(DraftAuthorizationFenceRepository.Ordering.REVOKE_ORDER);
      assertThat(c.tx(() -> c.fences.readSourceChange(disjointChange).status()))
          .isEqualTo("WAITING");
    } finally {
      releaseFirstIntent.countDown();
    }

    join(firstClaim);
    join(overlappingClaim);
    assertThat(firstFailure.get()).isNull();
    assertThat(overlappingFailure.get())
        .isInstanceOf(DraftAuthorizationFenceRepository.PendingSourceChangeException.class);
    assertThat(c.tx(() -> c.fences.readSourceChange(firstChange).status())).isEqualTo("WAITING");
    assertThatThrownBy(() -> c.tx(() -> c.fences.readSourceChange(overlappingChange)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Absent or changed immutable source mutation intent");
  }

  @Test
  void commitOrderAndRevocationContendersSerializeBehindObservedPostgresLocks() throws Exception {
    assertContendedOrderingWins(true);
    assertContendedOrderingWins(false);
  }

  @Test
  void reservationAndSourceChangeIntentRollbackLeavesRowsUnchangedAndAllowsDisjointWork() {
    Context c = context();
    var binding = binding(c);
    var change = sourceChange(binding);
    Map<String, Integer> before = authorizationRowCounts(c);
    String passwordBefore = accountPassword(c, binding.actorAccountId());

    assertThatThrownBy(
            () ->
                c.tx(
                    () -> {
                      c.fences.reserve(binding);
                      assertThat(c.fences.requestSourceChange(change)).isFalse();
                      throw new IllegalStateException("test-only transaction rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("test-only transaction rollback");

    assertThat(authorizationRowCounts(c)).isEqualTo(before);
    assertThat(accountPassword(c, binding.actorAccountId())).isEqualTo(passwordBefore);
    assertThat(c.tx(() -> c.fences.readOriginalBinding(binding.operationId())))
        .as("the reservation rolled back with its source-change intent")
        .isEmpty();
    assertThatThrownBy(() -> c.tx(() -> c.fences.readSourceChange(change)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Absent or changed immutable source mutation intent");

    var disjointBinding = binding(c);
    var disjointChange = sourceChange(disjointBinding);
    c.tx(() -> c.fences.reserve(disjointBinding));
    assertThat(c.tx(() -> c.fences.requestSourceChange(disjointChange))).isFalse();
    assertThat(c.tx(() -> c.fences.read(disjointBinding).ordering()))
        .isEqualTo(DraftAuthorizationFenceRepository.Ordering.REVOKE_ORDER);
    assertThat(c.tx(() -> c.fences.readSourceChange(disjointChange).status())).isEqualTo("WAITING");
  }

  @Test
  void exactSourceAbortRetryPreservesTerminalReadbackAndCannotReopen() {
    Context c = context();
    var binding = binding(c);
    var change = sourceChange(binding);
    c.tx(
        () -> {
          c.fences.reserve(binding);
          assertThat(c.fences.requestSourceChange(change)).isFalse();
          return null;
        });
    terminal(c, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    terminal(c, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThat(c.tx(() -> c.fences.sourceAbortPermitted(change))).isTrue();

    c.tx(
        () -> {
          c.fences.markSourceAborted(change, SourceChangeAbortReason.EXPIRED);
          return null;
        });
    var aborted = c.tx(() -> c.fences.readSourceChange(change));
    String abortedRowVersion = sourceChangeRowVersion(c, change.changeId());
    assertThat(aborted.status()).isEqualTo("SOURCE_ABORTED");
    assertThat(aborted.requestedAt()).isNotNull();
    assertThat(aborted.committedAt()).isNull();
    assertThat(aborted.abortedAt()).isNotNull();
    assertThat(aborted.abortReason()).isEqualTo(SourceChangeAbortReason.EXPIRED);

    c.tx(
        () -> {
          c.fences.markSourceAborted(change, SourceChangeAbortReason.EXPIRED);
          return null;
        });
    assertSourceChangeUnchanged(c.tx(() -> c.fences.readSourceChange(change)), aborted);
    assertThat(sourceChangeRowVersion(c, change.changeId())).isEqualTo(abortedRowVersion);

    assertThatThrownBy(
            () ->
                c.tx(
                    () -> {
                      c.fences.markSourceAborted(change, SourceChangeAbortReason.DEFINITIVE_ABORT);
                      return null;
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Source change abort reason differs from readback");
    assertSourceChangeUnchanged(c.tx(() -> c.fences.readSourceChange(change)), aborted);

    assertThatThrownBy(() -> c.tx(() -> c.fences.requestSourceChange(change)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Aborted source change cannot be requested again");
    assertThatThrownBy(
            () ->
                c.tx(
                    () -> {
                      c.fences.markSourceCommitted(change);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Aborted source change cannot be committed");
    assertSourceChangeUnchanged(c.tx(() -> c.fences.readSourceChange(change)), aborted);
    assertThat(sourceChangeRowVersion(c, change.changeId())).isEqualTo(abortedRowVersion);
  }

  private static void assertContendedOrderingWins(boolean commitOrderWins) throws Exception {
    Context c = context();
    var binding = binding(c);
    var change = sourceChange(binding);
    c.tx(() -> c.fences.reserve(binding));

    try (Connection blocker = c.source.getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement(
              "SELECT source_key FROM account_draft_authorization_source_locks"
                  + " WHERE source_key = ? FOR UPDATE")) {
        statement.setString(1, binding.sources().getFirst().key());
        assertThat(statement.executeQuery().next()).isTrue();
      }

      int blockerPid;
      try (var statement = blocker.createStatement();
          var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
        assertThat(rows.next()).isTrue();
        blockerPid = rows.getInt(1);
      }
      CountDownLatch firstStarted = new CountDownLatch(1);
      AtomicInteger firstPid = new AtomicInteger();
      AtomicReference<Throwable> firstFailure = new AtomicReference<>();
      Thread firstContender =
          startContender(
              c,
              firstStarted,
              firstPid,
              firstFailure,
              () -> {
                if (commitOrderWins) {
                  c.fences.claimCommitOrder(binding);
                } else {
                  c.fences.requestSourceChange(change);
                }
              });
      Thread secondContender = null;
      AtomicReference<Throwable> secondFailure = new AtomicReference<>();
      try {
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitBackendLock(c, firstPid.get(), List.of(blockerPid));

        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger secondPid = new AtomicInteger();
        secondContender =
            startContender(
                c,
                secondStarted,
                secondPid,
                secondFailure,
                () -> {
                  if (commitOrderWins) {
                    c.fences.requestSourceChange(change);
                  } else {
                    c.fences.claimCommitOrder(binding);
                  }
                });
        assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitBackendLock(c, secondPid.get(), List.of(blockerPid, firstPid.get()));
      } finally {
        blocker.commit();
      }
      join(firstContender);
      if (secondContender != null) {
        join(secondContender);
      }
      assertThat(firstFailure.get()).isNull();
      if (commitOrderWins) {
        assertThat(secondFailure.get()).isNull();
      } else {
        assertThat(secondFailure.get())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Revocation precedes this owner commit");
      }
    }

    if (commitOrderWins) {
      assertThat(c.tx(() -> c.fences.read(binding).ordering()))
          .isEqualTo(DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER);
      assertThat(c.tx(() -> c.fences.readSourceChange(change).status())).isEqualTo("WAITING");
    } else {
      assertThat(c.tx(() -> c.fences.read(binding).ordering()))
          .isEqualTo(DraftAuthorizationFenceRepository.Ordering.REVOKE_ORDER);
      assertThatThrownBy(() -> c.tx(() -> c.fences.claimCommitOrder(binding)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Revocation precedes this owner commit");
    }
  }

  private static Thread startContender(
      Context c,
      CountDownLatch started,
      AtomicInteger backendPid,
      AtomicReference<Throwable> failure,
      Runnable action) {
    Thread contender =
        new Thread(
            () -> {
              try {
                c.tx(
                    () -> {
                      backendPid.set(
                          Objects.requireNonNull(c.dsl.fetchOne("SELECT pg_backend_pid()"))
                              .get(0, Integer.class));
                      started.countDown();
                      action.run();
                      return null;
                    });
              } catch (Throwable thrown) {
                failure.set(thrown);
                started.countDown();
              }
            },
            "draft-fence-postgres-contender");
    contender.start();
    return contender;
  }

  private static void awaitBackendLock(Context c, int backendPid, List<Integer> expectedBlockers)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      for (int expectedBlocker : expectedBlockers) {
        var activity =
            c.dsl.fetchOne(
                "SELECT wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)) "
                    + "FROM pg_stat_activity WHERE pid = ?",
                expectedBlocker,
                backendPid);
        if (activity != null && Boolean.TRUE.equals(activity.get(0, Boolean.class))) {
          return;
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "PostgreSQL backend " + backendPid + " did not wait behind " + expectedBlockers);
  }

  private static void join(Thread thread) throws InterruptedException {
    thread.join(Duration.ofSeconds(10).toMillis());
    assertThat(thread.isAlive()).as("PostgreSQL contention worker finished").isFalse();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while holding a test transaction", interrupted);
    }
  }

  private static Map<String, Integer> authorizationRowCounts(Context c) {
    return Map.of(
        "source_locks", c.dsl.fetchCount(DSL.table("account_draft_authorization_source_locks")),
        "fences", c.dsl.fetchCount(DSL.table("account_draft_authorization_fences")),
        "sources", c.dsl.fetchCount(DSL.table("account_draft_authorization_sources")),
        "owner_readbacks",
            c.dsl.fetchCount(DSL.table("account_draft_authorization_owner_readbacks")),
        "source_changes", c.dsl.fetchCount(DSL.table("account_draft_authorization_source_changes")),
        "changed_scopes",
            c.dsl.fetchCount(DSL.table("account_draft_authorization_changed_scopes")));
  }

  private static String accountPassword(Context c, UUID accountId) {
    return Objects.requireNonNull(
            c.dsl.fetchOne("SELECT password_hash FROM accounts WHERE account_uuid = ?", accountId))
        .get("password_hash", String.class);
  }

  private static DraftAuthorizationFenceRepository.SourceChange sourceChange(
      DraftAuthorizationFenceBinding binding) {
    return new DraftAuthorizationFenceRepository.SourceChange(
        UUID.randomUUID(), binding.sources(), new byte[] {17});
  }

  private static void assertSourceChangeUnchanged(
      DraftAuthorizationFenceRepository.SourceChangeSnapshot actual,
      DraftAuthorizationFenceRepository.SourceChangeSnapshot expected) {
    assertThat(actual.changeId()).isEqualTo(expected.changeId());
    assertThat(actual.binding()).containsExactly(expected.binding());
    assertThat(actual.status()).isEqualTo(expected.status());
    assertThat(actual.requestedAt()).isEqualTo(expected.requestedAt());
    assertThat(actual.committedAt()).isEqualTo(expected.committedAt());
    assertThat(actual.abortedAt()).isEqualTo(expected.abortedAt());
    assertThat(actual.abortReason()).isEqualTo(expected.abortReason());
  }

  private static String sourceChangeRowVersion(Context c, UUID changeId) {
    return Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT xmin::text FROM account_draft_authorization_source_changes WHERE change_id = ?",
                changeId))
        .get(0, String.class);
  }

  private static void terminal(
      Context c, DraftAuthorizationFenceBinding binding, Owner owner, Outcome outcome) {
    c.tx(
        () -> {
          c.fences.recordOwnerReadback(
              binding,
              new OwnerReadback(
                  owner,
                  outcome,
                  binding.operationId(),
                  binding.commitId(),
                  binding.fenceId(),
                  binding.inputDigest(),
                  binding.canonicalBytes(),
                  new byte[] {7}));
          return null;
        });
  }

  private static void changePassword(Context c, UUID id, String hash) {
    c.tx(
        () -> {
          Account account = new AccountRepository(c.dsl).findByAccountUuid(id).orElseThrow();
          account.setPasswordHash(hash);
          return new AccountRepository(c.dsl).save(account);
        });
  }

  private static UUID account(Context c) {
    return c.tx(
        () -> {
          Account row = new Account();
          String suffix = UUID.randomUUID().toString();
          row.setUsername("test-" + suffix);
          row.setEmail(suffix + "@example.test");
          row.setPasswordHash("test-only-hash");
          return new AccountRepository(c.dsl).save(row).getAccountUuid();
        });
  }

  private static DraftAuthorizationFenceBinding binding(Context c) {
    UUID actor = account(c);
    var complete =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "test-only-tenant-key",
                2,
                "test-only-tenant-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-only/base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-only-world-payload")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "test-region",
                    "aggregate",
                    "test-region",
                    "0")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        actor,
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "0",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                actor.toString(),
                "1",
                "1",
                null,
                null,
                "test-only-source-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  }

  private static Context context() {
    String schema = "control_ui_guard_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(source, dsl, new DraftAuthorizationFenceRepository(dsl), transaction);
  }

  private record Context(
      DriverManagerDataSource source,
      DSLContext dsl,
      DraftAuthorizationFenceRepository fences,
      TransactionTemplate transaction) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }
  }
}
