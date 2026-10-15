package integration.net.firedevops.firemud.accountservice.service.session;

import static integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture.transactionalIssuanceOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.mkammerer.argon2.Argon2Factory;
import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthEvidenceBundleRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingIntent;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource.CredentialDigestKey;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommitSignerFixture;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.PendingRegistrationOutcome;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseEnvelopeService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayPrimaryCredentialVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedGameplayAuthorityProjection;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.IssuanceBinding;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.ExecuteListener;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises Account's registered Coordination Redis scripts against isolated test-owned physical
 * Redis and PostgreSQL fixtures. An explicitly supplied isolated loopback Redis pair remains
 * supported. This test does not establish deployed ACL/TLS custody.
 */
class AccountGameplayDelegationRedisIntegrationTest {
  private static final String REDIS_URI_ENV = "FIREMUD_ACCOUNT_REDIS_INTEGRATION_URI";
  private static final String FIXTURE_ADMIN_URI_ENV = "FIREMUD_ACCOUNT_REDIS_FIXTURE_ADMIN_URI";
  private static final String ACCOUNT_IDENTITY = "account_coord_app";
  private static final String FIXTURE_ADMIN_IDENTITY = "account_proof_fixture_admin";
  private static final String FIXTURE_ADMIN_PASSWORD =
      UUID.randomUUID().toString().replace("-", "");
  private static final String ACCOUNT_PASSWORD = UUID.randomUUID().toString().replace("-", "");
  private static final String ACCOUNT_ISSUER = "firemud-account-service";
  private static final int REDIS_PORT = 6379;
  private static final String SCHEMA_PREFIX = "acct_gameplay_active_";
  private static final String CALLER_WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final long PROJECTION_BYTES =
      AccountGameplayDelegationAuthorityProjection.MAX_PROJECTION_BYTES;
  private static final int REGISTRY_BYTES =
      GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SecureRandom PRIMARY_CREDENTIAL_RANDOM = new SecureRandom();
  private static final Network REDIS_NETWORK = Network.newNetwork();
  private static final GenericContainer<?> REDIS_PRIMARY =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(REDIS_NETWORK)
          .withNetworkAliases("account-gameplay-delegation-primary")
          .withExposedPorts(REDIS_PORT)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");
  private static final GenericContainer<?> REDIS_REPLICA =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(REDIS_NETWORK)
          .dependsOn(REDIS_PRIMARY)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "account-gameplay-delegation-primary",
              Integer.toString(REDIS_PORT));
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture(true);
  private static RedisURI writerUri;
  private static RedisURI fixtureAdminUri;
  private static RedisClient redisBootstrapClient;
  private static volatile boolean ownsRedisFixture;
  private String ownedPostgresSchema;

  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void startPhysicalFixtures() throws InterruptedException {
    String configuredWriter = System.getenv(REDIS_URI_ENV);
    String configuredAdmin = System.getenv(FIXTURE_ADMIN_URI_ENV);
    boolean writerConfigured = configuredWriter != null && !configuredWriter.isBlank();
    boolean adminConfigured = configuredAdmin != null && !configuredAdmin.isBlank();
    if (writerConfigured != adminConfigured) {
      throw new IllegalStateException(
          REDIS_URI_ENV + " and " + FIXTURE_ADMIN_URI_ENV + " must be configured together");
    }

    if (writerConfigured) {
      writerUri = requireLoopbackUri(configuredWriter, ACCOUNT_IDENTITY);
      fixtureAdminUri = requireLoopbackUri(configuredAdmin, FIXTURE_ADMIN_IDENTITY);
      if (writerUri.getPort() != fixtureAdminUri.getPort()) {
        throw new IllegalArgumentException(
            "The Account writer and fixture administrator must address the same Redis primary");
      }
    } else {
      assumeTrue(
          DockerClientFactory.instance().isDockerAvailable(),
          "Docker is unavailable; physical Account Redis proof is skipped");
      ownsRedisFixture = true;
      REDIS_PRIMARY.start();
      REDIS_REPLICA.start();
      int primaryPort = REDIS_PRIMARY.getMappedPort(REDIS_PORT);
      redisBootstrapClient = redisClient("127.0.0.1", primaryPort, null, null);
      try (var connection = redisBootstrapClient.connect(ByteArrayCodec.INSTANCE)) {
        RedisCommands<byte[], byte[]> commands = connection.sync();
        configureAccountUser(commands);
        configureFixtureAdmin(commands);
        awaitLocalAndReplicaAof(commands);
      }
      writerUri = authenticatedUri(primaryPort, ACCOUNT_IDENTITY, ACCOUNT_PASSWORD);
      fixtureAdminUri =
          authenticatedUri(primaryPort, FIXTURE_ADMIN_IDENTITY, FIXTURE_ADMIN_PASSWORD);
    }

    POSTGRES.start();
  }

  @AfterAll
  static void stopPhysicalFixtures() {
    POSTGRES.stop();
    if (redisBootstrapClient != null) {
      redisBootstrapClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
    if (ownsRedisFixture) {
      if (REDIS_REPLICA.isRunning()) REDIS_REPLICA.stop();
      if (REDIS_PRIMARY.isRunning()) REDIS_PRIMARY.stop();
      REDIS_NETWORK.close();
    }
  }

  @AfterEach
  void dropOwnedPostgresSchema() {
    if (ownedPostgresSchema == null) return;
    if (!ownedPostgresSchema.startsWith(SCHEMA_PREFIX)
        || !ownedPostgresSchema.matches("[a-z][a-z0-9_]{0,62}")) {
      throw new IllegalStateException("Refusing to clean an unowned Account proof schema");
    }
    try {
      new JdbcTemplate(POSTGRES.dataSource())
          .execute("DROP SCHEMA IF EXISTS \"" + ownedPostgresSchema + "\" CASCADE");
    } finally {
      ownedPostgresSchema = null;
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pinnedRedisHelperRegistersPendingAndProjectsExactOwnerValues(boolean tenantBound)
      throws Exception {
    RedisClient writer = redisClient(writerUri);
    RedisClient fixtureAdmin = redisClient(fixtureAdminUri);
    StatefulRedisConnection<byte[], byte[]> adminConnection = null;
    Set<String> ownedKeys = new LinkedHashSet<>();
    try {
      adminConnection = fixtureAdmin.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      assertThat(admin.aclWhoami()).isEqualTo(FIXTURE_ADMIN_IDENTITY);

      UUID accountId = UUID.randomUUID();
      PendingFixture pending = pendingFixture(accountId, tenantBound ? UUID.randomUUID() : null);
      String pendingKey =
          AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + pending.record().tokenHash();
      String issuerKey =
          AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
              + GameSessionAccountDelegationProfile.ISSUER;
      String accountKey =
          AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId;
      for (String key : List.of(pendingKey, issuerKey, accountKey)) {
        assertThat(admin.get(ascii(key)))
            .as("isolated fixture key is absent before this test owns it")
            .isNull();
      }

      TrackedProvider writerProvider = new TrackedProvider(writer);
      AccountGameplayDelegationRedisClient redis = redisClient(writerProvider);
      ownedKeys.add(pendingKey);

      int pendingCreateConnection = writerProvider.openedConnections();
      var created =
          redis.registerPending(
              pending.record().tokenHash(), pending.recordBytes(), pending.absoluteExpiryMillis());
      assertThat(created.outcome()).isEqualTo(PendingRegistrationOutcome.CREATED);
      assertThat(created.localAofCount()).isGreaterThanOrEqualTo(1L);
      assertThat(created.replicaAofCount()).isGreaterThanOrEqualTo(1L);
      assertSingleConnectionTrace(
          writerProvider,
          pendingCreateConnection,
          "aclWhoami",
          "scriptLoad",
          "evalsha",
          "dispatch",
          "get",
          "pexpiretime",
          "close");
      assertThat(admin.get(ascii(pendingKey))).containsExactly(pending.recordBytes());
      assertThat(admin.pexpiretime(ascii(pendingKey))).isEqualTo(pending.absoluteExpiryMillis());

      int pendingRetryConnection = writerProvider.openedConnections();
      var exactRetry =
          redis.registerPending(
              pending.record().tokenHash(), pending.recordBytes(), pending.absoluteExpiryMillis());
      assertThat(exactRetry.outcome()).isEqualTo(PendingRegistrationOutcome.EXACT_RETRY);
      assertThat(exactRetry.absoluteExpiryMillis()).isEqualTo(pending.absoluteExpiryMillis());
      assertThat(exactRetry.localAofCount()).isGreaterThanOrEqualTo(1L);
      assertThat(exactRetry.replicaAofCount()).isGreaterThanOrEqualTo(1L);
      assertSingleConnectionTrace(
          writerProvider,
          pendingRetryConnection,
          "aclWhoami",
          "scriptLoad",
          "evalsha",
          "dispatch",
          "get",
          "pexpiretime",
          "close");

      Map<String, Object> changedFields = new java.util.LinkedHashMap<>(pending.record().fields());
      changedFields.put("operationId", UUID.randomUUID().toString());
      changedFields.put("requestDigest", "b".repeat(64));
      byte[] changedRecord =
          net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
              JSON.writeValueAsString(changedFields));
      int changedBytesConnection = writerProvider.openedConnections();
      assertThatThrownBy(
              () ->
                  redis.registerPending(
                      pending.record().tokenHash(), changedRecord, pending.absoluteExpiryMillis()))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(writerProvider.trace(changedBytesConnection))
          .containsSubsequence("aclWhoami", "scriptLoad", "evalsha", "close")
          .doesNotContain("dispatch", "get", "pexpiretime");
      assertThat(admin.get(ascii(pendingKey))).containsExactly(pending.recordBytes());
      assertThat(admin.pexpiretime(ascii(pendingKey))).isEqualTo(pending.absoluteExpiryMillis());

      int changedDeadlineConnection = writerProvider.openedConnections();
      assertThatThrownBy(
              () ->
                  redis.registerPending(
                      pending.record().tokenHash(),
                      pending.recordBytes(),
                      Math.addExact(pending.absoluteExpiryMillis(), 1_000L)))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(writerProvider.trace(changedDeadlineConnection))
          .containsSubsequence("aclWhoami", "scriptLoad", "evalsha", "close")
          .doesNotContain("dispatch", "get", "pexpiretime");
      assertThat(admin.get(ascii(pendingKey))).containsExactly(pending.recordBytes());
      assertThat(admin.pexpiretime(ascii(pendingKey))).isEqualTo(pending.absoluteExpiryMillis());

      int insufficientReplicaAofConnection = writerProvider.openedConnections();
      AccountGameplayDelegationRedisClient insufficientReplicaAofClient =
          redisClient(writerProvider, new AcknowledgementRequirements(1, 2, 250));
      assertThatThrownBy(
              () ->
                  insufficientReplicaAofClient.registerPending(
                      pending.record().tokenHash(),
                      pending.recordBytes(),
                      pending.absoluteExpiryMillis()))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(writerProvider.trace(insufficientReplicaAofConnection))
          .containsSubsequence("aclWhoami", "scriptLoad", "evalsha", "dispatch", "close")
          .doesNotContain("get", "pexpiretime");
      assertThat(admin.get(ascii(pendingKey))).containsExactly(pending.recordBytes());
      assertThat(admin.pexpiretime(ascii(pendingKey))).isEqualTo(pending.absoluteExpiryMillis());

      ownedKeys.add(issuerKey);
      ownedKeys.add(accountKey);
      byte[][] initialProjection = projectionPair(accountId, 7L, 7L, "issuer-base", "account-base");
      int projectionPublishConnection = writerProvider.openedConnections();
      assertThat(
              redis.publishAuthorityProjections(
                  accountId, initialProjection[0], initialProjection[1]))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertSingleConnectionTrace(
          writerProvider,
          projectionPublishConnection,
          "aclWhoami",
          "scriptLoad",
          "evalsha",
          "evalsha",
          "dispatch",
          "get",
          "get",
          "close");
      assertProjectionBytes(admin, issuerKey, accountKey, initialProjection);

      int projectionReadConnection = writerProvider.openedConnections();
      byte[][] observedProjection = redis.readAuthorityProjections(accountId);
      assertThat(Arrays.equals(observedProjection[0], initialProjection[0])).isTrue();
      assertThat(Arrays.equals(observedProjection[1], initialProjection[1])).isTrue();
      assertSingleConnectionTrace(
          writerProvider, projectionReadConnection, "aclWhoami", "get", "get", "close");
      String accountProjectionJson = new String(observedProjection[1], StandardCharsets.UTF_8);
      var decodedAccount = AccountGenerationProjection.parse(accountProjectionJson);
      assertThat(decodedAccount.accountAuthorityGeneration()).isEqualTo("7");
      assertThat(decodedAccount.outboxSequence()).isEqualTo("6");
      assertThat(decodedAccount.sourceEvent()).isPresent();

      int projectionRetryConnection = writerProvider.openedConnections();
      assertThat(
              redis.publishAuthorityProjections(
                  accountId, initialProjection[0], initialProjection[1]))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY);
      assertThat(writerProvider.trace(projectionRetryConnection))
          .containsSubsequence(
              "aclWhoami", "scriptLoad", "evalsha", "evalsha", "dispatch", "get", "get", "close");

      byte[][] regressedProjection =
          projectionPair(accountId, 6L, 8L, "issuer-stale", "account-newer");
      byte[] accountBeforeIssuerRegression = admin.get(ascii(accountKey));
      int issuerRegressionConnection = writerProvider.openedConnections();
      assertThatThrownBy(
              () ->
                  redis.publishAuthorityProjections(
                      accountId, regressedProjection[0], regressedProjection[1]))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(writerProvider.trace(issuerRegressionConnection))
          .containsExactly("aclWhoami", "scriptLoad", "evalsha", "close");
      assertThat(admin.get(ascii(issuerKey))).containsExactly(initialProjection[0]);
      assertThat(admin.get(ascii(accountKey))).containsExactly(accountBeforeIssuerRegression);
      assertThat(admin.get(ascii(accountKey))).containsExactly(initialProjection[1]);

      byte[][] equalIssuerConflict =
          projectionPair(accountId, 7L, 7L, "issuer-conflict", "account-base");
      int equalIssuerConflictConnection = writerProvider.openedConnections();
      assertThatThrownBy(
              () ->
                  redis.publishAuthorityProjections(
                      accountId, equalIssuerConflict[0], equalIssuerConflict[1]))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(writerProvider.trace(equalIssuerConflictConnection))
          .doesNotContain("dispatch", "get");
      assertProjectionBytes(admin, issuerKey, accountKey, initialProjection);

      byte[][] equalAccountConflict =
          projectionPair(accountId, 7L, 7L, "issuer-base", "account-conflict");
      int equalAccountConflictConnection = writerProvider.openedConnections();
      assertThatThrownBy(
              () ->
                  redis.publishAuthorityProjections(
                      accountId, equalAccountConflict[0], equalAccountConflict[1]))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(writerProvider.trace(equalAccountConflictConnection))
          .contains("get")
          .doesNotContain("dispatch");
      assertProjectionBytes(admin, issuerKey, accountKey, initialProjection);

      byte[][] signedBigintBoundary =
          projectionPair(
              accountId, Long.MAX_VALUE, Long.MAX_VALUE, "issuer-bigint-max", "account-bigint-max");
      assertThat(new String(signedBigintBoundary[0], StandardCharsets.UTF_8))
          .contains("\"issuerAuthGeneration\":\"9223372036854775807\"");
      assertThat(new String(signedBigintBoundary[1], StandardCharsets.UTF_8))
          .contains("\"accountAuthorityGeneration\":\"9223372036854775807\"")
          .contains("\"outboxSequence\":\"9223372036854775806\"");
      int boundaryConnection = writerProvider.openedConnections();
      assertThat(
              redis.publishAuthorityProjections(
                  accountId, signedBigintBoundary[0], signedBigintBoundary[1]))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(writerProvider.trace(boundaryConnection))
          .containsSubsequence(
              "aclWhoami", "scriptLoad", "evalsha", "evalsha", "dispatch", "get", "get", "close");
      assertProjectionBytes(admin, issuerKey, accountKey, signedBigintBoundary);

      byte[][] belowSignedBigintBoundary =
          projectionPair(
              accountId,
              Long.MAX_VALUE - 1L,
              Long.MAX_VALUE - 1L,
              "issuer-below-max",
              "account-below-max");
      int boundaryRegressionConnection = writerProvider.openedConnections();
      assertThatThrownBy(
              () ->
                  redis.publishAuthorityProjections(
                      accountId, belowSignedBigintBoundary[0], belowSignedBigintBoundary[1]))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(writerProvider.trace(boundaryRegressionConnection))
          .doesNotContain("dispatch", "get");
      assertProjectionBytes(admin, issuerKey, accountKey, signedBigintBoundary);

      TrackedProvider wrongRoleProvider = new TrackedProvider(fixtureAdmin);
      AccountGameplayDelegationRedisClient wrongRoleClient = redisClient(wrongRoleProvider);
      int wrongRoleConnection = wrongRoleProvider.openedConnections();
      assertThatThrownBy(
              () ->
                  wrongRoleClient.publishAuthorityProjections(
                      accountId, signedBigintBoundary[0], signedBigintBoundary[1]))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(wrongRoleProvider.trace(wrongRoleConnection))
          .containsExactly("aclWhoami", "close");
      assertProjectionBytes(admin, issuerKey, accountKey, signedBigintBoundary);

      // Transport/script proof only: these pure event fixtures are not SQL currentness proof.
      UUID selectedTenant = UUID.randomUUID();
      String tenantKey = "session:auth:generation:tenant:" + selectedTenant;
      String memberKey = "session:auth:generation:membership:" + accountId + ":" + selectedTenant;
      assertThat(admin.get(ascii(tenantKey))).isNull();
      assertThat(admin.get(ascii(memberKey))).isNull();
      ownedKeys.add(tenantKey);
      ownedKeys.add(memberKey);
      byte[][] selected =
          selectedProjectionFixture(signedBigintBoundary, accountId, selectedTenant);
      int selectedConnection = writerProvider.openedConnections();
      assertThat(redis.publishSelectedAuthorityProjections(accountId, selectedTenant, selected))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(
              writerProvider.trace(selectedConnection).stream().filter("evalsha"::equals).count())
          .isEqualTo(4L);
      assertThat(
              writerProvider.trace(selectedConnection).stream().filter("dispatch"::equals).count())
          .isEqualTo(1L);
      assertThat(admin.get(ascii(tenantKey))).containsExactly(selected[2]);
      assertThat(admin.get(ascii(memberKey))).containsExactly(selected[3]);
      assertThat(admin.pttl(ascii(tenantKey))).isEqualTo(-1L);
      assertThat(admin.pttl(ascii(memberKey))).isEqualTo(-1L);
      assertThat(redis.publishSelectedAuthorityProjections(accountId, selectedTenant, selected))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY);
      assertThat(
              Arrays.deepEquals(
                  redis.readSelectedAuthorityProjections(accountId, selectedTenant), selected))
          .isTrue();
      byte[] tenantSequenceTwo = tenantProjectionFixture(selectedTenant, 3L, 3L, 2L);
      byte[] membershipSequenceTwo =
          membershipProjectionFixture(accountId, selectedTenant, "2", "3", "1");
      int beforeFirstPublicationAboveOne = writerProvider.openedConnections();
      assertThatThrownBy(
              () ->
                  redis.publishSelectedAuthorityEvent(
                      accountId, selectedTenant, false, null, membershipSequenceTwo))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(writerProvider.openedConnections()).isEqualTo(beforeFirstPublicationAboveOne);
      assertThatThrownBy(
              () ->
                  redis.publishSelectedAuthorityEvent(
                      accountId,
                      selectedTenant,
                      false,
                      selected[3],
                      membershipProjectionFixture(accountId, selectedTenant, "3", "4", "1")))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, selectedTenant, true, selected[2], tenantSequenceTwo))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, selectedTenant, true, selected[2], tenantSequenceTwo))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY);
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, selectedTenant, false, selected[3], membershipSequenceTwo))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, selectedTenant, false, selected[3], membershipSequenceTwo))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY);
      assertThatThrownBy(
              () ->
                  redis.publishSelectedAuthorityEvent(
                      accountId, selectedTenant, false, null, membershipSequenceTwo))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThatThrownBy(
              () ->
                  redis.publishSelectedAuthorityEvent(
                      accountId,
                      selectedTenant,
                      false,
                      selected[3],
                      membershipProjectionFixture(accountId, selectedTenant, "3", "4", "1")))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      admin.del(ascii(tenantKey));
      admin.del(ascii(memberKey));
      assertThatThrownBy(
              () ->
                  redis.publishSelectedAuthorityEvent(
                      accountId, selectedTenant, true, selected[2], tenantSequenceTwo))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThatThrownBy(
              () ->
                  redis.publishSelectedAuthorityEvent(
                      accountId, selectedTenant, false, selected[3], membershipSequenceTwo))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, selectedTenant, true, null, selected[2]))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, selectedTenant, true, selected[2], tenantSequenceTwo))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, selectedTenant, false, null, selected[3]))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, selectedTenant, false, selected[3], membershipSequenceTwo))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(admin.get(ascii(tenantKey))).containsExactly(tenantSequenceTwo);
      assertThat(admin.get(ascii(memberKey))).containsExactly(membershipSequenceTwo);
      assertThatThrownBy(
              () ->
                  redis.publishSelectedAuthorityEvent(
                      accountId, selectedTenant, false, null, selected[3]))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThatThrownBy(
              () ->
                  redis.publishSelectedAuthorityEvent(
                      accountId, selectedTenant, true, null, selected[2]))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      byte[][] gap = selected.clone();
      gap[3] = membershipProjectionFixture(accountId, selectedTenant, "3", "4", "1");
      assertThatThrownBy(
              () -> redis.publishSelectedAuthorityProjections(accountId, selectedTenant, gap))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(admin.get(ascii(memberKey))).containsExactly(membershipSequenceTwo);
      admin.pexpire(ascii(memberKey), 60_000L);
      assertThatThrownBy(() -> redis.readSelectedAuthorityProjections(accountId, selectedTenant))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThatThrownBy(
              () -> redis.publishSelectedAuthorityProjections(accountId, selectedTenant, selected))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    } finally {
      if (adminConnection != null) {
        RedisCommands<byte[], byte[]> admin = adminConnection.sync();
        for (String key : ownedKeys) {
          admin.del(ascii(key));
        }
        adminConnection.close();
      }
      writer.shutdown(java.time.Duration.ZERO, java.time.Duration.ofSeconds(2));
      fixtureAdmin.shutdown(java.time.Duration.ZERO, java.time.Duration.ofSeconds(2));
    }
  }

  @Test
  void activeTransitionRequiresRealCommittedOwnerReadbackAndPreservesExactExpiry()
      throws Exception {
    exerciseActiveTransition();
  }

  @Test
  void retainedPostgresPagesReplayToRedisWithCapturedHeadLossAndGapRecovery() {
    RedisClient writer = redisClient(writerUri);
    RedisClient fixtureAdmin = redisClient(fixtureAdminUri);
    StatefulRedisConnection<byte[], byte[]> adminConnection = null;
    Set<String> ownedKeys = new LinkedHashSet<>();
    try {
      TestContext database = newPostgresContext();
      AccountAuthorityOutboxRepository outbox =
          new AccountAuthorityOutboxRepository(database.dsl());
      UUID accountId = UUID.randomUUID();
      UUID tenantId = UUID.randomUUID();
      String stream = "account:auth-authority:v1:membership/" + accountId + "/" + tenantId;
      String redisKey =
          AccountSelectedGameplayAuthorityProjection.MEMBERSHIP_PREFIX + accountId + ":" + tenantId;
      TrackedProvider writerProvider = new TrackedProvider(writer);
      AccountGameplayDelegationRedisClient redis = redisClient(writerProvider);

      for (long sequence = 1L; sequence <= 129L; sequence++) {
        long requestedSequence = sequence;
        String requestId = "member-request-" + requestedSequence;
        inTransaction(
            database,
            () ->
                outbox.append(
                    stream,
                    requestId,
                    actualSequence -> {
                      if (actualSequence != requestedSequence) {
                        throw new IllegalStateException("Unexpected outbox fixture sequence");
                      }
                      var event = membershipOutboxEventFixture(accountId, tenantId, actualSequence);
                      return new AccountAuthorityOutboxRepository.EventEvidence(
                          event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
                    }));
      }
      AccountAuthorityOutboxRepository.Checkpoint capturedHead =
          inTransaction(database, () -> outbox.readCheckpoint(stream).orElseThrow());
      assertThat(capturedHead.outboxSequence()).isEqualTo(129L);
      inTransaction(
          database,
          () ->
              outbox.append(
                  stream,
                  "member-request-130",
                  actualSequence -> {
                    var event = membershipOutboxEventFixture(accountId, tenantId, actualSequence);
                    return new AccountAuthorityOutboxRepository.EventEvidence(
                        event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
                  }));

      List<AccountAuthorityOutboxRepository.Event> firstPage =
          inTransaction(
              database, () -> outbox.findEventPage(stream, 0L, capturedHead.outboxSequence()));
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(firstPage).hasSize(AccountAuthorityOutboxRepository.MAX_EVENT_HISTORY_PAGE_SIZE);
      assertThat(firstPage.getFirst().outboxSequence()).isEqualTo(1L);
      assertThat(firstPage.getLast().outboxSequence()).isEqualTo(128L);
      List<AccountAuthorityOutboxRepository.Event> secondPage =
          inTransaction(
              database,
              () ->
                  outbox.findEventPage(
                      stream, firstPage.getLast().outboxSequence(), capturedHead.outboxSequence()));
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(secondPage).hasSize(1);
      assertThat(secondPage.getFirst().outboxSequence()).isEqualTo(129L);
      assertThat(
              inTransaction(
                  database,
                  () ->
                      outbox.findEventPage(
                          stream, capturedHead.outboxSequence(), capturedHead.outboxSequence())))
          .isEmpty();
      assertThat(
              inTransaction(database, () -> outbox.readCheckpoint(stream).orElseThrow())
                  .outboxSequence())
          .isEqualTo(130L);

      adminConnection = fixtureAdmin.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      assertThat(admin.aclWhoami()).isEqualTo(FIXTURE_ADMIN_IDENTITY);
      assertThat(admin.get(ascii(redisKey))).isNull();
      ownedKeys.add(redisKey);

      List<byte[]> firstCandidates =
          firstPage.stream()
              .map(row -> membershipProjectionFromOutboxRow(row, accountId, tenantId))
              .toList();
      List<byte[]> secondCandidates =
          secondPage.stream()
              .map(row -> membershipProjectionFromOutboxRow(row, accountId, tenantId))
              .toList();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(
              redis.publishSelectedAuthorityEventPage(
                  accountId, tenantId, false, null, firstCandidates))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(writerProvider.openedConnections()).isEqualTo(1);
      assertThat(writerProvider.trace(0).stream().filter("aclWhoami"::equals).count())
          .isEqualTo(1L);
      assertThat(writerProvider.trace(0).stream().filter("scriptLoad"::equals).count())
          .isEqualTo(1L);
      assertThat(writerProvider.trace(0).stream().filter("evalsha"::equals).count())
          .isEqualTo(128L);
      assertThat(writerProvider.dispatchedCommands(0)).containsExactly("WAITAOF");
      assertThat(writerProvider.trace(0).getLast()).isEqualTo("close");
      byte[] beforeFinalBytes = firstCandidates.getLast();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(
              redis.publishSelectedAuthorityEventPage(
                  accountId, tenantId, false, beforeFinalBytes, secondCandidates))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(writerProvider.openedConnections()).isEqualTo(2);
      assertThat(writerProvider.trace(1).stream().filter("evalsha"::equals).count()).isEqualTo(1L);
      assertThat(writerProvider.dispatchedCommands(1)).containsExactly("WAITAOF");
      assertThat(writerProvider.trace(1).getLast()).isEqualTo("close");
      byte[] finalBytes = secondCandidates.getLast();
      assertThat(admin.get(ascii(redisKey))).containsExactly(finalBytes);
      assertThat(admin.pttl(ascii(redisKey))).isEqualTo(-1L);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, tenantId, false, beforeFinalBytes, finalBytes))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY);

      // Redis loss is recovered from the real retained SQL prefix. Deliberately presenting the
      // sequence-three event with sequence two as its predecessor while Redis holds sequence one
      // exercises the server CAS gap fence; replaying two and three then repairs the lost head.
      admin.del(ascii(redisKey));
      assertThat(admin.get(ascii(redisKey))).isNull();
      List<AccountAuthorityOutboxRepository.Event> recoveryPrefix =
          inTransaction(database, () -> outbox.findEventPage(stream, 0L, 3L));
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(recoveryPrefix).hasSize(3);
      byte[] sequenceOne =
          membershipProjectionFromOutboxRow(recoveryPrefix.get(0), accountId, tenantId);
      byte[] sequenceTwo =
          membershipProjectionFromOutboxRow(recoveryPrefix.get(1), accountId, tenantId);
      byte[] sequenceThree =
          membershipProjectionFromOutboxRow(recoveryPrefix.get(2), accountId, tenantId);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(redis.publishSelectedAuthorityEvent(accountId, tenantId, false, null, sequenceOne))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThatThrownBy(
              () ->
                  redis.publishSelectedAuthorityEvent(
                      accountId, tenantId, false, sequenceTwo, sequenceThree))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(admin.get(ascii(redisKey))).containsExactly(sequenceOne);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, tenantId, false, sequenceOne, sequenceTwo))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(
              redis.publishSelectedAuthorityEvent(
                  accountId, tenantId, false, sequenceTwo, sequenceThree))
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      assertThat(admin.get(ascii(redisKey))).containsExactly(sequenceThree);
    } finally {
      if (adminConnection != null) {
        RedisCommands<byte[], byte[]> admin = adminConnection.sync();
        for (String key : ownedKeys) admin.del(ascii(key));
        adminConnection.close();
      }
      writer.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      fixtureAdmin.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNBOUND", "BOUND_ABSENT", "REDIS_PENDING", "SQL_COMMITTED"})
  void initialLoginDisposalFencesEveryInterruptedIssuanceStage(String stage) throws Exception {
    RedisClient writer = redisClient(writerUri);
    RedisClient fixtureAdmin = redisClient(fixtureAdminUri);
    Set<String> ownedKeys = new LinkedHashSet<>();
    StatefulRedisConnection<byte[], byte[]> adminConnection = null;
    try {
      adminConnection = fixtureAdmin.connect(ByteArrayCodec.INSTANCE);
      TestContext database = newPostgresContext();
      var dsl = database.dsl();
      var authorities = new AccountAuthorityGenerationRepository(dsl);
      var outbox = new AccountAuthorityOutboxRepository(dsl);
      var sources = new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
      UUID accountId = createFreshAccount(database, sources);
      var bundles = new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox);
      var issuance =
          transactionalIssuanceOwner(
              new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles),
              database.manager());
      UUID request = UUID.randomUUID();
      UUID context = UUID.randomUUID();
      inTransaction(
          database,
          () ->
              issuance.beginPendingForAccount(
                  request,
                  accountId,
                  CALLER_WORKLOAD,
                  context,
                  net.firedevops.firemud.accountservice.repository
                      .AccountGameplayCredentialRequestBindingFixture.binding()));
      var responseRepository =
          new AccountGameplayDelegationResponseEnvelopeRepository(
              dsl,
              sources,
              bundles,
              new AccountResponseEnvelopeCryptography(
                  new AccountResponseEnvelopeKeyring(writeResponseKeyring())));
      var signer =
          AccountGameplayDelegationCommitSignerFixture.create(
              temporaryDirectory,
              issuance,
              new AccountGameplayDelegationResponseEnvelopeService(responseRepository),
              database.manager(),
              Clock.systemUTC());
      var provider = new TrackedProvider(writer);
      var redis = redisClient(provider);
      var registry =
          new AccountGameplayDelegationTokenRegistry(
              issuance, database.manager(), redis, Clock.systemUTC(), REGISTRY_BYTES, 30_000L);
      var disposalRepository =
          new net.firedevops.firemud.accountservice.repository
              .AccountInitialGameplayLoginDisposalRepository(dsl);
      var disposal =
          new net.firedevops.firemud.accountservice.service.session
              .AccountInitialGameplayLoginDisposalOwner(
              disposalRepository, registry, database.manager());
      var disposalRequest =
          new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest(
              request, context, null);
      var admin = adminConnection.sync();
      net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity
          pendingRevocationIdentity = null;
      UUID revocationRequest = UUID.randomUUID();
      String pendingRevocationXmin = null;
      if (!"UNBOUND".equals(stage)) {
        signer.preparePendingCandidate(request);
        var candidate =
            inTransaction(database, () -> issuance.readPendingRegistryCandidate(request));
        String tokenKey =
            AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + candidate.tokenHash();
        assertThat(admin.get(ascii(tokenKey))).isNull();
        ownedKeys.add(tokenKey);
        if (!"BOUND_ABSENT".equals(stage)) registry.registerPending(request);
        if ("SQL_COMMITTED".equals(stage)) {
          ownedKeys.add(
              AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX + ACCOUNT_ISSUER);
          ownedKeys.add(
              AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId);
          var commit =
              new AccountGameplayDelegationIssuanceCommitService(
                  signer.signer(),
                  registry,
                  new AccountGameplayDelegationAuthorityProjection(
                      sources, database.manager(), redis),
                  issuance,
                  database.manager(),
                  Clock.systemUTC());
          commit.commitPendingCandidate(request);
          var fenceRow =
              dsl.fetchSingle(
                  "SELECT * FROM account_gameplay_token_identity_fences WHERE issuance_request_id = ?",
                  request);
          pendingRevocationIdentity =
              new net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence
                  .TokenIdentity(
                  fenceRow.get("account_uuid", UUID.class),
                  fenceRow.get("operation_id", UUID.class),
                  request,
                  fenceRow.get("token_hash", String.class),
                  fenceRow.get("token_jti", UUID.class),
                  fenceRow.get("not_before_epoch_second", Long.class),
                  fenceRow.get("token_generation", Long.class),
                  fenceRow.get("issuance_fence", Long.class));
          var identity = pendingRevocationIdentity;
          String digest =
              net.firedevops.firemud.accountservice.repository
                  .AccountGameplayTokenIdentityFenceRepository.revocationDigest(
                  identity, revocationRequest);
          var started =
              inTransaction(
                  database,
                  () ->
                      new net.firedevops.firemud.accountservice.repository
                              .AccountGameplayTokenIdentityFenceRepository(dsl)
                          .beginRevocationIntent(identity, revocationRequest, digest));
          assertThat(started.state())
              .isEqualTo(
                  net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.State
                      .PENDING);
          pendingRevocationXmin =
              dsl.fetchSingle(
                      "SELECT xmin::text AS row_version FROM account_gameplay_token_identity_fences WHERE operation_id = ?",
                      identity.operationId())
                  .get("row_version", String.class);
        }
        var intent =
            inTransaction(
                database, () -> disposalRepository.begin(disposalRequest, CALLER_WORKLOAD));
        // Required durability failure after a real physical mutation keeps SQL PENDING.
        var unavailableRedis = redisClient(provider, new AcknowledgementRequirements(1, 2, 250));
        assertThatThrownBy(() -> unavailableRedis.disposeInitialLogin(intent))
            .isInstanceOf(
                AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
        assertThat(
                dsl.fetchSingle(
                        "SELECT state FROM account_initial_gameplay_login_disposals WHERE request_id = ?",
                        request)
                    .get("state", String.class))
            .isEqualTo("PENDING");
        // Neither expiry nor trust/key withdrawal is a prerequisite: disposal takes only stored
        // immutable identity and has no signer/credential authority collaborator.
        var result = disposal.retire(disposalRequest, CALLER_WORKLOAD);
        assertThat(result.disposition())
            .isEqualTo(
                net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult
                    .Disposition.RETIRED);
        assertThat(admin.get(ascii(tokenKey))).containsExactly(intent.tombstoneBytes());
        if (pendingRevocationIdentity != null) {
          var identity = pendingRevocationIdentity;
          String digest =
              net.firedevops.firemud.accountservice.repository
                  .AccountGameplayTokenIdentityFenceRepository.revocationDigest(
                  identity, revocationRequest);
          var replayed =
              inTransaction(
                  database,
                  () ->
                      new net.firedevops.firemud.accountservice.repository
                              .AccountGameplayTokenIdentityFenceRepository(dsl)
                          .beginRevocationIntent(identity, revocationRequest, digest));
          assertThat(replayed.state())
              .isEqualTo(
                  net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.State
                      .PENDING);
          assertThat(replayed.revocationRequestId()).isEqualTo(revocationRequest);
          assertThat(replayed.revocationDigest()).isEqualTo(digest);
          assertThat(
                  dsl.fetchSingle(
                          "SELECT xmin::text AS row_version FROM account_gameplay_token_identity_fences WHERE operation_id = ?",
                          identity.operationId())
                      .get("row_version", String.class))
              .isEqualTo(pendingRevocationXmin);
          assertThatThrownBy(
                  () ->
                      inTransaction(
                          database,
                          () ->
                              new net.firedevops.firemud.accountservice.repository
                                      .AccountGameplayTokenIdentityFenceRepository(dsl)
                                  .requireActiveForUpdate(identity)))
              .isInstanceOf(
                  net.firedevops.firemud.accountservice.repository
                      .AccountGameplayTokenIdentityFenceRepository.TokenRevokedException.class);
        }
        assertThatThrownBy(
                () ->
                    redis.registerPending(
                        candidate.tokenHash(),
                        candidate.canonicalRecordBytes(),
                        Math.addExact(
                            Math.multiplyExact(candidate.expiresAtEpochSecond(), 1000L), 30_000L)))
            .isInstanceOf(
                AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
        assertThat(admin.get(ascii(tokenKey))).containsExactly(intent.tombstoneBytes());
      } else {
        assertThat(disposal.retire(disposalRequest, CALLER_WORKLOAD).disposition())
            .isEqualTo(
                net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult
                    .Disposition.PENDING);
      }
      assertThatThrownBy(() -> signer.preparePendingCandidate(request))
          .satisfies(
              failure ->
                  assertThat(failure.getClass().getName())
                      .isEqualTo(
                          "net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner$SigningUnavailableException"));
      assertThatThrownBy(
              () -> inTransaction(database, () -> issuance.readCurrentGameplayLoginReplay(request)))
          .isInstanceOf(
              AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);
      assertThatThrownBy(
              () ->
                  disposal.retire(
                      new net.firedevops.firemud.accountservice.dto
                          .InitialGameplayLoginDisposalRequest(request, UUID.randomUUID(), null),
                      CALLER_WORKLOAD))
          .isInstanceOf(
              net.firedevops.firemud.accountservice.repository
                  .AccountInitialGameplayLoginDisposalRepository.DisposalConflictException.class);
    } finally {
      if (adminConnection != null) {
        for (String key : ownedKeys) adminConnection.sync().del(ascii(key));
        adminConnection.close();
      }
      writer.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      fixtureAdmin.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"NO_ROW", "UNBOUND", "BEFORE_SIGNING", "AFTER_TRANSIENT_SIGNING"})
  void noKeyGuardAndAuthoritativeReadbackStayPendingWithoutHistoryQualification(String boundary)
      throws Exception {
    TestContext database = newPostgresContext();
    var dsl = database.dsl();
    var authorities = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sources = new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
    UUID account = createFreshAccount(database, sources);
    UUID otherAccount = createFreshAccount(database, sources);
    var issuance =
        transactionalIssuanceOwner(
            new AccountGameplayDelegationIssuanceRepository(
                dsl, sources, new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox)),
            database.manager());
    UUID request = UUID.randomUUID();
    UUID context = UUID.randomUUID();
    if (!"NO_ROW".equals(boundary))
      inTransaction(
          database,
          () ->
              issuance.beginPendingForAccount(
                  request,
                  account,
                  CALLER_WORKLOAD,
                  context,
                  net.firedevops.firemud.accountservice.repository
                      .AccountGameplayCredentialRequestBindingFixture.binding()));
    var disposals =
        new net.firedevops.firemud.accountservice.repository
            .AccountInitialGameplayLoginDisposalRepository(dsl);
    var registry = org.mockito.Mockito.mock(AccountGameplayDelegationTokenRegistry.class);
    var owner =
        new net.firedevops.firemud.accountservice.service.session
            .AccountInitialGameplayLoginDisposalOwner(disposals, registry, database.manager());
    var identity =
        new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest(
            request, context, null);
    if (boundary.endsWith("SIGNING")) {
      var response =
          new AccountGameplayDelegationResponseEnvelopeRepository(
              dsl,
              sources,
              new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox),
              new AccountResponseEnvelopeCryptography(
                  new AccountResponseEnvelopeKeyring(writeResponseKeyring())));
      int pauseTransaction = "BEFORE_SIGNING".equals(boundary) ? 1 : 2;
      var pausedManager =
          new BeforeSecondTransactionManager(
              database.manager(),
              pauseTransaction,
              () ->
                  assertThat(owner.retire(identity, CALLER_WORKLOAD).disposition())
                      .isEqualTo(
                          net.firedevops.firemud.accountservice.dto
                              .InitialGameplayLoginDisposalResult.Disposition.PENDING));
      var signer =
          AccountGameplayDelegationCommitSignerFixture.create(
              temporaryDirectory,
              issuance,
              new AccountGameplayDelegationResponseEnvelopeService(response),
              pausedManager,
              Clock.systemUTC());
      assertThatThrownBy(() -> signer.preparePendingCandidate(request))
          .satisfies(
              failure ->
                  assertThat(failure.getClass().getName())
                      .isEqualTo(
                          "net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner$SigningUnavailableException"));
      assertThat(
              dsl.fetchSingle(
                      "SELECT token_hash FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                      request)
                  .get("token_hash", String.class))
          .isNull();
      assertThat(
              dsl.fetchOne(
                  "SELECT request_id FROM account_gameplay_delegation_response_envelopes WHERE request_id = ?",
                  request))
          .isNull();
    }
    var first = owner.retire(identity, CALLER_WORKLOAD);
    assertThat(first.disposition())
        .isEqualTo(
            net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.Disposition
                .PENDING);
    assertThat(first.proofKind())
        .isEqualTo(
            net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.ProofKind
                .NONE);
    assertThat(owner.retire(identity, CALLER_WORKLOAD)).isEqualTo(first);
    var intent = inTransaction(database, () -> disposals.begin(identity, CALLER_WORKLOAD));
    assertThat(inTransaction(database, () -> disposals.readUnqualifiedNoBinding(intent)))
        .isEqualTo(first);
    for (UUID resolvedAccount : List.of(account, otherAccount)) {
      assertThatThrownBy(
              () ->
                  inTransaction(
                      database,
                      () ->
                          issuance.beginPendingForAccount(
                              request,
                              resolvedAccount,
                              CALLER_WORKLOAD,
                              context,
                              net.firedevops.firemud.accountservice.repository
                                  .AccountGameplayCredentialRequestBindingFixture.binding())))
          .isInstanceOf(
              AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);
    }
    assertThatThrownBy(
            () ->
                owner.retire(
                    new net.firedevops.firemud.accountservice.dto
                        .InitialGameplayLoginDisposalRequest(request, UUID.randomUUID(), null),
                    CALLER_WORKLOAD))
        .isInstanceOf(
            net.firedevops.firemud.accountservice.repository
                .AccountInitialGameplayLoginDisposalRepository.DisposalConflictException.class);
    assertThatThrownBy(
            () -> owner.retire(identity, "spiffe://firemud/ns/other/sa/game-session-service"))
        .isInstanceOf(
            net.firedevops.firemud.accountservice.repository
                .AccountInitialGameplayLoginDisposalRepository.DisposalConflictException.class);
    // A syntactically complete requested terminal proof cannot invent intact binding history.
    assertThatThrownBy(
            () ->
                inTransaction(
                    database,
                    () ->
                        dsl.execute(
                            "UPDATE account_initial_gameplay_login_disposals SET state = 'NO_TOKEN', owner_proof_kind = 'NO_ISSUANCE_BINDING',"
                                + " owner_proof_canonical_bytes = ?, owner_proof_sha256 = ? WHERE request_id = ?",
                            "{}".getBytes(StandardCharsets.UTF_8),
                            "a".repeat(64),
                            request)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("exceptionClass=PSQLException; sqlState=23514");
    org.mockito.Mockito.verifyNoInteractions(registry);

    UUID rolledBackRequest = UUID.randomUUID();
    var rollbackIdentity =
        new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest(
            rolledBackRequest, UUID.randomUUID(), null);
    var rolledBackIntent =
        database
            .transaction()
            .execute(
                status -> {
                  var pending = disposals.begin(rollbackIdentity, CALLER_WORKLOAD);
                  status.setRollbackOnly();
                  return pending;
                });
    assertThat(
            dsl.fetchOne(
                "SELECT request_id FROM account_initial_gameplay_login_disposals WHERE request_id = ?",
                rolledBackRequest))
        .isNull();
    assertThatThrownBy(
        () -> inTransaction(database, () -> disposals.readUnqualifiedNoBinding(rolledBackIntent)));
    assertThat(owner.retire(rollbackIdentity, CALLER_WORKLOAD).disposition())
        .isEqualTo(
            net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.Disposition
                .PENDING);
    org.mockito.Mockito.verifyNoInteractions(registry);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void disposalReconcilesRolledBackBindingVersusLostBindingCommitAcknowledgement(
      boolean commitThenLoseResponse) throws Exception {
    RedisClient writer = redisClient(writerUri);
    RedisClient fixtureAdmin = redisClient(fixtureAdminUri);
    String ownedKey = null;
    StatefulRedisConnection<byte[], byte[]> adminConnection = null;
    try {
      TestContext database = newPostgresContext();
      var dsl = database.dsl();
      var authorities = new AccountAuthorityGenerationRepository(dsl);
      var outbox = new AccountAuthorityOutboxRepository(dsl);
      var sources = new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
      UUID account = createFreshAccount(database, sources);
      var bundles = new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox);
      var issuance =
          transactionalIssuanceOwner(
              new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles),
              database.manager());
      UUID request = UUID.randomUUID();
      UUID context = UUID.randomUUID();
      inTransaction(
          database,
          () ->
              issuance.beginPendingForAccount(
                  request,
                  account,
                  CALLER_WORKLOAD,
                  context,
                  net.firedevops.firemud.accountservice.repository
                      .AccountGameplayCredentialRequestBindingFixture.binding()));
      var response =
          new AccountGameplayDelegationResponseEnvelopeRepository(
              dsl,
              sources,
              bundles,
              new AccountResponseEnvelopeCryptography(
                  new AccountResponseEnvelopeKeyring(writeResponseKeyring())));
      var manager = new SigningCommitOutcomeManager(database.manager(), commitThenLoseResponse);
      var signer =
          AccountGameplayDelegationCommitSignerFixture.create(
              temporaryDirectory,
              issuance,
              new AccountGameplayDelegationResponseEnvelopeService(response),
              manager,
              Clock.systemUTC());
      assertThatThrownBy(() -> signer.preparePendingCandidate(request))
          .satisfies(
              failure ->
                  assertThat(failure.getClass().getName())
                      .isEqualTo(
                          "net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner$SigningUnavailableException"));
      var original =
          dsl.fetchOne(
              "SELECT * FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
              request);
      assertThat(original.get("status", String.class)).isEqualTo("PENDING");
      var provider = new TrackedProvider(writer);
      var registry =
          new AccountGameplayDelegationTokenRegistry(
              issuance,
              database.manager(),
              redisClient(provider),
              Clock.systemUTC(),
              REGISTRY_BYTES,
              30_000L);
      var disposals =
          new net.firedevops.firemud.accountservice.repository
              .AccountInitialGameplayLoginDisposalRepository(dsl);
      var owner =
          new net.firedevops.firemud.accountservice.service.session
              .AccountInitialGameplayLoginDisposalOwner(disposals, registry, database.manager());
      var identity =
          new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest(
              request, context, null);
      if (commitThenLoseResponse) {
        // The bind transaction won, even though the signer received no commit acknowledgement.
        // Missing final COMMITTED issuance/registry receipt cannot turn it into a no-key case.
        String hash = original.get("token_hash", String.class);
        assertThat(hash).isNotNull();
        ownedKey = AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + hash;
        adminConnection = fixtureAdmin.connect(ByteArrayCodec.INSTANCE);
        assertThat(adminConnection.sync().get(ascii(ownedKey))).isNull();
        var result = owner.retire(identity, CALLER_WORKLOAD);
        assertThat(result.disposition())
            .isEqualTo(
                net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult
                    .Disposition.RETIRED);
        assertThat(result.proofKind())
            .isEqualTo(
                net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult
                    .ProofKind.PHYSICAL_TOKEN_DENIAL);
        assertThat(provider.openedConnections()).isGreaterThan(0);
        assertThat(adminConnection.sync().get(ascii(ownedKey))).isNotNull();
      } else {
        assertThat(original.get("token_hash", String.class)).isNull();
        assertThat(
                dsl.fetchOne(
                    "SELECT request_id FROM account_gameplay_delegation_response_envelopes WHERE request_id = ?",
                    request))
            .isNull();
        assertThat(owner.retire(identity, CALLER_WORKLOAD).disposition())
            .isEqualTo(
                net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult
                    .Disposition.PENDING);
        assertThat(provider.openedConnections()).isZero();
      }
      assertThatThrownBy(() -> signer.preparePendingCandidate(request))
          .satisfies(
              failure ->
                  assertThat(failure.getClass().getName())
                      .isEqualTo(
                          "net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner$SigningUnavailableException"));
      assertThat(
              dsl.fetchOne(
                  "SELECT * FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                  request))
          .isEqualTo(original);
    } finally {
      if (adminConnection != null) {
        if (ownedKey != null) adminConnection.sync().del(ascii(ownedKey));
        adminConnection.close();
      }
      writer.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      fixtureAdmin.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }

  private void exerciseActiveTransition() throws Exception {
    RedisClient writer = redisClient(writerUri);
    RedisClient fixtureAdmin = redisClient(fixtureAdminUri);
    StatefulRedisConnection<byte[], byte[]> adminConnection = null;
    Set<String> ownedKeys = new LinkedHashSet<>();
    try {
      TestContext database = newPostgresContext();
      DSLContext dsl = database.dsl();
      AccountAuthorityGenerationRepository authorities =
          new AccountAuthorityGenerationRepository(dsl);
      AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
      AccountAuthoritySourceEvidenceRepository sources =
          new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
      UUID accountId = createFreshAccount(database, sources);
      String issuerKey =
          AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX + ACCOUNT_ISSUER;
      String accountKey =
          AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId;
      AccountAuthEvidenceBundleRepository bundles =
          new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox);
      AccountGameplayDelegationIssuanceRepository issuance =
          transactionalIssuanceOwner(
              new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles),
              database.manager());
      var sourceSnapshot =
          inTransaction(
              database, () -> sources.readCurrentIssuerAccountSources(ACCOUNT_ISSUER, accountId));
      long now = Instant.now().getEpochSecond();
      PendingIntent pending =
          new PendingIntent(
              UUID.randomUUID(),
              UUID.randomUUID(),
              CALLER_WORKLOAD,
              UUID.randomUUID(),
              UUID.randomUUID(),
              now,
              now,
              Math.addExact(now, 180L),
              new AccountAuthoritySnapshot(
                  accountId,
                  sourceSnapshot.issuer().generation(),
                  sourceSnapshot.issuer().sourceVersion(),
                  sourceSnapshot.account().generation(),
                  sourceSnapshot.account().sourceVersion(),
                  sourceSnapshot.issuanceFence().value(),
                  sourceSnapshot.issuanceFence().sourceVersion(),
                  Optional.empty()),
              net.firedevops.firemud.accountservice.repository
                  .AccountGameplayCredentialRequestBindingFixture.binding());
      inTransaction(database, () -> issuance.beginPending(pending));

      AccountResponseEnvelopeCryptography responseCryptography =
          new AccountResponseEnvelopeCryptography(
              new AccountResponseEnvelopeKeyring(writeResponseKeyring()));
      AccountGameplayDelegationResponseEnvelopeService responseEnvelopes =
          new AccountGameplayDelegationResponseEnvelopeService(
              new AccountGameplayDelegationResponseEnvelopeRepository(
                  dsl, sources, bundles, responseCryptography));
      AccountGameplayDelegationCommitSignerFixture signerFixture =
          AccountGameplayDelegationCommitSignerFixture.create(
              temporaryDirectory,
              issuance,
              responseEnvelopes,
              database.manager(),
              Clock.systemUTC());

      adminConnection = fixtureAdmin.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      assertThat(admin.aclWhoami()).isEqualTo(FIXTURE_ADMIN_IDENTITY);
      for (String key : List.of(issuerKey, accountKey)) {
        assertThat(admin.get(ascii(key)))
            .as("isolated authority projection key is absent before this test owns it")
            .isNull();
      }
      ownedKeys.add(issuerKey);
      ownedKeys.add(accountKey);

      TrackedProvider writerProvider = new TrackedProvider(writer);
      AccountGameplayDelegationRedisClient redis = redisClient(writerProvider);
      AccountGameplayDelegationAuthorityProjection projection =
          org.mockito.Mockito.spy(
              new AccountGameplayDelegationAuthorityProjection(sources, database.manager(), redis));
      AccountGameplayDelegationTokenRegistry tokenRegistry =
          new AccountGameplayDelegationTokenRegistry(
              issuance, database.manager(), redis, Clock.systemUTC(), REGISTRY_BYTES, 30_000L);
      AccountGameplayDelegationIssuanceCommitService commitService =
          new AccountGameplayDelegationIssuanceCommitService(
              signerFixture.signer(),
              tokenRegistry,
              projection,
              issuance,
              database.manager(),
              Clock.systemUTC());

      signerFixture.preparePendingCandidate(pending.requestId());
      var durablePending =
          inTransaction(database, () -> issuance.readPendingRegistryCandidate(pending.requestId()));
      String tokenKey =
          AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + durablePending.tokenHash();
      assertThat(admin.get(ascii(tokenKey)))
          .as("isolated token registry key is absent before this test owns it")
          .isNull();
      ownedKeys.add(tokenKey);
      var pendingReceipt = tokenRegistry.registerPending(pending.requestId());
      assertThat(pendingReceipt.tokenKey()).isEqualTo(tokenKey);
      assertThat(admin.get(ascii(tokenKey))).isNotNull();
      byte[] pendingBytes = admin.get(ascii(tokenKey));
      long originalDeadline = pendingReceipt.absoluteExpiryMillis();
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalDeadline);

      org.mockito.Mockito.doThrow(
              new AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException())
          .when(projection)
          .publishCurrent(accountId);
      assertThatThrownBy(() -> commitService.commitPendingCandidate(pending.requestId()))
          .isInstanceOf(
              AccountGameplayDelegationIssuanceCommitService.IssuanceCommitUnavailableException
                  .class)
          .hasNoCause();
      var stillPending =
          inTransaction(
              database,
              () -> issuance.readCurrentGameplayLoginReplay(pending.requestId()).orElseThrow());
      assertThat(stillPending.state())
          .isEqualTo(AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayState.PENDING);
      assertThat(stillPending.identity().operationId()).isEqualTo(pending.operationId());
      assertThat(stillPending.operation().operationId()).isEqualTo(pending.operationId());
      assertThat(stillPending.operation().requestId()).isEqualTo(pending.requestId());
      assertThat(stillPending.operation().candidateBound()).isTrue();
      assertThat(admin.get(ascii(tokenKey))).containsExactly(pendingBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalDeadline);
      assertThat(admin.get(ascii(issuerKey))).isNull();
      assertThat(admin.get(ascii(accountKey))).isNull();

      org.mockito.Mockito.doCallRealMethod().when(projection).publishCurrent(accountId);

      var committedResult = commitService.commitPendingCandidate(pending.requestId());
      assertThat(committedResult.outcome())
          .isEqualTo(AccountGameplayDelegationIssuanceCommitService.Outcome.COMMITTED);
      var committedOwner =
          inTransaction(
              database, () -> issuance.readCurrentCommittedCandidate(pending.requestId()));
      assertThat(committedOwner.tokenSha256()).isEqualTo(pendingReceipt.tokenHash());
      assertThat(committedOwner.commitProofSha256()).isEqualTo(committedResult.proofSha256());
      assertThat(committedOwner.canonicalRegistryRecordSha256())
          .isEqualTo(pendingReceipt.canonicalRecordSha256());
      assertThat(committedOwner.toString())
          .isEqualTo("CommittedCandidateVerificationData[redacted]");
      assertThat(committedOwner.registryAbsoluteExpiryMillis()).isEqualTo(originalDeadline);
      assertThat(committedOwner.registrationLocalAofCount()).isGreaterThanOrEqualTo(1L);
      assertThat(committedOwner.registrationReplicaAofCount()).isGreaterThanOrEqualTo(1L);
      assertThat(committedOwner.registrationOutcome())
          .isEqualTo(PendingRegistrationOutcome.EXACT_RETRY);
      assertCurrentOwnerProjection(projection, admin, accountId, sourceSnapshot);

      AccountGameplayDelegationCommittedIssuanceOwner committedIssuanceOwner =
          new AccountGameplayDelegationCommittedIssuanceOwner(
              issuance,
              signerFixture.signer(),
              tokenRegistry,
              database.manager(),
              Clock.systemUTC());

      int activationConnection = writerProvider.openedConnections();
      var activeReceipt = committedIssuanceOwner.activateCommittedIssuance(pending.requestId());
      assertThat(activeReceipt.outcome())
          .isEqualTo(AccountGameplayDelegationRedisClient.ActiveRegistrationOutcome.ACTIVATED);
      assertThat(activeReceipt.operationId()).isEqualTo(pending.operationId());
      assertThat(activeReceipt.requestId()).isEqualTo(pending.requestId());
      assertThat(activeReceipt.accountId()).isEqualTo(accountId);
      assertThat(activeReceipt.commitProofSha256()).isEqualTo(committedResult.proofSha256());
      assertThat(activeReceipt.registryVersion()).isEqualTo(2L);
      assertThat(activeReceipt.absoluteExpiryMillis()).isEqualTo(originalDeadline);
      assertThat(writerProvider.trace(activationConnection))
          .containsSubsequence("aclWhoami", "get", "pexpiretime", "close");
      assertThat(writerProvider.trace(activationConnection + 1))
          .containsSubsequence(
              "aclWhoami",
              "scriptLoad",
              "get",
              "pexpiretime",
              "evalsha",
              "dispatch",
              "get",
              "pexpiretime",
              "close");
      byte[] activeBytes = admin.get(ascii(tokenKey));
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalDeadline);
      assertThat(new String(activeBytes, StandardCharsets.UTF_8)).doesNotContain("eyJ");
      var pendingRecord =
          GameSessionAccountDelegationRegistryRecord.decode(pendingBytes, REGISTRY_BYTES);
      var activeRecord =
          GameSessionAccountDelegationRegistryRecord.decode(activeBytes, REGISTRY_BYTES);
      assertThat(pendingRecord.state()).isEqualTo("pending");
      assertThat(activeRecord.state()).isEqualTo("active");
      assertThat(activeRecord.registryVersion()).isEqualTo(2L);
      Map<String, Object> unchangedFields = new java.util.LinkedHashMap<>(pendingRecord.fields());
      unchangedFields.put("registryVersion", activeRecord.fields().get("registryVersion"));
      unchangedFields.put("state", activeRecord.fields().get("state"));
      assertThat(activeRecord.fields()).isEqualTo(unchangedFields);

      int retryConnection = writerProvider.openedConnections();
      AccountGameplayDelegationCommittedIssuanceOwner recoveredOwner =
          new AccountGameplayDelegationCommittedIssuanceOwner(
              issuance,
              signerFixture.signer(),
              tokenRegistry,
              database.manager(),
              Clock.systemUTC());
      var exactRetry = recoveredOwner.activateCommittedIssuance(pending.requestId());
      assertThat(exactRetry.outcome())
          .isEqualTo(AccountGameplayDelegationRedisClient.ActiveRegistrationOutcome.EXACT_RETRY);
      assertThat(exactRetry.activeRecordSha256()).isEqualTo(activeReceipt.activeRecordSha256());
      assertThat(admin.get(ascii(tokenKey))).containsExactly(activeBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalDeadline);
      assertThat(writerProvider.trace(retryConnection))
          .containsSubsequence("aclWhoami", "get", "pexpiretime", "close");
      assertThat(writerProvider.trace(retryConnection + 1))
          .containsSubsequence(
              "aclWhoami", "scriptLoad", "evalsha", "dispatch", "get", "pexpiretime", "close");

      int connectionsBeforeAuthorityChange = writerProvider.openedConnections();
      BeforeSecondTransactionManager postCheckRaceManager =
          new BeforeSecondTransactionManager(
              database.manager(),
              () ->
                  commitPasswordResetAuthorityChange(
                      database, dsl, authorities, outbox, sources, accountId));
      AccountGameplayDelegationCommittedIssuanceOwner postCheckRaceOwner =
          new AccountGameplayDelegationCommittedIssuanceOwner(
              issuance,
              signerFixture.signer(),
              tokenRegistry,
              postCheckRaceManager,
              Clock.systemUTC());
      assertThatThrownBy(() -> postCheckRaceOwner.activateCommittedIssuance(pending.requestId()))
          .isInstanceOf(
              AccountGameplayDelegationCommittedIssuanceOwner.OwnerUnavailableException.class)
          .hasNoCause();
      var changedAccountAuthority =
          inTransaction(
              database,
              () ->
                  authorities.read(
                      AccountAuthorityGenerationRepository.AuthorityScope.account(accountId)));
      assertThat(changedAccountAuthority.generation())
          .isGreaterThan(sourceSnapshot.account().generation());
      var refreshedSources =
          inTransaction(
              database, () -> sources.readCurrentIssuerAccountSources(ACCOUNT_ISSUER, accountId));
      assertThat(refreshedSources.account().generation())
          .isEqualTo(changedAccountAuthority.generation());
      assertThat(refreshedSources.account().generation())
          .isGreaterThan(sourceSnapshot.account().generation());
      assertThat(refreshedSources.account().sourceVersion())
          .isNotEqualTo(sourceSnapshot.account().sourceVersion());
      assertThat(refreshedSources.issuer()).isEqualTo(sourceSnapshot.issuer());
      assertThat(writerProvider.openedConnections())
          .isEqualTo(connectionsBeforeAuthorityChange + 2);
      assertThat(writerProvider.trace(connectionsBeforeAuthorityChange))
          .containsSubsequence("aclWhoami", "get", "pexpiretime", "close");
      assertThat(writerProvider.trace(connectionsBeforeAuthorityChange + 1))
          .containsSubsequence(
              "aclWhoami", "scriptLoad", "evalsha", "dispatch", "get", "pexpiretime", "close");
      assertThat(admin.get(ascii(tokenKey))).containsExactly(activeBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalDeadline);

      // The committed record is not returned as a credential and does not become an authorization
      // result merely because the registry transition itself succeeded.
      assertThat(activeReceipt.toString()).doesNotContain("eyJ");
    } finally {
      if (adminConnection != null) {
        RedisCommands<byte[], byte[]> admin = adminConnection.sync();
        for (String key : ownedKeys) admin.del(ascii(key));
        adminConnection.close();
      }
      writer.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      fixtureAdmin.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }

  @Test
  void physicalCommittedResponseRecoverySurvivesOrdinarySignerRotationAndReturnsSameCredential()
      throws Exception {
    RedisClient writer = redisClient(writerUri);
    RedisClient fixtureAdmin = redisClient(fixtureAdminUri);
    StatefulRedisConnection<byte[], byte[]> adminConnection = null;
    Set<String> ownedKeys = new LinkedHashSet<>();
    try {
      TestContext database = newPostgresContext();
      DSLContext dsl = database.dsl();
      AccountAuthorityGenerationRepository authorities =
          new AccountAuthorityGenerationRepository(dsl);
      AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
      AccountAuthoritySourceEvidenceRepository sources =
          new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
      UUID accountId = createFreshAccount(database, sources);
      String issuerKey =
          AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX + ACCOUNT_ISSUER;
      String accountKey =
          AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId;
      AccountAuthEvidenceBundleRepository bundles =
          new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox);
      AccountGameplayDelegationIssuanceRepository issuance =
          transactionalIssuanceOwner(
              new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles),
              database.manager());
      IssuerAccountSourceSnapshot sourceSnapshot =
          inTransaction(
              database, () -> sources.readCurrentIssuerAccountSources(ACCOUNT_ISSUER, accountId));
      long now = Instant.now().getEpochSecond();
      UUID requestId = UUID.randomUUID();
      UUID callerContextId = UUID.randomUUID();
      PendingIntent pending =
          new PendingIntent(
              UUID.randomUUID(),
              requestId,
              CALLER_WORKLOAD,
              callerContextId,
              UUID.randomUUID(),
              now,
              now,
              Math.addExact(now, 180L),
              new AccountAuthoritySnapshot(
                  accountId,
                  sourceSnapshot.issuer().generation(),
                  sourceSnapshot.issuer().sourceVersion(),
                  sourceSnapshot.account().generation(),
                  sourceSnapshot.account().sourceVersion(),
                  sourceSnapshot.issuanceFence().value(),
                  sourceSnapshot.issuanceFence().sourceVersion(),
                  sourceSnapshot
                      .account()
                      .accountSecurityCutoff()
                      .map(
                          cutoff ->
                              new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                                  cutoff.accountAuthorityGeneration(),
                                  cutoff.outboxStreamKey(),
                                  cutoff.outboxSequence()))),
              net.firedevops.firemud.accountservice.repository
                  .AccountGameplayCredentialRequestBindingFixture.binding());
      inTransaction(database, () -> issuance.beginPending(pending));

      AccountResponseEnvelopeCryptography responseCryptography =
          new AccountResponseEnvelopeCryptography(
              new AccountResponseEnvelopeKeyring(writeResponseKeyring()));
      AccountGameplayDelegationResponseEnvelopeRepository responseRepository =
          new AccountGameplayDelegationResponseEnvelopeRepository(
              dsl, sources, bundles, issuance, responseCryptography);
      AccountGameplayDelegationResponseEnvelopeService responseEnvelopes =
          new AccountGameplayDelegationResponseEnvelopeService(responseRepository);
      AccountGameplayDelegationCommitSignerFixture signerFixture =
          AccountGameplayDelegationCommitSignerFixture.create(
              temporaryDirectory,
              issuance,
              responseEnvelopes,
              database.manager(),
              Clock.systemUTC());

      adminConnection = fixtureAdmin.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      assertThat(admin.aclWhoami()).isEqualTo(FIXTURE_ADMIN_IDENTITY);
      for (String key : List.of(issuerKey, accountKey)) {
        assertThat(admin.get(ascii(key)))
            .as("isolated Account projection key is absent before this test owns it")
            .isNull();
      }
      ownedKeys.add(issuerKey);
      ownedKeys.add(accountKey);

      TrackedProvider writerProvider = new TrackedProvider(writer);
      AccountGameplayDelegationRedisClient redis = redisClient(writerProvider);
      AccountGameplayDelegationAuthorityProjection projection =
          org.mockito.Mockito.spy(
              new AccountGameplayDelegationAuthorityProjection(sources, database.manager(), redis));
      AccountGameplayDelegationTokenRegistry tokenRegistry =
          new AccountGameplayDelegationTokenRegistry(
              issuance, database.manager(), redis, Clock.systemUTC(), REGISTRY_BYTES, 30_000L);
      AccountGameplayDelegationIssuanceCommitService commitService =
          new AccountGameplayDelegationIssuanceCommitService(
              signerFixture.signer(),
              tokenRegistry,
              projection,
              issuance,
              database.manager(),
              Clock.systemUTC());

      signerFixture.preparePendingCandidate(requestId);
      var pendingCandidate =
          inTransaction(database, () -> issuance.readPendingRegistryCandidate(requestId));
      String tokenKey =
          AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + pendingCandidate.tokenHash();
      assertThat(admin.get(ascii(tokenKey)))
          .as("isolated committed-candidate registry key is absent before this test owns it")
          .isNull();
      ownedKeys.add(tokenKey);

      org.mockito.Mockito.doThrow(
              new AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException())
          .when(projection)
          .publishCurrent(accountId);
      assertThatThrownBy(() -> commitService.commitPendingCandidate(requestId))
          .isInstanceOf(
              AccountGameplayDelegationIssuanceCommitService.IssuanceCommitUnavailableException
                  .class)
          .hasNoCause();
      var stillPending =
          inTransaction(
              database, () -> issuance.readCurrentGameplayLoginReplay(requestId).orElseThrow());
      assertThat(stillPending.state())
          .isEqualTo(AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayState.PENDING);
      assertThat(stillPending.identity().operationId()).isEqualTo(pending.operationId());
      assertThat(stillPending.operation().operationId()).isEqualTo(pending.operationId());
      assertThat(stillPending.operation().requestId()).isEqualTo(requestId);
      assertThat(stillPending.operation().candidateBound()).isTrue();
      byte[] pendingRegistryBytes = admin.get(ascii(tokenKey));
      assertThat(pendingRegistryBytes).containsExactly(pendingCandidate.canonicalRecordBytes());
      assertThat(
              GameSessionAccountDelegationRegistryRecord.decode(
                      pendingRegistryBytes, REGISTRY_BYTES)
                  .state())
          .isEqualTo("pending");
      long originalRegistryDeadline =
          Math.addExact(Math.multiplyExact(pending.expiresAtEpochSecond(), 1_000L), 30_000L);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalRegistryDeadline);
      assertThat(admin.get(ascii(issuerKey))).isNull();
      assertThat(admin.get(ascii(accountKey))).isNull();

      org.mockito.Mockito.doCallRealMethod().when(projection).publishCurrent(accountId);

      var commit = commitService.commitPendingCandidate(requestId);
      assertThat(commit.outcome())
          .isEqualTo(AccountGameplayDelegationIssuanceCommitService.Outcome.COMMITTED);
      var committed =
          inTransaction(database, () -> issuance.readCurrentCommittedCandidate(requestId));
      assertThat(committed.identity().operationId()).isEqualTo(pending.operationId());
      assertThat(committed.identity().requestId()).isEqualTo(requestId);
      assertThat(committed.identity().accountId()).isEqualTo(accountId);
      assertThat(committed.identity().callerWorkload()).isEqualTo(CALLER_WORKLOAD);
      assertThat(committed.identity().callerContextId()).isEqualTo(callerContextId);
      assertThat(committed.envelopeSha256()).isNotBlank();
      assertThat(committed.tokenSha256()).isEqualTo(pendingCandidate.tokenHash());
      assertCurrentOwnerProjection(projection, admin, accountId, sourceSnapshot);
      assertThat(committed.registryAbsoluteExpiryMillis()).isEqualTo(originalRegistryDeadline);
      long originalRecoveryExpiry = committed.responseRecoveryExpiryEpochMillis();
      assertThat(originalRecoveryExpiry)
          .isEqualTo(Math.multiplyExact(pending.expiresAtEpochSecond(), 1_000L));
      pendingRegistryBytes = admin.get(ascii(tokenKey));
      assertThat(pendingRegistryBytes).isNotNull();
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalRegistryDeadline);
      assertThat(
              GameSessionAccountDelegationRegistryRecord.decode(
                      pendingRegistryBytes, REGISTRY_BYTES)
                  .state())
          .isEqualTo("pending");

      assertThat(committed.signerGeneration()).isEqualTo("42");
      signerFixture.rotateToNextGenerationRetainingOriginalKey();
      assertThat(signerFixture.currentGeneration()).isEqualTo("43");
      assertThat(signerFixture.currentKid()).isEqualTo("account-key-43");
      assertThat(
              inTransaction(database, () -> issuance.readCurrentCommittedCandidate(requestId))
                  .signerGeneration())
          .isEqualTo("42");

      AccountGameplayDelegationCommittedIssuanceOwner committedOwner =
          new AccountGameplayDelegationCommittedIssuanceOwner(
              issuance,
              signerFixture.signer(),
              tokenRegistry,
              database.manager(),
              Clock.systemUTC());
      AccountGameplayDelegationResponseRecoveryOwner recoveryOwner =
          new AccountGameplayDelegationResponseRecoveryOwner(
              responseRepository, committedOwner, database.manager());
      CallerIdentity caller = new CallerIdentity(CALLER_WORKLOAD, callerContextId);

      int connectionsBeforeCallerMismatch = writerProvider.openedConnections();
      assertThatThrownBy(
              () ->
                  recoveryOwner.recoverInitialLoginResponse(
                      requestId, accountId, new CallerIdentity(CALLER_WORKLOAD, UUID.randomUUID())))
          .isInstanceOf(IdempotencyConflictException.class)
          .hasNoCause();
      assertThatThrownBy(
              () ->
                  recoveryOwner.recoverInitialLoginResponse(
                      requestId,
                      accountId,
                      new CallerIdentity(
                          "spiffe://firemud/ns/other/sa/game-session-service", callerContextId)))
          .isInstanceOf(IdempotencyConflictException.class)
          .hasNoCause();
      assertThatThrownBy(
              () -> recoveryOwner.recoverInitialLoginResponse(requestId, UUID.randomUUID(), caller))
          .isInstanceOf(IdempotencyConflictException.class)
          .hasNoCause();
      assertThat(writerProvider.openedConnections()).isEqualTo(connectionsBeforeCallerMismatch);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(pendingRegistryBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalRegistryDeadline);

      signerFixture.withdrawOriginalKeyFromCurrentJwks();
      int connectionsBeforeMissingHistoricalKey = writerProvider.openedConnections();
      assertThatThrownBy(
              () -> recoveryOwner.recoverInitialLoginResponse(requestId, accountId, caller))
          .isInstanceOf(
              AccountGameplayDelegationResponseRecoveryOwner.OwnerUnavailableException.class)
          .hasNoCause();
      assertThat(writerProvider.openedConnections())
          .isEqualTo(connectionsBeforeMissingHistoricalKey);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(pendingRegistryBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalRegistryDeadline);
      signerFixture.restoreOriginalKeyInCurrentJwks();

      int firstRecoveryConnection = writerProvider.openedConnections();
      RecoveredCredential first =
          recoveryOwner.recoverInitialLoginResponse(requestId, accountId, caller);
      assertThat(writerProvider.openedConnections()).isEqualTo(firstRecoveryConnection + 2);
      assertThat(writerProvider.trace(firstRecoveryConnection))
          .containsSubsequence("aclWhoami", "get", "pexpiretime", "close");
      assertThat(writerProvider.trace(firstRecoveryConnection + 1))
          .containsSubsequence(
              "aclWhoami",
              "scriptLoad",
              "get",
              "pexpiretime",
              "evalsha",
              "dispatch",
              "get",
              "pexpiretime",
              "close");
      byte[] activeRegistryBytes = admin.get(ascii(tokenKey));
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalRegistryDeadline);
      assertThat(
              GameSessionAccountDelegationRegistryRecord.decode(activeRegistryBytes, REGISTRY_BYTES)
                  .state())
          .isEqualTo("active");
      assertThat(first.accountId()).isEqualTo(accountId);
      assertThat(first.tokenJti()).isEqualTo(pending.tokenJti());
      assertThat(first.profile()).isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
      assertThat(first.tokenSha256()).isEqualTo(committed.tokenSha256());
      assertThat(first.expiresAtEpochSecond()).isEqualTo(pending.expiresAtEpochSecond());
      assertThat(first.toString()).isEqualTo("RecoveredCredential[secret,redacted]");
      var currentCandidate =
          committedOwner.requireCurrentActiveCommittedCandidateByJti(pending.tokenJti());
      assertThat(currentCandidate.tokenSha256()).isEqualTo(committed.tokenSha256());
      assertThat(admin.get(ascii(tokenKey))).containsExactly(activeRegistryBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalRegistryDeadline);

      signerFixture.withdrawOriginalKeyFromCurrentJwks();
      int connectionsBeforeCurrentnessMissingHistoricalKey = writerProvider.openedConnections();
      assertThatThrownBy(
              () -> committedOwner.requireCurrentActiveCommittedCandidateByJti(pending.tokenJti()))
          .isInstanceOf(
              AccountGameplayDelegationCommittedIssuanceOwner.OwnerUnavailableException.class)
          .hasNoCause();
      assertThat(writerProvider.openedConnections())
          .isEqualTo(connectionsBeforeCurrentnessMissingHistoricalKey);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(activeRegistryBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalRegistryDeadline);
      signerFixture.restoreOriginalKeyInCurrentJwks();

      byte[] originalCredentialBytes = first.compactJwtBytes();
      assertThat(originalCredentialBytes).isNotEmpty();
      String originalCompactJwt = new String(originalCredentialBytes, StandardCharsets.US_ASCII);
      String[] originalJwtSegments = originalCompactJwt.split("\\.", -1);
      assertThat(originalJwtSegments)
          .hasSize(3)
          .allSatisfy(segment -> assertThat(segment).isNotEmpty());
      String originalHeader =
          new String(Base64.getUrlDecoder().decode(originalJwtSegments[0]), StandardCharsets.UTF_8);
      assertThat(originalHeader).contains("\"kid\":\"account-key-42\"");

      int retryConnection = writerProvider.openedConnections();
      RecoveredCredential exactRetry =
          recoveryOwner.recoverInitialLoginResponse(requestId, accountId, caller);
      assertThat(writerProvider.openedConnections()).isEqualTo(retryConnection + 2);
      assertThat(Arrays.equals(exactRetry.compactJwtBytes(), originalCredentialBytes)).isTrue();
      assertThat(exactRetry.tokenSha256()).isEqualTo(first.tokenSha256());
      assertThat(exactRetry.expiresAtEpochSecond()).isEqualTo(first.expiresAtEpochSecond());
      assertThat(admin.get(ascii(tokenKey))).containsExactly(activeRegistryBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalRegistryDeadline);

      var committedAfterRetry =
          inTransaction(database, () -> issuance.readCurrentCommittedCandidate(requestId));
      assertThat(committedAfterRetry.commitProofSha256()).isEqualTo(committed.commitProofSha256());
      assertThat(committedAfterRetry.tokenSha256()).isEqualTo(committed.tokenSha256());
      assertThat(committedAfterRetry.signerGeneration()).isEqualTo(committed.signerGeneration());
      assertThat(committedAfterRetry.envelopeSha256()).isEqualTo(committed.envelopeSha256());
      assertThat(signerFixture.currentGeneration()).isEqualTo("43");
      assertThat(signerFixture.currentKid()).isEqualTo("account-key-43");
      assertThat(committedAfterRetry.responseRecoveryExpiryEpochMillis())
          .isEqualTo(originalRecoveryExpiry);
      assertThat(committedAfterRetry.registryAbsoluteExpiryMillis())
          .isEqualTo(originalRegistryDeadline);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT count(*) AS envelope_count FROM "
                              + "account_gameplay_delegation_response_envelopes WHERE request_id = ?",
                          requestId),
                      "response envelope count query must return a row")
                  .get("envelope_count", Long.class))
          .isEqualTo(1L);
      byte[] storedEnvelope =
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT envelope_bytes FROM account_gameplay_delegation_response_envelopes "
                          + "WHERE request_id = ?",
                      requestId),
                  "stored response envelope query must return a row")
              .get("envelope_bytes", byte[].class);
      assertThat(containsSequence(storedEnvelope, originalCredentialBytes)).isFalse();
      assertThat(committedAfterRetry.toString()).doesNotContain("eyJ");

      byte[] changedRegistryBytes = activeRegistryBytes.clone();
      changedRegistryBytes[0] = (byte) 'x';
      admin.set(ascii(tokenKey), changedRegistryBytes);
      admin.pexpireat(ascii(tokenKey), originalRegistryDeadline);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(changedRegistryBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalRegistryDeadline);
      int changedRegistryConnection = writerProvider.openedConnections();
      assertThatThrownBy(
              () -> recoveryOwner.recoverInitialLoginResponse(requestId, accountId, caller))
          .isInstanceOf(
              AccountGameplayDelegationResponseRecoveryOwner.OwnerUnavailableException.class)
          .hasNoCause();
      assertThat(writerProvider.openedConnections()).isEqualTo(changedRegistryConnection + 1);
      assertThat(writerProvider.trace(changedRegistryConnection))
          .containsSubsequence("aclWhoami", "get", "pexpiretime", "close");

      admin.del(ascii(tokenKey));
      assertThat(admin.get(ascii(tokenKey))).isNull();
      int missingRegistryConnection = writerProvider.openedConnections();
      assertThatThrownBy(
              () -> recoveryOwner.recoverInitialLoginResponse(requestId, accountId, caller))
          .isInstanceOf(
              AccountGameplayDelegationResponseRecoveryOwner.OwnerUnavailableException.class)
          .hasNoCause();
      assertThat(writerProvider.openedConnections()).isEqualTo(missingRegistryConnection + 1);
      assertThat(writerProvider.trace(missingRegistryConnection))
          .containsSubsequence("aclWhoami", "get", "pexpiretime", "close");
    } finally {
      if (adminConnection != null) {
        RedisCommands<byte[], byte[]> admin = adminConnection.sync();
        for (String key : ownedKeys) admin.del(ascii(key));
        adminConnection.close();
      }
      writer.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      fixtureAdmin.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }

  /**
   * Actual LOGIN producer composition with controlled Account/challenge/key/caller inputs. The
   * existing signer fixture performs real crypto but supplies mocked protected signer/JWKS trust;
   * the isolated Redis fixture proves ACL/AOF behavior over loopback, not TLS or deployed custody.
   */
  @ParameterizedTest
  @ValueSource(strings = {"PASSWORD", "EMAIL_OTP"})
  void canonicalPrimaryLoginCommitsActivatesAndRecoversDiscardedResponse(String authMode)
      throws Exception {
    RedisClient writer = redisClient(writerUri);
    RedisClient fixtureAdmin = redisClient(fixtureAdminUri);
    StatefulRedisConnection<byte[], byte[]> adminConnection = null;
    Set<String> ownedKeys = new LinkedHashSet<>();
    try {
      TestContext database = newPostgresContext();
      DSLContext dsl = database.dsl();
      var authorities = new AccountAuthorityGenerationRepository(dsl);
      var outbox = new AccountAuthorityOutboxRepository(dsl);
      var sources = new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
      var accounts = new AccountRepository(dsl, sources);
      var challenges = new AccountEmailLoginChallengeRepository(dsl);
      boolean otp = "EMAIL_OTP".equals(authMode);
      String password = "primary-password-" + UUID.randomUUID();
      String code = "246810";
      String secret = otp ? code : password;
      String passwordHash = hashPrimaryCredential(password);
      String codeHash = hashPrimaryCredential(code);
      Account seed = new Account();
      seed.setUsername("primary-login-" + UUID.randomUUID());
      seed.setEmail("primary-login-" + UUID.randomUUID() + "@example.test");
      seed.setPasswordHash(passwordHash);
      seed.setLoginAuthModes(authMode);
      Account account =
          inTransaction(
              database,
              () -> {
                sources.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
                Account saved = accounts.save(seed);
                challenges.save(primaryLoginChallenge(saved, codeHash));
                return saved;
              });
      UUID accountId = account.getAccountUuid();
      UUID requestId = UUID.randomUUID();
      var context =
          new GameplayCredentialSourceContext(UUID.randomUUID(), "203.0.113.10", "FIRST_PARTY_WEB");
      var request =
          new CanonicalGameplayLoginRequest(requestId, account.getEmail(), secret, context);
      var bundles = new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox);
      var issuance =
          transactionalIssuanceOwner(
              new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles),
              database.manager());
      var responseCryptography =
          new AccountResponseEnvelopeCryptography(
              new AccountResponseEnvelopeKeyring(writeResponseKeyring()));
      var responseRepository =
          new AccountGameplayDelegationResponseEnvelopeRepository(
              dsl, sources, bundles, issuance, responseCryptography);
      var responseEnvelopes =
          new AccountGameplayDelegationResponseEnvelopeService(responseRepository);
      var signerFixture =
          AccountGameplayDelegationCommitSignerFixture.create(
              temporaryDirectory,
              issuance,
              responseEnvelopes,
              database.manager(),
              Clock.systemUTC());

      adminConnection = fixtureAdmin.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      assertThat(admin.aclWhoami()).isEqualTo(FIXTURE_ADMIN_IDENTITY);
      String issuerKey =
          AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX + ACCOUNT_ISSUER;
      String accountKey =
          AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId;
      for (String key : List.of(issuerKey, accountKey)) {
        assertThat(admin.get(ascii(key)))
            .as("isolated projection key is absent before this test owns it")
            .isNull();
      }
      ownedKeys.add(issuerKey);
      ownedKeys.add(accountKey);
      var writerProvider = new TrackedProvider(writer);
      AtomicReference<AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayReadback>
          beforeRegistration = new AtomicReference<>();
      // Observe the real committed SQL producer before the first physical registry connection.
      AccountCoordinationPinnedConnectionProvider observedProvider =
          () -> {
            if (beforeRegistration.get() == null) {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var pending =
                  inTransaction(
                      database,
                      () -> issuance.readCurrentGameplayLoginReplay(requestId).orElseThrow());
              assertThat(pending.state())
                  .isEqualTo(
                      AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayState.PENDING);
              assertThat(pending.operation().candidateBound()).isTrue();
              assertThat(pending.identity().requestId()).isEqualTo(requestId);
              assertThat(pending.identity().accountId()).isEqualTo(accountId);
              assertThat(pending.identity().callerContextId()).isEqualTo(context.contextId());
              assertThat(pending.identity().callerWorkload()).isEqualTo(CALLER_WORKLOAD);
              assertThat(challenges.findByAccountId(account.getId()).isEmpty()).isEqualTo(otp);
              var signed =
                  inTransaction(database, () -> issuance.readPendingRegistryCandidate(requestId));
              String tokenKey =
                  AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + signed.tokenHash();
              assertThat(admin.get(ascii(tokenKey)))
                  .as("isolated token key is absent before this test owns it")
                  .isNull();
              ownedKeys.add(tokenKey);
              beforeRegistration.set(pending);
            }
            return writerProvider.openPinnedConnection();
          };
      var redis = redisClient(observedProvider);
      var projection =
          new AccountGameplayDelegationAuthorityProjection(sources, database.manager(), redis);
      var tokenRegistry =
          new AccountGameplayDelegationTokenRegistry(
              issuance, database.manager(), redis, Clock.systemUTC(), REGISTRY_BYTES, 30_000L);
      var commitService =
          new AccountGameplayDelegationIssuanceCommitService(
              signerFixture.signer(),
              tokenRegistry,
              projection,
              issuance,
              database.manager(),
              Clock.systemUTC());
      var committedOwner =
          new AccountGameplayDelegationCommittedIssuanceOwner(
              issuance,
              signerFixture.signer(),
              tokenRegistry,
              database.manager(),
              Clock.systemUTC());
      var recoveryOwner =
          new AccountGameplayDelegationResponseRecoveryOwner(
              responseRepository, committedOwner, database.manager());
      var verifier =
          org.mockito.Mockito.spy(
              new AccountGameplayPrimaryCredentialVerifier(
                  primaryCredentialAuthority(
                      database, accounts, challenges, authorities, outbox, sources)));
      var login =
          new AccountGameplayCanonicalLoginOwner(
              accounts,
              issuance,
              primaryLoginDigestKeys(),
              commitService,
              recoveryOwner,
              database.manager(),
              Clock.systemUTC());

      // Simulate a discarded delivery after full owner response production. Retain its exact bytes
      // only as the test oracle; this is not an authenticated network-loss/transport proof.
      var discarded = login.authenticate(request, CALLER_WORKLOAD, verifier);
      var pending =
          Objects.requireNonNull(
              beforeRegistration.get(), "Pending registration observation required");
      var committed =
          inTransaction(database, () -> issuance.readCurrentCommittedCandidate(requestId));
      var replay =
          inTransaction(
              database, () -> issuance.readCurrentGameplayLoginReplay(requestId).orElseThrow());
      assertThat(replay.state())
          .isEqualTo(
              AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayState.COMMITTED);
      assertThat(replay.identity()).isEqualTo(pending.identity());
      assertThat(committed.identity()).isEqualTo(pending.identity());
      assertThat(committed.commitProofSha256()).isNotBlank();
      assertThat(committed.registrationLocalAofCount()).isGreaterThanOrEqualTo(1L);
      assertThat(committed.registrationReplicaAofCount()).isGreaterThanOrEqualTo(1L);
      assertThat(discarded.accountId()).isEqualTo(accountId);
      assertThat(discarded.requestId()).isEqualTo(requestId);
      assertThat(discarded.callerContextId()).isEqualTo(context.contextId());
      assertThat(discarded.tokenJti()).isEqualTo(pending.identity().tokenJti());
      assertThat(discarded.tokenSha256()).isEqualTo(committed.tokenSha256());
      assertThat(discarded.profile()).isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
      assertThat(discarded.expiresAtEpochSecond())
          .isEqualTo(pending.identity().expiresAtEpochSecond());
      String tokenKey =
          AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + discarded.tokenSha256();
      byte[] activeBytes = admin.get(ascii(tokenKey));
      assertThat(
              GameSessionAccountDelegationRegistryRecord.decode(activeBytes, REGISTRY_BYTES)
                  .state())
          .isEqualTo("active");
      long originalDeadline = committed.registryAbsoluteExpiryMillis();
      assertThat(originalDeadline)
          .isEqualTo(
              Math.addExact(Math.multiplyExact(discarded.expiresAtEpochSecond(), 1000L), 30_000L));
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalDeadline);
      var sourceSnapshot =
          inTransaction(
              database, () -> sources.readCurrentIssuerAccountSources(ACCOUNT_ISSUER, accountId));
      assertCurrentOwnerProjection(projection, admin, accountId, sourceSnapshot);
      var storedOperation =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT * FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                  requestId));
      var storedEnvelope =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT * FROM account_gameplay_delegation_response_envelopes WHERE request_id = ?",
                  requestId));
      assertThat(
              containsSequence(
                  storedEnvelope.get("envelope_bytes", byte[].class), discarded.compactJwtBytes()))
          .isFalse();

      // A newly issued OTP cannot be touched by replay of the consumed original challenge.
      if (otp) {
        inTransaction(
            database, () -> challenges.save(primaryLoginChallenge(account, passwordHash)));
      }
      var retainedChallenge =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT * FROM account_email_login_challenge WHERE account_id = ?",
                  account.getId()));
      var recovered = login.authenticate(request, CALLER_WORKLOAD, verifier);
      assertThat(Arrays.equals(recovered.compactJwtBytes(), discarded.compactJwtBytes())).isTrue();
      assertThat(recovered.accountId()).isEqualTo(discarded.accountId());
      assertThat(recovered.requestId()).isEqualTo(discarded.requestId());
      assertThat(recovered.callerContextId()).isEqualTo(discarded.callerContextId());
      assertThat(recovered.tokenJti()).isEqualTo(discarded.tokenJti());
      assertThat(recovered.tokenSha256()).isEqualTo(discarded.tokenSha256());
      assertThat(recovered.issuedAtEpochSecond()).isEqualTo(discarded.issuedAtEpochSecond());
      assertThat(recovered.notBeforeEpochSecond()).isEqualTo(discarded.notBeforeEpochSecond());
      assertThat(recovered.expiresAtEpochSecond()).isEqualTo(discarded.expiresAtEpochSecond());
      assertThat(recovered.tokenGeneration()).isEqualTo(discarded.tokenGeneration());
      assertThat(recovered.issuanceFence()).isEqualTo(discarded.issuanceFence());
      assertThat(recovered.authorityTupleCanonicalJson())
          .containsExactly(discarded.authorityTupleCanonicalJson());
      assertThat(recovered.outboxCheckpointsCanonicalJson())
          .containsExactly(discarded.outboxCheckpointsCanonicalJson());
      assertThat(recovered.outboxSourceEventEvidenceCanonicalJson())
          .containsExactly(discarded.outboxSourceEventEvidenceCanonicalJson());

      int beforeConflicts = writerProvider.openedConnections();
      var changedCredential =
          new CanonicalGameplayLoginRequest(requestId, account.getEmail(), "wrong-secret", context);
      var changedEmail =
          new CanonicalGameplayLoginRequest(requestId, "other@example.test", secret, context);
      var changedContext =
          new CanonicalGameplayLoginRequest(
              requestId,
              account.getEmail(),
              secret,
              new GameplayCredentialSourceContext(
                  UUID.randomUUID(), context.canonicalClientAddress(), context.transportClass()));
      for (var changed : List.of(changedCredential, changedEmail, changedContext)) {
        assertThatThrownBy(() -> login.authenticate(changed, CALLER_WORKLOAD, verifier))
            .isInstanceOf(IdempotencyConflictException.class);
      }
      assertThatThrownBy(
              () ->
                  login.authenticate(
                      request, "spiffe://firemud/ns/other/sa/game-session-service", verifier))
          .isInstanceOf(IdempotencyConflictException.class);
      assertThat(writerProvider.openedConnections()).isEqualTo(beforeConflicts);
      assertThat(
              dsl.fetchOne(
                  "SELECT * FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                  requestId))
          .isEqualTo(storedOperation);
      assertThat(
              dsl.fetchOne(
                  "SELECT * FROM account_gameplay_delegation_response_envelopes WHERE request_id = ?",
                  requestId))
          .isEqualTo(storedEnvelope);
      assertThat(
              dsl.fetchOne(
                  "SELECT * FROM account_email_login_challenge WHERE account_id = ?",
                  account.getId()))
          .isEqualTo(retainedChallenge);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT count(*) FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                          requestId))
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(activeBytes);
      assertThat(admin.pexpiretime(ascii(tokenKey))).isEqualTo(originalDeadline);
      org.mockito.Mockito.verify(verifier, org.mockito.Mockito.times(1))
          .verify(
              org.mockito.ArgumentMatchers.any(Account.class),
              org.mockito.ArgumentMatchers.eq(secret));
      org.mockito.Mockito.verify(verifier, org.mockito.Mockito.times(otp ? 1 : 0))
          .consumeOneTimeCredential(
              org.mockito.ArgumentMatchers.any(Account.class), org.mockito.ArgumentMatchers.any());

      var disposals =
          new net.firedevops.firemud.accountservice.repository
              .AccountInitialGameplayLoginDisposalRepository(dsl);
      var disposalOwner =
          new net.firedevops.firemud.accountservice.service.session
              .AccountInitialGameplayLoginDisposalOwner(
              disposals, tokenRegistry, database.manager());
      var disposalRequest =
          new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest(
              requestId,
              context.contextId(),
              new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest
                  .TokenWitness(
                  discarded.tokenJti(),
                  discarded.tokenSha256(),
                  Long.parseLong(discarded.tokenGeneration()),
                  Long.parseLong(discarded.issuanceFence())));
      assertThatThrownBy(
              () ->
                  disposalOwner.retire(
                      disposalRequest, "spiffe://firemud/ns/other/sa/game-session-service"))
          .isInstanceOf(
              net.firedevops.firemud.accountservice.repository
                  .AccountInitialGameplayLoginDisposalRepository.DisposalConflictException.class);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(activeBytes);
      var intent = inTransaction(database, () -> disposals.begin(disposalRequest, CALLER_WORKLOAD));
      // Crash after durable SQL intent: every owner path denies while Redis is still active.
      assertThatThrownBy(() -> login.authenticate(request, CALLER_WORKLOAD, verifier))
          .isInstanceOf(
              AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);
      assertThatThrownBy(() -> committedOwner.activateCommittedIssuance(requestId))
          .isInstanceOf(
              AccountGameplayDelegationCommittedIssuanceOwner.OwnerUnavailableException.class);
      assertThatThrownBy(
              () ->
                  recoveryOwner.recoverInitialLoginResponse(
                      requestId,
                      accountId,
                      new CallerIdentity(CALLER_WORKLOAD, context.contextId())))
          .isInstanceOf(
              AccountGameplayDelegationResponseRecoveryOwner.OwnerUnavailableException.class);
      // Unknown physical state must preserve PENDING and never delete somebody else's bytes.
      byte[] poison = "conflicting-physical-registry".getBytes(StandardCharsets.US_ASCII);
      admin.set(ascii(tokenKey), poison);
      assertThatThrownBy(() -> disposalOwner.retire(disposalRequest, CALLER_WORKLOAD))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(poison);
      assertThat(
              dsl.fetchSingle(
                      "SELECT state FROM account_initial_gameplay_login_disposals WHERE request_id = ?",
                      requestId)
                  .get("state", String.class))
          .isEqualTo("PENDING");
      admin.set(ascii(tokenKey), activeBytes);
      // Crash/lost response after physical mutation but before terminal SQL evidence.
      tokenRegistry.disposeInitialLogin(intent);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(intent.tombstoneBytes());
      assertThat(admin.pttl(ascii(tokenKey))).isEqualTo(-1L);
      assertThatThrownBy(
              () ->
                  GameSessionAccountDelegationRegistryRecord.decode(
                      admin.get(ascii(tokenKey)), REGISTRY_BYTES))
          .as(
              "purpose-specific denied tombstone cannot be read as an authorizing registry projection")
          .isInstanceOf(GameSessionAccountDelegationRegistryRecord.InvalidRecordException.class);
      var terminal = disposalOwner.retire(disposalRequest, CALLER_WORKLOAD);
      assertThat(terminal.disposition())
          .isEqualTo(
              net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult
                  .Disposition.RETIRED);
      assertThat(disposalOwner.retire(disposalRequest, CALLER_WORKLOAD)).isEqualTo(terminal);
      assertThat(
              dsl.fetchOne(
                  "SELECT * FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                  requestId))
          .isEqualTo(storedOperation);
      assertThat(
              dsl.fetchOne(
                  "SELECT * FROM account_gameplay_delegation_response_envelopes WHERE request_id = ?",
                  requestId))
          .isEqualTo(storedEnvelope);
      // A captured pre-disposal physical registration cannot resurrect, even after SQL completion.
      assertThatThrownBy(
              () ->
                  redis.registerPending(
                      committed.tokenSha256(), intent.pendingBytes(), originalDeadline))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(intent.tombstoneBytes());
      byte[] activationScript;
      try (var stream =
          getClass()
              .getClassLoader()
              .getResourceAsStream(
                  "redis/lua/account-game-session-delegation-activate-committed.lua")) {
        activationScript = Objects.requireNonNull(stream).readAllBytes();
      }
      try (var activationConnection = writer.connect(ByteArrayCodec.INSTANCE)) {
        RedisCommands<byte[], byte[]> activationWriter = activationConnection.sync();
        assertThat(activationWriter.aclWhoami()).isEqualTo(ACCOUNT_IDENTITY);
        String activationSha = activationWriter.scriptLoad(activationScript);
        Long rejectedActivation =
            activationWriter.evalsha(
                activationSha,
                io.lettuce.core.ScriptOutputType.INTEGER,
                new byte[][] {ascii(tokenKey)},
                intent.pendingBytes(),
                activeBytes,
                ascii(Long.toString(originalDeadline)));
        assertThat(rejectedActivation).isNegative();
      }
      assertThat(admin.get(ascii(tokenKey))).containsExactly(intent.tombstoneBytes());
      // Reset loss/expiry absence is not replay proof; exact replay writes and ACKs the marker
      // again.
      admin.del(ascii(tokenKey));
      assertThat(disposalOwner.retire(disposalRequest, CALLER_WORKLOAD)).isEqualTo(terminal);
      assertThat(admin.get(ascii(tokenKey))).containsExactly(intent.tombstoneBytes());

      UUID absentRequest = UUID.randomUUID();
      var absent =
          new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest(
              absentRequest, UUID.randomUUID(), null);
      var held = disposalOwner.retire(absent, CALLER_WORKLOAD);
      assertThat(held.disposition())
          .isEqualTo(
              net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult
                  .Disposition.PENDING);
      var delayed =
          new CanonicalGameplayLoginRequest(
              absentRequest,
              account.getEmail(),
              secret,
              new GameplayCredentialSourceContext(
                  absent.callerContextId(), "203.0.113.10", "FIRST_PARTY_WEB"));
      assertThatThrownBy(() -> login.authenticate(delayed, CALLER_WORKLOAD, verifier))
          .isInstanceOf(
              AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);
      assertThat(
              dsl.fetchOne(
                  "SELECT request_id FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                  absentRequest))
          .isNull();
    } finally {
      if (adminConnection != null) {
        RedisCommands<byte[], byte[]> admin = adminConnection.sync();
        for (String key : ownedKeys) admin.del(ascii(key));
        adminConnection.close();
      }
      writer.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      fixtureAdmin.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }

  private static AccountServiceImpl primaryCredentialAuthority(
      TestContext database,
      AccountRepository accounts,
      AccountEmailLoginChallengeRepository challenges,
      AccountAuthorityGenerationRepository authorities,
      AccountAuthorityOutboxRepository outbox,
      AccountAuthoritySourceEvidenceRepository sources) {
    // Every collaborator on the primary verification/consumption path is real. Unused surfaces
    // remain absent, as in the existing password-reset authority fixture.
    return new AccountServiceImpl(
        accounts,
        authorities,
        sources,
        outbox,
        new AccountPasswordResetOperationRepository(database.dsl()),
        new AccountLogoutAllOperationRepository(database.dsl()),
        new AccountSecurityStateOperationRepository(database.dsl()),
        null,
        null,
        null,
        null,
        challenges,
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
        null,
        null,
        null,
        null,
        database.manager());
  }

  private static AccountEmailLoginChallenge primaryLoginChallenge(Account account, String hash) {
    LocalDateTime now = LocalDateTime.now();
    var challenge = new AccountEmailLoginChallenge();
    challenge.setAccountId(account.getId());
    challenge.setCodeHash(hash);
    challenge.setExpiresAt(now.plusMinutes(10));
    challenge.setResendAvailableAt(now);
    challenge.setCreatedAt(now);
    challenge.setUpdatedAt(now);
    challenge.setInvalidAttemptCount(0);
    return challenge;
  }

  private static AccountGameplayCredentialRequestDigestKeySource primaryLoginDigestKeys() {
    byte[] material = new byte[32];
    PRIMARY_CREDENTIAL_RANDOM.nextBytes(material);
    var key =
        new CredentialDigestKey(
            "primary-fixture",
            new SecretKeySpec(material, "HmacSHA256"),
            Instant.now().plusSeconds(3600));
    Arrays.fill(material, (byte) 0);
    return new AccountGameplayCredentialRequestDigestKeySource() {
      @Override
      public CredentialDigestKey currentKey() {
        return key;
      }

      @Override
      public CredentialDigestKey requireKey(String id) {
        if (!key.keyId().equals(id)) throw new IllegalStateException("Unknown fixture key");
        return key;
      }
    };
  }

  private static String hashPrimaryCredential(String secret) {
    var argon2 = Argon2Factory.create();
    char[] chars = secret.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  private static RedisClient redisClient(RedisURI uri) {
    RedisClient client = RedisClient.create(uri);
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  private static RedisClient redisClient(String host, int port, String username, String password) {
    RedisURI.Builder uri = RedisURI.Builder.redis(host, port);
    if (username != null) uri.withAuthentication(username, password);
    RedisURI redisUri = uri.build();
    redisUri.setTimeout(Duration.ofSeconds(5));
    return redisClient(redisUri);
  }

  private static RedisURI authenticatedUri(int port, String username, String password) {
    RedisURI uri =
        RedisURI.Builder.redis("127.0.0.1", port).withAuthentication(username, password).build();
    uri.setTimeout(Duration.ofSeconds(5));
    return uri;
  }

  private static void configureAccountUser(RedisCommands<byte[], byte[]> commands) {
    commands.aclSetuser(
        ACCOUNT_IDENTITY,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(ACCOUNT_PASSWORD)
            .keyPattern("session:auth:token:*")
            .keyPattern("session:auth:generation:issuer:*")
            .keyPattern("session:auth:generation:account:*")
            .keyPattern("session:auth:generation:tenant:*")
            .keyPattern("session:auth:generation:membership:*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.GET)
            .addCommand(CommandType.SET)
            .addCommand(CommandType.PEXPIRETIME)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.TYPE));
    commands.dispatch(
        TestAclSetuser.INSTANCE,
        new StatusOutput<>(ByteArrayCodec.INSTANCE),
        new CommandArgs<>(ByteArrayCodec.INSTANCE)
            .add("SETUSER")
            .add(ACCOUNT_IDENTITY)
            .add("+waitaof"));
  }

  private static void configureFixtureAdmin(RedisCommands<byte[], byte[]> commands) {
    commands.aclSetuser(
        FIXTURE_ADMIN_IDENTITY,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(FIXTURE_ADMIN_PASSWORD)
            .keyPattern("*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.GET)
            .addCommand(CommandType.SET)
            .addCommand(CommandType.DEL)
            .addCommand(CommandType.PEXPIRE)
            .addCommand(CommandType.PEXPIREAT)
            .addCommand(CommandType.PEXPIRETIME)
            .addCommand(CommandType.PTTL));
  }

  private static void awaitLocalAndReplicaAof(RedisCommands<byte[], byte[]> commands)
      throws InterruptedException {
    byte[] probeKey = ascii("test-only:account-gameplay-delegation-aof-probe:" + UUID.randomUUID());
    commands.set(probeKey, ascii("test-only-replication-probe"));
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    try {
      while (System.nanoTime() < deadline) {
        List<Object> counts =
            commands.dispatch(
                TestWaitAof.INSTANCE,
                new ArrayOutput<>(ByteArrayCodec.INSTANCE),
                new CommandArgs<>(ByteArrayCodec.INSTANCE).add(1).add(1).add(500));
        if (hasAofCounts(counts, 1, 1)) return;
        Thread.sleep(50);
      }
      throw new IllegalStateException("Test Coordination Redis replica did not acknowledge AOF");
    } finally {
      commands.del(List.of(probeKey).toArray(byte[][]::new));
    }
  }

  private static boolean hasAofCounts(List<Object> counts, long local, long replicas) {
    return counts != null
        && counts.size() == 2
        && counts.get(0) instanceof Long observedLocal
        && observedLocal >= local
        && counts.get(1) instanceof Long observedReplicas
        && observedReplicas >= replicas;
  }

  private enum TestWaitAof implements ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "WAITAOF".getBytes(StandardCharsets.US_ASCII);
    }
  }

  private enum TestAclSetuser implements ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "ACL".getBytes(StandardCharsets.US_ASCII);
    }
  }

  /**
   * Real Account/Redis component composition; external workload and Game Design origins are
   * stipulated.
   */
  /** Pure sealed transport fixtures, deliberately not SQL source/currentness evidence. */
  private static byte[][] selectedProjectionFixture(byte[][] initial, UUID account, UUID tenant) {
    UUID creation = UUID.fromString("72a18e29-57c0-48be-a9e7-bd7b681d7720");
    UUID operation = UUID.fromString("a750fb1f-3e5d-40e6-b51e-160ef64d24ec");
    String digest = "sha256:" + "a".repeat(64);
    var source =
        new net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence(
            1,
            "account-service",
            creation,
            operation,
            digest,
            tenant,
            91L,
            "demo-source",
            "NEW_GAME_ROW",
            net.firedevops.firemud.common.tenant.GameTenantCreationDigest.evidenceDigest(
                "account-service",
                creation,
                operation,
                digest,
                tenant,
                91L,
                "demo-source",
                "NEW_GAME_ROW"));
    var request =
        new DemoTenantEntitlementRequest(
            UUID.fromString("15aad6f4-3c5b-4cd9-b1b9-654e6d9bc51a"),
            tenant,
            creation,
            digest,
            null,
            null,
            null,
            true,
            true,
            true,
            true,
            new DemoTenantEntitlementRequest.Quotas(1L, 1L, 1L));
    var billing = DemoTenantEntitlementEventV1Codec.seal(request, source, 1L, 2L, 2L, 1L);
    var event =
        TenantAuthorityEventV1Codec.seal(
            new TenantAuthorityEventV1Codec.AuthorityRequest(
                request.requestId(),
                request.requestDigest(),
                request.canonicalTenantId(),
                request.tenantCreationRequestId(),
                request.tenantCreationRequestDigest()),
            source,
            2L,
            2L,
            1L,
            new TenantAuthorityEventV1Codec.BillingReceipt(
                billing.canonicalTenantId(),
                billing.requestId(),
                billing.requestDigest(),
                billing.outboxStreamKey(),
                billing.tenantBillingSequence(),
                billing.eventId(),
                billing.eventDigest()));
    return new byte[][] {
      initial[0],
      initial[1],
      selectedProjectionBytes(
          Map.of(
              "schemaVersion", AccountSelectedGameplayAuthorityProjection.TENANT_SCHEMA,
              "tenantId", event.tenantId().toString(),
              "tenantAuthorityGeneration", Long.toString(event.tenantAuthorityGeneration()),
              "sourceVersion", Long.toString(event.tenantAuthoritySourceVersion()),
              "outboxStreamKey", event.outboxStreamKey(),
              "outboxSequence", Long.toString(event.outboxSequence()),
              "sourceEvent", new String(event.payload(), StandardCharsets.UTF_8)),
          null,
          tenant),
      membershipProjectionFixture(account, tenant, "1", "2", "1")
    };
  }

  private static net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec
          .MembershipEvent
      membershipOutboxEventFixture(UUID account, UUID tenant, long sequence) {
    String sequenceText = Long.toString(sequence);
    return net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.seal(
        Map.ofEntries(
            Map.entry(
                "schemaVersion",
                net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec
                    .SCHEMA_VERSION),
            Map.entry(
                "eventType",
                net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec
                    .EVENT_TYPE),
            Map.entry("eventId", "member-event-" + sequenceText),
            Map.entry("requestId", "member-request-" + sequenceText),
            Map.entry(
                "outboxStreamKey",
                "account:auth-authority:v1:membership/" + account + "/" + tenant),
            Map.entry("outboxSequence", sequenceText),
            Map.entry("sourceScope", "membership/" + account + "/" + tenant),
            Map.entry("accountId", account.toString()),
            Map.entry("tenantId", tenant.toString()),
            Map.entry("membershipExists", true),
            Map.entry("membershipLifecycleState", "ACTIVE"),
            Map.entry("membershipVersion", Map.of(tenant.toString(), Long.toString(sequence + 1L))),
            Map.entry("membershipAuthorityGeneration", "1"),
            Map.entry(
                "authorityTuple",
                Map.of(
                    "issuerAuthGeneration",
                    "1",
                    "accountAuthorityGeneration",
                    "1",
                    "tenantAuthorityGeneration",
                    Map.of(tenant.toString(), "2"),
                    "membershipAuthorityGeneration",
                    Map.of(tenant.toString(), "1"),
                    "privateRealmGrantVersions",
                    List.of())),
            Map.entry("issuanceFence", "1"),
            Map.entry("roles", List.of("player")),
            Map.entry("gameplayAdmissionAllowed", true),
            Map.entry("callerBoundAuthorityInvalidated", false)));
  }

  private static byte[] membershipProjectionFromOutboxRow(
      AccountAuthorityOutboxRepository.Event row, UUID account, UUID tenant) {
    var event =
        net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.verify(
            new String(row.payload(), StandardCharsets.UTF_8));
    long sequence = Long.parseLong(event.outboxSequence());
    if (!row.outboxStreamKey().equals(event.outboxStreamKey())
        || row.outboxSequence() != sequence
        || !row.requestId().equals(event.requestId())
        || !row.eventId().equals(event.eventId())
        || !row.eventDigest().equals(event.eventDigest())
        || !account.toString().equals(event.accountId())
        || !tenant.toString().equals(event.tenantId())
        || !Arrays.equals(row.payload(), event.canonicalJsonUtf8())) {
      throw new IllegalStateException("PostgreSQL retained membership row contradicts its event");
    }
    return selectedProjectionBytes(
        Map.of(
            "schemaVersion", AccountSelectedGameplayAuthorityProjection.MEMBERSHIP_SCHEMA,
            "accountId", event.accountId(),
            "tenantId", event.tenantId(),
            "membershipAuthorityGeneration", event.membershipAuthorityGeneration(),
            "membershipVersion", event.membershipVersion().get(event.tenantId()),
            "sourceVersion", event.membershipAuthorityGeneration(),
            "outboxStreamKey", event.outboxStreamKey(),
            "outboxSequence", event.outboxSequence(),
            "sourceEvent", event.canonicalJson()),
        account,
        tenant);
  }

  private static byte[] membershipProjectionFixture(
      UUID account, UUID tenant, String sequence, String version, String generation) {
    var event =
        net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry(
                    "schemaVersion",
                    net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec
                        .SCHEMA_VERSION),
                Map.entry(
                    "eventType",
                    net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec
                        .EVENT_TYPE),
                Map.entry("eventId", "member-event-" + sequence),
                Map.entry("requestId", "member-request-" + sequence),
                Map.entry(
                    "outboxStreamKey",
                    "account:auth-authority:v1:membership/" + account + "/" + tenant),
                Map.entry("outboxSequence", sequence),
                Map.entry("sourceScope", "membership/" + account + "/" + tenant),
                Map.entry("accountId", account.toString()),
                Map.entry("tenantId", tenant.toString()),
                Map.entry("membershipExists", true),
                Map.entry("membershipLifecycleState", "ACTIVE"),
                Map.entry("membershipVersion", Map.of(tenant.toString(), version)),
                Map.entry("membershipAuthorityGeneration", generation),
                Map.entry(
                    "authorityTuple",
                    Map.of(
                        "issuerAuthGeneration",
                        "1",
                        "accountAuthorityGeneration",
                        "1",
                        "tenantAuthorityGeneration",
                        Map.of(tenant.toString(), "2"),
                        "membershipAuthorityGeneration",
                        Map.of(tenant.toString(), generation),
                        "privateRealmGrantVersions",
                        List.of())),
                Map.entry("issuanceFence", "1"),
                Map.entry("roles", List.of("player")),
                Map.entry("gameplayAdmissionAllowed", true),
                Map.entry("callerBoundAuthorityInvalidated", false)));
    return selectedProjectionBytes(
        Map.of(
            "schemaVersion", AccountSelectedGameplayAuthorityProjection.MEMBERSHIP_SCHEMA,
            "accountId", event.accountId(),
            "tenantId", event.tenantId(),
            "membershipAuthorityGeneration", event.membershipAuthorityGeneration(),
            "membershipVersion", event.membershipVersion().get(event.tenantId()),
            "sourceVersion", "1",
            "outboxStreamKey", event.outboxStreamKey(),
            "outboxSequence", event.outboxSequence(),
            "sourceEvent", event.canonicalJson()),
        account,
        tenant);
  }

  private static byte[] tenantProjectionFixture(
      UUID tenant, long generation, long sourceVersion, long outboxSequence) {
    UUID creation = UUID.fromString("72a18e29-57c0-48be-a9e7-bd7b681d7720");
    UUID operation = UUID.fromString("a750fb1f-3e5d-40e6-b51e-160ef64d24ec");
    String digest = "sha256:" + "a".repeat(64);
    var source =
        new net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence(
            1,
            "account-service",
            creation,
            operation,
            digest,
            tenant,
            91L,
            "demo-source",
            "NEW_GAME_ROW",
            net.firedevops.firemud.common.tenant.GameTenantCreationDigest.evidenceDigest(
                "account-service",
                creation,
                operation,
                digest,
                tenant,
                91L,
                "demo-source",
                "NEW_GAME_ROW"));
    UUID requestId =
        UUID.nameUUIDFromBytes(
            ("tenant-authority-request-" + outboxSequence).getBytes(StandardCharsets.UTF_8));
    var request =
        new DemoTenantEntitlementRequest(
            requestId,
            tenant,
            creation,
            digest,
            outboxSequence == 1L ? null : outboxSequence - 1L,
            outboxSequence == 1L ? null : generation - 1L,
            outboxSequence == 1L ? null : sourceVersion - 1L,
            true,
            true,
            true,
            true,
            new DemoTenantEntitlementRequest.Quotas(1L, 1L, 1L));
    var billing =
        DemoTenantEntitlementEventV1Codec.seal(
            request, source, outboxSequence, generation, sourceVersion, outboxSequence);
    var event =
        TenantAuthorityEventV1Codec.seal(
            new TenantAuthorityEventV1Codec.AuthorityRequest(
                request.requestId(),
                request.requestDigest(),
                request.canonicalTenantId(),
                request.tenantCreationRequestId(),
                request.tenantCreationRequestDigest()),
            source,
            generation,
            sourceVersion,
            outboxSequence,
            new TenantAuthorityEventV1Codec.BillingReceipt(
                billing.canonicalTenantId(),
                billing.requestId(),
                billing.requestDigest(),
                billing.outboxStreamKey(),
                billing.tenantBillingSequence(),
                billing.eventId(),
                billing.eventDigest()));
    return selectedProjectionBytes(
        Map.of(
            "schemaVersion", AccountSelectedGameplayAuthorityProjection.TENANT_SCHEMA,
            "tenantId", tenant.toString(),
            "tenantAuthorityGeneration", Long.toString(event.tenantAuthorityGeneration()),
            "sourceVersion", Long.toString(event.tenantAuthoritySourceVersion()),
            "outboxStreamKey", event.outboxStreamKey(),
            "outboxSequence", Long.toString(event.outboxSequence()),
            "sourceEvent", new String(event.payload(), StandardCharsets.UTF_8)),
        null,
        tenant);
  }

  private static byte[] selectedProjectionBytes(
      Map<String, Object> fields, UUID account, UUID tenant) {
    try {
      byte[] canonical =
          net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
              JSON.writeValueAsString(fields));
      return AccountSelectedGameplayAuthorityProjection.decode(canonical, account, tenant)
          .canonicalBytes();
    } catch (Exception malformedFixture) {
      throw new IllegalStateException(
          "Invalid sealed selected projection fixture", malformedFixture);
    }
  }

  private TestContext newPostgresContext() {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    assertThat(schema.length()).isLessThanOrEqualTo(63);
    ownedPostgresSchema = schema;
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
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
    dsl.configuration()
        .setAppending(
            new DefaultExecuteListenerProvider(
                ExecuteListener.onException(
                    context -> {
                      java.sql.SQLException sqlException = context.sqlException();
                      if (sqlException == null) return;
                      String exceptionClass = sqlException.getClass().getSimpleName();
                      if (!exceptionClass.matches("[A-Za-z0-9_$]+")) {
                        exceptionClass = "SQLException";
                      }
                      String sqlState = sqlException.getSQLState();
                      if (sqlState == null || !sqlState.matches("[A-Z0-9]{5}")) {
                        sqlState = "unknown";
                      }
                      String constraint = "unknown";
                      if (sqlException instanceof PSQLException postgresException) {
                        var serverError = postgresException.getServerErrorMessage();
                        String candidateConstraint =
                            serverError == null ? null : serverError.getConstraint();
                        if (candidateConstraint != null
                            && candidateConstraint.matches("[a-z][a-z0-9_]{0,62}")) {
                          constraint = candidateConstraint;
                        }
                      }
                      throw new AssertionError(
                          "Physical Account PostgreSQL failure: exceptionClass="
                              + exceptionClass
                              + "; sqlState="
                              + sqlState
                              + "; constraint="
                              + constraint);
                    })));
    DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
    return new TestContext(dsl, manager, new TransactionTemplate(manager));
  }

  private static UUID createFreshAccount(
      TestContext context, AccountAuthoritySourceEvidenceRepository sources) {
    Account account = new Account();
    account.setUsername("game-session-" + UUID.randomUUID().toString().replace("-", ""));
    account.setEmail("game-session-" + UUID.randomUUID() + "@example.test");
    account.setPasswordHash("integration-test-hash");
    account.setRole("player");
    return inTransaction(
        context,
        () -> {
          sources.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
          return new AccountRepository(context.dsl(), sources).save(account).getAccountUuid();
        });
  }

  private static void commitPasswordResetAuthorityChange(
      TestContext context,
      DSLContext dsl,
      AccountAuthorityGenerationRepository authorities,
      AccountAuthorityOutboxRepository outbox,
      AccountAuthoritySourceEvidenceRepository sources,
      UUID accountId) {
    AccountRepository accounts = new AccountRepository(dsl, sources);
    AccountPasswordResetOperationRepository resetOperations =
        new AccountPasswordResetOperationRepository(dsl);
    AccountLogoutAllOperationRepository logoutAllOperations =
        new AccountLogoutAllOperationRepository(dsl);
    AccountSecurityStateOperationRepository securityStateOperations =
        new AccountSecurityStateOperationRepository(dsl);
    AccountPasswordResetDraftSourceChangeRepository sourceChanges =
        new AccountPasswordResetDraftSourceChangeRepository(dsl);
    PasswordResetTokenRepository resetTokens = new PasswordResetTokenRepository(dsl);
    String rawToken = "active-transition-reset-" + UUID.randomUUID();

    inTransaction(
        context,
        () -> {
          Account account = accounts.findByAccountUuid(accountId).orElseThrow();
          PasswordResetToken token = new PasswordResetToken();
          token.setAccount(account);
          token.setToken(rawToken);
          token.setExpiresAt(LocalDateTime.now().plusHours(2));
          resetTokens.save(token);
          return null;
        });

    AccountServiceImpl accountService =
        new AccountServiceImpl(
            accounts,
            authorities,
            sources,
            outbox,
            resetOperations,
            logoutAllOperations,
            securityStateOperations,
            sourceChanges,
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
            resetTokens,
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
            context.manager());
    inTransaction(
        context,
        () -> {
          accountService.completePasswordReset(
              new CompletePasswordResetRequest(
                  rawToken, "active-transition-password-" + UUID.randomUUID()));
          return null;
        });
  }

  private String writeResponseKeyring() throws Exception {
    Path root = temporaryDirectory.resolve("response-keyring-" + UUID.randomUUID());
    Files.createDirectories(root);
    byte[] key = new byte[32];
    for (int index = 0; index < key.length; index++) key[index] = (byte) (index + 1);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    Files.writeString(
        root.resolve("keyring"),
        "firemud-account-response-envelope-keyring-v1\nactive integration-key " + encoded + "\n",
        StandardCharsets.US_ASCII);
    return root.toString();
  }

  private static <T> T inTransaction(
      TestContext context, java.util.function.Supplier<T> operation) {
    return context.transaction().execute(status -> operation.get());
  }

  private static void assertCurrentOwnerProjection(
      AccountGameplayDelegationAuthorityProjection projection,
      RedisCommands<byte[], byte[]> admin,
      UUID accountId,
      IssuerAccountSourceSnapshot ownerSnapshot) {
    byte[][] expected =
        AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(ownerSnapshot);
    var expectedIssuer =
        AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
            expected[0], "issuer", ACCOUNT_ISSUER);
    var expectedAccount =
        AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
            expected[1], "account", accountId.toString());
    var observation = projection.observeCurrent(accountId);
    assertThat(observation.issuerGeneration()).isEqualTo(ownerSnapshot.issuer().generation());
    assertThat(observation.accountGeneration()).isEqualTo(ownerSnapshot.account().generation());
    assertThat(observation.issuerDigest()).isEqualTo(expectedIssuer.digest());
    assertThat(observation.accountDigest()).isEqualTo(expectedAccount.digest());
    assertThat(
            admin.get(
                ascii(
                    AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
                        + ACCOUNT_ISSUER)))
        .containsExactly(expected[0]);
    assertThat(
            admin.get(
                ascii(AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId)))
        .containsExactly(expected[1]);
  }

  private static AccountGameplayDelegationRedisClient redisClient(
      AccountCoordinationPinnedConnectionProvider provider) {
    return redisClient(provider, new AcknowledgementRequirements(1, 1, 5_000));
  }

  private static AccountGameplayDelegationRedisClient redisClient(
      AccountCoordinationPinnedConnectionProvider provider,
      AcknowledgementRequirements acknowledgements) {
    return new AccountGameplayDelegationRedisClient(
        provider,
        RedisScriptCatalog.loadInstalled(
            AccountGameplayDelegationRedisIntegrationTest.class.getClassLoader()),
        acknowledgements,
        Clock.systemUTC(),
        REGISTRY_BYTES,
        AccountGameplayDelegationRedisIntegrationTest.class.getClassLoader());
  }

  private static RedisURI requireLoopbackUri(String raw, String expectedUsername) {
    final URI parsed;
    try {
      parsed = URI.create(raw);
    } catch (RuntimeException rejected) {
      throw new IllegalArgumentException("Redis integration fixture URI is malformed");
    }
    String userInfo = parsed.getRawUserInfo();
    String username = userInfo == null ? null : userInfo.split(":", 2)[0];
    String path = parsed.getPath();
    if (!"redis".equals(parsed.getScheme())
        || !"127.0.0.1".equals(parsed.getHost())
        || parsed.getPort() <= 0
        || (path != null && !path.isEmpty() && !"/0".equals(path))
        || parsed.getRawQuery() != null
        || parsed.getRawFragment() != null
        || !expectedUsername.equals(username)) {
      throw new IllegalArgumentException(
          "Redis integration fixture must be an exact loopback role URI");
    }
    try {
      RedisURI redisUri = RedisURI.create(raw);
      redisUri.setTimeout(Duration.ofSeconds(5));
      return redisUri;
    } catch (RuntimeException rejected) {
      throw new IllegalArgumentException("Redis integration fixture URI is malformed");
    }
  }

  private static void assertSingleConnectionTrace(
      TrackedProvider provider, int expectedConnectionIndex, String... expectedSequence) {
    assertThat(provider.openedConnections()).isEqualTo(expectedConnectionIndex + 1);
    assertThat(provider.trace(expectedConnectionIndex)).containsSubsequence(expectedSequence);
  }

  private static void assertProjectionBytes(
      RedisCommands<byte[], byte[]> admin, String issuerKey, String accountKey, byte[][] expected) {
    assertThat(admin.get(ascii(issuerKey))).containsExactly(expected[0]);
    assertThat(admin.get(ascii(accountKey))).containsExactly(expected[1]);
  }

  private static byte[][] projectionPair(
      UUID accountId,
      long issuerGeneration,
      long accountGeneration,
      String issuerEventMarker,
      String accountEventMarker) {
    String issuerStream =
        "account:auth-authority:v1:issuer/" + GameSessionAccountDelegationProfile.ISSUER;
    String accountStream = "account:auth-authority:v1:account/" + accountId;
    String event = null;
    if (accountGeneration > 1) {
      String generation = Long.toString(accountGeneration);
      String sequence = Long.toString(accountGeneration - 1);
      UUID markerId = UUID.nameUUIDFromBytes(accountEventMarker.getBytes(StandardCharsets.UTF_8));
      String request =
          new UUID(
                  (markerId.getMostSignificantBits() & ~0xf000L) | 0x4000L,
                  (markerId.getLeastSignificantBits() & 0x3fffffffffffffffL) | 0x8000000000000000L)
              .toString();
      event =
          AccountSecurityStateAuthorityEventV1Codec.seal(
                  Map.ofEntries(
                      Map.entry(
                          "schemaVersion",
                          AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
                      Map.entry("eventType", AccountSecurityStateAuthorityEventV1Codec.EVENT_TYPE),
                      Map.entry(
                          "eventId",
                          AccountSecurityStateAuthorityEventV1Codec.EVENT_ID_PREFIX + request),
                      Map.entry("requestId", request),
                      Map.entry("accountId", accountId.toString()),
                      Map.entry("sourceScope", "account/" + accountId),
                      Map.entry("outboxStreamKey", accountStream),
                      Map.entry("outboxSequence", sequence),
                      Map.entry("accountAuthorityGeneration", generation),
                      Map.entry("sourceVersion", generation),
                      Map.entry(
                          "accountSecurityCutoff",
                          Map.of(
                              "accountAuthorityGeneration",
                              generation,
                              "outboxStreamKey",
                              accountStream,
                              "outboxSequence",
                              sequence)),
                      Map.entry("mutationKinds", List.of("EMAIL_LOGIN_ELIGIBILITY_CHANGED")),
                      Map.entry(
                          "accountState",
                          Map.of(
                              "emailVerified",
                              true,
                              "loginAuthModes",
                              List.of("EMAIL_OTP", "PASSWORD"),
                              "globalRoles",
                              List.of(),
                              "lifecycleState",
                              "ACTIVE"))))
              .canonicalJson();
    }
    Optional<AccountAuthoritySourceEventV1Codec.IssuerEvent> issuerSourceEvent =
        issuerGeneration == 1
            ? Optional.empty()
            : Optional.of(
                AccountAuthoritySourceEventV1Codec.sealIssuer(
                    new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                        "event-" + issuerEventMarker,
                        "event-" + issuerEventMarker,
                        issuerStream,
                        Long.toString(issuerGeneration - 1),
                        GameSessionAccountDelegationProfile.ISSUER,
                        Long.toString(issuerGeneration),
                        Long.toString(issuerGeneration),
                        "ISSUER_SECURITY_CHANGE")));
    var canonicalIssuer =
        new net.firedevops.firemud.accountservice.service.IssuerGenerationProjection(
            GameSessionAccountDelegationProfile.ISSUER,
            Long.toString(issuerGeneration),
            Long.toString(issuerGeneration),
            issuerStream,
            Long.toString(issuerGeneration - 1),
            issuerSourceEvent.map(AccountAuthoritySourceEventV1Codec.IssuerEvent::eventId),
            issuerSourceEvent.map(AccountAuthoritySourceEventV1Codec.IssuerEvent::eventDigest),
            issuerSourceEvent.map(AccountAuthoritySourceEventV1Codec.IssuerEvent::canonicalJson));
    return new byte[][] {
      canonicalIssuer.toJson().getBytes(StandardCharsets.UTF_8),
      new AccountGenerationProjection(
              accountId.toString(),
              Long.toString(accountGeneration),
              Long.toString(accountGeneration),
              accountStream,
              Long.toString(accountGeneration - 1),
              Optional.ofNullable(event))
          .toJson()
          .getBytes(StandardCharsets.UTF_8)
    };
  }

  private static PendingFixture pendingFixture(UUID accountId, UUID tenantId) throws Exception {
    long issuedAt = Instant.now().getEpochSecond();
    long expiresAt = Math.addExact(issuedAt, 180L);
    UUID tokenJti = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    String compactToken = compactToken(accountId, tokenJti, issuedAt, expiresAt, tenantId);
    AccountAuthoritySnapshot snapshot = authoritySnapshot(accountId);
    GameSessionAccountDelegationRegistryRecord record =
        tenantId == null
            ? GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
                compactToken,
                "5",
                new IssuanceBinding(
                    operationId.toString(),
                    requestId.toString(),
                    "a".repeat(64),
                    accountId.toString()),
                snapshot,
                new EvidenceBundleReference("1", "7", "6", "12345678", "d".repeat(64)),
                issuedAt,
                REGISTRY_BYTES)
            : null;
    if (tenantId != null) {
      record =
          GameSessionAccountDelegationRegistryRecord.fromAccountSignedBoundCompactJwt(
              compactToken,
              "5",
              new IssuanceBinding(
                  operationId.toString(),
                  requestId.toString(),
                  "a".repeat(64),
                  accountId.toString()),
              snapshot,
              new EvidenceBundleReference("1", "7", "6", "12345678", "d".repeat(64)),
              tenantId.toString(),
              boundTuple(accountId, tenantId),
              Map.of(tenantId.toString(), "2"),
              issuedAt,
              REGISTRY_BYTES);
    }
    long absoluteExpiryMillis = Math.addExact(Math.multiplyExact(expiresAt, 1_000L), 30_000L);
    return new PendingFixture(
        accountId,
        tokenJti,
        operationId,
        requestId,
        issuedAt,
        expiresAt,
        compactToken,
        record,
        record.toCanonicalJsonBytes(REGISTRY_BYTES),
        absoluteExpiryMillis);
  }

  private static byte[] pendingRecordBytes(
      String compactToken,
      UUID accountId,
      long issuedAt,
      long expiresAt,
      UUID operationId,
      UUID requestId,
      String requestDigest) {
    return GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
            compactToken,
            "5",
            new IssuanceBinding(
                operationId.toString(), requestId.toString(), requestDigest, accountId.toString()),
            authoritySnapshot(accountId),
            new EvidenceBundleReference("1", "7", "6", "12345678", "d".repeat(64)),
            issuedAt,
            REGISTRY_BYTES)
        .toCanonicalJsonBytes(REGISTRY_BYTES);
  }

  private static String compactToken(
      UUID accountId, UUID tokenJti, long issuedAt, long expiresAt, UUID tenantId)
      throws Exception {
    var cutoff = accountCutoff(accountId, 7L);
    Map<String, Object> claims =
        Map.ofEntries(
            Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
            Map.entry("sub", accountId.toString()),
            Map.entry("accountId", accountId.toString()),
            Map.entry("jti", tokenJti.toString()),
            Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
            Map.entry("iat", issuedAt),
            Map.entry("nbf", issuedAt),
            Map.entry("exp", expiresAt),
            Map.entry("tokenGeneration", "1"),
            Map.entry(
                "authorityTuple",
                GameSessionAccountDelegationProfile.authorityTuple(7L, 7L, Optional.of(cutoff))),
            Map.entry("membershipVersion", Map.of()),
            Map.entry("issuanceFence", "7"));
    if (tenantId != null) {
      claims = new java.util.LinkedHashMap<>(claims);
      claims.put("tenantId", tenantId.toString());
      claims.put("authorityTuple", boundTuple(accountId, tenantId));
      claims.put("membershipVersion", Map.of(tenantId.toString(), "2"));
    }
    byte[] header =
        JSON.writeValueAsBytes(Map.of("alg", "RS256", "kid", "fixture-key", "typ", "JWT"));
    byte[] payload = JSON.writeValueAsBytes(claims);
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(header)
        + "."
        + encoder.encodeToString(payload)
        + "."
        + encoder.encodeToString("non-authorizing-test-signature".getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> boundTuple(UUID accountId, UUID tenantId) {
    var tuple =
        new java.util.LinkedHashMap<String, Object>(
            GameSessionAccountDelegationProfile.authorityTuple(
                7L, 7L, Optional.of(accountCutoff(accountId, 7L))));
    tuple.put("tenantAuthorityGeneration", Map.of(tenantId.toString(), "2"));
    tuple.put("membershipAuthorityGeneration", Map.of(tenantId.toString(), "1"));
    return tuple;
  }

  private static AccountAuthoritySnapshot authoritySnapshot(UUID accountId) {
    return new AccountAuthoritySnapshot(
        accountId, 7L, 7L, 7L, 7L, 7L, 7L, Optional.of(accountCutoff(accountId, 7L)));
  }

  private static GameSessionAccountDelegationProfile.AccountSecurityCutoff accountCutoff(
      UUID accountId, long generation) {
    return new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
        Long.toString(generation),
        "account:auth-authority:v1:account/" + accountId,
        Long.toString(generation - 1L));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static boolean containsSequence(byte[] value, byte[] sequence) {
    if (sequence.length == 0 || sequence.length > value.length) return false;
    for (int start = 0; start <= value.length - sequence.length; start++) {
      if (Arrays.equals(Arrays.copyOfRange(value, start, start + sequence.length), sequence)) {
        return true;
      }
    }
    return false;
  }

  private record PendingFixture(
      UUID accountId,
      UUID tokenJti,
      UUID operationId,
      UUID requestId,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond,
      String compactToken,
      GameSessionAccountDelegationRegistryRecord record,
      byte[] recordBytes,
      long absoluteExpiryMillis) {}

  private record TestContext(
      DSLContext dsl, PlatformTransactionManager manager, TransactionTemplate transaction) {}

  private static final class BeforeSecondTransactionManager implements PlatformTransactionManager {
    private final PlatformTransactionManager delegate;
    private final Runnable beforeSecondTransaction;
    private final AtomicInteger transactions = new AtomicInteger();
    private final int pauseTransaction;

    private BeforeSecondTransactionManager(
        PlatformTransactionManager delegate, Runnable beforeSecondTransaction) {
      this(delegate, 2, beforeSecondTransaction);
    }

    private BeforeSecondTransactionManager(
        PlatformTransactionManager delegate,
        int pauseTransaction,
        Runnable beforeSecondTransaction) {
      this.delegate = delegate;
      this.beforeSecondTransaction = beforeSecondTransaction;
      this.pauseTransaction = pauseTransaction;
    }

    @Override
    public org.springframework.transaction.TransactionStatus getTransaction(
        org.springframework.transaction.TransactionDefinition definition) {
      if (transactions.incrementAndGet() == pauseTransaction) beforeSecondTransaction.run();
      return delegate.getTransaction(definition);
    }

    @Override
    public void commit(org.springframework.transaction.TransactionStatus status) {
      delegate.commit(status);
    }

    @Override
    public void rollback(org.springframework.transaction.TransactionStatus status) {
      delegate.rollback(status);
    }
  }

  /**
   * Controlled commit-disposition fixture; actual PG commit/rollback occurs before simulated loss.
   */
  private static final class SigningCommitOutcomeManager implements PlatformTransactionManager {
    private final PlatformTransactionManager delegate;
    private final boolean commitThenLoseResponse;
    private final AtomicInteger commits = new AtomicInteger();

    private SigningCommitOutcomeManager(
        PlatformTransactionManager delegate, boolean commitThenLoseResponse) {
      this.delegate = delegate;
      this.commitThenLoseResponse = commitThenLoseResponse;
    }

    @Override
    public org.springframework.transaction.TransactionStatus getTransaction(
        org.springframework.transaction.TransactionDefinition definition) {
      return delegate.getTransaction(definition);
    }

    @Override
    public void commit(org.springframework.transaction.TransactionStatus status) {
      if (commits.incrementAndGet() == 2) {
        if (commitThenLoseResponse) delegate.commit(status);
        else delegate.rollback(status);
        throw new IllegalStateException("Controlled binding commit acknowledgement loss");
      }
      delegate.commit(status);
    }

    @Override
    public void rollback(org.springframework.transaction.TransactionStatus status) {
      delegate.rollback(status);
    }
  }

  private static final class TrackedProvider
      implements AccountCoordinationPinnedConnectionProvider {
    private final RedisClient client;
    private final List<List<String>> traces = new ArrayList<>();
    private final List<List<String>> dispatchedCommands = new ArrayList<>();

    private TrackedProvider(RedisClient client) {
      this.client = client;
    }

    @Override
    public StatefulRedisConnection<byte[], byte[]> openPinnedConnection() {
      StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
      if (!connection.isOpen()
          || connection.getOptions() == null
          || connection.getOptions().isAutoReconnect()) {
        connection.close();
        throw new IllegalStateException("Test Redis connection is not pinned");
      }
      List<String> trace = new ArrayList<>();
      traces.add(trace);
      List<String> dispatches = new ArrayList<>();
      dispatchedCommands.add(dispatches);
      return observe(connection, trace, dispatches);
    }

    private int openedConnections() {
      return traces.size();
    }

    private List<String> trace(int index) {
      return List.copyOf(traces.get(index));
    }

    private List<String> dispatchedCommands(int index) {
      return List.copyOf(dispatchedCommands.get(index));
    }

    @SuppressWarnings("unchecked")
    private static StatefulRedisConnection<byte[], byte[]> observe(
        StatefulRedisConnection<byte[], byte[]> delegate,
        List<String> trace,
        List<String> dispatches) {
      return (StatefulRedisConnection<byte[], byte[]>)
          Proxy.newProxyInstance(
              StatefulRedisConnection.class.getClassLoader(),
              new Class<?>[] {StatefulRedisConnection.class},
              (proxy, method, arguments) -> {
                if (method.getName().equals("sync") && method.getParameterCount() == 0) {
                  RedisCommands<byte[], byte[]> commands =
                      (RedisCommands<byte[], byte[]>) invoke(delegate, method, arguments);
                  return Proxy.newProxyInstance(
                      RedisCommands.class.getClassLoader(),
                      new Class<?>[] {RedisCommands.class},
                      (commandProxy, commandMethod, commandArguments) -> {
                        String name = commandMethod.getName();
                        if (name.equals("dispatch")
                            && commandArguments != null
                            && commandArguments[0]
                                instanceof io.lettuce.core.protocol.ProtocolKeyword keyword) {
                          dispatches.add(new String(keyword.getBytes(), StandardCharsets.US_ASCII));
                        }
                        if (List.of(
                                "aclWhoami",
                                "scriptLoad",
                                "evalsha",
                                "dispatch",
                                "get",
                                "pexpiretime")
                            .contains(name)) {
                          trace.add(name);
                        }
                        return invoke(commands, commandMethod, commandArguments);
                      });
                }
                if (method.getName().equals("close")) trace.add("close");
                return invoke(delegate, method, arguments);
              });
    }

    private static Object invoke(Object target, Method method, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException ex) {
        throw ex.getCause();
      }
    }
  }
}
