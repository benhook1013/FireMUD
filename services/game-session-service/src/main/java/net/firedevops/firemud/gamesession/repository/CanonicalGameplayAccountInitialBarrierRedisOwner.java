package net.firedevops.firemud.gamesession.repository;

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
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountInitialBarrierRedisScriptContribution;

/** Explicit manual owner for a create-once INITIAL OPEN barrier and exact current readback. */
final class CanonicalGameplayAccountInitialBarrierRedisOwner {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String KEY_PREFIX = "session:game:account-admission:";
  private static final int MAX_SCRIPT_BYTES = 32 * 1024;
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000;
  private static final List<String> FIELDS =
      List.of(
          "schema",
          "accountId",
          "tenantId",
          "playableStateNamespaceId",
          "tenantGameplayTag",
          "ownerOperationId",
          "ownerFenceEpoch",
          "incarnationDigest",
          "state");
  private static final List<RedisScriptDescriptor.ArgumentSpec> SCRIPT_ARGUMENTS =
      List.of(
          new RedisScriptDescriptor.ArgumentSpec("schema"),
          new RedisScriptDescriptor.ArgumentSpec("accountId"),
          new RedisScriptDescriptor.ArgumentSpec("tenantId"),
          new RedisScriptDescriptor.ArgumentSpec("playableStateNamespaceId"),
          new RedisScriptDescriptor.ArgumentSpec("tenantGameplayTag"),
          new RedisScriptDescriptor.ArgumentSpec("initializerId"),
          new RedisScriptDescriptor.ArgumentSpec("ownerFenceEpoch"),
          new RedisScriptDescriptor.ArgumentSpec("incarnationDigest"),
          new RedisScriptDescriptor.ArgumentSpec("state"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> SCRIPT_OUTCOMES =
      List.of(
          outcome(
              "OPEN_CREATED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          outcome(
              "TARGET_ALREADY_PRESENT",
              RedisScriptDescriptor.OutcomeCategory.STALE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "INVALID_ARGUMENTS",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING));

  private final io.lettuce.core.RedisClient redisClient;
  private final RedisScriptCatalog catalog;
  private final CanonicalGameplayAccountInitialBarrierInitializerRepository repository;
  private final CanonicalGameplayTenantGameplayTagRepository tenantTags;
  private final CanonicalGameplayProtectedIncarnationReceiver incarnationReceiver;

  CanonicalGameplayAccountInitialBarrierRedisOwner(
      io.lettuce.core.RedisClient redisClient,
      RedisScriptCatalog catalog,
      CanonicalGameplayAccountInitialBarrierInitializerRepository repository,
      CanonicalGameplayTenantGameplayTagRepository tenantTags,
      CanonicalGameplayProtectedIncarnationReceiver incarnationReceiver) {
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Redis client is required");
    this.catalog = Objects.requireNonNull(catalog, "Manual Redis script catalog is required");
    this.repository = Objects.requireNonNull(repository, "Exact SQL initializer is required");
    this.tenantTags = Objects.requireNonNull(tenantTags, "Persisted target resolver is required");
    this.incarnationReceiver =
        Objects.requireNonNull(incarnationReceiver, "Protected observation verifier is required");
  }

  /**
   * Starts one original-attempt target or performs read-only same-original verification. The SQL
   * DISPATCH_POSSIBLE state commits before the only Redis write call; every later path is
   * read-only.
   */
  CanonicalGameplayAccountInitialOpenProof initializeOnce(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult accountLogin,
      UUID tenantId,
      UUID playableStateNamespaceId) {
    Objects.requireNonNull(original, "original attempt");
    Objects.requireNonNull(accountLogin, "authenticated Account LOGIN result");
    CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot snapshot;
    try {
      requireNoAmbientTransaction();
      snapshot = repository.beginOrRead(original, accountLogin, tenantId, playableStateNamespaceId);
    } catch (RuntimeException unavailable) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.SOURCE_CONTROL_UNAVAILABLE);
    }
    if (snapshot.state()
        != CanonicalGameplayAccountInitialBarrierInitializerRepository.State.NEVER_DISPATCHED) {
      return verifyCurrentOpen(snapshot.originalRequestId());
    }

    try {
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
          requireInitialAssignment(snapshot.originalRequestId(), snapshot);
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before =
          incarnationReceiver.observeForInitialAccountBarrier(snapshot.originalRequestId());
      requireObservation(snapshot, before);
      PreparedWrite prepared = prepareWrite(snapshot, assignment, before);
      requireNoAmbientTransaction();
      try (StatefulRedisConnection<byte[], byte[]> connection =
          redisClient.connect(ByteArrayCodec.INSTANCE)) {
        RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
        requirePrincipal(await(commands.aclWhoami()));
        byte[] script = ascii(prepared.script());
        String sha = await(commands.scriptLoad(script));
        if (!digest("SHA-1", script).equals(sha)) throw unavailable();
        repository.recordDispatchPossible(snapshot.originalRequestId(), before);
        // The claim commits before EVAL on this same preflighted connection. A lost SQL
        // commit reply is still uncertain and must never authorize another create attempt.
        tryCreateOnce(prepared, commands, sha);
      }
    } catch (RuntimeException possibleDispatchOrSourceFailure) {
      // Never create a replacement initializer or repeat a possibly dispatched write. A positive
      // exact readback can still finish the original receipt; absence remains explicit uncertainty.
    }
    return verifyCurrentOpen(snapshot.originalRequestId());
  }

  /** Typed read-only current-OPEN proof or refusal; it cannot authorize final admission. */
  CanonicalGameplayAccountInitialOpenProof verifyCurrentOpen(UUID originalRequestId) {
    if (originalRequestId == null) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.ORIGINAL_ATTEMPT_MISMATCH);
    }
    CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot snapshot;
    try {
      requireNoAmbientTransaction();
      snapshot = repository.readCurrent(originalRequestId);
    } catch (RuntimeException unavailable) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.SOURCE_CONTROL_UNAVAILABLE);
    }
    if (snapshot.state()
        == CanonicalGameplayAccountInitialBarrierInitializerRepository.State.NEVER_DISPATCHED) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.TARGET_NEVER_DISPATCHED);
    }
    if (snapshot.state()
            == CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE
        && snapshot.beforeIncarnationDigest() == null) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.REMOTE_OUTCOME_UNCERTAIN);
    }

    try {
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
          requireInitialAssignment(originalRequestId, snapshot);
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before =
          incarnationReceiver.observeForInitialAccountBarrier(originalRequestId);
      requireObservation(snapshot, before);
      if (!before.incarnationDigest().equals(snapshot.beforeIncarnationDigest())
          || !before
              .acceptedAttestationDigest()
              .equals(snapshot.beforeAcceptedAttestationDigest())) {
        return refused(
            CanonicalGameplayAccountInitialOpenProof.Reason.PROTECTED_OBSERVATION_UNAVAILABLE);
      }
      CanonicalGameplayAccountInitialBarrierValue value =
          CanonicalGameplayAccountInitialBarrierValue.open(
              snapshot.accountId(),
              snapshot.tenantId(),
              snapshot.playableStateNamespaceId(),
              assignment.tenantGameplayTag(),
              snapshot.initializerId(),
              snapshot.sourceControlEpoch(),
              snapshot.beforeIncarnationDigest());
      String key =
          CanonicalGameplayAccountInitialBarrierKey.forTarget(
              assignment, snapshot.initializerId(), originalRequestId);
      Map<String, String> physical = readExactPhysicalState(key, value);
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after =
          incarnationReceiver.observeForInitialAccountBarrier(originalRequestId);
      requireObservation(snapshot, after);
      if (!before.incarnationDigest().equals(after.incarnationDigest())
          || !before.acceptedAttestationDigest().equals(after.acceptedAttestationDigest())
          || after.issuedAtEpochMillis() < before.issuedAtEpochMillis()
          || after.observedAtEpochMillis() < before.observedAtEpochMillis()) {
        return refused(
            CanonicalGameplayAccountInitialOpenProof.Reason.PROTECTED_OBSERVATION_UNAVAILABLE);
      }

      Readback readback =
          new Readback(snapshot, assignment, key, value, physical, before, after, 0L);
      if (snapshot.state()
          == CanonicalGameplayAccountInitialBarrierInitializerRepository.State.OPEN_INSTALLED) {
        var current = repository.readCurrent(originalRequestId);
        if (current.state()
            != CanonicalGameplayAccountInitialBarrierInitializerRepository.State.OPEN_INSTALLED) {
          return refused(CanonicalGameplayAccountInitialOpenProof.Reason.CURRENT_CONTROL_CHANGED);
        }
        return currentProof(current, key, value.fields(), after);
      }
      var accepted = repository.acceptOpenReadback(originalRequestId, readback);
      if (accepted.state()
          != CanonicalGameplayAccountInitialBarrierInitializerRepository.State.OPEN_INSTALLED) {
        return refused(CanonicalGameplayAccountInitialOpenProof.Reason.REMOTE_OUTCOME_UNCERTAIN);
      }
      return currentProof(accepted, key, value.fields(), after);
    } catch (RuntimeException unavailable) {
      return refused(classifyRefusal(snapshot));
    }
  }

  /**
   * Resolves and verifies a retained installed INITIAL OPEN for its exact account scope. These
   * coordinates select storage evidence only; the returned proof is not fresh caller or Account
   * admission authority.
   */
  CanonicalGameplayAccountInitialOpenProof verifyCurrentOpenForTarget(
      UUID accountId, UUID tenantId, UUID playableStateNamespaceId) {
    if (isNil(accountId) || isNil(tenantId) || isNil(playableStateNamespaceId)) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.ORIGINAL_ATTEMPT_MISMATCH);
    }
    CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot snapshot;
    try {
      requireNoAmbientTransaction();
      snapshot =
          repository.readInstalledOpenForTarget(accountId, tenantId, playableStateNamespaceId);
    } catch (RuntimeException unavailable) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.SOURCE_CONTROL_UNAVAILABLE);
    }
    if (snapshot == null
        || !accountId.equals(snapshot.accountId())
        || !tenantId.equals(snapshot.tenantId())
        || !playableStateNamespaceId.equals(snapshot.playableStateNamespaceId())) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.ORIGINAL_ATTEMPT_MISMATCH);
    }
    if (snapshot.state()
        == CanonicalGameplayAccountInitialBarrierInitializerRepository.State.NEVER_DISPATCHED) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.TARGET_NEVER_DISPATCHED);
    }
    if (snapshot.state()
        == CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.REMOTE_OUTCOME_UNCERTAIN);
    }
    if (snapshot.state()
        != CanonicalGameplayAccountInitialBarrierInitializerRepository.State.OPEN_INSTALLED) {
      return refused(CanonicalGameplayAccountInitialOpenProof.Reason.ORIGINAL_ATTEMPT_MISMATCH);
    }
    return verifyCurrentOpen(snapshot.originalRequestId());
  }

  private PreparedWrite prepareWrite(
      CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot snapshot,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before) {
    RedisScriptDescriptor descriptor = requireDescriptor();
    byte[] script = scriptSource(descriptor);
    CanonicalGameplayAccountInitialBarrierValue value =
        CanonicalGameplayAccountInitialBarrierValue.open(
            snapshot.accountId(),
            snapshot.tenantId(),
            snapshot.playableStateNamespaceId(),
            assignment.tenantGameplayTag(),
            snapshot.initializerId(),
            snapshot.sourceControlEpoch(),
            before.incarnationDigest());
    String key =
        CanonicalGameplayAccountInitialBarrierKey.forTarget(
            assignment, snapshot.initializerId(), snapshot.originalRequestId());
    List<String> arguments = FIELDS.stream().map(value.fields()::get).toList();
    // Validate every local byte conversion before the durable possible-dispatch claim.
    ascii(key);
    arguments.forEach(CanonicalGameplayAccountInitialBarrierRedisOwner::ascii);
    return new PreparedWrite(asciiString(script), key, arguments);
  }

  private void tryCreateOnce(
      PreparedWrite prepared, RedisAsyncCommands<byte[], byte[]> commands, String sha) {
    requireNoAmbientTransaction();
    Long outcome =
        await(
            commands.evalsha(
                sha,
                ScriptOutputType.INTEGER,
                new byte[][] {ascii(prepared.key())},
                prepared.arguments().stream()
                    .map(CanonicalGameplayAccountInitialBarrierRedisOwner::ascii)
                    .toArray(byte[][]::new)));
    if (outcome == null || outcome < 0 || outcome > 1) throw unavailable();
  }

  private record PreparedWrite(String script, String key, List<String> arguments) {}

  private CanonicalGameplayTenantGameplayTagRepository.Assignment requireInitialAssignment(
      UUID originalRequestId,
      CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot snapshot) {
    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
        tenantTags.assignmentForInitialBarrierTarget(originalRequestId);
    if (!assignment.isInitialBarrierTarget()
        || !snapshot.initializerId().equals(assignment.initialBarrierInitializerId())
        || !snapshot.originalRequestId().equals(assignment.initialBarrierOriginalRequestId())
        || !snapshot.accountId().equals(assignment.accountId())
        || !snapshot.tenantId().equals(assignment.tenantId())
        || !snapshot.playableStateNamespaceId().equals(assignment.playableStateNamespaceId())) {
      throw unavailable();
    }
    return assignment;
  }

  private static void requireObservation(
      CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot snapshot,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation observation) {
    if (observation.ownerKind()
            != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER
        || !snapshot.initializerId().equals(observation.operationId())
        || !snapshot.originalRequestId().equals(observation.requestId())
        || !snapshot.originalCallerContextId().equals(observation.originalCallerContextId())
        || snapshot.originalAttemptFence() != observation.originalAttemptFence()
        || !snapshot.accountId().equals(observation.accountId())
        || !snapshot.tenantId().equals(observation.tenantId())
        || !snapshot.playableStateNamespaceId().equals(observation.playableStateNamespaceId())
        || !"NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(observation.sourceControlState())
        || !snapshot.sourceControlEpoch().toString().equals(observation.sourceControlEpoch())
        || !snapshot
            .inventorySnapshotRevision()
            .toString()
            .equals(observation.inventorySnapshotRevision())
        || !snapshot.accountLoginEvidenceDigest().equals(observation.accountLoginEvidenceDigest())
        || !snapshot.sourceControlDigest().equals(observation.sourceControlDigest())
        || !snapshot.targetDigest().equals(observation.barrierTargetDigest())) {
      throw unavailable();
    }
  }

  private Map<String, String> readExactPhysicalState(
      String key, CanonicalGameplayAccountInitialBarrierValue expected) {
    requireNoAmbientTransaction();
    byte[] keyBytes = ascii(key);
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      if (!"hash".equals(await(commands.type(keyBytes)))
          || !Long.valueOf(-1L).equals(await(commands.pttl(keyBytes)))) throw unavailable();
      Long length = await(commands.hlen(keyBytes));
      if (length == null || length != FIELDS.size()) throw unavailable();
      Map<byte[], byte[]> raw = await(commands.hgetall(keyBytes));
      if (raw == null || raw.size() != FIELDS.size()) throw unavailable();
      Map<String, String> actual = new HashMap<>();
      for (Map.Entry<byte[], byte[]> entry : raw.entrySet()) {
        String field = asciiString(entry.getKey());
        String value = asciiString(Objects.requireNonNull(entry.getValue()));
        if (!FIELDS.contains(field) || actual.putIfAbsent(field, value) != null)
          throw unavailable();
      }
      if (!expected.fields().equals(actual)) throw unavailable();
      return Map.copyOf(actual);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private RedisScriptDescriptor requireDescriptor() {
    String scriptId = CanonicalGameplayAccountInitialBarrierRedisScriptContribution.SCRIPT_ID;
    String resourcePath =
        CanonicalGameplayAccountInitialBarrierRedisScriptContribution.RESOURCE_PATH;
    try {
      RedisScriptDescriptor descriptor = catalog.require(scriptId);
      if (!scriptId.equals(descriptor.scriptId())
          || !resourcePath.equals(descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || !List.of(
                  new RedisScriptDescriptor.KeySpec(
                      "accountAdmissionBarrier",
                      KEY_PREFIX,
                      RedisScriptDescriptor.HashTagDeclaration.REQUIRED))
              .equals(descriptor.keys())
          || !SCRIPT_ARGUMENTS.equals(descriptor.arguments())
          || descriptor.category() != RedisScriptDescriptor.ScriptCategory.SESSION_CAS
          || !SCRIPT_OUTCOMES.equals(descriptor.outcomes())
          || descriptor.resetSensitivity() != RedisScriptDescriptor.ResetSensitivity.CLUSTER
          || descriptor.lossClass()
              != RedisScriptDescriptor.LossClass.EMPTY_REDIS_COLD_START_OR_RESET
          || descriptor.compatibilityLevel()
              != RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET
          || !descriptor
              .supportedCoexistence()
              .equals(List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "none")))
          || descriptor.legacyPayloadShape() != null) throw unavailable();
      return descriptor;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static byte[] scriptSource(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        CanonicalGameplayAccountInitialBarrierRedisOwner.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (bytes.length == 0
          || bytes.length > MAX_SCRIPT_BYTES
          || !descriptor.sha256().equals(digest("SHA-256", bytes))) throw unavailable();
      return bytes;
    } catch (IOException | RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static CanonicalGameplayAccountInitialOpenProof currentProof(
      CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot snapshot,
      String key,
      Map<String, String> fields,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation currentObservation) {
    return new CanonicalGameplayAccountInitialOpenProof.CurrentOpen(
        snapshot.initializerId(),
        snapshot.originalRequestId(),
        snapshot.originalCallerContextId(),
        snapshot.originalAttemptFence(),
        snapshot.accountId(),
        snapshot.tenantId(),
        snapshot.playableStateNamespaceId(),
        key,
        snapshot.sourceControlEpoch(),
        snapshot.inventorySnapshotRevision(),
        snapshot.accountLoginEvidenceDigest(),
        snapshot.sourceControlDigest(),
        snapshot.beforeIncarnationDigest(),
        currentObservation.issuedAtEpochMillis(),
        currentObservation.expiresAtEpochMillis(),
        fields);
  }

  private static CanonicalGameplayAccountInitialOpenProof refused(
      CanonicalGameplayAccountInitialOpenProof.Reason reason) {
    return new CanonicalGameplayAccountInitialOpenProof.Refused(reason);
  }

  private static CanonicalGameplayAccountInitialOpenProof.Reason classifyRefusal(
      CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot snapshot) {
    return snapshot.state()
            == CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE
        ? CanonicalGameplayAccountInitialOpenProof.Reason.REMOTE_OUTCOME_UNCERTAIN
        : CanonicalGameplayAccountInitialOpenProof.Reason.PHYSICAL_VALUE_MISSING_OR_CONFLICTING;
  }

  private static RedisAsyncCommands<byte[], byte[]> requireCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null || !connection.isOpen()) throw unavailable();
    RedisAsyncCommands<byte[], byte[]> commands = connection.async();
    if (commands == null) throw unavailable();
    return commands;
  }

  private static void requirePrincipal(String principal) {
    if (!PRINCIPAL.equals(principal)) throw unavailable();
  }

  private static byte[] ascii(String value) {
    Objects.requireNonNull(value, "ASCII value");
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) > 0x7f) throw unavailable();
    }
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String asciiString(byte[] value) {
    Objects.requireNonNull(value, "Redis field");
    for (byte character : value) if ((character & 0x80) != 0) throw unavailable();
    return new String(value, StandardCharsets.US_ASCII);
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception failure) {
      throw unavailable(failure);
    }
  }

  private static <T> T await(RedisFuture<T> future) {
    if (future == null) throw unavailable();
    try {
      return future.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable(interrupted);
    } catch (ExecutionException | TimeoutException failure) {
      throw unavailable(failure);
    }
  }

  private void requireNoAmbientTransaction() {
    repository.requireNoAmbientTransaction();
  }

  private static RedisScriptDescriptor.OutcomeSpec outcome(
      String code,
      RedisScriptDescriptor.OutcomeCategory category,
      RedisScriptDescriptor.MutationEffect effect) {
    return new RedisScriptDescriptor.OutcomeSpec(code, category, effect);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("INITIAL account barrier is unavailable");
  }

  private static IllegalStateException unavailable(Throwable cause) {
    return new IllegalStateException("INITIAL account barrier is unavailable", cause);
  }

  private static boolean isNil(UUID value) {
    return value == null || value.equals(new UUID(0L, 0L));
  }

  record Readback(
      UUID initializerId,
      UUID originalRequestId,
      UUID accountId,
      UUID tenantId,
      UUID playableStateNamespaceId,
      String accountLoginEvidenceDigest,
      String sourceControlDigest,
      BigInteger inventorySnapshotRevision,
      String key,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayAccountInitialBarrierValue value,
      Map<String, String> barrierFields,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation beforeIncarnation,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation afterIncarnation,
      long scriptOutcome) {
    Readback(
        CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot snapshot,
        CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
        String key,
        CanonicalGameplayAccountInitialBarrierValue value,
        Map<String, String> barrierFields,
        CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation beforeIncarnation,
        CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation afterIncarnation,
        long scriptOutcome) {
      this(
          snapshot.initializerId(),
          snapshot.originalRequestId(),
          snapshot.accountId(),
          snapshot.tenantId(),
          snapshot.playableStateNamespaceId(),
          snapshot.accountLoginEvidenceDigest(),
          snapshot.sourceControlDigest(),
          snapshot.inventorySnapshotRevision(),
          key,
          assignment,
          value,
          barrierFields,
          beforeIncarnation,
          afterIncarnation,
          scriptOutcome);
    }

    Readback {
      Objects.requireNonNull(initializerId, "initializerId");
      Objects.requireNonNull(originalRequestId, "originalRequestId");
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(accountLoginEvidenceDigest, "accountLoginEvidenceDigest");
      Objects.requireNonNull(sourceControlDigest, "sourceControlDigest");
      Objects.requireNonNull(inventorySnapshotRevision, "inventorySnapshotRevision");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(assignment, "assignment");
      Objects.requireNonNull(value, "value");
      barrierFields = Map.copyOf(Objects.requireNonNull(barrierFields, "barrierFields"));
      Objects.requireNonNull(beforeIncarnation, "beforeIncarnation");
      Objects.requireNonNull(afterIncarnation, "afterIncarnation");
      if (scriptOutcome < 0
          || scriptOutcome > 1
          || !CanonicalGameplayAccountInitialBarrierValue.FIELDS.equals(barrierFields.keySet())
          || !value.fields().equals(barrierFields)
          || !CanonicalGameplayAccountInitialBarrierKey.forTarget(
                  assignment, initializerId, originalRequestId)
              .equals(key)
          || !initializerId.equals(assignment.initialBarrierInitializerId())
          || !originalRequestId.equals(assignment.initialBarrierOriginalRequestId())
          || !accountId.equals(assignment.accountId())
          || !tenantId.equals(assignment.tenantId())
          || !playableStateNamespaceId.equals(assignment.playableStateNamespaceId())) {
        throw unavailable();
      }
    }
  }
}
