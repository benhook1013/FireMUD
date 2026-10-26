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
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountClosedBarrierRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Game Session owner for one tenant-local CLOSED account-admission barrier.
 *
 * <p>This owner performs only a guarded one-key CLOSED write and physical readback. It does not
 * publish an SQL installation receipt, open admission, perform final binding CAS, or establish
 * account-wide coverage. Every Redis phase is outside SQL transactions.
 */
final class CanonicalGameplayAccountClosedBarrierRedisOwner {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String KEY_PREFIX = "session:game:account-admission:";
  private static final String SCHEMA = "gs_account_admission_barrier/v1";
  private static final String CLOSED = "CLOSED";
  private static final int MAX_SCRIPT_BYTES = 32 * 1024;
  private static final int MAX_DECIMAL_BYTES = 256;
  private static final long REDIS_COMMAND_TIMEOUT_MILLIS = 5_000;
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
  private static final List<RedisScriptDescriptor.KeySpec> EXPECTED_KEYS =
      List.of(
          new RedisScriptDescriptor.KeySpec(
              "accountAdmissionBarrier",
              KEY_PREFIX,
              RedisScriptDescriptor.HashTagDeclaration.REQUIRED));
  private static final List<RedisScriptDescriptor.ArgumentSpec> EXPECTED_ARGUMENTS =
      List.of(
          new RedisScriptDescriptor.ArgumentSpec("schema"),
          new RedisScriptDescriptor.ArgumentSpec("accountId"),
          new RedisScriptDescriptor.ArgumentSpec("tenantId"),
          new RedisScriptDescriptor.ArgumentSpec("playableStateNamespaceId"),
          new RedisScriptDescriptor.ArgumentSpec("tenantGameplayTag"),
          new RedisScriptDescriptor.ArgumentSpec("ownerOperationId"),
          new RedisScriptDescriptor.ArgumentSpec("ownerFenceEpoch"),
          new RedisScriptDescriptor.ArgumentSpec("incarnationDigest"),
          new RedisScriptDescriptor.ArgumentSpec("state"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> EXPECTED_OUTCOMES =
      List.of(
          new RedisScriptDescriptor.OutcomeSpec(
              "CLOSED_CREATED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "CLOSED_REPLAY",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "CLOSED_ADVANCED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "TYPE_TTL_OR_FIELDS_REFUSED",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "INVALID_ARGUMENTS",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "STALE_OR_CONFLICTING_OWNER",
              RedisScriptDescriptor.OutcomeCategory.STALE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING));
  private static final Set<String> EXPECTED_FIELD_SET = Set.copyOf(FIELDS);

  enum MutationOutcome {
    CREATED_CLOSED,
    EXACT_CLOSED_REPLAY,
    ADVANCED_TO_CLOSED
  }

  private final CanonicalGameplayAccountAdmissionBarrierRepository intentRepository;
  private final CanonicalGameplayTenantGameplayTagRepository tenantTagRepository;
  private final CanonicalGameplayProtectedIncarnationReceiver incarnationReceiver;
  private final RedisClient redisClient;
  private final RedisScriptCatalog catalog;
  private final Clock clock;

  CanonicalGameplayAccountClosedBarrierRedisOwner(
      CanonicalGameplayAccountAdmissionBarrierRepository intentRepository,
      CanonicalGameplayTenantGameplayTagRepository tenantTagRepository,
      CanonicalGameplayProtectedIncarnationReceiver incarnationReceiver,
      RedisClient redisClient,
      RedisScriptCatalog catalog,
      Clock clock) {
    this.intentRepository = Objects.requireNonNull(intentRepository, "intentRepository");
    this.tenantTagRepository = Objects.requireNonNull(tenantTagRepository, "tenantTagRepository");
    this.incarnationReceiver = Objects.requireNonNull(incarnationReceiver, "incarnationReceiver");
    this.redisClient = Objects.requireNonNull(redisClient, "configured Redis client");
    this.catalog = Objects.requireNonNull(catalog, "installed Redis script catalog");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Installs or exactly retries one CLOSED barrier, then reads it back from a distinct Redis
   * connection and obtains fresh protected current-incarnation evidence. The result is only local
   * physical evidence; a SQL owner must separately accept a guarded per-target receipt.
   */
  Readback installAndRead(UUID operationId, long targetOrdinal) {
    requireNoAmbientTransaction();
    requireNonNil(operationId, "operationId");
    if (targetOrdinal < 0) throw unavailable();

    try {
      BarrierContext before = loadCurrentContext(operationId, targetOrdinal);
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation beforeIncarnation =
          incarnationReceiver.observeForFenceTarget(operationId, targetOrdinal);
      requireIncarnationMatches(before, beforeIncarnation);
      requireUnexpired(beforeIncarnation);

      RedisScriptDescriptor descriptor = requireDescriptor();
      byte[] source = source(descriptor);
      byte[] key = ascii(before.key());
      Map<String, String> expectedFields =
          expectedFields(before, beforeIncarnation.incarnationDigest());
      byte[][] arguments = arguments(before, beforeIncarnation.incarnationDigest());
      long mutationReply = executeMutation(source, key, arguments);
      MutationOutcome outcome = mutationOutcome(mutationReply);

      Map<String, String> actualFields = readPhysicalBarrier(key, expectedFields);
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation afterIncarnation =
          incarnationReceiver.observeForFenceTarget(operationId, targetOrdinal);
      requireIncarnationMatches(before, afterIncarnation);
      if (!beforeIncarnation.incarnationDigest().equals(afterIncarnation.incarnationDigest())) {
        throw unavailable();
      }
      requireUnexpired(beforeIncarnation);
      requireUnexpired(afterIncarnation);

      BarrierContext after = loadCurrentContext(operationId, targetOrdinal);
      if (!before.sameOwnerContext(after)) throw unavailable();
      if (!actualFields.equals(expectedFields)) throw unavailable();
      requireNoAmbientTransaction();
      return new Readback(before, actualFields, outcome, beforeIncarnation, afterIncarnation);
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().contains("CLOSED account barrier is unavailable")) {
        throw state;
      }
      throw unavailable(failure);
    }
  }

  private BarrierContext loadCurrentContext(UUID operationId, long targetOrdinal) {
    requireNoAmbientTransaction();
    CanonicalGameplayAccountAdmissionFenceIntent intent =
        intentRepository.readCurrentFenceIntent(operationId);
    if (!operationId.equals(intent.operationId())
        || intent.state()
            != CanonicalGameplayAccountAdmissionFenceIntent.State.ACQUISITION_INTENT) {
      throw unavailable();
    }
    List<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget> targets =
        intent.barrierTargets().stream()
            .filter(target -> target.ordinal() == targetOrdinal)
            .toList();
    if (targets.size() != 1) throw unavailable();
    CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget target = targets.get(0);

    requireNoAmbientTransaction();
    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
        tenantTagRepository.assignmentForBarrierTarget(operationId, targetOrdinal);
    if (assignment.isBindingTransition()
        || !operationId.equals(assignment.admissionFenceIntentOperationId())
        || !Long.valueOf(targetOrdinal).equals(assignment.barrierTargetOrdinal())
        || !intent.accountId().equals(assignment.accountId())
        || !target.tenantId().equals(assignment.tenantId())
        || !target.playableStateNamespaceId().equals(assignment.playableStateNamespaceId())) {
      throw unavailable();
    }
    String key = CanonicalGameplaySessionRedisKeys.accountAdmissionBarrier(assignment);
    if (!key.startsWith(KEY_PREFIX)) throw unavailable();
    if (!canonicalPositiveDecimal(intent.operationFence().toString())
        || !canonicalPositiveDecimal(intent.inventorySnapshotRevision().toString())) {
      throw unavailable();
    }
    return new BarrierContext(intent, targetOrdinal, assignment, key);
  }

  private static Map<String, String> expectedFields(
      BarrierContext context, String incarnationDigest) {
    if (!canonicalDigest(incarnationDigest)) throw unavailable();
    CanonicalGameplayAccountAdmissionFenceIntent intent = context.intent();
    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment = context.assignment();
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("schema", SCHEMA);
    fields.put("accountId", intent.accountId().toString());
    fields.put("tenantId", assignment.tenantId().toString());
    fields.put("playableStateNamespaceId", assignment.playableStateNamespaceId().toString());
    fields.put("tenantGameplayTag", assignment.tenantGameplayTag());
    fields.put("ownerOperationId", intent.operationId().toString());
    fields.put("ownerFenceEpoch", intent.operationFence().toString());
    fields.put("incarnationDigest", incarnationDigest);
    fields.put("state", CLOSED);
    return Map.copyOf(fields);
  }

  private static void requireIncarnationMatches(
      BarrierContext context,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation incarnation) {
    Objects.requireNonNull(incarnation, "verified current-incarnation evidence");
    CanonicalGameplayAccountAdmissionFenceIntent intent = context.intent();
    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment = context.assignment();
    if (!intent.operationId().equals(incarnation.operationId())
        || incarnation.targetOrdinal() != context.targetOrdinal()
        || !intent.accountId().equals(incarnation.accountId())
        || !assignment.tenantId().equals(incarnation.tenantId())
        || !assignment.playableStateNamespaceId().equals(incarnation.playableStateNamespaceId())
        || !intent.plannedAccountAdmissionFence().equals(incarnation.plannedAccountAdmissionFence())
        || !intent.operationFence().toString().equals(incarnation.operationFence())
        || !intent.cohortDigest().equals(incarnation.cohortDigest())
        || !intent.barrierTargetDigest().equals(incarnation.barrierTargetDigest())
        || !assignment.tenantGameplayTag().equals(incarnation.tenantGameplayTag())
        || !canonicalDigest(incarnation.incarnationDigest())) {
      throw unavailable();
    }
  }

  private long executeMutation(byte[] source, byte[] key, byte[][] arguments) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      String loadedSha = await(commands.scriptLoad(source));
      if (!digest("SHA-1", source).equals(loadedSha)) throw unavailable();
      RedisFuture<Long> mutation =
          commands.evalsha(loadedSha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments);
      Long reply = await(mutation);
      if (reply == null || reply < 0L) throw unavailable();
      return reply;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private Map<String, String> readPhysicalBarrier(byte[] key, Map<String, String> expected) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      if (!"hash".equals(await(commands.type(key)))
          || !Long.valueOf(-1L).equals(await(commands.pttl(key)))
          || !Long.valueOf(FIELDS.size()).equals(await(commands.hlen(key)))) {
        throw unavailable();
      }
      Map<byte[], byte[]> physical = await(commands.hgetall(key));
      if (physical == null || physical.size() != FIELDS.size()) throw unavailable();
      Map<String, String> actual = new LinkedHashMap<>();
      for (Map.Entry<byte[], byte[]> entry : physical.entrySet()) {
        String field = strictAscii(entry.getKey());
        String value = strictAscii(entry.getValue());
        if (!EXPECTED_FIELD_SET.contains(field) || actual.putIfAbsent(field, value) != null) {
          throw unavailable();
        }
      }
      if (!EXPECTED_FIELD_SET.equals(actual.keySet()) || !expected.equals(actual)) {
        throw unavailable();
      }
      return Map.copyOf(actual);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static byte[][] arguments(BarrierContext context, String incarnationDigest) {
    Map<String, String> fields = expectedFields(context, incarnationDigest);
    return new byte[][] {
      ascii(fields.get("schema")),
      ascii(fields.get("accountId")),
      ascii(fields.get("tenantId")),
      ascii(fields.get("playableStateNamespaceId")),
      ascii(fields.get("tenantGameplayTag")),
      ascii(fields.get("ownerOperationId")),
      ascii(fields.get("ownerFenceEpoch")),
      ascii(fields.get("incarnationDigest")),
      ascii(fields.get("state"))
    };
  }

  private static MutationOutcome mutationOutcome(long reply) {
    if (reply == 1L) return MutationOutcome.CREATED_CLOSED;
    if (reply == 2L) return MutationOutcome.EXACT_CLOSED_REPLAY;
    if (reply == 3L) return MutationOutcome.ADVANCED_TO_CLOSED;
    throw unavailable();
  }

  private void requireUnexpired(
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation incarnation) {
    long now = clock.millis();
    if (now < incarnation.issuedAtEpochMillis() || now >= incarnation.expiresAtEpochMillis()) {
      throw unavailable();
    }
  }

  private RedisScriptDescriptor requireDescriptor() {
    try {
      RedisScriptDescriptor descriptor =
          catalog.require(CanonicalGameplayAccountClosedBarrierRedisScriptContribution.SCRIPT_ID);
      if (!CanonicalGameplayAccountClosedBarrierRedisScriptContribution.SCRIPT_ID.equals(
              descriptor.scriptId())
          || !CanonicalGameplayAccountClosedBarrierRedisScriptContribution.RESOURCE_PATH.equals(
              descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || descriptor.category() != RedisScriptDescriptor.ScriptCategory.SESSION_CAS
          || !EXPECTED_KEYS.equals(descriptor.keys())
          || !EXPECTED_ARGUMENTS.equals(descriptor.arguments())
          || !EXPECTED_OUTCOMES.equals(descriptor.outcomes())
          || descriptor.resetSensitivity() != RedisScriptDescriptor.ResetSensitivity.CLUSTER
          || descriptor.lossClass()
              != RedisScriptDescriptor.LossClass.EMPTY_REDIS_COLD_START_OR_RESET
          || descriptor.compatibilityLevel()
              != RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET
          || !descriptor
              .supportedCoexistence()
              .equals(List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "none")))
          || descriptor.legacyPayloadShape() != null) {
        throw unavailable();
      }
      return descriptor;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static byte[] source(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        CanonicalGameplayAccountClosedBarrierRedisOwner.class
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

  private static RedisAsyncCommands<byte[], byte[]> requireCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null || !connection.isOpen()) throw unavailable();
    return connection.async();
  }

  private static <T> T await(RedisFuture<T> future) {
    try {
      return future.get(REDIS_COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw unavailable(interrupted);
    } catch (ExecutionException | TimeoutException | RuntimeException failure) {
      future.cancel(true);
      throw unavailable(failure);
    }
  }

  private static void requirePrincipal(String actual) {
    if (!PRINCIPAL.equals(actual)) throw unavailable();
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable();
    }
  }

  private static String strictAscii(byte[] value) {
    if (value == null) throw unavailable();
    String decoded = new String(value, StandardCharsets.US_ASCII);
    if (!Arrays.equals(value, ascii(decoded))) throw unavailable();
    return decoded;
  }

  private static boolean canonicalPositiveDecimal(String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_DECIMAL_BYTES) return false;
    if (value.charAt(0) < '1' || value.charAt(0) > '9') return false;
    for (int index = 1; index < value.length(); index++) {
      if (value.charAt(index) < '0' || value.charAt(index) > '9') return false;
    }
    return true;
  }

  private static boolean canonicalDigest(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) throw unavailable();
  }

  private static byte[] ascii(String value) {
    if (value == null) throw unavailable();
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    if (!value.equals(new String(bytes, StandardCharsets.US_ASCII))) throw unavailable();
    return bytes;
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw unavailable(failure);
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("CLOSED account barrier is unavailable");
  }

  private static IllegalStateException unavailable(Throwable cause) {
    return new IllegalStateException("CLOSED account barrier is unavailable", cause);
  }

  private record BarrierContext(
      CanonicalGameplayAccountAdmissionFenceIntent intent,
      long targetOrdinal,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      String key) {

    private boolean sameOwnerContext(BarrierContext other) {
      return other != null
          && intent.operationId().equals(other.intent.operationId())
          && intent.requestId().equals(other.intent.requestId())
          && intent.accountId().equals(other.intent.accountId())
          && intent.operationFence().equals(other.intent.operationFence())
          && intent
              .plannedAccountAdmissionFence()
              .equals(other.intent.plannedAccountAdmissionFence())
          && intent.inventorySnapshotRevision().equals(other.intent.inventorySnapshotRevision())
          && intent.cohortDigest().equals(other.intent.cohortDigest())
          && intent.barrierTargetDigest().equals(other.intent.barrierTargetDigest())
          && targetOrdinal == other.targetOrdinal
          && assignment.accountId().equals(other.assignment.accountId())
          && assignment.tenantId().equals(other.assignment.tenantId())
          && assignment
              .playableStateNamespaceId()
              .equals(other.assignment.playableStateNamespaceId())
          && assignment.tenantGameplayTag().equals(other.assignment.tenantGameplayTag())
          && assignment.workloadNamespace().equals(other.assignment.workloadNamespace())
          && assignment.allocationVersion() == other.assignment.allocationVersion()
          && key.equals(other.key);
    }
  }

  /** Opaque physical CLOSED readback; construction is private to this Redis owner. */
  static final class Readback {
    private final CanonicalGameplayAccountAdmissionFenceIntent intent;
    private final CanonicalGameplayTenantGameplayTagRepository.Assignment assignment;
    private final UUID operationId;
    private final UUID accountId;
    private final long targetOrdinal;
    private final String key;
    private final Map<String, String> barrierFields;
    private final MutationOutcome mutationOutcome;
    private final CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before;
    private final CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after;

    private Readback(
        BarrierContext context,
        Map<String, String> barrierFields,
        MutationOutcome mutationOutcome,
        CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before,
        CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after) {
      this.intent = context.intent();
      this.assignment = context.assignment();
      this.operationId = context.intent().operationId();
      this.accountId = context.intent().accountId();
      this.targetOrdinal = context.targetOrdinal();
      this.key = context.key();
      this.barrierFields = Map.copyOf(barrierFields);
      this.mutationOutcome = Objects.requireNonNull(mutationOutcome, "mutationOutcome");
      this.before = Objects.requireNonNull(before, "before verified incarnation");
      this.after = Objects.requireNonNull(after, "after verified incarnation");
      if (!this.barrierFields.equals(expectedFields(context, before.incarnationDigest()))
          || !CLOSED.equals(this.barrierFields.get("state"))
          || !this.before.incarnationDigest().equals(this.after.incarnationDigest())) {
        throw unavailable();
      }
    }

    UUID operationId() {
      return operationId;
    }

    CanonicalGameplayAccountAdmissionFenceIntent intent() {
      return intent;
    }

    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment() {
      return assignment;
    }

    UUID accountId() {
      return accountId;
    }

    long targetOrdinal() {
      return targetOrdinal;
    }

    String key() {
      return key;
    }

    MutationOutcome mutationOutcome() {
      return mutationOutcome;
    }

    Map<String, String> barrierFields() {
      return barrierFields;
    }

    CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation beforeIncarnation() {
      return before;
    }

    CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation afterIncarnation() {
      return after;
    }
  }
}
