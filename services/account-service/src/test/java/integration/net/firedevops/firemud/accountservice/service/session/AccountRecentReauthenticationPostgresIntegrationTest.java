package integration.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mkammerer.argon2.Argon2Factory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationEvidence;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicy;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicyRepository;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationRepository;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationService;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationService.Disposition;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual primary authentication, Account repositories, policy sources and evidence use PostgreSQL.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountRecentReauthenticationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final String PASSWORD = "test-only-recent-reauth-password";
  private static final String OTP = "test-only-recent-reauth-otp";
  private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");

  @Test
  void actualPasswordProducerPersistsExactImmutableAccountOperationAndPolicyEvidence() {
    Context c = context();
    Account account = c.account("PASSWORD");
    UUID operationId = UUID.randomUUID();
    var request = c.request(account, UUID.randomUUID(), operationId, PASSWORD);

    var evidence = c.service.reauthenticate(request);
    Record row = c.evidenceRow(evidence.evidenceId());
    String payload =
        new String(
            row.get("request_payload", byte[].class), java.nio.charset.StandardCharsets.UTF_8);

    assertThat(evidence.accountId()).isEqualTo(account.getAccountUuid());
    assertThat(evidence.creationOperationId()).isEqualTo(operationId);
    assertThat(evidence.identitySourceNumericId()).isEqualTo(account.getId());
    assertThat(evidence.accountAuthorityGeneration()).isPositive();
    assertThat(evidence.accountAuthoritySourceVersion()).isPositive();
    assertThat(evidence.issuanceFence()).isPositive();
    assertThat(evidence.issuanceFenceSourceVersion()).isPositive();
    assertThat(evidence.platformPolicyVersion()).isEqualTo(1L);
    assertThat(evidence.deploymentPolicyVersion()).isEqualTo(1L);
    assertThat(evidence.recentAgeSeconds()).isEqualTo(300L);
    assertThat(evidence.maxClockSkewSeconds()).isEqualTo(30L);
    assertThat(payload).contains(account.getAccountUuid().toString(), evidence.purpose());
    assertThat(payload).doesNotContain(PASSWORD, account.getEmail());
    assertThat(request.toString()).doesNotContain(PASSWORD, account.getEmail());
    assertThat(row.get("request_digest", String.class)).isEqualTo(evidence.requestDigest());
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence"))
        .isEqualTo(1L);
  }

  @Test
  void postgresMicrosecondTimestampReadbackPreservesOriginalAndExactReplay() {
    Context c = context();
    Account account = c.account("PASSWORD");
    Instant sourceInstant = BASE.plusNanos(123_456_789L);
    Instant persistedInstant = sourceInstant.truncatedTo(ChronoUnit.MICROS);
    c.clock.set(sourceInstant);
    var request = c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD);

    var original = c.service.reauthenticate(request);
    Record row = c.evidenceRow(original.evidenceId());
    var replay = c.service.reauthenticate(request);

    assertThat(original.issuedAt()).isEqualTo(persistedInstant);
    assertThat(row.get("issued_at", java.time.OffsetDateTime.class).toInstant())
        .isEqualTo(persistedInstant);
    assertThat(replay.evidenceId()).isEqualTo(original.evidenceId());
    assertThat(replay.issuedAt()).isEqualTo(persistedInstant);
  }

  @Test
  void nonV4RequestAndCreationIdsAreAcceptedForPersistedAccountIdentity() {
    Context c = context();
    Account account = c.account("PASSWORD");
    UUID requestId = new UUID(1L, 1L);
    UUID operationId = new UUID(2L, 2L);
    assertThat(requestId.version()).isNotEqualTo(4);
    assertThat(operationId.version()).isNotEqualTo(4);
    var request = c.request(account, requestId, operationId, PASSWORD);

    var original = c.service.reauthenticate(request);
    var replay = c.service.reauthenticate(request);

    assertThat(original.requestId()).isEqualTo(requestId);
    assertThat(original.creationOperationId()).isEqualTo(operationId);
    assertThat(replay.evidenceId()).isEqualTo(original.evidenceId());
  }

  @Test
  void missingCurrentPolicyFailsBeforeValidOtpIsConsumedOrEvidenceIsRetained() {
    Context c = context(false);
    Account account = c.account("EMAIL_OTP");
    AccountEmailLoginChallenge challenge = c.challenge(account);
    var request = c.request(account, UUID.randomUUID(), UUID.randomUUID(), OTP);

    assertThatThrownBy(() -> c.service.reauthenticate(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Current Account reauthentication policy is unavailable");

    assertThat(c.challenges.findByAccountId(account.getId())).contains(challenge);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence")).isZero();
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();
  }

  @Test
  void exactOtpRequestReplayReturnsOriginalEvidenceWithoutSecondAuthenticationOrOtpUse() {
    Context c = context();
    Account account = c.account("EMAIL_OTP");
    AccountEmailLoginChallenge challenge = c.challenge(account);
    var request = c.request(account, UUID.randomUUID(), UUID.randomUUID(), OTP);

    var first = c.service.reauthenticate(request);
    assertThat(c.challenges.findByAccountId(account.getId())).isEmpty();
    var replay = c.service.reauthenticate(request);

    assertThat(replay.evidenceId()).isEqualTo(first.evidenceId());
    assertThat(replay.issuedAt()).isEqualTo(first.issuedAt());
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence"))
        .isEqualTo(1L);
    assertThat(c.challenges.findByAccountId(account.getId())).isEmpty();
    assertThat(challenge.getCodeHash()).isNotBlank();
  }

  @Test
  void changedAccountPurposeOrCreationOperationConflictsBeforeNewAuthentication() {
    Context c = context();
    Account account = c.account("PASSWORD");
    Account other = c.account("PASSWORD");
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    var original = c.request(account, requestId, operationId, PASSWORD);
    c.service.reauthenticate(original);

    assertThatThrownBy(
            () ->
                c.service.reauthenticate(
                    c.request(other, requestId, operationId, "not-the-secret")))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                c.service.reauthenticate(
                    new AccountRecentReauthenticationService.Request(
                        requestId,
                        account.getAccountUuid(),
                        "ACCOUNT_SECURITY_CHANGE",
                        operationId,
                        account.getEmail(),
                        "not-the-secret")))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                c.service.reauthenticate(
                    c.request(account, requestId, UUID.randomUUID(), "not-the-secret")))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence"))
        .isEqualTo(1L);
  }

  @Test
  void failedOwnerCommitRollsBackExactOtpConsumptionAndDoesNotReturnEvidence() {
    Context c = context();
    Account account = c.account("EMAIL_OTP");
    AccountEmailLoginChallenge challenge = c.challenge(account);
    var request = c.request(account, UUID.randomUUID(), UUID.randomUUID(), OTP);
    c.dsl.execute(
        "CREATE FUNCTION reject_recent_reauthentication_commit() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test commit failure'; END $$");
    c.dsl.execute(
        "CREATE CONSTRAINT TRIGGER reject_recent_reauthentication_commit "
            + "AFTER INSERT ON account_recent_reauthentication_evidence "
            + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
            + "EXECUTE FUNCTION reject_recent_reauthentication_commit()");

    assertThatThrownBy(() -> c.service.reauthenticate(request))
        .isInstanceOf(RuntimeException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence")).isZero();
    assertThat(c.challenges.findByAccountId(account.getId())).contains(challenge);
  }

  @Test
  void accountAuthorityAdvanceAndCurrentLockedLifecycleInvalidateUnconsumedEvidence() {
    Context c = context();
    Account account = c.account("PASSWORD");
    var generationEvidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    ScopeState before =
        c.tx(() -> c.generations.read(AuthorityScope.account(account.getAccountUuid())));

    c.tx(
        () -> {
          account.setEmailVerified(true);
          c.accounts.save(account);
          return null;
        });
    ScopeState after =
        c.tx(() -> c.generations.read(AuthorityScope.account(account.getAccountUuid())));
    assertThat(after.generation()).isGreaterThan(before.generation());
    assertThatThrownBy(
            () ->
                c.consume(
                    generationEvidence,
                    account.getAccountUuid(),
                    generationEvidence.purpose(),
                    generationEvidence.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();

    Account lifecycleAccount = c.account("PASSWORD");
    var lifecycleEvidence =
        c.service.reauthenticate(
            c.request(lifecycleAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    // This fixture changes only the persisted current lifecycle to isolate the AccountRepository
    // current-state denial. It does not claim the separate security-lock transition producer.
    c.transaction.executeWithoutResult(
        status -> {
          c.dsl.execute("SET LOCAL session_replication_role = 'replica'");
          c.dsl.execute(
              "UPDATE accounts SET lifecycle_state = 'security_locked' WHERE account_uuid = ?",
              lifecycleAccount.getAccountUuid());
        });
    assertThatThrownBy(
            () ->
                c.consume(
                    lifecycleEvidence,
                    lifecycleAccount.getAccountUuid(),
                    lifecycleEvidence.purpose(),
                    lifecycleEvidence.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();
  }

  @Test
  void exactExpiryFutureSkewAndPolicyVersionAdvanceFailClosed() {
    Context c = context();
    Account exactDeadlineAccount = c.account("PASSWORD");
    c.clock.set(BASE);
    var exactDeadline =
        c.service.reauthenticate(
            c.request(exactDeadlineAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    c.clock.set(BASE.plusSeconds(270));
    assertThatThrownBy(
            () ->
                c.consume(
                    exactDeadline,
                    exactDeadlineAccount.getAccountUuid(),
                    exactDeadline.purpose(),
                    exactDeadline.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);

    Account futureAccount = c.account("PASSWORD");
    c.clock.set(BASE.plusSeconds(30));
    var atMaximumSkew =
        c.service.reauthenticate(
            c.request(futureAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    c.clock.set(BASE);
    var accepted =
        c.consume(
            atMaximumSkew,
            futureAccount.getAccountUuid(),
            atMaximumSkew.purpose(),
            atMaximumSkew.creationOperationId());
    assertThat(accepted.disposition()).isEqualTo(Disposition.CONSUMED);

    Account beyondSkewAccount = c.account("PASSWORD");
    c.clock.set(BASE.plusSeconds(31));
    var beyondSkew =
        c.service.reauthenticate(
            c.request(beyondSkewAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    c.clock.set(BASE);
    assertThatThrownBy(
            () ->
                c.consume(
                    beyondSkew,
                    beyondSkewAccount.getAccountUuid(),
                    beyondSkew.purpose(),
                    beyondSkew.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);

    Account policyAccount = c.account("PASSWORD");
    c.clock.set(BASE);
    var priorPolicy =
        c.service.reauthenticate(
            c.request(policyAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    c.tx(
        () -> {
          c.dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_values "
                  + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                  + "VALUES ('DEPLOYMENT', 2, 100, 10)");
          c.dsl.execute(
              "UPDATE account_recent_reauthentication_policy_current SET policy_version = 2 "
                  + "WHERE policy_scope = 'DEPLOYMENT'");
          return null;
        });
    assertThatThrownBy(
            () ->
                c.consume(
                    priorPolicy,
                    policyAccount.getAccountUuid(),
                    priorPolicy.purpose(),
                    priorPolicy.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);
    var currentPolicy =
        c.service.reauthenticate(
            c.request(policyAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    assertThat(currentPolicy.deploymentPolicyVersion()).isEqualTo(2L);
    assertThat(currentPolicy.recentAgeSeconds()).isEqualTo(100L);
    assertThat(currentPolicy.maxClockSkewSeconds()).isEqualTo(10L);
  }

  @Test
  void consumeIsImmutableAndConcurrentExactRetryCannotConsumeForAnotherOperation()
      throws Exception {
    Context c = context();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    var locked = c.evidenceRow(evidence.evidenceId());
    var enteredFirst = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondEntered = new CountDownLatch(1);
    var firstPid = new AtomicInteger();
    var secondPid = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  c.transaction.execute(
                      status -> {
                        firstPid.set(c.pid());
                        var result =
                            c.service.validateAndConsumeLocked(
                                evidence,
                                account.getAccountUuid(),
                                evidence.purpose(),
                                evidence.creationOperationId());
                        enteredFirst.countDown();
                        awaitLatch(releaseFirst);
                        return result.disposition();
                      }));
      awaitLatch(enteredFirst);
      var second =
          executor.submit(
              () ->
                  c.transaction.execute(
                      status -> {
                        secondPid.set(c.pid());
                        secondEntered.countDown();
                        return c.service
                            .validateAndConsumeLocked(
                                evidence,
                                account.getAccountUuid(),
                                evidence.purpose(),
                                evidence.creationOperationId())
                            .disposition();
                      }));
      awaitLatch(secondEntered);
      awaitBlocked(c, secondPid.get(), firstPid.get());
      releaseFirst.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(Disposition.CONSUMED);
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(Disposition.EXACT_REPLAY);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isEqualTo(1L);
    assertThat(c.evidenceRow(evidence.evidenceId())).isEqualTo(locked);
    assertThatThrownBy(
            () ->
                c.consume(
                    evidence, account.getAccountUuid(), evidence.purpose(), UUID.randomUUID()))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
  }

  @Test
  void retainedEvidenceAndConsumptionRejectSqlMutation() {
    Context c = context();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    Record original = c.evidenceRow(evidence.evidenceId());

    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_recent_reauthentication_evidence "
                                + "SET issued_at = clock_timestamp() WHERE evidence_id = ?",
                            evidence.evidenceId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "DELETE FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
                            evidence.evidenceId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> c.tx(() -> c.dsl.execute("TRUNCATE account_recent_reauthentication_evidence")))
        .isInstanceOf(DataAccessException.class);
    assertThat(c.evidenceRow(evidence.evidenceId())).isEqualTo(original);

    c.consume(
        evidence, account.getAccountUuid(), evidence.purpose(), evidence.creationOperationId());
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_recent_reauthentication_consumptions "
                                + "SET consumed_at = clock_timestamp() WHERE evidence_id = ?",
                            evidence.evidenceId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "DELETE FROM account_recent_reauthentication_consumptions WHERE evidence_id = ?",
                            evidence.evidenceId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(() -> c.dsl.execute("TRUNCATE account_recent_reauthentication_consumptions")))
        .isInstanceOf(DataAccessException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isEqualTo(1L);
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Expected recent-reauthentication transaction boundary");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted recent-reauthentication transaction boundary");
    }
  }

  private static void awaitBlocked(Context c, int waitingPid, int blockerPid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var observed =
          Objects.requireNonNull(
              c.dsl.fetchOne(
                  "SELECT ? = ANY(pg_blocking_pids(?)) AS blocked", blockerPid, waitingPid));
      if (Boolean.TRUE.equals(observed.get("blocked", Boolean.class))) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("Concurrent consume did not wait on the exact evidence row");
  }

  private static Context context() {
    return context(true);
  }

  private static Context context(boolean installApprovedPolicySources) {
    String schema = "recent_reauth_" + UUID.randomUUID().toString().replace("-", "");
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
    var manager = new DataSourceTransactionManager(source);
    var transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    if (installApprovedPolicySources) {
      transaction.executeWithoutResult(
          ignored -> {
            dsl.execute(
                "INSERT INTO account_recent_reauthentication_policy_values "
                    + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                    + "VALUES ('PLATFORM', 1, 300, 30), ('DEPLOYMENT', 1, 300, 30)");
            dsl.execute(
                "INSERT INTO account_recent_reauthentication_policy_current "
                    + "(policy_scope, policy_version) VALUES ('PLATFORM', 1), ('DEPLOYMENT', 1)");
          });
    }
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    var accounts = new AccountRepository(dsl, sourceEvidence);
    var challenges = new AccountEmailLoginChallengeRepository(dsl);
    var primary =
        new AccountServiceImpl(
            accounts,
            generations,
            sourceEvidence,
            outbox,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            mock(AccountSecurityStateOperationRepository.class),
            mock(AccountPasswordResetDraftSourceChangeRepository.class),
            mock(AccountAuditOutboxRepository.class),
            mock(AccountConnectScopeRepository.class),
            mock(AccountJoinOperationRepository.class),
            challenges,
            null,
            null,
            Mappers.getMapper(AccountMapper.class),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            manager);
    var evidence = new AccountRecentReauthenticationRepository(dsl);
    var policies = new AccountRecentReauthenticationPolicyRepository(dsl);
    var clock = new MutableClock(BASE);
    var service =
        new AccountRecentReauthenticationService(
            primary, accounts, generations, evidence, policies, manager, clock);
    return new Context(
        dsl, accounts, challenges, generations, evidence, service, transaction, clock);
  }

  private static String hash(String secret) {
    char[] chars = secret.toCharArray();
    var argon = Argon2Factory.create();
    try {
      return argon.hash(2, 4096, 1, chars);
    } finally {
      argon.wipeArray(chars);
    }
  }

  private record Context(
      DSLContext dsl,
      AccountRepository accounts,
      AccountEmailLoginChallengeRepository challenges,
      AccountAuthorityGenerationRepository generations,
      AccountRecentReauthenticationRepository evidenceRepository,
      AccountRecentReauthenticationService service,
      TransactionTemplate transaction,
      MutableClock clock) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }

    Account account(String modes) {
      Account account = new Account();
      String suffix = UUID.randomUUID().toString();
      account.setUsername("recent-reauth-" + suffix);
      account.setEmail(suffix + "@example.test");
      account.setPasswordHash(hash(PASSWORD));
      account.setRole("player");
      account.setLoginAuthModes(modes);
      account.setLifecycleState(AccountLifecycleState.ACTIVE);
      return tx(() -> accounts.save(account));
    }

    AccountRecentReauthenticationService.Request request(
        Account account, UUID requestId, UUID operationId, String secret) {
      return new AccountRecentReauthenticationService.Request(
          requestId,
          account.getAccountUuid(),
          AccountRecentReauthenticationPolicy.PURPOSE,
          operationId,
          account.getEmail(),
          secret);
    }

    AccountEmailLoginChallenge challenge(Account account) {
      return tx(
          () -> {
            var challenge = new AccountEmailLoginChallenge();
            var now = LocalDateTime.now().withNano(0);
            challenge.setAccountId(account.getId());
            challenge.setCodeHash(hash(OTP));
            challenge.setExpiresAt(now.plusMinutes(5));
            challenge.setResendAvailableAt(now.plusMinutes(1));
            challenge.setCreatedAt(now);
            challenge.setUpdatedAt(now);
            return challenges.save(challenge);
          });
    }

    AccountRecentReauthenticationService.Validation consume(
        AccountRecentReauthenticationEvidence evidence,
        UUID accountId,
        String purpose,
        UUID operationId) {
      return tx(() -> service.validateAndConsumeLocked(evidence, accountId, purpose, operationId));
    }

    Record evidenceRow(UUID evidenceId) {
      return Objects.requireNonNull(
          dsl.fetchOne(
              "SELECT * FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
              evidenceId));
    }

    long count(String query) {
      return Objects.requireNonNull(dsl.fetchOne(query)).get(0, Long.class);
    }

    int pid() {
      return Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid() AS pid"))
          .get("pid", Integer.class);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant next) {
      instant = next;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("Test clock is fixed to UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
