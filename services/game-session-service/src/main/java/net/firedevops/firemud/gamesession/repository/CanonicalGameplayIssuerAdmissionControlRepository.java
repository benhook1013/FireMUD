package net.firedevops.firemud.gamesession.repository;

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
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL owner for the positive, reset-sensitive live issuer admission-control row. */
final class CanonicalGameplayIssuerAdmissionControlRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String CONTROL = "game_session_canonical_issuer_admission_control";
  private static final String COUNTER = "game_session_canonical_issuer_admission_epoch_counter";
  private static final String OPERATION = "game_session_canonical_issuer_admission_operation";
  private static final String LAYOUT_CONTROL =
      "game_session_canonical_issuer_layout_bootstrap_control";
  private static final String ANCHOR = "game_session_canonical_issuer_installed_anchor";
  private static final String LOCAL_OPERATION =
      "game_session_canonical_issuer_binding_local_operation";
  private static final String BOOTSTRAP_MEMBER_OPERATION =
      "game_session_canonical_issuer_bootstrap_member_operation";
  private static final String COHORT = "game_session_canonical_issuer_admission_cohort_row";
  private static final String INITIALIZER = "game_session_canonical_issuer_admission_initializer";
  private static final String INITIALIZER_RECEIPT =
      "game_session_canonical_issuer_admission_initializer_receipt";
  private static final String CUTOFF_TARGET =
      "game_session_canonical_issuer_admission_cutoff_target";
  private static final String TENANT_TAG = "game_session_canonical_tenant_gameplay_tag";

  private final DSLContext dsl;

  CanonicalGameplayIssuerAdmissionControlRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Positively installs the first live OPEN row from an already-qualified immutable anchor. The
   * source and incarnation are controlled local observations, not authority produced here.
   */
  CanonicalGameplayIssuerAdmissionControlValue installInitialOpen(
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentStateSource) {
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(currentStateSource, "currentStateSource");
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState before =
        Objects.requireNonNull(
            currentStateSource.readFreshCurrentState(), "current state source returned null");
    CanonicalGameplayIssuerAdmissionControlValue installed =
        dsl.transactionResult(
            configuration -> installInitialInTransaction(DSL.using(configuration), anchor, before));
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState after =
        Objects.requireNonNull(
            currentStateSource.readFreshCurrentState(),
            "current state source returned null after install");
    if (!installed.matchesCurrentIssuerState(after)) {
      throw conflict(
          "Current Account issuer source or stable incarnation moved during live-control installation");
    }
    return installed;
  }

  /**
   * Freezes the complete current issuer cohort and advances the positive live-control epoch before
   * any tenant-local CLOSED writes are attempted. Tenant keys come from the locked cohort and each
   * key must have its exact positive current SQL tenant-tag mapping.
   */
  CanonicalGameplayIssuerAdmissionControlValue beginCutoff(
      UUID cutoffOperationId,
      UUID requestId,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    requireNonNil(cutoffOperationId, "cutoffOperationId");
    requireNonNil(requestId, "requestId");
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(currentState, "currentState");
    if (cutoffOperationId.equals(requestId)) {
      throw conflict("Issuer cutoff operation and request identities must be distinct");
    }
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger snapshotRevision =
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          lockLayoutControl(transaction, anchor);
          Record controlRow =
              transaction.fetchOne(
                  "SELECT * FROM " + CONTROL + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
          if (controlRow == null) {
            throw conflict("Issuer cutoff requires a positively installed live control row");
          }
          CanonicalGameplayIssuerAdmissionControlValue control = loadControl(controlRow);
          requireExactAnchorAndCurrentSource(control, anchor, currentState);
          Record counter =
              transaction.fetchOne(
                  "SELECT * FROM " + COUNTER + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
          if (counter == null) throw conflict("The positive live issuer epoch counter is missing");
          requireCounterMatchesCurrent(transaction, control.liveBarrierEpoch());

          Record priorOperation =
              transaction.fetchOne(
                  "SELECT * FROM " + OPERATION + " WHERE operation_id = ? FOR UPDATE",
                  cutoffOperationId);
          if (control.state() == CanonicalGameplayIssuerAdmissionControlValue.State.CUTOFF_PENDING
              && control.currentOperationId().equals(cutoffOperationId)) {
            if (priorOperation == null
                || !requestId.equals(uuid(priorOperation, "request_id"))
                || !"CUTOFF".equals(text(priorOperation, "operation_kind"))
                || !"CUTOFF_PENDING".equals(text(priorOperation, "operation_state"))) {
              throw conflict("Issuer cutoff retry differs from its exact pending operation");
            }
            verifyCutoffTargets(transaction, control);
            return control;
          }
          if (control.state() != CanonicalGameplayIssuerAdmissionControlValue.State.OPEN
              || control.currentOperationKind()
                  != CanonicalGameplayIssuerAdmissionControlValue.OperationKind
                      .INITIAL_ANCHOR_INSTALL
              || !control.currentOperationId().equals(anchor.bootstrapOperationId())
              || priorOperation != null) {
            throw conflict(
                "Issuer live control cannot be reset, reopened, or replaced by this cutoff");
          }
          requireInitialOperationCurrent(transaction, control);

          List<CohortRow> rows = captureCutoffCohort(transaction, snapshotRevision);
          Map<TargetScope, List<CohortRow>> targets = groupTargets(rows);
          Map<TargetScope, String> tagByTarget = new TreeMap<>();
          for (TargetScope scope : targets.keySet()) {
            tagByTarget.put(scope, requireCurrentTenantTagMapping(transaction, scope.tenantId()));
          }

          BigInteger priorEpoch = control.liveBarrierEpoch();
          Record allocated =
              transaction.fetchOne(
                  "UPDATE "
                      + COUNTER
                      + " SET last_live_barrier_epoch = last_live_barrier_epoch + 1,"
                      + " updated_at = CURRENT_TIMESTAMP"
                      + " WHERE issuer_id = ? AND last_live_barrier_epoch = ?"
                      + " RETURNING last_live_barrier_epoch",
                  ISSUER,
                  priorEpoch);
          if (allocated == null) {
            throw conflict("Issuer cutoff lost its serialized live epoch allocation");
          }
          BigInteger cutoffEpoch = integer(allocated, "last_live_barrier_epoch");
          String cohortDigest = digestCohort(rows);
          CanonicalGameplayIssuerAdmissionControlValue pending =
              control.cutoffPending(
                  cutoffOperationId, cutoffEpoch, snapshotRevision, rows.size(), cohortDigest);
          insertCutoffOperation(transaction, pending, requestId, priorEpoch);
          updateControl(transaction, pending, control.liveBarrierEpoch());
          insertCohortRows(transaction, pending.currentOperationId(), rows);
          insertCutoffTargets(transaction, pending, targets, tagByTarget);
          verifyCutoffTargets(transaction, pending);
          return pending;
        });
  }

  /** Publishes INSTALLED only after every captured tenant target has an exact CLOSED readback. */
  CanonicalGameplayIssuerAdmissionControlValue completeCutoff(
      UUID cutoffOperationId,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    requireNonNil(cutoffOperationId, "cutoffOperationId");
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(currentState, "currentState");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          lockLayoutControl(transaction, anchor);
          Record controlRow =
              transaction.fetchOne(
                  "SELECT * FROM " + CONTROL + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
          if (controlRow == null) throw conflict("Issuer cutoff control row is missing");
          CanonicalGameplayIssuerAdmissionControlValue control = loadControl(controlRow);
          requireExactAnchorAndCurrentSource(control, anchor, currentState);
          requireCounterMatchesCurrent(transaction, control.liveBarrierEpoch());
          if (!control.currentOperationId().equals(cutoffOperationId)
              || control.state()
                  != CanonicalGameplayIssuerAdmissionControlValue.State.CUTOFF_PENDING
              || control.currentOperationKind()
                  != CanonicalGameplayIssuerAdmissionControlValue.OperationKind.CUTOFF) {
            if (control.currentOperationId().equals(cutoffOperationId)
                && control.state()
                    == CanonicalGameplayIssuerAdmissionControlValue.State.INSTALLED) {
              requireCutoffOperationCurrent(transaction, control);
              verifyCutoffTargets(transaction, control);
              return control;
            }
            throw conflict("The exact current cutoff is not pending completion");
          }
          requireCutoffOperationCurrent(transaction, control);
          verifyCutoffTargets(transaction, control);
          Record remaining =
              transaction.fetchOne(
                  "SELECT EXISTS (SELECT 1 FROM "
                      + CUTOFF_TARGET
                      + " WHERE operation_id = ? AND issuer_id = ? AND target_state <> 'CLOSED')"
                      + " AS has_open_target",
                  cutoffOperationId,
                  ISSUER);
          if (remaining == null
              || Boolean.TRUE.equals(remaining.get("has_open_target", Boolean.class))) {
            throw conflict(
                "Every frozen issuer tenant target must read back CLOSED before installation");
          }
          CanonicalGameplayIssuerAdmissionControlValue installed = control.installed();
          int updatedOperation =
              transaction.execute(
                  "UPDATE "
                      + OPERATION
                      + " SET operation_state = 'INSTALLED', installed_at = CURRENT_TIMESTAMP"
                      + " WHERE operation_id = ? AND issuer_id = ? AND operation_kind = 'CUTOFF'"
                      + " AND operation_state = 'CUTOFF_PENDING' AND live_barrier_epoch = ?"
                      + " AND prior_live_barrier_epoch = ?",
                  cutoffOperationId,
                  ISSUER,
                  control.liveBarrierEpoch(),
                  control.liveBarrierEpoch().subtract(BigInteger.ONE));
          if (updatedOperation != 1)
            throw conflict("Issuer cutoff operation changed before installation");
          updateControl(transaction, installed, control.liveBarrierEpoch());
          Record saved =
              transaction.fetchOne(
                  "SELECT * FROM " + CONTROL + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
          if (saved == null
              || !Arrays.equals(loadControl(saved).exactBytes(), installed.exactBytes())) {
            throw conflict("Installed issuer cutoff did not read back exactly");
          }
          return installed;
        });
  }

  /** Revalidates a requested CLOSED key against the complete frozen cutoff cohort. */
  void verifyCurrentCutoffTarget(
      DSLContext transaction,
      CanonicalGameplayIssuerAdmissionControlValue control,
      UUID tenantId,
      UUID namespaceId,
      byte[] expectedClosedPayload) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(control, "control");
    requireNonNil(tenantId, "tenantId");
    requireNonNil(namespaceId, "namespaceId");
    if (control.state() != CanonicalGameplayIssuerAdmissionControlValue.State.CUTOFF_PENDING
        || control.currentOperationKind()
            != CanonicalGameplayIssuerAdmissionControlValue.OperationKind.CUTOFF) {
      throw conflict("A CLOSED target requires the current pending issuer cutoff");
    }
    requireCutoffOperationCurrent(transaction, control);
    verifyCutoffTargets(transaction, control);
    Record target =
        transaction.fetchOne(
            "SELECT * FROM "
                + CUTOFF_TARGET
                + " WHERE operation_id = ? AND issuer_id = ? AND tenant_id = ?"
                + " AND playable_state_namespace_id = ? FOR UPDATE",
            control.currentOperationId(),
            ISSUER,
            tenantId,
            namespaceId);
    if (target == null
        || (expectedClosedPayload != null
            && !Arrays.equals(bytes(target, "closed_payload_bytes"), expectedClosedPayload))) {
      throw conflict("Requested CLOSED target differs from the exact frozen cutoff cohort");
    }
  }

  private static List<CohortRow> captureCutoffCohort(
      DSLContext transaction, BigInteger snapshotRevision) {
    requireNoFutureCutoffRows(transaction, snapshotRevision);
    String sql =
        "SELECT source_kind, source_key, source_revision, source_row_json, tenant_id,"
            + " playable_state_namespace_id, transition_id FROM ("
            + " SELECT 'BINDING' AS source_kind, encode(i.binding_ref, 'hex') AS source_key,"
            + " i.inventory_revision AS source_revision, to_jsonb(i)::text AS source_row_json,"
            + " i.tenant_id, i.playable_state_namespace_id, i.transition_id"
            + " FROM game_session_canonical_gameplay_binding_inventory i"
            + " WHERE i.issuer_id = ? AND i.inventory_revision <= ?"
            + " UNION ALL SELECT 'TRANSITION', t.transition_id::text, t.inventory_revision,"
            + " to_jsonb(t)::text, t.tenant_id, t.playable_state_namespace_id, t.transition_id"
            + " FROM game_session_canonical_binding_transition t"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.transition_id = t.transition_id AND i.issuer_id = ?"
            + " WHERE t.inventory_revision <= ?"
            + " UNION ALL SELECT 'RESERVATION', r.reservation_id::text, r.inventory_revision,"
            + " to_jsonb(r)::text, r.tenant_id, r.playable_state_namespace_id, r.transition_id"
            + " FROM game_session_canonical_issuer_partition_reservation r"
            + " WHERE r.issuer_id = ? AND r.inventory_revision <= ?"
            + " UNION ALL SELECT 'ACCOUNT_OBLIGATION',"
            + " a.transition_id::text || ':' || a.obligation_ordinal::text, a.inventory_revision,"
            + " to_jsonb(a)::text, t.tenant_id, t.playable_state_namespace_id, t.transition_id"
            + " FROM game_session_canonical_binding_account_index_obligation a"
            + " JOIN game_session_canonical_binding_transition t ON t.transition_id = a.transition_id"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = a.binding_ref AND i.issuer_id = ?"
            + " WHERE a.inventory_revision <= ?"
            + " UNION ALL SELECT 'ISSUER_OBLIGATION',"
            + " encode(o.binding_ref, 'hex') || ':' || o.transition_id::text, o.inventory_revision,"
            + " to_jsonb(o)::text, t.tenant_id, t.playable_state_namespace_id, t.transition_id"
            + " FROM game_session_canonical_binding_issuer_index_obligation o"
            + " JOIN game_session_canonical_binding_transition t ON t.transition_id = o.transition_id"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = o.binding_ref AND i.issuer_id = ?"
            + " WHERE o.inventory_revision <= ?"
            + " UNION ALL SELECT 'REGION_OBLIGATION', g.transition_id::text, g.inventory_revision,"
            + " to_jsonb(g)::text, t.tenant_id, t.playable_state_namespace_id, t.transition_id"
            + " FROM game_session_canonical_binding_region_bridge_obligation g"
            + " JOIN game_session_canonical_binding_transition t ON t.transition_id = g.transition_id"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = g.binding_ref AND i.issuer_id = ?"
            + " WHERE g.inventory_revision <= ?"
            + " UNION ALL SELECT 'LOCAL_OPERATION', o.operation_id::text,"
            + " o.local_inventory_snapshot_revision, to_jsonb(o)::text, t.tenant_id,"
            + " t.playable_state_namespace_id, t.transition_id"
            + " FROM game_session_canonical_issuer_binding_local_operation o"
            + " JOIN game_session_canonical_binding_transition t ON t.transition_id = o.transition_id"
            + " WHERE o.issuer_id = ? AND o.local_inventory_snapshot_revision <= ?"
            + " UNION ALL SELECT 'BOOTSTRAP_MEMBER_OPERATION', m.member_operation_id::text,"
            + " m.original_inventory_snapshot_revision, to_jsonb(m)::text, r.tenant_id,"
            + " r.playable_state_namespace_id, r.transition_id"
            + " FROM game_session_canonical_issuer_bootstrap_member_operation m"
            + " JOIN game_session_canonical_issuer_partition_reservation r"
            + " ON r.reservation_id = m.reservation_id"
            + " WHERE m.issuer_id = ? AND m.original_inventory_snapshot_revision <= ?"
            + " UNION ALL SELECT 'PENDING_INITIALIZER', init.operation_id::text,"
            + " local.local_inventory_snapshot_revision, to_jsonb(init)::text, init.tenant_id,"
            + " init.playable_state_namespace_id, local.transition_id"
            + " FROM game_session_canonical_issuer_admission_initializer init"
            + " JOIN game_session_canonical_issuer_binding_local_operation local"
            + " ON local.operation_id = init.operation_id AND local.issuer_id = init.issuer_id"
            + " LEFT JOIN game_session_canonical_issuer_admission_initializer_receipt receipt"
            + " ON receipt.operation_id = init.operation_id AND receipt.issuer_id = init.issuer_id"
            + " WHERE init.issuer_id = ? AND receipt.operation_id IS NULL"
            + " AND local.local_inventory_snapshot_revision <= ?"
            + ") captured ORDER BY source_kind COLLATE \"C\", source_key COLLATE \"C\"";
    List<Object> binds = new ArrayList<>();
    for (int index = 0; index < 9; index++) {
      binds.add(ISSUER);
      binds.add(snapshotRevision);
    }
    return transaction
        .fetch(sql, binds.toArray())
        .map(
            row -> {
              String json = text(row, "source_row_json");
              String digest =
                  CanonicalGameplayIssuerAdmissionControlValue.sha256(
                      json.getBytes(StandardCharsets.UTF_8));
              BigInteger revision = integer(row, "source_revision");
              if (revision.signum() <= 0 || revision.compareTo(snapshotRevision) > 0) {
                throw conflict(
                    "Issuer cutoff cohort contains a source outside its locked snapshot");
              }
              return new CohortRow(
                  text(row, "source_kind"),
                  text(row, "source_key"),
                  revision,
                  json,
                  uuid(row, "tenant_id"),
                  uuid(row, "playable_state_namespace_id"),
                  uuid(row, "transition_id"),
                  digest);
            });
  }

  private static void requireNoFutureCutoffRows(
      DSLContext transaction, BigInteger snapshotRevision) {
    String sql =
        "SELECT EXISTS (SELECT 1 FROM game_session_canonical_gameplay_binding_inventory"
            + " WHERE issuer_id = ? AND inventory_revision > ?)"
            + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_transition t"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.transition_id = t.transition_id AND i.issuer_id = ?"
            + " WHERE t.inventory_revision > ?)"
            + " OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_partition_reservation"
            + " WHERE issuer_id = ? AND inventory_revision > ?)"
            + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_account_index_obligation a"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = a.binding_ref AND i.issuer_id = ?"
            + " WHERE a.inventory_revision > ?)"
            + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_issuer_index_obligation o"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = o.binding_ref AND i.issuer_id = ?"
            + " WHERE o.inventory_revision > ?)"
            + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_region_bridge_obligation g"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = g.binding_ref AND i.issuer_id = ?"
            + " WHERE g.inventory_revision > ?)"
            + " OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_binding_local_operation"
            + " WHERE issuer_id = ? AND local_inventory_snapshot_revision > ?)"
            + " OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_bootstrap_member_operation"
            + " WHERE issuer_id = ? AND original_inventory_snapshot_revision > ?)"
            + " OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_admission_initializer init"
            + " JOIN game_session_canonical_issuer_binding_local_operation local"
            + " ON local.operation_id = init.operation_id AND local.issuer_id = init.issuer_id"
            + " WHERE init.issuer_id = ? AND local.local_inventory_snapshot_revision > ?)"
            + " AS has_future_rows";
    List<Object> binds = new ArrayList<>();
    for (int index = 0; index < 9; index++) {
      binds.add(ISSUER);
      binds.add(snapshotRevision);
    }
    Record future = transaction.fetchOne(sql, binds.toArray());
    if (future == null || Boolean.TRUE.equals(future.get("has_future_rows", Boolean.class))) {
      throw conflict(
          "Issuer cutoff found source rows newer than its serialized inventory snapshot");
    }
  }

  private static Map<TargetScope, List<CohortRow>> groupTargets(List<CohortRow> rows) {
    Map<TargetScope, List<CohortRow>> targets = new TreeMap<>();
    for (CohortRow row : rows) {
      TargetScope scope = new TargetScope(row.tenantId(), row.namespaceId());
      targets.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(row);
    }
    return targets;
  }

  private static void insertCutoffOperation(
      DSLContext transaction,
      CanonicalGameplayIssuerAdmissionControlValue value,
      UUID requestId,
      BigInteger priorEpoch) {
    int inserted =
        transaction.execute(
            "INSERT INTO "
                + OPERATION
                + " (operation_id, request_id, issuer_id, operation_kind, operation_state,"
                + " live_barrier_epoch, prior_live_barrier_epoch, bootstrap_operation_id,"
                + " bootstrap_request_id, bootstrap_control_epoch, anchor_exact_bytes,"
                + " anchor_exact_digest, account_issuer_auth_generation, account_checkpoint_bytes,"
                + " account_checkpoint_digest, account_source_event_bytes, account_source_event_digest,"
                + " stable_incarnation_bytes, stable_incarnation_digest, inventory_snapshot_revision,"
                + " cohort_row_count, cohort_digest, installed_at)"
                + " VALUES (?, ?, ?, 'CUTOFF', 'CUTOFF_PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
            value.currentOperationId(),
            requestId,
            value.issuerId(),
            value.liveBarrierEpoch(),
            priorEpoch,
            value.bootstrapOperationId(),
            value.bootstrapRequestId(),
            value.bootstrapControlEpoch(),
            value.anchorExactBytes(),
            value.anchorExactDigest(),
            value.accountIssuerAuthGeneration(),
            value.accountCheckpointBytes(),
            value.accountCheckpointDigest(),
            value.accountSourceEventBytes(),
            value.accountSourceEventDigest(),
            value.stableIncarnationBytes(),
            value.stableIncarnationDigest(),
            value.inventorySnapshotRevision(),
            value.cohortRowCount(),
            value.cohortDigest());
    if (inserted != 1) throw conflict("Issuer cutoff operation did not persist");
  }

  private static void insertCohortRows(
      DSLContext transaction, UUID operationId, List<CohortRow> rows) {
    for (int index = 0; index < rows.size(); index++) {
      CohortRow row = rows.get(index);
      int inserted =
          transaction.execute(
              "INSERT INTO "
                  + COHORT
                  + " (operation_id, issuer_id, row_ordinal, source_kind, source_key,"
                  + " source_inventory_revision, source_row_json, source_row_digest)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
              operationId,
              ISSUER,
              (long) index,
              row.sourceKind(),
              row.sourceKey(),
              row.sourceRevision(),
              row.sourceJson(),
              row.sourceDigest());
      if (inserted != 1) throw conflict("Issuer cutoff cohort row did not persist");
    }
  }

  private static void insertCutoffTargets(
      DSLContext transaction,
      CanonicalGameplayIssuerAdmissionControlValue control,
      Map<TargetScope, List<CohortRow>> targets,
      Map<TargetScope, String> tagByTarget) {
    int ordinal = 0;
    for (Map.Entry<TargetScope, List<CohortRow>> entry : targets.entrySet()) {
      TargetScope scope = entry.getKey();
      String tenantTag = tagByTarget.get(scope);
      if (tenantTag == null) throw conflict("Issuer cutoff target has no current SQL tenant tag");
      Record initializerRow =
          transaction.fetchOne(
              "SELECT * FROM "
                  + INITIALIZER
                  + " WHERE issuer_id = ? AND tenant_id = ? AND playable_state_namespace_id = ?"
                  + " AND live_barrier_epoch <= ? ORDER BY live_barrier_epoch DESC LIMIT 1 FOR UPDATE",
              ISSUER,
              scope.tenantId(),
              scope.namespaceId(),
              control.liveBarrierEpoch().subtract(BigInteger.ONE));
      CanonicalGameplayIssuerAdmissionBarrierValue.Initializer initializer = null;
      byte[] openPayload = null;
      String openDigest = null;
      UUID initializerOperationId = null;
      BigInteger initializerEpoch = null;
      String initializerDigest = null;
      if (initializerRow != null) {
        initializerOperationId = uuid(initializerRow, "operation_id");
        initializerEpoch = integer(initializerRow, "live_barrier_epoch");
        byte[] initializerBytes = bytes(initializerRow, "initializer_exact_bytes");
        initializerDigest = text(initializerRow, "initializer_digest");
        openPayload = bytes(initializerRow, "open_payload_bytes");
        openDigest = text(initializerRow, "open_payload_digest");
        if (!CanonicalGameplayIssuerAdmissionControlValue.sha256(initializerBytes)
                .equals(initializerDigest)
            || !CanonicalGameplayIssuerAdmissionControlValue.sha256(openPayload)
                .equals(openDigest)) {
          throw conflict("Persisted original issuer initializer digest is invalid");
        }
        CanonicalGameplayIssuerAdmissionBarrierValue decoded =
            CanonicalGameplayIssuerAdmissionBarrierValue.decode(openPayload);
        initializer = decoded.initializer();
        if (decoded.state() != CanonicalGameplayIssuerAdmissionBarrierValue.State.OPEN
            || !decoded.issuerId().equals(ISSUER)
            || !decoded.tenantId().equals(scope.tenantId())
            || !decoded.playableStateNamespaceId().equals(scope.namespaceId())
            || !decoded.tenantGameplayTag().equals(tenantTag)
            || !decoded.ownerOperationId().equals(initializerOperationId)
            || !decoded.admissionEpoch().equals(initializerEpoch)
            || initializer == null
            || !initializer.exactDigest().equals(initializerDigest)
            || !initializer.operationId().equals(initializerOperationId)
            || !initializer.admissionEpoch().equals(initializerEpoch)
            || !initializer.tenantGameplayTag().equals(tenantTag)
            || !Arrays.equals(initializer.anchorBytes(), control.anchorExactBytes())
            || !initializer.anchorDigest().equals(control.anchorExactDigest())
            || !initializer
                .accountIssuerAuthGeneration()
                .equals(control.accountIssuerAuthGeneration())
            || !Arrays.equals(
                initializer.accountCheckpointBytes(), control.accountCheckpointBytes())
            || !initializer.accountCheckpointDigest().equals(control.accountCheckpointDigest())
            || !Arrays.equals(
                initializer.accountSourceEventBytes(), control.accountSourceEventBytes())
            || !Objects.equals(
                initializer.accountSourceEventDigest(), control.accountSourceEventDigest())
            || !Arrays.equals(initializer.incarnationBytes(), control.stableIncarnationBytes())
            || !initializer.incarnationDigest().equals(control.stableIncarnationDigest())) {
          throw conflict(
              "Captured original OPEN initializer differs from the frozen issuer source");
        }
      }
      String targetDigest = digestTargetSource(entry.getValue());
      CanonicalGameplayIssuerAdmissionBarrierValue closed =
          CanonicalGameplayIssuerAdmissionBarrierValue.closed(
              ISSUER,
              scope.tenantId(),
              scope.namespaceId(),
              tenantTag,
              control.liveBarrierEpoch(),
              control.currentOperationId(),
              initializer);
      int inserted =
          transaction.execute(
              "INSERT INTO "
                  + CUTOFF_TARGET
                  + " (operation_id, issuer_id, target_ordinal, tenant_id,"
                  + " playable_state_namespace_id, tenant_gameplay_tag, target_source_digest,"
                  + " captured_initializer_operation_id, captured_initializer_epoch,"
                  + " captured_initializer_digest, captured_open_payload_bytes,"
                  + " captured_open_payload_digest, closed_payload_bytes, closed_payload_digest,"
                  + " exact_closed_readback_bytes, exact_closed_readback_digest, script_outcome, target_state)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 'REQUIRED')",
              control.currentOperationId(),
              ISSUER,
              (long) ordinal++,
              scope.tenantId(),
              scope.namespaceId(),
              tenantTag,
              targetDigest,
              initializerOperationId,
              initializerEpoch,
              initializerDigest,
              openPayload,
              openDigest,
              closed.exactBytes(),
              closed.exactDigest());
      if (inserted != 1) throw conflict("Issuer cutoff target did not persist");
    }
  }

  private static List<CohortRow> verifyFrozenCohort(
      DSLContext transaction, CanonicalGameplayIssuerAdmissionControlValue control) {
    // Terminal cleanup may advance live source revisions after intent. Resolve every target only
    // from the original retained JSON and its retained transition/reservation dependencies.
    List<CohortRow> rows =
        transaction
            .fetch(
                "WITH frozen AS (SELECT *, source_row_json::jsonb AS source FROM "
                    + COHORT
                    + " WHERE operation_id = ? AND issuer_id = ?)"
                    + " SELECT c.*, (scope.source ->> 'tenant_id')::uuid AS tenant_id,"
                    + " (scope.source ->> 'playable_state_namespace_id')::uuid"
                    + " AS playable_state_namespace_id FROM frozen c"
                    + " LEFT JOIN frozen transition ON transition.source_kind = 'TRANSITION'"
                    + " AND transition.source_key = c.source ->> 'transition_id'"
                    + " LEFT JOIN frozen reservation ON reservation.source_kind = 'RESERVATION'"
                    + " AND reservation.source_key = c.source ->> 'reservation_id'"
                    + " CROSS JOIN LATERAL (SELECT CASE"
                    + " WHEN c.source_kind IN ('BINDING', 'TRANSITION', 'RESERVATION',"
                    + " 'PENDING_INITIALIZER') THEN c.source"
                    + " WHEN c.source_kind IN ('ACCOUNT_OBLIGATION', 'ISSUER_OBLIGATION',"
                    + " 'REGION_OBLIGATION', 'LOCAL_OPERATION') THEN transition.source"
                    + " WHEN c.source_kind = 'BOOTSTRAP_MEMBER_OPERATION' THEN reservation.source"
                    + " END AS source) scope ORDER BY c.row_ordinal",
                control.currentOperationId(),
                ISSUER)
            .map(
                row -> {
                  String json = text(row, "source_row_json");
                  String storedDigest = text(row, "source_row_digest");
                  if (!CanonicalGameplayIssuerAdmissionControlValue.sha256(
                          json.getBytes(StandardCharsets.UTF_8))
                      .equals(storedDigest)) {
                    throw conflict(
                        "Issuer cutoff cohort source digest differs from its stored JSON");
                  }
                  return new CohortRow(
                      text(row, "source_kind"),
                      text(row, "source_key"),
                      integer(row, "source_inventory_revision"),
                      json,
                      uuid(row, "tenant_id"),
                      uuid(row, "playable_state_namespace_id"),
                      null,
                      storedDigest);
                });
    for (int index = 0; index < rows.size(); index++) {
      if (rows.get(index).sourceRevision().compareTo(control.inventorySnapshotRevision()) > 0) {
        throw conflict("Issuer cutoff cohort row is newer than its frozen inventory snapshot");
      }
      Record ordinal =
          transaction.fetchOne(
              "SELECT row_ordinal FROM "
                  + COHORT
                  + " WHERE operation_id = ? AND issuer_id = ? AND source_kind = ? AND source_key = ?",
              control.currentOperationId(),
              ISSUER,
              rows.get(index).sourceKind(),
              rows.get(index).sourceKey());
      if (ordinal == null || longInteger(ordinal, "row_ordinal") != index) {
        throw conflict("Issuer cutoff cohort ordinals are not contiguous and canonical");
      }
    }
    if (rows.size() != control.cohortRowCount()
        || !digestCohort(rows).equals(control.cohortDigest())) {
      throw conflict("Issuer cutoff cohort no longer matches its frozen complete digest");
    }
    return rows;
  }

  private static String requireCurrentTenantTagMapping(DSLContext transaction, UUID tenantId) {
    Record mapping =
        transaction.fetchOne(
            "SELECT allocation_version, tenant_gameplay_tag FROM "
                + TENANT_TAG
                + " WHERE tenant_id = ? FOR SHARE",
            tenantId);
    Integer version = mapping == null ? null : mapping.get("allocation_version", Integer.class);
    String tag = mapping == null ? null : mapping.get("tenant_gameplay_tag", String.class);
    if (version == null
        || version <= 0
        || tag == null
        || !tag.matches("g[1-9][0-9]{0,9}-[0-9a-f]{32}")
        || !tag.equals("g" + version + "-" + tenantId.toString().replace("-", ""))) {
      throw conflict("Issuer cutoff requires a positive exact SQL tenant gameplay-tag mapping");
    }
    return tag;
  }

  private static void verifyCutoffTargets(
      DSLContext transaction, CanonicalGameplayIssuerAdmissionControlValue control) {
    List<Record> rows =
        transaction.fetch(
            "SELECT * FROM "
                + CUTOFF_TARGET
                + " WHERE operation_id = ? AND issuer_id = ? ORDER BY target_ordinal",
            control.currentOperationId(),
            ISSUER);
    Map<TargetScope, List<CohortRow>> cohortTargets =
        groupTargets(verifyFrozenCohort(transaction, control));
    if (rows.size() != cohortTargets.size()) {
      throw conflict("Issuer cutoff target set differs from its complete frozen cohort");
    }
    for (int index = 0; index < rows.size(); index++) {
      Record row = rows.get(index);
      UUID tenantId = uuid(row, "tenant_id");
      UUID namespaceId = uuid(row, "playable_state_namespace_id");
      String tenantTag = text(row, "tenant_gameplay_tag");
      TargetScope scope = new TargetScope(tenantId, namespaceId);
      List<CohortRow> sources = cohortTargets.get(scope);
      if (!tenantTag.equals(requireCurrentTenantTagMapping(transaction, tenantId))) {
        throw conflict("Issuer cutoff tenant tag differs from the current immutable SQL mapping");
      }
      if (sources == null
          || longInteger(row, "target_ordinal") != index
          || !CanonicalGameplayIssuerAdmissionControlValue.sha256(
                  bytes(row, "closed_payload_bytes"))
              .equals(text(row, "closed_payload_digest"))
          || !digestTargetSource(sources).equals(text(row, "target_source_digest"))) {
        throw conflict("Issuer cutoff target differs from its immutable captured source");
      }
      CanonicalGameplayIssuerAdmissionBarrierValue closed =
          CanonicalGameplayIssuerAdmissionBarrierValue.decode(bytes(row, "closed_payload_bytes"));
      if (closed.state() != CanonicalGameplayIssuerAdmissionBarrierValue.State.CLOSED
          || !closed.issuerId().equals(ISSUER)
          || !closed.tenantId().equals(tenantId)
          || !closed.playableStateNamespaceId().equals(namespaceId)
          || !closed.tenantGameplayTag().equals(tenantTag)
          || !closed.ownerOperationId().equals(control.currentOperationId())
          || !closed.admissionEpoch().equals(control.liveBarrierEpoch())) {
        throw conflict("Issuer cutoff target payload does not bind its exact live cutoff");
      }
      byte[] capturedOpen = nullableBytes(row, "captured_open_payload_bytes");
      if (capturedOpen != null) {
        CanonicalGameplayIssuerAdmissionBarrierValue open =
            CanonicalGameplayIssuerAdmissionBarrierValue.decode(capturedOpen);
        if (open.state() != CanonicalGameplayIssuerAdmissionBarrierValue.State.OPEN
            || closed.initializer() == null
            || !Arrays.equals(open.initializer().exactBytes(), closed.initializer().exactBytes())
            || !CanonicalGameplayIssuerAdmissionControlValue.sha256(capturedOpen)
                .equals(text(row, "captured_open_payload_digest"))) {
          throw conflict("Issuer cutoff target lost its exact captured original OPEN value");
        }
      } else if (closed.initializer() != null
          || nullableBytes(row, "captured_open_payload_digest") != null
          || row.get("captured_initializer_operation_id", UUID.class) != null
          || row.get("captured_initializer_epoch", java.math.BigDecimal.class) != null
          || row.get("captured_initializer_digest", String.class) != null) {
        throw conflict("Issuer cutoff target has partial initializer evidence");
      }
      byte[] readback = nullableBytes(row, "exact_closed_readback_bytes");
      if ("CLOSED".equals(text(row, "target_state"))) {
        if (readback == null
            || !CanonicalGameplayIssuerAdmissionControlValue.sha256(readback)
                .equals(text(row, "exact_closed_readback_digest"))
            || !Arrays.equals(readback, closed.exactBytes())) {
          throw conflict("Issuer CLOSED receipt differs from its exact persistent target payload");
        }
      } else if (!"REQUIRED".equals(text(row, "target_state")) || readback != null) {
        throw conflict("Issuer cutoff target has an unknown or partial readback state");
      }
    }
  }

  private static void requireCutoffOperationCurrent(
      DSLContext transaction, CanonicalGameplayIssuerAdmissionControlValue control) {
    Record operation =
        transaction.fetchOne(
            "SELECT * FROM " + OPERATION + " WHERE operation_id = ? FOR UPDATE",
            control.currentOperationId());
    if (operation == null
        || !ISSUER.equals(text(operation, "issuer_id"))
        || !"CUTOFF".equals(text(operation, "operation_kind"))
        || !text(operation, "operation_state").equals(control.state().name())
        || !control.liveBarrierEpoch().equals(integer(operation, "live_barrier_epoch"))
        || !control.bootstrapOperationId().equals(uuid(operation, "bootstrap_operation_id"))
        || !control.bootstrapRequestId().equals(uuid(operation, "bootstrap_request_id"))
        || !control.bootstrapControlEpoch().equals(integer(operation, "bootstrap_control_epoch"))
        || !Arrays.equals(control.anchorExactBytes(), bytes(operation, "anchor_exact_bytes"))
        || !control.anchorExactDigest().equals(text(operation, "anchor_exact_digest"))
        || !control
            .accountIssuerAuthGeneration()
            .equals(integer(operation, "account_issuer_auth_generation"))
        || !Arrays.equals(
            control.accountCheckpointBytes(), bytes(operation, "account_checkpoint_bytes"))
        || !control.accountCheckpointDigest().equals(text(operation, "account_checkpoint_digest"))
        || !Arrays.equals(
            control.accountSourceEventBytes(),
            nullableBytes(operation, "account_source_event_bytes"))
        || !Objects.equals(
            control.accountSourceEventDigest(),
            nullableText(operation, "account_source_event_digest"))
        || !Arrays.equals(
            control.stableIncarnationBytes(), bytes(operation, "stable_incarnation_bytes"))
        || !control.stableIncarnationDigest().equals(text(operation, "stable_incarnation_digest"))
        || !control
            .inventorySnapshotRevision()
            .equals(integer(operation, "inventory_snapshot_revision"))
        || control.cohortRowCount() != longInteger(operation, "cohort_row_count")
        || !control.cohortDigest().equals(text(operation, "cohort_digest"))) {
      throw conflict("Live issuer cutoff has no exact current durable operation");
    }
  }

  private static void updateControl(
      DSLContext transaction,
      CanonicalGameplayIssuerAdmissionControlValue value,
      BigInteger expectedEpoch) {
    int updated =
        transaction.execute(
            "UPDATE "
                + CONTROL
                + " SET state = ?, live_barrier_epoch = ?, current_operation_id = ?,"
                + " current_operation_kind = ?, bootstrap_operation_id = ?, bootstrap_request_id = ?,"
                + " bootstrap_control_epoch = ?, anchor_exact_bytes = ?, anchor_exact_digest = ?,"
                + " account_issuer_auth_generation = ?, account_checkpoint_bytes = ?,"
                + " account_checkpoint_digest = ?, account_source_event_bytes = ?,"
                + " account_source_event_digest = ?, stable_incarnation_bytes = ?,"
                + " stable_incarnation_digest = ?, inventory_snapshot_revision = ?,"
                + " cohort_row_count = ?, cohort_digest = ?, control_exact_bytes = ?,"
                + " control_exact_digest = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE issuer_id = ? AND live_barrier_epoch = ?",
            value.state().name(),
            value.liveBarrierEpoch(),
            value.currentOperationId(),
            value.currentOperationKind().name(),
            value.bootstrapOperationId(),
            value.bootstrapRequestId(),
            value.bootstrapControlEpoch(),
            value.anchorExactBytes(),
            value.anchorExactDigest(),
            value.accountIssuerAuthGeneration(),
            value.accountCheckpointBytes(),
            value.accountCheckpointDigest(),
            value.accountSourceEventBytes(),
            value.accountSourceEventDigest(),
            value.stableIncarnationBytes(),
            value.stableIncarnationDigest(),
            value.inventorySnapshotRevision(),
            value.cohortRowCount(),
            value.cohortDigest(),
            value.exactBytes(),
            value.exactDigest(),
            value.issuerId(),
            expectedEpoch);
    if (updated != 1) throw conflict("Live issuer control changed before its exact update");
  }

  private static String digestCohort(List<CohortRow> rows) {
    MessageDigest digest = sha256Digest();
    rows.stream()
        .sorted(Comparator.comparing(CohortRow::sourceKind).thenComparing(CohortRow::sourceKey))
        .forEach(
            row -> {
              updateFramed(digest, row.sourceKind().getBytes(StandardCharsets.UTF_8));
              updateFramed(digest, row.sourceKey().getBytes(StandardCharsets.UTF_8));
              updateFramed(
                  digest, row.sourceRevision().toString().getBytes(StandardCharsets.US_ASCII));
              updateFramed(digest, row.sourceDigest().getBytes(StandardCharsets.US_ASCII));
            });
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }

  private static String digestTargetSource(List<CohortRow> rows) {
    MessageDigest digest = sha256Digest();
    rows.stream()
        .sorted(Comparator.comparing(CohortRow::sourceKind).thenComparing(CohortRow::sourceKey))
        .forEach(
            row -> {
              updateFramed(digest, row.sourceKind().getBytes(StandardCharsets.UTF_8));
              updateFramed(digest, row.sourceKey().getBytes(StandardCharsets.UTF_8));
              updateFramed(digest, row.sourceDigest().getBytes(StandardCharsets.US_ASCII));
            });
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void updateFramed(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }

  private static void requireNonNil(UUID value, String name) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private record CohortRow(
      String sourceKind,
      String sourceKey,
      BigInteger sourceRevision,
      String sourceJson,
      UUID tenantId,
      UUID namespaceId,
      UUID transitionId,
      String sourceDigest) {}

  private record TargetScope(UUID tenantId, UUID namespaceId) implements Comparable<TargetScope> {
    @Override
    public int compareTo(TargetScope other) {
      int tenant = tenantId.compareTo(other.tenantId);
      return tenant != 0 ? tenant : namespaceId.compareTo(other.namespaceId);
    }
  }

  /**
   * Requires a positive current OPEN control after the caller has locked inventory, then layout
   * control. Missing state, source movement, and every cutoff state deny preparation.
   */
  static CanonicalGameplayIssuerAdmissionControlValue requireOpenInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(currentState, "currentState");
    lockLayoutControl(transaction, anchor);
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + CONTROL + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (row == null)
      throw conflict("Ordinary issuer work requires a positive live admission-control row");
    CanonicalGameplayIssuerAdmissionControlValue value = loadControl(row);
    requireExactAnchorAndCurrentSource(value, anchor, currentState);
    if (value.state() != CanonicalGameplayIssuerAdmissionControlValue.State.OPEN
        || value.currentOperationKind()
            != CanonicalGameplayIssuerAdmissionControlValue.OperationKind.INITIAL_ANCHOR_INSTALL) {
      throw conflict("Live issuer admission control is cutting off or installed closed");
    }
    requireCounterMatchesCurrent(transaction, value.liveBarrierEpoch());
    requireInitialOperationCurrent(transaction, value);
    return value;
  }

  private CanonicalGameplayIssuerAdmissionControlValue installInitialInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
    lockLayoutControl(transaction, anchor);
    Record existing =
        transaction.fetchOne(
            "SELECT * FROM " + CONTROL + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (existing != null) {
      CanonicalGameplayIssuerAdmissionControlValue prior = loadControl(existing);
      requireExactAnchorAndCurrentSource(prior, anchor, currentState);
      if (prior.state() == CanonicalGameplayIssuerAdmissionControlValue.State.OPEN
          && prior.currentOperationKind()
              == CanonicalGameplayIssuerAdmissionControlValue.OperationKind.INITIAL_ANCHOR_INSTALL
          && prior.currentOperationId().equals(anchor.bootstrapOperationId())) {
        requireCounterMatchesCurrent(transaction, prior.liveBarrierEpoch());
        requireInitialOperationCurrent(transaction, prior);
        return prior;
      }
      throw conflict("Existing issuer admission control cannot be reset or reopened");
    }

    Record counter =
        transaction.fetchOne(
            "SELECT * FROM " + COUNTER + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (counter == null) throw conflict("The positive live issuer epoch counter is missing");
    BigInteger last = integer(counter, "last_live_barrier_epoch");
    if (last.signum() != 0) {
      throw conflict("Missing live issuer control cannot be reconstructed from its epoch counter");
    }
    // Existing post-anchor operations have no V1046 initializer provenance and cannot be
    // reclassified as safe OPEN work during this install.
    Record oldLocal =
        transaction.fetchOne(
            "SELECT EXISTS (SELECT 1 FROM " + LOCAL_OPERATION + " WHERE issuer_id = ?) AS has_rows",
            ISSUER);
    if (oldLocal == null || Boolean.TRUE.equals(oldLocal.get("has_rows", Boolean.class))) {
      throw conflict("Initial live control cannot adopt an unregistered local issuer operation");
    }

    Record allocated =
        transaction.fetchOne(
            "UPDATE "
                + COUNTER
                + " SET last_live_barrier_epoch = last_live_barrier_epoch + 1,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE issuer_id = ? AND last_live_barrier_epoch = 0"
                + " RETURNING last_live_barrier_epoch",
            ISSUER);
    if (allocated == null)
      throw conflict("Live issuer epoch allocation lost its serialized counter");
    BigInteger liveEpoch = integer(allocated, "last_live_barrier_epoch");
    CanonicalGameplayIssuerAdmissionControlValue requested =
        CanonicalGameplayIssuerAdmissionControlValue.initialOpen(
            anchor,
            currentState,
            liveEpoch,
            0L,
            CanonicalGameplayIssuerAdmissionControlValue.sha256(
                "issuer-admission-initial-cohort/v1"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    insertInitialOperation(transaction, requested);
    insertControl(transaction, requested);
    Record saved =
        transaction.fetchOne(
            "SELECT * FROM " + CONTROL + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (saved == null) throw conflict("Initial live issuer control did not persist");
    CanonicalGameplayIssuerAdmissionControlValue result = loadControl(saved);
    if (!Arrays.equals(result.exactBytes(), requested.exactBytes())) {
      throw conflict("Persisted initial live issuer control differs from its exact owner value");
    }
    return result;
  }

  private static void lockLayoutControl(
      DSLContext transaction, CanonicalGameplayIssuerInstalledAnchor anchor) {
    Record control =
        transaction.fetchOne(
            "SELECT * FROM " + LAYOUT_CONTROL + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (control == null
        || !ISSUER.equals(text(control, "issuer_id"))
        || !"INITIAL_BOOTSTRAP_INTENT".equals(text(control, "state"))
        || !anchor.bootstrapOperationId().equals(uuid(control, "current_operation_id"))
        || !anchor.controlEpoch().equals(integer(control, "control_epoch"))
        || !anchor.operationFence().equals(integer(control, "current_operation_fence"))
        || !anchor.coverageFence().equals(integer(control, "current_coverage_fence"))) {
      throw conflict(
          "Live admission control requires its exact immutable V1030 bootstrap provenance");
    }
    Record anchorRow =
        transaction.fetchOne(
            "SELECT exact_anchor_bytes, exact_anchor_digest FROM "
                + ANCHOR
                + " WHERE issuer_id = ? FOR UPDATE",
            ISSUER);
    if (anchorRow == null
        || !Arrays.equals(bytes(anchorRow, "exact_anchor_bytes"), anchor.exactAnchorBytes())
        || !anchor.exactAnchorDigest().equals(text(anchorRow, "exact_anchor_digest"))) {
      throw conflict("Live admission control requires the exact installed anchor row");
    }
  }

  private static void insertInitialOperation(
      DSLContext transaction, CanonicalGameplayIssuerAdmissionControlValue value) {
    int inserted =
        transaction.execute(
            "INSERT INTO "
                + OPERATION
                + " (operation_id, request_id, issuer_id, operation_kind, operation_state,"
                + " live_barrier_epoch, prior_live_barrier_epoch, bootstrap_operation_id,"
                + " bootstrap_request_id, bootstrap_control_epoch, anchor_exact_bytes,"
                + " anchor_exact_digest, account_issuer_auth_generation, account_checkpoint_bytes,"
                + " account_checkpoint_digest, account_source_event_bytes, account_source_event_digest,"
                + " stable_incarnation_bytes, stable_incarnation_digest, inventory_snapshot_revision,"
                + " cohort_row_count, cohort_digest, installed_at)"
                + " VALUES (?, ?, ?, 'INITIAL_ANCHOR_INSTALL', 'OPEN', ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
            value.currentOperationId(),
            value.bootstrapRequestId(),
            value.issuerId(),
            value.liveBarrierEpoch(),
            value.bootstrapOperationId(),
            value.bootstrapRequestId(),
            value.bootstrapControlEpoch(),
            value.anchorExactBytes(),
            value.anchorExactDigest(),
            value.accountIssuerAuthGeneration(),
            value.accountCheckpointBytes(),
            value.accountCheckpointDigest(),
            value.accountSourceEventBytes(),
            value.accountSourceEventDigest(),
            value.stableIncarnationBytes(),
            value.stableIncarnationDigest(),
            value.inventorySnapshotRevision(),
            value.cohortRowCount(),
            value.cohortDigest());
    if (inserted != 1) throw conflict("Initial live issuer control operation did not persist");
  }

  private static void insertControl(
      DSLContext transaction, CanonicalGameplayIssuerAdmissionControlValue value) {
    int inserted =
        transaction.execute(
            "INSERT INTO "
                + CONTROL
                + " (issuer_id, state, live_barrier_epoch, current_operation_id, current_operation_kind,"
                + " bootstrap_operation_id, bootstrap_request_id, bootstrap_control_epoch,"
                + " anchor_exact_bytes, anchor_exact_digest, account_issuer_auth_generation,"
                + " account_checkpoint_bytes, account_checkpoint_digest, account_source_event_bytes,"
                + " account_source_event_digest, stable_incarnation_bytes, stable_incarnation_digest,"
                + " inventory_snapshot_revision, cohort_row_count, cohort_digest, control_exact_bytes,"
                + " control_exact_digest) VALUES (?, 'OPEN', ?, ?, 'INITIAL_ANCHOR_INSTALL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            value.issuerId(),
            value.liveBarrierEpoch(),
            value.currentOperationId(),
            value.bootstrapOperationId(),
            value.bootstrapRequestId(),
            value.bootstrapControlEpoch(),
            value.anchorExactBytes(),
            value.anchorExactDigest(),
            value.accountIssuerAuthGeneration(),
            value.accountCheckpointBytes(),
            value.accountCheckpointDigest(),
            value.accountSourceEventBytes(),
            value.accountSourceEventDigest(),
            value.stableIncarnationBytes(),
            value.stableIncarnationDigest(),
            value.inventorySnapshotRevision(),
            value.cohortRowCount(),
            value.cohortDigest(),
            value.exactBytes(),
            value.exactDigest());
    if (inserted != 1) throw conflict("Initial live issuer admission control did not persist");
  }

  private static CanonicalGameplayIssuerAdmissionControlValue loadControl(Record row) {
    byte[] exact = bytes(row, "control_exact_bytes");
    String digest = text(row, "control_exact_digest");
    if (!CanonicalGameplayIssuerAdmissionControlValue.sha256(exact).equals(digest)) {
      throw conflict("Persisted issuer admission control digest differs from its exact bytes");
    }
    CanonicalGameplayIssuerAdmissionControlValue value;
    try {
      value = CanonicalGameplayIssuerAdmissionControlValue.decode(exact);
    } catch (RuntimeException malformed) {
      throw conflict("Persisted issuer admission-control value is malformed");
    }
    if (!value.issuerId().equals(text(row, "issuer_id"))
        || !value.state().name().equals(text(row, "state"))
        || !value.liveBarrierEpoch().equals(integer(row, "live_barrier_epoch"))
        || !value.currentOperationId().equals(uuid(row, "current_operation_id"))
        || !value.currentOperationKind().name().equals(text(row, "current_operation_kind"))
        || !value.bootstrapOperationId().equals(uuid(row, "bootstrap_operation_id"))
        || !value.bootstrapRequestId().equals(uuid(row, "bootstrap_request_id"))
        || !value.bootstrapControlEpoch().equals(integer(row, "bootstrap_control_epoch"))
        || !Arrays.equals(value.anchorExactBytes(), bytes(row, "anchor_exact_bytes"))
        || !value.anchorExactDigest().equals(text(row, "anchor_exact_digest"))
        || !value
            .accountIssuerAuthGeneration()
            .equals(integer(row, "account_issuer_auth_generation"))
        || !Arrays.equals(value.accountCheckpointBytes(), bytes(row, "account_checkpoint_bytes"))
        || !value.accountCheckpointDigest().equals(text(row, "account_checkpoint_digest"))
        || !Arrays.equals(
            value.accountSourceEventBytes(), nullableBytes(row, "account_source_event_bytes"))
        || !Objects.equals(
            value.accountSourceEventDigest(), nullableText(row, "account_source_event_digest"))
        || !Arrays.equals(value.stableIncarnationBytes(), bytes(row, "stable_incarnation_bytes"))
        || !value.stableIncarnationDigest().equals(text(row, "stable_incarnation_digest"))
        || !value.inventorySnapshotRevision().equals(integer(row, "inventory_snapshot_revision"))
        || value.cohortRowCount() != longInteger(row, "cohort_row_count")
        || !value.cohortDigest().equals(text(row, "cohort_digest"))) {
      throw conflict("Issuer admission-control columns differ from their exact typed value");
    }
    return value;
  }

  private static void requireExactAnchorAndCurrentSource(
      CanonicalGameplayIssuerAdmissionControlValue value,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    if (!value.bootstrapOperationId().equals(anchor.bootstrapOperationId())
        || !value.bootstrapRequestId().equals(anchor.bootstrapRequestId())
        || !value.bootstrapControlEpoch().equals(anchor.controlEpoch())
        || !Arrays.equals(value.anchorExactBytes(), anchor.exactAnchorBytes())
        || !value.anchorExactDigest().equals(anchor.exactAnchorDigest())
        || !value.matchesCurrentIssuerState(currentState)) {
      throw conflict("Live issuer control no longer matches its exact anchor and current sources");
    }
  }

  private static void requireCounterMatchesCurrent(DSLContext transaction, BigInteger liveEpoch) {
    Record counter =
        transaction.fetchOne(
            "SELECT * FROM " + COUNTER + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (counter == null || !integer(counter, "last_live_barrier_epoch").equals(liveEpoch)) {
      throw conflict("Live issuer admission epoch differs from its positive owner counter");
    }
  }

  private static void requireInitialOperationCurrent(
      DSLContext transaction, CanonicalGameplayIssuerAdmissionControlValue value) {
    Record operation =
        transaction.fetchOne(
            "SELECT * FROM " + OPERATION + " WHERE operation_id = ? FOR UPDATE",
            value.currentOperationId());
    if (operation == null
        || !ISSUER.equals(text(operation, "issuer_id"))
        || !"INITIAL_ANCHOR_INSTALL".equals(text(operation, "operation_kind"))
        || !"OPEN".equals(text(operation, "operation_state"))
        || !value.liveBarrierEpoch().equals(integer(operation, "live_barrier_epoch"))
        || !value.bootstrapRequestId().equals(uuid(operation, "request_id"))
        || !value.bootstrapOperationId().equals(uuid(operation, "bootstrap_operation_id"))
        || !value.bootstrapControlEpoch().equals(integer(operation, "bootstrap_control_epoch"))
        || !Arrays.equals(value.anchorExactBytes(), bytes(operation, "anchor_exact_bytes"))
        || !value.anchorExactDigest().equals(text(operation, "anchor_exact_digest"))
        || !value
            .accountIssuerAuthGeneration()
            .equals(integer(operation, "account_issuer_auth_generation"))
        || !Arrays.equals(
            value.accountCheckpointBytes(), bytes(operation, "account_checkpoint_bytes"))
        || !value.accountCheckpointDigest().equals(text(operation, "account_checkpoint_digest"))
        || !Arrays.equals(
            value.accountSourceEventBytes(), nullableBytes(operation, "account_source_event_bytes"))
        || !Objects.equals(
            value.accountSourceEventDigest(),
            nullableText(operation, "account_source_event_digest"))
        || !Arrays.equals(
            value.stableIncarnationBytes(), bytes(operation, "stable_incarnation_bytes"))
        || !value.stableIncarnationDigest().equals(text(operation, "stable_incarnation_digest"))
        || !value
            .inventorySnapshotRevision()
            .equals(integer(operation, "inventory_snapshot_revision"))
        || value.cohortRowCount() != longInteger(operation, "cohort_row_count")
        || !value.cohortDigest().equals(text(operation, "cohort_digest"))) {
      throw conflict("Live issuer control has no exact persisted initial-anchor operation");
    }
  }

  private static BigInteger integer(Record row, String name) {
    java.math.BigDecimal value = row.get(name, java.math.BigDecimal.class);
    if (value == null) throw conflict("Persisted issuer admission field is missing: " + name);
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Persisted issuer admission integer is malformed: " + name);
    }
  }

  private static long longInteger(Record row, String name) {
    Long value = row.get(name, Long.class);
    if (value == null || value < 0) {
      throw conflict("Persisted issuer admission count is missing or negative: " + name);
    }
    return value;
  }

  private static java.util.UUID uuid(Record row, String name) {
    java.util.UUID value = row.get(name, java.util.UUID.class);
    if (value == null) throw conflict("Persisted issuer admission UUID is missing: " + name);
    return value;
  }

  private static String text(Record row, String name) {
    String value = row.get(name, String.class);
    if (value == null) throw conflict("Persisted issuer admission text is missing: " + name);
    return value;
  }

  private static byte[] bytes(Record row, String name) {
    byte[] value = row.get(name, byte[].class);
    if (value == null) throw conflict("Persisted issuer admission bytes are missing: " + name);
    return value;
  }

  private static byte[] nullableBytes(Record row, String name) {
    return row.get(name, byte[].class);
  }

  private static String nullableText(Record row, String name) {
    return row.get(name, String.class);
  }

  private void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw conflict("Issuer admission-control owner cannot join an ambient transaction");
    }
    try {
      dsl.connectionResult(
          connection -> {
            if (!connection.getAutoCommit()) {
              throw conflict("Issuer admission-control owner cannot join a jOOQ transaction");
            }
            return Boolean.TRUE;
          });
    } catch (RuntimeException failure) {
      if (failure instanceof CanonicalGameplayBindingInventoryConflictException) throw failure;
      throw new CanonicalGameplayBindingInventoryConflictException(
          "Issuer admission-control SQL connection state is unavailable", failure);
    }
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict(String message) {
    return new CanonicalGameplayBindingInventoryConflictException(message);
  }
}
