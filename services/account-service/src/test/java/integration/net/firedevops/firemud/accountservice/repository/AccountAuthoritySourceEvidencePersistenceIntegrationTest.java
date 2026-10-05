package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountEvent;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL proof for owner-created sequence-zero baselines and atomic source events. */
class AccountAuthoritySourceEvidencePersistenceIntegrationTest {
  private static final String ISSUER = "firemud-account-service";
  private static final String SCHEMA_PREFIX = "account_source_evidence_proof";
  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void configureDatabase() {
    postgres.start();
  }

  @AfterAll
  static void stopOwnedContainer() {
    postgres.stop();
  }

  @Test
  void freshAccountBaselineAndSecurityMutationsCommitExactSourceEvidence() throws Exception {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    TransactionTemplate transaction = context.transaction();
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    AccountRepository accounts = new AccountRepository(dsl);
    Account account = account("source-evidence-" + UUID.randomUUID());

    transaction.executeWithoutResult(status -> accounts.save(account));
    var baseline =
        transaction.execute(
            status -> sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid()));

    assertThat(baseline.issuer().generation()).isEqualTo(1L);
    assertThat(baseline.issuer().checkpoint().sequence()).isZero();
    assertThat(baseline.issuer().checkpoint().sourceEventId()).isEmpty();
    assertThat(baseline.issuer().initializationProvenance()).isEqualTo("ISSUER_SCOPE_INSERT");
    assertThat(baseline.issuer().initializationTransactionId()).isPositive();
    assertThat(baseline.account().generation()).isEqualTo(1L);
    assertThat(baseline.account().sourceVersion()).isEqualTo(1L);
    assertThat(baseline.account().issuanceFence().value()).isEqualTo(1L);
    assertThat(baseline.account().checkpoint().sequence()).isZero();
    assertThat(baseline.account().accountSourceNumericId()).isEqualTo(account.getId());
    assertThat(baseline.account().accountUuidProvenance()).isEqualTo("ACCOUNT_REPOSITORY_INSERT");
    assertThat(baseline.account().initializationTransactionId())
        .isEqualTo(baseline.account().accountRepositoryInsertTransactionId());
    assertThat(baseline.account().accountSecurityCutoff()).isEmpty();

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        dsl.execute(
                            "UPDATE account_authority_generations "
                                + "SET generation = 2, source_version = 2 "
                                + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                            account.getAccountUuid())))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            dsl.fetchOne(
                    "SELECT generation FROM account_authority_generations "
                        + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    account.getAccountUuid())
                .get(0, Long.class))
        .isEqualTo(1L);

    account.setPasswordHash("changed-password-hash-not-for-event");
    transaction.executeWithoutResult(status -> accounts.save(account));
    account.setEmailVerified(true);
    transaction.executeWithoutResult(status -> accounts.save(account));

    var changed =
        transaction.execute(
            status -> sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid()));
    assertThat(changed.account().generation()).isEqualTo(3L);
    assertThat(changed.account().sourceVersion()).isEqualTo(3L);
    assertThat(changed.account().issuanceFence().value()).isEqualTo(3L);
    assertThat(changed.account().checkpoint().sequence()).isEqualTo(2L);
    assertThat(changed.account().accountSecurityCutoff()).isPresent();
    assertThat(changed.account().accountSecurityCutoff().orElseThrow().accountAuthorityGeneration())
        .isEqualTo("3");
    assertThat(changed.account().accountSecurityCutoff().orElseThrow().outboxSequence())
        .isEqualTo("2");
    AccountEvent passwordReset =
        (AccountEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                new String(
                    transaction.execute(
                        status ->
                            outbox
                                .findEvent(changed.account().checkpoint().outboxStreamKey(), 1L)
                                .orElseThrow()
                                .payload()),
                    java.nio.charset.StandardCharsets.UTF_8));
    assertThat(passwordReset.mutationKinds()).containsExactly("PASSWORD_RESET");
    assertThat(passwordReset.canonicalJson()).doesNotContain("changed-password-hash-not-for-event");
    AccountEvent latest =
        (AccountEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                new String(
                    transaction.execute(
                        status ->
                            outbox
                                .findEvent(changed.account().checkpoint().outboxStreamKey(), 2L)
                                .orElseThrow()
                                .payload()),
                    java.nio.charset.StandardCharsets.UTF_8));
    assertThat(latest.mutationKinds()).containsExactly("EMAIL_LOGIN_ELIGIBILITY_CHANGED");
    assertThat(latest.accountSecurityCutoff().outboxSequence()).isEqualTo("2");
    assertThat(latest.canonicalJson()).doesNotContain("changed-password-hash-not-for-event");

    Account rollbackAttempt = accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow();
    rollbackAttempt.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      accounts.save(rollbackAttempt);
                      throw new IllegalStateException("force lifecycle evidence rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("force lifecycle evidence rollback");
    var afterLifecycleRollback =
        transaction.execute(
            status -> sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid()));
    assertThat(
            accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow().getLifecycleState())
        .isEqualTo(AccountLifecycleState.ACTIVE);
    assertThat(afterLifecycleRollback.account().generation()).isEqualTo(3L);
    assertThat(afterLifecycleRollback.account().issuanceFence().value()).isEqualTo(3L);
    assertThat(afterLifecycleRollback.account().checkpoint().sequence()).isEqualTo(2L);
    assertThat(
            afterLifecycleRollback.account().accountSecurityCutoff().orElseThrow().outboxSequence())
        .isEqualTo("2");
    Optional<AccountAuthorityOutboxRepository.Event> rolledBackEvent =
        transaction.execute(
            status -> outbox.findEvent(changed.account().checkpoint().outboxStreamKey(), 3L));
    assertThat(rolledBackEvent).isEmpty();
    Account lockedAccount = accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow();
    lockedAccount.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    transaction.executeWithoutResult(status -> accounts.save(lockedAccount));
    var lockedSnapshot =
        transaction.execute(
            status -> sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid()));
    assertThat(
            accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow().getLifecycleState())
        .isEqualTo(AccountLifecycleState.SECURITY_LOCKED);
    assertThat(lockedSnapshot.account().generation()).isEqualTo(4L);
    assertThat(lockedSnapshot.account().sourceVersion()).isEqualTo(4L);
    assertThat(lockedSnapshot.account().issuanceFence().value()).isEqualTo(4L);
    assertThat(lockedSnapshot.account().issuanceFence().sourceVersion()).isEqualTo(4L);
    assertThat(lockedSnapshot.account().checkpoint().sequence()).isEqualTo(3L);
    assertThat(
            lockedSnapshot
                .account()
                .accountSecurityCutoff()
                .orElseThrow()
                .accountAuthorityGeneration())
        .isEqualTo("4");
    assertThat(lockedSnapshot.account().accountSecurityCutoff().orElseThrow().outboxStreamKey())
        .isEqualTo("account:auth-authority:v1:account/" + account.getAccountUuid());
    assertThat(lockedSnapshot.account().accountSecurityCutoff().orElseThrow().outboxSequence())
        .isEqualTo("3");
    AccountEvent lifecycleEvent =
        (AccountEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                new String(
                    transaction.execute(
                        status ->
                            outbox
                                .findEvent(
                                    lockedSnapshot.account().checkpoint().outboxStreamKey(), 3L)
                                .orElseThrow()
                                .payload()),
                    StandardCharsets.UTF_8));
    assertThat(lifecycleEvent.mutationKinds()).containsExactly("LIFECYCLE_STATE_CHANGED");
    assertThat(lifecycleEvent.accountState().lifecycleState()).isEqualTo("SECURITY_LOCKED");

    assertThatThrownBy(() -> accounts.delete(account))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pending-deletion retention workflow");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        dsl.execute(
                            "DELETE FROM accounts WHERE account_uuid = ?",
                            account.getAccountUuid())))
        .isInstanceOf(DataAccessException.class);
    assertThat(accounts.findByAccountUuid(account.getAccountUuid())).isPresent();
  }

  @Test
  void retainedOrForgedEmptyStreamsDoNotBecomeFreshSourceBaselines() {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    TransactionTemplate transaction = context.transaction();
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);

    Account retained = account("retained-" + UUID.randomUUID());
    dsl.fetchOne(
        "INSERT INTO accounts (username, email, password_hash, role) VALUES (?, ?, ?, ?) "
            + "RETURNING id",
        retained.getUsername(),
        retained.getEmail(),
        retained.getPasswordHash(),
        retained.getRole());
    UUID retainedUuid =
        dsl.fetchOne("SELECT account_uuid FROM accounts WHERE username = ?", retained.getUsername())
            .get("account_uuid", UUID.class);
    String retainedIssuer = "retained-issuer-" + UUID.randomUUID();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      dsl.execute(
                          "INSERT INTO account_authority_generations "
                              + "(scope_kind, issuer_id, generation, source_version) "
                              + "VALUES ('ISSUER', ?, 1, 1)",
                          retainedIssuer);
                      sources.initializeIssuerIfAbsent(retainedIssuer);
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            dsl.fetchOne(
                    "SELECT count(*) FROM account_authority_source_records "
                        + "WHERE scope_kind = 'ISSUER' AND issuer_id = ?",
                    retainedIssuer)
                .get(0, Long.class))
        .isZero();

    transaction.executeWithoutResult(status -> sources.initializeIssuerIfAbsent(ISSUER));

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> {
                      dsl.execute(
                          "INSERT INTO account_authority_generations "
                              + "(scope_kind, account_uuid, generation, source_version) "
                              + "VALUES ('ACCOUNT', ?, 1, 1)",
                          retainedUuid);
                      dsl.execute(
                          "INSERT INTO account_authority_issuance_fences "
                              + "(account_uuid, issuance_fence, source_version) VALUES (?, 1, 1)",
                          retainedUuid);
                      return sources.readCurrentIssuerAccountSources(ISSUER, retainedUuid);
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            dsl.fetchOne(
                    "SELECT count(*) FROM account_authority_source_records "
                        + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    retainedUuid)
                .get(0, Long.class))
        .isZero();

    String forgedKey = "account:auth-authority:v1:issuer/forged-empty-" + UUID.randomUUID();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        dsl.execute(
                            "INSERT INTO account_authority_outbox_streams "
                                + "(outbox_stream_key, last_sequence) VALUES (?, 0)",
                            forgedKey)))
        .isInstanceOf(RuntimeException.class)
        .satisfies(
            failure -> {
              Throwable rootCause = failure;
              while (rootCause.getCause() != null) rootCause = rootCause.getCause();
              assertThat(rootCause.getMessage()).contains("fresh source-baseline provenance");
            });
  }

  @Test
  void concurrentAccountMutationAndAuthorityReadUseAccountRowBeforeGenerationLocks()
      throws Exception {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    TransactionTemplate transaction = context.transaction();
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    AccountRepository accounts = new AccountRepository(dsl);
    Account account = account("lock-order-" + UUID.randomUUID());
    transaction.executeWithoutResult(status -> accounts.save(account));

    CountDownLatch accountRowLocked = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);
    CompletableFuture<Integer> snapshotBackendPid = new CompletableFuture<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> writer =
        executor.submit(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      dsl.fetchOne(
                          "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE",
                          account.getAccountUuid());
                      accountRowLocked.countDown();
                      await(releaseWriter, "Account writer was not released");
                      Account updated =
                          accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow();
                      updated.setPasswordHash("lock-order-updated-hash");
                      accounts.save(updated);
                    }));
    Future<AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot> snapshot =
        executor.submit(
            () -> {
              await(accountRowLocked, "Account writer did not acquire its row lock");
              return transaction.execute(
                  status -> {
                    snapshotBackendPid.complete(
                        dsl.fetchOne("SELECT pg_backend_pid()").get(0, Integer.class));
                    return sources.readCurrentIssuerAccountSources(
                        ISSUER, account.getAccountUuid());
                  });
            });

    try {
      assertThat(accountRowLocked.await(5, TimeUnit.SECONDS)).isTrue();
      Integer readerPid = snapshotBackendPid.get(5, TimeUnit.SECONDS);
      awaitLockWait(dsl, readerPid);
      releaseWriter.countDown();
      writer.get(10, TimeUnit.SECONDS);
      var readback = snapshot.get(10, TimeUnit.SECONDS);
      assertThat(readback.account().generation()).isEqualTo(2L);
      assertThat(readback.account().issuanceFence().value()).isEqualTo(2L);
      assertThat(readback.account().checkpoint().sequence()).isEqualTo(1L);
    } finally {
      releaseWriter.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static void await(CountDownLatch latch, String failureMessage) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(failureMessage);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failureMessage, interrupted);
    }
  }

  private static void awaitLockWait(DSLContext dsl, Integer backendPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var activity =
          dsl.fetchOne("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", backendPid);
      String waitEventType = activity == null ? null : activity.get(0, String.class);
      if ("Lock".equals(waitEventType)) return;
      Thread.sleep(10L);
    }
    throw new AssertionError("Authority snapshot never waited on the already locked Account row");
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        dsl, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private static Account account(String suffix) {
    Account account = new Account();
    String uniqueUuidSuffix = suffix.substring(suffix.length() - 36);
    account.setUsername("account-" + uniqueUuidSuffix);
    account.setEmail(suffix.replace("-", "") + "@example.test");
    account.setPasswordHash("initial-hash-" + suffix);
    account.setRole("player");
    return account;
  }

  private record TestContext(DSLContext dsl, TransactionTemplate transaction) {}
}
