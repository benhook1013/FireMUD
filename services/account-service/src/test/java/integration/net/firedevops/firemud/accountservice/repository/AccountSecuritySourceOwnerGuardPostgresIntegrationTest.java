package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
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

/**
 * PostgreSQL proof for the closed reset/logout source paths and their Account SQL guards.
 *
 * <p>Token bindings below are synthetic test inputs to unwired owner primitives. This proves only
 * Account-owned source/receipt consistency; it is not external caller-authentication evidence or
 * proof that either primitive is activated by an API.
 */
class AccountSecuritySourceOwnerGuardPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "acct_sec_owner_guard";
  private static final String TOKEN_PROFILE = "control-ui";
  private static final HexFormat HEX = HexFormat.of();
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void configureDatabase() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopOwnedContainer() {
    POSTGRES.stop();
  }

  @Test
  void closedResetAndLogoutEventsPassExactGuardsAndRejectUnbackedMutations() {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    TransactionTemplate transaction = context.transaction();
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountPasswordResetOperationRepository resetOperations =
        new AccountPasswordResetOperationRepository(dsl);
    AccountLogoutAllOperationRepository logoutOperations =
        new AccountLogoutAllOperationRepository(dsl);
    AccountSecurityStateOperationRepository securityOperations =
        new AccountSecurityStateOperationRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    AccountRepository accounts = new AccountRepository(dsl, sources);
    PasswordResetTokenRepository tokens = new PasswordResetTokenRepository(dsl);
    AccountPasswordResetDraftSourceChangeRepository resetSourceChanges =
        new AccountPasswordResetDraftSourceChangeRepository(dsl);

    Seed seed = seedAccountAndResetToken(transaction, accounts, tokens);
    AccountServiceImpl passwordResetService =
        newPasswordResetService(
            accounts,
            generations,
            sources,
            outbox,
            resetOperations,
            logoutOperations,
            securityOperations,
            resetSourceChanges,
            tokens,
            context);

    passwordResetService.completePasswordReset(
        new CompletePasswordResetRequest(seed.rawResetToken(), "test replacement password"));

    Account afterReset = accounts.findByAccountUuid(seed.account().getAccountUuid()).orElseThrow();
    PasswordResetReceipt resetReceipt =
        transaction.execute(
            status ->
                resetOperations.findByTokenHash(sha256Hex(seed.rawResetToken())).orElseThrow());
    ScopeState resetState = readState(transaction, generations, seed.account().getAccountUuid());
    CurrentSourceEvidence resetEvidence =
        transaction.execute(
            status ->
                sources.readCurrentAccountSource(seed.account().getAccountUuid(), resetState));
    var resetEvent =
        transaction.execute(
            status ->
                outbox
                    .findEvent(resetReceipt.outboxStreamKey(), resetReceipt.outboxSequence())
                    .orElseThrow());
    var decodedReset =
        PasswordResetAuthorityEventV1Codec.verify(
            new String(resetEvent.payload(), StandardCharsets.UTF_8));

    assertThat(decodedReset.accountId()).isEqualTo(seed.account().getAccountUuid().toString());
    assertThat(decodedReset.accountAuthorityGeneration()).isEqualTo("2");
    assertThat(resetReceipt.accountUuid()).isEqualTo(seed.account().getAccountUuid());
    assertThat(resetReceipt.outboxSequence()).isEqualTo(1L);
    assertThat(resetReceipt.eventId()).isEqualTo(resetEvent.eventId());
    assertThat(resetReceipt.eventDigest()).isEqualTo(resetEvent.eventDigest());
    assertThat(resetReceipt.passwordVerifierDigest())
        .isEqualTo(sha256Hex(afterReset.getPasswordHash()));
    assertThat(resetEvidence.checkpoint().sequence()).isEqualTo(1L);
    assertThat(afterReset.getPasswordHash()).startsWith("$argon2");

    assertDatabaseWriteRejected(
        () ->
            dsl.execute(
                "UPDATE accounts SET password_hash = ? WHERE account_uuid = ?",
                "unbacked-password-verifier",
                seed.account().getAccountUuid()));
    assertThat(
            accounts
                .findByAccountUuid(seed.account().getAccountUuid())
                .orElseThrow()
                .getPasswordHash())
        .isEqualTo(afterReset.getPasswordHash());

    assertDatabaseWriteRejected(
        () ->
            dsl.execute(
                "UPDATE account_password_reset_operation_receipts "
                    + "SET request_digest = request_digest WHERE token_hash = ?",
                HEX.parseHex(resetReceipt.tokenHash())));
    assertDatabaseWriteRejected(
        () ->
            dsl.execute(
                "UPDATE account_authority_issuance_fences "
                    + "SET issuance_fence = issuance_fence + 1, source_version = source_version + 1 "
                    + "WHERE account_uuid = ?",
                seed.account().getAccountUuid()));
    assertThat(readState(transaction, generations, seed.account().getAccountUuid()))
        .isEqualTo(resetState);

    String syntheticPresentedTokenHash = sha256Hex("test-only presented logout token");
    UUID logoutRequestId = UUID.randomUUID();
    String logoutRequestDigest =
        AccountLogoutRequestDigest.accountLogoutAll(
            seed.account().getAccountUuid(), TOKEN_PROFILE, syntheticPresentedTokenHash);
    Account persistedAccount = accounts.findById(seed.account().getId()).orElseThrow();
    ScopeState expectedLogoutState =
        readState(transaction, generations, seed.account().getAccountUuid());
    AccountLogoutAllAuthorityEventProducer logoutProducer =
        new AccountLogoutAllAuthorityEventProducer(
            accounts,
            generations,
            sources,
            outbox,
            logoutOperations,
            new AccountAuthoritySourceEventReadback(
                outbox, resetOperations, logoutOperations, securityOperations),
            dsl,
            context.transactionManager());

    assertThat(
            logoutProducer.commit(
                logoutRequestId,
                1,
                logoutRequestDigest,
                TOKEN_PROFILE,
                syntheticPresentedTokenHash,
                persistedAccount,
                expectedLogoutState))
        .isEqualTo(AccountLogoutAllAuthorityEventProducer.LogoutAllResult.LOGOUT_ALL_COMMITTED);

    var logoutReceipt =
        transaction.execute(
            status -> logoutOperations.findByRequestId(logoutRequestId).orElseThrow());
    var logoutEvent =
        transaction.execute(
            status ->
                outbox
                    .findEvent(logoutReceipt.outboxStreamKey(), logoutReceipt.outboxSequence())
                    .orElseThrow());
    var decodedLogout =
        AccountLogoutAllAuthorityEventV1Codec.verify(
            new String(logoutEvent.payload(), StandardCharsets.UTF_8));
    ScopeState finalState = readState(transaction, generations, seed.account().getAccountUuid());
    CurrentSourceEvidence finalEvidence =
        transaction.execute(
            status ->
                sources.readCurrentAccountSource(seed.account().getAccountUuid(), finalState));

    assertThat(decodedLogout.accountId()).isEqualTo(seed.account().getAccountUuid().toString());
    assertThat(decodedLogout.accountAuthorityGeneration()).isEqualTo("3");
    assertThat(logoutReceipt.outboxSequence()).isEqualTo(2L);
    assertThat(logoutReceipt.accountUuid()).isEqualTo(seed.account().getAccountUuid());
    assertThat(logoutReceipt.eventId()).isEqualTo(logoutEvent.eventId());
    assertThat(logoutReceipt.eventDigest()).isEqualTo(logoutEvent.eventDigest());
    assertThat(finalEvidence.checkpoint().sequence()).isEqualTo(2L);
    assertThat(finalState.generation()).isEqualTo(3L);
    assertThat(finalState.sourceVersion()).isEqualTo(3L);
    assertThat(finalState.issuanceFence().value()).isEqualTo(3L);

    assertDatabaseWriteRejected(
        () ->
            dsl.execute(
                "UPDATE accounts SET password_hash = ? WHERE account_uuid = ?",
                "logout-is-not-password-reset-authority",
                seed.account().getAccountUuid()));
    assertThat(
            accounts
                .findByAccountUuid(seed.account().getAccountUuid())
                .orElseThrow()
                .getPasswordHash())
        .isEqualTo(afterReset.getPasswordHash());
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    return new TestContext(dsl, new TransactionTemplate(transactionManager), transactionManager);
  }

  private Seed seedAccountAndResetToken(
      TransactionTemplate transaction,
      AccountRepository accounts,
      PasswordResetTokenRepository tokens) {
    return transaction.execute(
        status -> {
          String suffix = UUID.randomUUID().toString();
          Account account = new Account();
          account.setUsername("source-guard-" + suffix);
          account.setEmail("source-guard-" + suffix + "@example.test");
          account.setPasswordHash("initial-password-verifier-" + suffix);
          account.setRole("player");
          Account saved = accounts.save(account);

          String rawToken = "reset-token-" + suffix;
          PasswordResetToken token = new PasswordResetToken();
          token.setAccount(saved);
          token.setToken(rawToken);
          token.setExpiresAt(LocalDateTime.now().plusHours(2));
          tokens.save(token);
          return new Seed(saved, rawToken);
        });
  }

  private AccountServiceImpl newPasswordResetService(
      AccountRepository accounts,
      AccountAuthorityGenerationRepository generations,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountAuthorityOutboxRepository outbox,
      AccountPasswordResetOperationRepository resetOperations,
      AccountLogoutAllOperationRepository logoutOperations,
      AccountSecurityStateOperationRepository securityOperations,
      AccountPasswordResetDraftSourceChangeRepository resetSourceChanges,
      PasswordResetTokenRepository tokens,
      TestContext context) {
    return new AccountServiceImpl(
        accounts,
        generations,
        sources,
        outbox,
        resetOperations,
        logoutOperations,
        securityOperations,
        resetSourceChanges,
        null, // audit outbox
        null, // connect scopes
        null, // join operations
        null, // email login challenges
        null, // realm grants
        null, // tenant memberships
        null, // account mapper is not on the password-reset path
        null, // profiles
        null, // profile mapper
        null, // payment transactions
        null, // subscriptions
        null, // external account identities
        tokens,
        null, // email verification tokens
        null, // notifications
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        context.transactionManager());
  }

  private ScopeState readState(
      TransactionTemplate transaction,
      AccountAuthorityGenerationRepository generations,
      UUID accountUuid) {
    return transaction.execute(status -> generations.read(AuthorityScope.account(accountUuid)));
  }

  private static void assertDatabaseWriteRejected(Runnable write) {
    assertThatThrownBy(write::run).isInstanceOf(DataAccessException.class);
  }

  private static String sha256Hex(String value) {
    try {
      return HEX.formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record Seed(Account account, String rawResetToken) {}

  private record TestContext(
      DSLContext dsl,
      TransactionTemplate transaction,
      DataSourceTransactionManager transactionManager) {}
}
