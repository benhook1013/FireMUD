package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manual one-key owner for exact original-operation terminal capacity retirement. */
final class CanonicalGameplayIssuerBindingLocalRetirementRedisOwner {
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
          new RedisScriptDescriptor.ArgumentSpec("issuerIdPartitionLayoutAndExactBaselineFields"),
          new RedisScriptDescriptor.ArgumentSpec(
              "currentUnretiredLocalOperationCountAndExactFields"),
          new RedisScriptDescriptor.ArgumentSpec(
              "originalReleaseReceiptAndOpaquePhysicalRevisionCompareAndAdvance"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> EXPECTED_OUTCOMES =
      List.of(
          new RedisScriptDescriptor.OutcomeSpec(
              "TERMINAL_CAPACITY_RETIRED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "ORIGINAL_RETIREMENT_REPLAY",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "TYPE_TTL_OR_FIELDSET_REFUSED",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "INVALID_OR_STALE_REVISION_REFUSED",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING));

  private final RedisClient redisClient;
  private final RedisScriptCatalog catalog;
  private final CanonicalGameplayIssuerBindingLocalProjectionRepository projectionRepository;
  private final CanonicalGameplayIssuerBindingLocalRetirementOperationRepository
      retirementRepository;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentStateSource;

  CanonicalGameplayIssuerBindingLocalRetirementRedisOwner(
      RedisClient redisClient,
      RedisScriptCatalog catalog,
      CanonicalGameplayIssuerBindingLocalProjectionRepository projectionRepository,
      CanonicalGameplayIssuerBindingLocalRetirementOperationRepository retirementRepository,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentStateSource) {
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Redis client is required");
    this.catalog = Objects.requireNonNull(catalog, "Manual Redis script catalog is required");
    this.projectionRepository =
        Objects.requireNonNull(projectionRepository, "SQL current projection owner is required");
    this.retirementRepository =
        Objects.requireNonNull(retirementRepository, "SQL retirement owner is required");
    this.currentStateSource =
        Objects.requireNonNull(currentStateSource, "Fresh issuer source is required");
  }

  /** Retires one terminal capacity field and independently proves its exact absence and fence. */
  Readback retireAndRead(CanonicalGameplayIssuerBindingLocalRetirementOperation operation) {
    Objects.requireNonNull(operation, "SQL-issued original retirement is required");
    requireNoAmbientTransaction();
    if (operation.status()
        == CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED) {
      return new Readback(
          operation.operationId(), 2L, operation.exactAbsenceReadbackFieldSetBytes());
    }
    try {
      var release = operation.release();
      var work = projectionRepository.readPreparedPartitionWork(release.originalOperation());
      var mutation = operation.physicalMutation();
      if (mutation.status()
              != CanonicalGameplayIssuerPartitionMutation.Status.PENDING_PHYSICAL_PROOF
          || !mutation.mutationId().equals(work.pendingMutationId())
          || !mutation.expectedRevision().equals(work.currentRevision())) {
        throw unavailable("Original retirement no longer owns its prepared partition revision");
      }
      requireFreshCurrentState(work);
      RedisScriptDescriptor descriptor = requireDescriptor();
      byte[] script = scriptSource(descriptor);
      byte[] key = exactPartitionKey(work);
      long outcome = writeOneKey(script, key, scriptArguments(work, operation));
      if (outcome != 1 && outcome != 2) {
        throw unavailable(
            "Issuer retirement Lua refused the exact prepared terminal field: " + outcome);
      }
      byte[] readback = readExactPhysicalState(key, work, operation);
      requireFreshCurrentState(work);
      projectionRepository.requireStillCurrent(work);
      Readback proof = new Readback(operation.operationId(), outcome, readback);
      retirementRepository.completeWithReadback(operation, proof);
      return proof;
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().startsWith("Issuer binding-local retirement is unavailable")) {
        throw state;
      }
      throw unavailable(
          "Physical retirement did not obtain exact source and absence proof", failure);
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
      if (reply == null) throw unavailable("Redis returned no retirement result");
      return reply;
    } catch (RuntimeException failure) {
      throw unavailable("One-key issuer retirement command failed", failure);
    }
  }

  private byte[] readExactPhysicalState(
      byte[] key,
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work,
      CanonicalGameplayIssuerBindingLocalRetirementOperation operation) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      if (!"hash".equals(await(commands.type(key)))
          || !Long.valueOf(-1L).equals(await(commands.pttl(key)))) {
        throw unavailable("Issuer partition type or TTL is not canonical");
      }
      Long length = await(commands.hlen(key));
      int max = CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_LOCAL_HASH_FIELDS;
      if (length == null || length < 1 || length > max) {
        throw unavailable("Issuer retirement readback field count is outside its strict bound");
      }
      Map<byte[], byte[]> raw = await(commands.hgetall(key));
      if (raw == null || raw.size() != length)
        throw unavailable("Issuer retirement readback is incomplete");
      Map<String, byte[]> actual = new HashMap<>();
      long byteCount = 0;
      for (Map.Entry<byte[], byte[]> entry : raw.entrySet()) {
        byte[] field = Objects.requireNonNull(entry.getKey(), "Redis field");
        byte[] value = Objects.requireNonNull(entry.getValue(), "Redis value");
        byteCount = Math.addExact(byteCount, field.length + (long) value.length);
        if (byteCount
            > CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_TOTAL_HASH_BYTES) {
          throw unavailable("Issuer retirement readback exceeds its bounded bytes");
        }
        String encoded = Base64.getEncoder().encodeToString(field);
        if (actual.putIfAbsent(encoded, value.clone()) != null) {
          throw unavailable("Issuer retirement readback repeats a field");
        }
      }
      Map<String, byte[]> allowed = new HashMap<>(work.allowedFields());
      String capacityField =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              operation.release().releasedMemberValue().reservation().reservationId());
      if (allowed.containsKey(capacityField)) {
        allowed.remove(capacityField);
      } else {
        throw unavailable(
            "The current SQL projection does not contain the terminal target capacity");
      }
      byte[] revision =
          actual.get(
              b64(
                  CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD.getBytes(
                      StandardCharsets.US_ASCII)));
      if (!Arrays.equals(
              revision,
              operation
                  .physicalMutation()
                  .successorRevision()
                  .toString()
                  .getBytes(StandardCharsets.US_ASCII))
          || actual.size() != allowed.size() + 1) {
        throw unavailable(
            "Issuer retirement lacks the exact successor fence or current field count");
      }
      for (Map.Entry<String, byte[]> field : work.baselineFields().entrySet()) {
        byte[] value = actual.get(b64(field.getKey().getBytes(StandardCharsets.UTF_8)));
        if (value == null || !Arrays.equals(value, field.getValue())) {
          throw unavailable("Exact installed issuer baseline changed during retirement");
        }
      }
      for (Map.Entry<String, byte[]> field : actual.entrySet()) {
        String decoded = decodeUtf8(Base64.getDecoder().decode(field.getKey()));
        if (decoded.equals(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD))
          continue;
        byte[] expected = allowed.get(decoded);
        if (expected == null || !Arrays.equals(expected, field.getValue())) {
          throw unavailable("Issuer retirement readback contains a foreign or changed field");
        }
      }
      for (var entry : work.localEntries()) {
        if (!entry
            .memberValue()
            .reservation()
            .partitionId()
            .equals(operation.release().releasedMemberValue().reservation().partitionId()))
          continue;
        boolean retirementTarget =
            entry
                .memberValue()
                .reservation()
                .reservationId()
                .equals(operation.release().releasedMemberValue().reservation().reservationId());
        String localCapacityField =
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                entry.memberValue().reservation().reservationId());
        byte[] localCapacity =
            actual.get(b64(localCapacityField.getBytes(StandardCharsets.US_ASCII)));
        if (!retirementTarget
            && entry.capacityMustBePresent()
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
      if (actual.containsKey(b64(capacityField.getBytes(StandardCharsets.US_ASCII)))) {
        throw unavailable("Exact terminal capacity field remains after retirement");
      }
      return CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(
          decodePhysicalMap(actual));
    } catch (RuntimeException failure) {
      throw unavailable("Independent issuer retirement readback was not exact", failure);
    }
  }

  private static byte[][] scriptArguments(
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work,
      CanonicalGameplayIssuerBindingLocalRetirementOperation operation) {
    var anchor = work.anchor();
    List<byte[]> args = new ArrayList<>();
    args.add(ascii(anchor.issuerId()));
    args.add(
        ascii(operation.release().releasedMemberValue().reservation().partitionId().toString()));
    args.add(ascii(anchor.partitionCount().toString()));
    args.add(ascii(anchor.partitionCapacity().toString()));
    Map<String, byte[]> baseline = work.baselineFields();
    List<Map.Entry<String, byte[]>> sorted =
        baseline.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    args.add(ascii(Integer.toString(sorted.size())));
    for (Map.Entry<String, byte[]> field : sorted) {
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
                        .equals(
                            operation.release().releasedMemberValue().reservation().partitionId()))
            .sorted(Comparator.comparing(entry -> entry.operationId().toString()))
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
    var release = operation.release();
    var mutation = operation.physicalMutation();
    args.add(ascii(release.operationId().toString()));
    args.add(ascii(operation.operationId().toString()));
    args.add(ascii(release.releasedMemberValue().reservation().reservationId().toString()));
    args.add(release.releasedMemberValue().owner().bindingRef().bytes());
    args.add(release.exactReleasedMemberValueBytes());
    args.add(ascii(release.sourceInventoryRevision().toString()));
    args.add(ascii(release.completionInventoryRevision().toString()));
    args.add(ascii(work.partitionCapacity().toString()));
    args.add(ascii(work.aggregateCapacity().toString()));
    args.add(ascii(work.aggregateBound().toString()));
    args.add(ascii(mutation.expectedRevision().toString()));
    args.add(ascii(mutation.successorRevision().toString()));
    return args.toArray(byte[][]::new);
  }

  private byte[] exactPartitionKey(
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
              CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution.SCRIPT_ID);
    } catch (RuntimeException missing) {
      throw unavailable("Manual issuer retirement descriptor is not installed", missing);
    }
    if (!CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution.SCRIPT_ID.equals(
            descriptor.scriptId())
        || !CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution.RESOURCE_PATH
            .equals(descriptor.resourcePath())
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
      throw unavailable(
          "Manual issuer retirement descriptor differs from its exact owner contract");
    }
    return descriptor;
  }

  private static byte[] scriptSource(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        CanonicalGameplayIssuerBindingLocalRetirementRedisOwner.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable("Issuer retirement Lua resource is missing");
      byte[] bytes = input.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (bytes.length == 0
          || bytes.length > MAX_SCRIPT_BYTES
          || !descriptor.sha256().equals(digest("SHA-256", bytes))) {
        throw unavailable("Issuer retirement Lua differs from its exact descriptor digest");
      }
      return bytes;
    } catch (java.io.IOException failure) {
      throw unavailable("Issuer retirement Lua resource could not be read", failure);
    }
  }

  private static Map<String, byte[]> decodePhysicalMap(Map<String, byte[]> encoded) {
    Map<String, byte[]> result = new HashMap<>();
    encoded.forEach(
        (field, value) -> result.put(decodeUtf8(Base64.getDecoder().decode(field)), value.clone()));
    return result;
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw unavailable("Issuer partition contains a non-UTF-8 field", malformed);
    }
  }

  private static RedisAsyncCommands<byte[], byte[]> requireCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null || !connection.isOpen()) throw unavailable("Redis connection is closed");
    return connection.async();
  }

  private static void requirePrincipal(String principal) {
    if (!PRINCIPAL.equals(principal))
      throw unavailable("Redis ACL principal is not the exact owner");
  }

  private static <T> T await(RedisFuture<T> future) {
    try {
      return future.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw unavailable("Redis command was interrupted", interrupted);
    } catch (ExecutionException | TimeoutException | RuntimeException failure) {
      future.cancel(true);
      throw unavailable("Redis command did not return exact proof", failure);
    }
  }

  private static String digest(String algorithm, byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value));
    } catch (Exception impossible) {
      throw new IllegalStateException("Required digest algorithm is unavailable", impossible);
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw unavailable("Redis retirement cannot run inside an ambient SQL transaction");
    }
  }

  private static IllegalStateException unavailable(String message) {
    return new IllegalStateException("Issuer binding-local retirement is unavailable: " + message);
  }

  private static IllegalStateException unavailable(String message, Throwable cause) {
    return new IllegalStateException(
        "Issuer binding-local retirement is unavailable: " + message, cause);
  }

  static final class Readback {
    private final UUID operationId;
    private final long scriptOutcome;
    private final byte[] exactAbsenceReadbackFieldSetBytes;

    private Readback(UUID operationId, long scriptOutcome, byte[] exactBytes) {
      this.operationId = Objects.requireNonNull(operationId, "operationId");
      this.scriptOutcome = scriptOutcome;
      this.exactAbsenceReadbackFieldSetBytes =
          Objects.requireNonNull(exactBytes, "exactBytes").clone();
    }

    UUID operationId() {
      return operationId;
    }

    long scriptOutcome() {
      return scriptOutcome;
    }

    byte[] exactAbsenceReadbackFieldSetBytes() {
      return exactAbsenceReadbackFieldSetBytes.clone();
    }
  }
}
