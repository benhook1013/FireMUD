package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommitSignerFixture;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseEnvelopeService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises Account's SQL recovery component with physical PostgreSQL rows and the real Account
 * signer, envelope cryptography, committed-issuance, and registry-owner classes. The pinned Redis
 * connection is a deterministic Lettuce command fixture, not a live Redis deployment. This test
 * does not call Authenticate, bind Game Session socket context, or prove full LOGIN behavior.
 */
class AccountGameplayDelegationResponseRecoveryPersistenceIntegrationTest {
  private static final String SCHEMA_PREFIX = "acct_gameplay_recovery";
  private static final String EXTERNAL_POSTGRES_URL_ENV =
      "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";
  private static final String CALLER_WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ACCOUNT_ISSUER = "firemud-account-service";
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");
  private static final SecureRandom RESPONSE_KEY_RANDOM = new SecureRandom();

  private static String jdbcUrl;
  private static String jdbcUsername;
  private static String jdbcPassword;
  private static boolean startedOwnedContainer;

  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void configurePostgres() {
    String externalUrl = System.getenv(EXTERNAL_POSTGRES_URL_ENV);
    if (externalUrl != null) {
      jdbcUrl = validateLoopbackPostgresUrl(externalUrl);
      jdbcUsername = "postgres";
      jdbcPassword = "";
      return;
    }
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "PostgreSQL recovery component proof requires the explicit loopback tunnel or Docker");
    postgres.start();
    startedOwnedContainer = true;
    jdbcUrl = postgres.getJdbcUrl();
    jdbcUsername = postgres.getUsername();
    jdbcPassword = postgres.getPassword();
  }

  @AfterAll
  static void stopOwnedPostgres() {
    if (startedOwnedContainer) postgres.stop();
  }

  @Test
  void repeatedRecoveryReadsOneCommittedOperationAndReturnsTheSameOriginalCredential()
      throws Exception {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
    UUID accountId = createAccount(context, sources);
    AccountAuthEvidenceBundleRepository bundles =
        new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox);
    AccountGameplayDelegationIssuanceRepository issuance =
        new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles);
    IssuerAccountSourceSnapshot snapshot = currentSnapshot(context, sources, accountId);
    PendingIntent intent = intent(accountId, authoritySnapshot(snapshot));
    inTransaction(context, () -> issuance.beginPending(intent));

    Path keyringRoot = Files.createDirectory(temporaryDirectory.resolve("response-keyring"));
    writeResponseKeyring(keyringRoot.resolve("keyring"));
    AccountResponseEnvelopeCryptography cryptography =
        new AccountResponseEnvelopeCryptography(
            new AccountResponseEnvelopeKeyring(keyringRoot.toString()));
    AccountGameplayDelegationResponseEnvelopeRepository responseRepository =
        new AccountGameplayDelegationResponseEnvelopeRepository(
            dsl, sources, bundles, issuance, cryptography);
    AccountGameplayDelegationResponseEnvelopeService envelopeService =
        new AccountGameplayDelegationResponseEnvelopeService(responseRepository);
    AccountGameplayDelegationCommitSignerFixture signerFixture =
        AccountGameplayDelegationCommitSignerFixture.create(
            temporaryDirectory, issuance, envelopeService, context.manager(), Clock.systemUTC());
    RedisHarness redis = new RedisHarness(snapshot, getClass().getClassLoader());
    AccountGameplayDelegationTokenRegistry registry =
        new AccountGameplayDelegationTokenRegistry(
            issuance,
            redis.client(),
            Clock.systemUTC(),
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
            30_000L);
    AccountGameplayDelegationAuthorityProjection authorityProjection =
        new AccountGameplayDelegationAuthorityProjection(
            sources, context.manager(), redis.client());
    AccountGameplayDelegationIssuanceCommitService commitService =
        new AccountGameplayDelegationIssuanceCommitService(
            signerFixture.signer(),
            registry,
            authorityProjection,
            issuance,
            context.manager(),
            Clock.systemUTC());

    var committed = commitService.commitPendingCandidate(intent.requestId());
    assertThat(committed.operationId()).isEqualTo(intent.operationId());
    assertThat(committed.proofSha256()).matches("[0-9a-f]{64}");
    byte[] candidateBeforeRecovery =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT pending_registry_candidate_bytes FROM "
                        + "account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                    intent.requestId()),
                "pending candidate query must return a row")
            .get("pending_registry_candidate_bytes", byte[].class);

    AccountGameplayDelegationCommittedIssuanceOwner committedOwner =
        new AccountGameplayDelegationCommittedIssuanceOwner(
            issuance, signerFixture.signer(), registry, context.manager(), Clock.systemUTC());
    AccountGameplayDelegationResponseRecoveryOwner recoveryOwner =
        new AccountGameplayDelegationResponseRecoveryOwner(
            responseRepository, committedOwner, context.manager());
    CallerIdentity caller = new CallerIdentity(intent.callerWorkload(), intent.callerContextId());

    RecoveredCredential first =
        recoveryOwner.recoverInitialLoginResponse(intent.requestId(), accountId, caller);
    RecoveredCredential exactRetry =
        recoveryOwner.recoverInitialLoginResponse(intent.requestId(), accountId, caller);

    assertThat(first.compactJwtBytes()).isNotEmpty();
    assertThat(new String(first.compactJwtBytes(), StandardCharsets.US_ASCII).split("\\.", -1))
        .hasSize(3)
        .allSatisfy(segment -> assertThat(segment).isNotEmpty());
    assertThat(exactRetry.compactJwtBytes()).containsExactly(first.compactJwtBytes());
    assertThat(first.tokenSha256()).isEqualTo(exactRetry.tokenSha256());
    assertThat(first.tokenSha256()).isEqualTo(sha256(first.compactJwtBytes()));
    assertThat(first.profile()).isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
    assertThat(first.accountId()).isEqualTo(accountId);
    assertThat(first.tokenJti()).isEqualTo(intent.tokenJti());
    assertThat(first.expiresAtEpochSecond()).isEqualTo(intent.expiresAtEpochSecond());
    assertThat(exactRetry.expiresAtEpochSecond()).isEqualTo(first.expiresAtEpochSecond());
    assertThat(first.toString())
        .doesNotContain(new String(first.compactJwtBytes(), StandardCharsets.US_ASCII));

    var operationAfterRecovery =
        dsl.fetchOne(
            "SELECT status, token_hash, signer_generation, commit_proof_sha256, "
                + "commit_proof_canonical_bytes, pending_registry_candidate_bytes "
                + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
            intent.requestId());
    assertThat(operationAfterRecovery.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(operationAfterRecovery.get("token_hash", String.class))
        .isEqualTo(first.tokenSha256());
    assertThat(operationAfterRecovery.get("signer_generation", String.class)).isEqualTo("42");
    assertThat(operationAfterRecovery.get("commit_proof_sha256", String.class))
        .isEqualTo(committed.proofSha256());
    byte[] commitProofCanonicalBytes =
        operationAfterRecovery.get("commit_proof_canonical_bytes", byte[].class);
    assertThat(containsByteSequence(commitProofCanonicalBytes, first.compactJwtBytes())).isFalse();
    assertThat(operationAfterRecovery.get("pending_registry_candidate_bytes", byte[].class))
        .containsExactly(candidateBeforeRecovery);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) AS envelope_count "
                            + "FROM account_gameplay_delegation_response_envelopes WHERE request_id = ?",
                        intent.requestId()),
                    "response envelope count query must return a row")
                .get("envelope_count", Long.class))
        .isEqualTo(1L);
    byte[] storedEnvelope =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT envelope_bytes FROM account_gameplay_delegation_response_envelopes "
                        + "WHERE request_id = ?",
                    intent.requestId()),
                "stored response envelope query must return a row")
            .get("envelope_bytes", byte[].class);
    assertThat(containsByteSequence(storedEnvelope, first.compactJwtBytes())).isFalse();
    assertThat(redis.activeRecord()).contains("\"state\":\"active\"");
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    assertThat(schema.length()).isLessThanOrEqualTo(63);
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(jdbcUsername);
    dataSource.setPassword(jdbcPassword);
    Properties properties = new Properties();
    properties.setProperty("connectTimeout", "10");
    properties.setProperty("socketTimeout", "60");
    properties.setProperty("options", "-c lock_timeout=60000 -c statement_timeout=90000");
    dataSource.setConnectionProperties(properties);
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
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    return new TestContext(new TransactionTemplate(transactionManager), transactionManager, dsl);
  }

  private static UUID createAccount(
      TestContext context, AccountAuthoritySourceEvidenceRepository sources) {
    Account account = new Account();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    account.setUsername("recovery-" + suffix);
    account.setEmail("recovery-" + UUID.randomUUID() + "@example.test");
    account.setPasswordHash("integration-test-hash");
    // The fresh Account birth path creates the exact empty global-role source only for null role.
    return inTransaction(
            context,
            () -> {
              sources.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
              return new AccountRepository(context.dsl(), sources).save(account);
            })
        .getAccountUuid();
  }

  private static IssuerAccountSourceSnapshot currentSnapshot(
      TestContext context, AccountAuthoritySourceEvidenceRepository sources, UUID accountId) {
    return inTransaction(
        context, () -> sources.readCurrentIssuerAccountSources(ACCOUNT_ISSUER, accountId));
  }

  private static AccountAuthoritySnapshot authoritySnapshot(IssuerAccountSourceSnapshot source) {
    Optional<AccountSecurityCutoff> cutoff =
        source
            .account()
            .accountSecurityCutoff()
            .map(
                value ->
                    new AccountSecurityCutoff(
                        value.accountAuthorityGeneration(),
                        value.outboxStreamKey(),
                        value.outboxSequence()));
    return new AccountAuthoritySnapshot(
        source.account().scope().accountId(),
        source.issuer().generation(),
        source.issuer().sourceVersion(),
        source.account().generation(),
        source.account().sourceVersion(),
        source.issuanceFence().value(),
        source.issuanceFence().sourceVersion(),
        cutoff);
  }

  private static PendingIntent intent(UUID accountId, AccountAuthoritySnapshot authority) {
    long now = Instant.now().getEpochSecond();
    return new PendingIntent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        CALLER_WORKLOAD,
        UUID.randomUUID(),
        UUID.randomUUID(),
        now,
        now,
        now + 180L,
        authority,
        AccountGameplayCredentialRequestBindingFixture.binding());
  }

  private static void writeResponseKeyring(Path path) throws Exception {
    byte[] key = new byte[32];
    RESPONSE_KEY_RANDOM.nextBytes(key);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    Arrays.fill(key, (byte) 0);
    Files.writeString(
        path,
        "firemud-account-response-envelope-keyring-v1\nactive response-key-1 " + encoded + "\n",
        StandardCharsets.US_ASCII);
  }

  private static <T> T inTransaction(
      TestContext context, java.util.function.Supplier<T> operation) {
    return context.transaction().execute(status -> operation.get());
  }

  private static String validateLoopbackPostgresUrl(String value) {
    final URI uri;
    try {
      if (!value.startsWith("jdbc:")) throw new IllegalArgumentException("not a JDBC URL");
      uri = URI.create(value.substring("jdbc:".length()));
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
    return value;
  }

  private static String sha256(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static boolean containsByteSequence(byte[] haystack, byte[] needle) {
    if (needle.length == 0) {
      return true;
    }
    for (int start = 0; start <= haystack.length - needle.length; start++) {
      int offset = 0;
      while (offset < needle.length && haystack[start + offset] == needle[offset]) {
        offset++;
      }
      if (offset == needle.length) {
        return true;
      }
    }
    return false;
  }

  private static String sha1(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
  }

  private record TestContext(
      TransactionTemplate transaction, PlatformTransactionManager manager, DSLContext dsl) {}

  private static final class RedisHarness {
    private final AtomicReference<byte[]> tokenRecord = new AtomicReference<>();
    private final AtomicLong absoluteExpiryMillis = new AtomicLong();
    private final byte[][] projections;
    private final StatefulRedisConnection<byte[], byte[]> connection =
        mock(StatefulRedisConnection.class);
    private final RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
    private final AccountGameplayDelegationRedisClient client;

    private RedisHarness(IssuerAccountSourceSnapshot snapshot, ClassLoader classLoader)
        throws Exception {
      projections = AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot);
      when(connection.getOptions())
          .thenReturn(ClientOptions.builder().autoReconnect(false).build());
      when(connection.isOpen()).thenReturn(true);
      when(connection.sync()).thenReturn(commands);
      when(commands.aclWhoami())
          .thenReturn(AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY);
      when(commands.scriptLoad(any(byte[].class)))
          .thenAnswer(invocation -> sha1(invocation.getArgument(0)));
      when(commands.<Long>evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
          .thenAnswer(
              invocation -> {
                byte[][] keys = invocation.getArgument(2);
                byte[][] arguments = invocation.getArgument(3);
                String key = new String(keys[0], StandardCharsets.US_ASCII);
                if (!key.startsWith(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX)) {
                  return 0L;
                }
                if (arguments.length == 2) {
                  byte[] existing = tokenRecord.get();
                  long expiry = Long.parseLong(new String(arguments[1], StandardCharsets.US_ASCII));
                  if (existing == null) {
                    tokenRecord.set(arguments[0].clone());
                    absoluteExpiryMillis.set(expiry);
                    return 1L;
                  }
                  return Arrays.equals(existing, arguments[0])
                          && absoluteExpiryMillis.get() == expiry
                      ? 0L
                      : -1L;
                }
                if (arguments.length == 3) {
                  byte[] observed = tokenRecord.get();
                  long expiry = Long.parseLong(new String(arguments[2], StandardCharsets.US_ASCII));
                  if (Arrays.equals(observed, arguments[0])
                      && absoluteExpiryMillis.get() == expiry) {
                    tokenRecord.set(arguments[1].clone());
                    return 1L;
                  }
                  return Arrays.equals(observed, arguments[1])
                          && absoluteExpiryMillis.get() == expiry
                      ? 0L
                      : -1L;
                }
                return -1L;
              });
      when(commands.get(any(byte[].class)))
          .thenAnswer(
              invocation -> {
                String key = new String(invocation.getArgument(0), StandardCharsets.US_ASCII);
                if (key.startsWith(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX)) {
                  byte[] value = tokenRecord.get();
                  return value == null ? null : value.clone();
                }
                if ((AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
                        + GameSessionAccountDelegationProfile.ISSUER)
                    .equals(key)) {
                  return projections[0].clone();
                }
                if (key.startsWith(
                    AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX)) {
                  return projections[1].clone();
                }
                return null;
              });
      when(commands.pexpiretime(any(byte[].class)))
          .thenAnswer(
              invocation -> {
                String key = new String(invocation.getArgument(0), StandardCharsets.US_ASCII);
                return key.startsWith(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX)
                    ? absoluteExpiryMillis.get()
                    : null;
              });
      Mockito.doReturn(java.util.List.of(1L, 1L))
          .when(commands)
          .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
      doAnswer(invocation -> null).when(connection).close();
      AccountCoordinationPinnedConnectionProvider provider = () -> connection;
      client =
          new AccountGameplayDelegationRedisClient(
              provider,
              RedisScriptCatalog.loadInstalled(classLoader),
              new AcknowledgementRequirements(1, 1, 1_000),
              GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
              classLoader);
    }

    private AccountGameplayDelegationRedisClient client() {
      return client;
    }

    private String activeRecord() {
      byte[] value = tokenRecord.get();
      return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }
  }
}
