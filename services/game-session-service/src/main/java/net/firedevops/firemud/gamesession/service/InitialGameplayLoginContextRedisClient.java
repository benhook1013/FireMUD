package net.firedevops.firemud.gamesession.service;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Inactive owner-only compare mutation; no context producer, credential storage or admission. */
public final class InitialGameplayLoginContextRedisClient {
  private final InitialGameplayLoginRedisBinding binding;
  private final RedisScriptCatalog catalog;
  private final InitialGameplayLoginCoordinationQualification qualification;

  public InitialGameplayLoginContextRedisClient(
      InitialGameplayLoginRedisBinding binding,
      RedisScriptCatalog catalog,
      InitialGameplayLoginCoordinationQualification qualification) {
    this.binding = binding;
    this.catalog = catalog;
    this.qualification = qualification;
  }

  /**
   * Requires the verified private Account result; retained nonsecret evidence cannot install. An
   * exact installed retry reasserts only the same original verified bytes. This CAS evidence is
   * non-admitting: genuine callers still require original private custody and current
   * postconditions before wire success. It establishes neither current token usability nor
   * authority.
   */
  public Optional<InitialGameplayLoginContextState> install(
      InitialGameplayLoginAttemptCoordinates coordinates,
      CanonicalGameplayLoginResult verifiedResult) {
    Objects.requireNonNull(coordinates, "Original coordinates required");
    Objects.requireNonNull(verifiedResult, "Verified Account result required");
    var desired = InitialGameplayLoginContextState.installed(coordinates, verifiedResult);
    Optional<HeldQualification> acquired = acquireQualification();
    if (acquired.isEmpty()) return Optional.empty();
    try (HeldQualification held = acquired.orElseThrow()) {
      return mutate(coordinates, desired, "INSTALL", held);
    } catch (RuntimeException unavailable) {
      return Optional.empty();
    }
  }

  Optional<HeldQualification> acquireQualification() {
    if (binding == null
        || catalog == null
        || qualification == null
        || TransactionSynchronizationManager.isActualTransactionActive()) return Optional.empty();
    InitialGameplayLoginCoordinationQualification.Lease lease = null;
    try {
      Optional<InitialGameplayLoginCoordinationQualification.Lease> acquired =
          qualification.acquire(
              InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE, binding.protectedBindingId());
      if (acquired == null || acquired.isEmpty()) return Optional.empty();
      lease = acquired.orElseThrow();
      var held = new HeldQualification(lease, binding.protectedBindingId());
      held.requireCurrent();
      return Optional.of(held);
    } catch (RuntimeException unavailable) {
      if (lease != null) {
        try {
          lease.close();
        } catch (RuntimeException ignored) {
          /* Remains held. */
        }
      }
      return Optional.empty();
    }
  }

  Optional<InitialGameplayLoginContextState> closeOriginal(
      InitialGameplayLoginAttemptCoordinates coordinates, HeldQualification held) {
    return mutate(coordinates, InitialGameplayLoginContextState.closed(coordinates), "CLOSE", held);
  }

  private Optional<InitialGameplayLoginContextState> mutate(
      InitialGameplayLoginAttemptCoordinates coordinates,
      InitialGameplayLoginContextState desired,
      String operation,
      HeldQualification held) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) return Optional.empty();
    try {
      held.requireCurrent();
      var descriptor = requireDescriptor();
      byte[] source = source(descriptor);
      byte[] key =
          InitialGameplayLoginContextRedisKeys.contextKey(coordinates.callerContextId())
              .getBytes(StandardCharsets.US_ASCII);
      byte[] desiredBytes = desired.encode();
      try (StatefulRedisConnection<byte[], byte[]> connection = binding.openPinnedConnection()) {
        RedisCommands<byte[], byte[]> commands = requirePinned(connection);
        requirePrincipal(commands);
        byte[] priorBytes = commands.get(key);
        var prior = InitialGameplayLoginContextState.decode(priorBytes);
        if (!prior.coordinates().equals(coordinates)
            || "INSTALL".equals(operation)
                && prior.state() != InitialGameplayLoginContextState.State.PENDING
                && !(prior.state() == InitialGameplayLoginContextState.State.INSTALLED
                    && Arrays.equals(priorBytes, desiredBytes))) return Optional.empty();
        held.requireCurrent();
        String loaded = commands.scriptLoad(source);
        if (!digest("SHA-1", source).equals(loaded)) throw unavailable();
        Long result =
            commands.evalsha(
                loaded,
                ScriptOutputType.INTEGER,
                new byte[][] {key},
                operation.getBytes(StandardCharsets.US_ASCII),
                priorBytes,
                desiredBytes);
        if (!Long.valueOf(1).equals(result)) return Optional.empty();
        acknowledge(commands);
        held.requireCurrent();
        // A separately opened physical connection reads the actual postimage, not the Lua result.
        try (StatefulRedisConnection<byte[], byte[]> readback = binding.openPinnedConnection()) {
          if (readback == connection) throw unavailable();
          var reads = requirePinned(readback);
          requirePrincipal(reads);
          byte[] actual = reads.get(key);
          if (!Arrays.equals(desiredBytes, actual)
              || !Long.valueOf(-1L).equals(reads.pttl(key))
              || !InitialGameplayLoginContextState.decode(actual).coordinates().equals(coordinates))
            throw unavailable();
          held.requireCurrent();
        }
      }
      held.requireCurrent();
      return Optional.of(desired);
    } catch (RuntimeException failure) {
      return Optional.empty();
    }
  }

  private RedisScriptDescriptor requireDescriptor() {
    var descriptor = catalog.require(InitialGameplayLoginRedisScriptContribution.SCRIPT_ID);
    if (!InitialGameplayLoginRedisScriptContribution.SCRIPT_ID.equals(descriptor.scriptId())
        || !InitialGameplayLoginRedisScriptContribution.RESOURCE_PATH.equals(
            descriptor.resourcePath())
        || !"game-session-service".equals(descriptor.owner())
        || !InitialGameplayLoginRedisBinding.PRINCIPAL.equals(descriptor.principal())
        || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
        || descriptor.category() != RedisScriptDescriptor.ScriptCategory.SESSION_CAS
        || !descriptor
            .keys()
            .equals(
                List.of(
                    new RedisScriptDescriptor.KeySpec(
                        "originalContext",
                        InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE,
                        RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)))
        || !descriptor
            .arguments()
            .equals(
                List.of(
                    new RedisScriptDescriptor.ArgumentSpec("operation"),
                    new RedisScriptDescriptor.ArgumentSpec("exactPriorContextUtf8"),
                    new RedisScriptDescriptor.ArgumentSpec("exactDesiredContextUtf8")))
        || descriptor.resetSensitivity() != RedisScriptDescriptor.ResetSensitivity.CLUSTER
        || descriptor.lossClass() != RedisScriptDescriptor.LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP
        || descriptor.compatibilityLevel()
            != RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET
        || !descriptor
            .supportedCoexistence()
            .equals(List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "v1")))
        || descriptor.legacyPayloadShape() != null
        || !descriptor
            .outcomes()
            .equals(
                List.of(
                    new RedisScriptDescriptor.OutcomeSpec(
                        "EXACT_CONTEXT_WRITTEN",
                        RedisScriptDescriptor.OutcomeCategory.SUCCESS,
                        RedisScriptDescriptor.MutationEffect.MUTATING),
                    new RedisScriptDescriptor.OutcomeSpec(
                        "MISSING_OR_CHANGED_CONTEXT",
                        RedisScriptDescriptor.OutcomeCategory.STALE,
                        RedisScriptDescriptor.MutationEffect.NON_MUTATING),
                    new RedisScriptDescriptor.OutcomeSpec(
                        "INVALID_ARGUMENTS",
                        RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
                        RedisScriptDescriptor.MutationEffect.NON_MUTATING)))) throw unavailable();
    return descriptor;
  }

  private static byte[] source(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        InitialGameplayLoginContextRedisClient.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(32 * 1024 + 1);
      if (bytes.length == 0
          || bytes.length > 32 * 1024
          || !descriptor.sha256().equals(digest("SHA-256", bytes))) throw unavailable();
      return bytes;
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static RedisCommands<byte[], byte[]> requirePinned(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null
        || !connection.isOpen()
        || connection.getOptions() == null
        || connection.getOptions().isAutoReconnect()) throw unavailable();
    return connection.sync();
  }

  private static void requirePrincipal(RedisCommands<byte[], byte[]> commands) {
    if (!InitialGameplayLoginRedisBinding.PRINCIPAL.equals(commands.aclWhoami()))
      throw unavailable();
  }

  private void acknowledge(RedisCommands<byte[], byte[]> commands) {
    var policy = binding.acknowledgementPolicy();
    List<Object> counts =
        commands.dispatch(
            WaitAof.INSTANCE,
            new ArrayOutput<>(ByteArrayCodec.INSTANCE),
            new CommandArgs<>(ByteArrayCodec.INSTANCE)
                .add(policy.requiredLocalAofCount())
                .add(policy.requiredReplicaAofCount())
                .add(policy.timeoutMillis()));
    if (counts == null
        || counts.size() != 2
        || !(counts.get(0) instanceof Long local)
        || !(counts.get(1) instanceof Long replicas)
        || local < policy.requiredLocalAofCount()
        || replicas < policy.requiredReplicaAofCount()) throw unavailable();
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Initial LOGIN Coordination state is unavailable");
  }

  private enum WaitAof implements ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "WAITAOF".getBytes(StandardCharsets.US_ASCII);
    }
  }

  /** Holds the external exclusion; checking an unheld process-local fence is insufficient. */
  static final class HeldQualification implements AutoCloseable {
    private final InitialGameplayLoginCoordinationQualification.Lease lease;
    private final String bindingId;
    private final long fence;
    private boolean closed;

    private HeldQualification(
        InitialGameplayLoginCoordinationQualification.Lease lease, String bindingId) {
      this.lease = lease;
      this.bindingId = bindingId;
      this.fence = lease.fence();
    }

    void requireCurrent() {
      if (closed
          || fence <= 0
          || fence != lease.fence()
          || !InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE.equals(lease.scope())
          || !bindingId.equals(lease.protectedBindingId())) throw unavailable();
      lease.requireCurrent();
    }

    @Override
    public void close() {
      closed = true;
      lease.close();
    }
  }
}
