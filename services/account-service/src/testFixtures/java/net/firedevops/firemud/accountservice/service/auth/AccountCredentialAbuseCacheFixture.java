package net.firedevops.firemud.accountservice.service.auth;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.config.AccountCredentialAbuseConfiguration;
import net.firedevops.firemud.test.TestContainerImages;
import org.testcontainers.containers.GenericContainer;

/** Explicit disposable Cache/Rate-Limit composition. Never takes a Coordination endpoint. */
public final class AccountCredentialAbuseCacheFixture implements AutoCloseable {
  private static final long PROCESS_BYTES = 256L * 1024 * 1024;
  private RedisClient client;
  private StatefulRedisConnection<byte[], byte[]> connection;
  private AccountCredentialAbuseSubjectKeys.DigestKey key;
  private AccountCredentialAbuseService service;
  private AccountCredentialAbusePolicySource policySource;

  public static GenericContainer<?> container() {
    return new GenericContainer<>(TestContainerImages.redis())
        .withLabel("firemud.proof", "account-shared-abuse")
        .withLabel(
            "firemud.proof.run", System.getenv().getOrDefault("GITHUB_RUN_ID", "local-unrun"))
        .withExposedPorts(6379)
        .withStartupTimeout(Duration.ofSeconds(30))
        .withCreateContainerCmdModifier(
            command ->
                command
                    .getHostConfig()
                    .withMemory(PROCESS_BYTES)
                    .withMemorySwap(PROCESS_BYTES)
                    .withPidsLimit(128L))
        .withCommand(
            "redis-server",
            "--bind",
            "0.0.0.0",
            "--protected-mode",
            "no",
            "--save",
            "",
            "--appendonly",
            "no",
            "--maxmemory",
            "16mb",
            "--maxmemory-policy",
            "allkeys-lru",
            "--cluster-enabled",
            "no");
  }

  /** The test owns a separate container returned by container(); nothing starts implicitly. */
  public AccountCredentialAbuseCacheFixture(GenericContainer<?> cache) {
    if (!cache.isRunning()
        || !Long.valueOf(PROCESS_BYTES)
            .equals(cache.getContainerInfo().getHostConfig().getMemory()))
      throw new IllegalArgumentException("Bounded run-owned cache required");
    String password = UUID.randomUUID().toString(); // Disposable fixture credential only.
    String principal = AccountCredentialAbuseRedisScriptContribution.PRINCIPAL;
    var address =
        RedisURI.Builder.redis(cache.getHost(), cache.getMappedPort(6379))
            .withTimeout(Duration.ofSeconds(2));
    var admin = RedisClient.create(address.build());
    try (var adminConnection = admin.connect(ByteArrayCodec.INSTANCE)) {
      if (!adminConnection.sync().info("server").contains("redis_version:8.10.3\r\n"))
        throw new IllegalStateException("Exact Redis fixture version required");
      adminConnection
          .sync()
          .aclSetuser(
              principal,
              new AclSetuserArgs()
                  .reset()
                  .on()
                  .addPassword(password)
                  .keyPattern(AccountCredentialAbuseSubjectKeys.PREFIX + "*")
                  .addCommand(CommandType.HELLO)
                  .addCommand(CommandType.PING)
                  .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
                  .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
                  .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
                  .addCommand(CommandType.EVALSHA)
                  .addCommand(CommandType.INFO)
                  .addCommand(CommandType.TIME)
                  .addCommand(CommandType.DBSIZE)
                  .addCommand(CommandType.STRLEN)
                  .addCommand(CommandType.GET)
                  .addCommand(CommandType.SET)
                  .addCommand(CommandType.MEMORY, CommandKeyword.USAGE)
                  .addCommand(CommandType.DEL)
                  .addCommand(CommandType.PEXPIRETIME));
    } finally {
      admin.shutdown();
    }
    try {
      client =
          RedisClient.create(
              RedisURI.Builder.redis(cache.getHost(), cache.getMappedPort(6379))
                  .withAuthentication(principal, password)
                  .withTimeout(Duration.ofSeconds(2))
                  .build());
      client.setOptions(ClientOptions.builder().autoReconnect(false).build());
      connection = client.connect(ByteArrayCodec.INSTANCE);
      key = new AccountCredentialAbuseSubjectKeys.DigestKey("test-key", new byte[32]);
      Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      // Synthetic receiving values only. No production policy defaults or protected delivery claim.
      var policy =
          new AccountCredentialAbusePolicy(
              "receiving-" + UUID.randomUUID(),
              now.minusSeconds(60),
              now.plusSeconds(3600),
              Duration.ofSeconds(60),
              Duration.ofSeconds(120),
              2,
              512,
              64L * 1024 * 1024,
              4096,
              1000,
              10000,
              Duration.ofSeconds(2),
              Duration.ofSeconds(5),
              Duration.ofSeconds(10),
              Duration.ofSeconds(1),
              List.of(
                  new AccountCredentialAbusePolicy.Rule(3, 2, 0, Duration.ofSeconds(1)),
                  new AccountCredentialAbusePolicy.Rule(3, 2, 10, Duration.ofSeconds(5))));
      policySource = AccountCredentialAbusePolicySource.controlled(policy, key);
      service =
          AccountCredentialAbuseConfiguration.controlled(
              policySource, connection, Clock.systemUTC());
    } catch (RuntimeException | Error failure) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  public AccountCredentialAbuseService service() {
    return service;
  }

  /** Observation/fault boundaries always delegate to the real store; no synthetic signals. */
  public AccountCredentialAbuseService observedService(
      java.util.function.Consumer<BackendObservation> observer) {
    java.util.Objects.requireNonNull(observer);
    var actual = new AccountCredentialAbuseRedisStore(connection);
    return new AccountCredentialAbuseService(
        policySource,
        new AccountCredentialAbuseService.Backend() {
          @Override
          public AccountCredentialAbusePolicy.Signals admit(
              AccountCredentialAbusePolicy policy,
              AccountCredentialAbuseService.Buckets buckets,
              Instant now) {
            observer.accept(new BackendObservation(BackendPhase.BEFORE_ADMISSION, buckets));
            var result = actual.admit(policy, buckets, now);
            observer.accept(new BackendObservation(BackendPhase.AFTER_ADMISSION, buckets));
            return result;
          }

          @Override
          public boolean recordFailure(
              AccountCredentialAbusePolicy policy,
              AccountCredentialAbuseService.Buckets buckets,
              Instant now) {
            observer.accept(new BackendObservation(BackendPhase.BEFORE_FAILURE, buckets));
            boolean result = actual.recordFailure(policy, buckets, now);
            observer.accept(new BackendObservation(BackendPhase.AFTER_FAILURE, buckets));
            return result;
          }
        },
        Clock.systemUTC());
  }

  public String candidateImage(AccountCredentialAbuseService.Buckets buckets) {
    byte[] value =
        connection
            .sync()
            .get(buckets.candidate().redisKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return value == null ? null : new String(value, java.nio.charset.StandardCharsets.US_ASCII);
  }

  public enum BackendPhase {
    BEFORE_ADMISSION,
    AFTER_ADMISSION,
    BEFORE_FAILURE,
    AFTER_FAILURE
  }

  public record BackendObservation(
      BackendPhase phase, AccountCredentialAbuseService.Buckets buckets) {}

  /** Close only this fixture's real application connection; no cache or Coordination reset. */
  public void disconnectApplication() {
    connection.close();
  }

  public void close() {
    try {
      if (connection != null) connection.close();
    } finally {
      try {
        if (client != null) client.shutdown();
      } finally {
        if (key != null) key.close();
      }
    }
  }
}
