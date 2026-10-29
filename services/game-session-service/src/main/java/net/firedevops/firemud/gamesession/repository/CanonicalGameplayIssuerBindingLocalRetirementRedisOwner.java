package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerPartitionRedisSupport.KEY_PREFIX;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerPartitionRedisSupport.ascii;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerPartitionRedisSupport.b64;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution;

/** Manual one-key owner for exact original-operation terminal capacity retirement. */
final class CanonicalGameplayIssuerBindingLocalRetirementRedisOwner {
  private static final CanonicalGameplayIssuerPartitionRedisSupport REDIS =
      new CanonicalGameplayIssuerPartitionRedisSupport(
          CanonicalGameplayIssuerBindingLocalRetirementRedisOwner::unavailable);
  private static final List<RedisScriptDescriptor.KeySpec> EXPECTED_KEYS =
      List.of(
          new RedisScriptDescriptor.KeySpec(
              "issuerPartition", KEY_PREFIX, RedisScriptDescriptor.HashTagDeclaration.REQUIRED));
  private static final List<RedisScriptDescriptor.ArgumentSpec> EXPECTED_ARGUMENTS =
      List.of(
          new RedisScriptDescriptor.ArgumentSpec("issuerIdPartitionLayoutAndExactBaselineFields"),
          new RedisScriptDescriptor.ArgumentSpec(
              "currentUnretiredOriginalMemberCountAndExactFields"),
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
      var work = projectionRepository.readPreparedPartitionWork(release.originalSource());
      var mutation = operation.physicalMutation();
      if (mutation.status()
              != CanonicalGameplayIssuerPartitionMutation.Status.PENDING_PHYSICAL_PROOF
          || !mutation.mutationId().equals(work.pendingMutationId())
          || !mutation.expectedRevision().equals(work.currentRevision())) {
        throw unavailable("Original retirement no longer owns its prepared partition revision");
      }
      requireFreshCurrentState(work);
      RedisScriptDescriptor descriptor = requireDescriptor();
      byte[] script = REDIS.scriptSource(descriptor);
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
    try {
      return REDIS.writeOneKey(redisClient, script, key, arguments);
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
      RedisAsyncCommands<byte[], byte[]> commands = REDIS.authenticatedCommands(connection);
      Map<String, byte[]> actual = REDIS.readBoundedHash(commands, key);
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
              .getBytes(StandardCharsets.US_ASCII))) {
        throw unavailable("Issuer retirement lacks its exact successor fence");
      }
      for (Map.Entry<String, byte[]> field : work.baselineFields().entrySet()) {
        byte[] value = actual.get(b64(field.getKey().getBytes(StandardCharsets.UTF_8)));
        if (value == null || !Arrays.equals(value, field.getValue())) {
          throw unavailable("Exact installed issuer baseline changed during retirement");
        }
      }
      for (Map.Entry<String, byte[]> field : actual.entrySet()) {
        String decoded = REDIS.decodeField(field.getKey());
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
          REDIS.decodePhysicalMap(actual));
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
    String key = CanonicalGameplayIssuerRedisKeys.forMember(work.targetSource().memberValue());
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
              CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution.SCRIPT_ID);
    } catch (RuntimeException missing) {
      throw unavailable("Manual issuer retirement descriptor is not installed", missing);
    }
    if (!REDIS.matchesDescriptor(
        descriptor,
        CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution.SCRIPT_ID,
        CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution.RESOURCE_PATH,
        EXPECTED_KEYS,
        EXPECTED_ARGUMENTS,
        EXPECTED_OUTCOMES)) {
      throw unavailable(
          "Manual issuer retirement descriptor differs from its exact owner contract");
    }
    return descriptor;
  }

  private static void requireNoAmbientTransaction() {
    REDIS.requireNoAmbientTransaction();
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
