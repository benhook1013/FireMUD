package integration.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthEvidenceBundleRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingIntent;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommitSignerFixture;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.PendingRegistrationOutcome;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseEnvelopeService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedGameplayAuthorityProjection;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
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
  private static boolean ownsRedisFixture;
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
      byte[][] gap = selected.clone();
      gap[3] = membershipProjectionFixture(accountId, selectedTenant, "3", "4", "1");
      assertThatThrownBy(
              () -> redis.publishSelectedAuthorityProjections(accountId, selectedTenant, gap))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      assertThat(admin.get(ascii(memberKey))).containsExactly(selected[3]);
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
          new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles);
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
          new AccountGameplayDelegationAuthorityProjection(sources, database.manager(), redis);
      AccountGameplayDelegationTokenRegistry tokenRegistry =
          new AccountGameplayDelegationTokenRegistry(
              AccountGameplayDelegationCommitSignerFixture.transactionalPendingRegistryReads(
                  issuance, database.manager()),
              redis,
              Clock.systemUTC(),
              REGISTRY_BYTES,
              30_000L);
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

      publishAndAssertCurrentOwnerProjection(projection, admin, accountId, sourceSnapshot);

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
                  inTransaction(
                      database,
                      () -> {
                        AccountRepository accounts = new AccountRepository(dsl, sources);
                        Account changed = accounts.findByAccountUuid(accountId).orElseThrow();
                        changed.setEmailVerified(true);
                        accounts.save(changed);
                        return null;
                      }));
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
      assertThatThrownBy(
              () ->
                  inTransaction(
                      database,
                      () -> sources.readCurrentIssuerAccountSources(ACCOUNT_ISSUER, accountId)))
          .isInstanceOf(
              AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class)
          .hasNoCause();
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
          new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles);
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
          new AccountGameplayDelegationAuthorityProjection(sources, database.manager(), redis);
      AccountGameplayDelegationTokenRegistry tokenRegistry =
          new AccountGameplayDelegationTokenRegistry(
              AccountGameplayDelegationCommitSignerFixture.transactionalPendingRegistryReads(
                  issuance, database.manager()),
              redis,
              Clock.systemUTC(),
              REGISTRY_BYTES,
              30_000L);
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

      publishAndAssertCurrentOwnerProjection(projection, admin, accountId, sourceSnapshot);

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
    var event = TenantAuthorityEventV1Codec.seal(request, source, 2L, 2L, 1L, billing);
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

  private static void publishAndAssertCurrentOwnerProjection(
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

    var publication = projection.publishCurrent(accountId);
    assertThat(publication.redisOutcome())
        .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
    assertThat(publication.issuerGeneration()).isEqualTo(ownerSnapshot.issuer().generation());
    assertThat(publication.accountGeneration()).isEqualTo(ownerSnapshot.account().generation());
    assertThat(publication.issuerDigest()).isEqualTo(expectedIssuer.digest());
    assertThat(publication.accountDigest()).isEqualTo(expectedAccount.digest());

    var readback = projection.observeCurrent(accountId);
    assertThat(readback.issuerGeneration()).isEqualTo(publication.issuerGeneration());
    assertThat(readback.accountGeneration()).isEqualTo(publication.accountGeneration());
    assertThat(readback.issuerDigest()).isEqualTo(publication.issuerDigest());
    assertThat(readback.accountDigest()).isEqualTo(publication.accountDigest());
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

    private BeforeSecondTransactionManager(
        PlatformTransactionManager delegate, Runnable beforeSecondTransaction) {
      this.delegate = delegate;
      this.beforeSecondTransaction = beforeSecondTransaction;
    }

    @Override
    public org.springframework.transaction.TransactionStatus getTransaction(
        org.springframework.transaction.TransactionDefinition definition) {
      if (transactions.incrementAndGet() == 2) beforeSecondTransaction.run();
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
