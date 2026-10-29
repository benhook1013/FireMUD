package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerPartitionRedisSupport.KEY_PREFIX;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerPartitionRedisSupport.ascii;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerPartitionRedisSupport.b64;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution;

/** Manual one-key physical owner for an SQL-issued first-NEW issuer release successor. */
public final class CanonicalGameplayIssuerBindingLocalReleaseRedisOwner {
  private static final CanonicalGameplayIssuerPartitionRedisSupport REDIS =
      new CanonicalGameplayIssuerPartitionRedisSupport(
          CanonicalGameplayIssuerBindingLocalReleaseRedisOwner::unavailable);
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
      byte[] script = REDIS.scriptSource(descriptor);
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
    try {
      return REDIS.writeOneKey(redisClient, script, key, arguments);
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
      RedisAsyncCommands<byte[], byte[]> commands = REDIS.authenticatedCommands(connection);
      Map<String, byte[]> actual = REDIS.readBoundedHash(commands, key);
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
        String decoded = REDIS.decodeField(field.getKey());
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
        String decoded = REDIS.decodeField(field.getKey());
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
          REDIS.decodePhysicalMap(actual));
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
    var current = currentStateSource.readFreshCurrentState();
    requireNoAmbientTransaction();
    if (!REDIS.matchesCurrentState(current, work.anchor())) {
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
    if (!REDIS.matchesDescriptor(
        descriptor,
        CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution.SCRIPT_ID,
        CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution.RESOURCE_PATH,
        EXPECTED_KEYS,
        EXPECTED_ARGUMENTS,
        EXPECTED_OUTCOMES)) {
      throw unavailable("Manual issuer release descriptor differs from its exact owner contract");
    }
    return descriptor;
  }

  private static void requireNoAmbientTransaction() {
    REDIS.requireNoAmbientTransaction();
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
