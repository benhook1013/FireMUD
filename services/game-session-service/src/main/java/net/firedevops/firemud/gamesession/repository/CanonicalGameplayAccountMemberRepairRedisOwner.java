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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberRepairOperation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingAccountIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountMemberRepairRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Projects one frozen binding-local ADD_OR_RETAIN obligation to the untagged global account set.
 * This owner emits only physical Redis evidence; SQL independently accepts the original tuple.
 */
public final class CanonicalGameplayAccountMemberRepairRedisOwner {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String ACCOUNT_KEY_PREFIX = "session:game:index:account:";
  private static final int MAX_SCRIPT_BYTES = 32 * 1024;
  private static final int MAX_BINDING_REF_BYTES = 6_144;
  private static final int MAX_MEMBER_BYTES = 8_192;
  private static final int MAX_DECIMAL_BYTES = 256;
  private static final long REDIS_COMMAND_TIMEOUT_MILLIS = 5_000;

  private static final List<RedisScriptDescriptor.KeySpec> EXPECTED_KEYS =
      List.of(
          new RedisScriptDescriptor.KeySpec(
              "accountIndex",
              ACCOUNT_KEY_PREFIX,
              RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED));
  private static final List<RedisScriptDescriptor.ArgumentSpec> EXPECTED_ARGUMENTS =
      List.of(
          new RedisScriptDescriptor.ArgumentSpec("accountId"),
          new RedisScriptDescriptor.ArgumentSpec("tenantId"),
          new RedisScriptDescriptor.ArgumentSpec("canonicalBindingRefBytes"),
          new RedisScriptDescriptor.ArgumentSpec("bindingGeneration"),
          new RedisScriptDescriptor.ArgumentSpec("accountIndexFence"),
          new RedisScriptDescriptor.ArgumentSpec("exactAccountIndexMember"),
          new RedisScriptDescriptor.ArgumentSpec("transitionId"),
          new RedisScriptDescriptor.ArgumentSpec("operationId"),
          new RedisScriptDescriptor.ArgumentSpec("operationFence"),
          new RedisScriptDescriptor.ArgumentSpec("coverageFence"),
          new RedisScriptDescriptor.ArgumentSpec("inventorySnapshotRevision"),
          new RedisScriptDescriptor.ArgumentSpec("accountControlEpoch"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> EXPECTED_OUTCOMES =
      List.of(
          new RedisScriptDescriptor.OutcomeSpec(
              "MEMBER_ADDED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "MEMBER_ALREADY_PRESENT",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "WRONG_KEY_TYPE_OR_EXPIRY",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          new RedisScriptDescriptor.OutcomeSpec(
              "INVALID_ARGUMENTS",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING));

  private final RedisClient redisClient;
  private final RedisScriptCatalog catalog;

  /** Uses the caller's configured Redis client and installed owner contribution catalog. */
  public CanonicalGameplayAccountMemberRepairRedisOwner(
      RedisClient redisClient, RedisScriptCatalog catalog) {
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Redis client required");
    this.catalog = Objects.requireNonNull(catalog, "Installed Redis script catalog required");
  }

  /**
   * Adds or retains exactly the frozen generation-qualified member and reads that member back on a
   * separate connection from the same configured Redis client. No SQL call or admission decision
   * occurs in this phase.
   */
  public Readback projectAndRead(CanonicalGameplayAccountMemberRepairOperation operation) {
    validateOperation(operation);
    requireNoAmbientTransaction();
    RedisScriptDescriptor descriptor = requireDescriptor();
    byte[] source = source(descriptor);
    byte[] key = accountKey(operation.accountId());
    byte[] member = operation.member().getBytes(StandardCharsets.US_ASCII);

    try (StatefulRedisConnection<byte[], byte[]> writeConnection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(writeConnection);
      requirePrincipal(await(commands.aclWhoami()));
      String loadedSha = await(commands.scriptLoad(source));
      if (!digest("SHA-1", source).equals(loadedSha)) throw unavailable();
      RedisFuture<Long> mutation =
          commands.evalsha(
              loadedSha,
              ScriptOutputType.INTEGER,
              new byte[][] {key},
              operation.accountId().toString().getBytes(StandardCharsets.US_ASCII),
              operation.tenantId().toString().getBytes(StandardCharsets.US_ASCII),
              operation.bindingRef().bytes(),
              operation.bindingGeneration().toString().getBytes(StandardCharsets.US_ASCII),
              operation.accountIndexFence().toString().getBytes(StandardCharsets.US_ASCII),
              member,
              operation.transitionId().toString().getBytes(StandardCharsets.US_ASCII),
              operation.operationId().toString().getBytes(StandardCharsets.US_ASCII),
              operation.operationFence().toString().getBytes(StandardCharsets.US_ASCII),
              operation.coverageFence().toString().getBytes(StandardCharsets.US_ASCII),
              operation.inventorySnapshotRevision().toString().getBytes(StandardCharsets.US_ASCII),
              operation.accountControlEpoch().toString().getBytes(StandardCharsets.US_ASCII));
      Long reply = await(mutation);
      if (reply == null || (reply != 1L && reply != 2L)) throw unavailable();
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }

    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> readConnection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(readConnection);
      requirePrincipal(await(commands.aclWhoami()));
      if (!"set".equals(await(commands.type(key)))
          || !Long.valueOf(-1L).equals(await(commands.pttl(key)))
          || !Boolean.TRUE.equals(await(commands.sismember(key, member)))) throw unavailable();
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
    // Both a new SADD (1) and an exact idempotent SADD replay (2) require the physical read above.
    return new Readback(operation, operation.member());
  }

  private static void validateOperation(CanonicalGameplayAccountMemberRepairOperation operation) {
    Objects.requireNonNull(operation, "Binding-local repair operation required");
    requireNonNil(operation.transitionId(), "transitionId");
    requireNonNil(operation.operationId(), "operationId");
    requirePositive(operation.operationFence(), "operationFence");
    requireNonNil(operation.coverageFence(), "coverageFence");
    requirePositive(operation.inventorySnapshotRevision(), "inventorySnapshotRevision");
    requireNonNil(operation.accountId(), "accountId");
    requireNonNil(operation.tenantId(), "tenantId");
    requirePositive(operation.bindingGeneration(), "bindingGeneration");
    requireNonNil(operation.accountIndexFence(), "accountIndexFence");
    Objects.requireNonNull(operation.accountControlEpoch(), "accountControlEpoch");
    if (operation.accountControlEpoch().signum() < 0
        || operation.operationId().equals(operation.transitionId())
        || operation.coverageFence().equals(operation.transitionId())
        || operation.coverageFence().equals(operation.operationId())
        || operation.coverageFence().equals(operation.accountIndexFence())) {
      throw unavailable();
    }

    CanonicalGameplayBindingRef bindingRef;
    try {
      bindingRef = CanonicalGameplayBindingRef.fromExactBytes(operation.bindingRef().bytes());
    } catch (RuntimeException malformed) {
      throw unavailable(malformed);
    }
    if (!bindingRef.equals(operation.bindingRef())
        || !operation.tenantId().toString().equals(bindingRef.tenantId())) throw unavailable();

    var expectedMember =
        new CanonicalGameplayAccountIndexMember(
                bindingRef, operation.bindingGeneration(), operation.accountIndexFence())
            .value();
    if (expectedMember.length() > MAX_MEMBER_BYTES
        || !expectedMember.equals(operation.member())
        || bindingRef.bytes().length > MAX_BINDING_REF_BYTES) throw unavailable();

    CanonicalGameplayBindingAccountIndexObligation obligation = operation.originalObligation();
    if (!operation.transitionId().equals(obligation.transitionId())
        || obligation.ordinal() != 0
        || !bindingRef.equals(obligation.bindingRef())
        || !operation.accountId().equals(obligation.accountId())
        || !operation.tenantId().equals(obligation.tenantId())
        || obligation.action()
            != CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN
        || !operation.bindingGeneration().equals(obligation.bindingGeneration())
        || !operation.accountIndexFence().equals(obligation.accountIndexFence())
        || obligation.executionPhase()
            != CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS
        || obligation.status() != CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED
        || obligation.expectedPriorGeneration() != null
        || obligation.projectionState()
            == CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                .ABSENT_AWAITING_COVERAGE
        || obligation.inventoryRevision().compareTo(operation.inventorySnapshotRevision()) > 0) {
      throw unavailable();
    }
    if (obligation.projectionState()
        == CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED) {
      if (obligation.projectedMember() != null || obligation.projectionInventoryRevision() != null)
        throw unavailable();
    } else if (!operation.member().equals(obligation.projectedMember())
        || obligation.projectionInventoryRevision() == null
        || obligation.projectionInventoryRevision().signum() <= 0) {
      throw unavailable();
    }

    if (!canonicalDecimal(operation.bindingGeneration().toString(), false)
        || !canonicalDecimal(operation.operationFence().toString(), false)
        || !canonicalDecimal(operation.inventorySnapshotRevision().toString(), false)
        || !canonicalDecimal(operation.accountControlEpoch().toString(), true)) throw unavailable();
  }

  private RedisScriptDescriptor requireDescriptor() {
    try {
      var descriptor =
          catalog.require(CanonicalGameplayAccountMemberRepairRedisScriptContribution.SCRIPT_ID);
      if (!CanonicalGameplayAccountMemberRepairRedisScriptContribution.SCRIPT_ID.equals(
              descriptor.scriptId())
          || !CanonicalGameplayAccountMemberRepairRedisScriptContribution.RESOURCE_PATH.equals(
              descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || !EXPECTED_KEYS.equals(descriptor.keys())
          || !EXPECTED_ARGUMENTS.equals(descriptor.arguments())
          || descriptor.category() != RedisScriptDescriptor.ScriptCategory.SESSION_ACCOUNT_INDEX
          || !EXPECTED_OUTCOMES.equals(descriptor.outcomes())
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

  private static byte[] source(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        CanonicalGameplayAccountMemberRepairRedisOwner.class
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

  private static byte[] accountKey(UUID accountId) {
    return (ACCOUNT_KEY_PREFIX + accountId).getBytes(StandardCharsets.US_ASCII);
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

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
  }

  private static boolean canonicalDecimal(String value, boolean allowZero) {
    if (value == null || value.isEmpty() || value.length() > MAX_DECIMAL_BYTES) return false;
    if (allowZero && value.equals("0")) return true;
    if (value.charAt(0) < '1' || value.charAt(0) > '9') return false;
    for (int index = 1; index < value.length(); index++) {
      if (value.charAt(index) < '0' || value.charAt(index) > '9') return false;
    }
    return true;
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) throw unavailable();
  }

  private static void requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) throw unavailable();
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception failure) {
      throw unavailable(failure);
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Binding-local account member Redis projection is unavailable");
  }

  private static IllegalStateException unavailable(Throwable cause) {
    return new IllegalStateException(
        "Binding-local account member Redis projection is unavailable", cause);
  }

  /**
   * Capability created only after script execution and separate physical SET membership readback. A
   * caller cannot manufacture one from a PRESENT claim or a Redis script echo.
   */
  public static final class Readback {
    private final CanonicalGameplayAccountMemberRepairOperation operation;
    private final String member;

    private Readback(CanonicalGameplayAccountMemberRepairOperation operation, String member) {
      this.operation = Objects.requireNonNull(operation, "operation");
      this.member = Objects.requireNonNull(member, "member");
      if (!member.equals(operation.member())) throw unavailable();
    }

    public CanonicalGameplayAccountMemberRepairOperation operation() {
      return operation;
    }

    public String member() {
      return member;
    }
  }
}
