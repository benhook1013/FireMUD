package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
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
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerEvent;
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
  void freshAccountBaselineIsValidButGenericSaveHistoryCannotAuthorizeSecurityCurrentness()
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
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT generation FROM account_authority_generations "
                            + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                        account.getAccountUuid()))
                .get(0, Long.class))
        .isEqualTo(1L);

    account.setPasswordHash("changed-password-hash-not-for-event");
    transaction.executeWithoutResult(status -> accounts.save(account));
    account.setEmailVerified(true);
    transaction.executeWithoutResult(status -> accounts.save(account));

    String streamKey =
        AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX
            + "account/"
            + account.getAccountUuid();
    AccountEvent passwordReset =
        (AccountEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                new String(
                    transaction.execute(
                        status -> outbox.findEvent(streamKey, 1L).orElseThrow().payload()),
                    StandardCharsets.UTF_8));
    assertThat(passwordReset.mutationKinds()).containsExactly("PASSWORD_RESET");
    assertThat(passwordReset.canonicalJson()).doesNotContain("changed-password-hash-not-for-event");
    AccountEvent latest =
        (AccountEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                new String(
                    transaction.execute(
                        status -> outbox.findEvent(streamKey, 2L).orElseThrow().payload()),
                    StandardCharsets.UTF_8));
    assertThat(latest.mutationKinds()).containsExactly("EMAIL_LOGIN_ELIGIBILITY_CHANGED");
    assertThat(latest.canonicalJson()).doesNotContain("changed-password-hash-not-for-event");
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account source event schema is unsupported");

    Account rollbackAttempt = accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow();
    rollbackAttempt.setPasswordHash("rollback-only-password-hash");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      accounts.save(rollbackAttempt);
                      throw new IllegalStateException("force Account source event rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("force Account source event rollback");
    assertThat(accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow().getPasswordHash())
        .isEqualTo("changed-password-hash-not-for-event");
    var rolledBackEvent = transaction.execute(status -> outbox.findEvent(streamKey, 3L));
    assertThat(rolledBackEvent).isEmpty();
    assertThat(
            accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow().getLifecycleState())
        .isEqualTo(AccountLifecycleState.ACTIVE);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account source event schema is unsupported");
    Account lifecycleUpdate = accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow();
    lifecycleUpdate.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    transaction.executeWithoutResult(status -> accounts.save(lifecycleUpdate));
    assertThat(
            accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow().getLifecycleState())
        .isEqualTo(AccountLifecycleState.ACTIVE);
    assertThat(lifecycleUpdate.getLifecycleState()).isEqualTo(AccountLifecycleState.ACTIVE);
    var absentLifecycleEvent = transaction.execute(status -> outbox.findEvent(streamKey, 3L));
    assertThat(absentLifecycleEvent).isEmpty();
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account source event schema is unsupported");

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
  void genericSaveHistoryWithNullGlobalRoleDoesNotAuthorizeCanonicalCurrentness() {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    TransactionTemplate transaction = context.transaction();
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    AccountRepository accounts = new AccountRepository(dsl);
    Account account = account("null-role-source-evidence-" + UUID.randomUUID());
    account.setRole(null);

    transaction.executeWithoutResult(status -> accounts.save(account));
    var baseline =
        transaction.execute(
            status -> sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid()));
    assertThat(baseline.account().checkpoint().sequence()).isZero();

    account.setPasswordHash("changed-null-role-account-password-hash");
    transaction.executeWithoutResult(status -> accounts.save(account));
    String streamKey =
        AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX
            + "account/"
            + account.getAccountUuid();
    AccountAuthorityOutboxRepository.Event storedEvent =
        transaction.execute(status -> outbox.findEvent(streamKey, 1L).orElseThrow());
    AccountEvent event =
        (AccountEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                new String(storedEvent.payload(), StandardCharsets.UTF_8));

    assertThat(event.mutationKinds()).containsExactly("PASSWORD_RESET");
    assertThat(event.accountState().globalRole()).isNull();
    assertThat(event.canonicalJson()).contains("\"globalRole\":null");
    assertThat(event.canonicalJson()).doesNotContain("changed-null-role-account-password-hash");
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account source event schema is unsupported");
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
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT account_uuid FROM accounts WHERE username = ?", retained.getUsername()))
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
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM account_authority_source_records "
                            + "WHERE scope_kind = 'ISSUER' AND issuer_id = ?",
                        retainedIssuer))
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
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM account_authority_source_records "
                            + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                        retainedUuid))
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

    CountDownLatch participantsReady = new CountDownLatch(2);
    CountDownLatch startLockPhase = new CountDownLatch(1);
    CountDownLatch accountRowLocked = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);
    CompletableFuture<Integer> writerBackendPid = new CompletableFuture<>();
    CompletableFuture<Integer> snapshotBackendPid = new CompletableFuture<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> writer =
        executor.submit(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      writerBackendPid.complete(
                          Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid()"))
                              .get(0, Integer.class));
                      participantsReady.countDown();
                      await(startLockPhase, "Account lock-order phase was not started");
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
            () ->
                transaction.execute(
                    status -> {
                      snapshotBackendPid.complete(
                          Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid()"))
                              .get(0, Integer.class));
                      participantsReady.countDown();
                      await(startLockPhase, "Account lock-order phase was not started");
                      await(accountRowLocked, "Account writer did not acquire its row lock");
                      return sources.readCurrentIssuerAccountSources(
                          ISSUER, account.getAccountUuid());
                    }));

    try {
      assertThat(participantsReady.await(30, TimeUnit.SECONDS)).isTrue();
      Integer writerPid = writerBackendPid.get(5, TimeUnit.SECONDS);
      Integer readerPid = snapshotBackendPid.get(5, TimeUnit.SECONDS);
      startLockPhase.countDown();
      assertThat(accountRowLocked.await(30, TimeUnit.SECONDS)).isTrue();
      awaitLockWait(dsl, readerPid, writerPid);
      releaseWriter.countDown();
      writer.get(45, TimeUnit.SECONDS);
      assertThatThrownBy(() -> snapshot.get(45, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class)
          .hasRootCauseMessage("Account source event schema is unsupported");
      String streamKey =
          AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX
              + "account/"
              + account.getAccountUuid();
      AccountAuthorityOutboxRepository.Event committedEvent =
          transaction.execute(status -> outbox.findEvent(streamKey, 1L).orElseThrow());
      AccountEvent event =
          (AccountEvent)
              AccountAuthoritySourceEventV1Codec.verify(
                  new String(committedEvent.payload(), StandardCharsets.UTF_8));
      assertThat(event.accountId()).isEqualTo(account.getAccountUuid().toString());
      assertThat(event.outboxSequence()).isEqualTo("1");
      assertThat(event.mutationKinds()).containsExactly("PASSWORD_RESET");
      assertThat(event.canonicalJson()).doesNotContain("lock-order-updated-hash");
    } finally {
      startLockPhase.countDown();
      releaseWriter.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void concurrentCompositeReadersShareIssuerRowWhileIssuerAdvanceWaitsForTheirSnapshot()
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
    Account first = account("shared-issuer-lock-a-" + UUID.randomUUID());
    Account second = account("shared-issuer-lock-b-" + UUID.randomUUID());
    transaction.executeWithoutResult(status -> accounts.save(first));
    transaction.executeWithoutResult(status -> accounts.save(second));

    CountDownLatch readersReady = new CountDownLatch(2);
    CountDownLatch releaseReaders = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    CompletableFuture<Integer> firstReaderPid = new CompletableFuture<>();
    CompletableFuture<Integer> secondReaderPid = new CompletableFuture<>();
    CompletableFuture<Integer> writerPid = new CompletableFuture<>();
    ExecutorService executor = Executors.newFixedThreadPool(3);
    Future<AccountAuthorityGenerationRepository.CompositeSnapshot> firstReader =
        executor.submit(
            () ->
                transaction.execute(
                    status -> {
                      firstReaderPid.complete(
                          Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid()"))
                              .get(0, Integer.class));
                      var snapshot =
                          generations.readCompositeSnapshot(
                              ISSUER,
                              first.getAccountUuid(),
                              java.util.List.of(),
                              java.util.List.of());
                      readersReady.countDown();
                      await(releaseReaders, "Shared issuer readers were not released");
                      return snapshot;
                    }));
    Future<AccountAuthorityGenerationRepository.CompositeSnapshot> secondReader =
        executor.submit(
            () ->
                transaction.execute(
                    status -> {
                      secondReaderPid.complete(
                          Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid()"))
                              .get(0, Integer.class));
                      var snapshot =
                          generations.readCompositeSnapshot(
                              ISSUER,
                              second.getAccountUuid(),
                              java.util.List.of(),
                              java.util.List.of());
                      readersReady.countDown();
                      await(releaseReaders, "Shared issuer readers were not released");
                      return snapshot;
                    }));
    Future<?> issuerWriter =
        executor.submit(
            () -> {
              await(readersReady, "Both shared issuer readers did not acquire their snapshot");
              transaction.executeWithoutResult(
                  status -> {
                    writerPid.complete(
                        Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid()"))
                            .get(0, Integer.class));
                    writerStarted.countDown();
                    sources.appendIssuerAuthorityChange(
                        ISSUER, "SIGNER_COMPROMISE", "shared-reader-" + UUID.randomUUID());
                  });
            });

    try {
      assertThat(readersReady.await(30, TimeUnit.SECONDS)).isTrue();
      Integer firstPid = firstReaderPid.get(5, TimeUnit.SECONDS);
      Integer secondPid = secondReaderPid.get(5, TimeUnit.SECONDS);
      assertThat(writerStarted.await(30, TimeUnit.SECONDS)).isTrue();
      Integer writingPid = writerPid.get(5, TimeUnit.SECONDS);
      awaitLockWait(dsl, writingPid, firstPid, secondPid);

      releaseReaders.countDown();
      assertThat(firstReader.get(45, TimeUnit.SECONDS).issuer().generation()).isEqualTo(1L);
      assertThat(secondReader.get(45, TimeUnit.SECONDS).issuer().generation()).isEqualTo(1L);
      issuerWriter.get(45, TimeUnit.SECONDS);
      var readback =
          transaction.execute(
              status -> sources.readCurrentIssuerAccountSources(ISSUER, first.getAccountUuid()));
      assertThat(readback.issuer().generation()).isEqualTo(2L);
      assertThat(readback.issuer().sourceVersion()).isEqualTo(2L);
      assertThat(readback.issuer().checkpoint().sequence()).isEqualTo(1L);
    } finally {
      releaseReaders.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void concurrentIssuerWritersSerializeBeforeReadingAndAppendingSourceState() throws Exception {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    TransactionTemplate transaction = context.transaction();
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    AccountRepository accounts = new AccountRepository(dsl);
    Account account = account("issuer-writer-lock-" + UUID.randomUUID());
    transaction.executeWithoutResult(status -> accounts.save(account));

    CountDownLatch firstWriterLocked = new CountDownLatch(1);
    CountDownLatch secondWriterStarted = new CountDownLatch(1);
    CountDownLatch firstWriterAppended = new CountDownLatch(1);
    CountDownLatch allowFirstWriterCommit = new CountDownLatch(1);
    CompletableFuture<Integer> firstWriterPid = new CompletableFuture<>();
    CompletableFuture<Integer> secondWriterPid = new CompletableFuture<>();
    String firstRequestId = "issuer-writer-one-" + UUID.randomUUID();
    String secondRequestId = "issuer-writer-two-" + UUID.randomUUID();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> firstWriter =
        executor.submit(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      firstWriterPid.complete(
                          Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid()"))
                              .get(0, Integer.class));
                      var issuerRow =
                          dsl.fetchOne(
                              "SELECT generation FROM account_authority_generations "
                                  + "WHERE scope_kind = 'ISSUER' AND issuer_id = ? FOR UPDATE",
                              ISSUER);
                      assertThat(issuerRow).isNotNull();
                      firstWriterLocked.countDown();
                      await(secondWriterStarted, "Second issuer writer did not start");
                      sources.appendIssuerAuthorityChange(
                          ISSUER, "SIGNER_COMPROMISE", firstRequestId);
                      firstWriterAppended.countDown();
                      await(allowFirstWriterCommit, "First issuer writer was not released");
                    }));
    Future<?> secondWriter =
        executor.submit(
            () -> {
              await(firstWriterLocked, "First issuer writer did not acquire issuer row");
              transaction.executeWithoutResult(
                  status -> {
                    secondWriterPid.complete(
                        Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid()"))
                            .get(0, Integer.class));
                    secondWriterStarted.countDown();
                    sources.appendIssuerAuthorityChange(
                        ISSUER, "SIGNER_COMPROMISE", secondRequestId);
                  });
            });

    try {
      assertThat(firstWriterLocked.await(30, TimeUnit.SECONDS)).isTrue();
      Integer firstPid = firstWriterPid.get(30, TimeUnit.SECONDS);
      assertThat(secondWriterStarted.await(30, TimeUnit.SECONDS)).isTrue();
      Integer secondPid = secondWriterPid.get(30, TimeUnit.SECONDS);
      awaitLockWait(dsl, secondPid, firstPid);
      assertThat(firstWriterAppended.await(30, TimeUnit.SECONDS)).isTrue();

      allowFirstWriterCommit.countDown();
      firstWriter.get(60, TimeUnit.SECONDS);
      secondWriter.get(60, TimeUnit.SECONDS);

      var readback =
          transaction.execute(
              status -> sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid()));
      assertThat(readback.issuer().generation()).isEqualTo(3L);
      assertThat(readback.issuer().sourceVersion()).isEqualTo(3L);
      assertThat(readback.issuer().checkpoint().sequence()).isEqualTo(2L);
      var firstEvent =
          (IssuerEvent)
              AccountAuthoritySourceEventV1Codec.verify(
                  new String(
                      transaction.execute(
                          status ->
                              outbox
                                  .findEvent(readback.issuer().checkpoint().outboxStreamKey(), 1L)
                                  .orElseThrow()
                                  .payload()),
                      StandardCharsets.UTF_8));
      var secondEvent =
          (IssuerEvent)
              AccountAuthoritySourceEventV1Codec.verify(
                  new String(
                      transaction.execute(
                          status ->
                              outbox
                                  .findEvent(readback.issuer().checkpoint().outboxStreamKey(), 2L)
                                  .orElseThrow()
                                  .payload()),
                      StandardCharsets.UTF_8));
      assertThat(firstEvent.requestId()).isEqualTo(firstRequestId);
      assertThat(firstEvent.issuerAuthGeneration()).isEqualTo("2");
      assertThat(firstEvent.sourceVersion()).isEqualTo("2");
      assertThat(firstEvent.outboxSequence()).isEqualTo("1");
      assertThat(secondEvent.requestId()).isEqualTo(secondRequestId);
      assertThat(secondEvent.issuerAuthGeneration()).isEqualTo("3");
      assertThat(secondEvent.sourceVersion()).isEqualTo("3");
      assertThat(secondEvent.outboxSequence()).isEqualTo("2");
      assertThat(firstEvent.eventId()).isNotEqualTo(secondEvent.eventId());
    } finally {
      allowFirstWriterCommit.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static void await(CountDownLatch latch, String failureMessage) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException(failureMessage);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failureMessage, interrupted);
    }
  }

  private static void awaitLockWait(DSLContext dsl, Integer waitingPid, Integer... blockerPids)
      throws InterruptedException {
    if (blockerPids.length == 0) {
      throw new IllegalArgumentException("At least one expected blocker PID is required");
    }
    String expectedBlockerPredicates =
        java.util.stream.IntStream.range(0, blockerPids.length)
            .mapToObj(index -> "? = ANY(pg_blocking_pids(pid))")
            .collect(java.util.stream.Collectors.joining(" OR "));
    Object[] bindings = new Object[blockerPids.length + 1];
    System.arraycopy(blockerPids, 0, bindings, 0, blockerPids.length);
    bindings[blockerPids.length] = waitingPid;

    String lastWaitEventType = "<no pg_stat_activity row observed>";
    String lastWaitEvent = "<no pg_stat_activity row observed>";
    String lastBlockingPids = "<no pg_stat_activity row observed>";
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      var activity =
          dsl.fetchOne(
              "SELECT wait_event_type, wait_event, "
                  + "COALESCE(NULLIF(array_to_string(pg_blocking_pids(pid), ','), ''), '{}') "
                  + "AS blocking_pids, ("
                  + expectedBlockerPredicates
                  + ") AS blocked_by_expected "
                  + "FROM pg_stat_activity WHERE pid = ?",
              bindings);
      if (activity != null) {
        lastWaitEventType = activity.get("wait_event_type", String.class);
        lastWaitEvent = activity.get("wait_event", String.class);
        lastBlockingPids = activity.get("blocking_pids", String.class);
        Boolean blockedByExpected = activity.get("blocked_by_expected", Boolean.class);
        if ("Lock".equals(lastWaitEventType) && Boolean.TRUE.equals(blockedByExpected)) return;
      }
      Thread.sleep(25L);
    }
    throw new AssertionError(
        "Issuer authority writer did not wait on expected backend(s) "
            + java.util.Arrays.toString(blockerPids)
            + "; last wait_event_type="
            + lastWaitEventType
            + ", wait_event="
            + lastWaitEvent
            + ", blocking_pids="
            + lastBlockingPids);
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
