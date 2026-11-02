package net.firedevops.firemud.accountservice.service.session;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Account-only preliminary creation receipt owner. A qualified receipt is not SQL
 * admission: the callback must serialize final admission with SQL denial and commit/read back its
 * evidence before dispatch or acknowledgement. This class never authenticates a recovered JWT.
 */
public final class AccountTenantCreationAdmissionRedisClient {
  private final AccountCoordinationPinnedConnectionProvider connections;
  private final AcknowledgementRequirements acknowledgements;
  private final RedisScriptCatalog catalog;
  private final AccountTenantCreationAdmissionCoordinationQualification qualification;
  private final String protectedBindingId;
  private final Clock clock;

  public AccountTenantCreationAdmissionRedisClient(
      AccountCoordinationPinnedConnectionProvider connections,
      AcknowledgementRequirements acknowledgements,
      RedisScriptCatalog catalog,
      AccountTenantCreationAdmissionCoordinationQualification qualification,
      String protectedBindingId,
      Clock clock) {
    this.connections = Objects.requireNonNull(connections);
    this.acknowledgements = Objects.requireNonNull(acknowledgements);
    this.catalog = Objects.requireNonNull(catalog);
    this.qualification = Objects.requireNonNull(qualification);
    if (protectedBindingId == null
        || protectedBindingId.isBlank()
        || protectedBindingId.length() > 256
        || !protectedBindingId.equals(protectedBindingId.trim())) {
      throw new IllegalArgumentException("Exact protected Account Coordination binding required");
    }
    this.protectedBindingId = protectedBindingId;
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Atomically compare the original active record and install/reassert only the exact receipt. The
   * owner must durably retain this original intent before invoking this method.
   */
  public <T> T withInitialReceipt(
      AccountTenantCreationAdmissionBinding binding,
      byte[] exactActiveRegistry,
      Function<QualifiedReceipt, T> callback) {
    Objects.requireNonNull(binding);
    if (exactActiveRegistry == null
        || exactActiveRegistry.length == 0
        || exactActiveRegistry.length > AccountControlUiRegistryReader.MAX_RECORD_BYTES) {
      throw unavailable();
    }
    byte[] active = exactActiveRegistry.clone();
    var record = AccountControlUiRegistryReader.parseActiveRecord(active, binding.tokenHash());
    if (!digest("SHA-256", active).equals(binding.activeRegistryDigest())
        || !binding.actorAccountId().toString().equals(record.get("accountId"))
        || !binding.issuanceOperationId().toString().equals(record.get("operationId"))
        || !(record.get("exp") instanceof Number exp)
        || !Long.toString(binding.tokenExpiresAtEpochMillis() / 1000L).equals(exp.toString())
        || AccountControlUiRegistryReader.checkedCleanupDeadlineMillis(
                record.get("exp"), record.get("registryCleanupDeadlineEpochMillis"))
            != binding.tokenCleanupDeadlineEpochMillis()) throw unavailable();
    return withReceipt(binding, active, "INITIAL", callback);
  }

  /**
   * Recover only an existing exact-original receipt within its frozen logical deadline. No active
   * token or raw JWT is required, and absence never takes the creation arm. The caller must first
   * establish the original durable SQL admission; a receipt left by a failed admission attempt
   * cannot supply that prerequisite.
   */
  public <T> T withExistingReceipt(
      AccountTenantCreationAdmissionBinding binding, Function<QualifiedReceipt, T> callback) {
    return withReceipt(Objects.requireNonNull(binding), new byte[0], "EXISTING_ONLY", callback);
  }

  private <T> T withReceipt(
      AccountTenantCreationAdmissionBinding binding,
      byte[] active,
      String mode,
      Function<QualifiedReceipt, T> callback) {
    outsideSql();
    Objects.requireNonNull(callback);
    requireTime(binding);
    var descriptor = AccountTenantCreationAdmissionRedisContract.descriptor();
    if (!descriptor.equals(catalog.require(descriptor.scriptId()))) throw unavailable();
    byte[] source = source(descriptor.resourcePath(), descriptor.sha256());
    byte[] receipt = binding.canonicalReceipt();
    if (receipt.length == 0 || receipt.length > 8192) throw unavailable();
    var acquired =
        qualification.acquire(
            AccountTenantCreationAdmissionRedisKeys.QUALIFICATION_SCOPE, protectedBindingId);
    if (acquired == null || acquired.isEmpty()) throw unavailable();
    try (var held = new HeldQualification(acquired.orElseThrow(), binding)) {
      held.requireCurrent();
      Counts counts;
      byte[] tokenKey = bytes(AccountTenantCreationAdmissionRedisKeys.token(binding.tokenHash()));
      byte[] receiptKey =
          bytes(
              AccountTenantCreationAdmissionRedisKeys.receipt(
                  binding.tokenHash(), binding.creationOperationId()));
      try (var connection = connections.openPinnedConnection()) {
        var commands = requirePinned(connection);
        String sha = commands.scriptLoad(source);
        if (!digest("SHA-1", source).equals(sha)) throw unavailable();
        held.requireCurrent();
        Long outcome =
            commands.evalsha(
                sha,
                ScriptOutputType.INTEGER,
                new byte[][] {tokenKey, receiptKey},
                bytes(mode),
                active,
                receipt,
                bytes(Long.toString(clock.millis())));
        if (outcome == null
            || outcome != 0L && outcome != 1L
            || "EXISTING_ONLY".equals(mode) && outcome != 0L) throw unavailable();
        // Both successful script arms physically SET the exact receipt before this acknowledgement.
        counts = acknowledge(commands);
        held.requireCurrent();
      }
      // Close the mutation connection first. Readback is a fresh pinned physical invocation.
      try (var readback = connections.openPinnedConnection()) {
        var commands = requirePinned(readback);
        byte[] observed = commands.get(receiptKey);
        Long cleanup = commands.pexpiretime(receiptKey);
        if (!Arrays.equals(receipt, observed)
            || cleanup == null
            || cleanup != binding.receiptCleanupDeadlineEpochMillis()) throw unavailable();
        held.requireCurrent();
      }
      var qualified = new QualifiedReceipt(binding, receipt, counts, held);
      qualified.requireCurrent();
      T result = callback.apply(qualified);
      qualified.requireCurrent();
      return result;
    }
  }

  private Counts acknowledge(RedisCommands<byte[], byte[]> commands) {
    List<Object> observed =
        commands.dispatch(
            WaitAof.INSTANCE,
            new ArrayOutput<>(ByteArrayCodec.INSTANCE),
            new CommandArgs<>(ByteArrayCodec.INSTANCE)
                .add(acknowledgements.requiredLocalAofCount())
                .add(acknowledgements.requiredReplicaAofCount())
                .add(acknowledgements.timeoutMillis()));
    if (observed == null
        || observed.size() != 2
        || !(observed.get(0) instanceof Long local)
        || !(observed.get(1) instanceof Long replicas)
        || local < acknowledgements.requiredLocalAofCount()
        || replicas < acknowledgements.requiredReplicaAofCount()) throw unavailable();
    return new Counts(local, replicas);
  }

  private static RedisCommands<byte[], byte[]> requirePinned(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null
        || !connection.isOpen()
        || connection.getOptions() == null
        || connection.getOptions().isAutoReconnect()) throw unavailable();
    var commands = connection.sync();
    if (!"account_coord_app".equals(commands.aclWhoami())) throw unavailable();
    return commands;
  }

  private void requireTime(AccountTenantCreationAdmissionBinding binding) {
    long now = clock.millis();
    if (now < binding.preparedAtEpochMillis()
        || now >= binding.operationDeadlineEpochMillis()
        || now >= binding.tokenExpiresAtEpochMillis()) throw unavailable();
  }

  private static byte[] source(String path, String expectedDigest) {
    try (var input =
        AccountTenantCreationAdmissionRedisClient.class
            .getClassLoader()
            .getResourceAsStream(path)) {
      if (input == null) throw unavailable();
      byte[] source = input.readNBytes(32769);
      if (source.length == 0
          || source.length > 32768
          || !digest("SHA-256", source).equals(expectedDigest)) throw unavailable();
      return source;
    } catch (IOException failure) {
      throw unavailable();
    }
  }

  private static String digest(String algorithm, byte[] value) {
    return AccountControlUiCoordination.digest(algorithm, value);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Creation receipt Redis work cannot retain SQL locks");
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Qualified original Account creation receipt unavailable");
  }

  private record Counts(long local, long replicas) {}

  private final class HeldQualification implements AutoCloseable {
    private final AccountTenantCreationAdmissionCoordinationQualification.Lease lease;
    private final AccountTenantCreationAdmissionBinding binding;
    private final String bindingId;
    private long fence;
    private final AtomicBoolean closed = new AtomicBoolean();

    private HeldQualification(
        AccountTenantCreationAdmissionCoordinationQualification.Lease lease,
        AccountTenantCreationAdmissionBinding binding) {
      this.lease = Objects.requireNonNull(lease);
      this.binding = binding;
      this.bindingId = protectedBindingId;
    }

    private void requireCurrent() {
      long observedFence = lease.fence();
      if (fence == 0L && observedFence > 0L) fence = observedFence;
      if (closed.get()
          || fence <= 0L
          || fence != observedFence
          || !AccountTenantCreationAdmissionRedisKeys.QUALIFICATION_SCOPE.equals(lease.scope())
          || !bindingId.equals(lease.protectedBindingId())) throw unavailable();
      lease.requireCurrent();
      requireTime(binding);
    }

    @Override
    public void close() {
      closed.set(true);
      lease.close();
    }
  }

  /**
   * Private-construction physical evidence valid only under the held callback qualification. It
   * cannot substitute for durable SQL admission or current independent security/terms predicates.
   */
  public static final class QualifiedReceipt {
    private final AccountTenantCreationAdmissionBinding binding;
    private final byte[] canonicalReceipt;
    private final Counts counts;
    private final HeldQualification held;

    private QualifiedReceipt(
        AccountTenantCreationAdmissionBinding binding,
        byte[] canonicalReceipt,
        Counts counts,
        HeldQualification held) {
      this.binding = binding;
      this.canonicalReceipt = canonicalReceipt.clone();
      this.counts = counts;
      this.held = held;
    }

    public AccountTenantCreationAdmissionBinding binding() {
      return binding;
    }

    public byte[] canonicalReceipt() {
      return canonicalReceipt.clone();
    }

    public String recordDigest() {
      return digest("SHA-256", canonicalReceipt);
    }

    public long localAofCount() {
      return counts.local();
    }

    public long replicaAofCount() {
      return counts.replicas();
    }

    public long qualificationFence() {
      return held.fence;
    }

    public String protectedBindingId() {
      return held.bindingId;
    }

    /** Must be checked again at the caller's final SQL/dispatch boundaries. */
    public void requireCurrent() {
      held.requireCurrent();
    }
  }

  private enum WaitAof implements ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return bytes("WAITAOF");
    }
  }
}
