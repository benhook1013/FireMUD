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
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Projects one exact prepared bootstrap partition to its canonical single Coordination Redis hash.
 * The result is physical readback evidence only; this owner does not install an anchor or admit.
 */
public final class CanonicalGameplayIssuerBootstrapPartitionRedisOwner {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String KEY_PREFIX = "session:game:index:issuer:";
  private static final int MAX_SCRIPT_BYTES = 128 * 1024;
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000;
  private static final List<RedisScriptDescriptor.KeySpec> EXPECTED_KEYS =
      List.of(
          new RedisScriptDescriptor.KeySpec(
              "issuerPartition", KEY_PREFIX, RedisScriptDescriptor.HashTagDeclaration.REQUIRED));
  private static final List<RedisScriptDescriptor.ArgumentSpec> EXPECTED_ARGUMENTS =
      List.of(
          new RedisScriptDescriptor.ArgumentSpec("issuerId"),
          new RedisScriptDescriptor.ArgumentSpec("partitionId"),
          new RedisScriptDescriptor.ArgumentSpec("partitionCount"),
          new RedisScriptDescriptor.ArgumentSpec("partitionCapacity"),
          new RedisScriptDescriptor.ArgumentSpec("exactBootstrapMetadata"),
          new RedisScriptDescriptor.ArgumentSpec("initialPhysicalMutationRevision"),
          new RedisScriptDescriptor.ArgumentSpec("memberOperationCount"),
          new RedisScriptDescriptor.ArgumentSpec(
              "repeatedBindingRefMemberCapacityFieldBindingPresence"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> EXPECTED_OUTCOMES =
      List.of(
          new RedisScriptDescriptor.OutcomeSpec(
              "PARTITION_BUILT",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "PARTITION_REPLAY",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "TYPE_TTL_OR_FIELDSET_REFUSED",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "INVALID_ARGUMENTS_OR_CAPACITY",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING));

  private final RedisClient redisClient;
  private final RedisScriptCatalog catalog;
  private final CurrentIssuerStateSource currentStateSource;

  /**
   * The Redis client, manual owner contribution catalog, and protected local source are explicit.
   */
  public CanonicalGameplayIssuerBootstrapPartitionRedisOwner(
      RedisClient redisClient,
      RedisScriptCatalog catalog,
      CurrentIssuerStateSource currentStateSource) {
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Redis client is required");
    this.catalog = Objects.requireNonNull(catalog, "Manual Redis script catalog is required");
    this.currentStateSource =
        Objects.requireNonNull(currentStateSource, "Fresh current issuer source is required");
  }

  /**
   * Writes or exactly replays the complete physical field set, then reads it back on a separate
   * connection. Fresh controlled current source checks bracket the remote phase; source or Redis
   * uncertainty returns no readback capability.
   */
  public Readback buildAndRead(CanonicalGameplayIssuerBootstrapPartitionWork work) {
    Objects.requireNonNull(work, "Prepared issuer partition work is required");
    try {
      requireNoAmbientTransaction();
      var physical = CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.expectedFields(work);
      requireFreshCurrentState(work);
      RedisScriptDescriptor descriptor = requireDescriptor();
      byte[] script = scriptSource(descriptor);
      byte[] key = exactPartitionKey(work);
      byte[][] arguments = scriptArguments(work, physical);
      long mutationResult = writeOneKey(script, key, arguments);
      if (mutationResult != 1L && mutationResult != 2L) throw unavailable();
      PhysicalReadback observed = readExactPhysicalState(key, physical);
      requireFreshCurrentState(work);
      return new Readback(work, observed, physical.plannedAggregateCapacityCount());
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().contains("partition projection is unavailable")) {
        throw state;
      }
      throw unavailable(failure);
    }
  }

  /**
   * Reads and verifies the exact current physical field set for an installed-anchor retry or
   * reader. This method never creates, repairs, or rebuilds a missing partition.
   */
  public Readback readExistingAndRead(CanonicalGameplayIssuerBootstrapPartitionWork work) {
    Objects.requireNonNull(work, "Prepared issuer partition work is required");
    try {
      requireNoAmbientTransaction();
      var physical = CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.expectedFields(work);
      requireFreshCurrentState(work);
      PhysicalReadback observed = readExactPhysicalState(exactPartitionKey(work), physical);
      requireFreshCurrentState(work);
      return new Readback(work, observed, physical.plannedAggregateCapacityCount());
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().contains("partition projection is unavailable")) {
        throw state;
      }
      throw unavailable(failure);
    }
  }

  private long writeOneKey(byte[] script, byte[] key, byte[][] arguments) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      String loadedSha = await(commands.scriptLoad(script));
      if (!digest("SHA-1", script).equals(loadedSha)) throw unavailable();
      Long reply =
          await(
              commands.evalsha(loadedSha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments));
      if (reply == null) throw unavailable();
      return reply;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private PhysicalReadback readExactPhysicalState(
      byte[] key,
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.PhysicalFieldSet expected) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      if (!"hash".equals(await(commands.type(key)))
          || !Long.valueOf(-1L).equals(await(commands.pttl(key)))) {
        throw unavailable();
      }
      Long length = await(commands.hlen(key));
      if (length == null
          || length < 0
          || length > CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_HASH_FIELDS) {
        throw unavailable();
      }
      Map<byte[], byte[]> raw = await(commands.hgetall(key));
      if (raw == null || raw.size() != length) throw unavailable();
      long totalBytes = 0;
      Map<String, byte[]> actual = new HashMap<>();
      for (Map.Entry<byte[], byte[]> entry : raw.entrySet()) {
        byte[] field = Objects.requireNonNull(entry.getKey(), "Redis field");
        byte[] value = Objects.requireNonNull(entry.getValue(), "Redis value");
        totalBytes += (long) field.length + value.length;
        if (totalBytes
            > CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_TOTAL_HASH_BYTES) {
          throw unavailable();
        }
        String encodedField = Base64.getEncoder().encodeToString(field);
        if (actual.putIfAbsent(encodedField, value.clone()) != null) throw unavailable();
      }
      Map<String, byte[]> expectedFields = expected.fields();
      if (actual.size() != expectedFields.size()) throw unavailable();
      for (Map.Entry<String, byte[]> entry : expectedFields.entrySet()) {
        byte[] redisField = entry.getKey().getBytes(StandardCharsets.UTF_8);
        byte[] observed = actual.get(Base64.getEncoder().encodeToString(redisField));
        if (!Arrays.equals(entry.getValue(), observed)) throw unavailable();
      }
      byte[] metadata =
          actual.get(
              Base64.getEncoder()
                  .encodeToString(
                      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD
                          .getBytes(StandardCharsets.US_ASCII)));
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.requireExactMetadata(
          metadata, expected.metadataBytes());

      int capacityCount = 0;
      int bindingCount = 0;
      for (Map.Entry<String, byte[]> entry : expectedFields.entrySet()) {
        String field = entry.getKey();
        if (field.startsWith(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.RESERVATION_FIELD_PREFIX)) {
          CanonicalGameplayIssuerMemberV2Value decoded =
              CanonicalGameplayIssuerMemberV2Codec.decode(
                  actual.get(
                      Base64.getEncoder()
                          .encodeToString(field.getBytes(StandardCharsets.US_ASCII))));
          if (!decoded.equals(CanonicalGameplayIssuerMemberV2Codec.decode(entry.getValue()))) {
            throw unavailable();
          }
          if (decoded.reservation().lifecycle()
              != net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation
                  .Lifecycle.RELEASED) {
            capacityCount++;
          }
        } else if (field.equals(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MUTATION_REVISION_FIELD)) {
          byte[] revision =
              actual.get(
                  Base64.getEncoder().encodeToString(field.getBytes(StandardCharsets.UTF_8)));
          if (!Arrays.equals(revision, entry.getValue())) {
            throw unavailable();
          }
        } else if (!field.equals(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD)) {
          CanonicalGameplayIssuerMemberV2Codec.decode(
              actual.get(
                  Base64.getEncoder().encodeToString(field.getBytes(StandardCharsets.UTF_8))));
          bindingCount++;
        }
      }
      if (capacityCount != expected.countedReservationCount()
          || bindingCount != expected.bindingCount()
          || expected.reservationCount() != expected.partitionReservationCount()) {
        throw unavailable();
      }
      return new PhysicalReadback(expectedFields.size(), capacityCount, bindingCount);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private void requireFreshCurrentState(CanonicalGameplayIssuerBootstrapPartitionWork work) {
    requireNoAmbientTransaction();
    CurrentIssuerState current = currentStateSource.readFreshCurrentState();
    requireNoAmbientTransaction();
    if (current == null
        || !sameCheckpoint(
            current.accountCheckpoint(), work.buildPlan().bootstrapIntent().sourceCheckpoint())
        || !current.incarnation().equals(work.incarnation())) {
      throw unavailable();
    }
  }

  private RedisScriptDescriptor requireDescriptor() {
    try {
      RedisScriptDescriptor descriptor =
          catalog.require(
              CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution.SCRIPT_ID);
      if (!CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution.SCRIPT_ID.equals(
              descriptor.scriptId())
          || !CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution.RESOURCE_PATH.equals(
              descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || !EXPECTED_KEYS.equals(descriptor.keys())
          || !EXPECTED_ARGUMENTS.equals(descriptor.arguments())
          || descriptor.category() != RedisScriptDescriptor.ScriptCategory.SESSION_ISSUER_INDEX
          || !EXPECTED_OUTCOMES.equals(descriptor.outcomes())
          || descriptor.resetSensitivity() != RedisScriptDescriptor.ResetSensitivity.CLUSTER
          || descriptor.lossClass()
              != RedisScriptDescriptor.LossClass.EMPTY_REDIS_COLD_START_OR_RESET
          || descriptor.compatibilityLevel()
              != RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET
          || !descriptor
              .supportedCoexistence()
              .equals(List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "v2")))
          || descriptor.legacyPayloadShape() != null) {
        throw unavailable();
      }
      return descriptor;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static byte[] scriptSource(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        CanonicalGameplayIssuerBootstrapPartitionRedisOwner.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (bytes.length == 0
          || bytes.length > MAX_SCRIPT_BYTES
          || !descriptor.sha256().equals(digest("SHA-256", bytes))) {
        throw unavailable();
      }
      return bytes;
    } catch (IOException | RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static byte[] exactPartitionKey(CanonicalGameplayIssuerBootstrapPartitionWork work) {
    String key =
        CanonicalGameplayIssuerRedisKeys.forPartition(
            work.buildPlan().bootstrapIntent().layoutDescriptor(), work.partitionId());
    if (!key.startsWith(KEY_PREFIX) || key.length() > 512) throw unavailable();
    return key.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[][] scriptArguments(
      CanonicalGameplayIssuerBootstrapPartitionWork work,
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.PhysicalFieldSet physical) {
    var intent = work.buildPlan().bootstrapIntent();
    byte[][] arguments = new byte[7 + work.memberOperations().size() * 4][];
    arguments[0] = ascii(intent.issuerId());
    arguments[1] = ascii(work.partitionId().toString());
    arguments[2] = ascii(intent.layoutDescriptor().partitionCount().toString());
    arguments[3] = ascii(intent.layoutDescriptor().partitionCapacity().toString());
    arguments[4] = physical.metadataBytes();
    arguments[5] = ascii(work.initialPhysicalRevision().toString());
    arguments[6] = ascii(Integer.toString(work.memberOperations().size()));
    int position = 7;
    for (CanonicalGameplayIssuerBootstrapMemberOperation operation : work.memberOperations()) {
      CanonicalGameplayIssuerMemberV2Value value = operation.expectedMemberValue();
      arguments[position++] = value.owner().bindingRef().bytes();
      byte[] exactValue = operation.exactMemberValueBytes();
      arguments[position++] = exactValue;
      arguments[position++] =
          ascii(
              CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                  value.reservation().reservationId()));
      arguments[position++] = ascii(operation.expectedBindingPresent() ? "1" : "0");
    }
    return arguments;
  }

  private static boolean sameCheckpoint(
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint current,
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint expected) {
    return current != null
        && expected != null
        && current.issuerId().equals(expected.issuerId())
        && current.issuerAuthGeneration().equals(expected.issuerAuthGeneration())
        && Arrays.equals(current.exactCheckpointBytes(), expected.exactCheckpointBytes())
        && current.checkpointDigest().equals(expected.checkpointDigest())
        && Arrays.equals(current.latestIssuerEventBytes(), expected.latestIssuerEventBytes())
        && Objects.equals(current.latestIssuerEventDigest(), expected.latestIssuerEventDigest());
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static RedisAsyncCommands<byte[], byte[]> requireCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null || !connection.isOpen()) throw unavailable();
    return connection.async();
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

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable();
    }
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception failure) {
      throw unavailable(failure);
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Issuer bootstrap partition projection is unavailable");
  }

  private static IllegalStateException unavailable(Throwable cause) {
    return new IllegalStateException("Issuer bootstrap partition projection is unavailable", cause);
  }

  /**
   * Fresh, trusted local read collaborator. No default implementation derives authority from work.
   */
  @FunctionalInterface
  public interface CurrentIssuerStateSource {
    CurrentIssuerState readFreshCurrentState();
  }

  /**
   * One current exact Account checkpoint and stable incarnation observation from the collaborator.
   */
  public record CurrentIssuerState(
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint accountCheckpoint,
      CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation) {
    public CurrentIssuerState {
      Objects.requireNonNull(accountCheckpoint, "accountCheckpoint");
      Objects.requireNonNull(incarnation, "incarnation");
    }
  }

  private record PhysicalReadback(int fieldCount, int countedReservationCount, int bindingCount) {}

  /**
   * Exact physical partition readback; it carries no global aggregate, acknowledgement, or OPEN
   * authority.
   */
  public static final class Readback {
    private final CanonicalGameplayIssuerBootstrapPartitionWork work;
    private final int physicalFieldCount;
    private final int physicalReservationCount;
    private final int physicalBindingCount;
    private final int physicalCountedCapacityCount;
    private final BigInteger plannedAggregateCapacityCount;

    private Readback(
        CanonicalGameplayIssuerBootstrapPartitionWork work,
        PhysicalReadback observed,
        BigInteger plannedAggregateCapacityCount) {
      this.work = Objects.requireNonNull(work, "work");
      this.physicalFieldCount = observed.fieldCount();
      this.physicalReservationCount = work.memberOperations().size();
      this.physicalBindingCount = observed.bindingCount();
      this.physicalCountedCapacityCount = observed.countedReservationCount();
      this.plannedAggregateCapacityCount = Objects.requireNonNull(plannedAggregateCapacityCount);
      if (observed.fieldCount() != 2 + physicalReservationCount + physicalBindingCount
          || BigInteger.valueOf(observed.countedReservationCount())
                  .compareTo(
                      work.buildPlan().bootstrapIntent().layoutDescriptor().partitionCapacity())
              > 0) {
        throw unavailable();
      }
    }

    public int physicalFieldCount() {
      return physicalFieldCount;
    }

    public int physicalPartitionReservationCount() {
      return physicalReservationCount;
    }

    public int physicalBindingCount() {
      return physicalBindingCount;
    }

    public int physicalCountedCapacityCount() {
      return physicalCountedCapacityCount;
    }

    /** Frozen complete-plan capacity count, distinct from this one partition's physical count. */
    public BigInteger plannedAggregateCapacityCount() {
      return plannedAggregateCapacityCount;
    }

    public CanonicalGameplayIssuerBootstrapPartitionWork work() {
      return work;
    }
  }
}
