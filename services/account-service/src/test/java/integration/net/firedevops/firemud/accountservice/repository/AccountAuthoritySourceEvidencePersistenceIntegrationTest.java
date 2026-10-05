package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
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
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingIntent;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.StaleAuthorityException;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL proof for owner-created sequence-zero baselines and atomic source events. */
class AccountAuthoritySourceEvidencePersistenceIntegrationTest {
  private static final String ISSUER = "firemud-account-service";
  private static final String SCHEMA_PREFIX = "account_source_evidence_proof";
  private static final String EXTERNAL_POSTGRES_URL_ENV =
      "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";
  private static final String GAME_SESSION_WORKLOAD =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");
  private static String testJdbcUrl;
  private static String testJdbcUsername;
  private static String testJdbcPassword;
  private static boolean startedOwnedContainer;

  @BeforeAll
  static void configureDatabase() {
    String externalUrl = System.getenv(EXTERNAL_POSTGRES_URL_ENV);
    if (externalUrl != null) {
      testJdbcUrl = validateExternalLoopbackPostgresUrl(externalUrl);
      testJdbcUsername = "postgres";
      testJdbcPassword = "";
      return;
    }
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "PostgreSQL proof requires the explicit loopback tunnel or an available Docker daemon");
    postgres.start();
    startedOwnedContainer = true;
    testJdbcUrl = postgres.getJdbcUrl();
    testJdbcUsername = postgres.getUsername();
    testJdbcPassword = postgres.getPassword();
  }

  @AfterAll
  static void stopOwnedContainer() {
    if (startedOwnedContainer) postgres.stop();
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

    AccountGameplayDelegationIssuanceRepository issuances =
        new AccountGameplayDelegationIssuanceRepository(dsl, sources);
    PendingIntent preLifecycleIntent = issuanceIntent(changed, account.getAccountUuid());
    transaction.execute(status -> issuances.beginPending(preLifecycleIntent));
    String preLifecycleJwt = compactCandidate(preLifecycleIntent);
    var preLifecycleBound =
        transaction.execute(
            status ->
                issuances.bindSignedCandidate(
                    preLifecycleIntent.requestId(), preLifecycleJwt, "1"));
    byte[] storedCandidateBeforeLifecycle =
        dsl.resultQuery(
                "SELECT pending_registry_candidate_bytes "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                preLifecycleIntent.requestId())
            .fetchOne(0, byte[].class);
    assertThat(storedCandidateBeforeLifecycle).isNotNull();
    assertThat(new String(storedCandidateBeforeLifecycle, StandardCharsets.UTF_8))
        .doesNotContain(preLifecycleJwt);

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
    var afterRollbackCandidate =
        transaction.execute(
            status -> issuances.readPendingRegistryCandidate(preLifecycleIntent.requestId()));
    assertThat(afterRollbackCandidate.authoritySnapshot().accountSecurityCutoff())
        .isEqualTo(preLifecycleIntent.authoritySnapshot().accountSecurityCutoff());

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

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        issuances.readPendingRegistryCandidate(preLifecycleIntent.requestId())))
        .isInstanceOf(StaleAuthorityException.class);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        issuances.bindSignedCandidate(
                            preLifecycleIntent.requestId(), preLifecycleJwt, "1")))
        .isInstanceOf(StaleAuthorityException.class);
    byte[] storedCandidateAfterLifecycle =
        dsl.resultQuery(
                "SELECT pending_registry_candidate_bytes "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                preLifecycleIntent.requestId())
            .fetchOne(0, byte[].class);
    String storedTokenHashAfterLifecycle =
        dsl.resultQuery(
                "SELECT token_hash FROM account_gameplay_delegation_issuance_operations "
                    + "WHERE request_id = ?",
                preLifecycleIntent.requestId())
            .fetchOne(0, String.class);
    assertThat(storedCandidateAfterLifecycle).containsExactly(storedCandidateBeforeLifecycle);
    assertThat(storedTokenHashAfterLifecycle).isEqualTo(preLifecycleBound.tokenHash());

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
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = testJdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(testJdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(testJdbcUsername);
    dataSource.setPassword(testJdbcPassword);
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

  private static PendingIntent issuanceIntent(
      IssuerAccountSourceSnapshot snapshot, UUID accountUuid) {
    long now = Math.floorDiv(System.currentTimeMillis(), 1000L);
    AccountSecurityCutoff cutoff =
        snapshot
            .account()
            .accountSecurityCutoff()
            .map(
                value ->
                    new AccountSecurityCutoff(
                        value.accountAuthorityGeneration(),
                        value.outboxStreamKey(),
                        value.outboxSequence()))
            .orElse(null);
    return new PendingIntent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        GAME_SESSION_WORKLOAD,
        UUID.randomUUID(),
        UUID.randomUUID(),
        now,
        now,
        now + 120L,
        new AccountAuthoritySnapshot(
            accountUuid,
            snapshot.issuer().generation(),
            snapshot.issuer().sourceVersion(),
            snapshot.account().generation(),
            snapshot.account().sourceVersion(),
            snapshot.issuanceFence().value(),
            snapshot.issuanceFence().sourceVersion(),
            Optional.ofNullable(cutoff)));
  }

  private static String compactCandidate(PendingIntent intent) throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", "delegation-kid", "typ", "JWT");
    Map<String, Object> claims =
        Map.ofEntries(
            Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
            Map.entry("sub", intent.authoritySnapshot().accountId().toString()),
            Map.entry("accountId", intent.authoritySnapshot().accountId().toString()),
            Map.entry("jti", intent.tokenJti().toString()),
            Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
            Map.entry("iat", intent.issuedAtEpochSecond()),
            Map.entry("nbf", intent.notBeforeEpochSecond()),
            Map.entry("exp", intent.expiresAtEpochSecond()),
            Map.entry("tokenGeneration", 1L),
            Map.entry(
                "authorityTuple",
                GameSessionAccountDelegationProfile.authorityTuple(
                    intent.authoritySnapshot().issuerGeneration(),
                    intent.authoritySnapshot().accountGeneration(),
                    intent.authoritySnapshot().accountSecurityCutoff())),
            Map.entry("membershipVersion", Map.of()),
            Map.entry("issuanceFence", intent.authoritySnapshot().issuanceFence()));
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String head = encoder.encodeToString(canonicalBytes(header));
    String payload = encoder.encodeToString(canonicalBytes(claims));
    String signature = encoder.encodeToString(new byte[] {1, 2, 3});
    return head + "." + payload + "." + signature;
  }

  private static byte[] canonicalBytes(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String validateExternalLoopbackPostgresUrl(String jdbcUrl) {
    final URI uri;
    try {
      if (!jdbcUrl.startsWith("jdbc:")) throw new IllegalArgumentException("not a JDBC URL");
      uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (RuntimeException malformed) {
      throw new IllegalStateException(
          EXTERNAL_POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres",
          malformed);
    }
    if (!"postgresql".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getPort() > 65_535
        || !"/postgres".equals(uri.getPath())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(
          EXTERNAL_POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres");
    }
    return jdbcUrl;
  }

  private record TestContext(DSLContext dsl, TransactionTemplate transaction) {}
}
