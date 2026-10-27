package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
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
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionBindingBridgeRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manually composed region lease using the exact full-scope key, registered Lua, and readback. */
final class CanonicalGameplayRegionLeaseRedisOwner {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String SCHEMA = "gs_region_lease/v1";
  private static final int MAX_SCRIPT_BYTES = 16 * 1024;
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
  private static final List<String> LEASE_ARGUMENTS =
      List.of("schema", "token", "expectedRegionEpoch", "leaseTtlMs");
  private static final List<String> READ_ARGUMENTS =
      List.of("schema", "token", "expectedRegionEpoch");
  private static final List<RedisScriptDescriptor.KeySpec> EXPECTED_KEYS =
      List.of(
          new RedisScriptDescriptor.KeySpec(
              "regionLease",
              "tick-executor-lease:",
              RedisScriptDescriptor.HashTagDeclaration.REQUIRED),
          new RedisScriptDescriptor.KeySpec(
              "regionMeta", "tick:", RedisScriptDescriptor.HashTagDeclaration.REQUIRED));
  private static final String TIMELINE_SCHEMA = "gs_region_timeline_init/v1";
  private static final List<String> TIMELINE_ARGUMENTS =
      List.of(
          "schema",
          "action",
          "token",
          "expectedRegionEpoch",
          "initializationOperationId",
          "preparationDigest",
          "leaseTtlMs",
          "mayCreateFromAbsence");
  private static final String BRIDGE_SCHEMA = "gs_region_binding_bridge/v1";
  private static final List<String> BRIDGE_ARGUMENTS =
      List.of(
          "schema",
          "action",
          "leaseToken",
          "expectedRegionEpoch",
          "sessionId",
          "bindingGeneration");
  private static final List<RedisScriptDescriptor.KeySpec> BRIDGE_KEYS =
      List.of(
          new RedisScriptDescriptor.KeySpec(
              "regionLease",
              "tick-executor-lease:",
              RedisScriptDescriptor.HashTagDeclaration.REQUIRED),
          new RedisScriptDescriptor.KeySpec(
              "regionMeta", "tick:", RedisScriptDescriptor.HashTagDeclaration.REQUIRED),
          new RedisScriptDescriptor.KeySpec(
              "regionSessionBinding", "tick:", RedisScriptDescriptor.HashTagDeclaration.REQUIRED));

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

  TimelineAbsenceProof requireTimelineAbsent(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(incarnation, "incarnation");
    if (!CanonicalGameplayRegionIncarnationSource.RegionScope.from(source)
        .equals(incarnation.scope())) {
      throw unavailable();
    }
    long outcome =
        executeTimeline(
            source,
            1L,
            UUID.randomUUID().toString(),
            "PROBE",
            UUID.randomUUID(),
            incarnation.digest(),
            1L,
            false);
    if (outcome != 1L) throw unavailable();
    return new TimelineAbsenceProof(
        source,
        incarnation.scopeBytes(),
        incarnation.canonicalBytes(),
        absenceProofDigest(source, incarnation));
  }

  LeaseGrant initializeOrResumeTimeline(
      CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation preparation,
      CanonicalGameplayRegionRuntimeStatusRepository.InitialInitializationPermit permit,
      Duration leaseTtl,
      RuntimeIdentity identity) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(preparation, "preparation");
    long ttlMillis = ttlMillis(leaseTtl);
    String ownerService = requireOwnerService(identity);
    String ownerInstance = requireOwnerInstance(identity);
    boolean mayCreate = permit != null;
    if (mayCreate
        && (!permit.operationId().equals(preparation.operationId())
            || !permit.sourceDigest().equals(preparation.sourceDigest()))) {
      throw unavailable();
    }
    LeaseGrant grant =
        new LeaseGrant(
            preparation.source(),
            preparation.regionEpoch(),
            UUID.randomUUID().toString(),
            ownerService,
            ownerInstance);
    long outcome =
        executeTimeline(
            grant,
            "INITIALIZE",
            preparation.operationId(),
            preparation.sourceDigest(),
            ttlMillis,
            mayCreate);
    if (outcome != 1L && outcome != 2L) throw unavailable();
    return grant;
  }

  void verifyInitializedTimeline(
      LeaseGrant grant,
      CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation preparation) {
    requireNoAmbientTransaction();
    if (!grant.source.equals(preparation.source())
        || grant.regionEpoch != preparation.regionEpoch()) {
      throw unavailable();
    }
    long outcome =
        executeTimeline(
            grant, "VERIFY", preparation.operationId(), preparation.sourceDigest(), 1L, false);
    if (outcome != 3L) throw unavailable();
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

  private long executeTimeline(
      LeaseGrant grant,
      String action,
      UUID operationId,
      String sourceDigest,
      long ttlMillis,
      boolean mayCreate) {
    return executeTimeline(
        grant.source,
        grant.regionEpoch,
        grant.token,
        action,
        operationId,
        sourceDigest,
        ttlMillis,
        mayCreate);
  }

  private long executeTimeline(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      long regionEpoch,
      String token,
      String action,
      UUID operationId,
      String sourceDigest,
      long ttlMillis,
      boolean mayCreate) {
    requireNoAmbientTransaction();
    RedisScriptDescriptor descriptor =
        requireDescriptor(
            CanonicalGameplayRegionLeaseRedisScriptContribution.TIMELINE_INITIALIZE_ID,
            CanonicalGameplayRegionLeaseRedisScriptContribution.TIMELINE_INITIALIZE_PATH,
            TIMELINE_ARGUMENTS);
    byte[] script = scriptSource(descriptor);
    byte[][] keys =
        new byte[][] {
          ascii(
              CanonicalGameplayRegionRedisKeys.lease(
                  source.tenantId(), source.gameInstanceId(), source.regionId())),
          ascii(
              CanonicalGameplayRegionRedisKeys.meta(
                  source.tenantId(), source.gameInstanceId(), source.regionId()))
        };
    byte[][] arguments =
        new byte[][] {
          ascii(TIMELINE_SCHEMA),
          ascii(action),
          ascii(token),
          ascii(Long.toString(regionEpoch)),
          ascii(operationId.toString()),
          ascii(sourceDigest),
          ascii(Long.toString(ttlMillis)),
          ascii(mayCreate ? "1" : "0")
        };
    return executeInteger(script, keys, arguments);
  }

  private static String absenceProofDigest(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation) {
    return digest(
        "SHA-256",
        concat(
            ascii("firemud.gs.region-timeline-absent/v1"),
            incarnation.scopeBytes(),
            incarnation.canonicalBytes(),
            ascii(
                CanonicalGameplayRegionRedisKeys.tenantRegionTag(
                    source.tenantId(), source.gameInstanceId(), source.regionId()))));
  }

  private static byte[] concat(byte[]... values) {
    int length = 0;
    for (byte[] value : values) length = Math.addExact(length, value.length);
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] value : values) {
      System.arraycopy(value, 0, result, offset, value.length);
      offset += value.length;
    }
    return result;
  }

  /** Applies one exact first-NEW tuple under the same acquired grant and durable owner fence. */
  void applySessionBinding(
      LeaseGrant grant,
      CanonicalGameplayRegionRuntimeStatus durableOwner,
      net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId,
      String sessionId,
      BigInteger bindingGeneration) {
    long outcome =
        executeBinding(grant, durableOwner, runtimeEntityId, sessionId, bindingGeneration, "APPLY");
    if (outcome != 1L && outcome != 2L) throw unavailable();
  }

  /** Reads and privately binds the exact physical preimage under the live lease and epoch. */
  BindingPreimage probeSessionBinding(
      LeaseGrant grant,
      CanonicalGameplayRegionRuntimeStatus durableOwner,
      net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId,
      String sessionId,
      BigInteger bindingGeneration) {
    long outcome =
        executeBinding(grant, durableOwner, runtimeEntityId, sessionId, bindingGeneration, "PROBE");
    if (outcome != 4L && outcome != 5L) throw unavailable();
    return new BindingPreimage(
        runtimeEntityId,
        sessionId,
        bindingGeneration,
        durableOwner.regionEpoch(),
        durableOwner.executorFence(),
        durableOwner.leaseTokenCorrelation(),
        outcome == 4L ? PreimageState.ABSENT : PreimageState.EXACT);
  }

  /** Independently reads exact region-local state under the same live grant and durable fence. */
  BindingReadback readBackSessionBinding(
      LeaseGrant grant,
      CanonicalGameplayRegionRuntimeStatus durableOwner,
      net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId,
      String sessionId,
      BigInteger bindingGeneration) {
    long outcome =
        executeBinding(
            grant, durableOwner, runtimeEntityId, sessionId, bindingGeneration, "READBACK");
    if (outcome != 3L) throw unavailable();
    return new BindingReadback(
        runtimeEntityId,
        sessionId,
        bindingGeneration,
        durableOwner.regionEpoch(),
        durableOwner.executorFence(),
        durableOwner.leaseTokenCorrelation(),
        CanonicalGameplayRegionBindingBridgeProofs.redisReadbackDigest(
            durableOwner, runtimeEntityId, sessionId, bindingGeneration.toString()));
  }

  private long executeBinding(
      LeaseGrant grant,
      CanonicalGameplayRegionRuntimeStatus durableOwner,
      net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId,
      String sessionId,
      BigInteger bindingGeneration,
      String action) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(grant, "grant");
    Objects.requireNonNull(durableOwner, "durableOwner");
    Objects.requireNonNull(runtimeEntityId, "runtimeEntityId");
    Objects.requireNonNull(bindingGeneration, "bindingGeneration");
    if (bindingGeneration.signum() <= 0
        || bindingGeneration.toString().length() > 128
        || sessionId == null
        || !sessionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        || !"APPLY".equals(action) && !"READBACK".equals(action) && !"PROBE".equals(action)) {
      throw unavailable();
    }
    requireGrantBackedStatus(grant, durableOwner, runtimeEntityId);
    RedisScriptDescriptor descriptor = requireBridgeDescriptor();
    byte[] script = scriptSource(descriptor);
    byte[][] keys =
        new byte[][] {
          ascii(grant.key()),
          ascii(
              CanonicalGameplayRegionRedisKeys.meta(
                  grant.source.tenantId(), grant.source.gameInstanceId(), grant.source.regionId())),
          ascii(
              CanonicalGameplayRegionRedisKeys.sessionBinding(
                  grant.source.tenantId(),
                  grant.source.gameInstanceId(),
                  grant.source.regionId(),
                  runtimeEntityId.value()))
        };
    byte[][] arguments =
        new byte[][] {
          ascii(BRIDGE_SCHEMA),
          ascii(action),
          ascii(grant.token),
          ascii(Long.toString(grant.regionEpoch)),
          ascii(sessionId),
          ascii(bindingGeneration.toString())
        };
    return executeInteger(script, keys, arguments);
  }

  private static void requireGrantBackedStatus(
      LeaseGrant grant,
      CanonicalGameplayRegionRuntimeStatus status,
      net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId) {
    if (!status.tenantId().equals(grant.source.tenantId())
        || !status.gameInstanceId().equals(grant.source.gameInstanceId())
        || !status.canonicalRegionId().equals(grant.source.canonicalRegionId())
        || !status.regionId().equals(grant.source.regionId())
        || status.lifecycleEpoch() != grant.source.lifecycleEpoch()
        || status.lifecycleRowVersion() != grant.source.lifecycleRowVersion()
        || !status.lifecycleRequest().equals(grant.source.lifecycleRequest())
        || !status.captureId().equals(grant.source.captureId())
        || !status.graphSha256().equals(grant.source.graphSha256())
        || !status.preparationInputDigest().equals(grant.source.preparationInputDigest())
        || !status
            .tenantRegionTag()
            .equals(
                CanonicalGameplayRegionRedisKeys.tenantRegionTag(
                    grant.source.tenantId(),
                    grant.source.gameInstanceId(),
                    grant.source.regionId()))
        || status.regionEpoch() != grant.regionEpoch
        || !status.leaseTokenCorrelation().equals(grant.tokenCorrelation())
        || !status.ownerService().equals(grant.ownerService)
        || !status.ownerInstanceId().equals(grant.ownerInstanceId)
        || status.paused()
        || !runtimeEntityId.tenantId().equals(grant.source.tenantId())
        || !runtimeEntityId.gameInstanceId().equals(grant.source.gameInstanceId().toString())) {
      throw unavailable();
    }
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
    byte[][] keys =
        new byte[][] {
          ascii(grant.key()),
          ascii(
              CanonicalGameplayRegionRedisKeys.meta(
                  grant.source.tenantId(), grant.source.gameInstanceId(), grant.source.regionId()))
        };
    byte[][] arguments = grant.arguments(includeTtl ? ttlMillis : null);
    if (arguments.length != expectedArguments.size()) throw unavailable();
    return executeInteger(source, keys, arguments);
  }

  private long executeInteger(byte[] script, byte[][] keys, byte[][] arguments) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = connection.async();
      requirePrincipal(await(commands.aclWhoami()));
      String loadedSha = await(commands.scriptLoad(script));
      if (!digest("SHA-1", script).equals(loadedSha)) throw unavailable();
      Long result = await(commands.evalsha(loadedSha, ScriptOutputType.INTEGER, keys, arguments));
      if (result == null) throw unavailable();
      return result;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private RedisScriptDescriptor requireBridgeDescriptor() {
    try {
      RedisScriptDescriptor descriptor =
          catalog.require(CanonicalGameplayRegionBindingBridgeRedisScriptContribution.SCRIPT_ID);
      if (!CanonicalGameplayRegionBindingBridgeRedisScriptContribution.SCRIPT_ID.equals(
              descriptor.scriptId())
          || !CanonicalGameplayRegionBindingBridgeRedisScriptContribution.SCRIPT_PATH.equals(
              descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || descriptor.category() != RedisScriptDescriptor.ScriptCategory.TICK_LOCK
          || !BRIDGE_KEYS.equals(descriptor.keys())
          || !BRIDGE_ARGUMENTS.equals(
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
      if (ttlMillis == null) {
        return new byte[][] {ascii(SCHEMA), ascii(token), ascii(Long.toString(regionEpoch))};
      }
      return new byte[][] {
        ascii(SCHEMA), ascii(token), ascii(Long.toString(regionEpoch)), ascii(ttlMillis.toString())
      };
    }

    String key() {
      return key;
    }
  }

  /** Private result of the same-slot probe that observed no region meta or executor lease. */
  static final class TimelineAbsenceProof {
    private final CanonicalGameplayRegionRuntimeStatusRepository.Source source;
    private final byte[] incarnationScopeBytes;
    private final byte[] incarnationSourceBytes;
    private final String proofDigest;

    private TimelineAbsenceProof(
        CanonicalGameplayRegionRuntimeStatusRepository.Source source,
        byte[] incarnationScopeBytes,
        byte[] incarnationSourceBytes,
        String proofDigest) {
      this.source = Objects.requireNonNull(source, "source");
      this.incarnationScopeBytes = incarnationScopeBytes.clone();
      this.incarnationSourceBytes = incarnationSourceBytes.clone();
      this.proofDigest = Objects.requireNonNull(proofDigest, "proofDigest");
    }

    boolean matches(
        CanonicalGameplayRegionRuntimeStatusRepository.Source expectedSource,
        CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation) {
      return source.equals(expectedSource)
          && java.util.Arrays.equals(incarnationScopeBytes, incarnation.scopeBytes())
          && java.util.Arrays.equals(incarnationSourceBytes, incarnation.canonicalBytes())
          && proofDigest.equals(absenceProofDigest(expectedSource, incarnation));
    }

    String digest() {
      return proofDigest;
    }
  }

  /** Private-construction proof that a second region Lua call read the exact live tuple. */
  static final class BindingReadback {
    private final net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId;
    private final String sessionId;
    private final BigInteger bindingGeneration;
    private final long regionEpoch;
    private final BigInteger executorFence;
    private final String leaseTokenCorrelation;
    private final String digest;

    private BindingReadback(
        net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId,
        String sessionId,
        BigInteger bindingGeneration,
        long regionEpoch,
        BigInteger executorFence,
        String leaseTokenCorrelation,
        String digest) {
      this.runtimeEntityId = runtimeEntityId;
      this.sessionId = sessionId;
      this.bindingGeneration = bindingGeneration;
      this.regionEpoch = regionEpoch;
      this.executorFence = executorFence;
      this.leaseTokenCorrelation = leaseTokenCorrelation;
      this.digest = digest;
    }

    net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId() {
      return runtimeEntityId;
    }

    String sessionId() {
      return sessionId;
    }

    BigInteger bindingGeneration() {
      return bindingGeneration;
    }

    long regionEpoch() {
      return regionEpoch;
    }

    BigInteger executorFence() {
      return executorFence;
    }

    String leaseTokenCorrelation() {
      return leaseTokenCorrelation;
    }

    String digest() {
      return digest;
    }
  }

  /** Private-construction result of a lease/epoch-checked read of the exact key preimage. */
  static final class BindingPreimage {
    private final net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId;
    private final String sessionId;
    private final BigInteger bindingGeneration;
    private final long regionEpoch;
    private final BigInteger executorFence;
    private final String leaseTokenCorrelation;
    private final PreimageState state;

    private BindingPreimage(
        net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId,
        String sessionId,
        BigInteger bindingGeneration,
        long regionEpoch,
        BigInteger executorFence,
        String leaseTokenCorrelation,
        PreimageState state) {
      this.runtimeEntityId = runtimeEntityId;
      this.sessionId = sessionId;
      this.bindingGeneration = bindingGeneration;
      this.regionEpoch = regionEpoch;
      this.executorFence = executorFence;
      this.leaseTokenCorrelation = leaseTokenCorrelation;
      this.state = state;
    }

    net.firedevops.firemud.gamesession.client.CanonicalRuntimeEntityId runtimeEntityId() {
      return runtimeEntityId;
    }

    String sessionId() {
      return sessionId;
    }

    BigInteger bindingGeneration() {
      return bindingGeneration;
    }

    long regionEpoch() {
      return regionEpoch;
    }

    BigInteger executorFence() {
      return executorFence;
    }

    String leaseTokenCorrelation() {
      return leaseTokenCorrelation;
    }

    PreimageState state() {
      return state;
    }
  }

  enum PreimageState {
    ABSENT,
    EXACT
  }
}
