package integration.net.firedevops.firemud.accountservice.authorpublication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChangeAbortReason;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.Owner;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
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
 * Isolated component fixtures stipulate original authenticated synchronized selection and current
 * Account capture. They prove no operative legal terms, source-verification product, RPC or live
 * publication. Structural terminal rows in the focused SQL tests below are deliberately synthetic:
 * they test PostgreSQL integrity/ordering guards only and are not transport or owner proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class PublicationAuthorizationFencePostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void exactReserveOrderAndRestartReadbackPreserveOriginalBytes() {
    Context context = context();
    var binding = binding();
    assertThat(tx(context, () -> context.publication().reserve(binding)).ordering())
        .isEqualTo(Ordering.RESERVED);
    assertThat(tx(context, () -> context.publication().reserve(binding)).binding())
        .isEqualTo(binding.canonicalBytes());
    assertThat(tx(context, () -> context.publication().claimPublicationOrder(binding)).ordering())
        .isEqualTo(Ordering.PUBLICATION_ORDER);
    var restarted = new PublicationAuthorizationFenceRepository(context.dsl());
    assertThat(tx(context, () -> restarted.readOriginalBinding(binding.operationId())))
        .get()
        .satisfies(
            original -> assertThat(original.canonicalBytes()).isEqualTo(binding.canonicalBytes()));
    assertThat(tx(context, () -> restarted.claimPublicationOrder(binding)).binding())
        .isEqualTo(binding.canonicalBytes());
    assertThat(tx(context, () -> restarted.readOriginalBinding(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void changedRequestCaptureActorOrAllocatedIdentityCannotReplaceOriginal() {
    Context context = context();
    var original = binding();
    tx(context, () -> context.publication().reserve(original));
    var changedSource =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            original.input().actorAccountId().toString(),
            "4",
            "5",
            null,
            null,
            new byte[] {8});
    var recaptured =
        new AccountPublicationAuthorizationBinding(
            original.operationId(), original.fenceId(), original.input(), List.of(changedSource));
    assertThatThrownBy(() -> tx(context, () -> context.publication().reserve(recaptured)))
        .isInstanceOf(IllegalArgumentException.class);
    var reallocated =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(), UUID.randomUUID(), original.input(), original.sources());
    assertThatThrownBy(() -> tx(context, () -> context.publication().reserve(reallocated)))
        .isInstanceOf(DataAccessException.class);
    var changedIntent =
        new AccountPublicationAuthorizationBinding(
            original.operationId(),
            original.fenceId(),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                original.input().actorAccountId(), selection("changed")),
            original.sources());
    assertThatThrownBy(() -> tx(context, () -> context.publication().read(changedIntent)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(tx(context, () -> context.publication().read(original)).binding())
        .isEqualTo(original.canonicalBytes());
  }

  @Test
  void sourceChangeRevokesReservedPublicationButCannotCommitOrAbortWithoutItsOwnTerminalContract() {
    Context context = context();
    var binding = binding();
    tx(context, () -> context.publication().reserve(binding));
    var change = change(binding);
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isFalse();
    assertThat(tx(context, () -> context.publication().read(binding)).ordering())
        .isEqualTo(Ordering.REVOKE_ORDER);
    assertThatThrownBy(
            () -> tx(context, () -> context.publication().claimPublicationOrder(binding)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isFalse();
    assertThat(tx(context, () -> context.draft().sourceAbortPermitted(change))).isFalse();
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> {
                      context.draft().markSourceAborted(change, SourceChangeAbortReason.EXPIRED);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_draft_authorization_source_changes SET status='SOURCE_COMMITTED',"
                                    + " committed_at=CURRENT_TIMESTAMP WHERE change_id=?",
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
                                "UPDATE account_draft_authorization_source_changes SET status='SOURCE_ABORTED',"
                                    + " aborted_at=CURRENT_TIMESTAMP, abort_reason='EXPIRED' WHERE change_id=?",
                                change.changeId())))
        .isInstanceOf(DataAccessException.class);
    assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
        .isEqualTo("WAITING");
  }

  @Test
  void publicationOrderRemainsUnresolvedAfterOriginalDraftTerminalResults() {
    Context context = context();
    var publication = binding();
    tx(context, () -> context.publication().reserve(publication));
    tx(context, () -> context.publication().claimPublicationOrder(publication));
    var commit = publication.input().selection().selectedCommit();
    var draft =
        new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            commit.requestId(),
            commit.commitId(),
            UUID.randomUUID(),
            publication.input().actorAccountId(),
            publication.tenantId(),
            commit.target().canonicalVersionId(),
            commit.baseCommitId(),
            "1",
            commit.canonicalBytes(),
            commit.canonicalBytes(),
            commit.digest(),
            publication.sources());
    tx(
        context,
        () -> {
          context.draft().reserve(draft);
          context.draft().claimCommitOrder(draft);
          for (var owner : DraftAuthorizationFenceBinding.Owner.values()) {
            context
                .draft()
                .recordOwnerReadback(
                    draft,
                    new DraftAuthorizationFenceBinding.OwnerReadback(
                        owner,
                        DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                        draft.operationId(),
                        draft.commitId(),
                        draft.fenceId(),
                        draft.inputDigest(),
                        draft.canonicalBytes(),
                        new byte[] {1}));
          }
          return null;
        });
    var change = change(publication);
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isFalse();
    assertThat(tx(context, () -> context.publication().read(publication)).ordering())
        .isEqualTo(Ordering.PUBLICATION_ORDER);
    assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isFalse();
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> {
                      context.draft().markSourceCommitted(change);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void waitingChangeDeniesNewReservationAndUnrelatedScopeStillProceeds() {
    Context context = context();
    var binding = binding();
    var change = change(binding);
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isTrue();
    assertThatThrownBy(() -> tx(context, () -> context.publication().reserve(binding)))
        .isInstanceOf(IllegalStateException.class);
    var independentInput =
        new AccountPublicationAuthorizationBinding.PreallocationInput(
            UUID.randomUUID(), selection("notes"));
    var unrelated =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            independentInput,
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT,
                    independentInput.actorAccountId().toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    assertThat(tx(context, () -> context.publication().reserve(unrelated)).ordering())
        .isEqualTo(Ordering.RESERVED);
  }

  @Test
  void concurrentPublicationClaimAndSourceChangeChooseOneDurableOrder() throws Exception {
    Context context = context();
    var fixture = AccountPublicationTerminalFixtures.scenario();
    var binding = fixture.account();
    tx(context, () -> context.publication().reserve(binding));
    var change = change(binding);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var claim =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                try {
                  return tx(context, () -> context.publication().claimPublicationOrder(binding))
                      .ordering();
                } catch (IllegalStateException revoked) {
                  return Ordering.REVOKE_ORDER;
                }
              });
      var source =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return tx(context, () -> context.draft().requestSourceChange(change));
              });
      await(ready);
      start.countDown();
      Ordering winner = claim.get(10, TimeUnit.SECONDS);
      assertThat(source.get(10, TimeUnit.SECONDS)).isFalse();
      assertThat(tx(context, () -> context.publication().read(binding)).ordering())
          .isEqualTo(winner);
      assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
          .isEqualTo("WAITING");
      assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isFalse();
      tx(
          context,
          () -> {
            context
                .publication()
                .recordOwnerResult(
                    fixture.operation(), Owner.GAME_DESIGN, fixture.noPublicationTerminal());
            context
                .publication()
                .recordOwnerResult(
                    fixture.operation(), Owner.WORLD, fixture.noPublicationTerminal());
            return null;
          });
      if (winner == Ordering.PUBLICATION_ORDER) {
        assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isTrue();
        txRun(context, () -> context.draft().markSourceCommitted(change));
      } else {
        assertThat(tx(context, () -> context.draft().sourceAbortPermitted(change))).isTrue();
        tx(
            context,
            () -> {
              context.draft().markSourceAborted(change, SourceChangeAbortReason.DEFINITIVE_ABORT);
              return null;
            });
      }
      assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
          .isEqualTo(winner == Ordering.PUBLICATION_ORDER ? "SOURCE_COMMITTED" : "SOURCE_ABORTED");
    }
  }

  @Test
  void exactIdenticalOwnerReadbacksSettleOnlyAfterBothOwnersAndPreserveRetryIdentity()
      throws Exception {
    Context context = context();
    var fixture = AccountPublicationTerminalFixtures.scenario();
    tx(context, () -> context.publication().reserve(fixture.account()));
    tx(context, () -> context.publication().claimPublicationOrder(fixture.account()));
    var initial =
        tx(
            context,
            () -> context.publication().readOriginalOperation(fixture.operation()).orElseThrow());
    assertThat(initial.ordering()).isEqualTo(Ordering.PUBLICATION_ORDER);
    assertThat(initial.settlement()).isEqualTo(Settlement.PENDING);
    assertThat(initial.gameDesignResult()).isEmpty();
    assertThat(initial.worldResult()).isEmpty();
    var change = change(fixture.account());
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isFalse();

    var design =
        tx(
            context,
            () ->
                context
                    .publication()
                    .recordOwnerResult(
                        fixture.operation(), Owner.GAME_DESIGN, fixture.noPublicationTerminal()));
    assertThat(
            tx(
                context,
                () ->
                    context
                        .publication()
                        .recordOwnerResult(
                            fixture.operation(),
                            Owner.GAME_DESIGN,
                            fixture.noPublicationTerminal())))
        .satisfies(
            retry -> {
              assertThat(retry.outcome()).isEqualTo(design.outcome());
              assertThat(retry.operationBytes()).containsExactly(design.operationBytes());
              assertThat(retry.terminalBytes()).containsExactly(design.terminalBytes());
              assertThat(retry.recordedAt()).isEqualTo(design.recordedAt());
            });
    assertThat(tx(context, () -> context.publication().readSettlement(fixture.operation())))
        .isEqualTo(Settlement.PENDING);
    var incomplete =
        tx(
            context,
            () -> context.publication().readOriginalOperation(fixture.operation()).orElseThrow());
    assertThat(incomplete.settlement()).isEqualTo(Settlement.PENDING);
    assertThat(incomplete.gameDesignResult()).isPresent();
    assertThat(incomplete.worldResult()).isEmpty();
    assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isFalse();
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .publication()
                            .recordOwnerResult(
                                fixture.operation(),
                                Owner.GAME_DESIGN,
                                fixture.publishedTerminal())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Changed or contradictory");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .publication()
                            .recordOwnerResult(
                                fixture.operation(), Owner.WORLD, fixture.publishedTerminal())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Changed or contradictory");
    assertThat(tx(context, () -> context.publication().readSettlement(fixture.operation())))
        .isEqualTo(Settlement.PENDING);

    tx(
        context,
        () ->
            context
                .publication()
                .recordOwnerResult(
                    fixture.operation(), Owner.WORLD, fixture.noPublicationTerminal()));
    assertThat(tx(context, () -> context.publication().readSettlement(fixture.operation())))
        .isEqualTo(Settlement.NO_PUBLICATION);
    var settled =
        tx(
            context,
            () -> context.publication().readOriginalOperation(fixture.operation()).orElseThrow());
    assertThat(settled.settlement()).isEqualTo(Settlement.NO_PUBLICATION);
    assertThat(settled.gameDesignResult()).isPresent();
    assertThat(settled.worldResult()).isPresent();
    assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isTrue();
    txRun(context, () -> context.draft().markSourceCommitted(change));
    assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
        .isEqualTo("SOURCE_COMMITTED");
  }

  @Test
  void publishedOwnerPairSettlesPublicationOrderAndAllowsSourceTransition() throws Exception {
    Context context = context();
    var fixture = AccountPublicationTerminalFixtures.scenario();
    tx(context, () -> context.publication().reserve(fixture.account()));
    tx(context, () -> context.publication().claimPublicationOrder(fixture.account()));
    var change = change(fixture.account());
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isFalse();
    tx(
        context,
        () -> {
          context
              .publication()
              .recordOwnerResult(
                  fixture.operation(), Owner.GAME_DESIGN, fixture.publishedTerminal());
          return null;
        });
    assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isFalse();
    tx(
        context,
        () ->
            context
                .publication()
                .recordOwnerResult(fixture.operation(), Owner.WORLD, fixture.publishedTerminal()));
    assertThat(tx(context, () -> context.publication().readSettlement(fixture.operation())))
        .isEqualTo(Settlement.PUBLISHED);
    assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isTrue();
    txRun(context, () -> context.draft().markSourceCommitted(change));
    assertThat(tx(context, () -> context.draft().readSourceChange(change).status()))
        .isEqualTo("SOURCE_COMMITTED");
  }

  @Test
  void revokeOrderRequiresExactNoPublicationPairBeforeDefinitiveSourceAbort() throws Exception {
    Context context = context();
    var fixture = AccountPublicationTerminalFixtures.scenario();
    tx(context, () -> context.publication().reserve(fixture.account()));
    var change = change(fixture.account());
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isFalse();
    assertThat(tx(context, () -> context.publication().read(fixture.account()).ordering()))
        .isEqualTo(Ordering.REVOKE_ORDER);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .publication()
                            .recordOwnerResult(
                                fixture.operation(),
                                Owner.GAME_DESIGN,
                                fixture.publishedTerminal())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Revocation order");
    assertThat(tx(context, () -> context.draft().sourceAbortPermitted(change))).isFalse();
    tx(
        context,
        () ->
            context
                .publication()
                .recordOwnerResult(
                    fixture.operation(), Owner.GAME_DESIGN, fixture.noPublicationTerminal()));
    assertThat(tx(context, () -> context.draft().sourceAbortPermitted(change))).isFalse();
    tx(
        context,
        () ->
            context
                .publication()
                .recordOwnerResult(
                    fixture.operation(), Owner.WORLD, fixture.noPublicationTerminal()));
    assertThat(tx(context, () -> context.publication().readSettlement(fixture.operation())))
        .isEqualTo(Settlement.NO_PUBLICATION);
    assertThat(tx(context, () -> context.draft().sourceAbortPermitted(change))).isTrue();
    tx(
        context,
        () -> {
          context.draft().markSourceAborted(change, SourceChangeAbortReason.DEFINITIVE_ABORT);
          return null;
        });
    assertThat(tx(context, () -> context.draft().readSourceChange(change).status()))
        .isEqualTo("SOURCE_ABORTED");
  }

  @Test
  void lateRuntimeRollbackRetainsValidOwnerPairAndWaitingSourceIntent() throws Exception {
    Context context = context();
    var fixture = AccountPublicationTerminalFixtures.scenario();
    tx(context, () -> context.publication().reserve(fixture.account()));
    tx(context, () -> context.publication().claimPublicationOrder(fixture.account()));
    var change = change(fixture.account());
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isFalse();
    tx(
        context,
        () -> {
          context
              .publication()
              .recordOwnerResult(
                  fixture.operation(), Owner.GAME_DESIGN, fixture.noPublicationTerminal());
          context
              .publication()
              .recordOwnerResult(fixture.operation(), Owner.WORLD, fixture.noPublicationTerminal());
          return null;
        });
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> {
                      context.draft().markSourceCommitted(change);
                      throw new IllegalStateException("fixture rollback after owner settlement");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fixture rollback");
    assertThat(tx(context, () -> context.draft().readSourceChange(change).status()))
        .isEqualTo("WAITING");
    assertThat(tx(context, () -> context.publication().readSettlement(fixture.operation())))
        .isEqualTo(Settlement.NO_PUBLICATION);
    assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isTrue();
    txRun(context, () -> context.draft().markSourceCommitted(change));
    assertThat(tx(context, () -> context.draft().readSourceChange(change).status()))
        .isEqualTo("SOURCE_COMMITTED");
  }

  @Test
  void directSqlSourceTransitionNeedsTwoMatchingPublicationOwnerRowsAndImmutableRetries() {
    Context context = context();
    var binding = binding();
    tx(context, () -> context.publication().reserve(binding));
    tx(context, () -> context.publication().claimPublicationOrder(binding));
    var change = change(binding);
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isFalse();
    assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isFalse();

    assertThatThrownBy(
            () -> txRun(context, () -> transitionSourceSql(context, change, "SOURCE_COMMITTED")))
        .isInstanceOf(DataAccessException.class);
    assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
        .isEqualTo("WAITING");

    byte[] operationBytes = structuralOperationBytes(binding);
    byte[] noPublication = structuralTerminalBytes(operationBytes, "NO_PUBLICATION");
    assertThatThrownBy(
            () ->
                txRun(
                    context,
                    () ->
                        insertStructuralOwnerResult(
                            context,
                            binding,
                            "GAME_DESIGN",
                            "PUBLISHED",
                            operationBytes,
                            structuralTerminalBytes(
                                operationBytes, "PUBLISHED", "9223372036854775808"))))
        .isInstanceOf(DataAccessException.class);
    txRun(
        context,
        () ->
            insertStructuralOwnerResult(
                context, binding, "GAME_DESIGN", "NO_PUBLICATION", operationBytes, noPublication));
    assertThatThrownBy(
            () ->
                txRun(
                    context,
                    () ->
                        insertStructuralOwnerResult(
                            context,
                            binding,
                            "WORLD",
                            "PUBLISHED",
                            operationBytes,
                            structuralTerminalBytes(operationBytes, "PUBLISHED"))))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                txRun(
                    context,
                    () ->
                        insertStructuralOwnerResult(
                            context,
                            binding,
                            "GAME_DESIGN",
                            "NO_PUBLICATION",
                            operationBytes,
                            noPublication)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_publication_authorization_owner_results"
                                    + " SET terminal_bytes=decode('01','hex') WHERE operation_id=? AND owner='GAME_DESIGN'",
                                binding.operationId())))
        .isInstanceOf(DataAccessException.class);

    txRun(
        context,
        () ->
            insertStructuralOwnerResult(
                context, binding, "WORLD", "NO_PUBLICATION", operationBytes, noPublication));
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "DELETE FROM account_publication_authorization_owner_results WHERE operation_id=?",
                                binding.operationId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute("TRUNCATE account_publication_authorization_owner_results")))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> tx(context, () -> context.draft().sourceMutationPermitted(change)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Published World selector evidence");
    assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
        .isEqualTo("WAITING");

    // The database trigger proves structural pair consistency only. It does not authenticate the
    // stipulated owner rows; this direct SQL transition is not Account runtime proof.
    txRun(context, () -> transitionSourceSql(context, change, "SOURCE_COMMITTED"));
    assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
        .isEqualTo("SOURCE_COMMITTED");
  }

  @Test
  void revokeOrderAcceptsOnlyTwoMatchingNoPublicationRowsForDefinitiveAbort() {
    Context context = context();
    var binding = binding();
    tx(context, () -> context.publication().reserve(binding));
    var change = change(binding);
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isFalse();
    assertThat(tx(context, () -> context.publication().read(binding)).ordering())
        .isEqualTo(Ordering.REVOKE_ORDER);

    byte[] operationBytes = structuralOperationBytes(binding);
    assertThatThrownBy(
            () ->
                txRun(
                    context,
                    () ->
                        insertStructuralOwnerResult(
                            context,
                            binding,
                            "GAME_DESIGN",
                            "PUBLISHED",
                            operationBytes,
                            structuralTerminalBytes(operationBytes, "PUBLISHED"))))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> txRun(context, () -> transitionSourceSql(context, change, "SOURCE_ABORTED")))
        .isInstanceOf(DataAccessException.class);

    byte[] noPublication = structuralTerminalBytes(operationBytes, "NO_PUBLICATION");
    txRun(
        context,
        () ->
            insertStructuralOwnerResult(
                context, binding, "GAME_DESIGN", "NO_PUBLICATION", operationBytes, noPublication));
    txRun(
        context,
        () ->
            insertStructuralOwnerResult(
                context, binding, "WORLD", "NO_PUBLICATION", operationBytes, noPublication));
    assertThatThrownBy(
            () ->
                txRun(
                    context,
                    () ->
                        insertStructuralOwnerResult(
                            context,
                            binding,
                            "WORLD",
                            "PUBLISHED",
                            operationBytes,
                            structuralTerminalBytes(operationBytes, "PUBLISHED"))))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> tx(context, () -> context.draft().sourceAbortPermitted(change)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Published World selector evidence");
    assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
        .isEqualTo("WAITING");
    // As above, this directly tests SQL structural guarding, not an authenticated World ABORTED
    // read.
    txRun(context, () -> transitionSourceSql(context, change, "SOURCE_ABORTED"));
    assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
        .isEqualTo("SOURCE_ABORTED");
  }

  @Test
  void lateSourceTransitionRollbackRetainsWaitingChangeAndOwnerRows() {
    Context context = context();
    var binding = binding();
    tx(context, () -> context.publication().reserve(binding));
    tx(context, () -> context.publication().claimPublicationOrder(binding));
    var change = change(binding);
    assertThat(tx(context, () -> context.draft().requestSourceChange(change))).isFalse();
    byte[] operationBytes = structuralOperationBytes(binding);
    byte[] terminalBytes = structuralTerminalBytes(operationBytes, "NO_PUBLICATION");
    txRun(
        context,
        () ->
            insertStructuralOwnerResult(
                context, binding, "GAME_DESIGN", "NO_PUBLICATION", operationBytes, terminalBytes));
    txRun(
        context,
        () ->
            insertStructuralOwnerResult(
                context, binding, "WORLD", "NO_PUBLICATION", operationBytes, terminalBytes));

    assertThatThrownBy(
            () ->
                txRun(
                    context,
                    () -> {
                      transitionSourceSql(context, change, "SOURCE_COMMITTED");
                      throw new IllegalStateException(
                          "fixture rollback after guarded source transition");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fixture rollback");
    assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
        .isEqualTo("WAITING");
    var retainedOwnerCount =
        java.util.Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT count(*) FROM account_publication_authorization_owner_results WHERE operation_id=?",
                    binding.operationId()));
    assertThat(retainedOwnerCount.get(0, Long.class)).isEqualTo(2L);
    txRun(context, () -> transitionSourceSql(context, change, "SOURCE_COMMITTED"));
    assertThat(tx(context, () -> context.draft().readSourceChange(change)).status())
        .isEqualTo("SOURCE_COMMITTED");
  }

  @Test
  void rawSqlCannotReplaceOriginalSourceBindingOrOrderOrTruncateEvidence() {
    Context context = context();
    var binding = binding();
    tx(context, () -> context.publication().reserve(binding));
    for (String sql :
        List.of(
            "UPDATE account_publication_authorization_sources SET source_evidence=decode('01','hex') WHERE operation_id=?",
            "DELETE FROM account_publication_authorization_sources WHERE operation_id=?",
            "UPDATE account_publication_authorization_fences SET binding=decode('01','hex') WHERE operation_id=?",
            "DELETE FROM account_publication_authorization_fences WHERE operation_id=?")) {
      assertThatThrownBy(() -> tx(context, () -> context.dsl().execute(sql, binding.operationId())))
          .isInstanceOf(DataAccessException.class);
    }
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "TRUNCATE account_publication_authorization_fences, account_publication_authorization_sources")))
        .isInstanceOf(DataAccessException.class);
    tx(context, () -> context.publication().claimPublicationOrder(binding));
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_publication_authorization_fences SET ordering='REVOKE_ORDER',"
                                    + " ordered_at=CURRENT_TIMESTAMP WHERE operation_id=?",
                                binding.operationId())))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void failedOwnerTransactionRollsBackReservationAndParticipation() {
    Context context = context();
    var binding = binding();
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> {
                      context.publication().reserve(binding);
                      throw new IllegalStateException("fixture rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(tx(context, () -> context.publication().readOriginalBinding(binding.operationId())))
        .isEmpty();
    assertThat(context.dsl().fetchCount(DSL.table("account_publication_authorization_sources")))
        .isZero();
  }

  @Test
  void concurrentExactReservationReturnsOneImmutableOperation() throws Exception {
    Context context = context();
    var binding = binding();
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                await(start);
                return tx(context, () -> context.publication().reserve(binding)).binding();
              });
      var second =
          executor.submit(
              () -> {
                await(start);
                return tx(context, () -> context.publication().reserve(binding)).binding();
              });
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(binding.canonicalBytes());
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(binding.canonicalBytes());
      assertThat(context.dsl().fetchCount(DSL.table("account_publication_authorization_fences")))
          .isEqualTo(1);
    }
  }

  @Test
  void rawSqlCannotReserveIncompleteSourceParticipationOrSubstituteRequestIdentity() {
    Context context = context();
    var binding = binding();
    String vector =
        new tools.jackson.databind.ObjectMapper()
            .writeValueAsString(
                binding.sources().stream()
                    .map(
                        source ->
                            Map.of(
                                "key",
                                source.key(),
                                "evidence",
                                java.util.HexFormat.of().formatHex(source.canonicalBytes())))
                    .toList());
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "INSERT INTO account_publication_authorization_fences"
                                    + " (operation_id, tenant_id, publish_request_id, fence_id, actor_account_id,"
                                    + " selection_json, selection_digest, source_vector, binding, ordering)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'RESERVED')",
                                binding.operationId(),
                                binding.tenantId(),
                                binding.publishRequestId(),
                                binding.fenceId(),
                                binding.input().actorAccountId(),
                                binding.input().selection().canonicalJson(),
                                binding.input().selection().digest(),
                                vector,
                                binding.canonicalBytes())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("Complete immutable publication source participation required");
    assertThat(tx(context, () -> context.publication().readOriginalBinding(binding.operationId())))
        .isEmpty();
    tx(context, () -> context.publication().reserve(binding));
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "INSERT INTO account_publication_authorization_fences"
                                    + " SELECT ?::uuid, tenant_id, 'substituted-request', ?::uuid, actor_account_id,"
                                    + " selection_json, selection_digest, source_vector, binding, ordering, reserved_at, ordered_at"
                                    + " FROM account_publication_authorization_fences WHERE operation_id=?",
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                binding.operationId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "INSERT INTO account_publication_authorization_sources VALUES (?, ?, decode('01', 'hex'))",
                                binding.operationId(),
                                binding.sources().getFirst().key())))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void sqlPreservesJavaSourceLockOrderingForSupplementaryUnicodeScopes() {
    Context context = context();
    var original = binding();
    var binding =
        new AccountPublicationAuthorizationBinding(
            original.operationId(),
            original.fenceId(),
            original.input(),
            List.of(
                new SourceEvidence(
                    SourceKind.ISSUER, "issuer-\uE000", "1", "1", null, null, new byte[] {1}),
                new SourceEvidence(
                    SourceKind.ISSUER, "issuer-\uD800\uDC00", "1", "1", null, null, new byte[] {2}),
                original.sources().getFirst()));
    assertThat(tx(context, () -> context.publication().reserve(binding)).binding())
        .isEqualTo(binding.canonicalBytes());
    assertThat(tx(context, () -> context.publication().claimPublicationOrder(binding)).ordering())
        .isEqualTo(Ordering.PUBLICATION_ORDER);
    assertThat(tx(context, () -> context.draft().requestSourceChange(change(binding)))).isFalse();
  }

  @Test
  void missingCurrentSchemaFailsClosedAndUpgradePreservesOriginalDraftEvidence() {
    Context context = context("78");
    var publication = binding();
    var commit = publication.input().selection().selectedCommit();
    var draft =
        new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            commit.requestId(),
            commit.commitId(),
            UUID.randomUUID(),
            publication.input().actorAccountId(),
            publication.tenantId(),
            commit.target().canonicalVersionId(),
            commit.baseCommitId(),
            "1",
            commit.canonicalBytes(),
            commit.canonicalBytes(),
            commit.digest(),
            publication.sources());
    tx(context, () -> context.draft().reserve(draft));
    var before = tx(context, () -> context.draft().read(draft));
    assertThatThrownBy(
            () -> tx(context, () -> context.draft().requestSourceChange(change(publication))))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("account_publication_authorization_sources");
    assertThat(tx(context, () -> context.draft().read(draft)).ordering())
        .isEqualTo(before.ordering());
    migrate(context.source(), "latest");
    var after = tx(context, () -> context.draft().read(draft));
    assertThat(after.binding()).isEqualTo(before.binding());
    assertThat(after.ordering()).isEqualTo(before.ordering());
    assertThat(after.reservedAt()).isEqualTo(before.reservedAt());
    assertThat(after.orderedAt()).isEqualTo(before.orderedAt());
    assertThat(context.dsl().fetchCount(DSL.table("account_publication_authorization_fences")))
        .isZero();
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isZero();
  }

  @Test
  void v79PublicationOrderAndWaitingSourceChangeSurviveV80WithoutInventedOwnerResults() {
    Context context = context("79");
    var binding = binding();
    tx(context, () -> context.publication().reserve(binding));
    tx(context, () -> context.publication().claimPublicationOrder(binding));
    var change = change(binding);

    // V79 has no V80 owner-result table for the current repository settlement read. The failed
    // request is not a historical retained source change, and its transaction must leave no rows.
    assertThatThrownBy(() -> tx(context, () -> context.draft().requestSourceChange(change)))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("account_publication_authorization_owner_results");
    assertThat(context.dsl().fetchCount(DSL.table("account_draft_authorization_source_changes")))
        .isZero();
    assertThat(
            context.dsl().fetchCount(DSL.table("account_draft_authorization_changed_scopes")))
        .isZero();
    var orderedBeforeHistory = tx(context, () -> context.publication().read(binding));
    assertThat(orderedBeforeHistory.ordering()).isEqualTo(Ordering.PUBLICATION_ORDER);

    // Seed a valid historical V79 WAITING journal through its existing owner tables. These bytes
    // come from the exact source-change value and scopes; the SQL fixture does not claim owner
    // authorization or publication settlement.
    txRun(
        context,
        () -> {
          context
              .dsl()
              .execute(
                  "INSERT INTO account_draft_authorization_source_changes"
                      + " (change_id, binding, status, requested_at)"
                      + " VALUES (?, ?, 'WAITING', TIMESTAMPTZ '2026-10-06 12:34:56+00')",
                  change.changeId(),
                  change.canonicalBytes());
          for (var source : change.sources()) {
            context
                .dsl()
                .execute(
                    "INSERT INTO account_draft_authorization_changed_scopes"
                        + " (change_id, source_key) VALUES (?, ?)",
                    change.changeId(),
                    source.key());
          }
        });

    var retainedTables =
        Map.of(
            "account_draft_authorization_source_locks",
            retainedRows(context, "account_draft_authorization_source_locks"),
            "account_draft_authorization_source_changes",
            retainedRows(context, "account_draft_authorization_source_changes"),
            "account_draft_authorization_changed_scopes",
            retainedRows(context, "account_draft_authorization_changed_scopes"),
            "account_publication_authorization_fences",
            retainedRows(context, "account_publication_authorization_fences"),
            "account_publication_authorization_sources",
            retainedRows(context, "account_publication_authorization_sources"));
    var beforeChange =
        context
            .dsl()
            .fetchOne(
                "SELECT binding, status, requested_at, committed_at FROM"
                    + " account_draft_authorization_source_changes WHERE change_id = ?",
                change.changeId());
    assertThat(beforeChange).isNotNull();
    assertThat(beforeChange.get("binding", byte[].class)).isEqualTo(change.canonicalBytes());
    assertThat(beforeChange.get("status", String.class)).isEqualTo("WAITING");
    assertThat(
            context
                .dsl()
                .fetch(
                    "SELECT source_key FROM account_draft_authorization_changed_scopes"
                        + " WHERE change_id = ? ORDER BY source_key",
                    change.changeId())
                .getValues("source_key", String.class))
        .containsExactlyElementsOf(change.sources().stream().map(SourceEvidence::key).toList());
    var before = tx(context, () -> context.publication().read(binding));
    assertThat(before.ordering()).isEqualTo(Ordering.PUBLICATION_ORDER);

    migrate(context.source(), "latest");

    for (var retainedTable : retainedTables.entrySet()) {
      assertThat(retainedRows(context, retainedTable.getKey()))
          .as("complete retained rows in %s", retainedTable.getKey())
          .isEqualTo(retainedTable.getValue());
    }

    var after = tx(context, () -> context.publication().read(binding));
    assertThat(after.binding()).isEqualTo(before.binding());
    assertThat(after.ordering()).isEqualTo(before.ordering());
    assertThat(after.reservedAt()).isEqualTo(before.reservedAt());
    assertThat(after.orderedAt()).isEqualTo(before.orderedAt());
    var afterChange = tx(context, () -> context.draft().readSourceChange(change));
    assertThat(afterChange.binding()).isEqualTo(beforeChange.get("binding", byte[].class));
    assertThat(afterChange.status()).isEqualTo(beforeChange.get("status", String.class));
    assertThat(afterChange.requestedAt())
        .isEqualTo(beforeChange.get("requested_at", OffsetDateTime.class));
    assertThat(afterChange.committedAt())
        .isEqualTo(beforeChange.get("committed_at", OffsetDateTime.class));
    assertThat(
            context.dsl().fetchCount(DSL.table("account_publication_authorization_owner_results")))
        .isZero();
    assertThat(tx(context, () -> context.draft().sourceMutationPermitted(change))).isFalse();
  }

  private static List<String> retainedRows(Context context, String table) {
    return context
        .dsl()
        .fetch(
            "SELECT to_jsonb(retained)::text AS retained_row FROM "
                + table
                + " retained ORDER BY to_jsonb(retained)")
        .getValues("retained_row", String.class);
  }

  private static AccountPublicationAuthorizationBinding binding() {
    var input =
        new AccountPublicationAuthorizationBinding.PreallocationInput(
            UUID.randomUUID(), selection("notes"));
    return new AccountPublicationAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        input,
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                input.actorAccountId().toString(),
                "3",
                "4",
                null,
                null,
                new byte[] {7})));
  }

  private static SourceChange change(AccountPublicationAuthorizationBinding binding) {
    return new SourceChange(UUID.randomUUID(), binding.sources(), new byte[] {9});
  }

  private static byte[] structuralOperationBytes(AccountPublicationAuthorizationBinding binding) {
    var output = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(output, GameDesignPublicationOperationBinding.SCHEMA);
    DraftAuthorizationFenceBinding.frame(output, binding.canonicalBytes());
    // Deliberately stipulated nonempty checkpoint bytes: valid only for SQL structural tests.
    DraftAuthorizationFenceBinding.frame(output, new byte[] {1});
    return output.toByteArray();
  }

  private static byte[] structuralTerminalBytes(byte[] operationBytes, String outcome) {
    return structuralTerminalBytes(operationBytes, outcome, "2");
  }

  private static byte[] structuralTerminalBytes(
      byte[] operationBytes, String outcome, String epoch) {
    var output = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(output, GameDesignPublicationTerminalEvidence.SCHEMA);
    DraftAuthorizationFenceBinding.frame(output, operationBytes);
    DraftAuthorizationFenceBinding.frame(output, outcome);
    if ("PUBLISHED".equals(outcome)) {
      // Nonempty structural placeholders exercise SQL frame shape only, never release proof.
      DraftAuthorizationFenceBinding.frame(output, new byte[] {2});
      DraftAuthorizationFenceBinding.frame(output, epoch);
    }
    return output.toByteArray();
  }

  private static void insertStructuralOwnerResult(
      Context context,
      AccountPublicationAuthorizationBinding binding,
      String owner,
      String outcome,
      byte[] operationBytes,
      byte[] terminalBytes) {
    context
        .dsl()
        .execute(
            "INSERT INTO account_publication_authorization_owner_results"
                + " (operation_id, owner, outcome, operation_bytes, terminal_bytes) VALUES (?, ?, ?, ?, ?)",
            binding.operationId(),
            owner,
            outcome,
            operationBytes,
            terminalBytes);
  }

  private static void transitionSourceSql(Context context, SourceChange change, String status) {
    if ("SOURCE_COMMITTED".equals(status)) {
      context
          .dsl()
          .execute(
              "UPDATE account_draft_authorization_source_changes"
                  + " SET status='SOURCE_COMMITTED', committed_at=CURRENT_TIMESTAMP WHERE change_id=?",
              change.changeId());
      return;
    }
    if ("SOURCE_ABORTED".equals(status)) {
      context
          .dsl()
          .execute(
              "UPDATE account_draft_authorization_source_changes"
                  + " SET status='SOURCE_ABORTED', aborted_at=CURRENT_TIMESTAMP,"
                  + " abort_reason='DEFINITIVE_ABORT' WHERE change_id=?",
              change.changeId());
      return;
    }
    throw new IllegalArgumentException("Unsupported source transition status");
  }

  private Context context() {
    return context("latest");
  }

  private Context context(String target) {
    String schema = "publication_fence_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migrate(source, target);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(
        dsl,
        new PublicationAuthorizationFenceRepository(dsl),
        new DraftAuthorizationFenceRepository(dsl),
        transaction,
        source);
  }

  private static void migrate(DriverManagerDataSource source, String target) {
    String schema = source.getSchema();
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static <T> T tx(Context context, Supplier<T> work) {
    return context.transaction().execute(status -> work.get());
  }

  private static void txRun(Context context, Runnable work) {
    tx(
        context,
        () -> {
          work.run();
          return null;
        });
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private record Context(
      DSLContext dsl,
      PublicationAuthorizationFenceRepository publication,
      DraftAuthorizationFenceRepository draft,
      TransactionTemplate transaction,
      DriverManagerDataSource source) {}

  private static AuthoredDraftPublishSelectionBinding selection(String notes) {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            9007199254740993L,
            "tenant-source",
            9007199254740995L,
            "tenant-source",
            "NEW_GAME_ROW");
    var commit =
        DraftCommitBinding.create(
            target,
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.fromString("55555555-5555-4555-8555-555555555555"),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "{\"mutation\":\"fixture\"}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world",
                    "ROOM_SCOPE",
                    "room",
                    "900719925474099312345")));
    return AuthoredDraftPublishSelectionBinding.capture(
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            "publication-é",
            "900719925474099312345",
            notes,
            commit.requestId(),
            commit.commitId(),
            commit.digest()),
        target,
        commit,
        new AuthoredDraftPublishSelectionBinding.VisibilityFence(
            target,
            commit.requestId(),
            commit.commitId(),
            commit.digest(),
            "[]",
            OffsetDateTime.parse("2026-10-05T00:00:00Z")));
  }
}
