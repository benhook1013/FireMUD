package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberRepairOperation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingAccountIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountMemberRepairRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical one-key Coordination Redis proof for the binding-local member projection. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayAccountMemberRepairRedisOwnerIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String PREFIX = "session:game:index:account:";
  private static final int PORT = 6379;

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(PORT);

  private RedisClient administrator;
  private RedisClient ownerClient;
  private CanonicalGameplayAccountMemberRepairRedisOwner owner;
  private RedisScriptCatalog catalog;
  private StatefulRedisConnection<byte[], byte[]> adminConnection;
  private RedisCommands<byte[], byte[]> admin;

  @BeforeEach
  void setUp() {
    administrator = createClient(null, null);
    adminConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    admin = adminConnection.sync();
    admin.flushdb();
    admin.aclSetuser(
        PRINCIPAL,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(PASSWORD)
            .keyPattern(PREFIX + "*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.SADD)
            .addCommand(CommandType.SISMEMBER));
    ownerClient = createClient(PRINCIPAL, PASSWORD);
    catalog = RedisScriptCatalog.loadInstalled(getClass().getClassLoader());
    owner = new CanonicalGameplayAccountMemberRepairRedisOwner(ownerClient, catalog);
  }

  @AfterEach
  void tearDown() {
    if (adminConnection != null) adminConnection.close();
    if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
  }

  @Test
  void addsExactMemberOnOneGlobalKeyAndExactRetryPreservesOtherTenantsAndNewerGeneration() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    var unrelated =
        new CanonicalGameplayAccountIndexMember(
                CanonicalGameplayBindingRef.of(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString()),
                BigInteger.valueOf(3),
                UUID.randomUUID())
            .value();
    var newer =
        new CanonicalGameplayAccountIndexMember(
                operation.bindingRef(),
                operation.bindingGeneration().add(BigInteger.ONE),
                UUID.randomUUID())
            .value();
    admin.sadd(key, ascii(unrelated), ascii(newer));

    var first = owner.projectAndRead(operation);
    var retry = owner.projectAndRead(operation);

    assertThat(first.operation()).isSameAs(operation);
    assertThat(first.member()).isEqualTo(operation.member());
    assertThat(retry.operation()).isSameAs(operation);
    assertThat(retry.member()).isEqualTo(operation.member());
    assertThat(admin.type(key)).isEqualTo("set");
    assertThat(admin.sismember(key, ascii(operation.member()))).isTrue();
    assertThat(admin.sismember(key, ascii(unrelated))).isTrue();
    assertThat(admin.sismember(key, ascii(newer))).isTrue();
    assertThat(admin.scard(key)).isEqualTo(3L);
    assertThat(admin.pttl(key)).isEqualTo(-1L);
  }

  @Test
  void refusesWrongPhysicalTypeAndPreservesItsValue() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    admin.set(key, ascii("operator-owned-string"));

    assertThatThrownBy(() -> owner.projectAndRead(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("projection is unavailable");

    assertThat(admin.type(key)).isEqualTo("string");
    assertThat(admin.get(key)).isEqualTo(ascii("operator-owned-string"));
  }

  @Test
  void refusesWrongRedisPrincipalWithoutCreatingTheAccountKey() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    var wrongOwner = new CanonicalGameplayAccountMemberRepairRedisOwner(administrator, catalog);

    assertThatThrownBy(() -> wrongOwner.projectAndRead(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("projection is unavailable");

    assertThat(admin.type(key)).isEqualTo("none");
  }

  @Test
  void refusesMalformedMemberAtTheLuaBoundaryWithoutCreatingTheKey() throws IOException {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    byte[] source;
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                CanonicalGameplayAccountMemberRepairRedisScriptContribution.RESOURCE_PATH)) {
      if (input == null) throw new IllegalStateException("Redis script resource is missing");
      source = input.readAllBytes();
    }
    String sha = admin.scriptLoad(source);
    byte[][] arguments = arguments(operation);
    arguments[5] = ascii(operation.member() + "-conflict");

    Long reply = admin.evalsha(sha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments);

    assertThat(reply).isEqualTo(-2L);
    assertThat(admin.type(key)).isEqualTo("none");

    arguments = arguments(operation);
    arguments[0] = ascii(UUID.randomUUID().toString());
    reply = admin.evalsha(sha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments);
    assertThat(reply).isEqualTo(-2L);
    assertThat(admin.type(key)).isEqualTo("none");

    arguments = arguments(operation);
    arguments[1] = ascii(UUID.randomUUID().toString());
    reply = admin.evalsha(sha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments);
    assertThat(reply).isEqualTo(-2L);
    assertThat(admin.type(key)).isEqualTo("none");
  }

  @Test
  void refusesRedisProjectionWhileSqlTransactionIsAmbient() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> owner.projectAndRead(operation))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("projection is unavailable");
    } finally {
      TransactionSynchronizationManager.clear();
    }

    assertThat(admin.type(key)).isEqualTo("none");
  }

  private static CanonicalGameplayAccountMemberRepairOperation operation() {
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    CanonicalGameplayBindingRef bindingRef =
        CanonicalGameplayBindingRef.of(tenantId, UUID.randomUUID(), UUID.randomUUID().toString());
    UUID accountIndexFence = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID coverageFence = UUID.randomUUID();
    BigInteger generation = BigInteger.valueOf(17);
    BigInteger snapshotRevision = BigInteger.valueOf(23);
    String member =
        new CanonicalGameplayAccountIndexMember(bindingRef, generation, accountIndexFence).value();
    var obligation =
        new CanonicalGameplayBindingAccountIndexObligation(
            transitionId,
            0,
            bindingRef,
            accountId,
            tenantId,
            CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN,
            generation,
            null,
            accountIndexFence,
            CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS,
            CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED,
            BigInteger.valueOf(22),
            CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED,
            null,
            null);
    return new CanonicalGameplayAccountMemberRepairOperation(
        transitionId,
        operationId,
        BigInteger.valueOf(4),
        coverageFence,
        snapshotRevision,
        bindingRef,
        accountId,
        tenantId,
        generation,
        accountIndexFence,
        obligation,
        member,
        BigInteger.valueOf(5));
  }

  private static byte[][] arguments(CanonicalGameplayAccountMemberRepairOperation operation) {
    return new byte[][] {
      ascii(operation.accountId().toString()),
      ascii(operation.tenantId().toString()),
      operation.bindingRef().bytes(),
      ascii(operation.bindingGeneration().toString()),
      ascii(operation.accountIndexFence().toString()),
      ascii(operation.member()),
      ascii(operation.transitionId().toString()),
      ascii(operation.operationId().toString()),
      ascii(operation.operationFence().toString()),
      ascii(operation.coverageFence().toString()),
      ascii(operation.inventorySnapshotRevision().toString()),
      ascii(operation.accountControlEpoch().toString())
    };
  }

  private RedisClient createClient(String username, String password) {
    RedisURI.Builder builder =
        RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(PORT))
            .withTimeout(Duration.ofSeconds(3));
    if (username != null) builder.withAuthentication(username, password);
    RedisClient client = RedisClient.create(builder.build());
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  private static byte[] key(UUID accountId) {
    return ascii(PREFIX + accountId);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
