package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
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
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayDelegationResponseEnvelopePersistenceIntegrationTest {
  private static final String SCHEMA_PREFIX = "acct_gameplay_resp";
  private static final String EXTERNAL_POSTGRES_URL_ENV =
      "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";
  private static final String CALLER_WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ACCOUNT_ISSUER = "firemud-account-service";
  private static final long CONCURRENT_PROOF_TIMEOUT_SECONDS = 180L;
  private static final long CONCURRENT_WORKER_SHUTDOWN_TIMEOUT_SECONDS = 100L;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path tempDirectory;

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
  void storesExactEncryptedCandidateAndRejectsMismatchRollbackAndExpiredHorizon() throws Exception {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    UUID accountId = createAccount(context).getAccountUuid();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
    inTransaction(
        context,
        () -> {
          AccountRepository accounts = new AccountRepository(dsl, sourceEvidence);
          Account account = accounts.findByAccountUuid(accountId).orElseThrow();
          account.setRole("moderator");
          accounts.save(account);
          return null;
        });
    AccountGameplayDelegationIssuanceRepository issuance =
        new AccountGameplayDelegationIssuanceRepository(dsl, sourceEvidence);
    IssuerAccountSourceSnapshot authority = currentSnapshot(context, sourceEvidence, accountId);
    AccountAuthoritySnapshot authoritySnapshot = authoritySnapshot(authority);
    long now = Math.floorDiv(System.currentTimeMillis(), 1000L);
    PendingIntent pending = intent(authoritySnapshot, accountId, now);
    inTransaction(context, () -> issuance.beginPending(pending));
    String compactJwt = compactCandidate(pending);
    inTransaction(
        context, () -> issuance.bindSignedCandidate(pending.requestId(), compactJwt, "1"));

    AccountAuthEvidenceBundleRepository bundles =
        new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox);
    CallerIdentity caller = new CallerIdentity(pending.callerWorkload(), pending.callerContextId());

    // The bytes are a deliberately unverified structurally valid candidate. This proves the
    // Account source/bundle and encrypted-candidate storage primitive only; it does not prove a
    // signer signature, registry write, completed issuance, or caller recovery authority.
    writeTestKeyring(tempDirectory.resolve("response-keyring"));
    AccountResponseEnvelopeCryptography realCrypto =
        new AccountResponseEnvelopeCryptography(
            new AccountResponseEnvelopeKeyring(
                tempDirectory.resolve("response-keyring").toString()));
    AccountGameplayDelegationResponseEnvelopeRepository realResponses =
        new AccountGameplayDelegationResponseEnvelopeRepository(
            dsl, sourceEvidence, bundles, realCrypto);
    CountDownLatch captureAndSealStart = new CountDownLatch(1);
    ExecutorService concurrentCaptureAndSeal = Executors.newFixedThreadPool(2);
    AccountAuthEvidenceBundle concurrentBundle;
    AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation first;
    try {
      Future<AccountAuthEvidenceBundle> directCapture =
          concurrentCaptureAndSeal.submit(
              () -> {
                captureAndSealStart.await();
                return inTransaction(context, () -> bundles.captureAndPersist(pending.requestId()));
              });
      Future<AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation> seal =
          concurrentCaptureAndSeal.submit(
              () -> {
                captureAndSealStart.await();
                return inTransaction(
                    context,
                    () ->
                        realResponses.sealPendingCandidate(
                            pending.requestId(), caller, compactJwt));
              });
      captureAndSealStart.countDown();
      concurrentBundle = awaitConcurrentProof(directCapture);
      first = awaitConcurrentProof(seal);
    } finally {
      stopConcurrentProofWorkers(concurrentCaptureAndSeal);
    }
    var retry =
        inTransaction(
            context,
            () -> realResponses.sealPendingCandidate(pending.requestId(), caller, compactJwt));
    assertThat(retry).isEqualTo(first);
    byte[] storedBundleBytes =
        inTransaction(
            context,
            () -> bundles.readStoredNonAuthorizingValue(pending.operationId()).canonicalBytes());
    assertThat(storedBundleBytes).isEqualTo(concurrentBundle.canonicalBytes());
    assertThat(concurrentBundle.fields().get("authorityTuple"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry(
            "accountSecurityCutoff",
            authoritySnapshot.accountSecurityCutoff().orElseThrow().toMap());
    assertThat(first.operationId()).isEqualTo(pending.operationId());
    assertThat(first.responseRecoveryExpiryEpochMillis())
        .isEqualTo(Math.multiplyExact(pending.expiresAtEpochSecond(), 1_000L));
    assertThat(first.toString()).doesNotContain(compactJwt);
    assertThat(
            dsl.fetchOne(
                    "SELECT count(*) AS response_count "
                        + "FROM account_gameplay_delegation_response_envelopes")
                .get("response_count", Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchOne(
                    "SELECT count(*) AS bundle_count "
                        + "FROM account_gameplay_delegation_auth_evidence_bundles")
                .get("bundle_count", Long.class))
        .isEqualTo(1L);
    byte[] storedEnvelope =
        dsl.fetchOne(
                "SELECT envelope_bytes FROM account_gameplay_delegation_response_envelopes "
                    + "WHERE operation_id = ?",
                pending.operationId())
            .get("envelope_bytes", byte[].class);
    assertThat(new String(storedEnvelope, StandardCharsets.ISO_8859_1)).doesNotContain(compactJwt);

    byte[] boundMetadata =
        dsl.fetchOne(
                "SELECT pending_registry_candidate_bytes FROM "
                    + "account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                pending.requestId())
            .get("pending_registry_candidate_bytes", byte[].class);
    assertThat(new String(boundMetadata, StandardCharsets.ISO_8859_1)).doesNotContain(compactJwt);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        realResponses.sealPendingCandidate(
                            pending.requestId(), caller, compactJwt + "A")))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException.class);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_gameplay_delegation_response_envelopes "
                        + "SET envelope_bytes = ? WHERE operation_id = ?",
                    new byte[] {1, 2, 3},
                    pending.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            dsl.fetchOne(
                    "SELECT envelope_sha256 FROM account_gameplay_delegation_response_envelopes "
                        + "WHERE operation_id = ?",
                    pending.operationId())
                .get("envelope_sha256", String.class))
        .isEqualTo(first.envelopeSha256());

    PendingIntent secondPending = intent(authoritySnapshot, accountId, now);
    inTransaction(context, () -> issuance.beginPending(secondPending));
    String secondJwt = compactCandidate(secondPending);
    var secondCandidate =
        inTransaction(
            context, () -> issuance.bindSignedCandidate(secondPending.requestId(), secondJwt, "1"));
    byte[] fakeCiphertext = new byte[] {1, 2, 3};
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO account_gameplay_delegation_response_envelopes "
                        + "(operation_id, request_id, token_hash, signer_kid, "
                        + "signer_generation, authority_evidence_bundle_sha256, "
                        + "issuance_fence, response_recovery_expiry_epoch_ms, "
                        + "envelope_sha256, envelope_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    secondPending.operationId(),
                    secondPending.requestId(),
                    secondCandidate.tokenHash(),
                    secondCandidate.kid(),
                    secondCandidate.signerGeneration(),
                    "a".repeat(64),
                    authority.issuanceFence().value(),
                    Math.multiplyExact(secondPending.expiresAtEpochSecond(), 1_000L),
                    sha256(fakeCiphertext),
                    fakeCiphertext))
        .isInstanceOf(DataAccessException.class);

    var unavailableCrypto =
        new AccountResponseEnvelopeCryptography(new AccountResponseEnvelopeKeyring(""));
    var unavailableResponses =
        new AccountGameplayDelegationResponseEnvelopeRepository(
            dsl, sourceEvidence, bundles, unavailableCrypto);
    CallerIdentity secondCaller =
        new CallerIdentity(secondPending.callerWorkload(), secondPending.callerContextId());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        unavailableResponses.sealPendingCandidate(
                            secondPending.requestId(), secondCaller, secondJwt)))
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
    assertThat(
            dsl.fetchOne(
                    "SELECT count(*) AS bundle_count "
                        + "FROM account_gameplay_delegation_auth_evidence_bundles")
                .get("bundle_count", Long.class))
        .isEqualTo(1L);

    CountDownLatch concurrentStart = new CountDownLatch(1);
    ExecutorService concurrentRetries = Executors.newFixedThreadPool(2);
    try {
      Future<SealedCandidateObservation> firstRetry =
          concurrentRetries.submit(
              () -> {
                concurrentStart.await();
                return inTransaction(
                    context,
                    () ->
                        realResponses.sealPendingCandidate(
                            secondPending.requestId(), secondCaller, secondJwt));
              });
      Future<SealedCandidateObservation> secondRetry =
          concurrentRetries.submit(
              () -> {
                concurrentStart.await();
                return inTransaction(
                    context,
                    () ->
                        realResponses.sealPendingCandidate(
                            secondPending.requestId(), secondCaller, secondJwt));
              });
      concurrentStart.countDown();
      var firstConcurrentObservation = awaitConcurrentProof(firstRetry);
      var secondConcurrentObservation = awaitConcurrentProof(secondRetry);
      assertThat(firstConcurrentObservation).isEqualTo(secondConcurrentObservation);
      assertThat(firstConcurrentObservation.operationId()).isEqualTo(secondPending.operationId());
    } finally {
      stopConcurrentProofWorkers(concurrentRetries);
    }
    assertThat(
            dsl.fetchOne(
                    "SELECT count(*) AS response_count "
                        + "FROM account_gameplay_delegation_response_envelopes")
                .get("response_count", Long.class))
        .isEqualTo(2L);

    long expiredIssueTime = now - 121L;
    PendingIntent expiredPending = intent(authoritySnapshot, accountId, expiredIssueTime);
    AccountGameplayDelegationIssuanceRepository expiredIssuance =
        new AccountGameplayDelegationIssuanceRepository(
            dsl,
            sourceEvidence,
            Clock.fixed(Instant.ofEpochSecond(expiredIssueTime), ZoneOffset.UTC));
    inTransaction(context, () -> expiredIssuance.beginPending(expiredPending));
    String expiredJwt = compactCandidate(expiredPending);
    inTransaction(
        context,
        () -> expiredIssuance.bindSignedCandidate(expiredPending.requestId(), expiredJwt, "1"));
    AccountGameplayDelegationResponseEnvelopeRepository expiredResponses =
        new AccountGameplayDelegationResponseEnvelopeRepository(
            dsl, sourceEvidence, bundles, realCrypto);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        expiredResponses.sealPendingCandidate(
                            expiredPending.requestId(),
                            new CallerIdentity(
                                expiredPending.callerWorkload(), expiredPending.callerContextId()),
                            expiredJwt)))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.ResponseRecoveryExpiredException
                .class);
    assertThat(
            dsl.fetchOne(
                    "SELECT count(*) AS response_count "
                        + "FROM account_gameplay_delegation_response_envelopes")
                .get("response_count", Long.class))
        .isEqualTo(2L);
  }

  private static Account createAccount(TestContext context) {
    Account account = new Account();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    account.setUsername("response-envelope-" + suffix);
    account.setEmail("response-envelope-" + UUID.randomUUID() + "@example.test");
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

  private static void writeTestKeyring(Path root) throws Exception {
    Files.createDirectories(root);
    byte[] key = new byte[32];
    for (int index = 0; index < key.length; index++) {
      key[index] = (byte) (index + 1);
    }
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    Files.writeString(
        root.resolve("keyring"),
        "firemud-account-response-envelope-keyring-v1\nactive integration-key " + encoded + "\n",
        StandardCharsets.US_ASCII);
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

  private static PendingIntent intent(
      AccountAuthoritySnapshot authoritySnapshot, UUID accountId, long now) {
    if (!accountId.equals(authoritySnapshot.accountId())) {
      throw new IllegalArgumentException(
          "Fixture Account identity does not match its owner snapshot");
    }
    return new PendingIntent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        CALLER_WORKLOAD,
        UUID.randomUUID(),
        UUID.randomUUID(),
        now,
        now,
        now + 120L,
        authoritySnapshot,
        AccountGameplayCredentialRequestBindingFixture.binding());
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
            Map.entry("tokenGeneration", "1"),
            Map.entry(
                "authorityTuple",
                GameSessionAccountDelegationProfile.authorityTuple(
                    intent.authoritySnapshot().issuerGeneration(),
                    intent.authoritySnapshot().accountGeneration(),
                    intent.authoritySnapshot().accountSecurityCutoff())),
            Map.entry("membershipVersion", Map.of()),
            Map.entry("issuanceFence", Long.toString(intent.authoritySnapshot().issuanceFence())));
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String head = encoder.encodeToString(canonicalJson(header));
    String payload = encoder.encodeToString(canonicalJson(claims));
    return head + "." + payload + "." + encoder.encodeToString(new byte[] {1, 2, 3});
  }

  private static byte[] canonicalJson(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String sha256(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
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
    connectionProperties.setProperty("connectTimeout", "10");
    connectionProperties.setProperty("socketTimeout", "60");
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
      throw new AssertionError("Concurrent response-envelope proof worker failed");
    }
  }

  private static void stopConcurrentProofWorkers(ExecutorService executor) {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(
          CONCURRENT_WORKER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("Concurrent response-envelope proof workers did not terminate");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Concurrent response-envelope proof cleanup was interrupted");
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
