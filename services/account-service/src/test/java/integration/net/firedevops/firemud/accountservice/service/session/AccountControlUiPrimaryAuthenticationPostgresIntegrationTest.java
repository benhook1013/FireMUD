package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mkammerer.argon2.Argon2Factory;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
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
 * Actual primary authentication, persisted canonical identity/source mapping, password-reset owner,
 * OTP repositories, and PostgreSQL transactions/locks. Reset contention uses a genuine test token
 * and the production closed PASSWORD_RESET producer in one transaction. Secrets and Accounts are
 * test-only fixtures; this is not creator-source, signer, protected caller, or runtime issuance
 * proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountControlUiPrimaryAuthenticationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final String PASSWORD = "test-only-primary-password";
  private static final String RESET_PASSWORD = "test-only-reset-password";
  private static final String OTP = "test-only-email-otp";

  @Test
  void passwordAuthenticatesPersistedCanonicalUuidAndWrongSecretDoesNotMutate() {
    Context c = context();
    Account account = c.account("PASSWORD");
    String before = c.accountBytes(account.getAccountUuid());
    UUID authenticated =
        c.tx(
            () ->
                c.primary
                    .authenticateControlUiPrimaryIdentity(account.getEmail(), PASSWORD)
                    .accountId());
    assertThat(authenticated).isEqualTo(account.getAccountUuid());
    assertThat(account.getAccountUuidSourceNumericId()).isEqualTo(account.getId());
    assertThat(c.accountBytes(account.getAccountUuid())).isEqualTo(before);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.primary.authenticateControlUiPrimaryIdentity(
                            account.getEmail(), "wrong-secret")))
        .isInstanceOf(AuthenticationException.class);
    assertThat(c.accountBytes(account.getAccountUuid())).isEqualTo(before);
  }

  @Test
  void freshAccountCannotBeCreatedInSecurityLockedStateAndLeavesNoRows() {
    Context c = context();
    Account lockedAccount = c.accountCandidate("PASSWORD", AccountLifecycleState.SECURITY_LOCKED);
    assertThatThrownBy(
            () ->
                c.tx(
                    () -> {
                      c.accounts.save(lockedAccount);
                      return null;
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Fresh Accounts must be created in the ACTIVE lifecycle state");

    UUID accountUuid = lockedAccount.getAccountUuid();
    assertThat(accountUuid).isNull();
    assertThat(c.accountRowCount()).isZero();
    assertThat(c.accountSourceRowCount()).isZero();
    assertThat(c.accountGenerationRowCount()).isZero();
    assertThat(c.restrictionBirthRowCount()).isZero();
  }

  @Test
  void exactOtpConsumptionParticipatesInOwnerRollbackAndCannotBeReplayedAfterCommit() {
    Context c = context();
    Account account = c.account("EMAIL_OTP");
    AccountEmailLoginChallenge original = c.challenge(account);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.primary.authenticateControlUiPrimaryIdentity(
                            account.getEmail(), "wrong-otp")))
        .isInstanceOf(AuthenticationException.class);
    assertThat(c.challenges.findByAccountId(account.getId())).contains(original);
    c.transaction.executeWithoutResult(
        status -> {
          assertThat(
                  c.primary
                      .authenticateControlUiPrimaryIdentity(account.getEmail(), OTP)
                      .accountId())
              .isEqualTo(account.getAccountUuid());
          assertThat(c.challenges.findByAccountId(account.getId())).isEmpty();
          status.setRollbackOnly();
        });
    assertThat(c.challenges.findByAccountId(account.getId())).contains(original);
    assertThat(
            c.tx(
                () ->
                    c.primary
                        .authenticateControlUiPrimaryIdentity(account.getEmail(), OTP)
                        .accountId()))
        .isEqualTo(account.getAccountUuid());
    assertThat(c.challenges.findByAccountId(account.getId())).isEmpty();
    assertThatThrownBy(
            () ->
                c.tx(() -> c.primary.authenticateControlUiPrimaryIdentity(account.getEmail(), OTP)))
        .isInstanceOf(AuthenticationException.class);
    assertThat(c.challenges.findByAccountId(account.getId())).isEmpty();
  }

  @Test
  void concurrentOtpAttemptsSerializeOnActualPostgresOwnerLockAndOnlyOneAuthenticates()
      throws Exception {
    Context c = context();
    Account account = c.account("EMAIL_OTP");
    c.challenge(account);
    var firstAuthenticated = new CountDownLatch(1);
    var secondEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var firstPid = new AtomicInteger();
    var secondPid = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  c.tx(
                      () -> {
                        firstPid.set(c.pid());
                        UUID actor =
                            c.primary
                                .authenticateControlUiPrimaryIdentity(account.getEmail(), OTP)
                                .accountId();
                        firstAuthenticated.countDown();
                        awaitLatch(releaseFirst);
                        return actor;
                      }));
      awaitLatch(firstAuthenticated);
      var second =
          executor.submit(
              () ->
                  c.tx(
                      () -> {
                        secondPid.set(c.pid());
                        secondEntered.countDown();
                        return c.primary
                            .authenticateControlUiPrimaryIdentity(account.getEmail(), OTP)
                            .accountId();
                      }));
      awaitLatch(secondEntered);
      awaitBlocked(c, secondPid.get(), firstPid.get());
      assertThat(first.isDone()).isFalse();
      assertThat(second.isDone()).isFalse();
      releaseFirst.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(account.getAccountUuid());
      assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AuthenticationException.class);
      assertThat(c.challenges.findByAccountId(account.getId())).isEmpty();
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void resetThatOwnsTheAccountLockFirstMakesIssuanceVerifyTheCommittedNewPassword()
      throws Exception {
    var resetLocked = new CountDownLatch(1);
    var releaseReset = new CountDownLatch(1);
    var resetPid = new AtomicInteger();
    Context c =
        context(
            dsl -> {
              resetPid.set(backendPid(dsl));
              resetLocked.countDown();
              awaitLatch(releaseReset);
            });
    Account account = c.account("PASSWORD");
    String resetToken = c.passwordResetToken(account);
    var issuerEntered = new CountDownLatch(1);
    var issuerPid = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var reset =
          executor.submit(
              () ->
                  c.primary.completePasswordReset(
                      new CompletePasswordResetRequest(resetToken, RESET_PASSWORD)));
      awaitLatch(resetLocked);
      var issuance =
          executor.submit(
              () ->
                  c.tx(
                      () -> {
                        issuerPid.set(c.pid());
                        issuerEntered.countDown();
                        return c.primary
                            .authenticateControlUiPrimaryIdentity(account.getEmail(), PASSWORD)
                            .accountId();
                      }));
      awaitLatch(issuerEntered);
      awaitBlocked(c, issuerPid.get(), resetPid.get());
      assertThat(issuance.isDone()).isFalse();

      releaseReset.countDown();
      reset.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> issuance.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AuthenticationException.class);
      assertThat(
              c.tx(
                  () ->
                      c.primary
                          .authenticateControlUiPrimaryIdentity(account.getEmail(), RESET_PASSWORD)
                          .accountId()))
          .isEqualTo(account.getAccountUuid());
      assertResetAuthorityCommitted(c, account);
    } finally {
      releaseReset.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void issuanceKeepsTheAccountLockUntilItsCallerTransactionCommitsBeforeReset() throws Exception {
    Context c = context();
    Account account = c.account("PASSWORD");
    String resetToken = c.passwordResetToken(account);
    var issuanceAuthenticated = new CountDownLatch(1);
    var releaseIssuance = new CountDownLatch(1);
    var issuerPid = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var issuance =
          executor.submit(
              () ->
                  c.tx(
                      () -> {
                        issuerPid.set(c.pid());
                        UUID actor =
                            c.primary
                                .authenticateControlUiPrimaryIdentity(account.getEmail(), PASSWORD)
                                .accountId();
                        issuanceAuthenticated.countDown();
                        awaitLatch(releaseIssuance);
                        return actor;
                      }));
      awaitLatch(issuanceAuthenticated);
      var reset =
          executor.submit(
              () ->
                  c.primary.completePasswordReset(
                      new CompletePasswordResetRequest(resetToken, RESET_PASSWORD)));
      awaitAnyBlockedBy(c, issuerPid.get());
      assertThat(reset.isDone()).isFalse();

      releaseIssuance.countDown();
      assertThat(issuance.get(10, TimeUnit.SECONDS)).isEqualTo(account.getAccountUuid());
      reset.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(
              () ->
                  c.tx(
                      () ->
                          c.primary.authenticateControlUiPrimaryIdentity(
                              account.getEmail(), PASSWORD)))
          .isInstanceOf(AuthenticationException.class);
      assertThat(
              c.tx(
                  () ->
                      c.primary
                          .authenticateControlUiPrimaryIdentity(account.getEmail(), RESET_PASSWORD)
                          .accountId()))
          .isEqualTo(account.getAccountUuid());
      assertResetAuthorityCommitted(c, account);
    } finally {
      releaseIssuance.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Expected primary-auth transaction boundary");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted primary-auth transaction boundary");
    }
  }

  private static void awaitBlocked(Context c, int waitingPid, int blockerPid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var observed =
          Objects.requireNonNull(
              c.dsl.fetchOne(
                  "SELECT ? = ANY(pg_blocking_pids(?)) AS blocked", blockerPid, waitingPid),
              "Exact PostgreSQL blocking identity readback required");
      if (Boolean.TRUE.equals(observed.get("blocked", Boolean.class))) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException(
        "Second Account transaction did not block on the first Account transaction");
  }

  private static void awaitAnyBlockedBy(Context c, int blockerPid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var observed =
          Objects.requireNonNull(
              c.dsl.fetchOne(
                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity waiting "
                      + "WHERE waiting.pid <> pg_backend_pid() "
                      + "AND waiting.wait_event_type = 'Lock' "
                      + "AND ? = ANY(pg_blocking_pids(waiting.pid))) AS blocked",
                  blockerPid),
              "PostgreSQL lock-wait observation is required");
      if (Boolean.TRUE.equals(observed.get("blocked", Boolean.class))) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("Password reset did not block on the issuance Account lock");
  }

  private static int backendPid(DSLContext dsl) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT pg_backend_pid() AS pid"),
            "Exact owner transaction backend required")
        .get("pid", Integer.class);
  }

  private static void assertResetAuthorityCommitted(Context c, Account account) {
    var authority =
        c.tx(() -> c.generations.read(AuthorityScope.account(account.getAccountUuid())));
    assertThat(authority.generation()).isEqualTo(2L);
    assertThat(authority.sourceVersion()).isEqualTo(2L);
    assertThat(authority.issuanceFence().value()).isEqualTo(2L);
    assertThat(authority.issuanceFence().sourceVersion()).isEqualTo(2L);
  }

  private static Context context() {
    return context(ignored -> {});
  }

  private static Context context(Consumer<DSLContext> afterPasswordUpdate) {
    String schema = "control_ui_primary_" + UUID.randomUUID().toString().replace("-", "");
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
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    var accounts =
        new AccountRepository(dsl, sourceEvidence) {
          @Override
          public Account updatePasswordHashForLockedAccount(Account expected, String passwordHash) {
            Account updated = super.updatePasswordHashForLockedAccount(expected, passwordHash);
            afterPasswordUpdate.accept(dsl);
            return updated;
          }
        };
    var challenges = new AccountEmailLoginChallengeRepository(dsl);
    var resetOperations = new AccountPasswordResetOperationRepository(dsl);
    var logoutAllOperations = new AccountLogoutAllOperationRepository(dsl);
    var securityStateOperations = new AccountSecurityStateOperationRepository(dsl);
    var resetSourceChanges = new AccountPasswordResetDraftSourceChangeRepository(dsl);
    var resetTokens = new PasswordResetTokenRepository(dsl);
    var primary =
        new AccountServiceImpl(
            accounts,
            generations,
            sourceEvidence,
            outbox,
            resetOperations,
            logoutAllOperations,
            securityStateOperations,
            resetSourceChanges,
            null, // Audit outbox is not used by credential/reset owner tests.
            null, // Connect scopes are not used by credential/reset owner tests.
            null, // JOIN operations are not used by credential/reset owner tests.
            challenges,
            null, // Realm grants are not used by primary authentication.
            null, // Tenant memberships are not used by primary authentication.
            Mappers.getMapper(AccountMapper.class),
            null, // Profiles are not used by primary authentication.
            null, // Profile mapper is not used by primary authentication.
            null, // Payment transactions are not used by primary authentication.
            null, // Subscriptions are not used by primary authentication.
            null, // External identities are not used by primary authentication.
            resetTokens,
            null, // Email verification tokens are not used by primary authentication.
            null, // Notifications are not used by primary authentication.
            null, // Email delivery is not used by primary authentication.
            null, // Mail properties are not used by primary authentication.
            null, // Token properties are not used by primary authentication.
            null, // JWT auth properties are not used by primary authentication.
            null, // Game Session client is not used by primary authentication.
            null, // Entity Management client is not used by primary authentication.
            null, // JWT utility is not used by primary authentication.
            null, // Session service is not used by primary authentication.
            manager);
    return new Context(dsl, accounts, challenges, primary, transaction, generations, resetTokens);
  }

  private record Context(
      DSLContext dsl,
      AccountRepository accounts,
      AccountEmailLoginChallengeRepository challenges,
      AccountServiceImpl primary,
      TransactionTemplate transaction,
      AccountAuthorityGenerationRepository generations,
      PasswordResetTokenRepository resetTokens) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }

    Account account(String modes) {
      return account(modes, AccountLifecycleState.ACTIVE);
    }

    Account account(String modes, AccountLifecycleState lifecycleState) {
      Account account = accountCandidate(modes, lifecycleState);
      return tx(() -> accounts.save(account));
    }

    Account accountCandidate(String modes, AccountLifecycleState lifecycleState) {
      Account account = new Account();
      String suffix = UUID.randomUUID().toString();
      account.setUsername("primary-" + suffix);
      account.setEmail(suffix + "@example.test");
      account.setPasswordHash(hash(PASSWORD));
      account.setLoginAuthModes(modes);
      account.setLifecycleState(lifecycleState);
      return account;
    }

    long accountRowCount() {
      return count("SELECT COUNT(*) FROM accounts");
    }

    long accountSourceRowCount() {
      return count("SELECT COUNT(*) FROM account_authority_source_records");
    }

    long accountGenerationRowCount() {
      return count(
          "SELECT COUNT(*) FROM account_authority_generations " + "WHERE scope_kind = 'ACCOUNT'");
    }

    long restrictionBirthRowCount() {
      return count("SELECT COUNT(*) FROM account_platform_restriction_births");
    }

    private long count(String query) {
      return Objects.requireNonNull(dsl.fetchOne(query)).get(0, Long.class);
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

    String passwordResetToken(Account account) {
      String rawToken = "test-only-reset-token-" + UUID.randomUUID();
      tx(
          () -> {
            var token = new PasswordResetToken();
            token.setAccount(account);
            token.setToken(rawToken);
            token.setExpiresAt(LocalDateTime.now().plusHours(1));
            resetTokens.save(token);
            return null;
          });
      return rawToken;
    }

    int pid() {
      return Objects.requireNonNull(
              dsl.fetchOne("SELECT pg_backend_pid() AS pid"),
              "Exact owner transaction backend required")
          .get("pid", Integer.class);
    }

    String accountBytes(UUID account) {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT to_jsonb(a)::TEXT AS exact FROM accounts a WHERE account_uuid = ?",
                  account),
              "Persisted canonical Account readback required")
          .get("exact", String.class);
    }
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
}
