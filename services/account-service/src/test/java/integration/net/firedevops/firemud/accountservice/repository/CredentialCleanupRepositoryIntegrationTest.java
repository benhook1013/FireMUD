package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_EMAIL_LOGIN_CHALLENGE;
import static net.firedevops.firemud.accountservice.jooq.Tables.EMAIL_VERIFICATION_TOKEN;
import static net.firedevops.firemud.accountservice.jooq.Tables.PASSWORD_RESET_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialCleanupRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private PasswordResetTokenRepository passwordResetTokenRepository;
  private EmailVerificationTokenRepository emailVerificationTokenRepository;
  private AccountEmailLoginChallengeRepository emailLoginChallengeRepository;

  @BeforeAll
  void setUpRepositories() {
    DataSource dataSource = newDataSource();
    Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    passwordResetTokenRepository = new PasswordResetTokenRepository(dsl);
    emailVerificationTokenRepository = new EmailVerificationTokenRepository(dsl);
    emailLoginChallengeRepository = new AccountEmailLoginChallengeRepository(dsl);
  }

  @BeforeEach
  void cleanTables() {
    dsl.execute("TRUNCATE TABLE accounts RESTART IDENTITY CASCADE");
  }

  @Test
  void passwordResetCleanupIsBoundedStrictAndIdempotent() {
    LocalDateTime capturedNow = LocalDateTime.of(2026, 9, 19, 12, 0);
    long accountId = insertAccount("password-reset");
    insertPasswordReset(accountId, "oldest", capturedNow.minusMinutes(3));
    insertPasswordReset(accountId, "expired", capturedNow.minusMinutes(2));
    insertPasswordReset(accountId, "equal", capturedNow);
    insertPasswordReset(accountId, "future", capturedNow.plusMinutes(1));

    assertThat(passwordResetTokenRepository.deleteExpired(capturedNow, 1)).isEqualTo(1);
    assertThat(passwordResetTokenRepository.findOldestExpiredAt(capturedNow))
        .contains(capturedNow.minusMinutes(2));
    assertThat(passwordResetTokenRepository.deleteExpired(capturedNow, 10)).isEqualTo(1);
    assertThat(passwordResetTokenRepository.deleteExpired(capturedNow, 10)).isZero();
    assertThat(count(PASSWORD_RESET_TOKEN.getName(), "expires_at >= ?", capturedNow)).isEqualTo(2);
  }

  @Test
  void emailVerificationCleanupIsBoundedStrictAndIdempotent() {
    LocalDateTime capturedNow = LocalDateTime.of(2026, 9, 19, 12, 0);
    long accountId = insertAccount("email-verification");
    insertEmailVerification(accountId, "oldest", capturedNow.minusMinutes(3));
    insertEmailVerification(accountId, "expired", capturedNow.minusMinutes(2));
    insertEmailVerification(accountId, "equal", capturedNow);
    insertEmailVerification(accountId, "future", capturedNow.plusMinutes(1));

    assertThat(emailVerificationTokenRepository.deleteExpired(capturedNow, 1)).isEqualTo(1);
    assertThat(emailVerificationTokenRepository.findOldestExpiredAt(capturedNow))
        .contains(capturedNow.minusMinutes(2));
    assertThat(emailVerificationTokenRepository.deleteExpired(capturedNow, 10)).isEqualTo(1);
    assertThat(emailVerificationTokenRepository.deleteExpired(capturedNow, 10)).isZero();
    assertThat(count(EMAIL_VERIFICATION_TOKEN.getName(), "expires_at >= ?", capturedNow))
        .isEqualTo(2);
  }

  @Test
  void emailLoginChallengeCleanupIsBoundedStrictAndIdempotent() {
    LocalDateTime capturedNow = LocalDateTime.of(2026, 9, 19, 12, 0);
    insertChallenge("challenge-oldest", capturedNow.minusMinutes(3), 1);
    insertChallenge("challenge-expired", capturedNow.minusMinutes(2), 2);
    insertChallenge("challenge-equal", capturedNow, 3);
    insertChallenge("challenge-future", capturedNow.plusMinutes(1), 4);

    assertThat(emailLoginChallengeRepository.deleteExpired(capturedNow, 1)).isEqualTo(1);
    assertThat(emailLoginChallengeRepository.findOldestExpiredAt(capturedNow))
        .contains(capturedNow.minusMinutes(2));
    assertThat(emailLoginChallengeRepository.deleteExpired(capturedNow, 10)).isEqualTo(1);
    assertThat(emailLoginChallengeRepository.deleteExpired(capturedNow, 10)).isZero();
    assertThat(count(ACCOUNT_EMAIL_LOGIN_CHALLENGE.getName(), "expires_at >= ?", capturedNow))
        .isEqualTo(2);
  }

  private DataSource newDataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private long insertAccount(String suffix) {
    Number accountId =
        (Number)
            dsl.fetchValue(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                "cleanup-" + suffix,
                "cleanup-" + suffix + "@example.com",
                "hash");
    if (accountId == null) {
      throw new IllegalStateException("account insert did not return an id");
    }
    return accountId.longValue();
  }

  private void insertPasswordReset(long accountId, String token, LocalDateTime expiresAt) {
    dsl.insertInto(PASSWORD_RESET_TOKEN)
        .set(PASSWORD_RESET_TOKEN.ACCOUNT_ID, accountId)
        .set(PASSWORD_RESET_TOKEN.TOKEN, token)
        .set(PASSWORD_RESET_TOKEN.EXPIRES_AT, expiresAt)
        .execute();
  }

  private void insertEmailVerification(long accountId, String token, LocalDateTime expiresAt) {
    dsl.insertInto(EMAIL_VERIFICATION_TOKEN)
        .set(EMAIL_VERIFICATION_TOKEN.ACCOUNT_ID, accountId)
        .set(EMAIL_VERIFICATION_TOKEN.TOKEN, token)
        .set(EMAIL_VERIFICATION_TOKEN.EXPIRES_AT, expiresAt)
        .execute();
  }

  private void insertChallenge(String suffix, LocalDateTime expiresAt, long accountNumber) {
    long accountId = insertAccount(suffix);
    dsl.insertInto(ACCOUNT_EMAIL_LOGIN_CHALLENGE)
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.ACCOUNT_ID, accountId)
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.CODE_HASH, "hash-" + accountNumber)
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.EXPIRES_AT, expiresAt)
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.RESEND_AVAILABLE_AT, expiresAt.minusMinutes(1))
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.INVALID_ATTEMPT_COUNT, 0)
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.CREATED_AT, expiresAt.minusMinutes(2))
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.UPDATED_AT, expiresAt.minusMinutes(1))
        .execute();
  }

  private int count(String table, String predicate, LocalDateTime capturedNow) {
    return dsl.fetch("SELECT id FROM " + table + " WHERE " + predicate, capturedNow).size();
  }
}
