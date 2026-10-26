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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manual one-key physical owner for an SQL-issued first-NEW issuer release successor. */
public final class CanonicalGameplayIssuerBindingLocalReleaseRedisOwner {
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
          new RedisScriptDescriptor.ArgumentSpec("baselineFieldCountAndExactFieldValuePairs"),
          new RedisScriptDescriptor.ArgumentSpec(
              "registeredLocalOperationCountAndExactOperationTupleAndCurrentPhase"),
          new RedisScriptDescriptor.ArgumentSpec(
              "originalAndReleasedMemberOperationAndCapacityProof"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> EXPECTED_OUTCOMES =
      List.of(
          new RedisScriptDescriptor.OutcomeSpec(
              "RELEASED_CAPACITY_PROJECTED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "RELEASED_MEMBER_REPLAY",
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
  private final CanonicalGameplayIssuerBindingLocalProjectionRepository projectionRepository;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentStateSource;

  public CanonicalGameplayIssuerBindingLocalReleaseRedisOwner(
      RedisClient redisClient,
      RedisScriptCatalog catalog,
      CanonicalGameplayIssuerBindingLocalProjectionRepository projectionRepository,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentStateSource) {
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Redis client is required");
    this.catalog = Objects.requireNonNull(catalog, "Manual Redis script catalog is required");
    this.projectionRepository =
        Objects.requireNonNull(projectionRepository, "Actual SQL projection reader is required");
    this.currentStateSource =
        Objects.requireNonNull(currentStateSource, "Fresh issuer source is required");
  }

  /** Applies the retained SQL postimage, then proves it through an independent Redis connection. */
  public Readback releaseAndRead(CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    Objects.requireNonNull(operation, "SQL-issued issuer release is required");
    try {
      requireNoAmbientTransaction();
      if (operation.status()
          == CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED) {
        CanonicalGameplayIssuerPartitionMutation mutation = operation.physicalMutation();
        if (mutation.status() != CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED
            || !Arrays.equals(
                mutation.exactReadbackFieldSetBytes(),
                operation.exactPhysicalReadbackFieldSetBytes())) {
          throw unavailable("Committed issuer release lacks its original physical mutation proof");
        }
        return new Readback(
            operation.operationId(), 2L, operation.exactPhysicalReadbackFieldSetBytes());
      }
      var original = operation.originalOperation();
      var work = projectionRepository.readPreparedPartitionWork(original);
      CanonicalGameplayIssuerPartitionMutation mutation = operation.physicalMutation();
      if (mutation.status()
              != CanonicalGameplayIssuerPartitionMutation.Status.PENDING_PHYSICAL_PROOF
          || !mutation.mutationId().equals(work.pendingMutationId())
          || !mutation.expectedRevision().equals(work.currentRevision())) {
        throw unavailable("Original release no longer owns its prepared partition revision");
      }
      requireFreshCurrentState(work);
      RedisScriptDescriptor descriptor = requireDescriptor();
      byte[] script = scriptSource(descriptor);
      byte[] key = exactPartitionKey(work);
      long outcome = writeOneKey(script, key, scriptArguments(work, operation));
      if (outcome != 1 && outcome != 2) {
        throw unavailable("Issuer local release Lua refused the exact prepared partition");
      }
      byte[] readback = readExactPhysicalState(key, work, operation);
      requireFreshCurrentState(work);
      projectionRepository.requireStillCurrent(work);
      return new Readback(operation.operationId(), outcome, readback);
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().contains("Issuer binding-local release is unavailable")) {
        throw state;
      }
      throw unavailable(
          "Physical issuer release did not obtain exact source and readback proof", failure);
    }
  }

  private long writeOneKey(byte[] script, byte[] key, byte[][] arguments) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      String sha = await(commands.scriptLoad(script));
      if (!digest("SHA-1", script).equals(sha))
        throw unavailable("Redis loaded a different script");
      Long reply =
          await(commands.evalsha(sha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments));
      if (reply == null) throw unavailable("Redis returned no release result");
      return reply;
    } catch (RuntimeException failure) {
      throw unavailable("One-key issuer release command failed", failure);
    }
  }

  private byte[] readExactPhysicalState(
      byte[] key,
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work,
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      if (!"hash".equals(await(commands.type(key)))
          || !Long.valueOf(-1L).equals(await(commands.pttl(key)))) {
        throw unavailable("Issuer partition type or TTL is not canonical");
      }
      Long count = await(commands.hlen(key));
      int maxFields = CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_LOCAL_HASH_FIELDS;
      if (count == null || count < 1 || count > maxFields)
        throw unavailable("Issuer partition field count is invalid");
      Map<byte[], byte[]> raw = await(commands.hgetall(key));
      if (raw == null || raw.size() != count)
        throw unavailable("Issuer partition readback is incomplete");
      Map<String, byte[]> actual = new HashMap<>();
      long bytes = 0;
      for (Map.Entry<byte[], byte[]> field : raw.entrySet()) {
        byte[] name = Objects.requireNonNull(field.getKey(), "Redis field");
        byte[] value = Objects.requireNonNull(field.getValue(), "Redis value");
        bytes = Math.addExact(bytes, name.length + (long) value.length);
        if (bytes > CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_TOTAL_HASH_BYTES) {
          throw unavailable("Issuer partition readback exceeds its bounded field set");
        }
        String encodedName = Base64.getEncoder().encodeToString(name);
        if (actual.putIfAbsent(encodedName, value.clone()) != null) {
          throw unavailable("Issuer partition readback repeats a physical field");
        }
      }
      Map<String, byte[]> baseline = work.baselineFields();
      Map<String, byte[]> allowed = work.allowedFields();
      byte[] physicalRevision =
          actual.get(
              b64(
                  CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD.getBytes(
                      StandardCharsets.US_ASCII)));
      if (!Arrays.equals(
          physicalRevision,
          operation
              .physicalMutation()
              .successorRevision()
              .toString()
              .getBytes(StandardCharsets.US_ASCII))) {
        throw unavailable("Issuer release readback lacks its exact successor revision");
      }
      for (Map.Entry<String, byte[]> field : baseline.entrySet()) {
        byte[] value = actual.get(b64(field.getKey().getBytes(StandardCharsets.UTF_8)));
        if (value == null || !Arrays.equals(value, field.getValue())) {
          throw unavailable("Exact installed issuer baseline field changed");
        }
      }
      var target = operation.releasedMemberValue();
      String capacityField =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              target.reservation().reservationId());
      String bindingField = new String(target.owner().bindingRef().bytes(), StandardCharsets.UTF_8);
      byte[] targetCapacity = actual.get(b64(capacityField.getBytes(StandardCharsets.US_ASCII)));
      byte[] targetBinding = actual.get(b64(bindingField.getBytes(StandardCharsets.UTF_8)));
      if (!Arrays.equals(targetCapacity, operation.exactReleasedMemberValueBytes())
          || targetBinding != null) {
        throw unavailable("Exact RELEASED capacity field or binding-field removal is absent");
      }
      for (Map.Entry<String, byte[]> field : actual.entrySet()) {
        String decoded = decodeUtf8(Base64.getDecoder().decode(field.getKey()));
        if (decoded.equals(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD)) {
          continue;
        }
        if (decoded.equals(capacityField)) continue;
        byte[] expected = allowed.get(decoded);
        if (expected == null || !Arrays.equals(expected, field.getValue())) {
          throw unavailable("Issuer partition contains an unregistered or changed field");
        }
      }
      for (var entry : work.localEntries()) {
        if (!entry
                .memberValue()
                .reservation()
                .partitionId()
                .equals(operation.releasedMemberValue().reservation().partitionId())
            || entry.registeredOperationId().equals(operation.originalOperationId())) continue;
        String localCapacityField =
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                entry.memberValue().reservation().reservationId());
        byte[] localCapacity =
            actual.get(b64(localCapacityField.getBytes(StandardCharsets.US_ASCII)));
        if (entry.capacityMustBePresent()
            && !Arrays.equals(localCapacity, entry.exactMemberValueBytes())) {
          throw unavailable("A committed local capacity field is missing or changed");
        }
        String localBindingField =
            new String(entry.memberValue().owner().bindingRef().bytes(), StandardCharsets.UTF_8);
        byte[] localBinding = actual.get(b64(localBindingField.getBytes(StandardCharsets.UTF_8)));
        if (entry.bindingMustBePresent()
            && !Arrays.equals(localBinding, entry.exactMemberValueBytes())) {
          throw unavailable("A committed local binding field is missing or changed");
        }
      }
      int physicalCapacity = 0;
      int physicalReservations = 0;
      int physicalBindings = 0;
      for (Map.Entry<String, byte[]> field : actual.entrySet()) {
        String decoded = decodeUtf8(Base64.getDecoder().decode(field.getKey()));
        if (decoded.equals(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD)) {
          continue;
        } else if (decoded.startsWith(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.RESERVATION_FIELD_PREFIX)) {
          var value = CanonicalGameplayIssuerMemberV2Codec.decode(field.getValue());
          physicalReservations++;
          if (value.reservation().lifecycle()
              != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED) {
            physicalCapacity++;
          }
        } else if (!decoded.equals(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD)) {
          CanonicalGameplayIssuerMemberV2Codec.decode(field.getValue());
          physicalBindings++;
        }
      }
      int baselineReservations = work.baselineProof().physicalReservationCount();
      int baselineBindings = work.baselineProof().physicalBindingCount();
      int registeredReservations =
          Math.toIntExact(
              work.localEntries().stream()
                  .filter(
                      entry ->
                          entry
                              .memberValue()
                              .reservation()
                              .partitionId()
                              .equals(operation.releasedMemberValue().reservation().partitionId()))
                  .count());
      int registeredBindings =
          Math.toIntExact(
              work.localEntries().stream()
                  .filter(
                      entry ->
                          entry
                                  .memberValue()
                                  .reservation()
                                  .partitionId()
                                  .equals(
                                      operation.releasedMemberValue().reservation().partitionId())
                              && entry.bindingPresent())
                  .count());
      if (BigInteger.valueOf(physicalCapacity).compareTo(work.anchor().partitionCapacity()) > 0
          || BigInteger.valueOf(physicalCapacity).compareTo(work.partitionCapacity()) > 0
          || physicalReservations < baselineReservations + 1
          || physicalReservations > baselineReservations + registeredReservations
          || physicalBindings < baselineBindings
          || physicalBindings > baselineBindings + registeredBindings
          || work.aggregateCapacity().compareTo(work.aggregateBound()) > 0) {
        throw unavailable("Issuer release readback violates physical or SQL capacity bounds");
      }
      return CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(
          decodePhysicalMap(actual));
    } catch (RuntimeException failure) {
      throw unavailable("Independent issuer release readback was not exact", failure);
    }
  }

  private static byte[][] scriptArguments(
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work,
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    var anchor = work.anchor();
    List<byte[]> args = new ArrayList<>();
    args.add(ascii(anchor.issuerId()));
    args.add(ascii(operation.releasedMemberValue().reservation().partitionId().toString()));
    args.add(ascii(anchor.partitionCount().toString()));
    args.add(ascii(anchor.partitionCapacity().toString()));
    Map<String, byte[]> baseline = work.baselineFields();
    List<Map.Entry<String, byte[]>> sortedBaseline =
        baseline.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    args.add(ascii(Integer.toString(sortedBaseline.size())));
    for (Map.Entry<String, byte[]> field : sortedBaseline) {
      args.add(field.getKey().getBytes(StandardCharsets.UTF_8));
      args.add(field.getValue());
    }
    List<CanonicalGameplayIssuerBindingLocalProjectionRepository.LocalEntry> entries =
        work.localEntries().stream()
            .filter(
                entry ->
                    entry
                        .memberValue()
                        .reservation()
                        .partitionId()
                        .equals(operation.releasedMemberValue().reservation().partitionId()))
            .sorted(java.util.Comparator.comparing(entry -> entry.operationId().toString()))
            .toList();
    args.add(ascii(Integer.toString(entries.size())));
    for (var entry : entries) {
      args.add(ascii(entry.operationId().toString()));
      args.add(entry.memberValue().owner().bindingRef().bytes());
      args.add(ascii(entry.memberValue().reservation().reservationId().toString()));
      args.add(entry.exactMemberValueBytes());
      args.add(ascii(entry.bindingPresent() ? "1" : "0"));
      args.add(ascii(entry.countsTowardCapacity() ? "1" : "0"));
      args.add(ascii(entry.capacityMustBePresent() ? "1" : "0"));
      args.add(ascii(entry.bindingMustBePresent() ? "1" : "0"));
    }
    args.add(ascii(operation.originalOperation().operationId().toString()));
    args.add(ascii(operation.operationId().toString()));
    args.add(ascii(operation.releasedMemberValue().reservation().reservationId().toString()));
    args.add(operation.releasedMemberValue().owner().bindingRef().bytes());
    args.add(operation.exactOriginalMemberValueBytes());
    args.add(operation.exactReleasedMemberValueBytes());
    args.add(ascii(operation.sourceInventoryRevision().toString()));
    args.add(ascii(work.partitionCapacity().toString()));
    args.add(ascii(work.aggregateCapacity().toString()));
    args.add(ascii(work.aggregateBound().toString()));
    var mutation = operation.physicalMutation();
    args.add(ascii(mutation.expectedRevision().toString()));
    args.add(ascii(mutation.successorRevision().toString()));
    return args.toArray(byte[][]::new);
  }

  private static byte[] exactPartitionKey(
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work) {
    String key = CanonicalGameplayIssuerRedisKeys.forMember(work.targetOperation().memberValue());
    if (!key.startsWith(KEY_PREFIX) || key.length() > 512)
      throw unavailable("Issuer key is not canonical");
    return key.getBytes(StandardCharsets.US_ASCII);
  }

  private void requireFreshCurrentState(
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work) {
    requireNoAmbientTransaction();
    var state = currentStateSource.readFreshCurrentState();
    requireNoAmbientTransaction();
    var checkpoint = state == null ? null : state.accountCheckpoint();
    var anchor = work.anchor();
    if (checkpoint == null
        || !checkpoint.issuerId().equals(anchor.issuerId())
        || !checkpoint.issuerAuthGeneration().equals(anchor.issuerAuthGeneration())
        || !Arrays.equals(checkpoint.exactCheckpointBytes(), anchor.sourceCheckpointBytes())
        || !checkpoint.checkpointDigest().equals(anchor.sourceCheckpointDigest())
        || !Arrays.equals(checkpoint.latestIssuerEventBytes(), anchor.sourceEventBytes())
        || !Objects.equals(checkpoint.latestIssuerEventDigest(), anchor.sourceEventDigest())
        || !Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(state.incarnation()),
            anchor.incarnationBytes())) {
      throw unavailable("Issuer source checkpoint or incarnation changed");
    }
  }

  private RedisScriptDescriptor requireDescriptor() {
    RedisScriptDescriptor descriptor;
    try {
      descriptor =
          catalog.require(
              CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution.SCRIPT_ID);
    } catch (RuntimeException missing) {
      throw unavailable("Manual issuer release descriptor is not installed", missing);
    }
    if (!CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution.SCRIPT_ID.equals(
            descriptor.scriptId())
        || !CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution.RESOURCE_PATH.equals(
            descriptor.resourcePath())
        || !"game-session-service".equals(descriptor.owner())
        || !PRINCIPAL.equals(descriptor.principal())
        || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
        || !EXPECTED_KEYS.equals(descriptor.keys())
        || !EXPECTED_ARGUMENTS.equals(descriptor.arguments())
        || descriptor.category() != RedisScriptDescriptor.ScriptCategory.SESSION_ISSUER_INDEX
        || !EXPECTED_OUTCOMES.equals(descriptor.outcomes())
        || descriptor.resetSensitivity() != RedisScriptDescriptor.ResetSensitivity.CLUSTER
        || descriptor.lossClass() != RedisScriptDescriptor.LossClass.EMPTY_REDIS_COLD_START_OR_RESET
        || descriptor.compatibilityLevel()
            != RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET
        || !descriptor
            .supportedCoexistence()
            .equals(List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "v2")))
        || descriptor.legacyPayloadShape() != null) {
      throw unavailable("Manual issuer release descriptor differs from its exact owner contract");
    }
    return descriptor;
  }

  private static byte[] scriptSource(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        CanonicalGameplayIssuerBindingLocalReleaseRedisOwner.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable("Issuer release Lua resource is missing");
      byte[] bytes = input.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (bytes.length == 0
          || bytes.length > MAX_SCRIPT_BYTES
          || !descriptor.sha256().equals(digest("SHA-256", bytes))) {
        throw unavailable("Issuer release Lua differs from its exact descriptor digest");
      }
      return bytes;
    } catch (IOException | RuntimeException failure) {
      throw unavailable("Issuer release Lua is unavailable", failure);
    }
  }

  private static Map<String, byte[]> decodePhysicalMap(Map<String, byte[]> encoded) {
    Map<String, byte[]> result = new HashMap<>();
    encoded.forEach(
        (field, value) -> result.put(decodeUtf8(Base64.getDecoder().decode(field)), value.clone()));
    return result;
  }

  private static String b64(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw unavailable("Issuer hash field is not valid UTF-8", malformed);
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static RedisAsyncCommands<byte[], byte[]> requireCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null || !connection.isOpen())
      throw unavailable("Redis connection is unavailable");
    return connection.async();
  }

  private static void requirePrincipal(String principal) {
    if (!PRINCIPAL.equals(principal))
      throw unavailable("Redis principal is not the exact coordination owner");
  }

  private static <T> T await(RedisFuture<T> future) {
    try {
      return future.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw unavailable("Redis issuer release command was interrupted", interrupted);
    } catch (ExecutionException | TimeoutException | RuntimeException failure) {
      future.cancel(true);
      throw unavailable("Redis issuer release command failed", failure);
    }
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception failure) {
      throw unavailable("Could not verify issuer release Lua digest", failure);
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw unavailable("Physical issuer release cannot run inside an ambient SQL transaction");
    }
  }

  private static IllegalStateException unavailable(String message) {
    return new IllegalStateException("Issuer binding-local release is unavailable: " + message);
  }

  private static IllegalStateException unavailable(String message, Throwable cause) {
    return new IllegalStateException(
        "Issuer binding-local release is unavailable: " + message, cause);
  }

  /**
   * Exact private physical readback; SQL completion must consume this value in a new transaction.
   */
  public static final class Readback {
    private final UUID operationId;
    private final long scriptOutcome;
    private final byte[] exactPhysicalFieldSetBytes;

    private Readback(UUID operationId, long scriptOutcome, byte[] exactPhysicalFieldSetBytes) {
      this.operationId = Objects.requireNonNull(operationId, "operationId");
      this.scriptOutcome = scriptOutcome;
      this.exactPhysicalFieldSetBytes = exactPhysicalFieldSetBytes.clone();
    }

    public UUID operationId() {
      return operationId;
    }

    public long scriptOutcome() {
      return scriptOutcome;
    }

    byte[] exactPhysicalFieldSetBytes() {
      return exactPhysicalFieldSetBytes.clone();
    }
  }
}
