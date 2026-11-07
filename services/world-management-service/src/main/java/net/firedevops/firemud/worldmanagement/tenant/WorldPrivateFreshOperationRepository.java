package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldPrivateFreshOperation.Terminal;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual, unregistered owner. There is no production original-private-operation permission
 * producer. Source reads, source holds, workload identity and installation provenance cannot fill
 * that gap.
 */
public final class WorldPrivateFreshOperationRepository {
  private final DSLContext dsl;
  private final String schema;
  private final String namespace;
  private final PermissionSource permissions;
  private final SourceProtectionSource protections;
  private final TransactionTemplate write;
  private final TransactionTemplate read;

  /** Production/default construction refuses before any SQL or private runtime allocation. */
  public WorldPrivateFreshOperationRepository(
      DSLContext dsl, PlatformTransactionManager transactions, String schema, String namespace) {
    this(
        dsl,
        transactions,
        schema,
        namespace,
        input -> {
          throw new PermissionUnavailableException(
              "Original private-operation permission is unavailable");
        },
        input -> {
          throw new PermissionUnavailableException(
              "Continuing private source protection is unavailable");
        });
  }

  /**
   * Controlled qualification seam only. Do not register until genuine upstream authority exists.
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The transaction-bound DSLContext is an injected database dependency, not owned mutable value state")
  public WorldPrivateFreshOperationRepository(
      DSLContext dsl,
      PlatformTransactionManager transactions,
      String schema,
      String namespace,
      PermissionSource permissions,
      SourceProtectionSource protections) {
    this.dsl = Objects.requireNonNull(dsl);
    if (schema == null
        || !schema.matches("[a-z][a-z0-9_]{0,62}")
        || !GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Fixed owner schema and namespace required");
    this.schema = schema;
    this.namespace = namespace;
    this.permissions = Objects.requireNonNull(permissions);
    this.protections = Objects.requireNonNull(protections);
    write = new TransactionTemplate(Objects.requireNonNull(transactions));
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    read = new TransactionTemplate(transactions);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
  }

  public Terminal materialize(WorldPrivateFreshOperation input) {
    return execute(input, false);
  }

  /** Serializes against materialization and appends NOT_APPLIED without deleting retained input. */
  public Terminal abort(WorldPrivateFreshOperation input) {
    return execute(input, true);
  }

  private Terminal execute(WorldPrivateFreshOperation input, boolean abort) {
    independent();
    Objects.requireNonNull(input);
    if (!namespace.equals(input.namespace()))
      throw new IllegalArgumentException("Wrong private target namespace");
    try (HeldPermission held = Objects.requireNonNull(permissions.acquire(input));
        HeldSourceProtection source = Objects.requireNonNull(protections.acquire(input))) {
      ByteString original = Objects.requireNonNull(held.originalOperation());
      requireExact(input, held, source, original);
      // Persist the immutable original before a separately committed materialization/abort outcome.
      write.executeWithoutResult(
          status -> {
            requireExact(input, held, source, original);
            timeouts();
            dsl.fetchValue(
                "SELECT " + schema + ".world_private_fresh_intent(?,?,?)",
                input.inputJson(),
                input.inputDigest(),
                original.toByteArray());
            requireExact(input, held, source, original);
          });
      requireExact(input, held, source, original);
      Terminal committed =
          write.execute(
              status -> {
                requireExact(input, held, source, original);
                timeouts();
                dsl.fetchValue(
                    "SELECT " + schema + ".world_private_fresh_finish(?,?)",
                    input.operationId(),
                    abort);
                requireExact(input, held, source, original);
                return terminal(input.operationId()).orElseThrow();
              });
      requireExact(input, held, source, original);
      Terminal retained = read(input.operationId()).orElseThrow();
      if (!retained.equals(committed))
        throw new IllegalStateException("Private terminal changed before committed readback");
      return retained;
    }
  }

  /** Independent historical readback. It does not renew permission or an external source hold. */
  public Optional<Terminal> read(UUID operationId) {
    independent();
    Objects.requireNonNull(operationId);
    return read.execute(
        status -> {
          timeouts();
          return terminal(operationId);
        });
  }

  private void timeouts() {
    dsl.fetchValue("SELECT set_config('statement_timeout','5000ms',TRUE)");
    dsl.fetchValue("SELECT set_config('lock_timeout','5000ms',TRUE)");
  }

  private Optional<Terminal> terminal(UUID operationId) {
    Record row =
        dsl.fetchOne(
            "SELECT t.* FROM "
                + schema
                + ".world_private_fresh_terminal t JOIN "
                + schema
                + ".world_private_fresh_operation o USING(operation_id) WHERE t.operation_id=? AND o.target_namespace=?",
            operationId,
            namespace);
    if (row == null) return Optional.empty();
    return Optional.of(
        new Terminal(
            row.get("operation_id", UUID.class),
            row.get("canonical_game_instance_id", UUID.class),
            row.get("input_digest", String.class),
            row.get("outcome", String.class),
            row.get("world_instance_id", Long.class),
            row.get("private_game_instance_key", Long.class),
            ByteString.copyFrom(row.get("terminal_bytes", byte[].class))));
  }

  private static void requireExact(
      WorldPrivateFreshOperation input,
      HeldPermission held,
      HeldSourceProtection source,
      ByteString original) {
    source.requireHeld();
    if (!input.protection().equals(source.binding()))
      throw new PermissionUnavailableException(
          "Continuing source protection differs from exact pending source binding");
    held.requireHeld();
    if (!input.operationId().equals(held.operationId())
        || !input.inputDigest().equals(held.inputDigest())
        || !original.equals(held.originalOperation())
        || held.originalOperation().isEmpty()
        || held.originalOperation().size() > 262144)
      throw new PermissionUnavailableException(
          "Permission differs from the original private operation and exact inputs");
  }

  private static void independent() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Private World owner refuses ambient transactions");
  }

  /**
   * Source protection is separately held through both actual commits; a current-read DTO cannot
   * implement this.
   */
  public interface SourceProtectionSource {
    HeldSourceProtection acquire(WorldPrivateFreshOperation input);
  }

  /**
   * Must prevent source change and expiry through owner commit independently of actor permission.
   * There is currently no production implementation or terminal-settlement/cleanup transport.
   * Closing this qualification lease must not settle or release the retained Game Session hold.
   */
  public interface HeldSourceProtection extends AutoCloseable {
    WorldPrivateFreshOperation.SourceProtectionBinding binding();

    void requireHeld();

    @Override
    void close();
  }

  /** Must authenticate the original actor operation independently of all supplied source facts. */
  public interface PermissionSource {
    HeldPermission acquire(WorldPrivateFreshOperation input);
  }

  /**
   * Controlled owner-local qualification contract. The implementation must prevent permission loss
   * through actual SQL commit. This does not define credential lifetime, renewal or production
   * authorization semantics; those remain unresolved upstream. No ordinary evidence DTO implements
   * it.
   */
  public interface HeldPermission extends AutoCloseable {
    UUID operationId();

    String inputDigest();

    ByteString originalOperation();

    void requireHeld();

    @Override
    void close();
  }

  public static final class PermissionUnavailableException extends IllegalStateException {
    public PermissionUnavailableException(String message) {
      super(message);
    }
  }
}
