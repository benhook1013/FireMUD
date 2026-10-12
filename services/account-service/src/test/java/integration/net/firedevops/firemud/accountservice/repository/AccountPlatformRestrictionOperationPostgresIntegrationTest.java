package integration.net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.SourceKind;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountsRecord;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL proof that historical birth never becomes current restriction absence. */
class AccountPlatformRestrictionOperationPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startFixture() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopFixture() {
    POSTGRES.stop();
  }

  @Test
  void freshBirthDoesNotPopulateTheCurrentRestrictionProjection() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));

    assertThat(
            context.dsl().fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isZero();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            restrictionRepository(context.dsl())
                                .readCurrent(
                                    account.getAccountUuid(), Category.ACCOUNT_SECURITY_LOCK)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionProjectionUnavailableException
                .class);
  }

  @Test
  void fabricatedOwnerCorrelationCannotPromoteBirthToCurrentState() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));

    AccountPlatformRestrictionMutationRequest fabricated =
        context
            .transaction()
            .execute(
                status -> {
                  var birth =
                      new AccountPlatformRestrictionBirthRepository(context.dsl())
                          .readCurrentBirthSource(account.getAccountUuid());
                  var category = birth.accountSecurityLock();
                  return new AccountPlatformRestrictionMutationRequest(
                      UUID.randomUUID(),
                      account.getAccountUuid(),
                      Category.ACCOUNT_SECURITY_LOCK,
                      birth.currentAuthority().generation(),
                      birth.currentAuthority().sourceVersion(),
                      category.revision(),
                      category.enforcementEpoch(),
                      category.resultId(),
                      RestrictionState.RESTRICTED,
                      SourceKind.ACCOUNT_SECURITY_POLICY,
                      UUID.randomUUID(),
                      "sha256:" + "0".repeat(64));
                });

    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(status -> restrictionRepository(context.dsl()).commit(fabricated)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.OwnerAuthorizationUnresolvedException
                .class);
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_operations")))
        .isZero();
    assertThat(
            context.dsl().fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isZero();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            restrictionRepository(context.dsl())
                                .readCurrent(
                                    account.getAccountUuid(), Category.ACCOUNT_SECURITY_LOCK)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionProjectionUnavailableException
                .class);
  }

  @Test
  void retainedPreV137BirthDoesNotBecomeCurrentRestrictionAbsenceAfterMigration() {
    Context context = context("126.3");
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_births")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_projections")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_birth_outbox")))
        .isEqualTo(2);

    migrate(context, null);

    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_births")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_projections")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_birth_outbox")))
        .isEqualTo(2);
    assertThat(
            context
                .dsl()
                .fetch(
                    "SELECT restriction_state FROM account_platform_restriction_births "
                        + "WHERE account_uuid = ? ORDER BY category",
                    account.getAccountUuid())
                .getValues("restriction_state", String.class))
        .containsExactly("NONRESTRICTED", "NONRESTRICTED");
    assertThat(
            context.dsl().fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isZero();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            restrictionRepository(context.dsl())
                                .readCurrent(
                                    account.getAccountUuid(), Category.PLATFORM_ACCESS_BAN)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionProjectionUnavailableException
                .class);
  }

  @Test
  void nonrestrictedSecurityLockResultPreservesActiveLifecycleAndPunitiveBanIsIndependent() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));

    var birthRows =
        context
            .dsl()
            .fetch(
                "SELECT * FROM account_platform_restriction_births WHERE account_uuid = ? "
                    + "ORDER BY category",
                account.getAccountUuid())
            .intoMaps();
    var birthProjectionRows =
        context
            .dsl()
            .fetch(
                "SELECT * FROM account_platform_restriction_projections WHERE account_uuid = ? "
                    + "ORDER BY category",
                account.getAccountUuid())
            .intoMaps();
    var birthEventRows =
        context
            .dsl()
            .fetch(
                "SELECT * FROM account_platform_restriction_birth_outbox WHERE account_uuid = ? "
                    + "ORDER BY category",
                account.getAccountUuid())
            .intoMaps();

    commitDraft(context, account, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.NONRESTRICTED);

    assertThat(lifecycle(context, account)).isEqualTo("active");
    assertThat(
            context
                .dsl()
                .fetch(
                    "SELECT * FROM account_platform_restriction_births WHERE account_uuid = ? "
                        + "ORDER BY category",
                    account.getAccountUuid())
                .intoMaps())
        .isEqualTo(birthRows);
    assertThat(
            context
                .dsl()
                .fetch(
                    "SELECT * FROM account_platform_restriction_projections WHERE account_uuid = ? "
                        + "ORDER BY category",
                    account.getAccountUuid())
                .intoMaps())
        .isEqualTo(birthProjectionRows);
    assertThat(
            context
                .dsl()
                .fetch(
                    "SELECT * FROM account_platform_restriction_birth_outbox WHERE account_uuid = ? "
                        + "ORDER BY category",
                    account.getAccountUuid())
                .intoMaps())
        .isEqualTo(birthEventRows);
    assertThat(
            context
                .transaction()
                .execute(
                    status ->
                        restrictionRepository(context.dsl())
                            .readCurrent(account.getAccountUuid(), Category.ACCOUNT_SECURITY_LOCK))
                .state())
        .isEqualTo(RestrictionState.NONRESTRICTED);

    commitDraft(context, account, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.NONRESTRICTED);
    assertThat(lifecycle(context, account)).isEqualTo("active");
    assertThat(
            context
                .transaction()
                .execute(
                    status ->
                        restrictionRepository(context.dsl())
                            .readCurrent(account.getAccountUuid(), Category.ACCOUNT_SECURITY_LOCK))
                .revision())
        .isEqualTo(3L);

    commitDraft(context, account, Category.PLATFORM_ACCESS_BAN, RestrictionState.RESTRICTED);

    assertThat(lifecycle(context, account)).isEqualTo("active");
    assertThat(
            context
                .transaction()
                .execute(
                    status ->
                        restrictionRepository(context.dsl())
                            .readCurrent(account.getAccountUuid(), Category.PLATFORM_ACCESS_BAN))
                .state())
        .isEqualTo(RestrictionState.RESTRICTED);
  }

  @Test
  void restrictedSecurityLockOnActiveAccountProjectsLockedLifecycle() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));

    commitDraft(context, account, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.RESTRICTED);

    assertThat(lifecycle(context, account)).isEqualTo("security_locked");
    assertThat(
            context
                .transaction()
                .execute(
                    status ->
                        restrictionRepository(context.dsl())
                            .readCurrent(account.getAccountUuid(), Category.ACCOUNT_SECURITY_LOCK))
                .state())
        .isEqualTo(RestrictionState.RESTRICTED);

    commitDraft(context, account, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.NONRESTRICTED);

    assertThat(lifecycle(context, account)).isEqualTo("active");
    assertThat(
            context
                .transaction()
                .execute(
                    status ->
                        restrictionRepository(context.dsl())
                            .readCurrent(account.getAccountUuid(), Category.ACCOUNT_SECURITY_LOCK))
                .state())
        .isEqualTo(RestrictionState.NONRESTRICTED);
  }

  @Test
  void restrictedSecurityLockRejectsRetainedPendingDeletionAndDeletedAccountsWithoutWrites() {
    for (AccountLifecycleState lifecycleState :
        List.of(AccountLifecycleState.DEACTIVATED_PENDING_DELETE, AccountLifecycleState.DELETED)) {
      RetainedAccount retained = retainedLifecycleAccountAtV39(lifecycleState);
      Context context = retained.context();
      Account account = retained.account();
      assertThat(
              context
                  .dsl()
                  .fetch(
                      "SELECT 1 FROM account_platform_restriction_births WHERE account_uuid = ?",
                      account.getAccountUuid()))
          .isEmpty();
      Map<String, Object> before = restrictionWriteSnapshot(context.dsl());

      assertThatThrownBy(() -> attemptRestrictedDraft(context, account))
          .isInstanceOf(
              AccountPlatformRestrictionOperationRepository
                  .RestrictionProjectionUnavailableException.class);

      assertThat(lifecycle(context, account)).isEqualTo(lifecycleState.storageValue());
      assertThat(restrictionWriteSnapshot(context.dsl()))
          .usingRecursiveComparison()
          .isEqualTo(before);
    }
  }

  @Test
  void legacyLifecycleExitRejectsFinalRestrictedMismatchAndRollsBackItsReceipt() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));
    commitDraft(context, account, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.RESTRICTED);
    Map<String, Object> before = restrictionWriteSnapshot(context.dsl());

    assertThatThrownBy(
            () ->
                transitionLifecycleWithLegacyAuthorityReceipt(
                    context, account, AccountLifecycleState.DEACTIVATED_PENDING_DELETE))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Current Account security-lock projection and lifecycle disagree");

    assertThat(lifecycle(context, account)).isEqualTo("security_locked");
    assertThat(restrictionWriteSnapshot(context.dsl()))
        .usingRecursiveComparison()
        .isEqualTo(before);
  }

  @Test
  void nonrestrictedCurrentProjectionPreservesPendingDeletionAndDeletedThroughLegacyReceipts() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));
    commitDraft(context, account, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.NONRESTRICTED);

    transitionLifecycleWithLegacyAuthorityReceipt(
        context, account, AccountLifecycleState.DEACTIVATED_PENDING_DELETE);
    assertThat(lifecycle(context, account)).isEqualTo("deactivated_pending_delete");

    commitDraft(context, account, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.NONRESTRICTED);
    assertThat(lifecycle(context, account)).isEqualTo("deactivated_pending_delete");
    assertThat(currentRevision(context, account, Category.ACCOUNT_SECURITY_LOCK)).isEqualTo(3L);

    transitionLifecycleWithLegacyAuthorityReceipt(context, account, AccountLifecycleState.DELETED);
    assertThat(lifecycle(context, account)).isEqualTo("deleted");

    commitDraft(context, account, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.NONRESTRICTED);
    assertThat(lifecycle(context, account)).isEqualTo("deleted");
    assertThat(currentRevision(context, account, Category.ACCOUNT_SECURITY_LOCK)).isEqualTo(4L);
  }

  @Test
  void restrictedDraftWaitsForLifecycleRowAndRejectsTheCommittedNonactiveState() throws Exception {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));
    CountDownLatch lifecycleReceiptReady = new CountDownLatch(1);
    CountDownLatch allowLifecycleCommit = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> lifecycleTransition =
          executor.submit(
              () ->
                  transitionLifecycleWithLegacyAuthorityReceipt(
                      context,
                      account,
                      AccountLifecycleState.DEACTIVATED_PENDING_DELETE,
                      lifecycleReceiptReady,
                      allowLifecycleCommit));
      assertThat(lifecycleReceiptReady.await(10, TimeUnit.SECONDS)).isTrue();

      CountDownLatch restrictionAttemptStarted = new CountDownLatch(1);
      Future<?> restrictedDraft =
          executor.submit(
              () -> {
                restrictionAttemptStarted.countDown();
                attemptRestrictedDraft(context, account);
              });
      assertThat(restrictionAttemptStarted.await(10, TimeUnit.SECONDS)).isTrue();
      allowLifecycleCommit.countDown();
      lifecycleTransition.get(10, TimeUnit.SECONDS);

      try {
        restrictedDraft.get(10, TimeUnit.SECONDS);
        throw new AssertionError("Expected the locked-row precheck to reject a pending Account");
      } catch (ExecutionException failure) {
        assertThat(failure.getCause())
            .isInstanceOf(
                AccountPlatformRestrictionOperationRepository
                    .RestrictionProjectionUnavailableException.class);
      }
    } finally {
      allowLifecycleCommit.countDown();
      executor.shutdownNow();
    }

    assertThat(lifecycle(context, account)).isEqualTo("deactivated_pending_delete");
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_operations")))
        .isZero();
    assertThat(
            context.dsl().fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isZero();
  }

  @Test
  void deferredProjectionAndAccountGuardsReadFinalRowsWhenForcedIndependently() {
    Context projectionContext = context(null);
    Account projectionAccount = account();
    projectionContext
        .transaction()
        .executeWithoutResult(
            status -> new AccountRepository(projectionContext.dsl()).save(projectionAccount));
    Map<String, Object> projectionBefore = restrictionWriteSnapshot(projectionContext.dsl());

    assertThatThrownBy(
            () ->
                projectionContext
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          commitDraft(
                              projectionContext,
                              projectionAccount,
                              Category.ACCOUNT_SECURITY_LOCK,
                              RestrictionState.NONRESTRICTED);
                          transitionLifecycleWithLegacyAuthorityReceipt(
                              projectionContext,
                              projectionAccount,
                              AccountLifecycleState.SECURITY_LOCKED);
                          projectionContext
                              .dsl()
                              .execute(
                                  "SET CONSTRAINTS "
                                      + "account_platform_restriction_current_lifecycle_consistency "
                                      + "IMMEDIATE");
                        }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Current Account security-lock projection and lifecycle disagree");
    assertThat(restrictionWriteSnapshot(projectionContext.dsl()))
        .usingRecursiveComparison()
        .isEqualTo(projectionBefore);

    Context accountContext = context(null);
    Account account = account();
    accountContext
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(accountContext.dsl()).save(account));
    commitDraft(
        accountContext, account, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.NONRESTRICTED);
    Map<String, Object> accountBefore = restrictionWriteSnapshot(accountContext.dsl());

    assertThatThrownBy(
            () ->
                accountContext
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          transitionLifecycleWithLegacyAuthorityReceipt(
                              accountContext, account, AccountLifecycleState.SECURITY_LOCKED);
                          accountContext
                              .dsl()
                              .execute(
                                  "SET CONSTRAINTS "
                                      + "account_platform_restriction_account_lifecycle_consistency "
                                      + "IMMEDIATE");
                        }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Current Account security-lock projection and lifecycle disagree");
    assertThat(restrictionWriteSnapshot(accountContext.dsl()))
        .usingRecursiveComparison()
        .isEqualTo(accountBefore);
  }

  private static void commitDraft(
      Context context, Account account, Category category, RestrictionState state) {
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              var birthSource = new AccountPlatformRestrictionBirthRepository(context.dsl());
              var birth = birthSource.readCurrentBirthSource(account.getAccountUuid());
              var categoryBirth =
                  category == Category.ACCOUNT_SECURITY_LOCK
                      ? birth.accountSecurityLock()
                      : birth.platformAccessBan();
              var currentProjection =
                  context
                      .dsl()
                      .fetchOne(
                          "SELECT revision, enforcement_epoch, result_id FROM "
                              + "account_platform_restriction_current_projections "
                              + "WHERE account_uuid = ? AND category = ?",
                          account.getAccountUuid(),
                          category.storageValue());
              long expectedRevision =
                  currentProjection == null
                      ? categoryBirth.revision()
                      : currentProjection.get("revision", Long.class);
              long expectedEpoch =
                  currentProjection == null
                      ? categoryBirth.enforcementEpoch()
                      : currentProjection.get("enforcement_epoch", Long.class);
              UUID expectedResult =
                  currentProjection == null
                      ? categoryBirth.resultId()
                      : currentProjection.get("result_id", UUID.class);
              var sourceKind =
                  category == Category.ACCOUNT_SECURITY_LOCK
                      ? net.firedevops.firemud.accountservice.dto
                          .AccountPlatformRestrictionMutationRequest.SourceKind
                          .ACCOUNT_SECURITY_RECOVERY
                      : net.firedevops.firemud.accountservice.dto
                          .AccountPlatformRestrictionMutationRequest.SourceKind
                          .LOGGING_ADMIN_MODERATION;
              var request =
                  new AccountPlatformRestrictionMutationRequest(
                      UUID.randomUUID(),
                      account.getAccountUuid(),
                      category,
                      birth.currentAuthority().generation(),
                      birth.currentAuthority().sourceVersion(),
                      expectedRevision,
                      expectedEpoch,
                      expectedResult,
                      state,
                      sourceKind,
                      UUID.randomUUID(),
                      "sha256:" + "1".repeat(64));
              try {
                var repository = restrictionRepository(context.dsl());
                invokeDraftPersistence(repository, request);
              } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                    "Could not invoke the closed restriction draft", failure);
              }
            });
  }

  private static long currentRevision(Context context, Account account, Category category) {
    var row =
        context
            .dsl()
            .fetchOne(
                "SELECT revision FROM account_platform_restriction_current_projections "
                    + "WHERE account_uuid = ? AND category = ?",
                account.getAccountUuid(),
                category.storageValue());
    if (row == null) {
      throw new AssertionError("Expected current restriction revision");
    }
    Long revision = row.get("revision", Long.class);
    if (revision == null) {
      throw new AssertionError("Expected non-null restriction revision");
    }
    return revision;
  }

  /**
   * Exercises the retained V80 ACCOUNT_AUTHORITY_CHANGED receipt path as a SQL producer fixture.
   */
  private static void transitionLifecycleWithLegacyAuthorityReceipt(
      Context context, Account account, AccountLifecycleState nextLifecycle) {
    transitionLifecycleWithLegacyAuthorityReceipt(context, account, nextLifecycle, null, null);
  }

  private static void transitionLifecycleWithLegacyAuthorityReceipt(
      Context context,
      Account account,
      AccountLifecycleState nextLifecycle,
      CountDownLatch receiptReady,
      CountDownLatch allowCommit) {
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              AccountsRecord beforeRecord =
                  context
                      .dsl()
                      .selectFrom(ACCOUNTS)
                      .where(ACCOUNTS.ACCOUNT_UUID.eq(account.getAccountUuid()))
                      .fetchOne();
              if (beforeRecord == null) {
                throw new AssertionError("Expected Account before legacy lifecycle update");
              }
              context
                  .dsl()
                  .execute(
                      "UPDATE accounts SET lifecycle_state = ? WHERE account_uuid = ?",
                      nextLifecycle.storageValue(),
                      account.getAccountUuid());
              AccountsRecord afterRecord =
                  context
                      .dsl()
                      .selectFrom(ACCOUNTS)
                      .where(ACCOUNTS.ACCOUNT_UUID.eq(account.getAccountUuid()))
                      .fetchOne();
              if (afterRecord == null) {
                throw new AssertionError("Expected Account after legacy lifecycle update");
              }
              var evidence =
                  legacyAccountUpdateEvidence(
                      beforeRecord, afterRecord, account.getId(), account.getAccountUuid());
              var generations = new AccountAuthorityGenerationRepository(context.dsl());
              var outbox = new AccountAuthorityOutboxRepository(context.dsl());
              new AccountAuthoritySourceEvidenceRepository(context.dsl(), generations, outbox)
                  .recordAccountUpdate(evidence);
              if (receiptReady != null && allowCommit != null) {
                receiptReady.countDown();
                awaitCommitGate(allowCommit);
              }
            });
  }

  private static void awaitCommitGate(CountDownLatch allowCommit) {
    try {
      if (!allowCommit.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Lifecycle transaction commit gate timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Lifecycle transaction was interrupted", interrupted);
    }
  }

  private static AccountRepository.AccountUpdateEvidence legacyAccountUpdateEvidence(
      AccountsRecord beforeRecord, AccountsRecord afterRecord, long accountId, UUID accountUuid) {
    try {
      Method authorityState =
          AccountRepository.class.getDeclaredMethod("authorityState", AccountsRecord.class);
      authorityState.setAccessible(true);
      var before =
          (AccountRepository.AccountAuthorityState) authorityState.invoke(null, beforeRecord);
      var after =
          (AccountRepository.AccountAuthorityState) authorityState.invoke(null, afterRecord);
      Constructor<AccountRepository.AccountUpdateEvidence> constructor =
          AccountRepository.AccountUpdateEvidence.class.getDeclaredConstructor(
              long.class,
              UUID.class,
              String.class,
              AccountRepository.AccountAuthorityState.class,
              AccountRepository.AccountAuthorityState.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          accountId, accountUuid, UUID.randomUUID().toString(), before, after);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "Could not build the exact legacy Account update receipt", failure);
    }
  }

  private static void attemptRestrictedDraft(Context context, Account account) {
    AccountPlatformRestrictionMutationRequest request =
        new AccountPlatformRestrictionMutationRequest(
            UUID.randomUUID(),
            account.getAccountUuid(),
            Category.ACCOUNT_SECURITY_LOCK,
            1L,
            1L,
            1L,
            1L,
            UUID.randomUUID(),
            RestrictionState.RESTRICTED,
            SourceKind.ACCOUNT_SECURITY_POLICY,
            UUID.randomUUID(),
            "sha256:" + "2".repeat(64));
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              try {
                invokeDraftPersistence(restrictionRepository(context.dsl()), request);
              } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                    "Could not invoke the closed restriction draft", failure);
              }
            });
  }

  private static void invokeDraftPersistence(
      AccountPlatformRestrictionOperationRepository repository,
      AccountPlatformRestrictionMutationRequest request)
      throws ReflectiveOperationException {
    var persist =
        AccountPlatformRestrictionOperationRepository.class.getDeclaredMethod(
            "persistWithoutOwnerProof", AccountPlatformRestrictionMutationRequest.class);
    persist.setAccessible(true);
    try {
      persist.invoke(repository, request);
    } catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw failure;
    }
  }

  private static Map<String, Object> restrictionWriteSnapshot(DSLContext dsl) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    for (String table :
        List.of(
            "account_authority_generations",
            "accounts",
            "account_authority_source_records",
            "account_authority_issuance_fences",
            "account_authority_outbox_streams",
            "account_authority_outbox_events",
            "account_platform_restriction_operations",
            "account_platform_restriction_revisions",
            "account_platform_restriction_current_projections",
            "account_platform_restriction_births",
            "account_platform_restriction_projections",
            "account_platform_restriction_birth_outbox")) {
      snapshot.put(table, dsl.fetch("SELECT * FROM " + table).intoMaps());
    }
    return snapshot;
  }

  private static RetainedAccount retainedLifecycleAccountAtV39(
      AccountLifecycleState lifecycleState) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String schema = "restriction_retained_" + suffix;
    var dataSource = POSTGRES.dataSource(schema);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    Context context =
        new Context(
            schema,
            dataSource,
            dsl,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    migrate(context, "39");
    var inserted =
        dsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash, role, lifecycle_state) "
                + "VALUES (?, ?, ?, 'player', ?) RETURNING id",
            "legacy-" + suffix,
            suffix + "@example.test",
            "retained-password-hash",
            lifecycleState.storageValue());
    if (inserted == null) {
      throw new AssertionError("Expected retained pre-source Account row");
    }
    Long id = inserted.get("id", Long.class);
    if (id == null) {
      throw new AssertionError("Expected retained pre-source Account ID");
    }
    migrate(context, null);
    return new RetainedAccount(context, new AccountRepository(dsl).findById(id).orElseThrow());
  }

  private record RetainedAccount(Context context, Account account) {}

  private static String lifecycle(Context context, Account account) {
    var row =
        context
            .dsl()
            .fetchOne(
                "SELECT lifecycle_state FROM accounts WHERE account_uuid = ?",
                account.getAccountUuid());
    if (row == null) {
      throw new AssertionError("Expected lifecycle row for account " + account.getAccountUuid());
    }
    return row.get("lifecycle_state", String.class);
  }

  private static AccountPlatformRestrictionOperationRepository restrictionRepository(
      DSLContext dsl) {
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    return new AccountPlatformRestrictionOperationRepository(
        dsl,
        generations,
        outbox,
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox),
        new AccountPlatformRestrictionBirthRepository(dsl));
  }

  private static Account account() {
    Account account = new Account();
    String suffix = UUID.randomUUID().toString();
    account.setUsername(suffix);
    account.setEmail(suffix + "@example.test");
    account.setPasswordHash("restriction-current-test-hash");
    account.setRole("player");
    return account;
  }

  private static Context context(String target) {
    String schema = "restriction_current_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = POSTGRES.dataSource(schema);
    Context context =
        new Context(
            schema,
            dataSource,
            DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    migrate(context, target);
    return context;
  }

  private static void migrate(Context context, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(context.dataSource())
            .schemas(context.schema())
            .defaultSchema(context.schema())
            .locations("classpath:db/migration")
            .placeholders(Map.of("serviceSchema", context.schema()));
    if (target != null) configuration.target(target);
    configuration.load().migrate();
  }

  private record Context(
      String schema,
      org.springframework.jdbc.datasource.DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction) {}
}
