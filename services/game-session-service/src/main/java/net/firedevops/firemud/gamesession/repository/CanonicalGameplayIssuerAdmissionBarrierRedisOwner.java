package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
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
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit manual owner for exact one-key issuer tenant barrier projection and readback. */
final class CanonicalGameplayIssuerAdmissionBarrierRedisOwner {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String KEY_PREFIX = "session:game:issuer-admission:";
  private static final int MAX_SCRIPT_BYTES = 32 * 1024;
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000;
  private static final List<String> FIELDS =
      List.of(
          "schema",
          "issuerId",
          "tenantId",
          "playableStateNamespaceId",
          "tenantGameplayTag",
          "state",
          "admissionEpoch",
          "ownerOperationId",
          "initializerOperationId",
          "initializerEpoch",
          "initializerDigest",
          "payloadDigest",
          "payload");

  private final io.lettuce.core.RedisClient redisClient;
  private final RedisScriptCatalog catalog;
  private final ProjectionRepository repository;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentStateSource;

  CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
      io.lettuce.core.RedisClient redisClient,
      RedisScriptCatalog catalog,
      ProjectionRepository repository,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentStateSource) {
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Redis client is required");
    this.catalog = Objects.requireNonNull(catalog, "Manual Redis script catalog is required");
    this.repository =
        Objects.requireNonNull(repository, "Actual SQL projection repository is required");
    this.currentStateSource =
        Objects.requireNonNull(currentStateSource, "Fresh issuer source is required");
  }

  /** Writes and independently reads back one exact original initializer's OPEN payload. */
  Readback initializeAndRead(UUID operationId) {
    Objects.requireNonNull(operationId, "operationId");
    try {
      requireNoAmbientTransaction();
      PreparedInitializer prepared = repository.readPreparedInitializer(operationId);
      requireCurrentState(prepared.value().initializer());
      repository.requireStillCurrent(prepared);
      String createMode = prepared.consumeCreatePermit() ? "CREATE_ONCE" : "REPLAY_ONLY";
      RedisScriptDescriptor descriptor =
          requireDescriptor(
              CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution.OPEN_SCRIPT_ID,
              CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution.OPEN_RESOURCE_PATH,
              OPEN_ARGUMENTS,
              OPEN_OUTCOMES);
      byte[] script = scriptSource(descriptor);
      CanonicalGameplayIssuerAdmissionBarrierValue value = prepared.value();
      byte[] payload = value.exactBytes();
      long result =
          writeOneKey(
              script,
              prepared.key(),
              ascii(value.issuerId()),
              ascii(value.tenantId().toString()),
              ascii(value.playableStateNamespaceId().toString()),
              ascii(value.tenantGameplayTag()),
              ascii(value.ownerOperationId().toString()),
              ascii(value.admissionEpoch().toString()),
              ascii(value.initializer().exactDigest()),
              ascii(value.exactDigest()),
              payload,
              ascii(value.state().name()),
              ascii(createMode));
      if (result < 1 || result > 2) throw unavailable();
      byte[] observed = readExactPhysicalState(prepared.key(), value);
      requireCurrentState(value.initializer());
      repository.requireStillCurrent(prepared);
      Readback readback = new Readback(value, result, observed);
      repository.recordOpenReadback(prepared, readback);
      return readback;
    } catch (RuntimeException failure) {
      if (isUnavailable(failure)) throw failure;
      throw unavailable(failure);
    }
  }

  /** Closes one exact captured cutoff target and independently reads back its CLOSED payload. */
  Readback closeAndRead(UUID operationId, UUID tenantId, UUID namespaceId) {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(namespaceId, "namespaceId");
    try {
      requireNoAmbientTransaction();
      PreparedCutoffTarget prepared =
          repository.readPreparedCutoffTarget(operationId, tenantId, namespaceId);
      repository.requireStillCurrent(prepared);
      RedisScriptDescriptor descriptor =
          requireDescriptor(
              CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution.CLOSE_SCRIPT_ID,
              CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution.CLOSE_RESOURCE_PATH,
              CLOSE_ARGUMENTS,
              CLOSE_OUTCOMES);
      byte[] script = scriptSource(descriptor);
      CanonicalGameplayIssuerAdmissionBarrierValue closed = prepared.closedValue();
      CanonicalGameplayIssuerAdmissionBarrierValue.Initializer initializer = closed.initializer();
      CanonicalGameplayIssuerAdmissionBarrierValue expectedOpen = prepared.expectedOpenValue();
      byte[] payload = closed.exactBytes();
      long result =
          writeOneKey(
              script,
              prepared.key(),
              ascii(closed.issuerId()),
              ascii(closed.tenantId().toString()),
              ascii(closed.playableStateNamespaceId().toString()),
              ascii(closed.tenantGameplayTag()),
              ascii(closed.ownerOperationId().toString()),
              ascii(closed.admissionEpoch().toString()),
              ascii(closed.exactDigest()),
              payload,
              ascii(initializer == null ? "none" : initializer.operationId().toString()),
              ascii(initializer == null ? "0" : initializer.admissionEpoch().toString()),
              ascii(initializer == null ? "none" : initializer.exactDigest()),
              ascii(expectedOpen == null ? "none" : expectedOpen.exactDigest()),
              expectedOpen == null ? new byte[0] : expectedOpen.exactBytes(),
              ascii(closed.state().name()));
      if (result < 1 || result > 3) throw unavailable();
      byte[] observed = readExactPhysicalState(prepared.key(), closed);
      repository.requireStillCurrent(prepared);
      Readback readback = new Readback(closed, result, observed);
      repository.recordClosedReadback(prepared, readback);
      return readback;
    } catch (RuntimeException failure) {
      if (isUnavailable(failure)) throw failure;
      throw unavailable(failure);
    }
  }

  private void requireCurrentState(
      CanonicalGameplayIssuerAdmissionBarrierValue.Initializer initializer) {
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current;
    try {
      current = currentStateSource.readFreshCurrentState();
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
    if (current == null || !initializer.matchesCurrentIssuerState(current)) throw unavailable();
  }

  private byte[] readExactPhysicalState(
      String key, CanonicalGameplayIssuerAdmissionBarrierValue expected) {
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
      Map<String, byte[]> actual = new HashMap<>();
      for (Map.Entry<byte[], byte[]> entry : raw.entrySet()) {
        String field = asciiString(entry.getKey());
        if (!FIELDS.contains(field)
            || actual.putIfAbsent(field, Objects.requireNonNull(entry.getValue()).clone())
                != null) {
          throw unavailable();
        }
      }
      Map<String, byte[]> wanted = physicalFields(expected);
      if (actual.size() != wanted.size()) throw unavailable();
      for (Map.Entry<String, byte[]> entry : wanted.entrySet()) {
        if (!Arrays.equals(actual.get(entry.getKey()), entry.getValue())) throw unavailable();
      }
      byte[] payload = actual.get("payload");
      CanonicalGameplayIssuerAdmissionBarrierValue decoded =
          CanonicalGameplayIssuerAdmissionBarrierValue.decode(payload);
      if (!Arrays.equals(decoded.exactBytes(), expected.exactBytes())
          || !decoded.exactDigest().equals(expected.exactDigest())) throw unavailable();
      return payload.clone();
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private long writeOneKey(byte[] script, String key, byte[]... arguments) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      String sha = await(commands.scriptLoad(script));
      if (!digest("SHA-1", script).equals(sha)) throw unavailable();
      Long reply =
          await(
              commands.evalsha(
                  sha, ScriptOutputType.INTEGER, new byte[][] {ascii(key)}, arguments));
      if (reply == null) throw unavailable();
      return reply;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private RedisScriptDescriptor requireDescriptor(
      String scriptId,
      String resourcePath,
      List<RedisScriptDescriptor.ArgumentSpec> arguments,
      List<RedisScriptDescriptor.OutcomeSpec> outcomes) {
    try {
      RedisScriptDescriptor descriptor = catalog.require(scriptId);
      if (!scriptId.equals(descriptor.scriptId())
          || !resourcePath.equals(descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || !List.of(
                  new RedisScriptDescriptor.KeySpec(
                      "issuerAdmissionBarrier",
                      KEY_PREFIX,
                      RedisScriptDescriptor.HashTagDeclaration.REQUIRED))
              .equals(descriptor.keys())
          || !arguments.equals(descriptor.arguments())
          || descriptor.category() != RedisScriptDescriptor.ScriptCategory.SESSION_CAS
          || !outcomes.equals(descriptor.outcomes())
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
        CanonicalGameplayIssuerAdmissionBarrierRedisOwner.class
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

  static Map<String, byte[]> physicalFields(CanonicalGameplayIssuerAdmissionBarrierValue value) {
    var initializer = value.initializer();
    Map<String, byte[]> fields = new HashMap<>();
    fields.put("schema", ascii(CanonicalGameplayIssuerAdmissionBarrierValue.SCHEMA));
    fields.put("issuerId", ascii(value.issuerId()));
    fields.put("tenantId", ascii(value.tenantId().toString()));
    fields.put("playableStateNamespaceId", ascii(value.playableStateNamespaceId().toString()));
    fields.put("tenantGameplayTag", ascii(value.tenantGameplayTag()));
    fields.put("state", ascii(value.state().name()));
    fields.put("admissionEpoch", ascii(value.admissionEpoch().toString()));
    fields.put("ownerOperationId", ascii(value.ownerOperationId().toString()));
    fields.put(
        "initializerOperationId",
        ascii(initializer == null ? "none" : initializer.operationId().toString()));
    fields.put(
        "initializerEpoch",
        ascii(initializer == null ? "0" : initializer.admissionEpoch().toString()));
    fields.put(
        "initializerDigest", ascii(initializer == null ? "none" : initializer.exactDigest()));
    fields.put("payloadDigest", ascii(value.exactDigest()));
    fields.put("payload", value.exactBytes());
    return fields;
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

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
  }

  private static boolean isUnavailable(RuntimeException failure) {
    return failure instanceof IllegalStateException
        && failure.getMessage() != null
        && failure.getMessage().contains("Issuer tenant admission barrier is unavailable");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Issuer tenant admission barrier is unavailable");
  }

  private static IllegalStateException unavailable(Throwable cause) {
    return new IllegalStateException("Issuer tenant admission barrier is unavailable", cause);
  }

  private static final List<RedisScriptDescriptor.ArgumentSpec> OPEN_ARGUMENTS =
      List.of(
          new RedisScriptDescriptor.ArgumentSpec("issuerId"),
          new RedisScriptDescriptor.ArgumentSpec("tenantId"),
          new RedisScriptDescriptor.ArgumentSpec("playableStateNamespaceId"),
          new RedisScriptDescriptor.ArgumentSpec("tenantGameplayTag"),
          new RedisScriptDescriptor.ArgumentSpec("initializerOperationId"),
          new RedisScriptDescriptor.ArgumentSpec("admissionBarrierEpoch"),
          new RedisScriptDescriptor.ArgumentSpec("initializerTupleDigest"),
          new RedisScriptDescriptor.ArgumentSpec("exactBarrierPayloadDigest"),
          new RedisScriptDescriptor.ArgumentSpec("exactBarrierPayload"),
          new RedisScriptDescriptor.ArgumentSpec("state"),
          new RedisScriptDescriptor.ArgumentSpec("createMode"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> OPEN_OUTCOMES =
      List.of(
          outcome(
              "OPEN_CREATED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          outcome(
              "OPEN_REPLAY",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "TYPE_TTL_OR_FIELDSET_REFUSED",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "INVALID_ARGUMENTS_OR_TARGET",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "CLOSED_OR_CONFLICTING_STATE",
              RedisScriptDescriptor.OutcomeCategory.STALE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING));
  private static final List<RedisScriptDescriptor.ArgumentSpec> CLOSE_ARGUMENTS =
      List.of(
          new RedisScriptDescriptor.ArgumentSpec("issuerId"),
          new RedisScriptDescriptor.ArgumentSpec("tenantId"),
          new RedisScriptDescriptor.ArgumentSpec("playableStateNamespaceId"),
          new RedisScriptDescriptor.ArgumentSpec("tenantGameplayTag"),
          new RedisScriptDescriptor.ArgumentSpec("cutoffOperationId"),
          new RedisScriptDescriptor.ArgumentSpec("cutoffAdmissionBarrierEpoch"),
          new RedisScriptDescriptor.ArgumentSpec("exactClosedPayloadDigest"),
          new RedisScriptDescriptor.ArgumentSpec("exactClosedPayload"),
          new RedisScriptDescriptor.ArgumentSpec("capturedInitializerOperationIdOrNone"),
          new RedisScriptDescriptor.ArgumentSpec("capturedInitializerAdmissionBarrierEpochOrZero"),
          new RedisScriptDescriptor.ArgumentSpec("capturedInitializerTupleDigestOrNone"),
          new RedisScriptDescriptor.ArgumentSpec("expectedOriginalOpenPayloadDigestOrNone"),
          new RedisScriptDescriptor.ArgumentSpec("expectedOriginalOpenPayloadOrEmpty"),
          new RedisScriptDescriptor.ArgumentSpec("state"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> CLOSE_OUTCOMES =
      List.of(
          outcome(
              "CLOSED_CREATED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          outcome(
              "CLOSED_REPLAY",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "CAPTURED_OPEN_CLOSED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          outcome(
              "TYPE_TTL_OR_FIELDSET_REFUSED",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "INVALID_ARGUMENTS_OR_TARGET",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "NEWER_CLOSED_OR_UNEXPECTED_STATE",
              RedisScriptDescriptor.OutcomeCategory.STALE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING));

  private static RedisScriptDescriptor.OutcomeSpec outcome(
      String code,
      RedisScriptDescriptor.OutcomeCategory category,
      RedisScriptDescriptor.MutationEffect effect) {
    return new RedisScriptDescriptor.OutcomeSpec(code, category, effect);
  }

  interface ProjectionRepository {
    PreparedInitializer readPreparedInitializer(UUID operationId);

    PreparedCutoffTarget readPreparedCutoffTarget(
        UUID cutoffOperationId, UUID tenantId, UUID namespaceId);

    void requireStillCurrent(PreparedInitializer prepared);

    void requireStillCurrent(PreparedCutoffTarget prepared);

    void recordOpenReadback(PreparedInitializer prepared, Readback readback);

    void recordClosedReadback(PreparedCutoffTarget prepared, Readback readback);
  }

  static final class PreparedInitializer {
    private final CanonicalGameplayIssuerAdmissionBarrierValue value;
    private final CanonicalGameplayTenantGameplayTagRepository.Assignment assignment;
    private final UUID requestOperationId;
    private final String key;
    private final java.util.concurrent.atomic.AtomicBoolean createPermit;

    PreparedInitializer(
        CanonicalGameplayIssuerAdmissionBarrierValue value,
        CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
        UUID requestOperationId,
        String key,
        boolean createPermit) {
      this.value = Objects.requireNonNull(value, "value");
      this.assignment = assignment;
      this.requestOperationId = Objects.requireNonNull(requestOperationId, "requestOperationId");
      this.key = Objects.requireNonNull(key, "key");
      this.createPermit = new java.util.concurrent.atomic.AtomicBoolean(createPermit);
      if (value.state() != CanonicalGameplayIssuerAdmissionBarrierValue.State.OPEN
          || !CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(
                  value.issuerId(),
                  value.tenantId(),
                  value.playableStateNamespaceId(),
                  value.tenantGameplayTag())
              .equals(key)) throw unavailable();
      if (assignment == null) {
        if (!requestOperationId.equals(value.initializer().operationId())) throw unavailable();
      } else if (!assignment.isBindingTransition()
          || !assignment.tenantId().equals(value.tenantId())
          || !assignment.playableStateNamespaceId().equals(value.playableStateNamespaceId())
          || !assignment.tenantGameplayTag().equals(value.tenantGameplayTag())) {
        throw unavailable();
      }
    }

    CanonicalGameplayIssuerAdmissionBarrierValue value() {
      return value;
    }

    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment() {
      return assignment;
    }

    UUID requestOperationId() {
      return requestOperationId;
    }

    String key() {
      return key;
    }

    /** Consumes the SQL owner's first-attempt authority before any Redis command is attempted. */
    boolean consumeCreatePermit() {
      return createPermit.getAndSet(false);
    }
  }

  record PreparedCutoffTarget(
      CanonicalGameplayIssuerAdmissionBarrierValue closedValue,
      CanonicalGameplayIssuerAdmissionBarrierValue expectedOpenValue,
      String key) {
    PreparedCutoffTarget {
      Objects.requireNonNull(closedValue, "closedValue");
      Objects.requireNonNull(key, "key");
      if (closedValue.state() != CanonicalGameplayIssuerAdmissionBarrierValue.State.CLOSED
          || (expectedOpenValue != null
              && (expectedOpenValue.state()
                      != CanonicalGameplayIssuerAdmissionBarrierValue.State.OPEN
                  || closedValue.initializer() == null
                  || !Arrays.equals(
                      closedValue.initializer().exactBytes(),
                      expectedOpenValue.initializer().exactBytes())))
          || !CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(
                  closedValue.issuerId(),
                  closedValue.tenantId(),
                  closedValue.playableStateNamespaceId(),
                  closedValue.tenantGameplayTag())
              .equals(key)) throw unavailable();
    }
  }

  static final class Readback {
    private final CanonicalGameplayIssuerAdmissionBarrierValue value;
    private final long scriptOutcome;
    private final byte[] exactPhysicalPayload;

    private Readback(
        CanonicalGameplayIssuerAdmissionBarrierValue value,
        long scriptOutcome,
        byte[] exactPhysicalPayload) {
      this.value = Objects.requireNonNull(value, "value");
      if (scriptOutcome < 1 || scriptOutcome > 3) throw unavailable();
      this.scriptOutcome = scriptOutcome;
      this.exactPhysicalPayload = Objects.requireNonNull(exactPhysicalPayload).clone();
      if (!Arrays.equals(value.exactBytes(), this.exactPhysicalPayload)) throw unavailable();
    }

    CanonicalGameplayIssuerAdmissionBarrierValue value() {
      return value;
    }

    long scriptOutcome() {
      return scriptOutcome;
    }

    byte[] exactPhysicalPayload() {
      return exactPhysicalPayload.clone();
    }
  }
}
