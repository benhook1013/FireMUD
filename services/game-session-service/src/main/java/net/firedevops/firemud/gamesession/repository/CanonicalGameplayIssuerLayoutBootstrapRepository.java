package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationDisposition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Package-private LOCAL SQL owner for a pending initial issuer-layout bootstrap intent.
 *
 * <p>The descriptor and Account-source inputs are trusted injected LOCAL collaborators. This owner
 * freezes their bytes but does not authenticate or produce either source. It never installs an
 * active pointer, issues a coverage acknowledgement, or authorizes ordinary admission.
 */
final class CanonicalGameplayIssuerLayoutBootstrapRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String INVENTORY_CLOCK = "game_session_canonical_binding_inventory_clock";
  private static final String LEGACY_DISPOSITION =
      "game_session_canonical_binding_legacy_disposition";
  private static final String CONTROL = "game_session_canonical_issuer_layout_bootstrap_control";
  private static final String INTENT = "game_session_canonical_issuer_layout_bootstrap_intent";
  private static final String COHORT = "game_session_canonical_issuer_layout_bootstrap_cohort_row";
  private static final String MAPPING_IDENTITY =
      CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY;

  private final DSLContext dsl;

  CanonicalGameplayIssuerLayoutBootstrapRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Captures one pending initial-bootstrap intent. Input objects are trusted local collaborators,
   * not caller-supplied production authority.
   */
  CanonicalGameplayIssuerLayoutBootstrapIntent beginInitialBootstrapIntent(
      UUID requestId,
      TrustedLayoutDescriptor descriptor,
      TrustedAccountIssuerCheckpoint sourceCheckpoint) {
    requireNonNil(requestId, "requestId");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(sourceCheckpoint, "sourceCheckpoint");
    requireTrustedInputScopes(descriptor, sourceCheckpoint);
    requireValidSourceEvent(descriptor.issuerId(), sourceCheckpoint);
    requireNoAmbientTransaction();

    String descriptorDigest = sha256(descriptor.exactBytes());
    String checkpointDigest = sha256(sourceCheckpoint.checkpointBytes());
    String sourceEventDigest =
        sourceCheckpoint.latestEventBytes() == null
            ? null
            : sha256(sourceCheckpoint.latestEventBytes());

    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);

          // This is the same first lock as binding preparation. It serializes the immutable
          // inventory cut with every canonical source row that follows this clock.
          BigInteger snapshotRevision = lockInventoryRevision(transaction);
          Record existingControl = lockControl(transaction, descriptor.issuerId());
          if (existingControl != null) {
            UUID currentOperationId =
                Objects.requireNonNull(
                    existingControl.get("current_operation_id", UUID.class),
                    "issuer bootstrap control operation id");
            Record existingIntent = lockIntent(transaction, currentOperationId);
            if (existingIntent == null) {
              throw conflict("Issuer bootstrap control lost its original intent row");
            }
            CanonicalGameplayIssuerLayoutBootstrapIntent prior =
                loadAndRequireCurrent(transaction, existingControl, existingIntent);
            if (!prior.requestId().equals(requestId)) {
              throw conflict(
                  "A different initial issuer-layout bootstrap already owns this issuer");
            }
            requireSameTrustedInputs(
                prior,
                descriptor,
                sourceCheckpoint,
                descriptorDigest,
                checkpointDigest,
                sourceEventDigest);
            return prior;
          }

          Record priorIntent =
              transaction.fetchOne(
                  "SELECT operation_id FROM " + INTENT + " WHERE issuer_id = ?",
                  descriptor.issuerId());
          if (priorIntent != null) {
            throw conflict("Issuer bootstrap intent exists without its positive control row");
          }

          CanonicalGameplayLegacyMigrationDisposition legacy =
              readVerifiedLegacyDisposition(transaction, snapshotRevision, true);
          requireNoFutureInventoryRows(transaction, snapshotRevision);
          List<CohortSourceRow> sourceRows = captureCohort(transaction, snapshotRevision);
          List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohortRows =
              freezeCohort(sourceRows);
          String cohortDigest = digestCohort(cohortRows);

          UUID operationId = randomNonNilUuid();
          while (operationId.equals(requestId)) operationId = randomNonNilUuid();
          // The only operation in this migration is the initial intent. The first durable epoch
          // is positive; operationFence and coverageFence consume distinct ordered values.
          BigInteger operationFence = BigInteger.ONE;
          BigInteger coverageFence = BigInteger.TWO;
          BigInteger controlEpoch = operationFence;

          insertIntent(
              transaction,
              operationId,
              requestId,
              descriptor,
              descriptorDigest,
              sourceCheckpoint,
              checkpointDigest,
              sourceEventDigest,
              snapshotRevision,
              controlEpoch,
              operationFence,
              coverageFence,
              legacy,
              cohortRows,
              cohortDigest);
          insertCohort(transaction, operationId, descriptor.issuerId(), cohortRows);
          insertControl(
              transaction,
              descriptor.issuerId(),
              operationId,
              controlEpoch,
              operationFence,
              coverageFence);

          Record savedControl = lockControl(transaction, descriptor.issuerId());
          Record savedIntent = lockIntent(transaction, operationId);
          if (savedControl == null || savedIntent == null) {
            throw conflict("Persisted initial issuer-bootstrap intent is incomplete");
          }
          return loadAndRequireCurrent(transaction, savedControl, savedIntent);
        });
  }

  /**
   * Replaces only an uninstalled initial candidate whose immutable inventory cut is stale. The old
   * intent and every artifact it produced remain retained; a newer layout isolates in-flight Redis
   * work rather than granting deletion or active-layout replacement authority.
   */
  CanonicalGameplayIssuerLayoutBootstrapIntent supersedeStaleInitialBootstrapIntent(
      UUID expectedOldOperationId,
      UUID newRequestId,
      TrustedLayoutDescriptor descriptor,
      TrustedAccountIssuerCheckpoint sourceCheckpoint) {
    requireNonNil(expectedOldOperationId, "expectedOldOperationId");
    requireNonNil(newRequestId, "newRequestId");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(sourceCheckpoint, "sourceCheckpoint");
    requireTrustedInputScopes(descriptor, sourceCheckpoint);
    requireValidSourceEvent(descriptor.issuerId(), sourceCheckpoint);
    requireNoAmbientTransaction();
    String descriptorDigest = sha256(descriptor.exactBytes());
    String checkpointDigest = sha256(sourceCheckpoint.checkpointBytes());
    String sourceEventDigest =
        sourceCheckpoint.latestEventBytes() == null
            ? null
            : sha256(sourceCheckpoint.latestEventBytes());
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger snapshotRevision = lockInventoryRevision(transaction);
          Record control = lockControl(transaction, descriptor.issuerId());
          if (control == null)
            throw conflict("Initial bootstrap supersession requires current control");
          UUID currentId = control.get("current_operation_id", UUID.class);
          Record currentRow = lockIntent(transaction, currentId);
          if (currentRow == null) throw conflict("Current initial bootstrap intent is missing");
          var current = loadAndRequireCurrent(transaction, control, currentRow);
          if (transaction.fetchExists(
              DSL.selectOne()
                  .from("game_session_canonical_issuer_installed_anchor")
                  .where(DSL.field("issuer_id").eq(descriptor.issuerId())))) {
            throw conflict("An installed issuer anchor forbids initial bootstrap supersession");
          }
          if (!expectedOldOperationId.equals(currentId)) {
            Record replay =
                transaction.fetchOne(
                    "SELECT * FROM game_session_canonical_issuer_bootstrap_supersession"
                        + " WHERE old_operation_id = ? AND new_operation_id = ?",
                    expectedOldOperationId,
                    currentId);
            if (replay == null
                || !current.requestId().equals(newRequestId)
                || !current.inventorySnapshotRevision().equals(snapshotRevision)) {
              throw conflict("Expected original initial bootstrap is no longer current");
            }
            requireSameTrustedInputs(
                current,
                descriptor,
                sourceCheckpoint,
                descriptorDigest,
                checkpointDigest,
                sourceEventDigest);
            return current;
          }
          if (newRequestId.equals(expectedOldOperationId)
              || newRequestId.equals(current.requestId())
              || transaction.fetchExists(
                  DSL.selectOne()
                      .from(INTENT)
                      .where(
                          DSL.field("request_id")
                              .eq(newRequestId)
                              .or(DSL.field("operation_id").eq(newRequestId))))) {
            throw conflict("Initial bootstrap supersession requires a fresh distinct request");
          }
          if (snapshotRevision.compareTo(current.inventorySnapshotRevision()) <= 0) {
            throw conflict("Initial bootstrap supersession requires strictly newer inventory");
          }
          if (descriptor.layoutVersion().compareTo(current.layoutDescriptor().layoutVersion())
              <= 0) {
            throw conflict(
                "Initial bootstrap supersession requires a strictly newer layout version");
          }
          var legacy = readVerifiedLegacyDisposition(transaction, snapshotRevision, true);
          requireNoFutureInventoryRows(transaction, snapshotRevision);
          var rows = freezeCohort(captureCohort(transaction, snapshotRevision));
          BigInteger previousLastFence = readBigInteger(control, "last_issued_fence");
          BigInteger operationFence = previousLastFence.add(BigInteger.ONE);
          BigInteger coverageFence = operationFence.add(BigInteger.ONE);
          UUID operationId = randomNonNilUuid();
          while (operationId.equals(newRequestId) || operationId.equals(expectedOldOperationId)) {
            operationId = randomNonNilUuid();
          }
          insertIntent(
              transaction,
              operationId,
              newRequestId,
              descriptor,
              descriptorDigest,
              sourceCheckpoint,
              checkpointDigest,
              sourceEventDigest,
              snapshotRevision,
              operationFence,
              operationFence,
              coverageFence,
              legacy,
              rows,
              digestCohort(rows));
          insertCohort(transaction, operationId, descriptor.issuerId(), rows);
          transaction.execute(
              "INSERT INTO game_session_canonical_issuer_bootstrap_supersession"
                  + " (issuer_id, old_operation_id, new_operation_id, old_inventory_revision,"
                  + " new_inventory_revision, old_operation_fence, old_coverage_fence,"
                  + " previous_last_issued_fence, new_operation_fence, new_coverage_fence)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              descriptor.issuerId(),
              expectedOldOperationId,
              operationId,
              decimal(current.inventorySnapshotRevision()),
              decimal(snapshotRevision),
              decimal(current.operationFence()),
              decimal(current.coverageFence()),
              decimal(previousLastFence),
              decimal(operationFence),
              decimal(coverageFence));
          int changed =
              transaction.execute(
                  "UPDATE "
                      + CONTROL
                      + " SET control_epoch = ?, last_issued_fence = ?, current_operation_id = ?,"
                      + " current_operation_fence = ?, current_coverage_fence = ?,"
                      + " updated_at = CURRENT_TIMESTAMP WHERE issuer_id = ?"
                      + " AND current_operation_id = ? AND control_epoch = ?"
                      + " AND current_operation_fence = ? AND current_coverage_fence = ?"
                      + " AND last_issued_fence = ? AND state = 'INITIAL_BOOTSTRAP_INTENT'",
                  decimal(operationFence),
                  decimal(coverageFence),
                  operationId,
                  decimal(operationFence),
                  decimal(coverageFence),
                  descriptor.issuerId(),
                  expectedOldOperationId,
                  decimal(current.controlEpoch()),
                  decimal(current.operationFence()),
                  decimal(current.coverageFence()),
                  decimal(previousLastFence));
          if (changed != 1)
            throw conflict("Initial bootstrap supersession lost exact current control");
          return loadAndRequireCurrent(
              transaction,
              Objects.requireNonNull(lockControl(transaction, descriptor.issuerId())),
              Objects.requireNonNull(lockIntent(transaction, operationId)));
        });
  }

  /** Reads the exact persisted pending intent and its frozen cohort by owner-allocated ID. */
  CanonicalGameplayIssuerLayoutBootstrapIntent readCurrentInitialBootstrapIntent(UUID operationId) {
    requireNonNil(operationId, "operationId");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          Record intent =
              transaction.fetchOne(
                  "SELECT * FROM " + INTENT + " WHERE operation_id = ?", operationId);
          if (intent == null) throw conflict("Exact initial issuer-bootstrap intent is missing");
          String issuerId = Objects.requireNonNull(intent.get("issuer_id", String.class));
          Record control = readControl(transaction, issuerId);
          if (control == null) {
            throw conflict("Initial issuer-bootstrap intent lost its positive control row");
          }
          return loadAndRequireCurrent(transaction, control, intent);
        });
  }

  private static void requireTrustedInputScopes(
      TrustedLayoutDescriptor descriptor, TrustedAccountIssuerCheckpoint checkpoint) {
    if (!GameSessionAccountDelegationProfile.ISSUER.equals(descriptor.issuerId())
        || !descriptor.issuerId().equals(checkpoint.issuerId())
        || !CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY.equals(
            descriptor.mappingIdentity())) {
      throw conflict("Trusted issuer descriptor and checkpoint must use the exact Account issuer");
    }
  }

  private static void requireValidSourceEvent(
      String issuerId, TrustedAccountIssuerCheckpoint checkpoint) {
    byte[] eventBytes = checkpoint.latestEventBytes();
    if (eventBytes == null) return;
    CanonicalGameplayIssuerSourceEventVerifier.requireValid(
        issuerId,
        checkpoint.issuerAuthGeneration(),
        eventBytes,
        "Controlled Account issuer source event is not exact UTF-8",
        "Controlled Account issuer source event is not codec-verified",
        "Controlled Account issuer event differs from its exact checkpoint scope",
        CanonicalGameplayIssuerLayoutBootstrapRepository::conflict);
  }

  private static void requireSameTrustedInputs(
      CanonicalGameplayIssuerLayoutBootstrapIntent prior,
      TrustedLayoutDescriptor descriptor,
      TrustedAccountIssuerCheckpoint checkpoint,
      String descriptorDigest,
      String checkpointDigest,
      String sourceEventDigest) {
    var storedLayout = prior.layoutDescriptor();
    var storedSource = prior.sourceCheckpoint();
    if (!storedLayout.issuerId().equals(descriptor.issuerId())
        || !storedLayout.layoutVersion().equals(descriptor.layoutVersion())
        || !storedLayout.partitionCount().equals(descriptor.partitionCount())
        || !storedLayout.partitionCapacity().equals(descriptor.partitionCapacity())
        || !storedLayout.mappingIdentity().equals(descriptor.mappingIdentity())
        || !Arrays.equals(storedLayout.exactDescriptorBytes(), descriptor.exactBytes())
        || !storedLayout.descriptorDigest().equals(descriptorDigest)
        || !storedSource.issuerId().equals(checkpoint.issuerId())
        || !storedSource.issuerAuthGeneration().equals(checkpoint.issuerAuthGeneration())
        || !Arrays.equals(storedSource.exactCheckpointBytes(), checkpoint.checkpointBytes())
        || !storedSource.checkpointDigest().equals(checkpointDigest)
        || !Arrays.equals(storedSource.latestIssuerEventBytes(), checkpoint.latestEventBytes())
        || !Objects.equals(storedSource.latestIssuerEventDigest(), sourceEventDigest)) {
      throw conflict("Initial issuer-bootstrap retry changed its original trusted inputs");
    }
  }

  private static CanonicalGameplayIssuerLayoutBootstrapIntent loadAndRequireCurrent(
      DSLContext transaction, Record control, Record intent) {
    String issuerId = Objects.requireNonNull(intent.get("issuer_id", String.class));
    UUID operationId = Objects.requireNonNull(intent.get("operation_id", UUID.class));
    UUID requestId = Objects.requireNonNull(intent.get("request_id", UUID.class));
    BigInteger controlEpoch = readBigInteger(intent, "control_epoch");
    BigInteger operationFence = readBigInteger(intent, "operation_fence");
    BigInteger coverageFence = readBigInteger(intent, "coverage_fence");
    BigInteger snapshotRevision = readBigInteger(intent, "inventory_snapshot_revision");

    if (!issuerId.equals(control.get("issuer_id", String.class))
        || !"INITIAL_BOOTSTRAP_INTENT".equals(control.get("state", String.class))
        || !operationId.equals(control.get("current_operation_id", UUID.class))
        || !controlEpoch.equals(readBigInteger(control, "control_epoch"))
        || !operationFence.equals(readBigInteger(control, "current_operation_fence"))
        || !coverageFence.equals(readBigInteger(control, "current_coverage_fence"))
        || !operationFence.equals(controlEpoch)
        || operationFence.equals(coverageFence)
        || !"INITIAL_BOOTSTRAP_INTENT".equals(intent.get("intent_state", String.class))) {
      throw conflict("Initial issuer-bootstrap control and immutable intent differ");
    }

    byte[] descriptorBytes =
        Objects.requireNonNull(intent.get("layout_descriptor_bytes", byte[].class));
    String descriptorDigest =
        Objects.requireNonNull(intent.get("layout_descriptor_digest", String.class));
    if (!sha256(descriptorBytes).equals(descriptorDigest)) {
      throw conflict("Stored issuer layout descriptor digest is invalid");
    }
    var descriptor =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor(
            issuerId,
            readBigInteger(intent, "layout_version"),
            readBigInteger(intent, "partition_count"),
            readBigInteger(intent, "partition_capacity"),
            Objects.requireNonNull(intent.get("mapping_identity", String.class)),
            descriptorBytes,
            descriptorDigest);

    byte[] checkpointBytes =
        Objects.requireNonNull(intent.get("source_checkpoint_bytes", byte[].class));
    String checkpointDigest =
        Objects.requireNonNull(intent.get("source_checkpoint_digest", String.class));
    byte[] eventBytes = intent.get("source_event_bytes", byte[].class);
    String eventDigest = intent.get("source_event_digest", String.class);
    if (!sha256(checkpointBytes).equals(checkpointDigest)
        || ((eventBytes == null) != (eventDigest == null))
        || (eventBytes != null && !sha256(eventBytes).equals(eventDigest))) {
      throw conflict("Stored Account issuer checkpoint digest is invalid");
    }
    var sourceCheckpoint =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
            issuerId,
            readBigInteger(intent, "issuer_auth_generation"),
            checkpointBytes,
            checkpointDigest,
            eventBytes,
            eventDigest);
    if (eventBytes != null) {
      requireValidSourceEvent(
          issuerId,
          new TrustedAccountIssuerCheckpoint(
              issuerId, sourceCheckpoint.issuerAuthGeneration(), checkpointBytes, eventBytes));
    }

    CanonicalGameplayLegacyMigrationDisposition legacy = persistedLegacyDisposition(intent);
    CanonicalGameplayLegacyMigrationDisposition currentLegacy =
        readVerifiedLegacyDisposition(transaction, snapshotRevision, false);
    if (!legacy.equals(currentLegacy)) {
      throw conflict("Verified legacy disposition changed after initial issuer intent capture");
    }

    List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohortRows =
        transaction
            .fetch(
                "SELECT * FROM " + COHORT + " WHERE operation_id = ? ORDER BY row_ordinal",
                operationId)
            .map(
                row -> {
                  String sourceJson =
                      Objects.requireNonNull(row.get("source_row_json", String.class));
                  String sourceDigest =
                      Objects.requireNonNull(row.get("source_row_digest", String.class));
                  if (!sha256(sourceJson.getBytes(StandardCharsets.UTF_8)).equals(sourceDigest)) {
                    throw conflict("Stored issuer cohort source-row digest is invalid");
                  }
                  return new CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow(
                      Objects.requireNonNull(row.get("row_ordinal", Long.class)),
                      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.valueOf(
                          Objects.requireNonNull(row.get("source_kind", String.class))),
                      Objects.requireNonNull(row.get("source_key", String.class)),
                      readBigInteger(row, "source_inventory_revision"),
                      sourceJson,
                      sourceDigest);
                });
    if (cohortRows.stream()
            .anyMatch(row -> row.sourceInventoryRevision().compareTo(snapshotRevision) > 0)
        || cohortRows.size() != readLong(intent, "cohort_row_count")) {
      throw conflict("Stored issuer cohort is incomplete or newer than its snapshot");
    }
    for (int index = 0; index < cohortRows.size(); index++) {
      if (cohortRows.get(index).rowOrdinal() != index) {
        throw conflict("Stored issuer cohort row ordinals are not contiguous from zero");
      }
    }
    String cohortDigest = digestCohort(cohortRows);
    if (!cohortDigest.equals(intent.get("cohort_digest", String.class))) {
      throw conflict("Stored issuer cohort digest does not match its immutable row set");
    }

    return new CanonicalGameplayIssuerLayoutBootstrapIntent(
        operationId,
        requestId,
        issuerId,
        controlEpoch,
        operationFence,
        coverageFence,
        snapshotRevision,
        descriptor,
        sourceCheckpoint,
        legacy,
        CanonicalGameplayIssuerLayoutBootstrapIntent.State.INITIAL_BOOTSTRAP_INTENT,
        cohortRows,
        cohortDigest);
  }

  private static CanonicalGameplayLegacyMigrationDisposition persistedLegacyDisposition(
      Record intent) {
    return new CanonicalGameplayLegacyMigrationDisposition(
        Objects.requireNonNull(intent.get("legacy_cohort_id", UUID.class)),
        Objects.requireNonNull(intent.get("legacy_owner_operation_id", UUID.class)),
        Objects.requireNonNull(intent.get("legacy_writer_fence", UUID.class)),
        readBigInteger(intent, "legacy_source_snapshot_revision"),
        readBigInteger(intent, "legacy_canonical_inventory_revision"),
        readBigInteger(intent, "legacy_namespace_index_readback_revision"),
        Objects.requireNonNull(intent.get("legacy_evidence_digest", String.class)));
  }

  private static CanonicalGameplayLegacyMigrationDisposition readVerifiedLegacyDisposition(
      DSLContext transaction, BigInteger snapshotRevision, boolean lockForShare) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM "
                + LEGACY_DISPOSITION
                + " WHERE singleton_id = 1"
                + (lockForShare ? " FOR SHARE" : ""));
    if (row == null || !"VERIFIED".equals(row.get("disposition_state", String.class))) {
      throw conflict("Initial issuer bootstrap requires the actual VERIFIED legacy disposition");
    }
    final CanonicalGameplayLegacyMigrationDisposition disposition;
    try {
      disposition =
          new CanonicalGameplayLegacyMigrationDisposition(
              row.get("cohort_id", UUID.class),
              row.get("owner_operation_id", UUID.class),
              row.get("legacy_writer_fence", UUID.class),
              readBigInteger(row, "source_snapshot_revision"),
              readBigInteger(row, "canonical_inventory_revision"),
              readBigInteger(row, "namespace_index_readback_revision"),
              row.get("evidence_digest", String.class));
    } catch (RuntimeException malformed) {
      throw conflict("Actual VERIFIED legacy disposition is incomplete or malformed");
    }
    if (disposition.canonicalInventoryRevision().compareTo(snapshotRevision) > 0) {
      throw conflict("VERIFIED legacy disposition is newer than the locked issuer inventory");
    }
    return disposition;
  }

  private static List<CohortSourceRow> captureCohort(
      DSLContext transaction, BigInteger snapshotRevision) {
    requireNoFutureInventoryRows(transaction, snapshotRevision);
    return transaction
        .fetch(
            CanonicalGameplayIssuerCohortSql.cohortSql(),
            CanonicalGameplayIssuerCohortSql.cohortBindings(ISSUER, decimal(snapshotRevision)))
        .map(
            row ->
                new CohortSourceRow(
                    CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.valueOf(
                        Objects.requireNonNull(row.get("source_kind", String.class))),
                    Objects.requireNonNull(row.get("source_key", String.class)),
                    readBigInteger(row, "source_revision"),
                    Objects.requireNonNull(row.get("source_row_json", String.class))));
  }

  private static List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> freezeCohort(
      List<CohortSourceRow> sourceRows) {
    List<CohortSourceRow> sorted =
        sourceRows.stream()
            .sorted(
                Comparator.comparing((CohortSourceRow row) -> row.kind().name())
                    .thenComparing(CohortSourceRow::sourceKey))
            .toList();
    List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> result = new ArrayList<>();
    for (int index = 0; index < sorted.size(); index++) {
      CohortSourceRow row = sorted.get(index);
      result.add(
          new CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow(
              index,
              row.kind(),
              row.sourceKey(),
              row.sourceInventoryRevision(),
              row.sourceRowJson(),
              sha256(row.sourceRowJson().getBytes(StandardCharsets.UTF_8))));
    }
    return List.copyOf(result);
  }

  private static String digestCohort(
      List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> rows) {
    MessageDigest digest = sha256Digest();
    rows.stream()
        .sorted(
            Comparator.comparingLong(
                CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::rowOrdinal))
        .forEach(
            row -> {
              updateFramed(
                  digest, Long.toString(row.rowOrdinal()).getBytes(StandardCharsets.US_ASCII));
              updateFramed(digest, row.kind().name().getBytes(StandardCharsets.UTF_8));
              updateFramed(digest, row.sourceKey().getBytes(StandardCharsets.UTF_8));
              updateFramed(
                  digest,
                  row.sourceInventoryRevision().toString().getBytes(StandardCharsets.US_ASCII));
              updateFramed(digest, row.sourceRowDigest().getBytes(StandardCharsets.US_ASCII));
            });
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }

  private static void updateFramed(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }

  private static void insertIntent(
      DSLContext transaction,
      UUID operationId,
      UUID requestId,
      TrustedLayoutDescriptor descriptor,
      String descriptorDigest,
      TrustedAccountIssuerCheckpoint sourceCheckpoint,
      String checkpointDigest,
      String sourceEventDigest,
      BigInteger snapshotRevision,
      BigInteger controlEpoch,
      BigInteger operationFence,
      BigInteger coverageFence,
      CanonicalGameplayLegacyMigrationDisposition legacy,
      List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohortRows,
      String cohortDigest) {
    transaction.execute(
        "INSERT INTO "
            + INTENT
            + " (operation_id, request_id, issuer_id, control_epoch, operation_fence,"
            + " coverage_fence, inventory_snapshot_revision, layout_version, partition_count,"
            + " partition_capacity, mapping_identity, layout_descriptor_bytes,"
            + " layout_descriptor_digest, issuer_auth_generation, source_checkpoint_bytes,"
            + " source_checkpoint_digest, source_event_bytes, source_event_digest,"
            + " legacy_cohort_id, legacy_owner_operation_id, legacy_writer_fence,"
            + " legacy_source_snapshot_revision, legacy_canonical_inventory_revision,"
            + " legacy_namespace_index_readback_revision, legacy_evidence_digest,"
            + " cohort_row_count, cohort_digest, intent_state)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ",
        operationId,
        requestId,
        descriptor.issuerId(),
        decimal(controlEpoch),
        decimal(operationFence),
        decimal(coverageFence),
        decimal(snapshotRevision),
        decimal(descriptor.layoutVersion()),
        decimal(descriptor.partitionCount()),
        decimal(descriptor.partitionCapacity()),
        descriptor.mappingIdentity(),
        descriptor.exactBytes(),
        descriptorDigest,
        decimal(sourceCheckpoint.issuerAuthGeneration()),
        sourceCheckpoint.checkpointBytes(),
        checkpointDigest,
        sourceCheckpoint.latestEventBytes(),
        sourceEventDigest,
        legacy.cohortId(),
        legacy.ownerOperationId(),
        legacy.legacyWriterFence(),
        decimal(legacy.sourceSnapshotRevision()),
        decimal(legacy.canonicalInventoryRevision()),
        decimal(legacy.namespaceIndexReadbackRevision()),
        legacy.evidenceDigest(),
        (long) cohortRows.size(),
        cohortDigest,
        CanonicalGameplayIssuerLayoutBootstrapIntent.State.INITIAL_BOOTSTRAP_INTENT.name());
  }

  private static void insertCohort(
      DSLContext transaction,
      UUID operationId,
      String issuerId,
      List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> rows) {
    for (CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow row : rows) {
      transaction.execute(
          "INSERT INTO "
              + COHORT
              + " (operation_id, issuer_id, row_ordinal, source_kind, source_key,"
              + " source_inventory_revision, source_row_json, source_row_digest)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          operationId,
          issuerId,
          row.rowOrdinal(),
          row.kind().name(),
          row.sourceKey(),
          decimal(row.sourceInventoryRevision()),
          row.sourceRowJson(),
          row.sourceRowDigest());
    }
  }

  private static void insertControl(
      DSLContext transaction,
      String issuerId,
      UUID operationId,
      BigInteger controlEpoch,
      BigInteger operationFence,
      BigInteger coverageFence) {
    transaction.execute(
        "INSERT INTO "
            + CONTROL
            + " (issuer_id, state, control_epoch, last_issued_fence, current_operation_id,"
            + " current_operation_fence, current_coverage_fence)"
            + " VALUES (?, 'INITIAL_BOOTSTRAP_INTENT', ?, ?, ?, ?, ?)",
        issuerId,
        decimal(controlEpoch),
        decimal(coverageFence),
        operationId,
        decimal(operationFence),
        decimal(coverageFence));
  }

  private static BigInteger lockInventoryRevision(DSLContext transaction) {
    return readBigInteger(
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT inventory_revision FROM "
                    + INVENTORY_CLOCK
                    + " WHERE singleton_id = 1 FOR UPDATE"),
            "canonical inventory clock row must exist"),
        "inventory_revision");
  }

  private static Record lockControl(DSLContext transaction, String issuerId) {
    return transaction.fetchOne(
        "SELECT * FROM " + CONTROL + " WHERE issuer_id = ? FOR UPDATE", issuerId);
  }

  private static Record readControl(DSLContext transaction, String issuerId) {
    return transaction.fetchOne("SELECT * FROM " + CONTROL + " WHERE issuer_id = ?", issuerId);
  }

  private static Record lockIntent(DSLContext transaction, UUID operationId) {
    return transaction.fetchOne(
        "SELECT * FROM " + INTENT + " WHERE operation_id = ? FOR UPDATE", operationId);
  }

  private static void requireNoFutureInventoryRows(
      DSLContext transaction, BigInteger snapshotRevision) {
    Record row =
        Objects.requireNonNull(
            transaction.fetchOne(
                CanonicalGameplayIssuerCohortSql.FUTURE_REVISION_SQL,
                CanonicalGameplayIssuerCohortSql.futureRevisionBindings(decimal(snapshotRevision))),
            "future inventory revision query must return one row");
    if (Boolean.TRUE.equals(row.get("has_future_revision", Boolean.class))) {
      throw conflict("Issuer bootstrap cannot omit durable rows newer than the inventory clock");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw conflict("Issuer bootstrap owner cannot join an ambient transaction");
    }
  }

  private static String sha256(byte[] bytes) {
    return "sha256:" + HexFormat.of().formatHex(sha256Digest().digest(bytes));
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }

  private static BigInteger readBigInteger(Record row, String field) {
    BigDecimal value = Objects.requireNonNull(row.get(field, BigDecimal.class), field);
    return value.toBigIntegerExact();
  }

  private static long readLong(Record row, String field) {
    return Objects.requireNonNull(row.get(field, Long.class), field);
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException(name + " must not be nil");
    return value;
  }

  private static UUID randomNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (value.equals(new UUID(0L, 0L)));
    return value;
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  private record CohortSourceRow(
      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind kind,
      String sourceKey,
      BigInteger sourceInventoryRevision,
      String sourceRowJson) {}

  /** Shape-only trusted descriptor input; this code does not authenticate its authority. */
  static final class TrustedLayoutDescriptor {
    private final String issuerId;
    private final BigInteger layoutVersion;
    private final BigInteger partitionCount;
    private final BigInteger partitionCapacity;
    private final String mappingIdentity;
    private final byte[] exactBytes;

    TrustedLayoutDescriptor(
        String issuerId,
        BigInteger layoutVersion,
        BigInteger partitionCount,
        BigInteger partitionCapacity,
        String mappingIdentity,
        byte[] exactBytes) {
      this.issuerId = Objects.requireNonNull(issuerId, "issuerId");
      this.layoutVersion = Objects.requireNonNull(layoutVersion, "layoutVersion");
      this.partitionCount = Objects.requireNonNull(partitionCount, "partitionCount");
      this.partitionCapacity = Objects.requireNonNull(partitionCapacity, "partitionCapacity");
      this.mappingIdentity = Objects.requireNonNull(mappingIdentity, "mappingIdentity");
      this.exactBytes = cloneNonEmpty(exactBytes, "exactBytes");
      if (layoutVersion.signum() <= 0
          || partitionCount.signum() <= 0
          || partitionCapacity.signum() <= 0
          || !CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY.equals(
              mappingIdentity)) {
        throw new IllegalArgumentException("Trusted layout descriptor is malformed");
      }
    }

    String issuerId() {
      return issuerId;
    }

    BigInteger layoutVersion() {
      return layoutVersion;
    }

    BigInteger partitionCount() {
      return partitionCount;
    }

    BigInteger partitionCapacity() {
      return partitionCapacity;
    }

    String mappingIdentity() {
      return mappingIdentity;
    }

    byte[] exactBytes() {
      return exactBytes.clone();
    }
  }

  /**
   * Shape-only trusted Account-current-source input. Checkpoint bytes are retained exactly; when a
   * latest event is supplied, the existing Account event codec verifies it before persistence.
   */
  static final class TrustedAccountIssuerCheckpoint {
    private final String issuerId;
    private final BigInteger issuerAuthGeneration;
    private final byte[] checkpointBytes;
    private final byte[] latestEventBytes;

    TrustedAccountIssuerCheckpoint(
        String issuerId,
        BigInteger issuerAuthGeneration,
        byte[] checkpointBytes,
        byte[] latestEventBytes) {
      this.issuerId = Objects.requireNonNull(issuerId, "issuerId");
      this.issuerAuthGeneration =
          Objects.requireNonNull(issuerAuthGeneration, "issuerAuthGeneration");
      this.checkpointBytes = cloneNonEmpty(checkpointBytes, "checkpointBytes");
      this.latestEventBytes =
          latestEventBytes == null ? null : cloneNonEmpty(latestEventBytes, "latestEventBytes");
      if (issuerAuthGeneration.signum() <= 0) {
        throw new IllegalArgumentException("issuerAuthGeneration must be positive");
      }
    }

    String issuerId() {
      return issuerId;
    }

    BigInteger issuerAuthGeneration() {
      return issuerAuthGeneration;
    }

    byte[] checkpointBytes() {
      return checkpointBytes.clone();
    }

    byte[] latestEventBytes() {
      return latestEventBytes == null ? null : latestEventBytes.clone();
    }
  }

  private static byte[] cloneNonEmpty(byte[] value, String name) {
    Objects.requireNonNull(value, name);
    if (value.length == 0) throw new IllegalArgumentException(name + " must not be empty");
    return value.clone();
  }
}
