package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
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
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
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
  void preV40RetainedPasswordResetFailsTypedAndRollsBackWithoutInventingSource() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .target("39")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    String suffix = UUID.randomUUID().toString();
    String originalHash = "retained-password-" + suffix;
    Long accountId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO accounts (username, email, password_hash, role) VALUES (?, ?, ?, ?) RETURNING id",
                    "retained-" + suffix,
                    suffix + "@example.test",
                    originalHash,
                    "player"))
            .get(0, Long.class);
    String tokenValue = "retained-reset-" + suffix;
    var expiry = java.time.LocalDateTime.now().plusHours(1);
    dsl.execute(
        "INSERT INTO password_reset_token (account_id, token, expires_at) VALUES (?, ?, ?)",
        accountId,
        tokenValue,
        expiry);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .load()
        .migrate();
    var accounts = new AccountRepository(dsl);
    var resetTokens =
        new net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository(dsl);
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    var manager = new DataSourceTransactionManager(dataSource);
    var transaction = new TransactionTemplate(manager);
    var service =
        new net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl(
            accounts,
            generations,
            sourceEvidence,
            outbox,
            new net.firedevops.firemud.accountservice.repository
                .AccountPasswordResetOperationRepository(dsl),
            new net.firedevops.firemud.accountservice.repository
                .AccountLogoutAllOperationRepository(dsl),
            new net.firedevops.firemud.accountservice.repository
                .AccountSecurityStateOperationRepository(dsl),
            new net.firedevops.firemud.accountservice.service
                .AccountPasswordResetDraftSourceChangeRepository(dsl),
            null, // audit outbox
            null,
            null,
            null,
            null,
            null,
            null, // account mapper, profiles, payments, subscriptions, external identities
            null,
            null,
            null,
            null,
            null,
            resetTokens,
            null, // verification token repository
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            manager); // remote clients/JWT/session are not used by reset
    UUID accountUuid = accounts.findById(accountId).orElseThrow().getAccountUuid();
    var beforeToken =
        Objects.requireNonNull(
                dsl.fetchOne("SELECT * FROM password_reset_token WHERE token = ?", tokenValue),
                "expected retained reset token")
            .intoMap();
    var beforeEvents = dsl.fetch("SELECT * FROM account_authority_outbox_events").intoMaps();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        service.completePasswordReset(
                            new net.firedevops.firemud.accountservice.dto
                                .CompletePasswordResetRequest(tokenValue, "replacement-password"))))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
    assertThat(accounts.findById(accountId).orElseThrow().getPasswordHash())
        .isEqualTo(originalHash);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT * FROM password_reset_token WHERE token = ?", tokenValue),
                    "expected retained reset token after rollback")
                .intoMap())
        .isEqualTo(beforeToken);
    assertThat(dsl.fetch("SELECT * FROM account_authority_outbox_events").intoMaps())
        .isEqualTo(beforeEvents);
    assertThat(
            dsl.fetchCount(
                dsl.selectFrom("account_authority_source_records")
                    .where("account_uuid = ?", accountUuid)))
        .isZero();
    assertThat(
            dsl.fetchCount(
                dsl.selectFrom("account_authority_generations")
                    .where("scope_kind = 'ACCOUNT' AND account_uuid = ?", accountUuid)))
        .isZero();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT account_repository_insert_transaction_id FROM accounts WHERE id = ?",
                        accountId),
                    "expected account after reset rollback")
                .get(0, Long.class))
        .isNull();
  }

  @Test
  void freshAccountGenericSecurityEventsRemainNonCurrentWithExactSourceEvidence() throws Exception {
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

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid())))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class)
        .hasMessage("Account authority source evidence is unavailable or inconsistent");
    String accountStream = baseline.account().checkpoint().outboxStreamKey();
    var changedState =
        transaction.execute(
            status -> generations.read(AuthorityScope.account(account.getAccountUuid())));
    var changedCheckpoint =
        transaction.execute(status -> outbox.readCheckpoint(accountStream).orElseThrow());
    assertThat(changedState.generation()).isEqualTo(3L);
    assertThat(changedState.sourceVersion()).isEqualTo(3L);
    assertThat(changedState.issuanceFence().value()).isEqualTo(3L);
    assertThat(changedState.issuanceFence().sourceVersion()).isEqualTo(3L);
    assertThat(changedCheckpoint.outboxSequence()).isEqualTo(2L);
    AccountEvent passwordEvent =
        (AccountEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                new String(
                    transaction.execute(
                        status -> outbox.findEvent(accountStream, 1L).orElseThrow().payload()),
                    java.nio.charset.StandardCharsets.UTF_8));
    assertThat(passwordEvent.mutationKinds()).containsExactly("PASSWORD_RESET");
    AccountAuthorityOutboxRepository.Event firstEvent =
        transaction.execute(status -> outbox.findEvent(accountStream, 1L).orElseThrow());
    assertThat(passwordEvent.outboxStreamKey()).isEqualTo(accountStream);
    assertThat(passwordEvent.outboxSequence()).isEqualTo("1");
    assertThat(passwordEvent.eventId()).isEqualTo(firstEvent.eventId());
    assertThat(passwordEvent.eventDigest()).isEqualTo(firstEvent.eventDigest());
    assertThat(passwordEvent.accountAuthorityGeneration()).isEqualTo("2");
    assertThat(passwordEvent.sourceVersion()).isEqualTo("2");
    assertThat(passwordEvent.issuanceFence()).isEqualTo("2");
    assertThat(passwordEvent.canonicalJson()).doesNotContain("changed-password-hash-not-for-event");
    AccountEvent eligibilityEvent =
        (AccountEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                new String(
                    transaction.execute(
                        status -> outbox.findEvent(accountStream, 2L).orElseThrow().payload()),
                    java.nio.charset.StandardCharsets.UTF_8));
    assertThat(eligibilityEvent.mutationKinds()).containsExactly("EMAIL_LOGIN_ELIGIBILITY_CHANGED");
    assertThat(eligibilityEvent.outboxStreamKey()).isEqualTo(accountStream);
    assertThat(eligibilityEvent.outboxSequence()).isEqualTo("2");
    AccountAuthorityOutboxRepository.Event latestStoredEvent =
        transaction.execute(status -> outbox.findEvent(accountStream, 2L).orElseThrow());
    assertThat(eligibilityEvent.eventId()).isEqualTo(latestStoredEvent.eventId());
    assertThat(eligibilityEvent.eventDigest()).isEqualTo(latestStoredEvent.eventDigest());
    assertThat(latestStoredEvent.eventId()).isEqualTo(changedCheckpoint.sourceEventId());
    assertThat(latestStoredEvent.eventDigest()).isEqualTo(changedCheckpoint.sourceEventDigest());
    assertThat(eligibilityEvent.accountAuthorityGeneration()).isEqualTo("3");
    assertThat(eligibilityEvent.sourceVersion()).isEqualTo("3");
    assertThat(eligibilityEvent.issuanceFence()).isEqualTo("3");
    assertThat(eligibilityEvent.accountSecurityCutoff().outboxSequence()).isEqualTo("2");
    assertThat(eligibilityEvent.canonicalJson())
        .doesNotContain("changed-password-hash-not-for-event");

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
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid())))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class)
        .hasMessage("Account authority source evidence is unavailable or inconsistent");
    var afterRollback =
        transaction.execute(
            status -> generations.read(AuthorityScope.account(account.getAccountUuid())));
    var checkpointAfterRollback =
        transaction.execute(status -> outbox.readCheckpoint(accountStream).orElseThrow());
    assertThat(checkpointAfterRollback).isEqualTo(changedCheckpoint);
    assertThat(
            accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow().getLifecycleState())
        .isEqualTo(AccountLifecycleState.ACTIVE);
    assertThat(afterRollback.generation()).isEqualTo(3L);
    assertThat(afterRollback.sourceVersion()).isEqualTo(3L);
    assertThat(afterRollback.issuanceFence().value()).isEqualTo(3L);
    assertThat(afterRollback.issuanceFence().sourceVersion()).isEqualTo(3L);
    assertThat(checkpointAfterRollback.outboxSequence()).isEqualTo(2L);
    Optional<AccountAuthorityOutboxRepository.Event> rolledBackEvent =
        transaction.execute(status -> outbox.findEvent(accountStream, 3L));
    assertThat(rolledBackEvent).isEmpty();
    Account lifecycleUpdate = accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow();
    lifecycleUpdate.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    assertThatThrownBy(
            () -> transaction.executeWithoutResult(status -> accounts.save(lifecycleUpdate)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lifecycle changes are unavailable");
    var afterLifecycleUpdate =
        transaction.execute(
            status -> generations.read(AuthorityScope.account(account.getAccountUuid())));
    var checkpointAfterLifecycleRejection =
        transaction.execute(status -> outbox.readCheckpoint(accountStream).orElseThrow());
    assertThat(checkpointAfterLifecycleRejection).isEqualTo(changedCheckpoint);
    Account persistedAfterLifecycleRejection =
        accounts.findByAccountUuid(account.getAccountUuid()).orElseThrow();
    assertThat(persistedAfterLifecycleRejection.getLifecycleState())
        .isEqualTo(AccountLifecycleState.ACTIVE);
    assertThat(persistedAfterLifecycleRejection.getPasswordHash())
        .isEqualTo(account.getPasswordHash());
    assertThat(lifecycleUpdate.getLifecycleState())
        .isEqualTo(AccountLifecycleState.SECURITY_LOCKED);
    assertThat(afterLifecycleUpdate.generation()).isEqualTo(3L);
    assertThat(afterLifecycleUpdate.sourceVersion()).isEqualTo(3L);
    assertThat(afterLifecycleUpdate.issuanceFence().value()).isEqualTo(3L);
    assertThat(afterLifecycleUpdate.issuanceFence().sourceVersion()).isEqualTo(3L);
    assertThat(checkpointAfterLifecycleRejection.outboxSequence()).isEqualTo(2L);
    var absentLifecycleEvent = transaction.execute(status -> outbox.findEvent(accountStream, 3L));
    assertThat(absentLifecycleEvent).isEmpty();

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
  void freshAccountWithNullGlobalRoleKeepsGenericEventOutOfCurrentness() {
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
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        sources.readCurrentIssuerAccountSources(ISSUER, account.getAccountUuid())))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class)
        .hasMessage("Account authority source evidence is unavailable or inconsistent");
    var changedState =
        transaction.execute(
            status -> generations.read(AuthorityScope.account(account.getAccountUuid())));
    assertThat(changedState.generation()).isEqualTo(2L);
    assertThat(changedState.sourceVersion()).isEqualTo(2L);
    assertThat(changedState.issuanceFence().value()).isEqualTo(2L);
    assertThat(changedState.issuanceFence().sourceVersion()).isEqualTo(2L);
    String accountStream = baseline.account().checkpoint().outboxStreamKey();
    var changedCheckpoint =
        transaction.execute(status -> outbox.readCheckpoint(accountStream).orElseThrow());
    assertThat(changedCheckpoint.outboxSequence()).isEqualTo(1L);
    AccountAuthorityOutboxRepository.Event storedEvent =
        transaction.execute(status -> outbox.findEvent(accountStream, 1L).orElseThrow());
    assertThat(storedEvent.eventId()).isEqualTo(changedCheckpoint.sourceEventId());
    assertThat(storedEvent.eventDigest()).isEqualTo(changedCheckpoint.sourceEventDigest());
    AccountEvent event =
        (AccountEvent)
            AccountAuthoritySourceEventV1Codec.verify(
                new String(storedEvent.payload(), StandardCharsets.UTF_8));

    assertThat(event.mutationKinds()).containsExactly("PASSWORD_RESET");
    assertThat(event.accountId()).isEqualTo(account.getAccountUuid().toString());
    assertThat(event.outboxStreamKey()).isEqualTo(accountStream);
    assertThat(event.outboxSequence()).isEqualTo("1");
    assertThat(event.accountAuthorityGeneration()).isEqualTo("2");
    assertThat(event.sourceVersion()).isEqualTo("2");
    assertThat(event.issuanceFence()).isEqualTo("2");
    assertThat(event.eventId()).isEqualTo(storedEvent.eventId());
    assertThat(event.eventDigest()).isEqualTo(storedEvent.eventDigest());
    assertThat(event.accountState().globalRole()).isNull();
    assertThat(event.canonicalJson()).contains("\"globalRole\":null");
    assertThat(event.canonicalJson()).doesNotContain("changed-null-role-account-password-hash");
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
          .satisfies(
              failure -> {
                assertThat(failure.getCause())
                    .isInstanceOf(
                        AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException
                            .class);
                assertThat(failure.getCause())
                    .hasMessage("Account authority source evidence is unavailable or inconsistent");
              });
      var committedState =
          transaction.execute(
              status -> generations.read(AuthorityScope.account(account.getAccountUuid())));
      assertThat(committedState.generation()).isEqualTo(2L);
      assertThat(committedState.sourceVersion()).isEqualTo(2L);
      assertThat(committedState.issuanceFence().value()).isEqualTo(2L);
      assertThat(committedState.issuanceFence().sourceVersion()).isEqualTo(2L);
      String accountStream = "account:auth-authority:v1:account/" + account.getAccountUuid();
      var committedCheckpoint =
          transaction.execute(status -> outbox.readCheckpoint(accountStream).orElseThrow());
      assertThat(committedCheckpoint.outboxSequence()).isEqualTo(1L);
      AccountAuthorityOutboxRepository.Event committedEvent =
          transaction.execute(status -> outbox.findEvent(accountStream, 1L).orElseThrow());
      assertThat(committedEvent.eventId()).isEqualTo(committedCheckpoint.sourceEventId());
      assertThat(committedEvent.eventDigest()).isEqualTo(committedCheckpoint.sourceEventDigest());
      AccountEvent event =
          (AccountEvent)
              AccountAuthoritySourceEventV1Codec.verify(
                  new String(committedEvent.payload(), StandardCharsets.UTF_8));
      assertThat(event.accountId()).isEqualTo(account.getAccountUuid().toString());
      assertThat(event.outboxStreamKey()).isEqualTo(accountStream);
      assertThat(event.outboxSequence()).isEqualTo("1");
      assertThat(event.accountAuthorityGeneration()).isEqualTo("2");
      assertThat(event.sourceVersion()).isEqualTo("2");
      assertThat(event.issuanceFence()).isEqualTo("2");
      assertThat(event.mutationKinds()).containsExactly("PASSWORD_RESET");
      assertThat(event.eventId()).isEqualTo(committedEvent.eventId());
      assertThat(event.eventDigest()).isEqualTo(committedEvent.eventDigest());
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
