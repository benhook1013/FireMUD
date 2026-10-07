package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthEvidenceBundleRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingIntent;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
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

class AccountAuthEvidenceBundlePersistenceIntegrationTest {
  private static final String SCHEMA_PREFIX = "acct_auth_bundle";
  private static final String EXTERNAL_POSTGRES_URL_ENV =
      "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";
  private static final String ACCOUNT_ISSUER = "firemud-account-service";
  private static final String CALLER_WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final long CONCURRENT_PROOF_TIMEOUT_SECONDS = 180L;
  private static final long CONCURRENT_WORKER_SHUTDOWN_TIMEOUT_SECONDS = 100L;

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
  void freshOwnerBaselinesProduceExactZeroCheckpointBundlesAndConcurrentRetryReadback()
      throws Exception {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    Account account = createAccount(context);
    UUID accountId = account.getAccountUuid();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
    IssuerAccountSourceSnapshot snapshot = currentSnapshot(context, sourceEvidence, accountId);
    long now = Math.floorDiv(System.currentTimeMillis(), 1000L);
    PendingIntent pending =
        new PendingIntent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            CALLER_WORKLOAD,
            UUID.randomUUID(),
            UUID.randomUUID(),
            now,
            now,
            now + 120L,
            authoritySnapshot(snapshot),
            AccountGameplayCredentialRequestBindingFixture.binding());
    AccountGameplayDelegationIssuanceRepository issuance =
        new AccountGameplayDelegationIssuanceRepository(dsl, sourceEvidence);
    inTransaction(context, () -> issuance.beginPending(pending));

    AccountAuthEvidenceBundleRepository bundles =
        new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox);
    CountDownLatch captureStart = new CountDownLatch(1);
    ExecutorService concurrentCaptures = Executors.newFixedThreadPool(2);
    var firstCapture =
        concurrentCaptures.submit(
            () -> {
              captureStart.await();
              return inTransaction(context, () -> bundles.captureAndPersist(pending.requestId()));
            });
    var secondCapture =
        concurrentCaptures.submit(
            () -> {
              captureStart.await();
              return inTransaction(context, () -> bundles.captureAndPersist(pending.requestId()));
            });
    AccountAuthEvidenceBundle first;
    AccountAuthEvidenceBundle concurrentRetry;
    try {
      captureStart.countDown();
      first = awaitConcurrentProof(firstCapture);
      concurrentRetry = awaitConcurrentProof(secondCapture);
    } finally {
      stopConcurrentProofWorkers(concurrentCaptures);
    }
    assertThat(concurrentRetry.canonicalBytes()).isEqualTo(first.canonicalBytes());
    var exactRetry = inTransaction(context, () -> bundles.captureAndPersist(pending.requestId()));
    assertThat(exactRetry.canonicalBytes()).isEqualTo(first.canonicalBytes());
    assertThat(first.fields()).containsEntry("schema", "account-auth-evidence-bundle/v1");
    assertThat(first.fields().get("bundleRef"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("bundleVersion", "1")
        .containsEntry("sourceFence", "1");
    Map<?, ?> firstReference = (Map<?, ?>) first.fields().get("bundleRef");
    assertThat(firstReference.get("sourceVersion")).isInstanceOf(String.class);
    assertThat((String) firstReference.get("sourceVersion")).matches("[1-9][0-9]{0,19}");
    assertThat(firstReference.get("linearization")).isEqualTo(firstReference.get("sourceVersion"));
    assertThat(first.fields().get("authoritySourceVersions"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("accountSourceVersion", "1");
    assertThat(((Map<?, ?>) first.fields().get("tokenIdentity")).get("tokenGeneration"))
        .isEqualTo("1");
    Map<?, ?> firstAuthorityTuple = (Map<?, ?>) first.fields().get("authorityTuple");
    assertThat(firstAuthorityTuple.get("issuerAuthGeneration")).isEqualTo("1");
    assertThat(firstAuthorityTuple.get("accountAuthorityGeneration")).isEqualTo("1");
    Object accountAuthoritySourceVersion =
        ((Map<?, ?>) first.fields().get("authoritySourceVersions")).get("accountSourceVersion");
    assertThat(firstReference.get("sourceVersion")).isNotEqualTo(accountAuthoritySourceVersion);

    PendingIntent nextPending =
        new PendingIntent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            CALLER_WORKLOAD,
            UUID.randomUUID(),
            UUID.randomUUID(),
            now,
            now,
            now + 120L,
            authoritySnapshot(snapshot),
            AccountGameplayCredentialRequestBindingFixture.binding());
    inTransaction(context, () -> issuance.beginPending(nextPending));
    AccountAuthEvidenceBundle nextBundle =
        inTransaction(context, () -> bundles.captureAndPersist(nextPending.requestId()));
    Map<?, ?> nextReference = (Map<?, ?>) nextBundle.fields().get("bundleRef");
    assertThat(nextReference.get("sourceFence")).isEqualTo("2");
    assertThat(new java.math.BigInteger((String) nextReference.get("sourceFence")))
        .isGreaterThan(new java.math.BigInteger((String) firstReference.get("sourceFence")));
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        dsl.execute(
                            "UPDATE account_gameplay_delegation_auth_evidence_bundles "
                                + "SET source_fence = '99' WHERE operation_id = ?",
                            pending.operationId())))
        .isInstanceOf(RuntimeException.class);
    assertThat(first.fields().get("outboxCheckpoints"))
        .asList()
        .allSatisfy(
            checkpoint ->
                assertThat(checkpoint)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("outboxSequence", "0")
                    .doesNotContainKeys("sourceEventId", "sourceEventDigest"));
    Long bundleCount =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT count(*) AS bundle_count "
                        + "FROM account_gameplay_delegation_auth_evidence_bundles"),
                "bundle count query must return a row")
            .get("bundle_count", Long.class);
    assertThat(bundleCount).isEqualTo(2L);

    Account updated =
        inTransaction(
            context,
            () -> {
              AccountRepository accounts =
                  new AccountRepository(
                      dsl,
                      new AccountAuthoritySourceEvidenceRepository(
                          dsl, authorities, new AccountAuthorityOutboxRepository(dsl)));
              Account row = accounts.findByAccountUuid(accountId).orElseThrow();
              row.setRole("moderator");
              return accounts.save(row);
            });
    assertThat(updated.getRole()).isEqualTo("moderator");
    assertThatThrownBy(
            () -> inTransaction(context, () -> bundles.captureAndPersist(pending.requestId())))
        .isInstanceOf(AccountAuthEvidenceBundleRepository.OwnerEvidenceUnavailableException.class);
    String storedCanonicalDigest =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT canonical_sha256 FROM "
                        + "account_gameplay_delegation_auth_evidence_bundles WHERE operation_id = ?",
                    pending.operationId()),
                "stored bundle digest query must return a row")
            .get("canonical_sha256", String.class);
    assertThat(storedCanonicalDigest).isEqualTo(first.canonicalSha256());
  }

  private static Account createAccount(TestContext context) {
    Account account = new Account();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    account.setUsername("auth-bundle-" + suffix);
    account.setEmail("auth-bundle-" + UUID.randomUUID() + "@example.test");
    account.setPasswordHash("integration-test-hash");
    account.setRole("player");
    DSLContext dsl = context.dsl();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        new AccountAuthoritySourceEvidenceRepository(
            dsl, authorities, new AccountAuthorityOutboxRepository(dsl));
    return inTransaction(
        context,
        () -> {
          sourceEvidence.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
          return new AccountRepository(dsl, sourceEvidence).save(account);
        });
  }

  private static IssuerAccountSourceSnapshot currentSnapshot(
      TestContext context,
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      UUID accountId) {
    return inTransaction(
        context, () -> sourceEvidence.readCurrentIssuerAccountSources(ACCOUNT_ISSUER, accountId));
  }

  private static AccountAuthoritySnapshot authoritySnapshot(IssuerAccountSourceSnapshot snapshot) {
    var cutoff =
        snapshot
            .account()
            .accountSecurityCutoff()
            .map(
                value ->
                    new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                        value.accountAuthorityGeneration(),
                        value.outboxStreamKey(),
                        value.outboxSequence()));
    return new AccountAuthoritySnapshot(
        snapshot.account().scope().accountId(),
        snapshot.issuer().generation(),
        snapshot.issuer().sourceVersion(),
        snapshot.account().generation(),
        snapshot.account().sourceVersion(),
        snapshot.issuanceFence().value(),
        snapshot.issuanceFence().sourceVersion(),
        cutoff);
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    assertThat(schema.length()).isLessThanOrEqualTo(63);
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = testJdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(testJdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(testJdbcUsername);
    dataSource.setPassword(testJdbcPassword);
    Properties connectionProperties = new Properties();
    connectionProperties.setProperty("options", "-c lock_timeout=60000 -c statement_timeout=90000");
    dataSource.setConnectionProperties(connectionProperties);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)), dsl);
  }

  private static <T> T inTransaction(
      TestContext context, java.util.function.Supplier<T> operation) {
    return context.transaction().execute(status -> operation.get());
  }

  private static <T> T awaitConcurrentProof(Future<T> future)
      throws InterruptedException, TimeoutException {
    try {
      return future.get(CONCURRENT_PROOF_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException workerFailure) {
      throw new AssertionError("Concurrent bundle persistence proof worker failed");
    }
  }

  private static void stopConcurrentProofWorkers(ExecutorService executor) {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(
          CONCURRENT_WORKER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("Concurrent bundle persistence proof workers did not terminate");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Concurrent bundle persistence proof cleanup was interrupted");
    }
  }

  private static String validateExternalLoopbackPostgresUrl(String jdbcUrl) {
    final URI uri;
    try {
      if (!jdbcUrl.startsWith("jdbc:")) throw new IllegalArgumentException("not a JDBC URL");
      uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (RuntimeException ex) {
      throw new IllegalStateException(
          EXTERNAL_POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres",
          ex);
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

  private record TestContext(TransactionTemplate transaction, DSLContext dsl) {}
}
