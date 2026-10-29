package net.firedevops.firemud.gamesession.repository;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationDisposition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered Game Session SQL owner for durable account-barrier acquisition intent.
 *
 * <p>This owner closes canonical NEW preparation and freezes its SQL cohort. It does not install
 * tenant-local barriers, publish an installed-fence receipt, mint an admission token, or release a
 * fence.
 */
final class CanonicalGameplayAccountAdmissionBarrierRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String CONTROL = "game_session_canonical_account_coverage_control";
  private static final String INVENTORY_CLOCK = "game_session_canonical_binding_inventory_clock";
  private static final String LEGACY_DISPOSITION =
      "game_session_canonical_binding_legacy_disposition";
  private static final String INTENT = "game_session_canonical_account_admission_fence_intent";
  private static final String COHORT =
      "game_session_canonical_account_admission_fence_intent_cohort_row";
  private static final String BARRIER_TARGET =
      "game_session_canonical_account_admission_fence_intent_barrier_target";
  private static final String TENANT_TAG_MAPPING = "game_session_canonical_tenant_gameplay_tag";
  private static final String CLOSED_RECEIPT =
      "game_session_canonical_account_closed_barrier_receipt";
  private static final String CLOSED_BARRIER_SCHEMA = "gs_account_admission_barrier/v1";
  private static final String CLOSED_BARRIER_STATE = "CLOSED";
  private static final Set<String> CLOSED_BARRIER_FIELDS =
      Set.of(
          "schema",
          "accountId",
          "tenantId",
          "playableStateNamespaceId",
          "tenantGameplayTag",
          "ownerOperationId",
          "ownerFenceEpoch",
          "incarnationDigest",
          "state");
  private static final JsonMapper CLOSED_BARRIER_JSON = JsonMapper.builder().build();

  private final DSLContext dsl;
  private final Clock clock;

  CanonicalGameplayAccountAdmissionBarrierRepository(DSLContext dsl) {
    this(dsl, Clock.systemUTC());
  }

  CanonicalGameplayAccountAdmissionBarrierRepository(DSLContext dsl, Clock clock) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Creates or returns the exact persisted acquisition intent for this account and request.
   * Inventory revision is locked before account control, matching the canonical prepare owner.
   */
  CanonicalGameplayAccountAdmissionFenceIntent beginFenceIntent(UUID accountId, UUID requestId) {
    requireNonNil(accountId, "accountId");
    requireNonNil(requestId, "requestId");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger inventoryRevision = lockInventoryRevision(transaction);
          Record control = lockExistingControl(transaction, accountId);
          String controlState = control.get("state", String.class);

          Record priorIntent =
              transaction.fetchOne(
                  "SELECT * FROM " + INTENT + " WHERE account_id = ? AND request_id = ? FOR UPDATE",
                  accountId,
                  requestId);
          if ("ACQUISITION_INTENT".equals(controlState)) {
            if (priorIntent == null) {
              throw conflict("A different account-fence intent already owns this account");
            }
            return loadAndRequireCurrent(transaction, control, priorIntent);
          }
          if (priorIntent != null) {
            throw conflict("The requested account-fence intent is no longer the current owner");
          }
          requireNoActiveControl(control, accountId);
          CanonicalGameplayLegacyMigrationDisposition legacyDisposition =
              readVerifiedLegacyDisposition(transaction, inventoryRevision, true);
          requireNoFutureAccountRows(transaction, accountId, inventoryRevision);

          BigInteger previousFence = readBigInteger(control, "last_operation_fence");
          BigInteger operationFence = previousFence.add(BigInteger.ONE);
          UUID operationId = randomNonNilUuid();
          while (operationId.equals(accountId) || operationId.equals(requestId)) {
            operationId = randomNonNilUuid();
          }
          UUID plannedAccountAdmissionFence = randomNonNilUuid();
          while (plannedAccountAdmissionFence.equals(operationId)
              || plannedAccountAdmissionFence.equals(requestId)
              || plannedAccountAdmissionFence.equals(accountId)) {
            plannedAccountAdmissionFence = randomNonNilUuid();
          }

          List<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow> cohortRows =
              captureCohortRows(transaction, accountId);
          requireResolvableCohortScopes(cohortRows);
          List<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget> barrierTargets =
              captureBarrierTargets(transaction, accountId);
          requireBarrierTargetsCoverCohort(cohortRows, barrierTargets);
          String cohortDigest = digestCohortRows(cohortRows);
          String barrierTargetDigest = digestBarrierTargets(barrierTargets);
          insertIntent(
              transaction,
              operationId,
              requestId,
              accountId,
              operationFence,
              plannedAccountAdmissionFence,
              inventoryRevision,
              legacyDisposition,
              cohortRows,
              barrierTargets,
              cohortDigest,
              barrierTargetDigest);
          closeNewPreparation(
              transaction,
              accountId,
              previousFence,
              operationFence,
              operationId,
              plannedAccountAdmissionFence,
              inventoryRevision);
          Record savedIntent =
              Objects.requireNonNull(
                  transaction.fetchOne(
                      "SELECT * FROM " + INTENT + " WHERE operation_id = ?", operationId),
                  "persisted account-fence intent must exist");
          Record savedControl = lockExistingControl(transaction, accountId);
          return loadAndRequireCurrent(transaction, savedControl, savedIntent);
        });
  }

  /** Reads and verifies the immutable persisted tuple, cohort rows, and target rows. */
  CanonicalGameplayAccountAdmissionFenceIntent readFenceIntent(UUID accountId, UUID requestId) {
    requireNonNil(accountId, "accountId");
    requireNonNil(requestId, "requestId");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          Record control = readControl(transaction, accountId);
          Record intent =
              transaction.fetchOne(
                  "SELECT * FROM " + INTENT + " WHERE account_id = ? AND request_id = ?",
                  accountId,
                  requestId);
          if (control == null || intent == null) {
            throw conflict("The exact account-fence intent or its positive control row is missing");
          }
          return loadAndRequireCurrent(transaction, control, intent);
        });
  }

  /** Reloads the current durable intent and account control using only the operation identity. */
  CanonicalGameplayAccountAdmissionFenceIntent readCurrentFenceIntent(UUID operationId) {
    requireNonNil(operationId, "operationId");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          Record intent =
              transaction.fetchOne(
                  "SELECT * FROM " + INTENT + " WHERE operation_id = ?", operationId);
          if (intent == null) {
            throw conflict("The exact account-fence intent operation is missing");
          }
          UUID accountId =
              Objects.requireNonNull(intent.get("account_id", UUID.class), "intent account id");
          Record control = readControl(transaction, accountId);
          if (control == null) {
            throw conflict("The account-fence intent lost its positive account-control row");
          }
          return loadAndRequireCurrent(transaction, control, intent);
        });
  }

  /**
   * Persists one as-of CLOSED physical readback. It never advances the account intent or turns a
   * stored receipt into a current-incarnation capability.
   */
  StoredClosedBarrierReceipt acceptClosedBarrierReadback(
      UUID expectedOperationId,
      long expectedTargetOrdinal,
      CanonicalGameplayAccountClosedBarrierRedisOwner.Readback readback) {
    requireNonNil(expectedOperationId, "expectedOperationId");
    Objects.requireNonNull(readback, "readback");
    requireNoAmbientTransaction();
    if (expectedTargetOrdinal < 0
        || !expectedOperationId.equals(readback.operationId())
        || readback.targetOrdinal() != expectedTargetOrdinal
        || !expectedOperationId.equals(readback.intent().operationId())) {
      throw conflict("CLOSED readback differs from the explicitly expected operation and target");
    }

    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);

          // The prepare owner takes this same inventory-clock then account-control lock order.
          // The operation lookup below is only a locator; current SQL ownership is reloaded.
          lockInventoryRevision(transaction);
          Record identity =
              transaction.fetchOne(
                  "SELECT account_id FROM " + INTENT + " WHERE operation_id = ?",
                  expectedOperationId);
          if (identity == null) {
            throw conflict("The exact CLOSED receipt acquisition intent is missing");
          }
          UUID accountId =
              Objects.requireNonNull(identity.get("account_id", UUID.class), "intent account id");
          Record control = lockExistingControl(transaction, accountId);
          Record intentRow =
              transaction.fetchOne(
                  "SELECT * FROM " + INTENT + " WHERE operation_id = ? FOR UPDATE",
                  expectedOperationId);
          if (intentRow == null) {
            throw conflict("The exact CLOSED receipt acquisition intent is missing");
          }
          CanonicalGameplayAccountAdmissionFenceIntent currentIntent =
              loadAndRequireCurrent(transaction, control, intentRow);
          requireSameIntentSnapshot(readback.intent(), currentIntent);
          if (!expectedOperationId.equals(currentIntent.operationId())
              || !accountId.equals(currentIntent.accountId())
              || !accountId.equals(readback.accountId())) {
            throw conflict("CLOSED readback differs from the exact current intent owner");
          }

          CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget target =
              requireUniqueBarrierTarget(currentIntent, expectedTargetOrdinal);
          Record persistedTarget =
              transaction.fetchOne(
                  "SELECT account_id, tenant_id, playable_state_namespace_id, target_state FROM "
                      + BARRIER_TARGET
                      + " WHERE operation_id = ? AND target_ordinal = ? FOR SHARE",
                  expectedOperationId,
                  expectedTargetOrdinal);
          if (persistedTarget == null
              || !"REQUIRED".equals(persistedTarget.get("target_state", String.class))
              || !accountId.equals(persistedTarget.get("account_id", UUID.class))
              || !target.tenantId().equals(persistedTarget.get("tenant_id", UUID.class))
              || !target
                  .playableStateNamespaceId()
                  .equals(persistedTarget.get("playable_state_namespace_id", UUID.class))) {
            throw conflict("CLOSED receipt target is not the exact current REQUIRED SQL target");
          }

          CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
              readback.assignment();
          requireReadbackAssignment(
              assignment, currentIntent, target, expectedOperationId, expectedTargetOrdinal);
          Record persistedTag =
              transaction.fetchOne(
                  "SELECT tenant_id, allocation_version, tenant_gameplay_tag FROM "
                      + TENANT_TAG_MAPPING
                      + " WHERE tenant_id = ? FOR SHARE",
                  target.tenantId());
          if (persistedTag == null
              || !target.tenantId().equals(persistedTag.get("tenant_id", UUID.class))
              || !Integer.valueOf(assignment.allocationVersion())
                  .equals(persistedTag.get("allocation_version", Integer.class))
              || !assignment
                  .tenantGameplayTag()
                  .equals(persistedTag.get("tenant_gameplay_tag", String.class))) {
            throw conflict("Persisted tenant mapping differs from the CLOSED readback assignment");
          }

          String redisKey = CanonicalGameplaySessionRedisKeys.accountAdmissionBarrier(assignment);
          if (!redisKey.equals(readback.key())) {
            throw conflict("Canonical CLOSED barrier key differs from the physical readback");
          }

          CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before =
              readback.beforeIncarnation();
          CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after =
              readback.afterIncarnation();
          requireVerifiedIncarnation(before, currentIntent, target, assignment);
          requireVerifiedIncarnation(after, currentIntent, target, assignment);
          if (!before.incarnationDigest().equals(after.incarnationDigest())
              || !before.acceptedAttestationDigest().equals(after.acceptedAttestationDigest())
              || after.issuedAtEpochMillis() < before.issuedAtEpochMillis()
              || after.observedAtEpochMillis() < before.observedAtEpochMillis()) {
            throw conflict("Protected observations changed incarnation or regressed");
          }

          Map<String, String> expectedFields =
              expectedClosedBarrierFields(
                  currentIntent, target, assignment, after.incarnationDigest());
          if (!CLOSED_BARRIER_FIELDS.equals(readback.barrierFields().keySet())
              || !expectedFields.equals(readback.barrierFields())
              || !CLOSED_BARRIER_SCHEMA.equals(readback.barrierFields().get("schema"))
              || !CLOSED_BARRIER_STATE.equals(readback.barrierFields().get("state"))) {
            throw conflict("Physical CLOSED barrier fields differ from the current owner tuple");
          }
          requireSuccessfulClosedMutation(readback.mutationOutcome());
          String fieldsDigest = digestClosedBarrierFields(expectedFields);

          // Clock is trusted owner configuration and is sampled only after all SQL guards.
          long now = clock.millis();
          if (now <= 0) {
            throw conflict("Trusted CLOSED receipt acceptance time must be positive");
          }
          requireCurrentObservationWindow(before, now);
          requireCurrentObservationWindow(after, now);

          Record existing =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + CLOSED_RECEIPT
                      + " WHERE operation_id = ? AND target_ordinal = ? FOR UPDATE",
                  expectedOperationId,
                  expectedTargetOrdinal);
          if (existing != null) {
            StoredClosedBarrierReceipt original = toStoredClosedBarrierReceipt(existing);
            requireSameStoredBarrierIdentity(
                original,
                currentIntent,
                target,
                assignment,
                redisKey,
                fieldsDigest,
                before.acceptedAttestationDigest(),
                before.incarnationDigest());
            // A fresh protected observation has new source bytes/times. Exact retry returns the
            // original immutable SQL row, including its original creation time and source digests.
            return original;
          }

          transaction.execute(
              "INSERT INTO "
                  + CLOSED_RECEIPT
                  + " (operation_id, request_id, account_id, operation_fence,"
                  + " planned_account_admission_fence, inventory_snapshot_revision, cohort_digest,"
                  + " barrier_target_digest, target_ordinal, tenant_id,"
                  + " playable_state_namespace_id, tenant_gameplay_tag, workload_namespace,"
                  + " allocation_version, barrier_key, barrier_schema, incarnation_digest,"
                  + " barrier_state, accepted_attestation_digest, barrier_fields_digest,"
                  + " before_source_payload_digest, before_observation_issued_at_epoch_millis,"
                  + " before_observation_observed_at_epoch_millis,"
                  + " before_observation_expires_at_epoch_millis, after_source_payload_digest,"
                  + " after_observation_issued_at_epoch_millis,"
                  + " after_observation_observed_at_epoch_millis,"
                  + " after_observation_expires_at_epoch_millis)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                  + " ?, ?, ?, ?, ?, ?)",
              currentIntent.operationId(),
              currentIntent.requestId(),
              currentIntent.accountId(),
              decimal(currentIntent.operationFence()),
              currentIntent.plannedAccountAdmissionFence(),
              decimal(currentIntent.inventorySnapshotRevision()),
              currentIntent.cohortDigest(),
              currentIntent.barrierTargetDigest(),
              target.ordinal(),
              target.tenantId(),
              target.playableStateNamespaceId(),
              assignment.tenantGameplayTag(),
              assignment.workloadNamespace(),
              assignment.allocationVersion(),
              redisKey,
              CLOSED_BARRIER_SCHEMA,
              after.incarnationDigest(),
              CLOSED_BARRIER_STATE,
              before.acceptedAttestationDigest(),
              fieldsDigest,
              before.payloadDigest(),
              before.issuedAtEpochMillis(),
              before.observedAtEpochMillis(),
              before.expiresAtEpochMillis(),
              after.payloadDigest(),
              after.issuedAtEpochMillis(),
              after.observedAtEpochMillis(),
              after.expiresAtEpochMillis());

          Record saved =
              Objects.requireNonNull(
                  transaction.fetchOne(
                      "SELECT * FROM "
                          + CLOSED_RECEIPT
                          + " WHERE operation_id = ? AND target_ordinal = ? FOR UPDATE",
                      expectedOperationId,
                      expectedTargetOrdinal),
                  "inserted CLOSED receipt must exist");
          return toStoredClosedBarrierReceipt(saved);
        });
  }

  private static void insertIntent(
      DSLContext transaction,
      UUID operationId,
      UUID requestId,
      UUID accountId,
      BigInteger operationFence,
      UUID plannedAccountAdmissionFence,
      BigInteger inventoryRevision,
      CanonicalGameplayLegacyMigrationDisposition legacy,
      List<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow> cohortRows,
      List<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget> barrierTargets,
      String cohortDigest,
      String barrierTargetDigest) {
    transaction.execute(
        "INSERT INTO "
            + INTENT
            + " (operation_id, request_id, account_id, operation_fence,"
            + " planned_account_admission_fence, inventory_snapshot_revision,"
            + " legacy_cohort_id, legacy_owner_operation_id, legacy_writer_fence,"
            + " legacy_source_snapshot_revision, legacy_canonical_inventory_revision,"
            + " legacy_namespace_index_readback_revision, legacy_evidence_digest, intent_state,"
            + " cohort_row_count, cohort_digest, barrier_target_count, barrier_target_digest)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACQUISITION_INTENT', ?, ?, ?, ?)",
        operationId,
        requestId,
        accountId,
        decimal(operationFence),
        plannedAccountAdmissionFence,
        decimal(inventoryRevision),
        legacy.cohortId(),
        legacy.ownerOperationId(),
        legacy.legacyWriterFence(),
        decimal(legacy.sourceSnapshotRevision()),
        decimal(legacy.canonicalInventoryRevision()),
        decimal(legacy.namespaceIndexReadbackRevision()),
        legacy.evidenceDigest(),
        cohortRows.size(),
        cohortDigest,
        barrierTargets.size(),
        barrierTargetDigest);

    for (var target : barrierTargets) {
      transaction.execute(
          "INSERT INTO "
              + BARRIER_TARGET
              + " (operation_id, account_id, target_ordinal, tenant_id,"
              + " playable_state_namespace_id, target_state)"
              + " VALUES (?, ?, ?, ?, ?, 'REQUIRED')",
          operationId,
          accountId,
          target.ordinal(),
          target.tenantId(),
          target.playableStateNamespaceId());
    }
    for (var row : cohortRows) {
      transaction.execute(
          "INSERT INTO "
              + COHORT
              + " (operation_id, account_id, row_ordinal, source_kind, source_key, tenant_id,"
              + " playable_state_namespace_id, source_row)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
          operationId,
          accountId,
          row.ordinal(),
          row.kind().name(),
          row.sourceKey(),
          row.tenantId(),
          row.playableStateNamespaceId(),
          row.sourceRecordJson());
    }
  }

  private static void closeNewPreparation(
      DSLContext transaction,
      UUID accountId,
      BigInteger previousFence,
      BigInteger operationFence,
      UUID operationId,
      UUID plannedAccountAdmissionFence,
      BigInteger inventoryRevision) {
    int updated =
        transaction.execute(
            "UPDATE "
                + CONTROL
                + " SET state = 'ACQUISITION_INTENT', last_operation_fence = ? ,"
                + " current_operation_id = NULL, current_fence_intent_id = ?,"
                + " current_operation_fence = ?, account_admission_fence = ?,"
                + " historical_account_admission_fence = NULL, coverage_fence = NULL,"
                + " inventory_snapshot_revision = ?, coverage_generation = NULL,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE account_id = ? AND state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'"
                + " AND last_operation_fence = ? AND current_fence_intent_id IS NULL",
            decimal(operationFence),
            operationId,
            decimal(operationFence),
            plannedAccountAdmissionFence,
            decimal(inventoryRevision),
            accountId,
            decimal(previousFence));
    requireOne(
        updated, "Account control changed before acquisition intent could close NEW preparation");
  }

  private static List<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow> captureCohortRows(
      DSLContext transaction, UUID accountId) {
    var rows = new ArrayList<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow>();
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.BINDING,
        "SELECT encode(i.binding_ref, 'hex') AS source_key, i.tenant_id,"
            + " i.playable_state_namespace_id, to_jsonb(i)::text AS source_record_json"
            + " FROM game_session_canonical_gameplay_binding_inventory i"
            + " WHERE i.account_id = ? ORDER BY encode(i.binding_ref, 'hex')",
        accountId);
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.TRANSITION,
        "SELECT t.transition_id::text AS source_key,"
            + " CASE WHEN t.tenant_id = i.tenant_id"
            + " AND t.playable_state_namespace_id = i.playable_state_namespace_id"
            + " THEN t.tenant_id ELSE NULL::uuid END AS tenant_id,"
            + " CASE WHEN t.tenant_id = i.tenant_id"
            + " AND t.playable_state_namespace_id = i.playable_state_namespace_id"
            + " THEN t.playable_state_namespace_id ELSE NULL::uuid END"
            + " AS playable_state_namespace_id,"
            + " to_jsonb(t)::text AS source_record_json"
            + " FROM game_session_canonical_binding_transition t"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = t.candidate_binding_ref"
            + " WHERE i.account_id = ? ORDER BY t.transition_id",
        accountId);
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.RESERVATION,
        "SELECT r.reservation_id::text AS source_key, r.tenant_id,"
            + " r.playable_state_namespace_id, to_jsonb(r)::text AS source_record_json"
            + " FROM game_session_canonical_issuer_partition_reservation r"
            + " WHERE r.account_id = ? ORDER BY r.reservation_id",
        accountId);
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.ACCOUNT_OBLIGATION,
        "SELECT o.transition_id::text || ':' || o.obligation_ordinal::text AS source_key,"
            + " o.tenant_id, CASE WHEN i.account_id = o.account_id AND i.tenant_id = o.tenant_id"
            + " THEN i.playable_state_namespace_id ELSE NULL::uuid END"
            + " AS playable_state_namespace_id, to_jsonb(o)::text AS source_record_json"
            + " FROM game_session_canonical_binding_account_index_obligation o"
            + " LEFT JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = o.binding_ref"
            + " WHERE o.account_id = ? ORDER BY o.transition_id, o.obligation_ordinal",
        accountId);
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.ISSUER_OBLIGATION,
        "SELECT o.transition_id::text || ':' || encode(o.binding_ref, 'hex') AS source_key,"
            + " CASE WHEN o.binding_ref = t.candidate_binding_ref"
            + " THEN i.tenant_id ELSE NULL::uuid END AS tenant_id,"
            + " CASE WHEN o.binding_ref = t.candidate_binding_ref"
            + " THEN i.playable_state_namespace_id ELSE NULL::uuid END"
            + " AS playable_state_namespace_id, to_jsonb(o)::text AS source_record_json"
            + " FROM game_session_canonical_binding_issuer_index_obligation o"
            + " JOIN game_session_canonical_binding_transition t"
            + " ON t.transition_id = o.transition_id"
            + " JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = t.candidate_binding_ref"
            + " WHERE i.account_id = ? ORDER BY o.transition_id, encode(o.binding_ref, 'hex')",
        accountId);
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.REGION_OBLIGATION,
        "SELECT o.transition_id::text AS source_key, o.tenant_id,"
            + " CASE WHEN i.account_id = o.account_id AND i.tenant_id = o.tenant_id"
            + " THEN i.playable_state_namespace_id ELSE NULL::uuid END"
            + " AS playable_state_namespace_id, to_jsonb(o)::text AS source_record_json"
            + " FROM game_session_canonical_binding_region_bridge_obligation o"
            + " LEFT JOIN game_session_canonical_binding_transition t"
            + " ON t.transition_id = o.transition_id"
            + " LEFT JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = t.candidate_binding_ref"
            + " WHERE o.account_id = ? ORDER BY o.transition_id",
        accountId);
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.MEMBER_REPAIR_CONTROL,
        "SELECT c.account_id::text AS source_key, NULL::uuid AS tenant_id,"
            + " NULL::uuid AS playable_state_namespace_id, to_jsonb(c)::text AS source_record_json"
            + " FROM game_session_canonical_account_member_repair_control c"
            + " WHERE c.account_id = ?",
        accountId);
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.MEMBER_REPAIR_OPERATION,
        "SELECT o.operation_id::text AS source_key, o.tenant_id,"
            + " CASE WHEN i.account_id = o.account_id AND i.tenant_id = o.tenant_id"
            + " THEN i.playable_state_namespace_id ELSE NULL::uuid END"
            + " AS playable_state_namespace_id,"
            + " to_jsonb(o)::text AS source_record_json"
            + " FROM game_session_canonical_account_member_repair_operation o"
            + " LEFT JOIN game_session_canonical_binding_transition t"
            + " ON t.transition_id = o.transition_id"
            + " LEFT JOIN game_session_canonical_gameplay_binding_inventory i"
            + " ON i.binding_ref = t.candidate_binding_ref"
            + " WHERE o.account_id = ? ORDER BY o.operation_id",
        accountId);
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.INITIALIZER,
        "SELECT i.original_request_id::text AS source_key, t.tenant_id,"
            + " t.playable_state_namespace_id, to_jsonb(i)::text AS source_record_json"
            + " FROM game_session_canonical_initial_account_barrier_initializer i"
            + " JOIN game_session_canonical_initial_account_barrier_target t"
            + " ON t.initializer_id = i.initializer_id"
            + " AND t.original_request_id = i.original_request_id"
            + " WHERE i.account_id = ? ORDER BY i.original_request_id",
        accountId);
    appendRows(
        rows,
        transaction,
        CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.INITIAL_TARGET,
        "SELECT t.initializer_id::text || ':' || t.target_ordinal::text AS source_key,"
            + " t.tenant_id, t.playable_state_namespace_id, to_jsonb(t)::text AS source_record_json"
            + " FROM game_session_canonical_initial_account_barrier_target t"
            + " WHERE t.account_id = ? ORDER BY t.initializer_id, t.target_ordinal",
        accountId);
    return List.copyOf(rows);
  }

  private static void appendRows(
      List<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow> rows,
      DSLContext transaction,
      CanonicalGameplayAccountAdmissionFenceIntent.CohortKind kind,
      String query,
      UUID accountId) {
    for (Record row : transaction.fetch(query, accountId)) {
      rows.add(
          CanonicalGameplayAccountAdmissionFenceIntent.cohortRow(
              rows.size(),
              kind,
              Objects.requireNonNull(row.get("source_key", String.class), "source key"),
              row.get("tenant_id", UUID.class),
              row.get("playable_state_namespace_id", UUID.class),
              Objects.requireNonNull(
                  row.get("source_record_json", String.class), "source row JSON")));
    }
  }

  private static List<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget>
      captureBarrierTargets(DSLContext transaction, UUID accountId) {
    List<Record> rows =
        transaction.fetch(
            "SELECT DISTINCT tenant_id, playable_state_namespace_id FROM ("
                + " SELECT i.tenant_id, i.playable_state_namespace_id"
                + " FROM game_session_canonical_gameplay_binding_inventory i"
                + " WHERE i.account_id = ?"
                + " UNION ALL SELECT r.tenant_id, r.playable_state_namespace_id"
                + " FROM game_session_canonical_issuer_partition_reservation r"
                + " WHERE r.account_id = ?"
                + " UNION ALL SELECT t.tenant_id, t.playable_state_namespace_id"
                + " FROM game_session_canonical_binding_transition t"
                + " JOIN game_session_canonical_gameplay_binding_inventory i"
                + " ON i.binding_ref = t.candidate_binding_ref"
                + " WHERE i.account_id = ?"
                + " UNION ALL SELECT o.tenant_id, CASE WHEN i.account_id = o.account_id"
                + " AND i.tenant_id = o.tenant_id THEN i.playable_state_namespace_id"
                + " ELSE NULL::uuid END"
                + " FROM game_session_canonical_binding_account_index_obligation o"
                + " LEFT JOIN game_session_canonical_gameplay_binding_inventory i"
                + " ON i.binding_ref = o.binding_ref"
                + " WHERE o.account_id = ?"
                + " UNION ALL SELECT i.tenant_id, i.playable_state_namespace_id"
                + " FROM game_session_canonical_binding_issuer_index_obligation o"
                + " JOIN game_session_canonical_binding_transition t"
                + " ON t.transition_id = o.transition_id"
                + " JOIN game_session_canonical_gameplay_binding_inventory i"
                + " ON i.binding_ref = t.candidate_binding_ref"
                + " WHERE i.account_id = ?"
                + " UNION ALL SELECT o.tenant_id, i.playable_state_namespace_id"
                + " FROM game_session_canonical_binding_region_bridge_obligation o"
                + " LEFT JOIN game_session_canonical_binding_transition t"
                + " ON t.transition_id = o.transition_id"
                + " LEFT JOIN game_session_canonical_gameplay_binding_inventory i"
                + " ON i.binding_ref = t.candidate_binding_ref"
                + " WHERE o.account_id = ?"
                + " UNION ALL SELECT o.tenant_id, i.playable_state_namespace_id"
                + " FROM game_session_canonical_account_member_repair_operation o"
                + " LEFT JOIN game_session_canonical_binding_transition t"
                + " ON t.transition_id = o.transition_id"
                + " LEFT JOIN game_session_canonical_gameplay_binding_inventory i"
                + " ON i.binding_ref = t.candidate_binding_ref"
                + " WHERE o.account_id = ?"
                + " UNION ALL SELECT t.tenant_id, t.playable_state_namespace_id"
                + " FROM game_session_canonical_initial_account_barrier_target t"
                + " WHERE t.account_id = ?"
                + ") account_scopes"
                + " ORDER BY tenant_id, playable_state_namespace_id",
            accountId,
            accountId,
            accountId,
            accountId,
            accountId,
            accountId,
            accountId,
            accountId);
    var targets = new ArrayList<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget>();
    for (Record row : rows) {
      targets.add(
          CanonicalGameplayAccountAdmissionFenceIntent.barrierTarget(
              targets.size(),
              Objects.requireNonNull(row.get("tenant_id", UUID.class), "barrier target tenant"),
              Objects.requireNonNull(
                  row.get("playable_state_namespace_id", UUID.class), "barrier target namespace")));
    }
    return List.copyOf(targets);
  }

  private static void requireResolvableCohortScopes(
      List<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow> cohortRows) {
    for (var row : cohortRows) {
      if ((row.tenantId() == null) != (row.playableStateNamespaceId() == null)
          || (row.tenantId() == null
              && row.kind()
                  != CanonicalGameplayAccountAdmissionFenceIntent.CohortKind
                      .MEMBER_REPAIR_CONTROL)) {
        throw conflict(
            "An account cohort source lacks an exact tenant/namespace barrier scope; retain the"
                + " source and leave the account unfenced");
      }
    }
  }

  private static void requireBarrierTargetsCoverCohort(
      List<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow> cohortRows,
      List<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget> barrierTargets) {
    Set<String> scopes = new HashSet<>();
    for (var target : barrierTargets) {
      scopes.add(target.tenantId() + "/" + target.playableStateNamespaceId());
    }
    for (var row : cohortRows) {
      if (row.tenantId() != null
          && !scopes.contains(row.tenantId() + "/" + row.playableStateNamespaceId())) {
        throw conflict(
            "A frozen account source has no matching SQL-derived barrier target; retain the"
                + " source and leave the account unfenced");
      }
    }
  }

  private static CanonicalGameplayAccountAdmissionFenceIntent loadAndRequireCurrent(
      DSLContext transaction, Record control, Record intent) {
    UUID operationId =
        Objects.requireNonNull(intent.get("operation_id", UUID.class), "operation id");
    UUID accountId = Objects.requireNonNull(intent.get("account_id", UUID.class), "account id");
    if (!"ACQUISITION_INTENT".equals(intent.get("intent_state", String.class))
        || !"ACQUISITION_INTENT".equals(control.get("state", String.class))
        || !accountId.equals(control.get("account_id", UUID.class))
        || !operationId.equals(control.get("current_fence_intent_id", UUID.class))
        || control.get("current_operation_id", UUID.class) != null
        || !readBigInteger(control, "current_operation_fence")
            .equals(readBigInteger(intent, "operation_fence"))
        || !readBigInteger(control, "last_operation_fence")
            .equals(readBigInteger(intent, "operation_fence"))
        || !Objects.equals(
            control.get("account_admission_fence", UUID.class),
            intent.get("planned_account_admission_fence", UUID.class))
        || control.get("historical_account_admission_fence", UUID.class) != null
        || control.get("coverage_fence", UUID.class) != null
        || control.get("coverage_generation") != null
        || !readBigInteger(control, "inventory_snapshot_revision")
            .equals(readBigInteger(intent, "inventory_snapshot_revision"))) {
      throw conflict("Persisted acquisition intent is not the exact current account-control owner");
    }
    CanonicalGameplayLegacyMigrationDisposition legacy = readPersistedLegacyDisposition(intent);
    CanonicalGameplayLegacyMigrationDisposition currentLegacy =
        readVerifiedLegacyDisposition(
            transaction, readBigInteger(intent, "inventory_snapshot_revision"), false);
    if (!legacy.equals(currentLegacy)) {
      throw conflict("The verified legacy migration disposition changed after intent capture");
    }
    List<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow> cohortRows =
        readCohortRows(transaction, operationId);
    List<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget> barrierTargets =
        readBarrierTargets(transaction, operationId);
    String cohortDigest = digestCohortRows(cohortRows);
    String targetDigest = digestBarrierTargets(barrierTargets);
    if (cohortRows.size() != readLong(intent, "cohort_row_count")
        || !cohortDigest.equals(intent.get("cohort_digest", String.class))
        || barrierTargets.size() != readLong(intent, "barrier_target_count")
        || !targetDigest.equals(intent.get("barrier_target_digest", String.class))) {
      throw conflict("Immutable account-fence intent cohort or barrier target changed on readback");
    }
    return new CanonicalGameplayAccountAdmissionFenceIntent(
        operationId,
        intent.get("request_id", UUID.class),
        accountId,
        readBigInteger(intent, "operation_fence"),
        intent.get("planned_account_admission_fence", UUID.class),
        readBigInteger(intent, "inventory_snapshot_revision"),
        legacy,
        CanonicalGameplayAccountAdmissionFenceIntent.State.ACQUISITION_INTENT,
        cohortRows,
        barrierTargets,
        cohortDigest,
        targetDigest);
  }

  private static List<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow> readCohortRows(
      DSLContext transaction, UUID operationId) {
    return transaction
        .fetch(
            "SELECT row_ordinal, source_kind, source_key, tenant_id,"
                + " playable_state_namespace_id, source_row::text AS source_record_json FROM "
                + COHORT
                + " WHERE operation_id = ? ORDER BY row_ordinal",
            operationId)
        .map(
            row -> {
              CanonicalGameplayAccountAdmissionFenceIntent.CohortKind kind;
              try {
                kind =
                    CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.valueOf(
                        row.get("source_kind", String.class));
              } catch (RuntimeException malformed) {
                throw conflict("Persisted account-fence cohort contains an unknown source kind");
              }
              return CanonicalGameplayAccountAdmissionFenceIntent.cohortRow(
                  readLong(row, "row_ordinal"),
                  kind,
                  row.get("source_key", String.class),
                  row.get("tenant_id", UUID.class),
                  row.get("playable_state_namespace_id", UUID.class),
                  row.get("source_record_json", String.class));
            });
  }

  private static List<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget>
      readBarrierTargets(DSLContext transaction, UUID operationId) {
    return transaction
        .fetch(
            "SELECT target_ordinal, tenant_id, playable_state_namespace_id FROM "
                + BARRIER_TARGET
                + " WHERE operation_id = ? ORDER BY target_ordinal",
            operationId)
        .map(
            row ->
                CanonicalGameplayAccountAdmissionFenceIntent.barrierTarget(
                    readLong(row, "target_ordinal"),
                    row.get("tenant_id", UUID.class),
                    row.get("playable_state_namespace_id", UUID.class)));
  }

  private static CanonicalGameplayLegacyMigrationDisposition readVerifiedLegacyDisposition(
      DSLContext transaction, BigInteger inventoryRevision, boolean forShare) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM "
                + LEGACY_DISPOSITION
                + " WHERE singleton_id = 1"
                + (forShare ? " FOR SHARE" : ""));
    if (row == null || !"VERIFIED".equals(row.get("disposition_state", String.class))) {
      throw conflict(
          "Account-fence intent requires the exact VERIFIED legacy-index migration disposition");
    }
    CanonicalGameplayLegacyMigrationDisposition disposition;
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
      throw conflict("Verified legacy migration disposition is incomplete or invalid");
    }
    if (disposition.canonicalInventoryRevision().compareTo(inventoryRevision) > 0) {
      throw conflict("Verified legacy disposition is newer than the account snapshot revision");
    }
    return disposition;
  }

  private static CanonicalGameplayLegacyMigrationDisposition readPersistedLegacyDisposition(
      Record row) {
    try {
      return new CanonicalGameplayLegacyMigrationDisposition(
          row.get("legacy_cohort_id", UUID.class),
          row.get("legacy_owner_operation_id", UUID.class),
          row.get("legacy_writer_fence", UUID.class),
          readBigInteger(row, "legacy_source_snapshot_revision"),
          readBigInteger(row, "legacy_canonical_inventory_revision"),
          readBigInteger(row, "legacy_namespace_index_readback_revision"),
          row.get("legacy_evidence_digest", String.class));
    } catch (RuntimeException malformed) {
      throw conflict("Persisted account-fence intent has malformed legacy evidence");
    }
  }

  private static void requireNoFutureAccountRows(
      DSLContext transaction, UUID accountId, BigInteger revision) {
    Record future =
        transaction.fetchOne(
            "SELECT EXISTS (SELECT 1 FROM game_session_canonical_gameplay_binding_inventory"
                + " WHERE account_id = ? AND inventory_revision > ?)"
                + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_transition t"
                + " JOIN game_session_canonical_gameplay_binding_inventory i"
                + " ON i.binding_ref = t.candidate_binding_ref"
                + " WHERE i.account_id = ? AND t.inventory_revision > ?)"
                + " OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_partition_reservation"
                + " WHERE account_id = ? AND inventory_revision > ?)"
                + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_account_index_obligation"
                + " WHERE account_id = ? AND inventory_revision > ?)"
                + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_issuer_index_obligation o"
                + " JOIN game_session_canonical_binding_transition t"
                + " ON t.transition_id = o.transition_id"
                + " JOIN game_session_canonical_gameplay_binding_inventory i"
                + " ON i.binding_ref = t.candidate_binding_ref"
                + " WHERE i.account_id = ? AND o.inventory_revision > ?)"
                + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_region_bridge_obligation"
                + " WHERE account_id = ? AND inventory_revision > ?)"
                + " OR EXISTS (SELECT 1 FROM game_session_canonical_account_member_repair_operation"
                + " WHERE account_id = ? AND (original_inventory_revision > ?"
                + " OR completed_inventory_revision > ?)) AS has_future_revision",
            accountId,
            decimal(revision),
            accountId,
            decimal(revision),
            accountId,
            decimal(revision),
            accountId,
            decimal(revision),
            accountId,
            decimal(revision),
            accountId,
            decimal(revision),
            accountId,
            decimal(revision),
            decimal(revision));
    if (future == null || Boolean.TRUE.equals(future.get("has_future_revision", Boolean.class))) {
      throw conflict(
          "Account rows beyond the locked inventory clock cannot be omitted from intent");
    }
  }

  private static Record lockExistingControl(DSLContext transaction, UUID accountId) {
    Record control =
        transaction.fetchOne(
            "SELECT * FROM " + CONTROL + " WHERE account_id = ? FOR UPDATE", accountId);
    if (control == null) {
      throw conflict("Positive account-control row is missing; absence cannot start fence intent");
    }
    return control;
  }

  private static Record readControl(DSLContext transaction, UUID accountId) {
    return transaction.fetchOne("SELECT * FROM " + CONTROL + " WHERE account_id = ?", accountId);
  }

  private static void requireNoActiveControl(Record control, UUID accountId) {
    if (!"NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(control.get("state", String.class))
        || !accountId.equals(control.get("account_id", UUID.class))
        || control.get("current_operation_id", UUID.class) != null
        || control.get("current_fence_intent_id", UUID.class) != null
        || control.get("current_operation_fence") != null
        || control.get("account_admission_fence", UUID.class) != null
        || control.get("historical_account_admission_fence", UUID.class) != null
        || control.get("coverage_fence", UUID.class) != null
        || control.get("inventory_snapshot_revision") != null
        || control.get("coverage_generation") != null) {
      throw conflict("Only positively read NO_ACTIVE account control may begin fence intent");
    }
  }

  private static BigInteger lockInventoryRevision(DSLContext transaction) {
    Record row =
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT inventory_revision FROM "
                    + INVENTORY_CLOCK
                    + " WHERE singleton_id = 1 FOR UPDATE"),
            "inventory revision clock row must exist");
    BigInteger revision = readBigInteger(row, "inventory_revision");
    if (revision.signum() <= 0) {
      throw conflict("Verified account intent requires a positive inventory snapshot revision");
    }
    return revision;
  }

  private static BigInteger readBigInteger(Record row, String name) {
    BigDecimal value = row.get(name, BigDecimal.class);
    if (value == null) {
      throw conflict("Persisted account-fence intent field is missing: " + name);
    }
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Persisted account-fence intent field is not an integer: " + name);
    }
  }

  private static long readLong(Record row, String name) {
    BigDecimal value = row.get(name, BigDecimal.class);
    if (value != null) {
      try {
        return value.longValueExact();
      } catch (ArithmeticException malformed) {
        throw conflict("Persisted account-fence intent field is out of range: " + name);
      }
    }
    Long longValue = row.get(name, Long.class);
    if (longValue == null) {
      throw conflict("Persisted account-fence intent field is missing: " + name);
    }
    return longValue;
  }

  private static String digestCohortRows(
      List<CanonicalGameplayAccountAdmissionFenceIntent.CohortRow> rows) {
    MessageDigest digest = sha256Digest();
    for (var row : rows) {
      updateDigest(digest, Long.toString(row.ordinal()));
      updateDigest(digest, row.kind().name());
      updateDigest(digest, row.sourceKey());
      updateDigest(digest, row.tenantId() == null ? null : row.tenantId().toString());
      updateDigest(
          digest,
          row.playableStateNamespaceId() == null
              ? null
              : row.playableStateNamespaceId().toString());
      updateDigest(digest, row.sourceRecordJson());
    }
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }

  private static String digestBarrierTargets(
      List<CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget> targets) {
    MessageDigest digest = sha256Digest();
    for (var target : targets) {
      updateDigest(digest, Long.toString(target.ordinal()));
      updateDigest(digest, target.tenantId().toString());
      updateDigest(digest, target.playableStateNamespaceId().toString());
    }
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }

  private static void updateDigest(MessageDigest digest, String value) {
    if (value == null) {
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
      return;
    }
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
    digest.update(encoded);
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
  }

  private static UUID randomNonNilUuid() {
    UUID value = UUID.randomUUID();
    if (NIL_UUID.equals(value)) {
      throw new IllegalStateException("UUID generator returned nil account-fence identity");
    }
    return value;
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account-fence intent owner must not run inside an ambient transaction");
    }
  }

  private static void requireOne(int updated, String message) {
    if (updated != 1) {
      throw conflict(message);
    }
  }

  private static CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget
      requireUniqueBarrierTarget(
          CanonicalGameplayAccountAdmissionFenceIntent intent, long ordinal) {
    CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget match = null;
    for (var target : intent.barrierTargets()) {
      if (target.ordinal() == ordinal) {
        if (match != null) throw conflict("Intent target ordinal is duplicated");
        match = target;
      }
    }
    if (match == null) throw conflict("Exact frozen CLOSED barrier target is missing");
    return match;
  }

  private static void requireSameIntentSnapshot(
      CanonicalGameplayAccountAdmissionFenceIntent expected,
      CanonicalGameplayAccountAdmissionFenceIntent current) {
    if (!expected.operationId().equals(current.operationId())
        || !expected.requestId().equals(current.requestId())
        || !expected.accountId().equals(current.accountId())
        || !expected.operationFence().equals(current.operationFence())
        || !expected.plannedAccountAdmissionFence().equals(current.plannedAccountAdmissionFence())
        || !expected.inventorySnapshotRevision().equals(current.inventorySnapshotRevision())
        || !expected.legacyMigrationDisposition().equals(current.legacyMigrationDisposition())
        || expected.state() != current.state()
        || !expected.cohortDigest().equals(current.cohortDigest())
        || !expected.barrierTargetDigest().equals(current.barrierTargetDigest())
        || expected.cohortRows().size() != current.cohortRows().size()
        || expected.barrierTargets().size() != current.barrierTargets().size()) {
      throw conflict("Redis readback intent differs from the complete current SQL intent");
    }
    for (int index = 0; index < expected.cohortRows().size(); index++) {
      var left = expected.cohortRows().get(index);
      var right = current.cohortRows().get(index);
      if (left.ordinal() != right.ordinal()
          || left.kind() != right.kind()
          || !left.sourceKey().equals(right.sourceKey())
          || !Objects.equals(left.tenantId(), right.tenantId())
          || !Objects.equals(left.playableStateNamespaceId(), right.playableStateNamespaceId())
          || !left.sourceRecordJson().equals(right.sourceRecordJson())) {
        throw conflict("Redis readback intent differs from the immutable SQL cohort");
      }
    }
    for (int index = 0; index < expected.barrierTargets().size(); index++) {
      var left = expected.barrierTargets().get(index);
      var right = current.barrierTargets().get(index);
      if (left.ordinal() != right.ordinal()
          || !left.tenantId().equals(right.tenantId())
          || !left.playableStateNamespaceId().equals(right.playableStateNamespaceId())) {
        throw conflict("Redis readback intent differs from the immutable SQL target set");
      }
    }
  }

  private static void requireReadbackAssignment(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayAccountAdmissionFenceIntent intent,
      CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget target,
      UUID operationId,
      long ordinal) {
    if (assignment.isBindingTransition()
        || !operationId.equals(assignment.admissionFenceIntentOperationId())
        || !Long.valueOf(ordinal).equals(assignment.barrierTargetOrdinal())
        || !intent.accountId().equals(assignment.accountId())
        || !target.tenantId().equals(assignment.tenantId())
        || !target.playableStateNamespaceId().equals(assignment.playableStateNamespaceId())
        || assignment.allocationVersion() <= 0
        || assignment.workloadNamespace().isBlank()) {
      throw conflict("Redis assignment differs from the exact frozen SQL target");
    }
  }

  private static void requireVerifiedIncarnation(
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation value,
      CanonicalGameplayAccountAdmissionFenceIntent intent,
      CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget target,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    if (!intent.operationId().equals(value.operationId())
        || value.targetOrdinal() != target.ordinal()
        || !intent.accountId().equals(value.accountId())
        || !target.tenantId().equals(value.tenantId())
        || !target.playableStateNamespaceId().equals(value.playableStateNamespaceId())
        || !intent.plannedAccountAdmissionFence().equals(value.plannedAccountAdmissionFence())
        || !intent.operationFence().toString().equals(value.operationFence())
        || !intent.cohortDigest().equals(value.cohortDigest())
        || !intent.barrierTargetDigest().equals(value.barrierTargetDigest())
        || !assignment.tenantGameplayTag().equals(value.tenantGameplayTag())
        || !canonicalSha256(value.acceptedAttestationDigest())
        || !canonicalSha256(value.payloadDigest())
        || !canonicalSha256(value.incarnationDigest())
        || value.issuedAtEpochMillis() <= 0
        || value.observedAtEpochMillis() < value.issuedAtEpochMillis()
        || value.expiresAtEpochMillis() <= value.observedAtEpochMillis()) {
      throw conflict("Protected incarnation proof differs from the complete current target tuple");
    }
  }

  private static void requireCurrentObservationWindow(
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation value, long now) {
    if (now < value.issuedAtEpochMillis() || now < value.observedAtEpochMillis()) {
      throw conflict("Trusted acceptance clock precedes the protected observation");
    }
    if (now >= value.expiresAtEpochMillis()) {
      throw conflict("Protected incarnation observation is expired");
    }
  }

  private static Map<String, String> expectedClosedBarrierFields(
      CanonicalGameplayAccountAdmissionFenceIntent intent,
      CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget target,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      String incarnationDigest) {
    return Map.of(
        "schema", CLOSED_BARRIER_SCHEMA,
        "accountId", intent.accountId().toString(),
        "tenantId", target.tenantId().toString(),
        "playableStateNamespaceId", target.playableStateNamespaceId().toString(),
        "tenantGameplayTag", assignment.tenantGameplayTag(),
        "ownerOperationId", intent.operationId().toString(),
        "ownerFenceEpoch", intent.operationFence().toString(),
        "incarnationDigest", incarnationDigest,
        "state", CLOSED_BARRIER_STATE);
  }

  private static void requireSuccessfulClosedMutation(
      CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome outcome) {
    if (outcome != CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.CREATED_CLOSED
        && outcome
            != CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.EXACT_CLOSED_REPLAY
        && outcome
            != CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.ADVANCED_TO_CLOSED) {
      throw conflict("Redis owner did not report a successful CLOSED write and readback");
    }
  }

  private static String digestClosedBarrierFields(Map<String, String> fields) {
    try {
      byte[] canonical =
          Rfc8785CanonicalJson.canonicalizeUtf8(CLOSED_BARRIER_JSON.writeValueAsString(fields));
      return "sha256:" + HexFormat.of().formatHex(sha256Digest().digest(canonical));
    } catch (IOException invalid) {
      throw new IllegalStateException("Could not canonicalize CLOSED barrier fields", invalid);
    }
  }

  private static boolean canonicalSha256(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  private static StoredClosedBarrierReceipt toStoredClosedBarrierReceipt(Record row) {
    return new StoredClosedBarrierReceipt(
        row.get("operation_id", UUID.class),
        row.get("request_id", UUID.class),
        row.get("account_id", UUID.class),
        readBigInteger(row, "operation_fence"),
        row.get("planned_account_admission_fence", UUID.class),
        readBigInteger(row, "inventory_snapshot_revision"),
        row.get("cohort_digest", String.class),
        row.get("barrier_target_digest", String.class),
        readLong(row, "target_ordinal"),
        row.get("tenant_id", UUID.class),
        row.get("playable_state_namespace_id", UUID.class),
        row.get("tenant_gameplay_tag", String.class),
        row.get("workload_namespace", String.class),
        Objects.requireNonNull(
            row.get("allocation_version", Integer.class), "persisted allocation version"),
        row.get("barrier_key", String.class),
        row.get("barrier_schema", String.class),
        row.get("incarnation_digest", String.class),
        row.get("barrier_state", String.class),
        row.get("accepted_attestation_digest", String.class),
        row.get("barrier_fields_digest", String.class),
        row.get("before_source_payload_digest", String.class),
        readLong(row, "before_observation_issued_at_epoch_millis"),
        readLong(row, "before_observation_observed_at_epoch_millis"),
        readLong(row, "before_observation_expires_at_epoch_millis"),
        row.get("after_source_payload_digest", String.class),
        readLong(row, "after_observation_issued_at_epoch_millis"),
        readLong(row, "after_observation_observed_at_epoch_millis"),
        readLong(row, "after_observation_expires_at_epoch_millis"),
        row.get("created_at", OffsetDateTime.class));
  }

  private static void requireSameStoredBarrierIdentity(
      StoredClosedBarrierReceipt stored,
      CanonicalGameplayAccountAdmissionFenceIntent intent,
      CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget target,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      String barrierKey,
      String fieldsDigest,
      String acceptedAttestationDigest,
      String incarnationDigest) {
    if (!stored.operationId().equals(intent.operationId())
        || !stored.requestId().equals(intent.requestId())
        || !stored.accountId().equals(intent.accountId())
        || !stored.operationFence().equals(intent.operationFence())
        || !stored.plannedAccountAdmissionFence().equals(intent.plannedAccountAdmissionFence())
        || !stored.inventorySnapshotRevision().equals(intent.inventorySnapshotRevision())
        || !stored.cohortDigest().equals(intent.cohortDigest())
        || !stored.barrierTargetDigest().equals(intent.barrierTargetDigest())
        || stored.targetOrdinal() != target.ordinal()
        || !stored.tenantId().equals(target.tenantId())
        || !stored.playableStateNamespaceId().equals(target.playableStateNamespaceId())
        || !stored.tenantGameplayTag().equals(assignment.tenantGameplayTag())
        || !stored.workloadNamespace().equals(assignment.workloadNamespace())
        || stored.allocationVersion() != assignment.allocationVersion()
        || !stored.barrierKey().equals(barrierKey)
        || !stored.barrierSchema().equals(CLOSED_BARRIER_SCHEMA)
        || !stored.incarnationDigest().equals(incarnationDigest)
        || !stored.barrierState().equals(CLOSED_BARRIER_STATE)
        || !stored.acceptedAttestationDigest().equals(acceptedAttestationDigest)
        || !stored.barrierFieldsDigest().equals(fieldsDigest)) {
      throw conflict(
          "Existing CLOSED receipt belongs to a different immutable owner or incarnation");
    }
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict(String message) {
    return new CanonicalGameplayBindingInventoryConflictException(message);
  }
}
