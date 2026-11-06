package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed World installation owner. Ordinary requests cannot supply inventory bytes. The
 * protected source is unavailable by default; no constructor registers a runtime component.
 */
public final class WorldRuntimeInstallationRegistry {
  private final DSLContext dsl;
  private final String schema;
  private final String namespace;
  private final ProtectedInstallationSource source;
  private final TransactionTemplate write;
  private final TransactionTemplate read;

  public WorldRuntimeInstallationRegistry(
      DSLContext dsl, PlatformTransactionManager transactions, String schema, String namespace) {
    this(
        dsl,
        transactions,
        schema,
        namespace,
        () -> {
          throw new RegistryUnavailableException(
              "Protected current-installation source is unavailable");
        });
  }

  public WorldRuntimeInstallationRegistry(
      DSLContext dsl,
      PlatformTransactionManager transactions,
      String schema,
      String namespace,
      ProtectedInstallationSource source) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (schema == null
        || !schema.matches("[a-z][a-z0-9_]{0,62}")
        || !GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed World schema and namespace required");
    }
    this.schema = schema;
    this.namespace = namespace;
    this.source = Objects.requireNonNull(source, "source");
    write = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    read = new TransactionTemplate(transactions);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
  }

  /** Installs the exact protected current artifact and independently reads its committed bytes. */
  public Registration register() {
    independent();
    try (HeldInstallation held =
        Objects.requireNonNull(source.acquireCurrent(), "held installation")) {
      held.requireCurrent();
      var artifact = artifact(held);
      Registration committed =
          write.execute(
              status -> {
                // One namespace serializes registration; source authority remains held through
                // commit.
                dsl.execute(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                    "world-runtime-registry/" + namespace);
                Record current =
                    dsl.fetchOne(
                        "SELECT * FROM "
                            + table("world_runtime_installation_current")
                            + " WHERE target_namespace=? FOR UPDATE",
                        namespace);
                Record old =
                    dsl.fetchOne(
                        "SELECT * FROM "
                            + table("world_runtime_installation_registration")
                            + " WHERE artifact_id=?",
                        artifact.artifactId());
                Registration registered;
                if (old != null) {
                  registered = registration(old);
                  exact(registered.artifact(), artifact);
                  if (current == null
                      || registered.revision() != current.get("revision", Long.class)) {
                    throw new RegistryUnavailableException(
                        "Historical installation cannot replace the current registration");
                  }
                } else {
                  Record inserted =
                      dsl.fetchSingle(
                          "INSERT INTO "
                              + table("world_runtime_installation_registration")
                              + " (artifact_id,installation_id,target_namespace,artifact_bytes,artifact_digest) VALUES (?,?,?,?,?) RETURNING *",
                          artifact.artifactId(),
                          artifact.installationId(),
                          namespace,
                          artifact.exactBytes(),
                          artifact.digest());
                  registered = registration(inserted);
                  dsl.execute(
                      "INSERT INTO "
                          + table("world_runtime_installation_current")
                          + " (target_namespace,revision,artifact_digest) VALUES (?,?,?) ON CONFLICT (target_namespace)"
                          + " DO UPDATE SET revision=EXCLUDED.revision,artifact_digest=EXCLUDED.artifact_digest",
                      namespace,
                      registered.revision(),
                      artifact.digest());
                }
                held.requireCurrent();
                exact(artifact(held), artifact);
                return registered;
              });
      held.requireCurrent();
      Registration result = current();
      if (committed.revision() != result.revision()) {
        throw new RegistryUnavailableException("Registration changed before independent readback");
      }
      exact(result.artifact(), artifact);
      return result;
    }
  }

  /** Independent durable readback is provenance, not evidence that the source is currently held. */
  public Registration current() {
    independent();
    return read.execute(status -> currentInTransaction(false));
  }

  /**
   * Read-only readiness check before acquiring durable Account protection. An absent frozen set is
   * allowed; an existing set must match exactly. This is not a race guarantee or launch authority:
   * the protected source is released on return and {@link #withLaunchOwners} must still guard the
   * actual synchronous launch commit.
   */
  public void preflightLaunchOwners(UUID gameInstanceId) {
    independent();
    if (gameInstanceId == null || gameInstanceId.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("Non-nil canonical game instance required");
    }
    try (HeldInstallation held =
        Objects.requireNonNull(source.acquireCurrent(), "held installation")) {
      held.requireCurrent();
      var artifact = artifact(held);
      artifact.requireResolved();
      read.executeWithoutResult(
          status -> {
            var registered = currentInTransaction(false);
            exact(registered.artifact(), artifact);
            if (dsl.fetchExists(
                dsl.selectOne()
                    .from(table("world_runtime_required_owner_snapshot"))
                    .where("canonical_game_instance_id=?", gameInstanceId))) {
              requireFrozen(gameInstanceId, registered);
            }
          });
      held.requireCurrent();
      exact(artifact(held), artifact);
    }
  }

  /**
   * Freezes the complete set before invoking an existing launch owner and keeps the protected
   * installation authority held through that owner's actual synchronous commit. This grants no
   * Account, publication, FRESH, lifecycle activation or admission authority.
   */
  public <T> T withLaunchOwners(UUID gameInstanceId, Supplier<T> launchOwner) {
    independent();
    if (gameInstanceId == null || gameInstanceId.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("Non-nil canonical game instance required");
    }
    Objects.requireNonNull(launchOwner, "launchOwner");
    try (HeldInstallation held =
        Objects.requireNonNull(source.acquireCurrent(), "held installation")) {
      held.requireCurrent();
      var artifact = artifact(held);
      artifact.requireResolved();
      write.executeWithoutResult(
          status -> {
            var registered = currentInTransaction(true);
            exact(registered.artifact(), artifact);
            dsl.execute(
                "INSERT INTO "
                    + table("world_runtime_required_owner_snapshot")
                    + " (canonical_game_instance_id,target_namespace,registry_revision,artifact_digest,required_owners)"
                    + " VALUES (?,?,?,?,"
                    + schema
                    + ".world_runtime_required_owners(?)) ON CONFLICT (canonical_game_instance_id) DO NOTHING",
                gameInstanceId,
                namespace,
                registered.revision(),
                artifact.digest(),
                artifact.exactBytes());
            requireFrozen(gameInstanceId, registered);
            held.requireCurrent();
            exact(artifact(held), artifact);
          });
      read.executeWithoutResult(
          status -> requireFrozen(gameInstanceId, currentInTransaction(false)));
      held.requireCurrent();
      exact(artifact(held), artifact);
      T result = launchOwner.get();
      held.requireCurrent();
      exact(artifact(held), artifact);
      return result;
    }
  }

  private void requireFrozen(UUID id, Registration registration) {
    Record frozen =
        dsl.fetchOne(
            "SELECT * FROM "
                + table("world_runtime_required_owner_snapshot")
                + " WHERE canonical_game_instance_id=? AND target_namespace=? AND registry_revision=?"
                + " AND artifact_digest=? AND required_owners="
                + schema
                + ".world_runtime_required_owners(?)",
            id,
            namespace,
            registration.revision(),
            registration.artifact().digest(),
            registration.artifact().exactBytes());
    if (frozen == null)
      throw new RegistryUnavailableException("Frozen required-owner set is absent or invalidated");
  }

  private Registration currentInTransaction(boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT r.* FROM "
                + table("world_runtime_installation_current")
                + " c JOIN "
                + table("world_runtime_installation_registration")
                + " r ON r.revision=c.revision"
                + " AND r.artifact_digest=c.artifact_digest AND r.target_namespace=c.target_namespace"
                + " WHERE c.target_namespace=?"
                + (lock ? " FOR SHARE OF c" : ""),
            namespace);
    if (row == null)
      throw new RegistryUnavailableException("Current installation registration is unavailable");
    return registration(row);
  }

  private Registration registration(Record row) {
    var parsed = WorldRuntimeInstallationArtifact.parse(row.get("artifact_bytes", byte[].class));
    if (!namespace.equals(parsed.namespace())
        || !parsed.artifactId().equals(row.get("artifact_id", UUID.class))
        || !parsed.installationId().equals(row.get("installation_id", UUID.class))
        || !parsed.digest().equals(row.get("artifact_digest", String.class))) {
      throw new RegistryUnavailableException("Retained installation identity or digest mismatch");
    }
    return new Registration(row.get("revision", Long.class), parsed);
  }

  private WorldRuntimeInstallationArtifact artifact(HeldInstallation held) {
    var artifact = WorldRuntimeInstallationArtifact.parse(held.exactArtifactBytes());
    if (!namespace.equals(artifact.namespace()))
      throw new RegistryUnavailableException("Wrong installation namespace");
    return artifact;
  }

  private static void exact(
      WorldRuntimeInstallationArtifact actual, WorldRuntimeInstallationArtifact expected) {
    if (!Arrays.equals(actual.exactBytes(), expected.exactBytes())) {
      throw new RegistryUnavailableException("Protected installation bytes changed");
    }
  }

  private String table(String name) {
    return schema + "." + name;
  }

  private static void independent() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Installation owner requires independent transactions");
    }
  }

  public record Registration(long revision, WorldRuntimeInstallationArtifact artifact) {
    public Registration {
      if (revision <= 0)
        throw new IllegalArgumentException("Positive registration revision required");
      Objects.requireNonNull(artifact, "artifact");
    }
  }

  /** Configured protected installation boundary, never an ordinary request deserializer. */
  public interface ProtectedInstallationSource {
    HeldInstallation acquireCurrent();
  }

  /**
   * The implementation must independently verify completeness, exact completed installation,
   * workload incarnations and all input bindings, and prevent their change until close(). Merely
   * re-reading a caller file or comparing its digest cannot implement this contract.
   */
  public interface HeldInstallation extends AutoCloseable {
    byte[] exactArtifactBytes();

    void requireCurrent();

    @Override
    void close();
  }

  public static final class RegistryUnavailableException extends IllegalStateException {
    public RegistryUnavailableException(String message) {
      super(message);
    }
  }
}
