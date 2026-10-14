package integration.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
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
import io.lettuce.core.protocol.ProtocolVersion;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.Disposition;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.ProofKind;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCleanupOwner;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginContextRedisClient;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginContextRedisKeys;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginContextState;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCoordinationQualification;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisAttemptClosure;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.AcknowledgementPolicy;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.Profile;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical standalone AOF primary/replica, scoped ACL and PostgreSQL journal proof. Fresh PENDING
 * records are seeded exclusively by the test administrator: no trusted producer is established.
 * Qualification is stipulated by the controlled external-authority fixture, including exclusion
 * through Account continuation; this is not genuine restore/promotion qualification, Cluster, TLS,
 * deployed custody, authenticated Account transport or LOGIN activation proof. ACK-loss injection
 * executes real WAITAOF, closes the pinned physical connection, then suppresses delivery to the
 * adapter. This is a controlled response-loss boundary, not a network packet-drop test; a separate
 * RESP2 socket relay drops a real WAITAOF response on the wire. The insufficient replica threshold
 * case is reported separately.
 */
@SuppressWarnings("resource")
class InitialGameplayLoginContextRedisIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String ADMIN = "gs_initial_context_proof_admin";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
  private static final String BINDING = "test-owned-gs-coordination-primary";
  private static final int PORT = 6379;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);
  private static final long NOW = CLOCK.instant().getEpochSecond();
  private static final UUID ACCOUNT = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Network NETWORK = Network.newNetwork();
  private static final GenericContainer<?> PRIMARY =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("gs-initial-context-primary")
          .withExposedPorts(PORT)
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
  private static final GenericContainer<?> REPLICA =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(PRIMARY)
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
              "gs-initial-context-primary",
              "6379");
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());
  private static RedisClient bootstrap;
  private static RedisClient writer;
  private static RedisClient administrator;
  private static KeyPair keys;
  private final Set<String> ownedKeys = new LinkedHashSet<>();
  private StatefulRedisConnection<byte[], byte[]> adminConnection;
  private RedisCommands<byte[], byte[]> admin;
  private String schema;

  @BeforeAll
  static void startOwnedPhysicalFixtures() throws Exception {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker unavailable; native GS context proof skipped");
    PRIMARY.start();
    REPLICA.start();
    POSTGRES.start();
    bootstrap = redis(null, null);
    try (var connection = bootstrap.connect(ByteArrayCodec.INSTANCE)) {
      var commands = connection.sync();
      commands.aclSetuser(
          PRINCIPAL,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(PASSWORD)
              .keyPattern(InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE + "*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.TYPE)
              .addCommand(CommandType.PTTL)
              .addCommand(CommandType.PEXPIRETIME));
      commands.dispatch(
          Keyword.ACL,
          new StatusOutput<>(ByteArrayCodec.INSTANCE),
          new CommandArgs<>(ByteArrayCodec.INSTANCE).add("SETUSER").add(PRINCIPAL).add("+waitaof"));
      commands.aclSetuser(
          ADMIN,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(ADMIN_PASSWORD)
              .keyPattern(InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE + "*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.DEL)
              .addCommand(CommandType.PEXPIRE)
              .addCommand(CommandType.PEXPIRETIME));
      byte[] probe = ascii(InitialGameplayLoginContextRedisKeys.contextKey(UUID.randomUUID()));
      commands.set(probe, ascii("owned-aof-probe"));
      try {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean ready = false;
        while (System.nanoTime() < deadline) {
          List<Object> counts =
              commands.dispatch(
                  Keyword.WAITAOF,
                  new ArrayOutput<>(ByteArrayCodec.INSTANCE),
                  new CommandArgs<>(ByteArrayCodec.INSTANCE).add(1).add(1).add(500));
          if (counts.size() == 2
              && counts.get(0) instanceof Long local
              && local >= 1
              && counts.get(1) instanceof Long replicas
              && replicas >= 1) {
            ready = true;
            break;
          }
          Thread.sleep(50);
        }
        assertThat(ready).as("test-owned primary and replica AOF ready").isTrue();
      } finally {
        commands.del(List.of(probe).toArray(byte[][]::new));
      }
    }
    writer = redis(PRINCIPAL, PASSWORD);
    administrator = redis(ADMIN, ADMIN_PASSWORD);
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    keys = generator.generateKeyPair();
  }

  @BeforeEach
  void openAdministrator() {
    adminConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    admin = adminConnection.sync();
    assertThat(admin.aclWhoami()).isEqualTo(ADMIN);
  }

  @AfterEach
  void disposeExactOwnedResources() throws Exception {
    try {
      for (String key : ownedKeys) admin.del(ascii(key));
      if (schema != null) {
        assertThat(schema).matches("gs_context_[0-9a-f]{32}");
        try (var connection =
                DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            var statement = connection.createStatement()) {
          statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
      }
    } finally {
      if (adminConnection != null) adminConnection.close();
    }
  }

  @AfterAll
  static void stopOwnedPhysicalFixtures() {
    for (var client : new RedisClient[] {writer, administrator, bootstrap})
      if (client != null) client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (POSTGRES.isRunning()) POSTGRES.stop();
    if (REPLICA.isRunning()) REPLICA.stop();
    if (PRIMARY.isRunning()) PRIMARY.stop();
    NETWORK.close();
  }

  @Test
  void exactInstallAndRetryUsePhysicalAcknowledgementsIndependentReadbackAndNoSecretOrExpiry()
      throws Exception {
    var original = coordinates();
    seed(original);
    var result = validatedResult(original, UUID.randomUUID());
    var qualification = new ControlledAuthority();
    var provider = new TracedProvider(writer);
    var client = client(provider, qualification, 1);
    var installed = InitialGameplayLoginContextState.installed(original, result);
    assertThat(client.install(original, result).orElseThrow().encode())
        .containsExactly(installed.encode());
    byte[] payload = admin.get(key(original));
    assertThat(payload).containsExactly(installed.encode());
    assertThat(containsSequence(payload, result.compactJwtBytes()))
        .as("compact private token excluded from live payload")
        .isFalse();
    assertThat(containsSequence(payload, ascii("fixture-transient-secret")))
        .as("credential excluded from live payload")
        .isFalse();
    assertThat(admin.pexpiretime(key(original))).isEqualTo(-1L);
    assertThat(client.install(original, result).orElseThrow().encode())
        .containsExactly(installed.encode());
    assertThat(admin.get(key(original))).containsExactly(payload);
    assertThat(provider.traces).hasSize(4);
    assertThat(provider.traces.get(0))
        .containsSubsequence("aclWhoami", "evalsha", "WAITAOF", "close");
    assertThat(provider.traces.get(1))
        .containsSubsequence("aclWhoami", "get", "close")
        .doesNotContain("evalsha", "WAITAOF");
    assertThat(qualification.active).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "malformed",
        "mismatch",
        "absent",
        "newer-attempt",
        "closed",
        "expiry",
        "different-installed-evidence"
      })
  void compareOnlyInstallLeavesMalformedAbsentReplacedClosedAndNewerOriginalsUntouched(
      String condition) throws Exception {
    var original = coordinates();
    own(original);
    byte[] before = null;
    if (condition.equals("malformed")) before = ascii("{bad-record}");
    else if (condition.equals("mismatch"))
      before =
          InitialGameplayLoginContextState.pending(
                  new InitialGameplayLoginAttemptCoordinates(
                      UUID.randomUUID(), original.callerContextId(), 7))
              .encode();
    else if (condition.equals("newer-attempt"))
      before =
          InitialGameplayLoginContextState.pending(
                  new InitialGameplayLoginAttemptCoordinates(
                      original.requestId(), original.callerContextId(), 8))
              .encode();
    else if (condition.equals("closed"))
      before = InitialGameplayLoginContextState.closed(original).encode();
    else if (condition.equals("expiry"))
      before = InitialGameplayLoginContextState.pending(original).encode();
    else if (condition.equals("different-installed-evidence"))
      before =
          InitialGameplayLoginContextState.installed(
                  original, validatedResult(original, UUID.randomUUID()))
              .encode();
    if (before != null) admin.set(key(original), before);
    if (condition.equals("expiry")) admin.pexpire(key(original), 60_000);
    assertThat(
            client(new TracedProvider(writer), new ControlledAuthority(), 1)
                .install(original, validatedResult(original, UUID.randomUUID())))
        .isEmpty();
    if (before == null) assertThat(admin.get(key(original))).isNull();
    else assertThat(admin.get(key(original))).containsExactly(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"unavailable", "scope", "binding", "fence", "withdrawn", "wrong-identity"})
  void qualificationAndWrongAclIdentityDenyBeforeMutation(String condition) throws Exception {
    var original = coordinates();
    seed(original);
    var authority = new ControlledAuthority();
    authority.condition = condition;
    var provider = new TracedProvider(condition.equals("wrong-identity") ? administrator : writer);
    var result = validatedResult(original, UUID.randomUUID());
    assertThat(client(provider, authority, 1).install(original, result)).isEmpty();
    assertThat(admin.get(key(original)))
        .containsExactly(InitialGameplayLoginContextState.pending(original).encode());
    assertThat(provider.traces.stream().flatMap(List::stream).toList())
        .doesNotContain("evalsha", "WAITAOF");
  }

  @ParameterizedTest
  @ValueSource(strings = {"lost-ack", "insufficient-replicas", "readback", "withdraw-after-ack"})
  void physicalUncertainClosureHoldsJournalAndNeverDispatchesAccount(String fault)
      throws Exception {
    var original = coordinates();
    seed(original);
    var authority = new ControlledAuthority();
    var provider = new TracedProvider(writer);
    provider.fault = fault;
    provider.afterAck =
        () -> {
          if (fault.equals("withdraw-after-ack")) authority.current = false;
        };
    var account = mock(CanonicalGameplayLoginAccountClient.class);
    var owner =
        cleanup(
            client(provider, authority, fault.equals("insufficient-replicas") ? 2 : 1), account);
    owner.reserve(original);
    var observation = owner.abandonAndRetire(original);
    assertThat(observation.status()).isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
    verify(account, never()).retireInitialGameplayLogin(any());
    // Actual EVALSHA has executed even though no qualified completion reached the owner.
    assertThat(admin.get(key(original)))
        .containsExactly(InitialGameplayLoginContextState.closed(original).encode());
    assertThat(provider.traces.stream().flatMap(List::stream).toList())
        .contains("evalsha", "WAITAOF");
    assertThat(authority.active).isFalse();
  }

  @Test
  void physicallyDroppedWaitAofNetworkResponseHoldsCleanupWithoutAccountDispatch()
      throws Exception {
    var original = coordinates();
    seed(original);
    try (var wire = new AckResponseDropper(PRIMARY.getMappedPort(PORT))) {
      var uri =
          RedisURI.Builder.redis("127.0.0.1", wire.port())
              .withAuthentication(PRINCIPAL, PASSWORD)
              .build();
      uri.setTimeout(Duration.ofSeconds(2));
      var interruptedClient = RedisClient.create(uri);
      interruptedClient.setOptions(
          ClientOptions.builder()
              .autoReconnect(false)
              .protocolVersion(ProtocolVersion.RESP2)
              .build());
      try {
        var account = mock(CanonicalGameplayLoginAccountClient.class);
        var authority = new ControlledAuthority();
        var owner = cleanup(client(new TracedProvider(interruptedClient), authority, 1), account);
        owner.reserve(original);
        assertThat(owner.abandonAndRetire(original).status())
            .isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
        assertThat(wire.dropped.await(5, TimeUnit.SECONDS))
            .as("real WAITAOF response dropped on physical loopback socket")
            .isTrue();
        assertThat(wire.acknowledgedBeforeDrop.get())
            .as("actual local and replica AOF response was sufficient before network loss")
            .isTrue();
        verify(account, never()).retireInitialGameplayLogin(any());
        assertThat(admin.get(key(original)))
            .containsExactly(InitialGameplayLoginContextState.closed(original).encode());
        assertThat(authority.active).isFalse();
      } finally {
        interruptedClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      }
    }
  }

  @Test
  void delayedPhysicalInstallCannotRehydrateOriginalAfterAcknowledgedClosure() throws Exception {
    var original = coordinates();
    seed(original);
    var delayed = new TracedProvider(writer);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    delayed.beforeEval =
        () -> {
          entered.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("Controlled EVALSHA gate timed out");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Controlled EVALSHA gate interrupted");
          }
        };
    var result = validatedResult(original, UUID.randomUUID());
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pending =
          executor.submit(
              () -> client(delayed, new ControlledAuthority(), 1).install(original, result));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        var closure =
            new InitialGameplayLoginRedisAttemptClosure(
                client(new TracedProvider(writer), new ControlledAuthority(), 1));
        assertThat(closure.withAcknowledgedClosure(original, receipt -> receipt.coordinates()))
            .contains(original);
      } finally {
        release.countDown();
      }
      assertThat(pending.get(10, TimeUnit.SECONDS)).isEmpty();
      assertThat(admin.get(key(original)))
          .containsExactly(InitialGameplayLoginContextState.closed(original).encode());
      assertThat(delayed.traces.stream().flatMap(List::stream).toList()).contains("evalsha");
    }
  }

  @Test
  void actualJournalCleanupRetiresOnlyInsideExactCurrentExclusionAndOldReceiptCannotQualifyRetry()
      throws Exception {
    var original = coordinates();
    seed(original);
    var unrelated = coordinates();
    seed(unrelated);
    byte[] untouched = admin.get(key(unrelated));
    var authority = new ControlledAuthority();
    var provider = new TracedProvider(writer);
    var account = mock(CanonicalGameplayLoginAccountClient.class);
    var receipt =
        new InitialGameplayLoginDisposalResult(
            original.requestId(),
            original.callerContextId(),
            UUID.randomUUID(),
            Disposition.RETIRED,
            ProofKind.PHYSICAL_TOKEN_DENIAL);
    when(account.retireInitialGameplayLogin(any()))
        .thenAnswer(
            invocation -> {
              assertThat(authority.active).isTrue();
              assertThat(authority.current).isTrue();
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(admin.get(key(original)))
                  .containsExactly(InitialGameplayLoginContextState.closed(original).encode());
              assertThat(provider.traces).hasSize(2);
              assertThat(provider.traces.get(0)).contains("WAITAOF");
              assertThat(provider.traces.get(1)).contains("get");
              assertThat(
                      invocation
                          .getArgument(
                              0,
                              net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest
                                  .class)
                          .getCallerContextId())
                  .isEqualTo(original.callerContextId().toString());
              return receipt;
            });
    var owner = cleanup(client(provider, authority, 1), account);
    owner.reserve(original);
    assertThat(owner.abandonAndRetire(original).status())
        .isEqualTo(InitialGameplayLoginCleanupOwner.Status.ACCOUNT_RETIRED);
    assertThat(authority.active).isFalse();
    authority.condition = "unavailable";
    var retry = owner.abandonAndRetire(original);
    assertThat(retry.status()).isEqualTo(InitialGameplayLoginCleanupOwner.Status.HELD);
    assertThat(retry.priorRetainedReceipt()).contains(receipt);
    verify(account).retireInitialGameplayLogin(any());
    assertThat(admin.get(key(unrelated))).containsExactly(untouched);
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "replaced", "newer-attempt"})
  void originalClosureCannotCreateAbsentOrTouchReplacementAndNewContext(String condition) {
    var original = coordinates();
    own(original);
    byte[] before = null;
    if (!condition.equals("absent")) {
      var replacement =
          new InitialGameplayLoginAttemptCoordinates(
              UUID.randomUUID(),
              original.callerContextId(),
              condition.equals("newer-attempt") ? 8 : 7);
      before = InitialGameplayLoginContextState.pending(replacement).encode();
      admin.set(key(original), before);
    }
    var newContext = coordinates();
    seed(newContext);
    var called = new AtomicBoolean();
    var closure =
        new InitialGameplayLoginRedisAttemptClosure(
            client(new TracedProvider(writer), new ControlledAuthority(), 1));
    assertThat(
            closure.withAcknowledgedClosure(
                original,
                ignored -> {
                  called.set(true);
                  return true;
                }))
        .isEmpty();
    assertThat(called).isFalse();
    if (before == null) assertThat(admin.get(key(original))).isNull();
    else assertThat(admin.get(key(original))).containsExactly(before);
    assertThat(admin.get(key(newContext)))
        .containsExactly(InitialGameplayLoginContextState.pending(newContext).encode());
  }

  private InitialGameplayLoginCleanupOwner cleanup(
      InitialGameplayLoginContextRedisClient client, CanonicalGameplayLoginAccountClient account) {
    schema = "gs_context_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();
    var journal =
        new InitialGameplayLoginCoordinationJournalRepository(
            DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES));
    return new InitialGameplayLoginCleanupOwner(
        journal,
        new InitialGameplayLoginRedisAttemptClosure(client),
        account,
        new DataSourceTransactionManager(dataSource));
  }

  private static InitialGameplayLoginContextRedisClient client(
      TracedProvider provider, ControlledAuthority authority, int replicas) {
    return new InitialGameplayLoginContextRedisClient(
        new InitialGameplayLoginRedisBinding(
            BINDING, provider, new AcknowledgementPolicy(Profile.REPLICATED, 1, replicas, 500)),
        RedisScriptCatalog.loadInstalled(
            InitialGameplayLoginContextRedisIntegrationTest.class.getClassLoader()),
        authority);
  }

  private static RedisClient redis(String identity, String password) {
    var builder = RedisURI.Builder.redis("127.0.0.1", PRIMARY.getMappedPort(PORT));
    if (identity != null) builder.withAuthentication(identity, password);
    var uri = builder.build();
    uri.setTimeout(Duration.ofSeconds(5));
    var client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .protocolVersion(ProtocolVersion.RESP2)
            .build());
    return client;
  }

  private static InitialGameplayLoginAttemptCoordinates coordinates() {
    return new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 7);
  }

  private void own(InitialGameplayLoginAttemptCoordinates original) {
    String key = InitialGameplayLoginContextRedisKeys.contextKey(original.callerContextId());
    assertThat(admin.get(ascii(key))).isNull();
    ownedKeys.add(key);
  }

  private void seed(InitialGameplayLoginAttemptCoordinates original) {
    own(original);
    admin.set(key(original), InitialGameplayLoginContextState.pending(original).encode());
  }

  private static byte[] key(InitialGameplayLoginAttemptCoordinates original) {
    return ascii(InitialGameplayLoginContextRedisKeys.contextKey(original.callerContextId()));
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private enum Keyword implements ProtocolKeyword {
    ACL,
    WAITAOF;

    @Override
    public byte[] getBytes() {
      return name().getBytes(StandardCharsets.US_ASCII);
    }
  }

  private static boolean containsSequence(byte[] value, byte[] sequence) {
    for (int i = 0; i + sequence.length <= value.length; i++) {
      if (Arrays.equals(Arrays.copyOfRange(value, i, i + sequence.length), sequence)) return true;
    }
    return false;
  }

  /** Test-only RESP2 loopback relay drops the actual first WAITAOF response, never its request. */
  private static final class AckResponseDropper implements AutoCloseable {
    private final ServerSocket listener;
    private final int upstreamPort;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean dropNext = new AtomicBoolean(true);
    private final CountDownLatch dropped = new CountDownLatch(1);
    private final AtomicBoolean acknowledgedBeforeDrop = new AtomicBoolean();

    private AckResponseDropper(int upstreamPort) throws IOException {
      this.upstreamPort = upstreamPort;
      listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
      workers.submit(
          () -> {
            while (!stopping.get()) {
              try {
                var downstream = listener.accept();
                sockets.add(downstream);
                workers.submit(() -> relay(downstream));
              } catch (IOException disconnected) {
                if (!stopping.get())
                  throw new IllegalStateException("Controlled loopback relay unavailable");
              }
            }
          });
    }

    private int port() {
      return listener.getLocalPort();
    }

    private void relay(Socket downstream) {
      try (downstream;
          var upstream = new Socket("127.0.0.1", upstreamPort)) {
        sockets.add(upstream);
        downstream.setSoTimeout(5_000);
        upstream.setSoTimeout(5_000);
        while (!stopping.get()) {
          byte[] request = frame(downstream.getInputStream());
          upstream.getOutputStream().write(request);
          upstream.getOutputStream().flush();
          byte[] response = frame(upstream.getInputStream());
          boolean waitAof =
              new String(request, StandardCharsets.US_ASCII).startsWith("*4\r\n$7\r\nWAITAOF\r\n");
          if (waitAof && dropNext.compareAndSet(true, false)) {
            // Read the genuine response first, then sever both sockets without delivering bytes.
            acknowledgedBeforeDrop.set(
                new String(response, StandardCharsets.US_ASCII).equals("*2\r\n:1\r\n:1\r\n"));
            dropped.countDown();
            return;
          }
          downstream.getOutputStream().write(response);
          downstream.getOutputStream().flush();
        }
      } catch (IOException disconnected) {
        // Connection interruption is the controlled boundary; never print AUTH or token frames.
      } finally {
        sockets.remove(downstream);
      }
    }

    private static byte[] frame(InputStream input) throws IOException {
      var bytes = new ByteArrayOutputStream();
      readFrame(input, bytes);
      return bytes.toByteArray();
    }

    private static void readFrame(InputStream input, ByteArrayOutputStream bytes)
        throws IOException {
      int kind = input.read();
      if (kind < 0) throw new EOFException();
      bytes.write(kind);
      String line = line(input, bytes);
      if (kind == '$') {
        int length = Integer.parseInt(line);
        if (length < -1 || length > 64 * 1024) throw new IOException("Bounded RESP bulk rejected");
        if (length >= 0) {
          byte[] body = input.readNBytes(length + 2);
          if (body.length != length + 2) throw new EOFException();
          bytes.write(body);
        }
      } else if (kind == '*') {
        int length = Integer.parseInt(line);
        if (length < -1 || length > 128) throw new IOException("Bounded RESP array rejected");
        for (int i = 0; i < length; i++) readFrame(input, bytes);
      } else if (kind != '+' && kind != '-' && kind != ':') {
        throw new IOException("Unsupported RESP2 frame");
      }
    }

    private static String line(InputStream input, ByteArrayOutputStream bytes) throws IOException {
      var line = new ByteArrayOutputStream();
      while (line.size() < 1024) {
        int value = input.read();
        if (value < 0) throw new EOFException();
        bytes.write(value);
        if (value == '\r') {
          int newline = input.read();
          if (newline != '\n') throw new IOException("Invalid RESP delimiter");
          bytes.write(newline);
          return line.toString(StandardCharsets.US_ASCII);
        }
        line.write(value);
      }
      throw new IOException("Bounded RESP line rejected");
    }

    @Override
    public void close() throws IOException {
      stopping.set(true);
      listener.close();
      for (Socket socket : sockets) socket.close();
      workers.shutdownNow();
      try {
        if (!workers.awaitTermination(5, TimeUnit.SECONDS))
          throw new IOException("Controlled relay did not stop");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("Controlled relay shutdown interrupted");
      }
    }
  }

  /** Stipulates external authority; never presented as a production qualification provider. */
  private static final class ControlledAuthority
      implements InitialGameplayLoginCoordinationQualification {
    private String condition = "valid";
    private boolean current = true;
    private boolean active;

    @Override
    public Optional<Lease> acquire(String scope, String binding) {
      assertThat(scope).isEqualTo(InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE);
      assertThat(binding).isEqualTo(BINDING);
      if (condition.equals("unavailable")) return Optional.empty();
      active = true;
      return Optional.of(
          new Lease() {
            @Override
            public String scope() {
              return condition.equals("scope") ? "session:game:wrong:" : scope;
            }

            @Override
            public String protectedBindingId() {
              return condition.equals("binding") ? "other-binding" : binding;
            }

            @Override
            public long fence() {
              return condition.equals("fence") ? 0 : 37;
            }

            @Override
            public void requireCurrent() {
              if (!active || !current || condition.equals("withdrawn"))
                throw new IllegalStateException("Controlled external qualification unavailable");
            }

            @Override
            public void close() {
              active = false;
            }
          });
    }
  }

  /** Private scoped observation/fault gates; every successful command delegates to real Redis. */
  private static final class TracedProvider
      implements Supplier<StatefulRedisConnection<byte[], byte[]>> {
    private final RedisClient client;
    private final List<List<String>> traces = new ArrayList<>();
    private String fault = "none";
    private Runnable beforeEval = () -> {};
    private Runnable afterAck = () -> {};
    private final AtomicInteger opened = new AtomicInteger();

    private TracedProvider(RedisClient client) {
      this.client = client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StatefulRedisConnection<byte[], byte[]> get() {
      var connection = client.connect(ByteArrayCodec.INSTANCE);
      assertThat(connection.getOptions().isAutoReconnect()).isFalse();
      int index = opened.getAndIncrement();
      var trace = new ArrayList<String>();
      traces.add(trace);
      return (StatefulRedisConnection<byte[], byte[]>)
          Proxy.newProxyInstance(
              StatefulRedisConnection.class.getClassLoader(),
              new Class<?>[] {StatefulRedisConnection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("close")) trace.add("close");
                if (!method.getName().equals("sync")) return invoke(connection, method, args);
                var commands = connection.sync();
                return Proxy.newProxyInstance(
                    RedisCommands.class.getClassLoader(),
                    new Class<?>[] {RedisCommands.class},
                    (commandProxy, command, arguments) -> {
                      String name = command.getName();
                      trace.add(name);
                      if (name.equals("evalsha")) beforeEval.run();
                      if (name.equals("get") && index == 1 && fault.equals("readback")) {
                        connection.close();
                        throw new IllegalStateException(
                            "Controlled independent readback connection interrupted");
                      }
                      Object answer = invoke(commands, command, arguments);
                      if (name.equals("dispatch")
                          && arguments != null
                          && arguments[0] instanceof ProtocolKeyword keyword
                          && new String(keyword.getBytes(), StandardCharsets.US_ASCII)
                              .equals("WAITAOF")) {
                        trace.add("WAITAOF");
                        afterAck.run();
                        if (fault.equals("lost-ack")) {
                          connection.close();
                          throw new IllegalStateException(
                              "Controlled physical acknowledgement response delivery lost");
                        }
                      }
                      return answer;
                    });
              });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }
  }

  private static CanonicalGameplayLoginResult validatedResult(
      InitialGameplayLoginAttemptCoordinates original, UUID jti) throws Exception {
    var claims =
        new LinkedHashMap<String, Object>(
            Map.ofEntries(
                Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
                Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
                Map.entry("sub", ACCOUNT.toString()),
                Map.entry("accountId", ACCOUNT.toString()),
                Map.entry("jti", jti.toString()),
                Map.entry("iat", NOW),
                Map.entry("nbf", NOW),
                Map.entry("exp", NOW + 120),
                Map.entry("tokenGeneration", "1"),
                Map.entry("issuanceFence", "1"),
                Map.entry("membershipVersion", Map.of()),
                Map.entry(
                    "authorityTuple", GameSessionAccountDelegationProfile.authorityTuple(1, 1))));
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input =
        encoder.encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"test-key\",\"typ\":\"JWT\"}"
                    .getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(JSON.writeValueAsBytes(claims));
    var signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keys.getPrivate());
    signature.update(input.getBytes(StandardCharsets.US_ASCII));
    String token = input + "." + encoder.encodeToString(signature.sign());
    var request =
        AuthenticateRequest.newBuilder()
            .setRequestId(original.requestId().toString())
            .setEmail("player@example.test")
            .setPassword("fixture-transient-secret")
            .setSourceContext(
                GameplayLoginSourceContext.newBuilder()
                    .setCallerContextId(original.callerContextId().toString())
                    .setCanonicalClientAddress("203.0.113.10")
                    .setTransportClass(
                        GameplayLoginTransportClass
                            .GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
            .build();
    var response =
        AuthenticateResponse.newBuilder()
            .setAuthToken(token)
            .setAccountId(ACCOUNT.toString())
            .setRequestId(original.requestId().toString())
            .setCallerContextId(original.callerContextId().toString())
            .setTokenJti(jti.toString())
            .setTokenSha256(
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.US_ASCII))))
            .setTokenProfile(GameSessionAccountDelegationProfile.PROFILE)
            .setIssuedAtEpochSeconds(NOW)
            .setNotBeforeEpochSeconds(NOW)
            .setExpiresAtEpochSeconds(NOW + 120)
            .setTokenGeneration("1")
            .setIssuanceFence("1")
            .setAuthorityTupleCanonicalJson(canonical(claims.get("authorityTuple")))
            .setOutboxCheckpointsCanonicalJson(
                canonical(
                    List.of(
                        Map.of(
                            "outboxStreamKey",
                            "account:auth-authority:v1:account/" + ACCOUNT,
                            "outboxSequence",
                            "0"),
                        Map.of(
                            "outboxStreamKey",
                            "account:auth-authority:v1:issuer/"
                                + GameSessionAccountDelegationProfile.ISSUER,
                            "outboxSequence",
                            "0"))))
            .setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of()))
            .build();
    return CanonicalGameplayLoginResult.fromAuthenticatedResponse(
        response, request, verifier(), CLOCK);
  }

  private static ByteString canonical(Object value) throws Exception {
    return ByteString.copyFrom(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)));
  }

  private static AccountAsymmetricJwtVerifier verifier() throws Exception {
    var pin =
        new AccountPublicJwksCache.SourceIdentity(
            "test",
            "cluster-a",
            "11111111-1111-4111-8111-111111111111",
            "test",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "binding-7",
            "https://kubernetes.example:6443",
            "a".repeat(64));
    var key = (RSAPublicKey) keys.getPublic();
    String jwks =
        JSON.writeValueAsString(
            Map.of(
                "keys",
                List.of(
                    Map.of(
                        "kty",
                        "RSA",
                        "use",
                        "sig",
                        "alg",
                        "RS256",
                        "kid",
                        "test-key",
                        "n",
                        unsigned(key.getModulus().toByteArray()),
                        "e",
                        unsigned(key.getPublicExponent().toByteArray())))));
    var cache =
        new AccountPublicJwksCache(
            () ->
                new AccountPublicJwksCache.PublicJwksSnapshot(
                    pin, jwks.getBytes(StandardCharsets.UTF_8)),
            pin,
            CLOCK,
            Duration.ofSeconds(30));
    return new AccountAsymmetricJwtVerifier(cache, CLOCK);
  }

  private static String unsigned(byte[] bytes) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
  }
}
