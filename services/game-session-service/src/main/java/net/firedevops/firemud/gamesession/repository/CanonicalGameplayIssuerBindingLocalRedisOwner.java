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
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;

/**
 * Explicit, unregistered one-key projection owner for SQL-prepared ordinary issuer NEW work.
 * Physical readback is local evidence only and leaves its SQL operation pending.
 */
public final class CanonicalGameplayIssuerBindingLocalRedisOwner {
  private static final CanonicalGameplayIssuerPartitionRedisSupport REDIS =
      new CanonicalGameplayIssuerPartitionRedisSupport(
          (message, cause) -> cause == null ? unavailable() : unavailable(cause));
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
              "targetOperationAndRequiredFieldsAndCapacityCounts"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> EXPECTED_OUTCOMES =
      List.of(
          new RedisScriptDescriptor.OutcomeSpec(
              "CAPACITY_RESERVED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "MEMBER_PROJECTED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "LOCAL_OPERATION_REPLAY",
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

  /**
   * Redis client, passive manual descriptor catalog, actual SQL reader, and current source are
   * explicit.
   */
  public CanonicalGameplayIssuerBindingLocalRedisOwner(
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

  /** Writes only the exact target reservation and SQL-qualified target binding field. */
  public Readback projectAndRead(CanonicalGameplayIssuerBindingLocalOperation operation) {
    Objects.requireNonNull(operation, "SQL-issued local issuer operation is required");
    requireNoAmbientTransaction();
    try {
      return projectAndRead(operation, projectionRepository.prepareAddMutationWork(operation));
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().startsWith("Issuer binding-local projection is unavailable"))
        throw state;
      throw unavailable(failure);
    }
  }

  /** Finishes only an already-issued original capacity mutation for negative cleanup. */
  public void reconcileNeverAdmittedCapacity(
      CanonicalGameplayIssuerBindingLocalOperation operation) {
    Objects.requireNonNull(operation, "original operation");
    requireNoAmbientTransaction();
    var work = projectionRepository.prepareNeverAdmittedCapacityMutationWork(operation);
    if (work.isPresent()) projectAndRead(operation, work.orElseThrow());
  }

  private Readback projectAndRead(
      CanonicalGameplayIssuerBindingLocalOperation operation,
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work) {
    Objects.requireNonNull(operation, "SQL-issued local issuer operation is required");
    try {
      requireNoAmbientTransaction();
      requireFreshCurrentState(work);
      if (!work.targetEntry().registeredOperationId().equals(operation.operationId())) {
        throw unavailable();
      }
      CanonicalGameplayIssuerPartitionMutation mutation = work.physicalMutation();
      if (mutation == null) throw unavailable();
      if (mutation.status() == CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED) {
        if (work.pendingMutationId() != null) throw unavailable();
        byte[] currentReadback =
            readExactPhysicalState(exactPartitionKey(work), work, work.currentRevision());
        requireFreshCurrentState(work);
        projectionRepository.requireStillCurrent(work);
        return new Readback(operation, 3L, currentReadback, work.sqlCurrentnessDigest());
      }
      if (!work.targetEntry().operationId().equals(operation.operationId())) throw unavailable();
      if (!mutation.mutationId().equals(work.pendingMutationId())
          || !mutation.expectedRevision().equals(work.currentRevision())) {
        throw unavailable();
      }
      RedisScriptDescriptor descriptor = requireDescriptor();
      byte[] script = REDIS.scriptSource(descriptor);
      byte[] key = exactPartitionKey(work);
      byte[][] arguments = scriptArguments(work);
      long result = writeOneKey(script, key, arguments);
      if (result < 1 || result > 3) {
        throw new IllegalStateException(
            "Issuer binding-local projection returned script outcome " + result);
      }
      byte[] readbackBytes = readExactPhysicalState(key, work, mutation.successorRevision());
      requireFreshCurrentState(work);
      projectionRepository.requireStillCurrent(work);
      projectionRepository.completePhysicalMutation(mutation, readbackBytes);
      var completedWork = projectionRepository.readPreparedPartitionWork(operation);
      return new Readback(operation, result, readbackBytes, completedWork.sqlCurrentnessDigest());
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().startsWith("Issuer binding-local projection is unavailable")) {
        throw state;
      }
      throw unavailable(failure);
    }
  }

  private long writeOneKey(byte[] script, byte[] key, byte[][] arguments) {
    requireNoAmbientTransaction();
    try {
      return REDIS.writeOneKey(redisClient, script, key, arguments);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private byte[] readExactPhysicalState(
      byte[] key,
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work,
      UUID expectedPhysicalRevision) {
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
          expectedPhysicalRevision.toString().getBytes(StandardCharsets.US_ASCII))) {
        throw unavailable();
      }
      for (Map.Entry<String, byte[]> field : baseline.entrySet()) {
        byte[] redisValue = actual.get(b64(field.getKey().getBytes(StandardCharsets.UTF_8)));
        if (redisValue == null || !Arrays.equals(redisValue, field.getValue())) throw unavailable();
      }
      for (Map.Entry<String, byte[]> field : actual.entrySet()) {
        String decodedField = REDIS.decodeField(field.getKey());
        if (decodedField.equals(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD))
          continue;
        byte[] expected = allowed.get(decodedField);
        if (expected == null || !Arrays.equals(expected, field.getValue())) throw unavailable();
      }

      for (var entry : work.localEntries()) {
        if (!entry
            .memberValue()
            .reservation()
            .partitionId()
            .equals(work.targetOperation().memberValue().reservation().partitionId())) continue;
        String capacityName =
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                entry.memberValue().reservation().reservationId());
        byte[] capacityValue = actual.get(b64(capacityName.getBytes(StandardCharsets.US_ASCII)));
        if (entry.capacityMustBePresent()
            && !Arrays.equals(capacityValue, entry.exactMemberValueBytes())) throw unavailable();
        String bindingName =
            new String(entry.memberValue().owner().bindingRef().bytes(), StandardCharsets.UTF_8);
        byte[] bindingValue = actual.get(b64(bindingName.getBytes(StandardCharsets.UTF_8)));
        if (entry.bindingMustBePresent()
            && !Arrays.equals(bindingValue, entry.exactMemberValueBytes())) throw unavailable();
      }

      var target = work.targetEntry();
      String capacityField =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              target.memberValue().reservation().reservationId());
      byte[] targetCapacity = actual.get(b64(capacityField.getBytes(StandardCharsets.US_ASCII)));
      if (!Arrays.equals(targetCapacity, target.exactMemberValueBytes())) throw unavailable();
      String bindingField =
          new String(target.memberValue().owner().bindingRef().bytes(), StandardCharsets.UTF_8);
      byte[] targetBinding = actual.get(b64(bindingField.getBytes(StandardCharsets.UTF_8)));
      if (target.bindingPresent()) {
        if (!Arrays.equals(targetBinding, target.exactMemberValueBytes())) throw unavailable();
      } else if (targetBinding != null) {
        throw unavailable();
      }

      int physicalCapacityCount = 0;
      int physicalReservationCount = 0;
      int physicalBindingCount = 0;
      int qualifiedLocalBindingCount = 0;
      for (var entry : work.localEntries()) {
        if (entry
                .memberValue()
                .reservation()
                .partitionId()
                .equals(work.targetOperation().memberValue().reservation().partitionId())
            && entry.bindingPresent()) {
          qualifiedLocalBindingCount++;
        }
      }
      for (var entry : actual.entrySet()) {
        String field = REDIS.decodeField(entry.getKey());
        byte[] valueBytes = entry.getValue();
        if (field.equals(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD)) {
          continue;
        } else if (field.startsWith(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.RESERVATION_FIELD_PREFIX)) {
          var value = CanonicalGameplayIssuerMemberV2Codec.decode(valueBytes);
          if (value.reservation().lifecycle()
              != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED) {
            physicalCapacityCount++;
          }
          physicalReservationCount++;
        } else if (!field.equals(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD)) {
          CanonicalGameplayIssuerMemberV2Codec.decode(valueBytes);
          physicalBindingCount++;
        }
      }
      int baselineCapacityCount = work.baselineProof().physicalCountedCapacityCount();
      int baselineReservationCount = work.baselineProof().physicalReservationCount();
      int baselineBindingCount = work.baselineProof().physicalBindingCount();
      int registeredCapacityCount =
          Math.toIntExact(
              work.localEntries().stream()
                  .filter(
                      entry ->
                          entry
                                  .memberValue()
                                  .reservation()
                                  .partitionId()
                                  .equals(
                                      work.targetOperation()
                                          .memberValue()
                                          .reservation()
                                          .partitionId())
                              && entry.countsTowardCapacity())
                  .count());
      int registeredReservationCount =
          Math.toIntExact(
              work.localEntries().stream()
                  .filter(
                      entry ->
                          entry
                              .memberValue()
                              .reservation()
                              .partitionId()
                              .equals(
                                  work.targetOperation().memberValue().reservation().partitionId()))
                  .count());
      if (BigInteger.valueOf(physicalCapacityCount).compareTo(work.anchor().partitionCapacity()) > 0
          || physicalCapacityCount < baselineCapacityCount + (target.countsTowardCapacity() ? 1 : 0)
          || physicalCapacityCount > baselineCapacityCount + registeredCapacityCount
          || physicalReservationCount < baselineReservationCount + 1
          || physicalReservationCount > baselineReservationCount + registeredReservationCount
          || physicalBindingCount < baselineBindingCount
          || physicalBindingCount > baselineBindingCount + qualifiedLocalBindingCount
          || work.partitionCapacity().compareTo(work.anchor().partitionCapacity()) > 0
          || work.aggregateCapacity().compareTo(work.aggregateBound()) > 0) {
        throw unavailable();
      }
      return CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(
          REDIS.decodePhysicalMap(actual));
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private void requireFreshCurrentState(
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work) {
    requireNoAmbientTransaction();
    var current = currentStateSource.readFreshCurrentState();
    requireNoAmbientTransaction();
    if (!REDIS.matchesCurrentState(current, work.anchor())) {
      throw unavailable();
    }
  }

  private RedisScriptDescriptor requireDescriptor() {
    try {
      RedisScriptDescriptor descriptor =
          catalog.require(CanonicalGameplayIssuerBindingLocalRedisScriptContribution.SCRIPT_ID);
      if (!REDIS.matchesDescriptor(
          descriptor,
          CanonicalGameplayIssuerBindingLocalRedisScriptContribution.SCRIPT_ID,
          CanonicalGameplayIssuerBindingLocalRedisScriptContribution.RESOURCE_PATH,
          EXPECTED_KEYS,
          EXPECTED_ARGUMENTS,
          EXPECTED_OUTCOMES)) {
        throw unavailable();
      }
      return descriptor;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static byte[] exactPartitionKey(
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work) {
    String key = CanonicalGameplayIssuerRedisKeys.forMember(work.targetOperation().memberValue());
    if (!key.startsWith(KEY_PREFIX) || key.length() > 512) throw unavailable();
    return key.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[][] scriptArguments(
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work) {
    var anchor = work.anchor();
    Map<String, byte[]> baseline = work.baselineFields();
    List<Map.Entry<String, byte[]>> baselineEntries =
        baseline.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    List<byte[]> arguments = new ArrayList<>();
    arguments.add(ascii(anchor.issuerId()));
    arguments.add(
        ascii(work.targetOperation().memberValue().reservation().partitionId().toString()));
    arguments.add(ascii(anchor.partitionCount().toString()));
    arguments.add(ascii(anchor.partitionCapacity().toString()));
    arguments.add(ascii(Integer.toString(baselineEntries.size())));
    for (Map.Entry<String, byte[]> field : baselineEntries) {
      arguments.add(field.getKey().getBytes(StandardCharsets.UTF_8));
      arguments.add(field.getValue());
    }
    List<CanonicalGameplayIssuerBindingLocalProjectionRepository.LocalEntry> entries =
        work.localEntries().stream()
            .filter(
                entry ->
                    entry
                        .memberValue()
                        .reservation()
                        .partitionId()
                        .equals(work.targetOperation().memberValue().reservation().partitionId()))
            .sorted(java.util.Comparator.comparing(entry -> entry.operationId().toString()))
            .toList();
    arguments.add(ascii(Integer.toString(entries.size())));
    for (var entry : entries) {
      arguments.add(ascii(entry.operationId().toString()));
      arguments.add(entry.memberValue().owner().bindingRef().bytes());
      arguments.add(ascii(entry.memberValue().reservation().reservationId().toString()));
      arguments.add(entry.exactMemberValueBytes());
      arguments.add(ascii(entry.bindingPresent() ? "1" : "0"));
      arguments.add(ascii(entry.countsTowardCapacity() ? "1" : "0"));
      arguments.add(ascii(entry.capacityMustBePresent() ? "1" : "0"));
      arguments.add(ascii(entry.bindingMustBePresent() ? "1" : "0"));
    }
    var target = work.targetEntry();
    arguments.add(ascii(target.operationId().toString()));
    arguments.add(ascii(target.memberValue().reservation().reservationId().toString()));
    arguments.add(target.memberValue().owner().bindingRef().bytes());
    arguments.add(target.exactMemberValueBytes());
    arguments.add(ascii(target.bindingPresent() ? "1" : "0"));
    arguments.add(ascii(work.partitionCapacity().toString()));
    arguments.add(ascii(work.aggregateCapacity().toString()));
    arguments.add(ascii(work.aggregateBound().toString()));
    var mutation = work.physicalMutation();
    if (mutation == null) throw unavailable();
    arguments.add(ascii(mutation.expectedRevision().toString()));
    arguments.add(ascii(mutation.successorRevision().toString()));
    arguments.add(ascii(mutation.kind().name()));
    return arguments.toArray(byte[][]::new);
  }

  private static void requireNoAmbientTransaction() {
    REDIS.requireNoAmbientTransaction();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Issuer binding-local projection is unavailable");
  }

  private static IllegalStateException unavailable(Throwable cause) {
    return new IllegalStateException("Issuer binding-local projection is unavailable", cause);
  }

  /**
   * Exact SQL operation plus independent physical readback; no acknowledgement or admission claim.
   */
  public static final class Readback {
    private final CanonicalGameplayIssuerBindingLocalOperation operation;
    private final long scriptOutcome;
    private final byte[] exactReadbackBytes;
    private final byte[] sqlCurrentnessDigest;

    private Readback(
        CanonicalGameplayIssuerBindingLocalOperation operation,
        long scriptOutcome,
        byte[] exactReadbackBytes,
        byte[] sqlCurrentnessDigest) {
      this.operation = Objects.requireNonNull(operation, "operation");
      this.scriptOutcome = scriptOutcome;
      this.exactReadbackBytes =
          Objects.requireNonNull(exactReadbackBytes, "exactReadbackBytes").clone();
      this.sqlCurrentnessDigest =
          Objects.requireNonNull(sqlCurrentnessDigest, "sqlCurrentnessDigest").clone();
      if (this.sqlCurrentnessDigest.length != 32) {
        throw new IllegalArgumentException("SQL issuer currentness digest must be SHA-256");
      }
    }

    public CanonicalGameplayIssuerBindingLocalOperation operation() {
      return operation;
    }

    public UUID operationId() {
      return operation.operationId();
    }

    public long scriptOutcome() {
      return scriptOutcome;
    }

    public byte[] exactReadbackBytes() {
      return exactReadbackBytes.clone();
    }

    byte[] sqlCurrentnessDigest() {
      return sqlCurrentnessDigest.clone();
    }
  }
}
