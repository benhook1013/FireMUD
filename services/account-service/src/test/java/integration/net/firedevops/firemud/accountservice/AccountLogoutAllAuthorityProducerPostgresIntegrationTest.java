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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository.LogoutAllReceipt;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer.LogoutAllResult;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
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
class AccountLogoutAllAuthorityProducerPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "logout_src";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String EVENT_ID_PREFIX = "account-logout-all-event-v1:";
  private static final long MAX_COUNTER = Long.MAX_VALUE;
  private static final String TOKEN_PROFILE = "control-ui";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void commitsAccountGenerationFenceClosedEventCheckpointAndImmutableReceiptTogether() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);

    LogoutAllResult result =
        commit(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());

    ScopeState current = authority(fixture, seed);
    LogoutAllReceipt receipt = logoutReceipt(fixture, requestId);
    var event =
        transaction(
            fixture.transaction(),
            () -> fixture.outbox().findEvent(streamKey(seed.accountUuid()), 1L).orElseThrow());
    var checkpoint =
        transaction(
            fixture.transaction(),
            () -> fixture.outbox().readCheckpoint(streamKey(seed.accountUuid())).orElseThrow());
    var decoded =
        AccountLogoutAllAuthorityEventV1Codec.verify(
            new String(event.payload(), StandardCharsets.UTF_8));

    assertThat(result).isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);
    assertThat(current.generation()).isEqualTo(2L);
    assertThat(current.sourceVersion()).isEqualTo(2L);
    assertThat(current.issuanceFence().value()).isEqualTo(2L);
    assertThat(current.issuanceFence().sourceVersion()).isEqualTo(2L);
    assertThat(receipt.accountId()).isEqualTo(seed.accountId());
    assertThat(receipt.accountUuid()).isEqualTo(seed.accountUuid());
    assertThat(receipt.requestId()).isEqualTo(requestId);
    assertThat(receipt.requestDigestVersion()).isEqualTo(1);
    assertThat(receipt.requestDigest()).isEqualTo(requestDigest);
    assertThat(receipt.presentedTokenHash()).isEqualTo(tokenHash);
    assertThat(receipt.tokenProfile()).isEqualTo(TOKEN_PROFILE);
    assertThat(receipt.operationKind()).isEqualTo("ACCOUNT_LOGOUT_ALL");
    assertThat(receipt.lifecycleResult()).isEqualTo("LOGOUT_ALL_COMMITTED");
    assertThat(receipt.accountAuthorityGeneration()).isEqualTo(2L);
    assertThat(receipt.accountSourceVersion()).isEqualTo(2L);
    assertThat(receipt.issuanceFence()).isEqualTo(2L);
    assertThat(receipt.issuanceFenceSourceVersion()).isEqualTo(2L);
    assertThat(receipt.outboxSequence()).isEqualTo(1L);
    assertThat(receipt.eventId()).isEqualTo(EVENT_ID_PREFIX + requestId);
    assertThat(checkpoint.outboxSequence()).isEqualTo(receipt.outboxSequence());
    assertThat(checkpoint.sourceEventId()).isEqualTo(receipt.eventId());
    assertThat(checkpoint.sourceEventDigest()).isEqualTo(receipt.eventDigest());
    assertThat(decoded.requestId()).isEqualTo(requestId.toString());
    assertThat(decoded.accountId()).isEqualTo(seed.accountUuid().toString());
    assertThat(decoded.accountAuthorityGeneration()).isEqualTo("2");
    assertThat(decoded.sourceVersion()).isEqualTo("2");
    assertThat(new String(event.payload(), StandardCharsets.UTF_8)).doesNotContain(tokenHash);
    assertThat(count(fixture, "account_logout_all_operation_receipts")).isEqualTo(1L);
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(1L);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_logout_all_operation_receipts "
                            + "SET request_digest = request_digest WHERE request_id = ?",
                        requestId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_logout_all_operation_receipts WHERE request_id = ?",
                        requestId))
        .isInstanceOf(DataAccessException.class);
    assertThat(logoutReceipt(fixture, requestId)).isEqualTo(receipt);
  }

  @Test
  void exactRetryRecoversOriginalResultAfterLaterSourceAdvanceWithoutRewritingCounters() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID firstRequest = UUID.randomUUID();
    String firstTokenHash = digest("presented-token:" + firstRequest);
    String firstDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, firstTokenHash);
    LogoutAllResult firstResult =
        commit(fixture, seed, firstRequest, firstDigest, firstTokenHash, seed.initialState());
    LogoutAllReceipt firstReceipt = logoutReceipt(fixture, firstRequest);

    UUID laterRequest = UUID.randomUUID();
    String laterTokenHash = digest("presented-token:" + laterRequest);
    commit(
        fixture,
        seed,
        laterRequest,
        logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, laterTokenHash),
        laterTokenHash,
        authority(fixture, seed));
    StoredState afterLaterAdvance = snapshot(fixture, seed);

    LogoutAllResult retry =
        commit(fixture, seed, firstRequest, firstDigest, firstTokenHash, seed.initialState());

    assertThat(retry).isEqualTo(firstResult);
    assertThat(logoutReceipt(fixture, firstRequest)).isEqualTo(firstReceipt);
    assertThat(snapshot(fixture, seed)).isEqualTo(afterLaterAdvance);
  }

  @Test
  void changedRequestBindingsConflictWithoutMutation() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    commit(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
    StoredState committed = snapshot(fixture, seed);
    LogoutAllReceipt committedReceipt = logoutReceipt(fixture, requestId);
    var committedEvent =
        transaction(
            fixture.transaction(),
            () -> fixture.outbox().findEvent(streamKey(seed.accountUuid()), 1L).orElseThrow());

    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    requestId,
                    digest("changed-request:" + requestId),
                    tokenHash,
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                producer(fixture)
                    .commit(
                        requestId,
                        2,
                        requestDigest,
                        TOKEN_PROFILE,
                        tokenHash,
                        account(fixture, seed),
                        seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    requestId,
                    requestDigest,
                    digest("changed-token:" + requestId),
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    requestId,
                    logoutAllDigest(seed.accountUuid(), "player-bootstrap", tokenHash),
                    "player-bootstrap",
                    tokenHash,
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    UUID.randomUUID(),
                    digest("changed-request-id"),
                    tokenHash,
                    seed.initialState()))
        .isInstanceOf(AccountLogoutAllAuthorityEventProducer.OperationConflictException.class);
    UUID unboundRequestId = UUID.randomUUID();
    String unboundTokenHash = digest("unbound-token:" + unboundRequestId);
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    unboundRequestId,
                    digest("unbound-request-digest"),
                    unboundTokenHash,
                    authority(fixture, seed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical caller bindings");

    assertThat(snapshot(fixture, seed)).isEqualTo(committed);
    assertThat(logoutReceipt(fixture, requestId)).isEqualTo(committedReceipt);
    assertThat(
            transaction(
                fixture.transaction(),
                () -> fixture.outbox().findEvent(streamKey(seed.accountUuid()), 1L).orElseThrow()))
        .isEqualTo(committedEvent);
  }

  @Test
  void concurrentExactRequestCommitsOnlyOneAuthorityAdvanceAndEvent() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  concurrentCommit(
                      fixture, seed, requestId, requestDigest, tokenHash, ready, start));
      var second =
          executor.submit(
              () ->
                  concurrentCommit(
                      fixture, seed, requestId, requestDigest, tokenHash, ready, start));
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get(45, TimeUnit.SECONDS)).isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);
      assertThat(second.get(45, TimeUnit.SECONDS)).isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    ScopeState current = authority(fixture, seed);
    assertThat(current.generation()).isEqualTo(2L);
    assertThat(current.sourceVersion()).isEqualTo(2L);
    assertThat(current.issuanceFence().value()).isEqualTo(2L);
    assertThat(count(fixture, "account_logout_all_operation_receipts")).isEqualTo(1L);
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(1L);
  }

  @Test
  void eventInsertFailureRollsBackGenerationFenceCheckpointAndReceipt() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    fixture
        .setupDsl()
        .execute(
            "ALTER TABLE account_authority_outbox_events "
                + "ADD CONSTRAINT reject_logout_all_event "
                + "CHECK (event_id NOT LIKE 'account-logout-all-event-v1:%')");

    UUID rollbackRequest = UUID.randomUUID();
    String rollbackTokenHash = digest("rollback-token");
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    rollbackRequest,
                    logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, rollbackTokenHash),
                    rollbackTokenHash,
                    seed.initialState()))
        .isInstanceOf(RuntimeException.class);

    assertThat(snapshot(fixture, seed)).isEqualTo(new StoredState(1L, 1L, 1L, 1L, 0L, 0L, 0L));
    assertThat(count(fixture, "account_authority_outbox_streams")).isZero();
  }

  @Test
  void sourceCounterOverflowFailsBeforeAnyCutoffMutation() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture, MAX_COUNTER);
    seedLatestLogoutReceiptAtMaximumCounter(fixture, seed);
    ScopeState maximum = authority(fixture, seed);
    StoredState before = snapshot(fixture, seed);

    UUID overflowRequest = UUID.randomUUID();
    String overflowTokenHash = digest("overflow-token");
    assertThatThrownBy(
            () ->
                commit(
                    fixture,
                    seed,
                    overflowRequest,
                    logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, overflowTokenHash),
                    overflowTokenHash,
                    maximum))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exhausted");

    assertThat(snapshot(fixture, seed)).isEqualTo(before);
  }

  @Test
  void passwordResetThenLogoutAllPreservesBothReceiptsAndResetRetry() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    reset(fixture, seed, "reset-before-logout");
    PasswordResetReceipt resetReceipt = passwordResetReceipt(fixture, seed);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);

    commit(
        fixture,
        seed,
        requestId,
        logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash),
        tokenHash,
        authority(fixture, seed));
    LogoutAllReceipt logoutReceipt = logoutReceipt(fixture, requestId);

    reset(fixture, seed, "reset-before-logout");

    assertThat(passwordResetReceipt(fixture, seed)).isEqualTo(resetReceipt);
    assertThat(logoutReceipt(fixture, requestId)).isEqualTo(logoutReceipt);
    assertThat(authority(fixture, seed).generation()).isEqualTo(3L);
    assertThat(authority(fixture, seed).sourceVersion()).isEqualTo(3L);
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(2L);
  }

  @Test
  void logoutAllThenPasswordResetValidatesMixedLatestSourceAndPreservesLogoutReceipt() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    UUID requestId = UUID.randomUUID();
    String tokenHash = digest("presented-token:" + requestId);

    commit(
        fixture,
        seed,
        requestId,
        logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash),
        tokenHash,
        seed.initialState());
    LogoutAllReceipt logoutReceipt = logoutReceipt(fixture, requestId);
    reset(fixture, seed, "reset-after-logout");

    assertThat(logoutReceipt(fixture, requestId)).isEqualTo(logoutReceipt);
    assertThat(passwordResetReceipt(fixture, seed).outboxSequence()).isEqualTo(2L);
    assertThat(authority(fixture, seed).generation()).isEqualTo(3L);
    assertThat(authority(fixture, seed).sourceVersion()).isEqualTo(3L);
    assertThat(count(fixture, "account_authority_outbox_events")).isEqualTo(2L);
  }

  private LogoutAllResult concurrentCommit(
      Fixture fixture,
      Seed seed,
      UUID requestId,
      String requestDigest,
      String tokenHash,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    await(start);
    return commit(fixture, seed, requestId, requestDigest, tokenHash, seed.initialState());
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
    AccountPasswordResetOperationRepository passwordResetOperations =
        new AccountPasswordResetOperationRepository(transactionDsl);
    AccountLogoutAllOperationRepository logoutOperations =
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
        passwordResetOperations,
        logoutOperations,
        tokens);
  }

  private Seed seedAccount(Fixture fixture) {
    return seedAccount(fixture, 1L);
  }

  private Seed seedAccount(Fixture fixture, long initialCounter) {
    return transaction(
        fixture.transaction(),
        () -> {
          Account account = new Account();
          String unique = UUID.randomUUID().toString();
          account.setUsername("logout-all-" + unique);
          account.setEmail("logout-all-" + unique + "@example.test");
          account.setPasswordHash("initial-verifier");
          Account saved = fixture.accounts().save(account);
          if (initialCounter == 1L) {
            fixture.authority().initialize(AuthorityScope.account(saved.getAccountUuid()));
          } else {
            // Seed a synthetic retained maximum by INSERT, not an illegal monotonic jump.
            // All migration constraints and update triggers stay enabled for the whole test.
            fixture
                .transactionDsl()
                .execute(
                    "INSERT INTO account_authority_generations "
                        + "(scope_kind, account_uuid, generation, source_version) "
                        + "VALUES ('ACCOUNT', ?, ?, ?)",
                    saved.getAccountUuid(),
                    initialCounter,
                    initialCounter);
            fixture
                .transactionDsl()
                .execute(
                    "INSERT INTO account_authority_issuance_fences "
                        + "(account_uuid, issuance_fence, source_version) VALUES (?, ?, ?)",
                    saved.getAccountUuid(),
                    initialCounter,
                    initialCounter);
          }
          PasswordResetToken token = new PasswordResetToken();
          String rawToken = "reset-token-" + unique;
          LocalDateTime deadline = LocalDateTime.now().plusHours(2);
          token.setAccount(saved);
          token.setToken(rawToken);
          token.setExpiresAt(deadline);
          fixture.tokens().save(token);
          return new Seed(
              saved.getId(),
              saved.getAccountUuid(),
              rawToken,
              fixture.authority().read(AuthorityScope.account(saved.getAccountUuid())));
        });
  }

  private LogoutAllResult commit(
      Fixture fixture,
      Seed seed,
      UUID requestId,
      String requestDigest,
      String tokenHash,
      ScopeState expected) {
    return commit(fixture, seed, requestId, requestDigest, TOKEN_PROFILE, tokenHash, expected);
  }

  private LogoutAllResult commit(
      Fixture fixture,
      Seed seed,
      UUID requestId,
      String requestDigest,
      String tokenProfile,
      String tokenHash,
      ScopeState expected) {
    return producer(fixture)
        .commit(
            requestId, 1, requestDigest, tokenProfile, tokenHash, account(fixture, seed), expected);
  }

  private AccountLogoutAllAuthorityEventProducer producer(Fixture fixture) {
    AccountAuthoritySourceEventReadback sourceReadback =
        new AccountAuthoritySourceEventReadback(
            fixture.outbox(), fixture.passwordResetOperations(), fixture.logoutOperations());
    return new AccountLogoutAllAuthorityEventProducer(
        fixture.accounts(),
        fixture.authority(),
        fixture.outbox(),
        fixture.logoutOperations(),
        sourceReadback,
        fixture.transactionDsl(),
        fixture.transactionManager());
  }

  private void reset(Fixture fixture, Seed seed, String password) {
    AccountServiceImpl service =
        new AccountServiceImpl(
            fixture.accounts(),
            fixture.authority(),
            fixture.outbox(),
            fixture.passwordResetOperations(),
            fixture.logoutOperations(),
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
            fixture.tokens(),
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
            fixture.transactionManager());
    transaction(
        fixture.transaction(),
        () -> {
          service.completePasswordReset(
              new CompletePasswordResetRequest(seed.rawToken(), password));
          return null;
        });
  }

  private void seedLatestLogoutReceiptAtMaximumCounter(Fixture fixture, Seed seed) {
    UUID priorRequestId = UUID.randomUUID();
    String tokenHash = digest("prior-max-token");
    String requestDigest = logoutAllDigest(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    String streamKey = streamKey(seed.accountUuid());
    String eventId = EVENT_ID_PREFIX + priorRequestId;
    transaction(
        fixture.transaction(),
        () -> {
          var event =
              AccountLogoutAllAuthorityEventV1Codec.seal(
                  Map.ofEntries(
                      Map.entry(
                          "schemaVersion", AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION),
                      Map.entry("eventType", AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE),
                      Map.entry("eventId", eventId),
                      Map.entry("requestId", priorRequestId.toString()),
                      Map.entry("accountId", seed.accountUuid().toString()),
                      Map.entry("sourceScope", "account/" + seed.accountUuid()),
                      Map.entry("outboxStreamKey", streamKey),
                      Map.entry("outboxSequence", "1"),
                      Map.entry("accountAuthorityGeneration", Long.toString(MAX_COUNTER)),
                      Map.entry("sourceVersion", Long.toString(MAX_COUNTER)),
                      Map.entry(
                          "accountSecurityCutoff",
                          Map.of(
                              "accountAuthorityGeneration",
                              Long.toString(MAX_COUNTER),
                              "outboxStreamKey",
                              streamKey,
                              "outboxSequence",
                              "1"))));
          var appended =
              fixture
                  .outbox()
                  .append(
                      streamKey,
                      priorRequestId.toString(),
                      event.eventId(),
                      event.eventDigest(),
                      event.canonicalJsonUtf8());
          ScopeState maximum = fixture.authority().read(AuthorityScope.account(seed.accountUuid()));
          fixture
              .logoutOperations()
              .insert(
                  LogoutAllReceipt.committed(
                      priorRequestId,
                      seed.accountId(),
                      seed.accountUuid(),
                      requestDigest,
                      tokenHash,
                      TOKEN_PROFILE,
                      streamKey,
                      appended.outboxSequence(),
                      eventId,
                      event.eventDigest(),
                      maximum,
                      maximum.issuanceFence()));
          return null;
        });
  }

  private Account account(Fixture fixture, Seed seed) {
    return transaction(
        fixture.transaction(), () -> fixture.accounts().findById(seed.accountId()).orElseThrow());
  }

  private ScopeState authority(Fixture fixture, Seed seed) {
    return transaction(
        fixture.transaction(),
        () -> fixture.authority().read(AuthorityScope.account(seed.accountUuid())));
  }

  private LogoutAllReceipt logoutReceipt(Fixture fixture, UUID requestId) {
    return transaction(
        fixture.transaction(),
        () -> fixture.logoutOperations().findByRequestId(requestId).orElseThrow());
  }

  private PasswordResetReceipt passwordResetReceipt(Fixture fixture, Seed seed) {
    return transaction(
        fixture.transaction(),
        () ->
            fixture
                .passwordResetOperations()
                .findByTokenHash(digest(seed.rawToken()))
                .orElseThrow());
  }

  private StoredState snapshot(Fixture fixture, Seed seed) {
    ScopeState state = authority(fixture, seed);
    return new StoredState(
        state.generation(),
        state.sourceVersion(),
        state.issuanceFence().value(),
        state.issuanceFence().sourceVersion(),
        count(fixture, "account_logout_all_operation_receipts"),
        count(fixture, "account_password_reset_operation_receipts"),
        count(fixture, "account_authority_outbox_events"));
  }

  private long count(Fixture fixture, String table) {
    return Objects.requireNonNull(
        fixture.setupDsl().resultQuery("SELECT COUNT(*) FROM " + table).fetchOne(0, Long.class),
        "Logout-all source row count is missing");
  }

  private String streamKey(UUID accountUuid) {
    return STREAM_PREFIX + accountUuid;
  }

  private String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String logoutAllDigest(UUID accountUuid, String tokenProfile, String tokenHash) {
    return AccountLogoutRequestDigest.accountLogoutAll(accountUuid, tokenProfile, tokenHash);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent logout-all barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent logout-all proof was interrupted", interrupted);
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
      AccountPasswordResetOperationRepository passwordResetOperations,
      AccountLogoutAllOperationRepository logoutOperations,
      PasswordResetTokenRepository tokens) {}

  private record Seed(long accountId, UUID accountUuid, String rawToken, ScopeState initialState) {}

  private record StoredState(
      long generation,
      long sourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      long logoutReceiptCount,
      long passwordResetReceiptCount,
      long eventCount) {}
}
