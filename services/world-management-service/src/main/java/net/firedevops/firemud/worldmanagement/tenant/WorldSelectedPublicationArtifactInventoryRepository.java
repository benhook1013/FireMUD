package net.firedevops.firemud.worldmanagement.tenant;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-local immutable capture and historical readback for one selected-publication inventory. */
final class WorldSelectedPublicationArtifactInventoryRepository {
  private static final String SELECT_INVENTORY =
      "SELECT i.publication_fence, i.target_namespace, i.canonical_tenant_id, "
          + "i.canonical_version_id, i.local_tenant_key, i.local_version_key, "
          + "i.applied_commit_id, i.account_operation_id, i.account_fence_id, "
          + "i.inventory_schema_version, i.inventory_bytes, i.inventory_digest, "
          + "a.account_binding_bytes, a.account_binding_digest "
          + "FROM world_selected_publication_artifact_inventory i "
          + "JOIN world_design_publication_account_binding a USING (publication_fence) "
          + "WHERE i.publication_fence=?";

  private final DSLContext dsl;

  WorldSelectedPublicationArtifactInventoryRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Stores only a first-freeze source captured by the checkpoint callback, or reads the exact
   * immutable prior inventory when another claimant already froze the owner.
   */
  WorldSelectedPublicationArtifactInventory retainOrRequireExact(
      FrozenAttempt attempt,
      AccountPublicationAuthorizationBinding accountBinding,
      boolean attemptCreatedInThisTransaction,
      WorldSelectedDraftPublicationCheckpointRepository.CapturedCheckpoint captured) {
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(accountBinding, "accountBinding");
    requireWritableReadCommittedOwnerTransaction();
    if (attemptCreatedInThisTransaction) {
      if (captured == null) {
        throw conflict("New World freeze has no exact source for complete artifact inventory");
      }
      if (dsl.fetchOne(SELECT_INVENTORY, attempt.publicationFence()) != null) {
        throw conflict("New World freeze unexpectedly has a prior artifact inventory");
      }
      var inventory =
          WorldSelectedPublicationArtifactInventory.capture(attempt, accountBinding, captured);
      var owner = inventory.envelope().ownerScope();
      int inserted =
          dsl.execute(
              "INSERT INTO world_selected_publication_artifact_inventory "
                  + "(publication_fence, target_namespace, canonical_tenant_id, "
                  + "canonical_version_id, local_tenant_key, local_version_key, applied_commit_id, "
                  + "account_operation_id, account_fence_id, inventory_schema_version, "
                  + "inventory_bytes, inventory_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              attempt.publicationFence(),
              owner.targetNamespace(),
              owner.canonicalTenantId(),
              owner.canonicalVersionId(),
              Long.parseLong(owner.localTenantKey()),
              Long.parseLong(owner.localVersionKey()),
              attempt.checkpoint().appliedCommitId(),
              accountBinding.operationId(),
              accountBinding.fenceId(),
              inventory.envelope().schemaVersion(),
              inventory.canonicalBytes(),
              inventory.contentDigest());
      if (inserted != 1) {
        throw conflict("World complete artifact inventory was not inserted");
      }
      var readback = readExact(attempt, accountBinding);
      if (!Arrays.equals(inventory.canonicalBytes(), readback.canonicalBytes())
          || !inventory.contentDigest().equals(readback.contentDigest())) {
        throw conflict("World complete artifact inventory differs from first-freeze readback");
      }
      return readback;
    }
    if (captured != null) {
      throw conflict("Historical World freeze attempted to recapture artifact inventory source");
    }
    return readExact(attempt, accountBinding);
  }

  /** Exact post-commit read; absence or corrupt rows deny rather than imply an empty inventory. */
  WorldSelectedPublicationArtifactInventory readCommitted(
      FrozenAttempt attempt, AccountPublicationAuthorizationBinding accountBinding) {
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(accountBinding, "accountBinding");
    requireNoAmbientTransaction();
    return readExact(attempt, accountBinding);
  }

  private WorldSelectedPublicationArtifactInventory readExact(
      FrozenAttempt attempt, AccountPublicationAuthorizationBinding accountBinding) {
    Record row = dsl.fetchOne(SELECT_INVENTORY, attempt.publicationFence());
    if (row == null) {
      throw conflict("Exact frozen World publication has no complete artifact inventory");
    }
    UUID publicationFence = required(row, "publication_fence", UUID.class);
    String targetNamespace = required(row, "target_namespace", String.class);
    UUID tenantId = required(row, "canonical_tenant_id", UUID.class);
    UUID versionId = required(row, "canonical_version_id", UUID.class);
    long localTenantKey = required(row, "local_tenant_key", Long.class);
    long localVersionKey = required(row, "local_version_key", Long.class);
    String appliedCommitId = required(row, "applied_commit_id", String.class);
    UUID accountOperationId = required(row, "account_operation_id", UUID.class);
    UUID accountFenceId = required(row, "account_fence_id", UUID.class);
    short schemaVersion = required(row, "inventory_schema_version", Short.class);
    byte[] inventoryBytes = required(row, "inventory_bytes", byte[].class);
    String inventoryDigest = required(row, "inventory_digest", String.class);
    byte[] retainedAccountBytes = required(row, "account_binding_bytes", byte[].class);
    String retainedAccountDigest = required(row, "account_binding_digest", String.class);
    var request = attempt.request();
    if (!publicationFence.equals(attempt.publicationFence())
        || !targetNamespace.equals(request.targetNamespace())
        || !tenantId.equals(request.canonicalTenantId())
        || !versionId.equals(request.canonicalVersionId())
        || !appliedCommitId.equals(attempt.checkpoint().appliedCommitId())
        || !accountOperationId.equals(accountBinding.operationId())
        || !accountFenceId.equals(accountBinding.fenceId())
        || (schemaVersion != 1 && schemaVersion != 2)
        || !Arrays.equals(retainedAccountBytes, accountBinding.canonicalBytes())
        || !DraftAuthorizationFenceBinding.digest(retainedAccountBytes)
            .equals(retainedAccountDigest)) {
      throw conflict(
          "Stored World artifact inventory columns differ from exact freeze and Account order");
    }
    try {
      var retainedAccount = AccountPublicationAuthorizationBinding.fromStored(retainedAccountBytes);
      if (!Arrays.equals(retainedAccount.canonicalBytes(), accountBinding.canonicalBytes())) {
        throw conflict("Stored World artifact inventory Account order is not exact");
      }
    } catch (RuntimeException invalid) {
      throw conflict("Stored World artifact inventory Account order is corrupt");
    }
    var inventory =
        WorldSelectedPublicationArtifactInventory.fromStored(
            inventoryBytes,
            inventoryDigest,
            attempt,
            accountBinding,
            localTenantKey,
            localVersionKey);
    if (schemaVersion != inventory.envelope().schemaVersion()) {
      throw conflict(
          "Stored World artifact inventory schema column differs from exact retained bytes");
    }
    var scope = inventory.envelope().ownerScope();
    if (!scope.targetNamespace().equals(targetNamespace)
        || !scope.canonicalTenantId().equals(tenantId)
        || !scope.canonicalVersionId().equals(versionId)
        || !Long.toString(localTenantKey).equals(scope.localTenantKey())
        || !Long.toString(localVersionKey).equals(scope.localVersionKey())) {
      throw conflict("Stored World artifact inventory owner scope differs from its row");
    }
    return inventory;
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World artifact inventory capture requires a writable owner transaction");
    }
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException("World artifact inventory capture requires READ COMMITTED");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equalsIgnoreCase(settings.get("transaction_isolation", String.class))
        || !"off".equalsIgnoreCase(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "World artifact inventory capture requires writable READ COMMITTED storage");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World artifact inventory committed read requires no ambient transaction");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) {
      throw conflict("World artifact inventory is missing retained field " + field);
    }
    return value;
  }

  private static ConflictException conflict(String message) {
    return new ConflictException(message);
  }
}
