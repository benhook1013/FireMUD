package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeIdentity;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeRepository;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginResponseEnvelope;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginTokenIdentityFence;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginTokenIdentityFenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL proof for immutable initial bare-LOGIN token identity capture. Connect source
 * results, encrypted envelopes, token identity strings and digests in these fixtures are synthetic
 * repository evidence, not signed issuer output, authenticated source delivery or runtime proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountBareLoginTokenIdentityFencePostgresIntegrationTest {
  private static final String SCHEMA = "account_bare_login_token_identity_fence_proof";
  private static final String CAPTURE_TABLE = "account_bare_login_token_identity_fence_captures";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void initialCapturePersistsExactSyntheticEvidenceAndExactRetriesDoNotChurn() {
    TestContext context = newTestContext();
    TokenFixture fixture = pendingFixture(context);

    AccountBareLoginTokenIdentityFence captured =
        inTransaction(context.transaction(), () -> capture(context, fixture));
    AccountBareLoginTokenIdentityFence retry =
        inTransaction(context.transaction(), () -> capture(context, fixture));
    AccountBareLoginTokenIdentityFence required =
        inTransaction(context.transaction(), () -> requireInitial(context, fixture));

    assertThat(captured.schemaName()).isEqualTo(AccountBareLoginTokenIdentityFence.SCHEMA_NAME);
    assertThat(captured.operationId()).isEqualTo(fixture.operationId());
    assertThat(captured.sourceConnectOperationId())
        .isEqualTo(fixture.identity().sourceConnectOperationId());
    assertThat(captured.accountId()).isEqualTo(fixture.account().id());
    assertThat(captured.accountUuid()).isEqualTo(fixture.account().uuid());
    assertThat(captured.tenantId()).isEqualTo(fixture.identity().tenantId());
    assertThat(captured.connectScopeHash())
        .isEqualTo(AccountJoinDigest.tokenHash(fixture.identity().connectScopeId()));
    assertThat(captured.requestId()).isEqualTo(fixture.identity().requestId());
    assertThat(captured.requestDigest()).containsExactly(fixture.requestDigest());
    assertThat(captured.profile()).isEqualTo(AccountBareLoginTokenIdentityFence.PROFILE_NAME);
    assertThat(captured.tokenIdentity()).isEqualTo(fixture.tokenIdentity());
    assertThat(captured.tokenHash()).containsExactly(fixture.tokenHash());
    String persistedTokenHash =
        context
            .dsl()
            .resultQuery(
                "SELECT token_hash FROM " + CAPTURE_TABLE + " WHERE operation_id = ?",
                fixture.operationId())
            .fetchOne(0, String.class);
    assertThat(persistedTokenHash).isEqualTo(HexFormat.of().formatHex(fixture.tokenHash()));
    assertThat(captured.tokenIdentityFence()).isEqualTo(1L);
    assertThat(captured.tokenIdentityFenceSourceVersion()).isEqualTo(1L);
    assertThat(captured.accountIssuanceFence()).isEqualTo(1L);
    assertThat(captured.accountIssuanceFenceSourceVersion()).isEqualTo(1L);
    assertThat(captured.capturedAt()).isNotNull();
    assertThat(retry.sameInitialCapture(captured)).isTrue();
    assertThat(required.sameInitialCapture(captured)).isTrue();
    assertThat(retry.capturedAt()).isEqualTo(captured.capturedAt());
    assertThat(required.capturedAt()).isEqualTo(captured.capturedAt());
    assertThat(captureCount(context, fixture.operationId())).isEqualTo(1L);
  }

  @Test
  void missingRequireInitialDoesNotCreateOrBackfillEvidence() {
    TestContext context = newTestContext();
    TokenFixture fixture = pendingFixture(context);

    assertThatThrownBy(
            () -> inTransaction(context.transaction(), () -> requireInitial(context, fixture)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("absent");
    assertThat(captureCount(context, fixture.operationId())).isZero();
  }

  @Test
  void changedRequestOperationAccountOrTokenEvidenceCannotReplaceCapture() {
    TestContext context = newTestContext();
    TokenFixture fixture = pendingFixture(context);
    AccountBareLoginTokenIdentityFence captured =
        inTransaction(context.transaction(), () -> capture(context, fixture));

    assertCaptureRejected(
        context,
        fixture.identity(),
        fixture.operationId(),
        fixture.account().uuid(),
        digest(22),
        fixture.tokenIdentity(),
        fixture.tokenHash());
    assertCaptureRejected(
        context,
        new AccountBareLoginExchangeIdentity(
            UUID.randomUUID(),
            fixture.identity().accountId(),
            fixture.identity().tenantId(),
            fixture.identity().connectScopeId(),
            fixture.identity().requestId()),
        fixture.operationId(),
        fixture.account().uuid(),
        fixture.requestDigest(),
        fixture.tokenIdentity(),
        fixture.tokenHash());
    assertCaptureRejected(
        context,
        fixture.identity(),
        UUID.randomUUID(),
        fixture.account().uuid(),
        fixture.requestDigest(),
        fixture.tokenIdentity(),
        fixture.tokenHash());
    assertCaptureRejected(
        context,
        new AccountBareLoginExchangeIdentity(
            fixture.identity().sourceConnectOperationId(),
            fixture.identity().accountId(),
            fixture.identity().tenantId(),
            fixture.identity().connectScopeId(),
            "changed-request-" + UUID.randomUUID()),
        fixture.operationId(),
        fixture.account().uuid(),
        fixture.requestDigest(),
        fixture.tokenIdentity(),
        fixture.tokenHash());
    assertCaptureRejected(
        context,
        fixture.identity(),
        fixture.operationId(),
        fixture.account().uuid(),
        fixture.requestDigest(),
        "changed-delegation-jti-" + UUID.randomUUID(),
        fixture.tokenHash());
    assertCaptureRejected(
        context,
        fixture.identity(),
        fixture.operationId(),
        fixture.account().uuid(),
        fixture.requestDigest(),
        fixture.tokenIdentity(),
        digest(23));
    Account foreignAccount = newAccount(context);
    initializeAccountAuthority(context, foreignAccount);
    assertCaptureRejected(
        context,
        fixture.identity(),
        fixture.operationId(),
        foreignAccount.uuid(),
        fixture.requestDigest(),
        fixture.tokenIdentity(),
        fixture.tokenHash());
    assertCaptureRejected(
        context,
        new AccountBareLoginExchangeIdentity(
            fixture.identity().sourceConnectOperationId(),
            foreignAccount.id(),
            fixture.identity().tenantId(),
            fixture.identity().connectScopeId(),
            fixture.identity().requestId()),
        fixture.operationId(),
        foreignAccount.uuid(),
        fixture.requestDigest(),
        fixture.tokenIdentity(),
        fixture.tokenHash());

    AccountBareLoginTokenIdentityFence unchanged =
        inTransaction(context.transaction(), () -> requireInitial(context, fixture));
    assertThat(unchanged.sameInitialCapture(captured)).isTrue();
    assertThat(unchanged.capturedAt()).isEqualTo(captured.capturedAt());
    assertThat(captureCount(context, fixture.operationId())).isEqualTo(1L);
  }

  @Test
  void captureRequiresOutgoingTokenBindingAndRejectsLateTerminalCapture() {
    TestContext context = newTestContext();
    TokenFixture unbound = pendingFixture(context, newAccount(context), false);

    assertThatThrownBy(() -> inTransaction(context.transaction(), () -> capture(context, unbound)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pending operation");
    assertThat(captureCount(context, unbound.operationId())).isZero();

    TokenFixture missingAccountFence = pendingFixture(context, newAccount(context), true);
    assertThatThrownBy(
            () -> inTransaction(context.transaction(), () -> capture(context, missingAccountFence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Current Account issuance fence is absent");
    assertThat(captureCount(context, missingAccountFence.operationId())).isZero();

    TokenFixture terminal = pendingFixture(context);
    AccountBareLoginResponseEnvelope terminalEnvelope =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .exchangeRepository()
                    .completeWithEnvelope(
                        terminal.claim(),
                        terminal.requestDigest(),
                        terminal.tokenIdentity(),
                        terminal.tokenHash(),
                        terminal.binding(),
                        encryptedEnvelope(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE)));
    assertThat(terminalEnvelope.operationId()).isEqualTo(terminal.operationId());
    assertThat(
            inTransaction(
                context.transaction(),
                () ->
                    context
                        .exchangeRepository()
                        .find(terminal.identity(), terminal.requestDigest())
                        .orElseThrow()
                        .lifecycle()))
        .isEqualTo(
            net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeOperation
                .Lifecycle.COMMITTED);
    assertThatThrownBy(() -> inTransaction(context.transaction(), () -> capture(context, terminal)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pending operation");
    assertThat(captureCount(context, terminal.operationId())).isZero();
  }

  @Test
  void missingAccountAndMismatchedCanonicalAccountIdentityAreDenied() {
    TestContext context = newTestContext();
    TokenFixture fixture = pendingFixture(context);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .captureInitial(
                                fixture.identity(),
                                fixture.operationId(),
                                UUID.randomUUID(),
                                fixture.requestDigest(),
                                fixture.tokenIdentity(),
                                fixture.tokenHash())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account row is absent");
    assertThat(captureCount(context, fixture.operationId())).isZero();

    Account foreignAccount = newAccount(context);
    initializeAccountAuthority(context, foreignAccount);
    assertSqlConstraint(
        () -> insertCaptureDirect(context, fixture, foreignAccount.uuid(), 1L, 1L),
        "account_bare_login_token_identity_fence_account_binding");
    assertThat(captureCount(context, fixture.operationId())).isZero();
  }

  @Test
  void rollbackLeavesNoPartialInitialCapture() {
    TestContext context = newTestContext();
    TokenFixture fixture = pendingFixture(context);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> {
                      capture(context, fixture);
                      throw new IllegalStateException("test rollback after capture");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("test rollback");
    assertThat(captureCount(context, fixture.operationId())).isZero();

    AccountBareLoginTokenIdentityFence committed =
        inTransaction(context.transaction(), () -> capture(context, fixture));
    assertThat(committed.operationId()).isEqualTo(fixture.operationId());
    assertThat(captureCount(context, fixture.operationId())).isEqualTo(1L);
  }

  @Test
  void concurrentExactInitialCapturesConvergeOnOneImmutableRow() throws Exception {
    TestContext context = newTestContext();
    TokenFixture fixture = pendingFixture(context);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<AccountBareLoginTokenIdentityFence> capture =
          () -> {
            ready.countDown();
            await(start, "exact capture race did not start");
            return inTransaction(context.transaction(), () -> capture(context, fixture));
          };
      Future<AccountBareLoginTokenIdentityFence> first = executor.submit(capture);
      Future<AccountBareLoginTokenIdentityFence> second = executor.submit(capture);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      AccountBareLoginTokenIdentityFence firstResult = first.get(20, TimeUnit.SECONDS);
      AccountBareLoginTokenIdentityFence secondResult = second.get(20, TimeUnit.SECONDS);
      assertThat(firstResult.sameInitialCapture(secondResult)).isTrue();
      assertThat(firstResult.capturedAt()).isEqualTo(secondResult.capturedAt());
      assertThat(captureCount(context, fixture.operationId())).isEqualTo(1L);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void accountFenceAdvanceSerializesCaptureAndMakesHistoricalCurrentEvidenceStale()
      throws Exception {
    TestContext context = newTestContext();
    Account account = newAccount(context);
    initializeAccountAuthority(context, account);
    TokenFixture first = pendingFixture(context, account, true);
    TokenFixture afterAdvance = pendingFixture(context, account, true);
    AccountBareLoginTokenIdentityFence firstCapture =
        inTransaction(context.transaction(), () -> capture(context, first));

    CountDownLatch advanced = new CountDownLatch(1);
    CountDownLatch releaseAdvance = new CountDownLatch(1);
    CountDownLatch captureStarted = new CountDownLatch(1);
    AtomicInteger blockerPid = new AtomicInteger();
    AtomicInteger waiterPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AccountAuthorityGenerationRepository.ScopeState> advance =
          executor.submit(
              () ->
                  inTransaction(
                      context.transaction(),
                      () -> {
                        blockerPid.set(currentBackendPid(context));
                        AuthorityScope scope = AuthorityScope.account(account.uuid());
                        var current = context.authorityRepository().read(scope);
                        var next =
                            context.authorityRepository().advance(current, current.issuanceFence());
                        advanced.countDown();
                        await(releaseAdvance, "Account fence advance was not released");
                        return next;
                      }));
      assertThat(advanced.await(10, TimeUnit.SECONDS)).isTrue();

      Future<AccountBareLoginTokenIdentityFence> captureAfterAdvance =
          executor.submit(
              () ->
                  inTransaction(
                      context.transaction(),
                      () -> {
                        waiterPid.set(currentBackendPid(context));
                        captureStarted.countDown();
                        return capture(context, afterAdvance);
                      }));
      assertThat(captureStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(
              awaitDatabaseBlock(context, waiterPid.get(), blockerPid.get(), Duration.ofSeconds(5)))
          .isTrue();
      releaseAdvance.countDown();

      var advancedState = advance.get(20, TimeUnit.SECONDS);
      AccountBareLoginTokenIdentityFence secondCapture =
          captureAfterAdvance.get(20, TimeUnit.SECONDS);
      assertThat(advancedState.generation()).isEqualTo(2L);
      assertThat(advancedState.sourceVersion()).isEqualTo(2L);
      assertThat(secondCapture.accountIssuanceFence()).isEqualTo(2L);
      assertThat(secondCapture.accountIssuanceFenceSourceVersion()).isEqualTo(2L);
      assertThat(firstCapture.accountIssuanceFence()).isEqualTo(1L);
      assertThatThrownBy(
              () -> inTransaction(context.transaction(), () -> requireInitial(context, first)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("differs from its current exact evidence");
      assertThat(captureCount(context, first.operationId())).isEqualTo(1L);
      assertThat(captureCount(context, afterAdvance.operationId())).isEqualTo(1L);
    } finally {
      releaseAdvance.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void actualOwnerTransactionMustBeWritableReadCommittedAndConnectionBound() {
    TestContext context = newTestContext();
    TokenFixture fixture = pendingFixture(context);

    assertThatThrownBy(() -> capture(context, fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owner transaction");

    TransactionTemplate readOnly =
        new TransactionTemplate(new DataSourceTransactionManager(context.dataSource()));
    readOnly.setReadOnly(true);
    assertThatThrownBy(() -> inTransaction(readOnly, () -> capture(context, fixture)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable");

    TransactionTemplate repeatableRead =
        new TransactionTemplate(new DataSourceTransactionManager(context.dataSource()));
    repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(() -> inTransaction(repeatableRead, () -> capture(context, fixture)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ COMMITTED");
    assertThat(captureCount(context, fixture.operationId())).isZero();
  }

  @Test
  void directSqlMismatchedCurrentFenceInsertAndCaptureMutationAreDenied() {
    TestContext context = newTestContext();
    Account account = newAccount(context);
    initializeAccountAuthority(context, account);
    TokenFixture retained = pendingFixture(context, account, true);
    AccountBareLoginTokenIdentityFence stored =
        inTransaction(context.transaction(), () -> capture(context, retained));

    var state =
        inTransaction(
            context.transaction(),
            () -> context.authorityRepository().read(AuthorityScope.account(account.uuid())));
    inTransaction(
        context.transaction(),
        () -> context.authorityRepository().advance(state, state.issuanceFence()));
    TokenFixture staleDirectInsert = pendingFixture(context, account, true);
    assertSqlConstraint(
        () ->
            insertCaptureDirect(
                context,
                staleDirectInsert,
                account.uuid(),
                stored.accountIssuanceFence(),
                stored.accountIssuanceFenceSourceVersion()),
        "account_bare_login_token_identity_fence_account_issuance_binding");
    assertThat(captureCount(context, staleDirectInsert.operationId())).isZero();

    assertSqlConstraint(
        () ->
            context
                .dsl()
                .execute(
                    "UPDATE " + CAPTURE_TABLE + " SET request_id = ? WHERE operation_id = ?",
                    "rewritten-request",
                    retained.operationId()),
        "account_bare_login_token_identity_fence_immutable");
    assertSqlConstraint(
        () ->
            context
                .dsl()
                .execute(
                    "DELETE FROM " + CAPTURE_TABLE + " WHERE operation_id = ?",
                    retained.operationId()),
        "account_bare_login_token_identity_fence_immutable");
    assertSqlConstraint(
        () -> context.dsl().execute("TRUNCATE TABLE " + CAPTURE_TABLE),
        "account_bare_login_token_identity_fence_immutable");

    assertThat(readCapture(context, retained.operationId())).isEqualTo(stored);
    assertThat(captureCount(context, retained.operationId())).isEqualTo(1L);
  }

  private TestContext newTestContext() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + SCHEMA);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .placeholders(Map.of("serviceSchema", SCHEMA))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        dsl,
        dataSource,
        new AccountBareLoginExchangeRepository(dsl),
        new AccountConnectTokenIssuanceRepository(dsl),
        new AccountAuthorityGenerationRepository(dsl),
        new AccountBareLoginTokenIdentityFenceRepository(dsl, dataSource),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private TokenFixture pendingFixture(TestContext context) {
    Account account = newAccount(context);
    initializeAccountAuthority(context, account);
    return pendingFixture(context, account, true);
  }

  private TokenFixture pendingFixture(
      TestContext context, Account account, boolean recordOutgoingTokenEvidence) {
    Source source = committedSource(context, account.id());
    AccountBareLoginExchangeIdentity identity = source.exchangeIdentity();
    byte[] requestDigest = uniqueDigest();
    var claim =
        inTransaction(
            context.transaction(),
            () -> context.exchangeRepository().claim(identity, requestDigest));
    String tokenIdentity = "synthetic-delegation-jti-" + UUID.randomUUID();
    byte[] tokenHash = uniqueDigest();
    AccountEnvelopeBinding binding =
        bareLoginBinding(claim.operation().operationId(), identity, requestDigest);
    if (recordOutgoingTokenEvidence) {
      inTransaction(
          context.transaction(),
          () -> {
            context
                .exchangeRepository()
                .recordPendingEvidence(
                    claim,
                    requestDigest,
                    tokenIdentity,
                    tokenHash,
                    binding.contextEvidenceDigest(),
                    binding.authorityTupleDigest(),
                    binding.issuanceFenceDigest(),
                    binding.postconditionDigest());
            return null;
          });
    }
    return new TokenFixture(
        account,
        identity,
        claim.operation().operationId(),
        requestDigest,
        tokenIdentity,
        tokenHash,
        claim,
        binding);
  }

  private Source committedSource(TestContext context, long accountId) {
    AccountConnectTokenIssuanceIdentity sourceIdentity =
        new AccountConnectTokenIssuanceIdentity(
            accountId,
            UUID.randomUUID(),
            "synthetic-connect-scope-" + UUID.randomUUID(),
            "synthetic-connect-request-" + UUID.randomUUID());
    byte[] requestDigest = uniqueDigest();
    var claim =
        inTransaction(
            context.transaction(),
            () -> context.sourceRepository().claim(sourceIdentity, requestDigest));
    AccountEnvelopeBinding binding =
        new AccountEnvelopeBinding(
            AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
            claim.operation().operationId().toString(),
            sourceIdentity.requestId(),
            Long.toString(sourceIdentity.accountId()),
            sourceIdentity.tenantId().toString(),
            sourceIdentity.connectScopeId(),
            null,
            requestDigest,
            uniqueDigest(),
            uniqueDigest(),
            uniqueDigest(),
            uniqueDigest());
    inTransaction(
        context.transaction(),
        () -> {
          context
              .sourceRepository()
              .completeWithEnvelope(
                  claim,
                  requestDigest,
                  net.firedevops.firemud.accountservice.repository
                      .AccountConnectTokenIssuanceOperation.Lifecycle.COMMITTED,
                  "SUCCESS",
                  "synthetic-connect-jti-" + UUID.randomUUID(),
                  uniqueDigest(),
                  binding,
                  encryptedEnvelope(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE));
          return null;
        });
    return new Source(
        claim.operation().operationId(),
        new AccountBareLoginExchangeIdentity(
            claim.operation().operationId(),
            accountId,
            sourceIdentity.tenantId(),
            sourceIdentity.connectScopeId(),
            "synthetic-bare-login-request-" + UUID.randomUUID()));
  }

  private Account newAccount(TestContext context) {
    String unique = UUID.randomUUID().toString().replace("-", "");
    var row =
        context
            .dsl()
            .resultQuery(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                    + "RETURNING id, account_uuid",
                "bare_fence_" + unique.substring(0, 12),
                unique + "@example.test",
                "synthetic-test-hash")
            .fetchOne();
    if (row == null) {
      throw new IllegalStateException("Synthetic Account fixture was not inserted");
    }
    return new Account(row.get("id", Long.class), row.get("account_uuid", UUID.class));
  }

  private void initializeAccountAuthority(TestContext context, Account account) {
    inTransaction(
        context.transaction(),
        () -> context.authorityRepository().initialize(AuthorityScope.account(account.uuid())));
  }

  private AccountBareLoginTokenIdentityFence capture(TestContext context, TokenFixture fixture) {
    return context
        .repository()
        .captureInitial(
            fixture.identity(),
            fixture.operationId(),
            fixture.account().uuid(),
            fixture.requestDigest(),
            fixture.tokenIdentity(),
            fixture.tokenHash());
  }

  private AccountBareLoginTokenIdentityFence requireInitial(
      TestContext context, TokenFixture fixture) {
    return context
        .repository()
        .requireInitial(
            fixture.identity(),
            fixture.operationId(),
            fixture.account().uuid(),
            fixture.requestDigest(),
            fixture.tokenIdentity(),
            fixture.tokenHash());
  }

  private void assertCaptureRejected(
      TestContext context,
      AccountBareLoginExchangeIdentity identity,
      UUID operationId,
      UUID accountUuid,
      byte[] requestDigest,
      String tokenIdentity,
      byte[] tokenHash) {
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .captureInitial(
                                identity,
                                operationId,
                                accountUuid,
                                requestDigest,
                                tokenIdentity,
                                tokenHash)))
        .isInstanceOf(IllegalStateException.class);
  }

  private long captureCount(TestContext context, UUID operationId) {
    Long count =
        context
            .dsl()
            .resultQuery(
                "SELECT COUNT(*) FROM " + CAPTURE_TABLE + " WHERE operation_id = ?", operationId)
            .fetchOne(0, Long.class);
    return count == null ? -1L : count;
  }

  private void insertCaptureDirect(
      TestContext context,
      TokenFixture fixture,
      UUID accountUuid,
      long accountIssuanceFence,
      long accountIssuanceFenceSourceVersion) {
    context
        .dsl()
        .execute(
            "INSERT INTO "
                + CAPTURE_TABLE
                + " (token_hash, schema_name, operation_id, source_connect_operation_id, account_id, "
                + "account_uuid, tenant_id, connect_scope_hash, request_id, "
                + "request_digest_version, request_digest, token_identity, profile, "
                + "token_identity_fence, token_identity_fence_source_version, "
                + "account_issuance_fence, account_issuance_fence_source_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 1, 1, ?, ?)",
            HexFormat.of().formatHex(fixture.tokenHash()),
            AccountBareLoginTokenIdentityFence.SCHEMA_NAME,
            fixture.operationId(),
            fixture.identity().sourceConnectOperationId(),
            fixture.identity().accountId(),
            accountUuid,
            fixture.identity().tenantId(),
            AccountJoinDigest.tokenHash(fixture.identity().connectScopeId()),
            fixture.identity().requestId(),
            fixture.requestDigest(),
            fixture.tokenIdentity(),
            AccountBareLoginTokenIdentityFence.PROFILE_NAME,
            accountIssuanceFence,
            accountIssuanceFenceSourceVersion);
  }

  private AccountEnvelopeBinding bareLoginBinding(
      UUID operationId, AccountBareLoginExchangeIdentity identity, byte[] requestDigest) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
        operationId.toString(),
        identity.requestId(),
        Long.toString(identity.accountId()),
        identity.tenantId().toString(),
        identity.connectScopeId(),
        identity.sourceConnectOperationId().toString(),
        requestDigest,
        uniqueDigest(),
        uniqueDigest(),
        uniqueDigest(),
        uniqueDigest());
  }

  private AccountBareLoginTokenIdentityFence readCapture(TestContext context, UUID operationId) {
    var row =
        context
            .dsl()
            .fetchOne(
                "SELECT schema_name, token_hash, operation_id, source_connect_operation_id, "
                    + "account_id, account_uuid, tenant_id, connect_scope_hash, request_id, "
                    + "request_digest_version, request_digest, token_identity, profile, token_identity_fence, "
                    + "token_identity_fence_source_version, account_issuance_fence, "
                    + "account_issuance_fence_source_version, captured_at FROM "
                    + CAPTURE_TABLE
                    + " WHERE operation_id = ?",
                operationId);
    if (row == null) {
      throw new IllegalStateException("Expected retained identity capture is absent");
    }
    return new AccountBareLoginTokenIdentityFence(
        row.get("schema_name", String.class),
        row.get("operation_id", UUID.class),
        row.get("source_connect_operation_id", UUID.class),
        row.get("account_id", Long.class),
        row.get("account_uuid", UUID.class),
        row.get("tenant_id", UUID.class),
        row.get("connect_scope_hash", String.class),
        row.get("request_id", String.class),
        row.get("request_digest_version", Integer.class),
        row.get("request_digest", byte[].class),
        row.get("profile", String.class),
        row.get("token_identity", String.class),
        HexFormat.of().parseHex(row.get("token_hash", String.class)),
        row.get("token_identity_fence", Long.class),
        row.get("token_identity_fence_source_version", Long.class),
        row.get("account_issuance_fence", Long.class),
        row.get("account_issuance_fence_source_version", Long.class),
        row.get("captured_at", OffsetDateTime.class).toInstant());
  }

  private int currentBackendPid(TestContext context) {
    Integer pid = context.dsl().resultQuery("SELECT pg_backend_pid()").fetchOne(0, Integer.class);
    if (pid == null) {
      throw new IllegalStateException("PostgreSQL did not return the transaction backend PID");
    }
    return pid;
  }

  private boolean awaitDatabaseBlock(
      TestContext context, int waiterPid, int blockerPid, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Boolean blocked =
          context
              .dsl()
              .resultQuery(
                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity waiting "
                      + "WHERE waiting.pid = ? AND waiting.wait_event_type = 'Lock' "
                      + "AND ? = ANY(pg_blocking_pids(waiting.pid)))",
                  waiterPid,
                  blockerPid)
              .fetchOne(0, Boolean.class);
      if (Boolean.TRUE.equals(blocked)) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private void assertSqlConstraint(Runnable action, String constraint) {
    assertThatThrownBy(action::run)
        .isInstanceOf(DataAccessException.class)
        .satisfies(failure -> assertPostgresConstraint(failure, constraint));
  }

  private void assertPostgresConstraint(Throwable failure, String expectedConstraint) {
    Throwable cause = failure;
    while (cause != null && !(cause instanceof PSQLException)) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(PSQLException.class);
    PSQLException postgresFailure = (PSQLException) cause;
    assertThat(postgresFailure.getServerErrorMessage().getConstraint())
        .isEqualTo(expectedConstraint);
  }

  private AccountEncryptedEnvelope encryptedEnvelope(AccountEnvelopePurpose purpose) {
    return new AccountEncryptedEnvelope(
        AccountEncryptedEnvelope.CURRENT_FORMAT_VERSION,
        "synthetic_key_1",
        purpose,
        new byte[AccountEncryptedEnvelope.NONCE_LENGTH_BYTES],
        new byte[AccountEncryptedEnvelope.AUTHENTICATION_TAG_LENGTH_BYTES]);
  }

  private static byte[] uniqueDigest() {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static byte[] digest(int seed) {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (seed + index);
    }
    return value;
  }

  private static void await(CountDownLatch latch, String message) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException(message);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(message, exception);
    }
  }

  private <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record Account(long id, UUID uuid) {}

  private record Source(UUID operationId, AccountBareLoginExchangeIdentity exchangeIdentity) {}

  private record TokenFixture(
      Account account,
      AccountBareLoginExchangeIdentity identity,
      UUID operationId,
      byte[] requestDigest,
      String tokenIdentity,
      byte[] tokenHash,
      AccountBareLoginExchangeRepository.ClaimResult claim,
      AccountEnvelopeBinding binding) {}

  private record TestContext(
      DSLContext dsl,
      DataSource dataSource,
      AccountBareLoginExchangeRepository exchangeRepository,
      AccountConnectTokenIssuanceRepository sourceRepository,
      AccountAuthorityGenerationRepository authorityRepository,
      AccountBareLoginTokenIdentityFenceRepository repository,
      TransactionTemplate transaction) {}
}
