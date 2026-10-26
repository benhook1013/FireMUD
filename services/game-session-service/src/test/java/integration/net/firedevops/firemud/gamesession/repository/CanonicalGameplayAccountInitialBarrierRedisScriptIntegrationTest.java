package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountInitialBarrierRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Native Redis proof of create-only OPEN, exact physical readback, and CLOSED precedence. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayAccountInitialBarrierRedisScriptIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String SCHEMA = "gs_account_admission_barrier/v1";
  private static final String INCARNATION = "sha256:" + "a".repeat(64);

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private RedisClient administrator;
  private StatefulRedisConnection<byte[], byte[]> administratorConnection;
  private RedisCommands<byte[], byte[]> administratorCommands;
  private RedisClient ownerClient;

  @BeforeEach
  void configureOwnerPrincipal() {
    String password = UUID.randomUUID().toString();
    administrator = createClient(null, null);
    administratorConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    administratorCommands = administratorConnection.sync();
    administratorCommands.flushdb();
    administratorCommands.aclSetuser(
        PRINCIPAL,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(password)
            .keyPattern("session:game:*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.HLEN)
            .addCommand(CommandType.HGETALL)
            .addCommand(CommandType.HSET));
    ownerClient = createClient(PRINCIPAL, password);
  }

  @AfterEach
  void closeOwnedClients() {
    if (administratorConnection != null) administratorConnection.close();
    if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
  }

  @Test
  void oneKeyOpenIsReadBackExactlyAndEveryExistingClosedTargetWins() throws Exception {
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    UUID initializerId = UUID.randomUUID();
    String gameplayTag = "g8-" + "b".repeat(32);
    String key =
        "session:game:account-admission:{"
            + gameplayTag
            + "}:" + accountId + ":" + namespaceId;
    Map<String, String> expectedOpen =
        Map.of(
            "schema", SCHEMA,
            "accountId", accountId.toString(),
            "tenantId", tenantId.toString(),
            "playableStateNamespaceId", namespaceId.toString(),
            "tenantGameplayTag", gameplayTag,
            "ownerOperationId", initializerId.toString(),
            "ownerFenceEpoch", "8",
            "incarnationDigest", INCARNATION,
            "state", "OPEN");
    RedisScriptDescriptor descriptor =
        RedisScriptCatalog.fromContributions(
                List.of(new CanonicalGameplayAccountInitialBarrierRedisScriptContribution()))
            .require(CanonicalGameplayAccountInitialBarrierRedisScriptContribution.SCRIPT_ID);
    byte[] script;
    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
      assertThat(input).isNotNull();
      script = input.readAllBytes();
    }
    assertThat(hexSha256(script)).isEqualTo(descriptor.sha256());

    try (StatefulRedisConnection<byte[], byte[]> ownerConnection =
        ownerClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisCommands<byte[], byte[]> commands = ownerConnection.sync();
      assertThat(commands.aclWhoami()).isEqualTo(PRINCIPAL);
      String sha = commands.scriptLoad(script);
      assertThat(sha).isEqualTo(hexSha1(script));
      byte[][] arguments =
          openArguments(accountId, tenantId, namespaceId, gameplayTag, initializerId, "8");

      assertThat(
              commands.evalsha(
                  sha, ScriptOutputType.INTEGER, new byte[][] {ascii(key)}, arguments))
          .isEqualTo(1L);
      assertThat(commands.type(ascii(key))).isEqualTo("hash");
      assertThat(commands.pttl(ascii(key))).isEqualTo(-1L);
      assertThat(commands.hlen(ascii(key))).isEqualTo(9L);
      assertThat(readFields(commands, key)).isEqualTo(expectedOpen);

      // A lost response cannot turn the key into a mutable replay; the SQL owner handles retries
      // with exact readback and never executes this create script again after DISPATCH_POSSIBLE.
      assertThat(
              commands.evalsha(
                  sha, ScriptOutputType.INTEGER, new byte[][] {ascii(key)}, arguments))
          .isEqualTo(0L);
      assertThat(readFields(commands, key)).isEqualTo(expectedOpen);

      administratorCommands.del(ascii(key));
      Map<String, String> newerClosed = new HashMap<>(expectedOpen);
      newerClosed.put("ownerFenceEpoch", "9");
      newerClosed.put("state", "CLOSED");
      writeFields(administratorCommands, key, newerClosed);
      Map<String, String> beforeRefusedOpen = readFields(administratorCommands, key);
      assertThat(
              commands.evalsha(
                  sha, ScriptOutputType.INTEGER, new byte[][] {ascii(key)}, arguments))
          .isEqualTo(0L);
      assertThat(readFields(administratorCommands, key)).isEqualTo(beforeRefusedOpen);
      assertThat(readFields(administratorCommands, key)).containsEntry("state", "CLOSED");
      assertThat(readFields(administratorCommands, key)).containsEntry("ownerFenceEpoch", "9");
    }
  }

  private static byte[][] openArguments(
      UUID accountId,
      UUID tenantId,
      UUID namespaceId,
      String gameplayTag,
      UUID initializerId,
      String ownerFenceEpoch) {
    return new byte[][] {
      ascii(SCHEMA),
      ascii(accountId.toString()),
      ascii(tenantId.toString()),
      ascii(namespaceId.toString()),
      ascii(gameplayTag),
      ascii(initializerId.toString()),
      ascii(ownerFenceEpoch),
      ascii(INCARNATION),
      ascii("OPEN")
    };
  }

  private static Map<String, String> readFields(
      RedisCommands<byte[], byte[]> commands, String key) {
    Map<String, String> fields = new HashMap<>();
    for (Map.Entry<byte[], byte[]> field : commands.hgetall(ascii(key)).entrySet()) {
      fields.put(asciiString(field.getKey()), asciiString(field.getValue()));
    }
    return Map.copyOf(fields);
  }

  private static void writeFields(
      RedisCommands<byte[], byte[]> commands, String key, Map<String, String> fields) {
    for (Map.Entry<String, String> field : fields.entrySet()) {
      commands.hset(ascii(key), ascii(field.getKey()), ascii(field.getValue()));
    }
  }

  private static String hexSha256(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static String hexSha1(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String asciiString(byte[] value) {
    return new String(value, StandardCharsets.US_ASCII);
  }

  private static RedisClient createClient(String username, String password) {
    RedisURI.Builder builder = RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379));
    if (username != null) builder.withAuthentication(username, password);
    RedisClient client = RedisClient.create(builder.build());
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .protocolVersion(io.lettuce.core.protocol.ProtocolVersion.RESP2)
            .build());
    return client;
  }
}
