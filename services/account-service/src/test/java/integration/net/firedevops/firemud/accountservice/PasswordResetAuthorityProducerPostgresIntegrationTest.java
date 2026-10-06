package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.OperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PasswordResetAuthorityProducerPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "password_reset_source_proof";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String REQUEST_PREFIX = "account-password-reset-request-v1:";
  private static final String EVENT_PREFIX = "account-password-reset-event-v1:";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void commitsPasswordTokenAuthorityFenceClosedEventAndImmutableReceiptTogether() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");

    reset(fixture, seed.rawToken(), "new-password-one");

    Account account = account(fixture, seed.accountId());
    ScopeState authority = readAuthority(fixture, seed.accountUuid());
    String streamKey = streamKey(seed.accountUuid());
    PasswordResetReceipt receipt =
        transaction(
            fixture.transaction(),
            () -> fixture.operations().findByTokenHash(tokenHash(seed.rawToken())).orElseThrow());
    var checkpoint =
        transaction(
            fixture.transaction(), () -> fixture.outbox().readCheckpoint(streamKey).orElseThrow());
    var event =
        transaction(
            fixture.transaction(),
            () -> fixture.outbox().findEvent(streamKey, receipt.outboxSequence()).orElseThrow());
    var decoded =
        PasswordResetAuthorityEventV1Codec.verify(
            new String(event.payload(), StandardCharsets.UTF_8));
    String payload = new String(event.payload(), StandardCharsets.UTF_8);

    assertThat(account.getPasswordHash()).isNotEqualTo(seed.originalVerifier());
    assertThat(account.getPasswordHash()).startsWith("$argon2");
    assertThat(authority.generation()).isEqualTo(2L);
    assertThat(authority.sourceVersion()).isEqualTo(2L);
    assertThat(authority.issuanceFence().value()).isEqualTo(2L);
    assertThat(authority.issuanceFence().sourceVersion()).isEqualTo(2L);
    assertThat(tokenExists(fixture, seed.rawToken())).isFalse();
    assertThat(receipt.accountId()).isEqualTo(seed.accountId());
    assertThat(receipt.accountUuid()).isEqualTo(seed.accountUuid());
    assertThat(receipt.tokenHash()).isEqualTo(tokenHash(seed.rawToken()));
    assertThat(receipt.tokenExpiresAt()).isEqualTo(seed.deadline());
    assertThat(receipt.passwordVerifierDigest()).isEqualTo(sha256Hex(account.getPasswordHash()));
    assertThat(receipt.eventId()).isEqualTo(EVENT_PREFIX + tokenHash(seed.rawToken()));
    assertThat(receipt.requestId()).isEqualTo(REQUEST_PREFIX + tokenHash(seed.rawToken()));
    assertThat(checkpoint.outboxSequence()).isEqualTo(receipt.outboxSequence());
    assertThat(checkpoint.sourceEventId()).isEqualTo(receipt.eventId());
    assertThat(checkpoint.sourceEventDigest()).isEqualTo(receipt.eventDigest());
    assertThat(decoded.accountId()).isEqualTo(seed.accountUuid().toString());
    assertThat(decoded.accountAuthorityGeneration()).isEqualTo("2");
    assertThat(decoded.sourceVersion()).isEqualTo("2");
    assertThat(payload)
        .doesNotContain(
            seed.rawToken(),
            "new-password-one",
            account.getPasswordHash(),
            seed.originalVerifier());
    assertThat(countReceipts(fixture, seed.accountId())).isEqualTo(1L);
    assertThat(countEvents(fixture, streamKey)).isEqualTo(1L);
    byte[] tokenHashBytes = HexFormat.of().parseHex(tokenHash(seed.rawToken()));
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_password_reset_operation_receipts "
                            + "SET request_digest = request_digest WHERE token_hash = ?",
                        tokenHashBytes))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_password_reset_operation_receipts WHERE token_hash = ?",
                        tokenHashBytes))
        .isInstanceOf(DataAccessException.class);
    assertThat(countReceipts(fixture, seed.accountId())).isEqualTo(1L);
  }

  @Test
  void exactRetryRecoversLostResponseWithoutAnySecondMutation() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");

    reset(fixture, seed.rawToken(), "recovered-password");
    StoredState committed = snapshot(fixture, seed);
    reset(fixture, seed.rawToken(), "recovered-password");

    assertThat(snapshot(fixture, seed)).isEqualTo(committed);
  }

  @Test
  void concurrentExactRetriesCommitOneSourceMutation() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> concurrentReset(fixture, seed, ready, start));
      var second = executor.submit(() -> concurrentReset(fixture, seed, ready, start));
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(45, TimeUnit.SECONDS);
      second.get(45, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    StoredState state = snapshot(fixture, seed);
    assertThat(state.generation()).isEqualTo(2L);
    assertThat(state.sourceVersion()).isEqualTo(2L);
    assertThat(state.issuanceFence()).isEqualTo(2L);
    assertThat(state.tokenCount()).isZero();
    assertThat(state.receiptCount()).isEqualTo(1L);
    assertThat(state.eventCount()).isEqualTo(1L);
  }

  @Test
  void changedRetryConflictsAndConsecutiveResetsRequireExactPriorSourceProof() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");
    reset(fixture, seed.rawToken(), "first-password");
    StoredState committed = snapshot(fixture, seed);

    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), "different-password"))
        .isInstanceOf(OperationConflictException.class);
    assertThat(snapshot(fixture, seed)).isEqualTo(committed);

    String secondToken = "second-token-" + UUID.randomUUID();
    LocalDateTime secondDeadline = LocalDateTime.now().plusHours(2);
    transaction(
        fixture.transaction(),
        () -> {
          Account owner = fixture.accounts().findById(seed.accountId()).orElseThrow();
          PasswordResetToken token = new PasswordResetToken();
          token.setAccount(owner);
          token.setToken(secondToken);
          token.setExpiresAt(secondDeadline);
          fixture.tokens().save(token);
          return null;
        });
    reset(fixture, secondToken, "second-password");

    ScopeState authority = readAuthority(fixture, seed.accountUuid());
    assertThat(authority.generation()).isEqualTo(3L);
    assertThat(authority.sourceVersion()).isEqualTo(3L);
    assertThat(authority.issuanceFence().value()).isEqualTo(3L);
    assertThat(countEvents(fixture, streamKey(seed.accountUuid()))).isEqualTo(2L);
    assertThat(countReceipts(fixture, seed.accountId())).isEqualTo(2L);
  }

  @Test
  void appendAndReceiptReadbackFailuresRollBackEveryEarlierMutation() {
    Fixture appendFailure = newFixture();
    Seed appendSeed = seedAccountAndToken(appendFailure, "initial-password");
    appendFailure
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "ADD CONSTRAINT reject_password_reset_producer_event "
                + "CHECK (event_id NOT LIKE 'account-password-reset-event-v1:%')");

    assertThatThrownBy(() -> reset(appendFailure, appendSeed.rawToken(), "append-failure-password"))
        .isInstanceOf(RuntimeException.class);
    assertUnchangedAfterRollback(appendFailure, appendSeed);

    Fixture readbackFailure = newFixture();
    Seed readbackSeed = seedAccountAndToken(readbackFailure, "initial-password");
    AccountPasswordResetOperationRepository hideInsertedReceipt =
        new AccountPasswordResetOperationRepository(readbackFailure.transactionDsl()) {
          private final AtomicInteger lookups = new AtomicInteger();

          @Override
          public Optional<PasswordResetReceipt> findByTokenHash(String tokenHash) {
            int lookup = lookups.incrementAndGet();
            if (lookup >= 3) {
              return Optional.empty();
            }
            return super.findByTokenHash(tokenHash);
          }
        };
    AccountServiceImpl service = newService(readbackFailure, hideInsertedReceipt);

    assertThatThrownBy(
            () ->
                transaction(
                    readbackFailure.transaction(),
                    () -> {
                      service.completePasswordReset(
                          new CompletePasswordResetRequest(
                              readbackSeed.rawToken(), "readback-failure-password"));
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt readback is missing");
    assertUnchangedAfterRollback(readbackFailure, readbackSeed);
  }

  @Test
  void issuanceFenceOverflowRollsBackTokenPasswordAuthorityAndOutbox() {
    Fixture fixture = newFixture();
    Seed seed = seedAccountAndToken(fixture, "initial-password");
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_issuance_fences DISABLE TRIGGER account_authority_issuance_fences_monotonic");
    try {
      fixture
          .setupDsl()
          .execute(
              "UPDATE account_authority_issuance_fences "
                  + "SET issuance_fence = ?, source_version = ? WHERE account_uuid = ?",
              Long.MAX_VALUE,
              11L,
              seed.accountUuid());
    } finally {
      fixture
          .setupDsl()
          .execute(
              "ALTER TABLE account_authority_issuance_fences ENABLE TRIGGER account_authority_issuance_fences_monotonic");
    }

    assertThatThrownBy(() -> reset(fixture, seed.rawToken(), "overflow-password"))
        .isInstanceOf(RuntimeException.class);
    assertUnchangedAfterRollback(fixture, seed);
    assertThat(readAuthority(fixture, seed.accountUuid()).issuanceFence().value())
        .isEqualTo(Long.MAX_VALUE);
  }

  private boolean concurrentReset(
      Fixture fixture, Seed seed, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    reset(fixture, seed.rawToken(), "concurrent-password");
    return true;
  }

  private Fixture newFixture() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AccountRepository accounts = new AccountRepository(transactionDsl);
    AccountAuthorityGenerationRepository authority =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(transactionDsl);
    AccountPasswordResetOperationRepository operations =
        new AccountPasswordResetOperationRepository(transactionDsl);
    AccountLogoutAllOperationRepository logoutAllOperations =
        new AccountLogoutAllOperationRepository(transactionDsl);
    PasswordResetTokenRepository tokens = new PasswordResetTokenRepository(transactionDsl);
    return new Fixture(
        setupDsl,
        transactionDsl,
        transactionManager,
        transaction,
        accounts,
        authority,
        outbox,
        operations,
        logoutAllOperations,
        tokens);
  }

  private Seed seedAccountAndToken(Fixture fixture, String initialVerifier) {
    return transaction(
        fixture.transaction(),
        () -> {
          Account account = new Account();
          String unique = UUID.randomUUID().toString();
          account.setUsername("reset-" + unique);
          account.setEmail("reset-" + unique + "@example.test");
          account.setPasswordHash(initialVerifier);
          Account saved = fixture.accounts().save(account);
          fixture.authority().initialize(AuthorityScope.account(saved.getAccountUuid()));

          String tokenValue = "reset-token-" + unique;
          LocalDateTime deadline = LocalDateTime.now().plusHours(2);
          PasswordResetToken token = new PasswordResetToken();
          token.setAccount(saved);
          token.setToken(tokenValue);
          token.setExpiresAt(deadline);
          fixture.tokens().save(token);
          LocalDateTime storedDeadline =
              fixture.tokens().findByToken(tokenValue).orElseThrow().getExpiresAt();
          return new Seed(
              saved.getId(), saved.getAccountUuid(), tokenValue, storedDeadline, initialVerifier);
        });
  }

  private AccountServiceImpl newService(
      Fixture fixture, AccountPasswordResetOperationRepository operations) {
    return new AccountServiceImpl(
        fixture.accounts(),
        fixture.authority(),
        fixture.outbox(),
        operations,
        fixture.logoutAllOperations(),
        new net.firedevops.firemud.accountservice.repository
            .AccountSecurityStateOperationRepository(fixture.transactionDsl()),
        null, // audit outbox
        null, // connect scopes
        null, // join operations
        null, // membership transition receipts
        null, // membership authority producer
        null, // email login challenges
        null, // realm grants
        null, // tenant memberships
        null, // membership role snapshots
        null, // account mapper is not on the password-reset path
        null, // profiles
        null, // profile mapper
        null, // payment transactions
        null, // subscriptions
        null, // external account identities
        fixture.tokens(),
        null, // email verification tokens
        null, // notifications
        null, // email delivery
        null, // mail properties
        null, // token properties
        null, // JWT auth properties
        null, // game session client
        null, // entity management client
        null, // JWT utility
        null, // session service
        fixture.transactionManager());
  }

  private void reset(Fixture fixture, String rawToken, String password) {
    AccountServiceImpl service = newService(fixture, fixture.operations());
    transaction(
        fixture.transaction(),
        () -> {
          service.completePasswordReset(new CompletePasswordResetRequest(rawToken, password));
          return null;
        });
  }

  private void assertUnchangedAfterRollback(Fixture fixture, Seed seed) {
    Account account = account(fixture, seed.accountId());
    ScopeState authority = readAuthority(fixture, seed.accountUuid());
    assertThat(account.getPasswordHash()).isEqualTo(seed.originalVerifier());
    assertThat(tokenExists(fixture, seed.rawToken())).isTrue();
    assertThat(authority.generation()).isEqualTo(1L);
    assertThat(authority.sourceVersion()).isEqualTo(1L);
    assertThat(countReceipts(fixture, seed.accountId())).isZero();
    assertThat(countEvents(fixture, streamKey(seed.accountUuid()))).isZero();
    assertThat(countRows(fixture.setupDsl(), "account_authority_outbox_streams")).isZero();
  }

  private StoredState snapshot(Fixture fixture, Seed seed) {
    Account account = account(fixture, seed.accountId());
    ScopeState authority = readAuthority(fixture, seed.accountUuid());
    return new StoredState(
        account.getPasswordHash(),
        authority.generation(),
        authority.sourceVersion(),
        authority.issuanceFence().value(),
        tokenExists(fixture, seed.rawToken()) ? 1L : 0L,
        countReceipts(fixture, seed.accountId()),
        countEvents(fixture, streamKey(seed.accountUuid())));
  }

  private Account account(Fixture fixture, long accountId) {
    return transaction(
        fixture.transaction(), () -> fixture.accounts().findById(accountId).orElseThrow());
  }

  private ScopeState readAuthority(Fixture fixture, UUID accountUuid) {
    return transaction(
        fixture.transaction(), () -> fixture.authority().read(AuthorityScope.account(accountUuid)));
  }

  private boolean tokenExists(Fixture fixture, String rawToken) {
    return transaction(
        fixture.transaction(), () -> fixture.tokens().findByToken(rawToken).isPresent());
  }

  private long countReceipts(Fixture fixture, long accountId) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_password_reset_operation_receipts WHERE account_id = ?",
                accountId)
            .fetchOne(0, Long.class),
        "Password-reset receipt count readback is missing");
  }

  private long countEvents(Fixture fixture, String streamKey) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class),
        "Password-reset event count readback is missing");
  }

  private long countRows(DSLContext dsl, String table) {
    return Objects.requireNonNull(
        dsl.resultQuery("SELECT COUNT(*) FROM " + table).fetchOne(0, Long.class),
        "Password-reset row count readback is missing");
  }

  private String streamKey(UUID accountUuid) {
    return STREAM_PREFIX + accountUuid;
  }

  private String tokenHash(String rawToken) {
    return sha256Hex(rawToken);
  }

  private String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent password-reset barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Concurrent password-reset proof was interrupted", interrupted);
    }
  }

  private <T> T transaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record Fixture(
      DSLContext setupDsl,
      DSLContext transactionDsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transaction,
      AccountRepository accounts,
      AccountAuthorityGenerationRepository authority,
      AccountAuthorityOutboxRepository outbox,
      AccountPasswordResetOperationRepository operations,
      AccountLogoutAllOperationRepository logoutAllOperations,
      PasswordResetTokenRepository tokens) {}

  private record Seed(
      long accountId,
      UUID accountUuid,
      String rawToken,
      LocalDateTime deadline,
      String originalVerifier) {}

  private record StoredState(
      String passwordVerifier,
      long generation,
      long sourceVersion,
      long issuanceFence,
      long tokenCount,
      long receiptCount,
      long eventCount) {}
}
