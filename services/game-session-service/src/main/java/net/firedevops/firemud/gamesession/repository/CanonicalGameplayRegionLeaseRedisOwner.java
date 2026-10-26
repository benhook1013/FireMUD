package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manually composed region lease using the exact full-scope key, registered Lua, and readback. */
final class CanonicalGameplayRegionLeaseRedisOwner {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String SCHEMA = "gs_region_lease/v1";
  private static final int MAX_SCRIPT_BYTES = 16 * 1024;
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
  private static final List<String> LEASE_ARGUMENTS = List.of("schema", "token", "leaseTtlMs");
  private static final List<String> READ_ARGUMENTS = List.of("schema", "token");
  private static final List<RedisScriptDescriptor.KeySpec> EXPECTED_KEYS =
      List.of(
          new RedisScriptDescriptor.KeySpec(
              "regionLease",
              "tick-executor-lease:",
              RedisScriptDescriptor.HashTagDeclaration.REQUIRED));

  private final RedisClient redisClient;
  private final RedisScriptCatalog catalog;

  CanonicalGameplayRegionLeaseRedisOwner(RedisClient redisClient, RedisScriptCatalog catalog) {
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Coordination Redis client");
    this.catalog = Objects.requireNonNull(catalog, "Installed region lease script catalog");
  }

  LeaseGrant acquire(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      long regionEpoch,
      Duration leaseTtl,
      RuntimeIdentity identity) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(source, "source");
    requirePositive(regionEpoch, "regionEpoch");
    long ttlMillis = ttlMillis(leaseTtl);
    String ownerService = requireOwnerService(identity);
    String ownerInstance = requireOwnerInstance(identity);
    String token = UUID.randomUUID().toString();
    LeaseGrant grant = new LeaseGrant(source, regionEpoch, token, ownerService, ownerInstance);
    long outcome =
        execute(
            CanonicalGameplayRegionLeaseRedisScriptContribution.ACQUIRE_ID,
            CanonicalGameplayRegionLeaseRedisScriptContribution.ACQUIRE_PATH,
            LEASE_ARGUMENTS,
            grant,
            ttlMillis,
            true);
    if (outcome != 1L && outcome != 2L) {
      throw unavailable(new IllegalStateException("Region lease acquire outcome was " + outcome));
    }
    return grant;
  }

  void requireGrantScope(
      LeaseGrant grant,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      long regionEpoch,
      RuntimeIdentity identity) {
    Objects.requireNonNull(grant, "grant");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(identity, "runtimeIdentity");
    if (!grant.source.equals(source)
        || grant.regionEpoch != regionEpoch
        || !grant.ownerService.equals(requireOwnerService(identity))
        || !grant.ownerInstanceId.equals(requireOwnerInstance(identity))) {
      throw unavailable();
    }
  }

  void renew(LeaseGrant grant, Duration leaseTtl) {
    requireNoAmbientTransaction();
    long outcome =
        execute(
            CanonicalGameplayRegionLeaseRedisScriptContribution.RENEW_ID,
            CanonicalGameplayRegionLeaseRedisScriptContribution.RENEW_PATH,
            LEASE_ARGUMENTS,
            Objects.requireNonNull(grant, "grant"),
            ttlMillis(leaseTtl),
            true);
    if (outcome != 1L) throw unavailable();
  }

  void requireCurrent(LeaseGrant grant) {
    requireNoAmbientTransaction();
    long outcome =
        execute(
            CanonicalGameplayRegionLeaseRedisScriptContribution.READ_ID,
            CanonicalGameplayRegionLeaseRedisScriptContribution.READ_PATH,
            READ_ARGUMENTS,
            Objects.requireNonNull(grant, "grant"),
            0L,
            false);
    if (outcome != 1L) throw unavailable();
  }

  private long execute(
      String scriptId,
      String resourcePath,
      List<String> expectedArguments,
      LeaseGrant grant,
      long ttlMillis,
      boolean includeTtl) {
    RedisScriptDescriptor descriptor = requireDescriptor(scriptId, resourcePath, expectedArguments);
    byte[] source = scriptSource(descriptor);
    byte[] key = ascii(grant.key());
    byte[][] arguments = grant.arguments(includeTtl ? ttlMillis : null);
    if (arguments.length != expectedArguments.size()) throw unavailable();
    return executeInteger(source, key, arguments);
  }

  private long executeInteger(byte[] script, byte[] key, byte[][] arguments) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = connection.async();
      requirePrincipal(await(commands.aclWhoami()));
      String loadedSha = await(commands.scriptLoad(script));
      if (!digest("SHA-1", script).equals(loadedSha)) throw unavailable();
      Long result =
          await(
              commands.evalsha(loadedSha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments));
      if (result == null) throw unavailable();
      return result;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private RedisScriptDescriptor requireDescriptor(
      String id, String resourcePath, List<String> arguments) {
    try {
      RedisScriptDescriptor descriptor = catalog.require(id);
      if (!id.equals(descriptor.scriptId())
          || !resourcePath.equals(descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || descriptor.category() != RedisScriptDescriptor.ScriptCategory.TICK_LOCK
          || !EXPECTED_KEYS.equals(descriptor.keys())
          || !arguments.equals(
              descriptor.arguments().stream()
                  .map(RedisScriptDescriptor.ArgumentSpec::name)
                  .toList())) {
        throw unavailable();
      }
      return descriptor;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static byte[] scriptSource(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        CanonicalGameplayRegionLeaseRedisOwner.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable();
      byte[] source = input.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (source.length == 0
          || source.length > MAX_SCRIPT_BYTES
          || !descriptor.sha256().equals(digest("SHA-256", source))) {
        throw unavailable();
      }
      return source;
    } catch (IOException | RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static long ttlMillis(Duration duration) {
    Objects.requireNonNull(duration, "leaseTtl");
    long millis;
    try {
      millis = duration.toMillis();
    } catch (ArithmeticException overflow) {
      throw unavailable(overflow);
    }
    if (millis <= 0L) throw unavailable();
    return millis;
  }

  private static String requireOwnerService(RuntimeIdentity identity) {
    Objects.requireNonNull(identity, "runtimeIdentity");
    if (!"game-session-service".equals(identity.service())) throw unavailable();
    return identity.service();
  }

  private static String requireOwnerInstance(RuntimeIdentity identity) {
    String value = identity.serviceInstanceId();
    if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > 120) {
      throw unavailable();
    }
    return value;
  }

  private static void requirePrincipal(String principal) {
    if (!PRINCIPAL.equals(principal)) throw unavailable();
  }

  private static <T> T await(RedisFuture<T> future) {
    try {
      return future.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw unavailable(interrupted);
    } catch (ExecutionException | TimeoutException | RuntimeException failure) {
      future.cancel(true);
      throw unavailable(failure);
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception impossible) {
      throw unavailable(impossible);
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable();
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Canonical Game Session region lease is unavailable");
  }

  private static IllegalStateException unavailable(Throwable cause) {
    return new IllegalStateException("Canonical Game Session region lease is unavailable", cause);
  }

  /** Ephemeral opaque grant; its token never appears in string form, SQL, or returned evidence. */
  static final class LeaseGrant {
    private final CanonicalGameplayRegionRuntimeStatusRepository.Source source;
    private final long regionEpoch;
    private final String token;
    private final String ownerService;
    private final String ownerInstanceId;
    private final String key;

    private LeaseGrant(
        CanonicalGameplayRegionRuntimeStatusRepository.Source source,
        long regionEpoch,
        String token,
        String ownerService,
        String ownerInstanceId) {
      this.source = Objects.requireNonNull(source, "source");
      this.regionEpoch = regionEpoch;
      this.token = Objects.requireNonNull(token, "token");
      this.ownerService = Objects.requireNonNull(ownerService, "ownerService");
      this.ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
      this.key =
          CanonicalGameplayRegionRedisKeys.lease(
              source.tenantId(), source.gameInstanceId(), source.regionId());
    }

    String tokenCorrelation() {
      return digest("SHA-256", ascii(token));
    }

    String ownerService() {
      return ownerService;
    }

    String ownerInstanceId() {
      return ownerInstanceId;
    }

    private byte[][] arguments(Long ttlMillis) {
      if (ttlMillis == null) return new byte[][] {ascii(SCHEMA), ascii(token)};
      return new byte[][] {ascii(SCHEMA), ascii(token), ascii(ttlMillis.toString())};
    }

    String key() {
      return key;
    }
  }
}
