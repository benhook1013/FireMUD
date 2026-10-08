package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AcknowledgementRequirements;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Physical Coordination Redis proof for Account's pending token-registry CAS only. Inputs and any
 * directly seeded active read fixture are synthetic and non-authorizing; this does not prove
 * Account issuance, committed signer provenance, or the Account-owned activation transition.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountControlUiRegistryRedisIntegrationTest {
  private static final int REDIS_PORT = 6379;
  private static final String ACCOUNT_USER = "account_coord_app";
  private static final String ACCOUNT_PASSWORD = UUID.randomUUID().toString().replace("-", "");
  private static final String WRONG_USER = "account_coord_wrong";
  private static final String WRONG_PASSWORD = UUID.randomUUID().toString().replace("-", "");
  private static final Network REDIS_NETWORK = Network.newNetwork();

  @Container
  static final GenericContainer<?> primary =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(REDIS_NETWORK)
          .withNetworkAliases("control-ui-coordination-primary")
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

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(REDIS_NETWORK)
          .dependsOn(primary)
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
              "control-ui-coordination-primary",
              Integer.toString(REDIS_PORT));

  private static final RedisScriptCatalog TEST_CATALOG = testCatalog();
  private static RedisClient adminClient;

  @BeforeAll
  static void configureIsolatedRedis() throws InterruptedException {
    adminClient = client(null, null);
    try (var connection = adminClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisCommands<byte[], byte[]> commands = connection.sync();
      configureApplicationUser(commands, ACCOUNT_USER, ACCOUNT_PASSWORD, true);
      configureApplicationUser(commands, WRONG_USER, WRONG_PASSWORD, false);
      awaitLocalAndReplicaAof(commands);
    }
  }

  @AfterAll
  static void stopClients() {
    if (adminClient != null) {
      adminClient.shutdown();
    }
  }

  @Test
  void pendingRegistrationRequiresRealAofAckExactRetryAndImmutableCas() {
    String tokenHash = tokenHash();
    long expiryMillis = (Instant.now().getEpochSecond() + 300) * 1000L;
    byte[] pending = syntheticRecord(tokenHash, "pending", expiryMillis / 1000, "request-a");
    byte[] key = key(tokenHash);

    try (var pinned = new PinnedAccountConnection(ACCOUNT_USER, ACCOUNT_PASSWORD)) {
      AccountControlUiCoordination underDurabilityThreshold =
          coordination(pinned, new AcknowledgementRequirements(1, 2, 1000));
      assertThatThrownBy(
              () -> underDurabilityThreshold.registerPending(tokenHash, pending, expiryMillis))
          .isInstanceOf(IllegalStateException.class);
      // The Redis write occurred, but the under-threshold WAITAOF result created no owner receipt.
      assertThat(adminGet(key)).isEqualTo(pending);

      AccountControlUiCoordination owner =
          coordination(pinned, new AcknowledgementRequirements(1, 1, 1000));
      var created = owner.registerPending(tokenHash, pending, expiryMillis);
      assertThat(created.tokenHash).isEqualTo(tokenHash);
      assertThat(created.recordDigest).isEqualTo(AccountControlUiIssuanceRepository.hash(pending));
      assertThat(created.expiryMillis).isEqualTo(expiryMillis);
      assertThat(created.localAof).isEqualTo(1);
      assertThat(created.replicas).isEqualTo(1);
      assertThat(adminGet(key)).isEqualTo(pending);

      // Exact retry reasserts the bytes, obtains a fresh same-connection WAITAOF, and reads back.
      var retried = owner.registerPending(tokenHash, pending, expiryMillis);
      assertThat(retried.tokenHash).isEqualTo(created.tokenHash);
      assertThat(retried.recordDigest).isEqualTo(created.recordDigest);
      assertThat(retried.expiryMillis).isEqualTo(created.expiryMillis);
      assertThat(retried.localAof).isEqualTo(1);
      assertThat(retried.replicas).isEqualTo(1);

      byte[] changedBinding =
          syntheticRecord(tokenHash, "pending", expiryMillis / 1000, "request-b");
      assertThatThrownBy(() -> owner.registerPending(tokenHash, changedBinding, expiryMillis))
          .isInstanceOf(IllegalStateException.class);
      assertThat(adminGet(key)).isEqualTo(pending);

      // PENDING is deliberately not accepted as an active actor registry record.
      assertThatThrownBy(() -> owner.readActive(tokenHash))
          .isInstanceOf(IllegalStateException.class);
      assertThat(pinned.openedConnections()).isGreaterThan(0);
    }
  }

  @Test
  void wrongAuthenticatedPrincipalCannotReadEvenASyntheticActiveProjection() {
    String tokenHash = tokenHash();
    long expiryMillis = (Instant.now().getEpochSecond() + 300) * 1000L;
    byte[] active = syntheticRecord(tokenHash, "active", expiryMillis / 1000, "read-only-fixture");
    adminSetWithExpiry(key(tokenHash), active, expiryMillis);

    try (var wrongPrincipal = new PinnedAccountConnection(WRONG_USER, WRONG_PASSWORD)) {
      // Prove this authenticated test-only principal can reach the same synthetic key and
      // physical-expiry commands before the production identity guard rejects it.
      try (var connection = wrongPrincipal.openPinnedConnection()) {
        RedisCommands<byte[], byte[]> commands = connection.sync();
        assertThat(commands.aclWhoami()).isEqualTo(WRONG_USER);
        assertThat(commands.get(key(tokenHash))).isEqualTo(active);
        assertThat(commands.pexpiretime(key(tokenHash))).isEqualTo(expiryMillis);
      }

      AccountControlUiCoordination client =
          coordination(wrongPrincipal, new AcknowledgementRequirements(1, 1, 1000));
      assertThatThrownBy(() -> client.readActive(tokenHash))
          .isInstanceOf(IllegalStateException.class);
      assertThat(wrongPrincipal.openedConnections()).isEqualTo(2);
    }
  }

  @Test
  void activeReadRequiresExactPhysicalExpiryAndExpiredProjectionDenies() {
    String tokenHash = tokenHash();
    long expiryMillis = (Instant.now().getEpochSecond() + 300) * 1000L;
    byte[] active = syntheticRecord(tokenHash, "active", expiryMillis / 1000, "read-only-fixture");
    byte[] key = key(tokenHash);
    adminSetWithExpiry(key, active, expiryMillis);

    try (var pinned = new PinnedAccountConnection(ACCOUNT_USER, ACCOUNT_PASSWORD)) {
      AccountControlUiCoordination client =
          coordination(pinned, new AcknowledgementRequirements(1, 1, 1000));
      // Direct seeding exists only to exercise the actual read predicate; it is not activation.
      assertThat(client.readActive(tokenHash)).isEqualTo(active);

      adminSetExpiry(key, expiryMillis - 1000L);
      assertThatThrownBy(() -> client.readActive(tokenHash))
          .isInstanceOf(IllegalStateException.class);

      String expiredHash = tokenHash();
      long expiredMillis = (Instant.now().getEpochSecond() - 30) * 1000L;
      adminSetWithExpiry(
          key(expiredHash),
          syntheticRecord(expiredHash, "active", expiredMillis / 1000, "expired-fixture"),
          expiredMillis);
      assertThatThrownBy(() -> client.readActive(expiredHash))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private static AccountControlUiCoordination coordination(
      AccountCoordinationPinnedConnectionProvider provider,
      AcknowledgementRequirements acknowledgements) {
    return new AccountControlUiCoordination(provider, acknowledgements, TEST_CATALOG);
  }

  private static RedisScriptCatalog testCatalog() {
    // The isolated fixture contributes the production descriptor as-is; it does not register it
    // in Account's runtime ServiceLoader catalog or claim deployment/ACL registration.
    RedisScriptContribution contribution =
        new RedisScriptContribution() {
          @Override
          public String ownerId() {
            return "account-service";
          }

          @Override
          public Collection<RedisScriptDescriptor> descriptors() {
            return List.of(AccountControlUiRegistryContract.descriptor());
          }
        };
    RedisScriptCatalog catalog = RedisScriptCatalog.fromContributions(List.of(contribution));
    if (!AccountControlUiRegistryContract.descriptor()
        .equals(catalog.require(AccountControlUiRegistryContract.SCRIPT_ID))) {
      throw new IllegalStateException("Exact Account Control UI registry descriptor required");
    }
    return catalog;
  }

  private static void configureApplicationUser(
      RedisCommands<byte[], byte[]> commands, String username, String password, boolean registry) {
    if (registry) {
      commands.aclSetuser(
          username,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(password)
              .keyPattern("session:auth:token:*")
              // Narrow transport permissions required by this test's authenticated Lettuce client.
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.PEXPIRETIME)
              .addCommand(CommandType.TIME));
      // WAITAOF is newer than this Lettuce version's CommandType catalog, so use its supported
      // low-level command dispatch to add only that exact top-level ACL command.
      commands.dispatch(
          TestAclSetuser.INSTANCE,
          new StatusOutput<>(ByteArrayCodec.INSTANCE),
          new CommandArgs<>(ByteArrayCodec.INSTANCE).add("SETUSER").add(username).add("+waitaof"));
    } else {
      // The isolated Redis contains only synthetic test records. The wrong principal receives
      // read/physical-expiry access to that key family, but no registry-write or script rights.
      commands.aclSetuser(
          username,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(password)
              .keyPattern("session:auth:token:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.PEXPIRETIME));
    }
  }

  private static void awaitLocalAndReplicaAof(RedisCommands<byte[], byte[]> commands)
      throws InterruptedException {
    byte[] probeKey = bytes("test-only:account-coordination-aof-probe:" + UUID.randomUUID());
    commands.set(probeKey, bytes("test-only-replication-probe"));
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      List<Object> counts =
          commands.dispatch(
              TestWaitAof.INSTANCE,
              new ArrayOutput<>(ByteArrayCodec.INSTANCE),
              new CommandArgs<>(ByteArrayCodec.INSTANCE).add(1).add(1).add(500));
      if (hasAofCounts(counts, 1, 1)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("Test Coordination Redis replica did not acknowledge AOF");
  }

  private static boolean hasAofCounts(List<Object> counts, long local, long replicas) {
    return counts != null
        && counts.size() == 2
        && counts.get(0) instanceof Long observedLocal
        && counts.get(1) instanceof Long observedReplicas
        && observedLocal >= local
        && observedReplicas >= replicas;
  }

  private static byte[] adminGet(byte[] key) {
    try (var connection = adminClient.connect(ByteArrayCodec.INSTANCE)) {
      return connection.sync().get(key);
    }
  }

  private static void adminSetWithExpiry(byte[] key, byte[] value, long expiryMillis) {
    try (var connection = adminClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisCommands<byte[], byte[]> commands = connection.sync();
      commands.set(key, value);
      commands.pexpireat(key, expiryMillis);
    }
  }

  private static void adminSetExpiry(byte[] key, long expiryMillis) {
    try (var connection = adminClient.connect(ByteArrayCodec.INSTANCE)) {
      connection.sync().pexpireat(key, expiryMillis);
    }
  }

  private static RedisClient client(String username, String password) {
    RedisURI.Builder uri =
        RedisURI.Builder.redis(primary.getHost(), primary.getMappedPort(REDIS_PORT));
    if (username != null) {
      uri.withAuthentication(username, password);
    }
    RedisClient client = RedisClient.create(uri.build());
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  private static byte[] syntheticRecord(
      String tokenHash, String state, long expiryEpochSecond, String testOnlyBinding) {
    return AccountControlUiAuthority.canonical(
        Map.of(
            "schemaVersion",
            1,
            "registryVersion",
            "active".equals(state) ? 2 : 1,
            "profile",
            "control-ui",
            "type",
            "control-ui",
            "audience",
            "control-ui",
            "issuer",
            "firemud-account-service",
            "tokenHash",
            tokenHash,
            "state",
            state,
            "exp",
            expiryEpochSecond,
            "testOnlyBinding",
            testOnlyBinding));
  }

  private static String tokenHash() {
    return AccountControlUiIssuanceRepository.hash(
        bytes("test-only-control-ui-registry/" + UUID.randomUUID()));
  }

  private static byte[] key(String tokenHash) {
    return bytes("session:auth:token:" + tokenHash);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static final class PinnedAccountConnection
      implements AccountCoordinationPinnedConnectionProvider, AutoCloseable {
    private final RedisClient client;
    private final AtomicInteger openedConnections = new AtomicInteger();

    PinnedAccountConnection(String username, String password) {
      client = client(username, password);
    }

    @Override
    public StatefulRedisConnection<byte[], byte[]> openPinnedConnection() {
      openedConnections.incrementAndGet();
      return client.connect(ByteArrayCodec.INSTANCE);
    }

    int openedConnections() {
      return openedConnections.get();
    }

    @Override
    public void close() {
      client.shutdown();
    }
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
}
