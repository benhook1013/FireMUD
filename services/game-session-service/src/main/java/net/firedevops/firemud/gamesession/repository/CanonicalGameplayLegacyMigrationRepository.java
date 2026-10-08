package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Game Session-owned durable phases for legacy controller-index migration. Package-private and
 * deliberately not a Spring bean or a public receipt writer.
 */
final class CanonicalGameplayLegacyMigrationRepository {
  private static final String OPERATION = "game_session_canonical_legacy_migration_operation";
  private static final String SOURCE_ENTRY =
      "game_session_canonical_legacy_migration_source_snapshot_entry";
  private static final String LEGACY_DISPOSITION =
      "game_session_canonical_binding_legacy_disposition";
  private static final String INVENTORY_CLOCK = "game_session_canonical_binding_inventory_clock";

  private static final String CANONICAL_SNAPSHOT_SQL =
      "SELECT c.inventory_revision, jsonb_build_object("
          + "'bindingGenerations', COALESCE((SELECT jsonb_agg(to_jsonb(g)"
          + " ORDER BY g.tenant_id, g.playable_state_namespace_id, g.character_id)"
          + " FROM game_session_canonical_binding_generation g), '[]'::jsonb),"
          + "'bindings', COALESCE((SELECT jsonb_agg(to_jsonb(i) ORDER BY encode(i.binding_ref, 'hex'))"
          + " FROM game_session_canonical_gameplay_binding_inventory i), '[]'::jsonb),"
          + "'transitions', COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.transition_id)"
          + " FROM game_session_canonical_binding_transition t), '[]'::jsonb),"
          + "'reservations', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.reservation_id)"
          + " FROM game_session_canonical_issuer_partition_reservation r), '[]'::jsonb),"
          + "'accountObligations', COALESCE((SELECT jsonb_agg(to_jsonb(a)"
          + " ORDER BY a.transition_id, a.obligation_ordinal)"
          + " FROM game_session_canonical_binding_account_index_obligation a), '[]'::jsonb),"
          + "'issuerObligations', COALESCE((SELECT jsonb_agg(to_jsonb(i)"
          + " ORDER BY i.transition_id, i.binding_ref)"
          + " FROM game_session_canonical_binding_issuer_index_obligation i), '[]'::jsonb),"
          + "'regionObligations', COALESCE((SELECT jsonb_agg(to_jsonb(g) ORDER BY g.transition_id)"
          + " FROM game_session_canonical_binding_region_bridge_obligation g), '[]'::jsonb))::text AS snapshot_json"
          + " FROM "
          + INVENTORY_CLOCK
          + " c WHERE c.singleton_id = 1";
  private static final String HAS_FUTURE_REVISION_SQL =
      "SELECT EXISTS (SELECT 1 FROM game_session_canonical_gameplay_binding_inventory i"
          + " WHERE i.inventory_revision > c.inventory_revision)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_transition t"
          + " WHERE t.inventory_revision > c.inventory_revision)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_partition_reservation r"
          + " WHERE r.inventory_revision > c.inventory_revision)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_account_index_obligation a"
          + " WHERE a.inventory_revision > c.inventory_revision)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_issuer_index_obligation i"
          + " WHERE i.inventory_revision > c.inventory_revision)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_region_bridge_obligation g"
          + " WHERE g.inventory_revision > c.inventory_revision) AS has_future_revision"
          + " FROM "
          + INVENTORY_CLOCK
          + " c WHERE c.singleton_id = 1";

  private final DSLContext dsl;
  private final CanonicalGameplayBindingInventoryRepository inventory;

  CanonicalGameplayLegacyMigrationRepository(
      DSLContext dsl, CanonicalGameplayBindingInventoryRepository inventory) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.inventory = Objects.requireNonNull(inventory, "inventory");
  }

  /** Creates or resumes the one durable owner operation bound to this exact physical cohort. */
  CanonicalGameplayLegacyMigrationOperation begin(
      UUID cohortId,
      UUID legacyWriterFence,
      CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity) {
    requireNoAmbientTransaction();
    requireNonNil(cohortId, "cohortId");
    requireNonNil(legacyWriterFence, "legacyWriterFence");
    Objects.requireNonNull(storageIdentity, "storageIdentity");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          Record existing =
              transaction.fetchOne(
                  "SELECT * FROM " + OPERATION + " WHERE cohort_id = ? FOR UPDATE", cohortId);
          if (existing != null) {
            CanonicalGameplayLegacyMigrationOperation operation = toOperation(existing);
            if (!operation.legacyWriterFence().equals(legacyWriterFence)
                || !operation.storageIdentity().equals(storageIdentity)) {
              throw conflict(
                  "The cohort is already bound to a different retained writer/storage fence");
            }
            if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
              requirePublishedDispositionMatches(transaction, operation);
            }
            return operation;
          }

          Record disposition =
              transaction.fetchOne(
                  "SELECT disposition_state FROM "
                      + LEGACY_DISPOSITION
                      + " WHERE singleton_id = 1 FOR UPDATE");
          if (disposition == null
              || !"REQUIRED".equals(disposition.get("disposition_state", String.class))) {
            throw conflict(
                "Legacy migration can start only while the V31 disposition remains REQUIRED");
          }

          UUID operationId = UUID.randomUUID();
          if (operationId.equals(new UUID(0L, 0L))) {
            throw new IllegalStateException("UUID generator returned nil migration operation id");
          }
          insertOperation(transaction, operationId, cohortId, legacyWriterFence, storageIdentity);
          return readOperation(transaction, operationId, false);
        });
  }

  CanonicalGameplayLegacyMigrationOperation read(UUID operationId) {
    requireNoAmbientTransaction();
    requireNonNil(operationId, "operationId");
    return dsl.transactionResult(
        configuration -> readOperation(DSL.using(configuration), operationId, false));
  }

  /** Rehydrates only the persisted sanitized source snapshot for deterministic restart replay. */
  CanonicalGameplayLegacyMigrationSourceSnapshot readSourceSnapshot(UUID operationId) {
    requireNoAmbientTransaction();
    requireNonNil(operationId, "operationId");
    return dsl.transactionResult(
        configuration -> loadSourceSnapshot(DSL.using(configuration), operationId));
  }

  /**
   * Persists the complete typed repository snapshot and only credential-free legacy structure. Only
   * a completely enumerated empty legacy source is eligible to proceed. Any retained row, unknown
   * family, credential, or incomplete scan is durably BLOCKED rather than treated as empty.
   */
  CanonicalGameplayLegacyMigrationOperation captureSnapshots(
      UUID operationId,
      CanonicalGameplayLegacyMigrationSourceSnapshot source,
      CanonicalGameplayBindingInventorySnapshot canonical) {
    requireNoAmbientTransaction();
    requireNonNil(operationId, "operationId");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(canonical, "canonical");
    CanonicalGameplayBindingInventorySnapshot actualCanonical = inventory.readSnapshot();
    if (!actualCanonical.equals(canonical)) {
      return block(operationId, "CANONICAL_SNAPSHOT_NOT_FROM_OWNER_REPOSITORY");
    }
    CanonicalGameplayLegacyMigrationOperation.State blockState = null;
    String blockReason = null;
    if (!source.enumeratedEveryKnownFamily()) {
      blockState = CanonicalGameplayLegacyMigrationOperation.State.BLOCKED;
      blockReason = "SOURCE_INVENTORY_INCOMPLETE";
    } else if (source.entries().stream()
        .anyMatch(
            entry ->
                entry.disposition()
                    == CanonicalGameplayLegacyMigrationSourceSnapshot.Disposition
                        .CREDENTIAL_BEARING)) {
      blockState = CanonicalGameplayLegacyMigrationOperation.State.BLOCKED;
      blockReason = "CREDENTIAL_BEARING_SOURCE";
    } else if (source.entries().stream()
        .anyMatch(
            entry ->
                entry.disposition()
                    == CanonicalGameplayLegacyMigrationSourceSnapshot.Disposition.UNKNOWN)) {
      blockState = CanonicalGameplayLegacyMigrationOperation.State.BLOCKED;
      blockReason = "UNKNOWN_SOURCE_RECORD";
    } else if (!source.everyEntryIsReconciled()) {
      blockState = CanonicalGameplayLegacyMigrationOperation.State.BLOCKED;
      blockReason = "UNMAPPABLE_SOURCE_RECORD";
    }
    final CanonicalGameplayLegacyMigrationOperation.State finalBlockState = blockState;
    final String finalBlockReason = blockReason;
    String sourceDigest = digestSource(source);
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
          Record operationRow = lockOperation(transaction, operationId);
          CanonicalGameplayLegacyMigrationOperation current = toOperation(operationRow);
          if (current.state() == CanonicalGameplayLegacyMigrationOperation.State.VERIFIED
              || current.state() == CanonicalGameplayLegacyMigrationOperation.State.BLOCKED) {
            return current;
          }
          SnapshotJson snapshotJson = readCanonicalSnapshot(transaction);
          if (!snapshotJson.revision().equals(canonical.inventoryRevision())) {
            return block(transaction, operationId, "CANONICAL_SNAPSHOT_CHANGED");
          }
          String canonicalDigest = sha256(snapshotJson.json());
          if (current.state() != CanonicalGameplayLegacyMigrationOperation.State.FENCED) {
            if (!Objects.equals(current.sourceSnapshotDigest(), sourceDigest)
                || !Objects.equals(
                    current.canonicalSnapshotRevision(), canonical.inventoryRevision())
                || !Objects.equals(current.canonicalSnapshotDigest(), canonicalDigest)) {
              return block(transaction, operationId, "SNAPSHOT_CHANGED_ON_REPLAY");
            }
            return current;
          }

          BigInteger sourceRevision =
              readBigInteger(
                  Objects.requireNonNull(
                      transaction.fetchOne(
                          "SELECT nextval('game_session_canonical_legacy_migration_source_revision_seq') AS revision"),
                      "legacy migration source revision sequence must exist"),
                  "revision");
          long ordinal = 0L;
          List<CanonicalGameplayLegacyMigrationSourceSnapshot.Entry> entries =
              source.entries().stream()
                  .sorted(
                      Comparator.comparing(
                          CanonicalGameplayLegacyMigrationSourceSnapshot.Entry::sourceKeyDigest))
                  .toList();
          for (CanonicalGameplayLegacyMigrationSourceSnapshot.Entry entry : entries) {
            transaction.execute(
                "INSERT INTO "
                    + SOURCE_ENTRY
                    + " (operation_id, row_ordinal, source_family, source_key_digest, disposition)"
                    + " VALUES (?, ?, ?, ?, ?)",
                operationId,
                ordinal++,
                entry.family().name(),
                entry.sourceKeyDigest(),
                entry.disposition().name());
          }

          String nextState = finalBlockState == null ? "SNAPSHOTTED" : "BLOCKED";
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + OPERATION
                      + " SET state = ?, source_snapshot_revision = ?, source_snapshot_digest = ?,"
                      + " source_snapshot_entry_count = ?, canonical_snapshot_revision = ?,"
                      + " source_snapshot_families = ?::jsonb, canonical_snapshot_digest = ?, canonical_snapshot = ?::jsonb,"
                      + " blocked_reason = ?, updated_at = CURRENT_TIMESTAMP"
                      + " WHERE operation_id = ? AND state = 'FENCED'",
                  nextState,
                  decimal(sourceRevision),
                  sourceDigest,
                  entries.size(),
                  decimal(canonical.inventoryRevision()),
                  familiesJson(source.scannedFamilies()),
                  canonicalDigest,
                  snapshotJson.json(),
                  finalBlockReason,
                  operationId),
              "Migration operation changed while its snapshots were being staged");
          return readOperation(transaction, operationId, false);
        });
  }

  /**
   * Records completion of the idempotent exact-replacement request; readback is still mandatory.
   */
  CanonicalGameplayLegacyMigrationOperation markRebuilt(UUID operationId) {
    requireNoAmbientTransaction();
    requireNonNil(operationId, "operationId");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
          Record row = lockOperation(transaction, operationId);
          CanonicalGameplayLegacyMigrationOperation operation = toOperation(row);
          if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.BLOCKED
              || operation.state() == CanonicalGameplayLegacyMigrationOperation.State.VERIFIED
              || operation.state()
                  == CanonicalGameplayLegacyMigrationOperation.State.READBACK_VERIFIED) {
            return operation;
          }
          if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.REBUILT) {
            return operation;
          }
          if (operation.state() != CanonicalGameplayLegacyMigrationOperation.State.SNAPSHOTTED) {
            throw conflict("Only a complete staged source and canonical snapshot may be rebuilt");
          }
          requireStoredSnapshotsConsistent(transaction, operation);
          String expectedProjectionDigest =
              expectedActiveProjectionDigest(transaction, operationId);
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + OPERATION
                      + " SET state = 'REBUILT', rebuild_digest = ?, updated_at = CURRENT_TIMESTAMP"
                      + " WHERE operation_id = ? AND state = 'SNAPSHOTTED'",
                  expectedProjectionDigest,
                  operationId),
              "Migration operation changed before exact index rebuild was retained");
          return readOperation(transaction, operationId, false);
        });
  }

  /**
   * Persists only an exact full readback. A mismatch is durable BLOCKED evidence; no caller digest
   * or count is accepted, and canonical rows are re-read through the typed inventory owner.
   */
  CanonicalGameplayLegacyMigrationOperation recordReadback(
      UUID operationId,
      CanonicalGameplayBindingInventorySnapshot canonical,
      CanonicalGameplayLegacyMigrationReadback readback) {
    requireNoAmbientTransaction();
    requireNonNil(operationId, "operationId");
    Objects.requireNonNull(canonical, "canonical");
    Objects.requireNonNull(readback, "readback");
    CanonicalGameplayBindingInventorySnapshot actualCanonical = inventory.readSnapshot();
    if (!actualCanonical.equals(canonical)) {
      return block(operationId, "CANONICAL_READBACK_NOT_FROM_OWNER_REPOSITORY");
    }
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
          Record row = lockOperation(transaction, operationId);
          CanonicalGameplayLegacyMigrationOperation operation = toOperation(row);
          if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.BLOCKED
              || operation.state() == CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
            return operation;
          }
          if (operation.state() != CanonicalGameplayLegacyMigrationOperation.State.REBUILT
              && operation.state()
                  != CanonicalGameplayLegacyMigrationOperation.State.READBACK_VERIFIED) {
            throw conflict("Exact namespace readback is not available before rebuild");
          }
          requireStoredSnapshotsConsistent(transaction, operation);
          SnapshotJson snapshotJson = readCanonicalSnapshot(transaction);
          if (!snapshotJson.revision().equals(canonical.inventoryRevision())
              || !snapshotJson.revision().equals(operation.canonicalSnapshotRevision())
              || !sha256(snapshotJson.json()).equals(operation.canonicalSnapshotDigest())) {
            return block(transaction, operationId, "CANONICAL_SNAPSHOT_CHANGED_BEFORE_READBACK");
          }

          List<CanonicalGameplayBindingInventoryEntry> expected = activeBindings(actualCanonical);
          if (!sameRows(expected, readback.sessionRecords())
              || !sameRows(expected, readback.characterIndexRecords())
              || !readback.remainingLegacySourceKeyDigests().isEmpty()
              || !readback.unexpectedNamespaceKeyDigests().isEmpty()) {
            return block(transaction, operationId, "INDEX_READBACK_MISMATCH");
          }

          String readbackDigest = digestReadback(readback);
          if (operation.state()
              == CanonicalGameplayLegacyMigrationOperation.State.READBACK_VERIFIED) {
            if (!operation.namespaceIndexReadbackDigest().equals(readbackDigest)) {
              return block(transaction, operationId, "READBACK_CHANGED_ON_REPLAY");
            }
            return operation;
          }
          BigInteger readbackRevision =
              readBigInteger(
                  Objects.requireNonNull(
                      transaction.fetchOne(
                          "SELECT nextval('game_session_canonical_legacy_migration_readback_revision_seq') AS revision"),
                      "legacy migration readback revision sequence must exist"),
                  "revision");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + OPERATION
                      + " SET state = 'READBACK_VERIFIED', namespace_index_readback_revision = ?,"
                      + " namespace_index_readback_digest = ?, updated_at = CURRENT_TIMESTAMP"
                      + " WHERE operation_id = ? AND state = 'REBUILT'",
                  decimal(readbackRevision),
                  readbackDigest,
                  operationId),
              "Migration operation changed before exact index readback was retained");
          return readOperation(transaction, operationId, false);
        });
  }

  /**
   * The sole V31 publisher. The cohort fence is revalidated inside the same DB transaction and V31
   * plus the owner operation commit atomically. A zero snapshot clock is advanced only here, after
   * complete source/readback proof; the original zero snapshot revision remains recorded.
   */
  CanonicalGameplayLegacyMigrationOperation publishVerified(
      UUID operationId, CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort) {
    requireNoAmbientTransaction();
    requireNonNil(operationId, "operationId");
    Objects.requireNonNull(cohort, "cohort");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
          Record row = lockOperation(transaction, operationId);
          CanonicalGameplayLegacyMigrationOperation operation = toOperation(row);
          requireSameCohort(operation, cohort);
          cohort.requireStillFenced(operation.storageIdentity());
          if (operation.state() == CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
            requirePublishedDispositionMatches(transaction, operation);
            return operation;
          }
          if (operation.state()
              != CanonicalGameplayLegacyMigrationOperation.State.READBACK_VERIFIED) {
            throw conflict("Only exact complete readback evidence may publish the V31 disposition");
          }

          Record clock =
              Objects.requireNonNull(
                  transaction.fetchOne(
                      "SELECT inventory_revision FROM "
                          + INVENTORY_CLOCK
                          + " WHERE singleton_id = 1 FOR UPDATE"),
                  "canonical inventory clock row must exist");
          BigInteger currentRevision = readBigInteger(clock, "inventory_revision");
          requireStoredSourceSnapshotConsistent(transaction, operation);
          requireStoredCanonicalSnapshotConsistent(transaction, operation);
          if (!currentRevision.equals(operation.canonicalSnapshotRevision())) {
            throw conflict("Canonical inventory revision changed while migration fences were held");
          }
          SnapshotJson currentSnapshot = readCanonicalSnapshot(transaction);
          if (!currentSnapshot.revision().equals(currentRevision)
              || !sha256(currentSnapshot.json()).equals(operation.canonicalSnapshotDigest())) {
            throw conflict("Canonical durable inventory changed after staged readback");
          }

          BigInteger publicationRevision = currentRevision;
          if (publicationRevision.signum() == 0) {
            publicationRevision = BigInteger.ONE;
            requireOne(
                transaction.execute(
                    "UPDATE "
                        + INVENTORY_CLOCK
                        + " SET inventory_revision = ? WHERE singleton_id = 1 AND inventory_revision = 0",
                    decimal(publicationRevision)),
                "Empty canonical snapshot could not acquire its owner publication revision");
          }
          String evidenceDigest = digestEvidence(operation, publicationRevision);
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + LEGACY_DISPOSITION
                      + " SET disposition_state = 'VERIFIED', cohort_id = ?, owner_operation_id = ?,"
                      + " legacy_writer_fence = ?, source_snapshot_revision = ?,"
                      + " canonical_inventory_revision = ?, namespace_index_readback_revision = ?,"
                      + " evidence_digest = ? WHERE singleton_id = 1 AND disposition_state = 'REQUIRED'",
                  operation.cohortId(),
                  operation.operationId(),
                  operation.legacyWriterFence(),
                  decimal(operation.sourceSnapshotRevision()),
                  decimal(publicationRevision),
                  decimal(operation.namespaceIndexReadbackRevision()),
                  evidenceDigest),
              "V31 disposition was already changed by a different migration owner");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + OPERATION
                      + " SET state = 'VERIFIED', publication_inventory_revision = ?,"
                      + " evidence_digest = ?, updated_at = CURRENT_TIMESTAMP"
                      + " WHERE operation_id = ? AND state = 'READBACK_VERIFIED'",
                  decimal(publicationRevision),
                  evidenceDigest,
                  operationId),
              "Migration operation changed before atomic V31 publication");
          cohort.requireStillFenced(operation.storageIdentity());
          CanonicalGameplayLegacyMigrationOperation published =
              readOperation(transaction, operationId, false);
          requirePublishedDispositionMatches(transaction, published);
          return published;
        });
  }

  CanonicalGameplayLegacyMigrationOperation block(UUID operationId, String reason) {
    requireNoAmbientTransaction();
    requireNonNil(operationId, "operationId");
    if (reason == null || !reason.matches("[A-Z0-9_]{1,48}")) {
      throw new IllegalArgumentException("blocked migration reason must be a bounded code");
    }
    return dsl.transactionResult(
        configuration -> block(DSL.using(configuration), operationId, reason));
  }

  private CanonicalGameplayLegacyMigrationOperation block(
      DSLContext transaction, UUID operationId, String reason) {
    Record currentRow = lockOperation(transaction, operationId);
    CanonicalGameplayLegacyMigrationOperation current = toOperation(currentRow);
    if (current.state() == CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
      throw conflict("A VERIFIED migration operation cannot be rewritten as blocked");
    }
    requireOne(
        transaction.execute(
            "UPDATE "
                + OPERATION
                + " SET state = 'BLOCKED', blocked_reason = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE operation_id = ? AND state <> 'VERIFIED'",
            reason,
            operationId),
        "Migration operation changed before its blocking evidence was retained");
    return readOperation(transaction, operationId, false);
  }

  private static void insertOperation(
      DSLContext transaction,
      UUID operationId,
      UUID cohortId,
      UUID legacyWriterFence,
      CanonicalGameplayLegacyMigrationStorageIdentity identity) {
    requireOne(
        transaction.execute(
            "INSERT INTO "
                + OPERATION
                + " (operation_id, cohort_id, legacy_writer_fence, state,"
                + " kubernetes_cluster_uid, kubernetes_namespace_uid, producer_pod_uid,"
                + " producer_container_id, producer_node_uid, postgres_system_identifier,"
                + " postgres_database_oid, postgres_storage_uid, redis_run_id, redis_storage_uid,"
                + " storage_identity_digest)"
                + " VALUES (?, ?, ?, 'FENCED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            operationId,
            cohortId,
            legacyWriterFence,
            identity.kubernetesClusterUid(),
            identity.kubernetesNamespaceUid(),
            identity.producerPodUid(),
            identity.producerContainerId(),
            identity.producerNodeUid(),
            new BigDecimal(identity.postgresSystemIdentifier()),
            identity.postgresDatabaseOid(),
            identity.postgresStorageUid(),
            identity.redisRunId(),
            identity.redisStorageUid(),
            identity.digest()),
        "Migration operation could not bind its exact cohort, writer fence, and storage identity");
  }

  private static Record lockOperation(DSLContext transaction, UUID operationId) {
    return Objects.requireNonNull(
        transaction.fetchOne(
            "SELECT * FROM " + OPERATION + " WHERE operation_id = ? FOR UPDATE", operationId),
        "legacy migration operation does not exist");
  }

  private static CanonicalGameplayLegacyMigrationOperation readOperation(
      DSLContext transaction, UUID operationId, boolean forUpdate) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM "
                + OPERATION
                + " WHERE operation_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            operationId);
    if (row == null) {
      throw conflict("Legacy migration operation does not exist");
    }
    return toOperation(row);
  }

  private static CanonicalGameplayLegacyMigrationSourceSnapshot loadSourceSnapshot(
      DSLContext transaction, UUID operationId) {
    Record operation =
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT source_snapshot_revision, source_snapshot_digest,"
                    + " source_snapshot_entry_count, source_snapshot_families::text AS source_families"
                    + " FROM "
                    + OPERATION
                    + " WHERE operation_id = ?",
                operationId),
            "legacy migration operation does not exist");
    if (operation.get("source_snapshot_revision", BigDecimal.class) == null) {
      throw conflict("Legacy source snapshot has not been staged");
    }
    var families = parseFamilies(operation.get("source_families", String.class));
    List<CanonicalGameplayLegacyMigrationSourceSnapshot.Entry> entries =
        transaction
            .fetch(
                "SELECT source_family, source_key_digest, disposition"
                    + " FROM "
                    + SOURCE_ENTRY
                    + " WHERE operation_id = ? ORDER BY row_ordinal",
                operationId)
            .map(
                row -> {
                  return new CanonicalGameplayLegacyMigrationSourceSnapshot.Entry(
                      CanonicalGameplayLegacyMigrationSourceSnapshot.Family.valueOf(
                          row.get("source_family", String.class)),
                      row.get("source_key_digest", String.class),
                      CanonicalGameplayLegacyMigrationSourceSnapshot.Disposition.valueOf(
                          row.get("disposition", String.class)));
                });
    Long storedEntryCount = operation.get("source_snapshot_entry_count", Long.class);
    if (!Objects.equals(storedEntryCount, (long) entries.size())) {
      throw conflict("Persisted legacy source inventory entry count is incomplete");
    }
    CanonicalGameplayLegacyMigrationSourceSnapshot snapshot =
        new CanonicalGameplayLegacyMigrationSourceSnapshot(families, entries);
    if (!digestSource(snapshot).equals(operation.get("source_snapshot_digest", String.class))) {
      throw conflict("Persisted sanitized legacy source snapshot digest is malformed");
    }
    return snapshot;
  }

  private static void requireStoredSnapshotsConsistent(
      DSLContext transaction, CanonicalGameplayLegacyMigrationOperation operation) {
    requireStoredSourceSnapshotConsistent(transaction, operation);
    requireStoredCanonicalSnapshotConsistent(transaction, operation);
    SnapshotJson currentSnapshot = readCanonicalSnapshot(transaction);
    if (!currentSnapshot.revision().equals(operation.canonicalSnapshotRevision())
        || !sha256(currentSnapshot.json()).equals(operation.canonicalSnapshotDigest())) {
      throw conflict("Canonical durable inventory changed after its complete snapshot was staged");
    }
  }

  private static void requireStoredSourceSnapshotConsistent(
      DSLContext transaction, CanonicalGameplayLegacyMigrationOperation operation) {
    CanonicalGameplayLegacyMigrationSourceSnapshot source =
        loadSourceSnapshot(transaction, operation.operationId());
    if (!digestSource(source).equals(operation.sourceSnapshotDigest())) {
      throw conflict("Persisted sanitized legacy source inventory differs from its staged digest");
    }
  }

  private static void requireStoredCanonicalSnapshotConsistent(
      DSLContext transaction, CanonicalGameplayLegacyMigrationOperation operation) {
    Record snapshotRow =
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT canonical_snapshot::text AS canonical_snapshot_text FROM "
                    + OPERATION
                    + " WHERE operation_id = ?",
                operation.operationId()),
            "migration operation must retain its canonical snapshot");
    String snapshotText = snapshotRow.get("canonical_snapshot_text", String.class);
    if (snapshotText == null || !sha256(snapshotText).equals(operation.canonicalSnapshotDigest())) {
      throw conflict("Persisted canonical migration snapshot differs from its staged digest");
    }
  }

  private static CanonicalGameplayLegacyMigrationOperation toOperation(Record row) {
    BigDecimal systemId = row.get("postgres_system_identifier", BigDecimal.class);
    CanonicalGameplayLegacyMigrationStorageIdentity identity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            row.get("kubernetes_cluster_uid", String.class),
            row.get("kubernetes_namespace_uid", String.class),
            row.get("producer_pod_uid", String.class),
            row.get("producer_container_id", String.class),
            row.get("producer_node_uid", String.class),
            Objects.requireNonNull(systemId, "PostgreSQL system identity must be present")
                .toBigIntegerExact()
                .toString(),
            row.get("postgres_database_oid", Long.class),
            row.get("postgres_storage_uid", String.class),
            row.get("redis_run_id", String.class),
            row.get("redis_storage_uid", String.class));
    if (!identity.digest().equals(row.get("storage_identity_digest", String.class))) {
      throw conflict("Stored immutable storage identity digest is malformed");
    }
    return new CanonicalGameplayLegacyMigrationOperation(
        row.get("operation_id", UUID.class),
        row.get("cohort_id", UUID.class),
        row.get("legacy_writer_fence", UUID.class),
        identity,
        CanonicalGameplayLegacyMigrationOperation.State.valueOf(row.get("state", String.class)),
        readNullableBigInteger(row, "source_snapshot_revision"),
        readNullableBigInteger(row, "canonical_snapshot_revision"),
        readNullableBigInteger(row, "publication_inventory_revision"),
        row.get("source_snapshot_digest", String.class),
        row.get("canonical_snapshot_digest", String.class),
        row.get("rebuild_digest", String.class),
        readNullableBigInteger(row, "namespace_index_readback_revision"),
        row.get("namespace_index_readback_digest", String.class),
        row.get("evidence_digest", String.class),
        row.get("blocked_reason", String.class));
  }

  private static SnapshotJson readCanonicalSnapshot(DSLContext transaction) {
    Record revisionCheck =
        Objects.requireNonNull(
            transaction.fetchOne(HAS_FUTURE_REVISION_SQL),
            "canonical inventory clock and revision families must exist");
    if (!Boolean.FALSE.equals(revisionCheck.get("has_future_revision", Boolean.class))) {
      throw conflict("Canonical durable family contains rows beyond the inventory clock");
    }
    Record row =
        Objects.requireNonNull(
            transaction.fetchOne(CANONICAL_SNAPSHOT_SQL),
            "canonical inventory clock and snapshot tables must exist");
    return new SnapshotJson(
        readBigInteger(row, "inventory_revision"), row.get("snapshot_json", String.class));
  }

  private static String expectedActiveProjectionDigest(DSLContext transaction, UUID operationId) {
    Record row =
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT COALESCE(jsonb_agg(binding ORDER BY binding->>'binding_ref'), '[]'::jsonb)::text"
                    + " AS active_projection FROM "
                    + OPERATION
                    + " o CROSS JOIN LATERAL jsonb_array_elements(o.canonical_snapshot->'bindings') AS items(binding)"
                    + " WHERE o.operation_id = ? AND binding->>'lifecycle' = 'ACTIVE'",
                operationId),
            "migration canonical snapshot must exist");
    return sha256(row.get("active_projection", String.class));
  }

  private static List<CanonicalGameplayBindingInventoryEntry> activeBindings(
      CanonicalGameplayBindingInventorySnapshot snapshot) {
    return snapshot.bindings().stream()
        .filter(
            binding ->
                binding.lifecycle() == CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE)
        .sorted(Comparator.comparing(binding -> hex(binding.bindingRef().bytes())))
        .toList();
  }

  private static boolean sameRows(
      List<CanonicalGameplayBindingInventoryEntry> expected,
      List<CanonicalGameplayBindingInventoryEntry> actual) {
    return activeSorted(expected).equals(activeSorted(actual));
  }

  private static List<CanonicalGameplayBindingInventoryEntry> activeSorted(
      List<CanonicalGameplayBindingInventoryEntry> rows) {
    return rows.stream()
        .sorted(Comparator.comparing(binding -> hex(binding.bindingRef().bytes())))
        .toList();
  }

  private static String digestSource(CanonicalGameplayLegacyMigrationSourceSnapshot source) {
    StringBuilder framed = new StringBuilder("canonical-gameplay-legacy-source/v1|");
    source.scannedFamilies().stream()
        .map(Enum::name)
        .sorted()
        .forEach(family -> append(framed, family));
    source.entries().stream()
        .sorted(
            Comparator.comparing(
                CanonicalGameplayLegacyMigrationSourceSnapshot.Entry::sourceKeyDigest))
        .forEach(
            entry -> {
              append(framed, entry.family().name());
              append(framed, entry.sourceKeyDigest());
              append(framed, entry.disposition().name());
            });
    return sha256(framed.toString());
  }

  private static String familiesJson(
      java.util.Set<CanonicalGameplayLegacyMigrationSourceSnapshot.Family> families) {
    return families.stream()
        .map(Enum::name)
        .sorted()
        .map(value -> "\"" + value + "\"")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static java.util.Set<CanonicalGameplayLegacyMigrationSourceSnapshot.Family> parseFamilies(
      String json) {
    String compact = json == null ? null : json.replaceAll("\\s+", "");
    if (compact == null || !compact.matches("\\[(\\\"[A-Z_]+\\\"(,\\\"[A-Z_]+\\\")*)?\\]")) {
      throw conflict("Persisted legacy source-family inventory is malformed");
    }
    String body = compact.substring(1, compact.length() - 1);
    if (body.isEmpty()) {
      return java.util.Set.of();
    }
    java.util.Set<CanonicalGameplayLegacyMigrationSourceSnapshot.Family> families =
        new java.util.HashSet<>();
    for (String token : body.split(",")) {
      String name = token.substring(1, token.length() - 1);
      if (!families.add(CanonicalGameplayLegacyMigrationSourceSnapshot.Family.valueOf(name))) {
        throw conflict("Persisted legacy source-family inventory contains duplicates");
      }
    }
    return java.util.Set.copyOf(families);
  }

  private static String digestReadback(CanonicalGameplayLegacyMigrationReadback readback) {
    StringBuilder framed = new StringBuilder("canonical-gameplay-legacy-readback/v1|");
    framed.append("session|");
    readback.sessionRecords().stream()
        .sorted(Comparator.comparing(binding -> hex(binding.bindingRef().bytes())))
        .forEach(binding -> append(framed, bindingFingerprint(binding)));
    framed.append("index|");
    readback.characterIndexRecords().stream()
        .sorted(Comparator.comparing(binding -> hex(binding.bindingRef().bytes())))
        .forEach(binding -> append(framed, bindingFingerprint(binding)));
    readback.remainingLegacySourceKeyDigests().stream()
        .sorted()
        .forEach(value -> append(framed, value));
    readback.unexpectedNamespaceKeyDigests().stream()
        .sorted()
        .forEach(value -> append(framed, value));
    return sha256(framed.toString());
  }

  private static String bindingFingerprint(CanonicalGameplayBindingInventoryEntry binding) {
    return binding.identity()
        + "|"
        + hex(binding.bindingRef().bytes())
        + "|"
        + binding.bindingGeneration()
        + "|"
        + binding.accountIndexFence()
        + "|"
        + binding.issuerReservationId()
        + "|"
        + binding.transitionId()
        + "|"
        + binding.lifecycle()
        + "|"
        + binding.accountIndexState()
        + "|"
        + binding.issuerIndexState()
        + "|"
        + binding.inventoryRevision();
  }

  private static String digestEvidence(
      CanonicalGameplayLegacyMigrationOperation operation, BigInteger publicationRevision) {
    StringBuilder framed = new StringBuilder("canonical-gameplay-legacy-migration-evidence/v1|");
    for (String value :
        List.of(
            operation.operationId().toString(),
            operation.cohortId().toString(),
            operation.legacyWriterFence().toString(),
            operation.storageIdentity().digest(),
            operation.sourceSnapshotRevision().toString(),
            operation.canonicalSnapshotRevision().toString(),
            publicationRevision.toString(),
            operation.namespaceIndexReadbackRevision().toString(),
            operation.sourceSnapshotDigest(),
            operation.canonicalSnapshotDigest(),
            operation.rebuildDigest(),
            operation.namespaceIndexReadbackDigest())) {
      append(framed, value);
    }
    return sha256(framed.toString());
  }

  private static void requireSameCohort(
      CanonicalGameplayLegacyMigrationOperation operation,
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort) {
    if (!operation.cohortId().equals(cohort.cohortId())
        || !operation.legacyWriterFence().equals(cohort.legacyWriterFence())
        || !operation.storageIdentity().equals(cohort.storageIdentity())) {
      throw conflict(
          "Current all-writer fence or physical storage identity differs from the staged cohort");
    }
  }

  private static void requirePublishedDispositionMatches(
      DSLContext transaction, CanonicalGameplayLegacyMigrationOperation operation) {
    Record disposition =
        Objects.requireNonNull(
            transaction.fetchOne("SELECT * FROM " + LEGACY_DISPOSITION + " WHERE singleton_id = 1"),
            "V31 disposition row must exist");
    if (!"VERIFIED".equals(disposition.get("disposition_state", String.class))
        || !operation.cohortId().equals(disposition.get("cohort_id", UUID.class))
        || !operation.operationId().equals(disposition.get("owner_operation_id", UUID.class))
        || !operation.legacyWriterFence().equals(disposition.get("legacy_writer_fence", UUID.class))
        || !operation
            .sourceSnapshotRevision()
            .equals(readBigInteger(disposition, "source_snapshot_revision"))
        || !operation
            .publicationInventoryRevision()
            .equals(readBigInteger(disposition, "canonical_inventory_revision"))
        || !operation
            .namespaceIndexReadbackRevision()
            .equals(readBigInteger(disposition, "namespace_index_readback_revision"))
        || !operation.evidenceDigest().equals(disposition.get("evidence_digest", String.class))) {
      throw conflict("V31 VERIFIED disposition differs from this exact durable owner operation");
    }
  }

  private static BigInteger readBigInteger(Record row, String field) {
    BigDecimal value = row.get(field, BigDecimal.class);
    if (value == null) {
      throw conflict("Durable migration field " + field + " is missing");
    }
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Durable migration field " + field + " is not an integer");
    }
  }

  private static BigInteger readNullableBigInteger(Record row, String field) {
    BigDecimal value = row.get(field, BigDecimal.class);
    return value == null ? null : value.toBigIntegerExact();
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw conflict("Legacy migration owner refuses to join an ambient Spring transaction");
    }
  }

  private static void requireOne(int rows, String message) {
    if (rows != 1) {
      throw conflict(message);
    }
  }

  private static String sha256(String value) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable for migration evidence", impossible);
    }
  }

  private static String hex(byte[] value) {
    return HexFormat.of().formatHex(value);
  }

  private static void append(StringBuilder output, String value) {
    int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
    output.append(byteLength).append(':').append(value);
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict(String message) {
    return new CanonicalGameplayBindingInventoryConflictException(message);
  }

  private record SnapshotJson(BigInteger revision, String json) {
    private SnapshotJson {
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(json, "json");
    }
  }
}
