package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayRegionLeaseRedisOwnerIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final int PORT = 6379;

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(PORT);

  @Test
  void exactOpaqueLeaseGrantIsScopedRenewableAndFailsClosedAfterExpiry() {
    try (RedisFixture redis = RedisFixture.open()) {
      var owner = new CanonicalGameplayRegionLeaseRedisOwner(redis.ownerClient(), redis.catalog());
      RuntimeIdentity firstIdentity = identity("instance-a");
      var firstSource = source(GAME_INSTANCE_ID_A);
      try {
        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(
                () -> owner.acquire(firstSource, 7L, Duration.ofSeconds(10), firstIdentity))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      assertThat(
              redis
                  .admin()
                  .type(
                      ascii(
                          CanonicalGameplayRegionRedisKeys.lease(
                              TENANT_ID, GAME_INSTANCE_ID_A, OPERATIONAL_REGION_ID))))
          .isEqualTo("none");
      seedMeta(redis.admin(), firstSource);
      var firstGrant = owner.acquire(firstSource, 7L, Duration.ofSeconds(10), firstIdentity);
      byte[] firstKey = ascii(firstGrant.key());
      byte[] firstValue = redis.admin().get(firstKey);

      assertThat(redis.admin().type(firstKey)).isEqualTo("string");
      assertThat(new String(firstValue, StandardCharsets.US_ASCII))
          .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
      assertThat(redis.admin().pttl(firstKey)).isPositive();
      owner.requireCurrent(firstGrant);
      owner.renew(firstGrant, Duration.ofSeconds(10));
      assertThat(redis.admin().get(firstKey)).containsExactly(firstValue);

      assertThatThrownBy(
              () -> owner.acquire(firstSource, 7L, Duration.ofSeconds(10), identity("instance-b")))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().get(firstKey)).containsExactly(firstValue);

      var secondGameSource = source(GAME_INSTANCE_ID_B);
      seedMeta(redis.admin(), secondGameSource);
      var secondGameGrant =
          owner.acquire(secondGameSource, 7L, Duration.ofSeconds(10), firstIdentity);
      assertThat(secondGameGrant.key()).isNotEqualTo(firstGrant.key());
      owner.requireCurrent(secondGameGrant);

      assertThat(redis.admin().pexpire(firstKey, 0L)).isTrue();
      assertThatThrownBy(() -> owner.requireCurrent(firstGrant))
          .isInstanceOf(IllegalStateException.class);
      var successor =
          owner.acquire(firstSource, 7L, Duration.ofSeconds(10), identity("instance-b"));
      assertThat(redis.admin().get(firstKey)).isNotEqualTo(firstValue);
      owner.requireCurrent(successor);
    }
  }

  @Test
  void acceptsTickZeroAndRejectsExpiringTimelineMetadata() {
    try (RedisFixture redis = RedisFixture.open()) {
      var owner = new CanonicalGameplayRegionLeaseRedisOwner(redis.ownerClient(), redis.catalog());
      var source = source(GAME_INSTANCE_ID_A);
      String metaKey =
          CanonicalGameplayRegionRedisKeys.meta(
              source.tenantId(), source.gameInstanceId(), source.regionId());
      seedMeta(redis.admin(), source);
      redis
          .admin()
          .hset(
              ascii(metaKey),
              java.util.Map.of(
                  ascii("current_tick_id"), ascii("0"),
                  ascii("current_tick_state"), ascii("STAGED")));

      var grant = owner.acquire(source, 7L, Duration.ofSeconds(10), identity("tick-zero"));
      owner.requireCurrent(grant);
      owner.renew(grant, Duration.ofSeconds(10));

      assertThat(redis.admin().pexpire(ascii(metaKey), 60_000L)).isTrue();
      assertThatThrownBy(() -> owner.requireCurrent(grant))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> owner.renew(grant, Duration.ofSeconds(10)))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () -> owner.acquire(source, 7L, Duration.ofSeconds(10), identity("after-meta-ttl")))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID GAME_INSTANCE_ID_A =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID GAME_INSTANCE_ID_B =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CANONICAL_REGION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID OPERATIONAL_REGION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");

  private static RuntimeIdentity identity(String instanceId) {
    return new RuntimeIdentity(
        "game-session-service",
        instanceId,
        "localhost",
        Instant.parse("2026-10-26T00:00:00Z"),
        "test",
        "test-sha",
        "test-image");
  }

  private static CanonicalGameplayRegionRuntimeStatusRepository.Source source(UUID gameInstanceId) {
    var request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "firemud",
            TENANT_ID,
            "test-world",
            gameInstanceId,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            "SHARED",
            true,
            "control-plane-request",
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            "sha256:" + "1".repeat(64),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64));
    return new CanonicalGameplayRegionRuntimeStatusRepository.Source(
        TENANT_ID,
        gameInstanceId,
        CANONICAL_REGION_ID,
        OPERATIONAL_REGION_ID,
        9L,
        12L,
        request,
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        "a".repeat(64),
        "sha256:" + "b".repeat(64));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static void seedMeta(
      RedisCommands<byte[], byte[]> admin,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source) {
    admin.hset(
        ascii(
            CanonicalGameplayRegionRedisKeys.meta(
                source.tenantId(), source.gameInstanceId(), source.regionId())),
        java.util.Map.of(
            ascii("region_epoch"), ascii("7"),
            ascii("current_tick_id"), ascii("-1"),
            ascii("current_tick_state"), ascii("APPLIED")));
  }

  private static final class RedisFixture implements AutoCloseable {
    private final RedisClient administrator;
    private final RedisClient ownerClient;
    private final StatefulRedisConnection<byte[], byte[]> adminConnection;
    private final RedisCommands<byte[], byte[]> admin;
    private final RedisScriptCatalog catalog;

    private RedisFixture(
        RedisClient administrator,
        RedisClient ownerClient,
        StatefulRedisConnection<byte[], byte[]> adminConnection,
        RedisCommands<byte[], byte[]> admin,
        RedisScriptCatalog catalog) {
      this.administrator = administrator;
      this.ownerClient = ownerClient;
      this.adminConnection = adminConnection;
      this.admin = admin;
      this.catalog = catalog;
    }

    static RedisFixture open() {
      RedisClient administrator = createClient(null, null);
      StatefulRedisConnection<byte[], byte[]> adminConnection =
          administrator.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      admin.flushdb();
      admin.aclSetuser(
          PRINCIPAL,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(PASSWORD)
              .keyPattern("tick-executor-lease:*")
              .keyPattern("tick:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.TYPE)
              .addCommand(CommandType.HGET)
              .addCommand(CommandType.PTTL)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.PEXPIRE));
      RedisClient ownerClient = createClient(PRINCIPAL, PASSWORD);
      RedisScriptCatalog catalog =
          RedisScriptCatalog.fromContributions(
              java.util.List.of(new CanonicalGameplayRegionLeaseRedisScriptContribution()));
      return new RedisFixture(administrator, ownerClient, adminConnection, admin, catalog);
    }

    private static RedisClient createClient(String username, String password) {
      RedisURI.Builder builder =
          RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(PORT))
              .withTimeout(Duration.ofSeconds(3));
      if (username != null) builder.withAuthentication(username, password);
      RedisClient client = RedisClient.create(builder.build());
      client.setOptions(ClientOptions.builder().autoReconnect(false).build());
      return client;
    }

    private RedisClient ownerClient() {
      return ownerClient;
    }

    private RedisCommands<byte[], byte[]> admin() {
      return admin;
    }

    private RedisScriptCatalog catalog() {
      return catalog;
    }

    @Override
    public void close() {
      adminConnection.close();
      ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }
}
