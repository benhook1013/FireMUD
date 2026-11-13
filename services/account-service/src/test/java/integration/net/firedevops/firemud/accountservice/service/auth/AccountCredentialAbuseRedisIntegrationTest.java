package net.firedevops.firemud.accountservice.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.config.AccountCredentialAbuseConfiguration;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Buckets;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.CredentialSourceContext;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Outcome;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Transport;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Unrun physical definitions for a run-owned dedicated cache only. Fixture limits/ACLs are
 * explicit, synthetic inputs, never deployed settings. These tests do not qualify public receiving
 * or RSS from used_memory; the isolated container has a separate hard memory limit.
 */
@Testcontainers
@org.junit.jupiter.api.Timeout(60)
@SuppressWarnings("resource")
class AccountCredentialAbuseRedisIntegrationTest {
  private static final String PRINCIPAL = "account_cache_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final long PROCESS_BYTES = 256L * 1024 * 1024;
  private static final long POLICY_BYTES = 64L * 1024 * 1024;
  @Container static final GenericContainer<?> redis = container(false);
  private static RedisClient admin;
  private static RedisClient application;

  @BeforeAll
  static void prepareOwnedFixture() {
    admin = client(redis, false);
    try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
      assertThat(connection.sync().info("server")).contains("redis_version:8.10.3\r\n");
      configurePrincipal(connection, true);
    }
    application = client(redis, true);
    assertThat(redis.getContainerInfo().getHostConfig().getMemory()).isEqualTo(PROCESS_BYTES);
  }

  @BeforeEach
  void resetOnlyOwnedDisposableDatabase() {
    try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
      connection.sync().flushdb();
      configurePrincipal(connection, true);
    }
  }

  @AfterAll
  static void closeClients() {
    if (application != null) application.shutdown();
    if (admin != null) admin.shutdown();
  }

  @Test
  void boundsActualKeysAfterPressureCounterLossAndFullReset() {
    var policy = policy(4_096);
    try (var key = key();
        var connection = application.connect(ByteArrayCodec.INSTANCE);
        var inspector = admin.connect(ByteArrayCodec.INSTANCE)) {
      var service = service(policy, key, connection);
      for (int i = 0; i < 24; i++) {
        service.admit(source("192.0.2." + (i + 1)), "candidate" + i + "@example.test");
        assertEnvelope(inspector, policy);
      }
      for (byte[] pressure :
          inspector.sync().keys(AccountCredentialAbuseSubjectKeys.PREFIX + "pressure:*")) {
        inspector.sync().del(pressure);
      }
      for (int i = 0; i < 24; i++) {
        service.admit(source("198.51.100." + (i + 1)), "new" + i + "@example.test");
        assertEnvelope(inspector, policy);
      }
      inspector.sync().flushdb();
      assertThat(service.admit(source("192.0.2.1"), "fresh@example.test").outcome())
          .isEqualTo(Outcome.ADMITTED);
      assertEnvelope(inspector, policy);
    }
  }

  @Test
  void retainsFixedExpiryAndMeasuresValuesWhileCleaningAnOversizedProvisionalBucket() {
    var policy = policy(4_096);
    try (var key = key();
        var connection = application.connect(ByteArrayCodec.INSTANCE);
        var inspector = admin.connect(ByteArrayCodec.INSTANCE)) {
      var service = service(policy, key, connection);
      var first = service.admit(source("192.0.2.1"), "player@example.test");
      assertThat(first.outcome()).isEqualTo(Outcome.ADMITTED);
      byte[] candidate = onlyCandidateKey(inspector);
      Long expiry = inspector.sync().pexpiretime(candidate);
      assertThat(service.recordFailure(first.permit()).outcome())
          .isEqualTo(Outcome.FAILURE_RECORDED);
      assertThat(inspector.sync().pexpiretime(candidate)).isEqualTo(expiry);
      assertEnvelope(inspector, policy);
      inspector.sync().flushdb();
      var tooSmall = service(policy(1), key, connection);
      assertThat(tooSmall.admit(source("192.0.2.1"), "player@example.test").outcome())
          .isEqualTo(Outcome.UNAVAILABLE);
      assertThat(inspector.sync().dbsize()).isZero();
    }
  }

  @Test
  void detectsWitnessCollisionAndDeniedCleanupWithoutAdmitting() {
    var policy = policy(4_096);
    try (var key = key();
        var connection = application.connect(ByteArrayCodec.INSTANCE);
        var inspector = admin.connect(ByteArrayCodec.INSTANCE)) {
      var service = service(policy, key, connection);
      assertThat(service.admit(source("192.0.2.1"), "player@example.test").outcome())
          .isEqualTo(Outcome.ADMITTED);
      byte[] candidate = onlyCandidateKey(inspector);
      Long expiry = inspector.sync().pexpiretime(candidate);
      String original = new String(inspector.sync().get(candidate), StandardCharsets.US_ASCII);
      String collided =
          original.replaceFirst(
              "wsh-v2-test-key-[0-9a-f]{64}", "wsh-v2-test-key-" + "f".repeat(64));
      assertThat(collided).isNotEqualTo(original);
      inspector.sync().set(candidate, bytes(collided));
      inspector.sync().pexpireat(candidate, expiry);
      assertThat(service.admit(source("192.0.2.1"), "player@example.test").outcome())
          .isEqualTo(Outcome.UNAVAILABLE);
      inspector.sync().flushdb();
      configurePrincipal(inspector, false);
      assertThat(service.admit(source("192.0.2.2"), "other@example.test").outcome())
          .isEqualTo(Outcome.UNAVAILABLE);
      assertThat(inspector.sync().dbsize()).isZero();
    }
  }

  @Test
  void deniedExpiryCapabilityRefusesBothScriptsBeforeCreatingAnyKey() throws Exception {
    var policy = policy(4_096);
    try (var key = key();
        var inspector = admin.connect(ByteArrayCodec.INSTANCE)) {
      configurePrincipal(inspector, true, false);
      try (var connection = application.connect(ByteArrayCodec.INSTANCE)) {
        var service = service(policy, key, connection);
        assertThat(service.admit(source("192.0.2.1"), "player@example.test").outcome())
            .isEqualTo(Outcome.UNAVAILABLE);
        assertThat(inspector.sync().dbsize()).isZero();
        // Exercise each registered script independently, so a first pressure refusal cannot hide
        // a missing subject-script preflight. These are only this run-owned fixture's keys.
        var keys = new AccountCredentialAbuseSubjectKeys(policy.scope(), key);
        for (var descriptor : new AccountCredentialAbuseRedisScriptContribution().descriptors()) {
          Instant now = Instant.now();
          long window = now.toEpochMilli() / policy.window().toMillis();
          boolean pressure =
              descriptor
                  .scriptId()
                  .equals(AccountCredentialAbuseRedisScriptContribution.ADMISSION_ID);
          var subject = keys.source("192.0.2.1", window);
          String redisKey = pressure ? keys.pressure(window) : subject.redisKey();
          String witness = pressure ? "pressure-v1" : subject.collisionWitness();
          byte[] script;
          try (var input =
              getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
            assertThat(input).isNotNull();
            script = input.readNBytes(32 * 1024 + 1);
          }
          assertThat(script.length).isLessThanOrEqualTo(32 * 1024);
          String sha = connection.sync().scriptLoad(script);
          byte[][] argv = {
            bytes(Long.toString(now.toEpochMilli())),
            bytes(Long.toString(policy.expiryEpochMillis(window))),
            bytes(Long.toString(policy.maximumSignalSkew().toMillis())),
            bytes(Long.toString(policy.maximumActiveSubjects())),
            bytes(Long.toString(policy.maximumMemoryBytes())),
            bytes(Long.toString(policy.maximumBucketMemoryBytes())),
            bytes(Long.toString(policy.maximumCounter())),
            bytes(Long.toString(policy.maximumAdmissionsPerWindow())),
            bytes(pressure ? "ADMIT" : "READ"),
            bytes(witness),
            bytes(Long.toString(policy.window().toMillis())),
            bytes(Long.toString(policy.timeToLive().toMillis()))
          };
          List<Object> result =
              connection
                  .sync()
                  .evalsha(sha, ScriptOutputType.MULTI, new byte[][] {bytes(redisKey)}, argv);
          assertThat(result).hasSize(3);
          assertThat(new String((byte[]) result.getFirst(), StandardCharsets.US_ASCII))
              .isEqualTo("UNAVAILABLE");
          assertThat(result.get(1)).isEqualTo(0L);
          assertThat(result.get(2)).isEqualTo(0L);
          assertThat(inspector.sync().dbsize()).isZero();
        }
        configurePrincipal(inspector, true);
        assertThat(service.admit(source("192.0.2.1"), "player@example.test").outcome())
            .isEqualTo(Outcome.ADMITTED);
        assertEnvelope(inspector, policy);
      }
    }
  }

  @Test
  void preservesBoundsAfterActualEvictionAndReturnsUnavailableDuringForeignPressure() {
    var policy = policy(4_096);
    try (var key = key();
        var connection = application.connect(ByteArrayCodec.INSTANCE);
        var inspector = admin.connect(ByteArrayCodec.INSTANCE)) {
      var service = service(policy, key, connection);
      assertThat(service.admit(source("192.0.2.1"), "player@example.test").outcome())
          .isEqualTo(Outcome.ADMITTED);
      long before = metric(inspector.sync().info("stats"), "evicted_keys");
      byte[] payload = new byte[64 * 1024];
      List<byte[]> fillers = new ArrayList<>();
      // At most 32 MiB is sent to the 16 MiB evictable run-owned cache, never a shared store.
      for (int i = 0; i < 512; i++) {
        byte[] filler = bytes("fixture:pressure:" + i);
        fillers.add(filler);
        inspector.sync().set(filler, payload);
      }
      assertThat(metric(inspector.sync().info("stats"), "evicted_keys")).isGreaterThan(before);
      assertThat(service.admit(source("192.0.2.2"), "other@example.test").outcome())
          .isEqualTo(Outcome.UNAVAILABLE);
      inspector.sync().del(fillers.toArray(byte[][]::new));
      service.admit(source("192.0.2.2"), "other@example.test");
      assertEnvelope(inspector, policy);
    }
  }

  @Test
  void multipleAccountReplicasUseSingleKeyAtomicFailureUpdates() throws Exception {
    var policy = policy(4_096);
    try (var key = key();
        var inspector = admin.connect(ByteArrayCodec.INSTANCE)) {
      var keys = new AccountCredentialAbuseSubjectKeys(policy.scope(), key);
      Instant now = Instant.now();
      long window = now.toEpochMilli() / policy.window().toMillis();
      var buckets =
          new Buckets(
              keys.source("192.0.2.1", window),
              keys.candidate("player@example.test", window),
              keys.pressure(window),
              policy.expiryEpochMillis(window));
      try (var seed = application.connect(ByteArrayCodec.INSTANCE)) {
        assertThat(new AccountCredentialAbuseRedisStore(seed).admit(policy, buckets, now))
            .isNotNull();
      }
      try (var pool = Executors.newFixedThreadPool(4)) {
        var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
        for (int i = 0; i < 4; i++)
          futures.add(
              pool.submit(
                  () -> {
                    try (var connection = application.connect(ByteArrayCodec.INSTANCE)) {
                      var store = new AccountCredentialAbuseRedisStore(connection);
                      for (int n = 0; n < 3; n++)
                        if (!store.recordFailure(policy, buckets, Instant.now())) return false;
                      return true;
                    }
                  }));
        for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
      }
      String candidate =
          new String(
              inspector.sync().get(bytes(buckets.candidate().redisKey())),
              StandardCharsets.US_ASCII);
      assertThat(candidate).endsWith("|0000000000000012|0000000000000000");
      assertEnvelope(inspector, policy);
    }
  }

  @Test
  void wrongPrincipalAndClusterTopologyRefuseWithoutAStandaloneFallback() {
    var policy = policy(4_096);
    try (var key = key();
        var wrong = admin.connect(ByteArrayCodec.INSTANCE)) {
      assertThat(
              service(policy, key, wrong)
                  .admit(source("192.0.2.1"), "player@example.test")
                  .outcome())
          .isEqualTo(Outcome.UNAVAILABLE);
    }
    // A second bounded disposable process exercises refusal, not Cluster qualification.
    try (var cluster = container(true)) {
      cluster.start();
      RedisClient clusterAdmin = client(cluster, false);
      RedisClient clusterApp = null;
      try {
        try (var adminConnection = clusterAdmin.connect(ByteArrayCodec.INSTANCE)) {
          configurePrincipal(adminConnection, true);
        }
        clusterApp = client(cluster, true);
        try (var key = key();
            var connection = clusterApp.connect(ByteArrayCodec.INSTANCE)) {
          assertThat(
                  service(policy(4_096), key, connection)
                      .admit(source("192.0.2.1"), "player@example.test")
                      .outcome())
              .isEqualTo(Outcome.UNAVAILABLE);
        }
      } finally {
        if (clusterApp != null) clusterApp.shutdown();
        clusterAdmin.shutdown();
      }
    }
  }

  private static GenericContainer<?> container(boolean cluster) {
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
            cluster ? "yes" : "no");
  }

  private static RedisClient client(GenericContainer<?> target, boolean authenticated) {
    var builder =
        RedisURI.Builder.redis(target.getHost(), target.getMappedPort(6379))
            .withTimeout(Duration.ofSeconds(2));
    if (authenticated) builder.withAuthentication(PRINCIPAL, PASSWORD);
    var client = RedisClient.create(builder.build());
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  private static void configurePrincipal(
      StatefulRedisConnection<byte[], byte[]> connection, boolean cleanup) {
    configurePrincipal(connection, cleanup, true);
  }

  private static void configurePrincipal(
      StatefulRedisConnection<byte[], byte[]> connection, boolean cleanup, boolean expiry) {
    var args =
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(PASSWORD)
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
            .addCommand(CommandType.MEMORY, CommandKeyword.USAGE);
    if (cleanup) args.addCommand(CommandType.DEL);
    if (expiry) args.addCommand(CommandType.PEXPIRETIME);
    connection.sync().aclSetuser(PRINCIPAL, args);
  }

  private static AccountCredentialAbuseService service(
      AccountCredentialAbusePolicy p,
      AccountCredentialAbuseSubjectKeys.DigestKey key,
      StatefulRedisConnection<byte[], byte[]> connection) {
    return AccountCredentialAbuseConfiguration.controlled(
        AccountCredentialAbusePolicySource.controlled(p, key), connection, Clock.systemUTC());
  }

  private static AccountCredentialAbusePolicy policy(long bucketBytes) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    return new AccountCredentialAbusePolicy(
        "login-v1",
        now.minusSeconds(60),
        now.plusSeconds(3_600),
        Duration.ofSeconds(60),
        Duration.ofSeconds(120),
        2,
        8,
        POLICY_BYTES,
        bucketBytes,
        100,
        1_000,
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(1),
        List.of(
            new AccountCredentialAbusePolicy.Rule(3, 2, 0, Duration.ofSeconds(1)),
            new AccountCredentialAbusePolicy.Rule(3, 2, 10, Duration.ofSeconds(5))));
  }

  private static byte[] onlyCandidateKey(StatefulRedisConnection<byte[], byte[]> inspector) {
    // Read the retained key instead of deriving a possibly newer wall-clock window.
    var keys =
        inspector.sync().keys(AccountCredentialAbuseSubjectKeys.PREFIX + "subject:*:candidate:*");
    assertThat(keys).hasSize(1);
    return keys.getFirst();
  }

  private static void assertEnvelope(
      StatefulRedisConnection<byte[], byte[]> inspector, AccountCredentialAbusePolicy policy) {
    assertThat(inspector.sync().dbsize()).isLessThanOrEqualTo(policy.maximumActiveSubjects());
    long sum = 0;
    for (byte[] key : inspector.sync().keys(AccountCredentialAbuseSubjectKeys.PREFIX + "*")) {
      Long size = inspector.sync().memoryUsage(key);
      assertThat(size).isNotNull().isLessThanOrEqualTo(policy.maximumBucketMemoryBytes());
      sum += size;
      assertThat(inspector.sync().pttl(key))
          .isPositive()
          .isLessThanOrEqualTo(policy.timeToLive().toMillis());
    }
    assertThat(sum)
        .isLessThanOrEqualTo(policy.maximumActiveSubjects() * policy.maximumBucketMemoryBytes());
    String memory = inspector.sync().info("memory");
    assertThat(metric(memory, "used_memory")).isLessThanOrEqualTo(policy.maximumMemoryBytes());
    // Observed RSS is separate evidence; the Docker memory cap is the fixture's hard process
    // boundary.
    assertThat(metric(memory, "used_memory_rss")).isLessThanOrEqualTo(PROCESS_BYTES);
  }

  private static long metric(String info, String name) {
    return info.lines()
        .filter(line -> line.startsWith(name + ":"))
        .map(line -> line.substring(name.length() + 1))
        .mapToLong(Long::parseLong)
        .findFirst()
        .orElseThrow();
  }

  private static CredentialSourceContext source(String address) {
    return new CredentialSourceContext(address, Transport.HTTP);
  }

  private static AccountCredentialAbuseSubjectKeys.DigestKey key() {
    return new AccountCredentialAbuseSubjectKeys.DigestKey("test-key", new byte[32]);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
