package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Immutable persistence for an Account-observed, non-authorizing validator inventory snapshot. The
 * table stores only the source's closed canonical public/runtime observation, never API response
 * objects or credentials.
 */
@Repository
public class AccountJwtValidatorInventoryRepository {
  public static final int MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024;
  private static final String TABLE = "account_jwt_validator_inventory_snapshots";
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor only validates its trusted injected DSLContext; it performs no I/O or resource acquisition and defines no finalizer.")
  public AccountJwtValidatorInventoryRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /** Inserts once or proves that a same-digest retry reads back the exact immutable bytes. */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredSnapshot persistOrReadback(
      InventorySnapshot snapshot, Binding binding, TrustFence trustFence) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(snapshot, "Owner-observed inventory snapshot is required");
    requireBinding(snapshot, binding, trustFence);
    byte[] canonicalBytes = snapshot.canonicalBytes();
    requireSnapshotBytes(canonicalBytes, snapshot.digest());
    dsl.execute(
        "INSERT INTO "
            + TABLE
            + " (snapshot_digest, environment_id, cluster_id, cluster_incarnation_uid, "
            + "kubernetes_namespace, namespace_uid, api_binding_revision, api_binding_digest, "
            + "inventory_binding_revision, inventory_binding_digest, observed_at, canonical_snapshot) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (snapshot_digest) DO NOTHING",
        snapshot.digest(),
        snapshot.environmentId(),
        snapshot.clusterId(),
        UUID.fromString(snapshot.clusterIncarnationUid()),
        snapshot.namespace(),
        UUID.fromString(snapshot.namespaceUid()),
        snapshot.apiBindingRevision(),
        snapshot.apiBindingDigest(),
        snapshot.inventoryBindingRevision(),
        snapshot.inventoryBindingDigest(),
        snapshot.observedAt().toString(),
        canonicalBytes);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + TABLE + " WHERE snapshot_digest = ? FOR UPDATE", snapshot.digest());
    StoredSnapshot stored = decode(row);
    requireSameSnapshot(snapshot, stored);
    requireBinding(stored, binding, trustFence);
    return stored;
  }

  /** Reads the exact previously persisted snapshot and rechecks its Account binding. */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredSnapshot readRequired(String digest, Binding binding, TrustFence trustFence) {
    requireAccountTransaction();
    if (digest == null || !DIGEST.matcher(digest).matches()) {
      throw new InventorySnapshotUnavailableException();
    }
    Record row =
        dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE snapshot_digest = ? FOR KEY SHARE", digest);
    StoredSnapshot stored = decode(row);
    requireBinding(stored, binding, trustFence);
    return stored;
  }

  private static StoredSnapshot decode(Record row) {
    if (row == null) {
      throw new InventorySnapshotUnavailableException();
    }
    try {
      String digest = row.get("snapshot_digest", String.class);
      byte[] bytes = row.get("canonical_snapshot", byte[].class);
      requireSnapshotBytes(bytes, digest);
      StoredSnapshot stored =
          new StoredSnapshot(
              digest,
              row.get("environment_id", String.class),
              row.get("cluster_id", String.class),
              row.get("cluster_incarnation_uid", UUID.class).toString(),
              row.get("kubernetes_namespace", String.class),
              row.get("namespace_uid", UUID.class).toString(),
              row.get("api_binding_revision", String.class),
              row.get("api_binding_digest", String.class),
              row.get("inventory_binding_revision", String.class),
              row.get("inventory_binding_digest", String.class),
              Instant.parse(row.get("observed_at", String.class)),
              bytes);
      return stored;
    } catch (InventorySnapshotUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new InventorySnapshotUnavailableException();
    }
  }

  private static void requireSnapshotBytes(byte[] bytes, String digest) {
    if (bytes == null
        || bytes.length < 2
        || bytes.length > MAX_SNAPSHOT_BYTES
        || digest == null
        || !DIGEST.matcher(digest).matches()
        || !digest(bytes).equals(digest)) {
      throw new InventorySnapshotUnavailableException();
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new InventorySnapshotUnavailableException();
    }
  }

  private static void requireSameSnapshot(InventorySnapshot expected, StoredSnapshot actual) {
    if (!expected.digest().equals(actual.digest())
        || !expected.environmentId().equals(actual.environmentId())
        || !expected.clusterId().equals(actual.clusterId())
        || !expected.clusterIncarnationUid().equals(actual.clusterIncarnationUid())
        || !expected.namespace().equals(actual.namespace())
        || !expected.namespaceUid().equals(actual.namespaceUid())
        || !expected.apiBindingRevision().equals(actual.apiBindingRevision())
        || !expected.apiBindingDigest().equals(actual.apiBindingDigest())
        || !expected.inventoryBindingRevision().equals(actual.inventoryBindingRevision())
        || !expected.inventoryBindingDigest().equals(actual.inventoryBindingDigest())
        || !expected.observedAt().equals(actual.observedAt())
        || !MessageDigest.isEqual(expected.canonicalBytes(), actual.canonicalBytes())) {
      throw new InventorySnapshotUnavailableException();
    }
  }

  private static void requireBinding(
      InventorySnapshot snapshot, Binding binding, TrustFence trustFence) {
    Objects.requireNonNull(binding, "Account generation binding is required");
    Objects.requireNonNull(trustFence, "Account trust fence is required");
    if (!binding.environmentId().equals(snapshot.environmentId())
        || !binding.clusterId().equals(snapshot.clusterId())
        || !binding.namespace().equals(snapshot.namespace())
        || !trustFence.expectedClusterIncarnationUid().equals(snapshot.clusterIncarnationUid())
        || !trustFence.expectedNamespaceUid().equals(snapshot.namespaceUid())) {
      throw new InventorySnapshotUnavailableException();
    }
  }

  private static void requireBinding(
      StoredSnapshot snapshot, Binding binding, TrustFence trustFence) {
    Objects.requireNonNull(binding, "Account generation binding is required");
    Objects.requireNonNull(trustFence, "Account trust fence is required");
    if (!binding.environmentId().equals(snapshot.environmentId())
        || !binding.clusterId().equals(snapshot.clusterId())
        || !binding.namespace().equals(snapshot.namespace())
        || !trustFence.expectedClusterIncarnationUid().equals(snapshot.clusterIncarnationUid())
        || !trustFence.expectedNamespaceUid().equals(snapshot.namespaceUid())) {
      throw new InventorySnapshotUnavailableException();
    }
  }

  private static void requireAccountTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("An owning Account transaction is required");
    }
  }

  private static void requireWritableAccountTransaction() {
    requireAccountTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("A writable Account transaction is required");
    }
  }

  public record StoredSnapshot(
      String digest,
      String environmentId,
      String clusterId,
      String clusterIncarnationUid,
      String namespace,
      String namespaceUid,
      String apiBindingRevision,
      String apiBindingDigest,
      String inventoryBindingRevision,
      String inventoryBindingDigest,
      Instant observedAt,
      byte[] canonicalBytes) {
    public StoredSnapshot {
      Objects.requireNonNull(digest);
      Objects.requireNonNull(environmentId);
      Objects.requireNonNull(clusterId);
      Objects.requireNonNull(clusterIncarnationUid);
      Objects.requireNonNull(namespace);
      Objects.requireNonNull(namespaceUid);
      Objects.requireNonNull(apiBindingRevision);
      Objects.requireNonNull(apiBindingDigest);
      Objects.requireNonNull(inventoryBindingRevision);
      Objects.requireNonNull(inventoryBindingDigest);
      Objects.requireNonNull(observedAt);
      canonicalBytes = canonicalBytes.clone();
      requireSnapshotBytes(canonicalBytes, digest);
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof StoredSnapshot that)) {
        return false;
      }
      return digest.equals(that.digest)
          && environmentId.equals(that.environmentId)
          && clusterId.equals(that.clusterId)
          && clusterIncarnationUid.equals(that.clusterIncarnationUid)
          && namespace.equals(that.namespace)
          && namespaceUid.equals(that.namespaceUid)
          && apiBindingRevision.equals(that.apiBindingRevision)
          && apiBindingDigest.equals(that.apiBindingDigest)
          && inventoryBindingRevision.equals(that.inventoryBindingRevision)
          && inventoryBindingDigest.equals(that.inventoryBindingDigest)
          && observedAt.equals(that.observedAt)
          && Arrays.equals(canonicalBytes, that.canonicalBytes);
    }

    @Override
    public int hashCode() {
      return 31
              * Objects.hash(
                  digest,
                  environmentId,
                  clusterId,
                  clusterIncarnationUid,
                  namespace,
                  namespaceUid,
                  apiBindingRevision,
                  apiBindingDigest,
                  inventoryBindingRevision,
                  inventoryBindingDigest,
                  observedAt)
          + Arrays.hashCode(canonicalBytes);
    }
  }

  public static final class InventorySnapshotUnavailableException extends RuntimeException {
    public InventorySnapshotUnavailableException() {
      super("Account validator inventory snapshot is unavailable or inconsistent");
    }
  }
}
