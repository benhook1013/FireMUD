package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewCandidateClosureRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical Lua ordering proof only; controlled bytes do not establish SQL or Account authority. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewCandidateClosureLuaIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TENANT = "11111111-1111-4111-8111-111111111111";
  private static final String ACCOUNT = "22222222-2222-4222-8222-222222222222";
  private static final String NAMESPACE = "33333333-3333-4333-8333-333333333333";
  private static final String CHARACTER = "44444444-4444-4444-8444-444444444444";
  private static final String INSTANCE = "55555555-5555-4555-8555-555555555555";
  private static final String SESSION = "66666666-6666-4666-8666-666666666666";
  private static final String ABANDONMENT = "77777777-7777-4777-8777-777777777777";
  private static final String TAG = "controlled-original-new-closure";
  private static final String PURPOSE = "original-new-never-admitted-closure/v1";
  private static final byte[] CANDIDATE = new byte[] {0, 3, 7, (byte) 0xff, 13, 10};
  private static final byte[][] KEYS = {
    bytes("session:game:{" + TAG + "}:" + INSTANCE + ":" + SESSION),
    bytes("session:game:index:character:{" + TAG + "}:" + NAMESPACE + ":" + CHARACTER),
    bytes("session:game:index:account-tenant:{" + TAG + "}:" + ACCOUNT),
    bytes("session:game:index:tenant:{" + TAG + "}")
  };

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private RedisClient adminClient;
  private RedisClient ownerClient;
  private StatefulRedisConnection<byte[], byte[]> adminConnection;
  private StatefulRedisConnection<byte[], byte[]> ownerConnection;
  private RedisCommands<byte[], byte[]> admin;
  private RedisCommands<byte[], byte[]> owner;
  private String closeSha;
  private String installSha;
  private String candidateDigest;

  @BeforeEach
  void setUp() throws Exception {
    var address = RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379));
    adminClient = RedisClient.create(address);
    adminConnection = adminClient.connect(ByteArrayCodec.INSTANCE);
    admin = adminConnection.sync();
    admin.flushdb();
    admin.aclSetuser(
        PRINCIPAL,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(PASSWORD)
            .keyPattern("session:game:*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT)
            .addCommand(CommandType.SCRIPT)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.GET)
            .addCommand(CommandType.SET)
            .addCommand(CommandType.DEL)
            .addCommand(CommandType.SISMEMBER)
            .addCommand(CommandType.SADD)
            .addCommand(CommandType.SREM));
    ownerClient =
        RedisClient.create(
            RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379))
                .withAuthentication(PRINCIPAL, PASSWORD)
                .withTimeout(Duration.ofSeconds(3))
                .build());
    ownerConnection = ownerClient.connect(ByteArrayCodec.INSTANCE);
    owner = ownerConnection.sync();
    var descriptor =
        new CanonicalGameplayOriginalNewCandidateClosureRedisScriptContribution()
            .descriptors()
            .iterator()
            .next();
    byte[] script = resource(descriptor.resourcePath());
    assertThat(sha256(script)).isEqualTo(descriptor.sha256());
    closeSha = owner.scriptLoad(script);
    installSha =
        owner.scriptLoad(resource("redis/lua/game-session-original-new-candidate-install.lua"));
    candidateDigest = "sha256:" + sha256(CANDIDATE);
  }

  @AfterEach
  void close() {
    if (ownerConnection != null) ownerConnection.close();
    if (adminConnection != null) adminConnection.close();
    if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (adminClient != null) adminClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
  }

  @Test
  void closureBeforeDelayedCreateRetainsFenceAndRefusesCreation() {
    assertThat(closeOriginal()).isEqualTo(1L);
    assertFence();
    assertThat(install()).isEqualTo(-1L);
    assertFence();
    assertThat(admin.exists(KEYS[1], KEYS[2], KEYS[3])).isZero();
    assertThat(closeOriginal()).isEqualTo(2L);
    assertFence();
  }

  @Test
  void creationBeforeClosureRemovesOnlyExactAttemptAndPreservesOtherMembers() {
    admin.sadd(KEYS[2], bytes("other-account-session"));
    admin.sadd(KEYS[3], bytes("other-tenant-session"));
    assertThat(install()).isEqualTo(1L);
    assertThat(closeOriginal()).isEqualTo(1L);
    assertFence();
    assertThat(admin.exists(KEYS[1])).isZero();
    assertThat(admin.smembers(KEYS[2])).hasSize(1);
    assertThat(admin.sismember(KEYS[2], bytes("other-account-session"))).isTrue();
    assertThat(admin.smembers(KEYS[3])).hasSize(1);
    assertThat(admin.sismember(KEYS[3], bytes("other-tenant-session"))).isTrue();
    assertThat(install()).isEqualTo(-1L);
    assertThat(closeOriginal()).isEqualTo(2L);
  }

  @Test
  void absentAttemptClosureAndReplayPreserveForeignCharacterController() {
    byte[] foreign = bytes("foreign-controller-generation-2");
    admin.set(KEYS[1], foreign);
    assertThat(closeOriginal()).isEqualTo(1L);
    assertThat(closeOriginal()).isEqualTo(2L);
    assertThat(admin.get(KEYS[1])).containsExactly(foreign);
    assertThat(install()).isEqualTo(-1L);
    assertFence();
  }

  @Test
  void foreignOrPartialCandidateIsRefusedWithoutDeletingEvidence() {
    assertThat(install()).isEqualTo(1L);
    byte[] originalCharacter = admin.get(KEYS[1]);
    admin.srem(KEYS[3], bytes(SESSION));
    assertThat(closeOriginal()).isEqualTo(-1L);
    assertThat(admin.get(KEYS[0])).containsExactly(CANDIDATE);
    assertThat(admin.get(KEYS[1])).containsExactly(originalCharacter);
    assertThat(admin.sismember(KEYS[2], bytes(SESSION))).isTrue();
    byte[] foreign = bytes("authenticated-or-foreign-session");
    admin.set(KEYS[0], foreign);
    assertThat(closeOriginal()).isEqualTo(-1L);
    assertThat(admin.get(KEYS[0])).containsExactly(foreign);
    assertThat(admin.get(KEYS[1])).containsExactly(originalCharacter);
  }

  @Test
  void expiringOrWrongPurposeStateDoesNotProduceClosure() {
    assertThat(install()).isEqualTo(1L);
    admin.pexpire(KEYS[0], 120_000);
    assertThat(closeOriginal()).isEqualTo(-1L);
    assertThat(admin.get(KEYS[0])).containsExactly(CANDIDATE);
    byte[][] arguments = closeArguments();
    arguments[0] = bytes("original-new-non-admitting-candidate/v1");
    assertThat(owner.<Long>evalsha(closeSha, ScriptOutputType.INTEGER, KEYS, arguments))
        .isEqualTo(-2L);
    assertThat(admin.get(KEYS[0])).containsExactly(CANDIDATE);
  }

  private long install() {
    byte[][] arguments = Arrays.copyOf(closeArguments(), 10);
    arguments[0] = bytes("original-new-non-admitting-candidate/v1");
    return owner.evalsha(installSha, ScriptOutputType.INTEGER, KEYS, arguments);
  }

  private long closeOriginal() {
    return owner.evalsha(closeSha, ScriptOutputType.INTEGER, KEYS, closeArguments());
  }

  private byte[][] closeArguments() {
    return new byte[][] {
      bytes(PURPOSE), bytes(TENANT), bytes(ACCOUNT), bytes(NAMESPACE), bytes(CHARACTER),
      bytes(INSTANCE), bytes(SESSION), bytes("1"), bytes(TAG), CANDIDATE.clone(),
      bytes(ABANDONMENT), bytes(candidateDigest)
    };
  }

  private void assertFence() {
    assertThat(admin.get(KEYS[0]))
        .containsExactly(bytes(PURPOSE + ":" + ABANDONMENT + ":" + candidateDigest));
    assertThat(admin.pttl(KEYS[0])).isEqualTo(-1L);
  }

  private byte[] resource(String path) throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
      if (input == null) throw new IllegalStateException("Closure proof script is absent");
      return input.readAllBytes();
    }
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
