package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerEvent;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationDisposition;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Game Session-owned preparation owner for the first physical issuer-partition build.
 *
 * <p>It consumes only the exact persisted pending V1030 bootstrap operation and its complete
 * cohort. It does not write Redis, publish an active pointer, install an anchor, issue coverage, or
 * authorize ordinary admission.
 */
final class CanonicalGameplayIssuerPartitionBuildRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String INVENTORY_CLOCK = "game_session_canonical_binding_inventory_clock";
  private static final String LEGACY_DISPOSITION =
      "game_session_canonical_binding_legacy_disposition";
  private static final String BOOTSTRAP_CONTROL =
      "game_session_canonical_issuer_layout_bootstrap_control";
  private static final String BOOTSTRAP_INTENT =
      "game_session_canonical_issuer_layout_bootstrap_intent";
  private static final String BOOTSTRAP_COHORT =
      "game_session_canonical_issuer_layout_bootstrap_cohort_row";
  private static final String BUILD_PLAN = "game_session_canonical_issuer_partition_build_plan";
  private static final String BUILD_COHORT =
      "game_session_canonical_issuer_partition_build_cohort_row";
  private static final String MEMBER_WORK =
      "game_session_canonical_issuer_partition_build_member_work";
  private static final String BUILD_BLOCKER =
      "game_session_canonical_issuer_partition_build_blocker";
  private static final String MAPPING_IDENTITY =
      CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String COHORT_SQL =
      "SELECT source_kind, source_key, source_revision, source_row_json FROM ("
          + "SELECT CASE WHEN i.issuer_id = ? THEN 'BINDING'"
          + " ELSE 'UNRESOLVED_LEGACY_BINDING' END AS source_kind,"
          + " encode(i.binding_ref, 'hex') AS source_key, i.inventory_revision AS source_revision,"
          + " to_jsonb(i)::text AS source_row_json FROM game_session_canonical_gameplay_binding_inventory i"
          + " WHERE i.inventory_revision <= ?"
          + " UNION ALL SELECT 'TRANSITION' AS source_kind, t.transition_id::text AS source_key,"
          + " t.inventory_revision AS source_revision,"
          // Preserve frozen pre-V1035 transition bytes for the absence-equivalent new NULL field.
          // A definitive abort UUID remains part of the full captured source.
          + " CASE WHEN to_jsonb(t)->'admission_orphan_cleanup_id' = 'null'::jsonb"
          + " THEN (to_jsonb(t) - 'admission_orphan_cleanup_id')::text"
          + " ELSE to_jsonb(t)::text END AS source_row_json"
          + " FROM game_session_canonical_binding_transition t WHERE t.inventory_revision <= ?"
          + " UNION ALL SELECT CASE WHEN r.issuer_id = ? THEN 'RESERVATION'"
          + " ELSE 'UNRESOLVED_LEGACY_RESERVATION' END AS source_kind,"
          + " r.reservation_id::text AS source_key, r.inventory_revision AS source_revision,"
          + " to_jsonb(r)::text AS source_row_json FROM game_session_canonical_issuer_partition_reservation r"
          + " WHERE r.inventory_revision <= ?"
          + " UNION ALL SELECT 'ACCOUNT_OBLIGATION' AS source_kind,"
          + " a.transition_id::text || ':' || a.obligation_ordinal::text AS source_key,"
          + " a.inventory_revision AS source_revision, to_jsonb(a)::text AS source_row_json"
          + " FROM game_session_canonical_binding_account_index_obligation a"
          + " WHERE a.inventory_revision <= ?"
          + " UNION ALL SELECT 'ISSUER_OBLIGATION' AS source_kind,"
          + " encode(o.binding_ref, 'hex') || ':' || o.transition_id::text AS source_key,"
          + " o.inventory_revision AS source_revision,"
          // Preserve pre-V1036 frozen bytes only for the absence-equivalent new NULL linkage.
          // An actual release-operation identity remains significant captured source evidence.
          + " CASE WHEN to_jsonb(o)->'local_release_operation_id' = 'null'::jsonb"
          + " THEN (to_jsonb(o) - 'local_release_operation_id')::text"
          + " ELSE to_jsonb(o)::text END AS source_row_json"
          + " FROM game_session_canonical_binding_issuer_index_obligation o"
          + " WHERE o.inventory_revision <= ?"
          + " UNION ALL SELECT 'REGION_OBLIGATION' AS source_kind,"
          + " g.transition_id::text AS source_key, g.inventory_revision AS source_revision,"
          + " to_jsonb(g)::text AS source_row_json"
          + " FROM game_session_canonical_binding_region_bridge_obligation g"
          + " WHERE g.inventory_revision <= ?"
          + ") captured_cohort ORDER BY source_kind COLLATE \"C\", source_key COLLATE \"C\"";

  private static final String FUTURE_REVISION_SQL =
      "SELECT EXISTS (SELECT 1 FROM game_session_canonical_gameplay_binding_inventory"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_transition"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_issuer_partition_reservation"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_account_index_obligation"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_issuer_index_obligation"
          + " WHERE inventory_revision > ?)"
          + " OR EXISTS (SELECT 1 FROM game_session_canonical_binding_region_bridge_obligation"
          + " WHERE inventory_revision > ?) AS has_future_revision";

  private final DSLContext dsl;

  CanonicalGameplayIssuerPartitionBuildRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Prepares or resumes the immutable plan for one exact pending bootstrap operation/request pair.
   * No caller-provided layout or member list is accepted.
   */
  CanonicalGameplayIssuerPartitionBuildPlan prepareInitialBuildPlan(
      UUID bootstrapOperationId, UUID bootstrapRequestId) {
    requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    if (bootstrapOperationId.equals(bootstrapRequestId)) {
      throw conflict("Bootstrap operation and request identities must be distinct");
    }
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration ->
            prepareInTransaction(
                DSL.using(configuration), bootstrapOperationId, bootstrapRequestId));
  }

  private CanonicalGameplayIssuerPartitionBuildPlan prepareInTransaction(
      DSLContext transaction, UUID bootstrapOperationId, UUID bootstrapRequestId) {
    // Keep the same serialization order as V1030 and ordinary inventory preparation.
    BigInteger currentInventoryRevision = lockInventoryRevision(transaction);
    Record control = lockControl(transaction, ISSUER);
    if (control == null) {
      Record orphan =
          transaction.fetchOne(
              "SELECT operation_id FROM " + BOOTSTRAP_INTENT + " WHERE issuer_id = ?", ISSUER);
      if (orphan != null) {
        throw conflict("Pending issuer-bootstrap intent lost its positive current control row");
      }
      throw conflict("Exact pending issuer-bootstrap control row is missing");
    }
    Record intentRow = lockIntent(transaction, bootstrapOperationId);
    if (intentRow == null) {
      throw conflict("Exact pending issuer-bootstrap operation is missing");
    }
    CanonicalGameplayIssuerLayoutBootstrapIntent intent =
        loadAndRequireCurrentIntent(
            transaction,
            control,
            intentRow,
            bootstrapOperationId,
            bootstrapRequestId,
            currentInventoryRevision);

    List<IssuerCohortSourceRow> currentRows =
        captureCurrentCohort(transaction, intent.inventorySnapshotRevision());
    requireSameCohort(intent.cohortRows(), currentRows);
    DerivedPlan derived = derivePlan(intent, currentRows);

    Record existing =
        transaction.fetchOne(
            "SELECT * FROM " + BUILD_PLAN + " WHERE bootstrap_operation_id = ? FOR UPDATE",
            bootstrapOperationId);
    if (existing != null) {
      return loadAndRequireExactPlan(transaction, intent, currentRows, derived, existing);
    }

    Set<UUID> reservedIdentities = collectSourceIdentities(intent, derived);
    UUID buildOperationId = nextUniqueUuid(reservedIdentities);
    UUID buildPreparationFenceId = nextUniqueUuid(reservedIdentities);
    List<CanonicalGameplayIssuerPartitionBuildPlan.MemberWork> memberWorks =
        new ArrayList<>(derived.reservations().size());
    for (int index = 0; index < derived.reservations().size(); index++) {
      PlannedReservation planned = derived.reservations().get(index);
      memberWorks.add(
          toMemberWork(
              planned,
              index,
              nextUniqueUuid(reservedIdentities),
              nextUniqueUuid(reservedIdentities),
              intent));
    }
    List<CanonicalGameplayIssuerPartitionBuildPlan.Blocker> blockers =
        toBlockers(derived.blockers(), intent.cohortRows());
    String digest =
        digestPlan(
            buildOperationId,
            buildPreparationFenceId,
            intent,
            intent.cohortRows(),
            memberWorks,
            blockers);
    CanonicalGameplayIssuerPartitionBuildPlan plan =
        new CanonicalGameplayIssuerPartitionBuildPlan(
            buildOperationId,
            buildPreparationFenceId,
            intent,
            intent.cohortRows(),
            memberWorks,
            blockers,
            digest);
    insertPlan(transaction, plan);
    return loadAndRequireExactPlan(
        transaction,
        intent,
        currentRows,
        derived,
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT * FROM " + BUILD_PLAN + " WHERE bootstrap_operation_id = ? FOR UPDATE",
                bootstrapOperationId),
            "saved issuer partition build plan must be readable"));
  }

  /**
   * Reads one exact work item from an already persisted complete PREPARED plan using the caller's
   * transaction. This deliberately repeats the same locks and exact source/plan checks as plan
   * preparation; it does not open a nested transaction or create a missing plan.
   */
  PreparedMemberWork readPreparedMemberWorkInTransaction(
      DSLContext transaction,
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      long workOrdinal) {
    return readPreparedMemberWorkFromVerifiedPlan(
        readPreparedPlanInTransaction(transaction, bootstrapOperationId, bootstrapRequestId),
        workOrdinal);
  }

  /** Loads and locks the already persisted complete PREPARED plan and its current sources. */
  CanonicalGameplayIssuerPartitionBuildPlan readPreparedPlanInTransaction(
      DSLContext transaction, UUID bootstrapOperationId, UUID bootstrapRequestId) {
    requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    if (bootstrapOperationId.equals(bootstrapRequestId)) {
      throw conflict("Bootstrap operation and request identities must be distinct");
    }
    BigInteger currentInventoryRevision = lockInventoryRevision(transaction);
    Record control = lockControl(transaction, ISSUER);
    if (control == null) {
      Record orphan =
          transaction.fetchOne(
              "SELECT operation_id FROM " + BOOTSTRAP_INTENT + " WHERE issuer_id = ?", ISSUER);
      if (orphan != null) {
        throw conflict("Pending issuer-bootstrap intent lost its positive current control row");
      }
      throw conflict("Exact pending issuer-bootstrap control row is missing");
    }
    Record intentRow = lockIntent(transaction, bootstrapOperationId);
    if (intentRow == null) throw conflict("Exact pending issuer-bootstrap operation is missing");
    CanonicalGameplayIssuerLayoutBootstrapIntent intent =
        loadAndRequireCurrentIntent(
            transaction,
            control,
            intentRow,
            bootstrapOperationId,
            bootstrapRequestId,
            currentInventoryRevision);
    List<IssuerCohortSourceRow> currentRows =
        captureCurrentCohort(transaction, intent.inventorySnapshotRevision());
    requireSameCohort(intent.cohortRows(), currentRows);
    DerivedPlan derived = derivePlan(intent, currentRows);
    Record buildPlanRow =
        transaction.fetchOne(
            "SELECT * FROM " + BUILD_PLAN + " WHERE bootstrap_operation_id = ? FOR UPDATE",
            bootstrapOperationId);
    if (buildPlanRow == null) {
      throw conflict("Exact complete issuer partition build plan is missing");
    }
    CanonicalGameplayIssuerPartitionBuildPlan plan =
        loadAndRequireExactPlan(transaction, intent, currentRows, derived, buildPlanRow);
    if (plan.state() != CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED) {
      throw conflict("Blocked issuer partition build plan cannot issue member operations");
    }
    return plan;
  }

  /**
   * Reconstructs the immutable plan retained by an already installed initial anchor. This reads
   * only the frozen V1030 cohort and V1031 plan artifacts; it deliberately does not compare them
   * with the now-advancing live inventory clock or source rows.
   */
  CanonicalGameplayIssuerPartitionBuildPlan readInstalledPlanForAnchorInTransaction(
      DSLContext transaction, UUID bootstrapOperationId, UUID bootstrapRequestId) {
    requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    if (bootstrapOperationId.equals(bootstrapRequestId)) {
      throw conflict("Bootstrap operation and request identities must be distinct");
    }
    Record control = lockControl(transaction, ISSUER);
    if (control == null) throw conflict("Installed initial anchor lost its issuer control row");
    Record intentRow = lockIntent(transaction, bootstrapOperationId);
    if (intentRow == null) throw conflict("Installed initial anchor lost its bootstrap intent row");
    BigInteger frozenRevision = readBigInteger(intentRow, "inventory_snapshot_revision");
    CanonicalGameplayIssuerLayoutBootstrapIntent intent =
        loadAndRequireCurrentIntent(
            transaction,
            control,
            intentRow,
            bootstrapOperationId,
            bootstrapRequestId,
            frozenRevision);
    List<IssuerCohortSourceRow> frozenRows =
        intent.cohortRows().stream().map(IssuerCohortSourceRow::new).toList();
    DerivedPlan derived = derivePlan(intent, frozenRows);
    Record buildPlanRow =
        transaction.fetchOne(
            "SELECT * FROM " + BUILD_PLAN + " WHERE bootstrap_operation_id = ? FOR UPDATE",
            bootstrapOperationId);
    if (buildPlanRow == null)
      throw conflict("Installed initial anchor lost its immutable build plan");
    CanonicalGameplayIssuerPartitionBuildPlan plan =
        loadAndRequireExactPlan(transaction, intent, frozenRows, derived, buildPlanRow);
    if (plan.state() != CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED) {
      throw conflict("Installed initial anchor plan contains an unresolved blocker");
    }
    return plan;
  }

  /** Extracts typed source only from a plan returned by {@link #readPreparedPlanInTransaction}. */
  PreparedMemberWork readPreparedMemberWorkFromVerifiedPlan(
      CanonicalGameplayIssuerPartitionBuildPlan plan, long workOrdinal) {
    Objects.requireNonNull(plan, "plan");
    if (plan.state() != CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED) {
      throw conflict("Blocked issuer partition build plan cannot issue member operations");
    }
    if (workOrdinal < 0) throw conflict("Member work ordinal must not be negative");
    if (workOrdinal >= plan.memberWorks().size()) {
      throw conflict("Member work ordinal is outside the exact prepared plan");
    }
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work =
        plan.memberWorks().get(Math.toIntExact(workOrdinal));
    int sourceIndex = Math.toIntExact(work.reservationSourceRowOrdinal());
    if (sourceIndex >= intent.cohortRows().size()) {
      throw conflict("Prepared member work lost its exact reservation cohort row");
    }
    CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow sourceRow =
        intent.cohortRows().get(sourceIndex);
    if (sourceRow.kind() != CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION
        || !sourceRow.sourceRowDigest().equals(work.reservationSourceRowDigest())) {
      throw conflict("Prepared member work no longer links to its exact original reservation");
    }
    ReservationSource source = parseReservation(sourceRow);
    CanonicalGameplayBindingIdentity owner =
        new CanonicalGameplayBindingIdentity(
            UUID.fromString(source.accountId()),
            UUID.fromString(source.tenantId()),
            UUID.fromString(source.namespaceId()),
            UUID.fromString(source.characterId()),
            source.playableStateScope(),
            UUID.fromString(source.gameInstanceId()),
            source.runtimeGameInstanceId().longValueExact(),
            source.sessionId(),
            UUID.fromString(source.regionId()),
            source.regionEpoch(),
            source.issuerId(),
            source.issuerAuthGeneration(),
            source.layoutVersion(),
            source.partitionCount(),
            source.partitionCapacity());
    CanonicalIssuerPartitionReservation reservation =
        new CanonicalIssuerPartitionReservation(
            source.reservationId(),
            owner,
            source.bindingGeneration(),
            source.transitionId(),
            source.partitionId(),
            CanonicalIssuerPartitionReservation.Lifecycle.valueOf(source.lifecycle()),
            source.reservationFence(),
            source.coverageOperationId(),
            source.coverageOperationFence(),
            source.coverageFence(),
            source.inventorySnapshotRevision(),
            sourceRow.sourceInventoryRevision());
    if (!work.reservationId().equals(reservation.reservationId())
        || !Arrays.equals(work.bindingRef(), owner.bindingRef().bytes())
        || !work.transitionId().equals(reservation.transitionId())
        || !work.reservationLifecycle().equals(reservation.lifecycle().name())
        || !work.reservationFence().equals(reservation.reservationFence())
        || !work.originalPartitionId().equals(reservation.partitionId())
        || !work.originalLayoutVersion().equals(reservation.owner().issuerIndexLayoutVersion())
        || !work.originalPartitionCount().equals(reservation.owner().issuerIndexPartitionCount())
        || !work.originalPartitionCapacity()
            .equals(reservation.owner().issuerIndexPartitionCapacity())
        || !work.originalIssuerCoverageOperationId().equals(reservation.issuerCoverageOperationId())
        || !work.originalIssuerCoverageOperationFence()
            .equals(reservation.issuerCoverageOperationFence())
        || !work.originalCoverageFence().equals(reservation.coverageFence())
        || !work.originalInventorySnapshotRevision()
            .equals(reservation.inventorySnapshotRevision())) {
      throw conflict("Prepared member work differs from its exact typed original reservation");
    }
    CanonicalGameplayBindingInventoryEntry.Lifecycle bindingLifecycle = null;
    if (work.bindingSourceRowOrdinal() != null) {
      int bindingIndex = Math.toIntExact(work.bindingSourceRowOrdinal());
      if (bindingIndex >= intent.cohortRows().size()) {
        throw conflict("Prepared member work lost its exact binding cohort row");
      }
      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow bindingRow =
          intent.cohortRows().get(bindingIndex);
      if (bindingRow.kind() != CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.BINDING
          || !bindingRow.sourceRowDigest().equals(work.bindingSourceRowDigest())) {
        throw conflict("Prepared member work no longer links to its exact binding cohort row");
      }
      BindingSource binding = parseBinding(bindingRow);
      if (!binding.matches(source)) {
        throw conflict("Prepared member work binding differs from the exact original reservation");
      }
      try {
        bindingLifecycle =
            CanonicalGameplayBindingInventoryEntry.Lifecycle.valueOf(binding.lifecycle());
      } catch (RuntimeException malformed) {
        throw conflict("Prepared member work has an unsupported binding lifecycle");
      }
    }
    return new PreparedMemberWork(plan, work, reservation, bindingLifecycle);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapIntent loadAndRequireCurrentIntent(
      DSLContext transaction,
      Record control,
      Record intent,
      UUID expectedOperationId,
      UUID expectedRequestId,
      BigInteger currentInventoryRevision) {
    UUID operationId = requireNonNil(intent.get("operation_id", UUID.class), "operation_id");
    UUID requestId = requireNonNil(intent.get("request_id", UUID.class), "request_id");
    String issuerId = Objects.requireNonNull(intent.get("issuer_id", String.class), "issuer_id");
    BigInteger controlEpoch = readBigInteger(intent, "control_epoch");
    BigInteger operationFence = readBigInteger(intent, "operation_fence");
    BigInteger coverageFence = readBigInteger(intent, "coverage_fence");
    BigInteger snapshotRevision = readBigInteger(intent, "inventory_snapshot_revision");
    BigInteger controlLastFence = readBigInteger(control, "last_issued_fence");
    if (!operationId.equals(expectedOperationId)
        || !requestId.equals(expectedRequestId)
        || !ISSUER.equals(issuerId)
        || !issuerId.equals(control.get("issuer_id", String.class))
        || !"INITIAL_BOOTSTRAP_INTENT".equals(control.get("state", String.class))
        || !operationId.equals(control.get("current_operation_id", UUID.class))
        || !controlEpoch.equals(readBigInteger(control, "control_epoch"))
        || !operationFence.equals(readBigInteger(control, "current_operation_fence"))
        || !coverageFence.equals(readBigInteger(control, "current_coverage_fence"))
        || !operationFence.equals(controlEpoch)
        || operationFence.equals(coverageFence)
        || controlLastFence.compareTo(coverageFence) < 0
        || !"INITIAL_BOOTSTRAP_INTENT".equals(intent.get("intent_state", String.class))) {
      throw conflict("Pending bootstrap request or exact current control tuple changed");
    }
    if (!snapshotRevision.equals(currentInventoryRevision)) {
      throw conflict("Canonical issuer inventory advanced after its pending bootstrap snapshot");
    }

    byte[] descriptorBytes = requireBytes(intent, "layout_descriptor_bytes");
    String descriptorDigest = requireText(intent, "layout_descriptor_digest");
    if (!sha256(descriptorBytes).equals(descriptorDigest)) {
      throw conflict("Persisted bootstrap layout descriptor digest is invalid");
    }
    String mappingIdentity = requireText(intent, "mapping_identity");
    if (!MAPPING_IDENTITY.equals(mappingIdentity)) {
      throw conflict("Persisted bootstrap layout uses an unsupported canonical mapping");
    }
    CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor descriptor;
    try {
      descriptor =
          new CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor(
              issuerId,
              readBigInteger(intent, "layout_version"),
              readBigInteger(intent, "partition_count"),
              readBigInteger(intent, "partition_capacity"),
              mappingIdentity,
              descriptorBytes,
              descriptorDigest);
    } catch (RuntimeException malformed) {
      throw conflict("Persisted bootstrap layout descriptor is malformed");
    }

    byte[] checkpointBytes = requireBytes(intent, "source_checkpoint_bytes");
    String checkpointDigest = requireText(intent, "source_checkpoint_digest");
    byte[] eventBytes = intent.get("source_event_bytes", byte[].class);
    String eventDigest = intent.get("source_event_digest", String.class);
    if (!sha256(checkpointBytes).equals(checkpointDigest)
        || ((eventBytes == null) != (eventDigest == null))
        || (eventBytes != null && !sha256(eventBytes).equals(eventDigest))) {
      throw conflict("Persisted Account issuer source checkpoint digest is invalid");
    }
    BigInteger issuerGeneration = readBigInteger(intent, "issuer_auth_generation");
    if (eventBytes != null) requireValidSourceEvent(issuerId, issuerGeneration, eventBytes);
    CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint checkpoint;
    try {
      checkpoint =
          new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
              issuerId,
              issuerGeneration,
              checkpointBytes,
              checkpointDigest,
              eventBytes,
              eventDigest);
    } catch (RuntimeException malformed) {
      throw conflict("Persisted Account issuer source checkpoint is malformed");
    }

    CanonicalGameplayLegacyMigrationDisposition storedLegacy;
    CanonicalGameplayLegacyMigrationDisposition currentLegacy;
    try {
      storedLegacy = persistedLegacyDisposition(intent);
      currentLegacy = readCurrentLegacyDisposition(transaction, snapshotRevision);
    } catch (RuntimeException malformed) {
      throw conflict("Current verified legacy disposition is missing or malformed");
    }
    if (!storedLegacy.equals(currentLegacy)) {
      throw conflict("Verified legacy disposition changed after pending bootstrap capture");
    }

    List<IssuerCohortSourceRow> rows = readStoredCohort(transaction, operationId);
    long expectedCount = requireLong(intent, "cohort_row_count");
    String expectedDigest = requireText(intent, "cohort_digest");
    List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohortRows =
        rows.stream().map(IssuerCohortSourceRow::cohortRow).toList();
    if (cohortRows.size() != expectedCount
        || !digestCohortRows(cohortRows).equals(expectedDigest)
        || cohortRows.stream()
            .anyMatch(row -> row.sourceInventoryRevision().compareTo(snapshotRevision) > 0)) {
      throw conflict("Persisted bootstrap cohort is incomplete or its exact digest changed");
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
        checkpoint,
        storedLegacy,
        CanonicalGameplayIssuerLayoutBootstrapIntent.State.INITIAL_BOOTSTRAP_INTENT,
        cohortRows,
        expectedDigest);
  }

  private static List<IssuerCohortSourceRow> readStoredCohort(
      DSLContext transaction, UUID operationId) {
    return transaction
        .fetch(
            "SELECT * FROM " + BOOTSTRAP_COHORT + " WHERE operation_id = ? ORDER BY row_ordinal",
            operationId)
        .map(
            row -> {
              String sourceJson = requireText(row, "source_row_json");
              String digest = requireText(row, "source_row_digest");
              if (!sha256(sourceJson.getBytes(StandardCharsets.UTF_8)).equals(digest)) {
                throw conflict("Frozen bootstrap cohort contains a changed source-row digest");
              }
              if (!operationId.equals(row.get("operation_id", UUID.class))
                  || !ISSUER.equals(row.get("issuer_id", String.class))) {
                throw conflict("Frozen bootstrap cohort row lost its exact owner linkage");
              }
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow cohortRow;
              try {
                cohortRow =
                    new CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow(
                        requireLong(row, "row_ordinal"),
                        CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.valueOf(
                            requireText(row, "source_kind")),
                        requireText(row, "source_key"),
                        readBigInteger(row, "source_inventory_revision"),
                        sourceJson,
                        digest);
              } catch (RuntimeException malformed) {
                throw conflict("Frozen bootstrap cohort row is malformed");
              }
              return new IssuerCohortSourceRow(cohortRow);
            });
  }

  private static CanonicalGameplayLegacyMigrationDisposition persistedLegacyDisposition(
      Record row) {
    return new CanonicalGameplayLegacyMigrationDisposition(
        requireNonNil(row.get("legacy_cohort_id", UUID.class), "legacy_cohort_id"),
        requireNonNil(
            row.get("legacy_owner_operation_id", UUID.class), "legacy_owner_operation_id"),
        requireNonNil(row.get("legacy_writer_fence", UUID.class), "legacy_writer_fence"),
        readBigInteger(row, "legacy_source_snapshot_revision"),
        readBigInteger(row, "legacy_canonical_inventory_revision"),
        readBigInteger(row, "legacy_namespace_index_readback_revision"),
        requireText(row, "legacy_evidence_digest"));
  }

  private static CanonicalGameplayLegacyMigrationDisposition readCurrentLegacyDisposition(
      DSLContext transaction, BigInteger snapshotRevision) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + LEGACY_DISPOSITION + " WHERE singleton_id = 1 FOR SHARE");
    if (row == null || !"VERIFIED".equals(row.get("disposition_state", String.class))) {
      throw conflict("Initial issuer build requires the actual VERIFIED legacy disposition");
    }
    CanonicalGameplayLegacyMigrationDisposition current =
        new CanonicalGameplayLegacyMigrationDisposition(
            row.get("cohort_id", UUID.class),
            row.get("owner_operation_id", UUID.class),
            row.get("legacy_writer_fence", UUID.class),
            readBigInteger(row, "source_snapshot_revision"),
            readBigInteger(row, "canonical_inventory_revision"),
            readBigInteger(row, "namespace_index_readback_revision"),
            row.get("evidence_digest", String.class));
    if (current.canonicalInventoryRevision().compareTo(snapshotRevision) > 0) {
      throw conflict("Verified legacy disposition is newer than the frozen inventory snapshot");
    }
    return current;
  }

  private static List<IssuerCohortSourceRow> captureCurrentCohort(
      DSLContext transaction, BigInteger snapshotRevision) {
    requireNoFutureRows(transaction, snapshotRevision);
    return transaction
        .fetch(
            COHORT_SQL,
            ISSUER,
            decimal(snapshotRevision),
            decimal(snapshotRevision),
            ISSUER,
            decimal(snapshotRevision),
            decimal(snapshotRevision),
            decimal(snapshotRevision),
            decimal(snapshotRevision))
        .map(
            row -> {
              String sourceJson = requireText(row, "source_row_json");
              String sourceKey = requireText(row, "source_key");
              BigInteger sourceRevision = readBigInteger(row, "source_revision");
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind kind;
              try {
                kind =
                    CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.valueOf(
                        requireText(row, "source_kind"));
              } catch (RuntimeException malformed) {
                throw conflict("Current issuer inventory contains an unsupported source family");
              }
              if (sourceRevision.compareTo(snapshotRevision) > 0) {
                throw conflict(
                    "Current issuer inventory source is newer than its bootstrap snapshot");
              }
              return new IssuerCohortSourceRow(
                  new CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow(
                      0L,
                      kind,
                      sourceKey,
                      sourceRevision,
                      sourceJson,
                      sha256(sourceJson.getBytes(StandardCharsets.UTF_8))));
            });
  }

  private static void requireSameCohort(
      List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> frozen,
      List<IssuerCohortSourceRow> current) {
    List<IssuerCohortSourceRow> sorted =
        current.stream()
            .sorted(
                Comparator.comparing((IssuerCohortSourceRow row) -> row.cohortRow().kind().name())
                    .thenComparing(row -> row.cohortRow().sourceKey()))
            .toList();
    if (frozen.size() != sorted.size()) {
      throw conflict(
          "Current source inventory no longer equals its complete frozen bootstrap cohort");
    }
    for (int index = 0; index < frozen.size(); index++) {
      var expected = frozen.get(index);
      var row = sorted.get(index).cohortRow();
      if (expected.rowOrdinal() != index
          || expected.kind() != row.kind()
          || !expected.sourceKey().equals(row.sourceKey())
          || !expected.sourceInventoryRevision().equals(row.sourceInventoryRevision())
          || !expected.sourceRowJson().equals(row.sourceRowJson())
          || !expected.sourceRowDigest().equals(row.sourceRowDigest())) {
        throw conflict("Current source row differs from its exact original bootstrap cohort entry");
      }
    }
  }

  private static DerivedPlan derivePlan(
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      List<IssuerCohortSourceRow> currentRows) {
    List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohortRows =
        currentRows.stream()
            .sorted(
                Comparator.comparing((IssuerCohortSourceRow row) -> row.cohortRow().kind().name())
                    .thenComparing(row -> row.cohortRow().sourceKey()))
            .map(
                row -> {
                  var source = row.cohortRow();
                  return new CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow(
                      currentRows.indexOf(row),
                      source.kind(),
                      source.sourceKey(),
                      source.sourceInventoryRevision(),
                      source.sourceRowJson(),
                      source.sourceRowDigest());
                })
            .toList();
    List<PlanBlocker> blockers = new ArrayList<>();
    Map<UUID, ReservationSource> reservations = new HashMap<>();
    Map<UUID, List<BindingSource>> bindings = new HashMap<>();
    Map<Long, CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> byOrdinal = new HashMap<>();
    for (var row : cohortRows) byOrdinal.put(row.rowOrdinal(), row);

    for (var row : cohortRows) {
      switch (row.kind()) {
        case UNRESOLVED_LEGACY_BINDING ->
            blockers.add(
                new PlanBlocker(
                    row.rowOrdinal(),
                    CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                        .UNRESOLVED_LEGACY_BINDING));
        case UNRESOLVED_LEGACY_RESERVATION ->
            blockers.add(
                new PlanBlocker(
                    row.rowOrdinal(),
                    CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                        .UNRESOLVED_LEGACY_RESERVATION));
        case RESERVATION -> {
          try {
            ReservationSource parsed = parseReservation(row);
            if (reservations.putIfAbsent(parsed.reservationId(), parsed) != null) {
              blockers.add(
                  new PlanBlocker(
                      row.rowOrdinal(),
                      CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                          .UNREPRESENTABLE_RESERVATION));
            }
          } catch (RuntimeException malformed) {
            blockers.add(
                new PlanBlocker(
                    row.rowOrdinal(),
                    CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                        .UNREPRESENTABLE_RESERVATION));
          }
        }
        case BINDING -> {
          try {
            BindingSource parsed = parseBinding(row);
            bindings
                .computeIfAbsent(parsed.reservationId(), ignored -> new ArrayList<>())
                .add(parsed);
          } catch (RuntimeException malformed) {
            blockers.add(
                new PlanBlocker(
                    row.rowOrdinal(),
                    CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.UNREPRESENTABLE_BINDING));
          }
        }
        default -> {
          // Transition and obligation rows remain in the exact cohort artifact and plan digest.
        }
      }
    }

    Map<UUID, Long> bindingOrdinalByReservation = new HashMap<>();
    for (var entry : bindings.entrySet()) {
      List<BindingSource> rows = entry.getValue();
      if (rows.size() != 1) {
        rows.forEach(
            row ->
                blockers.add(
                    new PlanBlocker(
                        row.rowOrdinal(),
                        CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                            .CONFLICTING_RESERVATION_LINK)));
        continue;
      }
      BindingSource binding = rows.get(0);
      ReservationSource reservation = reservations.get(entry.getKey());
      if (reservation == null) {
        blockers.add(
            new PlanBlocker(
                binding.rowOrdinal(),
                CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.MISSING_RESERVATION));
      } else if (!binding.matches(reservation)) {
        blockers.add(
            new PlanBlocker(
                reservation.rowOrdinal(),
                CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                    .CONFLICTING_RESERVATION_LINK));
        blockers.add(
            new PlanBlocker(
                binding.rowOrdinal(),
                CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                    .CONFLICTING_RESERVATION_LINK));
      } else {
        bindingOrdinalByReservation.put(entry.getKey(), binding.rowOrdinal());
      }
    }

    List<ReservationSource> initialLayoutReservations = new ArrayList<>();
    for (ReservationSource reservation :
        reservations.values().stream()
            .sorted(Comparator.comparingLong(ReservationSource::rowOrdinal))
            .toList()) {
      if (matchesInitialLayout(reservation, intent)) {
        initialLayoutReservations.add(reservation);
      } else {
        blockers.add(
            new PlanBlocker(
                reservation.rowOrdinal(),
                CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                    .CONFLICTING_RESERVATION_LINK));
      }
    }

    List<PlannedReservation> planned =
        initialLayoutReservations.stream()
            .map(
                reservation -> {
                  Long bindingOrdinal =
                      bindingOrdinalByReservation.get(reservation.reservationId());
                  String bindingDigest =
                      bindingOrdinal == null
                          ? null
                          : byOrdinal.get(bindingOrdinal).sourceRowDigest();
                  BigInteger partitionId;
                  try {
                    partitionId =
                        CanonicalGameplayBindingRef.fromExactBytes(reservation.bindingRef())
                            .partitionId(intent.layoutDescriptor().partitionCount());
                  } catch (RuntimeException malformed) {
                    blockers.add(
                        new PlanBlocker(
                            reservation.rowOrdinal(),
                            CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                                .UNREPRESENTABLE_RESERVATION));
                    return null;
                  }
                  return new PlannedReservation(
                      reservation, bindingOrdinal, bindingDigest, partitionId);
                })
            .filter(Objects::nonNull)
            .toList();
    requireIssuerSourceLinks(
        cohortRows, reservations, bindingOrdinalByReservation, bindings, blockers);
    Map<BigInteger, List<PlannedReservation>> countedByPartition = new HashMap<>();
    List<PlannedReservation> countedReservations =
        planned.stream()
            .filter(item -> !item.reservation().lifecycle().equals("RELEASED"))
            .toList();
    for (PlannedReservation item : countedReservations) {
      countedByPartition
          .computeIfAbsent(item.plannedPartitionId(), ignored -> new ArrayList<>())
          .add(item);
    }
    for (List<PlannedReservation> partitionReservations : countedByPartition.values()) {
      if (BigInteger.valueOf(partitionReservations.size())
              .compareTo(intent.layoutDescriptor().partitionCapacity())
          > 0) {
        partitionReservations.forEach(
            item ->
                blockers.add(
                    new PlanBlocker(
                        item.reservation().rowOrdinal(),
                        CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                            .PARTITION_CAPACITY_EXCEEDED)));
      }
    }
    BigInteger aggregateCapacity =
        intent
            .layoutDescriptor()
            .partitionCount()
            .multiply(intent.layoutDescriptor().partitionCapacity());
    if (BigInteger.valueOf(countedReservations.size()).compareTo(aggregateCapacity) > 0) {
      countedReservations.forEach(
          item ->
              blockers.add(
                  new PlanBlocker(
                      item.reservation().rowOrdinal(),
                      CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                          .AGGREGATE_CAPACITY_EXCEEDED)));
    }
    blockers.sort(
        Comparator.comparingLong(PlanBlocker::sourceRowOrdinal)
            .thenComparing(blocker -> blocker.code().name()));
    return new DerivedPlan(planned, blockers);
  }

  /** Initial bootstrap adopts its frozen layout; it does not migrate reservations to a new one. */
  private static boolean matchesInitialLayout(
      ReservationSource reservation, CanonicalGameplayIssuerLayoutBootstrapIntent intent) {
    var layout = intent.layoutDescriptor();
    if (!reservation.layoutVersion().equals(layout.layoutVersion())
        || !reservation.partitionCount().equals(layout.partitionCount())
        || !reservation.partitionCapacity().equals(layout.partitionCapacity())) {
      return false;
    }
    try {
      BigInteger originalCanonicalPartition =
          CanonicalGameplayBindingRef.fromExactBytes(reservation.bindingRef())
              .partitionId(reservation.partitionCount());
      return originalCanonicalPartition.equals(reservation.partitionId());
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static ReservationSource parseReservation(
      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow row) {
    JsonNode json = parseObject(row.sourceRowJson());
    UUID reservationId = uuid(json, "reservation_id");
    if (!reservationId.toString().equals(row.sourceKey())) {
      throw conflict("Reservation cohort key differs from its original SQL row");
    }
    String issuerId = text(json, "issuer_id");
    if (!ISSUER.equals(issuerId))
      throw conflict("Reservation does not use the exact Account issuer");
    byte[] bindingRef = bytea(json, "binding_ref");
    CanonicalGameplayBindingRef.fromExactBytes(bindingRef);
    String tenantId = text(json, "tenant_id");
    String gameInstanceId = text(json, "game_instance_id");
    String sessionId = text(json, "session_id");
    if (!Arrays.equals(
        bindingRef, CanonicalGameplayBindingRef.of(tenantId, gameInstanceId, sessionId).bytes())) {
      throw conflict("Reservation owner identifiers do not match its original bindingRef bytes");
    }
    String lifecycle = text(json, "lifecycle");
    if (!List.of("RESERVED", "BOUND", "RELEASE_PENDING", "RELEASED").contains(lifecycle)) {
      throw conflict("Reservation lifecycle is not representable by the canonical owner");
    }
    BigInteger sourceRevision = integer(json, "inventory_revision");
    if (!sourceRevision.equals(row.sourceInventoryRevision())) {
      throw conflict("Reservation cohort revision differs from its original SQL row");
    }
    BigInteger partitionId = integer(json, "partition_id");
    BigInteger layoutVersion = integer(json, "issuer_index_layout_version");
    BigInteger partitionCount = integer(json, "issuer_index_partition_count");
    BigInteger partitionCapacity = integer(json, "issuer_index_partition_capacity");
    if (partitionId.signum() < 0
        || layoutVersion.signum() <= 0
        || partitionCount.signum() <= 0
        || partitionCapacity.signum() <= 0
        || partitionId.compareTo(partitionCount) >= 0) {
      throw conflict("Original reservation layout or partition is malformed");
    }
    BigInteger originalSnapshot = integer(json, "inventory_snapshot_revision");
    BigInteger originalOperationFence = integer(json, "issuer_coverage_operation_fence");
    BigInteger originalCoverageFence = integer(json, "coverage_fence");
    if (originalSnapshot.signum() <= 0
        || originalOperationFence.signum() <= 0
        || originalCoverageFence.signum() <= 0) {
      throw conflict("Original reservation coverage tuple is malformed");
    }
    return new ReservationSource(
        row.rowOrdinal(),
        row.sourceRowDigest(),
        reservationId,
        bindingRef,
        uuid(json, "transition_id"),
        uuid(json, "reservation_fence"),
        lifecycle,
        partitionId,
        layoutVersion,
        partitionCount,
        partitionCapacity,
        uuid(json, "issuer_coverage_operation_id"),
        originalOperationFence,
        originalCoverageFence,
        originalSnapshot,
        integer(json, "binding_generation"),
        text(json, "account_id"),
        tenantId,
        text(json, "playable_state_namespace_id"),
        text(json, "character_id"),
        text(json, "playable_state_scope"),
        gameInstanceId,
        integer(json, "runtime_game_instance_id"),
        sessionId,
        text(json, "region_id"),
        integer(json, "region_epoch"),
        issuerId,
        integer(json, "issuer_auth_generation"));
  }

  private static BindingSource parseBinding(
      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow row) {
    JsonNode json = parseObject(row.sourceRowJson());
    byte[] bindingRef = bytea(json, "binding_ref");
    CanonicalGameplayBindingRef.fromExactBytes(bindingRef);
    if (!HexFormat.of().formatHex(bindingRef).equals(row.sourceKey())) {
      throw conflict("Binding cohort key differs from its original SQL row");
    }
    String issuerId = text(json, "issuer_id");
    if (!ISSUER.equals(issuerId)) {
      throw conflict("Binding source does not use the exact Account issuer");
    }
    String lifecycle = text(json, "lifecycle");
    try {
      CanonicalGameplayBindingInventoryEntry.Lifecycle.valueOf(lifecycle);
    } catch (RuntimeException malformed) {
      throw conflict("Binding source uses an unsupported lifecycle");
    }
    return new BindingSource(
        row.rowOrdinal(),
        row.sourceRowDigest(),
        uuid(json, "issuer_reservation_id"),
        bindingRef,
        uuid(json, "transition_id"),
        text(json, "account_id"),
        text(json, "tenant_id"),
        text(json, "playable_state_namespace_id"),
        text(json, "character_id"),
        text(json, "playable_state_scope"),
        text(json, "game_instance_id"),
        integer(json, "runtime_game_instance_id"),
        text(json, "session_id"),
        text(json, "region_id"),
        integer(json, "region_epoch"),
        issuerId,
        integer(json, "issuer_auth_generation"),
        integer(json, "issuer_index_layout_version"),
        integer(json, "issuer_index_partition_count"),
        integer(json, "issuer_index_partition_capacity"),
        integer(json, "issuer_partition_id"),
        lifecycle,
        integer(json, "binding_generation"));
  }

  private static CanonicalGameplayIssuerPartitionBuildPlan.MemberWork toMemberWork(
      PlannedReservation planned,
      int ordinal,
      UUID memberOperationId,
      UUID memberPreparationFenceId,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent) {
    ReservationSource reservation = planned.reservation();
    return new CanonicalGameplayIssuerPartitionBuildPlan.MemberWork(
        ordinal,
        memberOperationId,
        memberPreparationFenceId,
        reservation.rowOrdinal(),
        reservation.sourceRowDigest(),
        planned.bindingSourceRowOrdinal(),
        planned.bindingSourceRowDigest(),
        reservation.reservationId(),
        reservation.bindingRef(),
        reservation.transitionId(),
        reservation.lifecycle(),
        reservation.reservationFence(),
        reservation.partitionId(),
        reservation.layoutVersion(),
        reservation.partitionCount(),
        reservation.partitionCapacity(),
        reservation.coverageOperationId(),
        reservation.coverageOperationFence(),
        reservation.coverageFence(),
        reservation.inventorySnapshotRevision(),
        planned.plannedPartitionId(),
        intent.operationId(),
        intent.operationFence(),
        intent.coverageFence(),
        intent.inventorySnapshotRevision());
  }

  private static List<CanonicalGameplayIssuerPartitionBuildPlan.Blocker> toBlockers(
      List<PlanBlocker> blockers,
      List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohort) {
    return java.util.stream.IntStream.range(0, blockers.size())
        .mapToObj(
            index -> {
              PlanBlocker blocker = blockers.get(index);
              return new CanonicalGameplayIssuerPartitionBuildPlan.Blocker(
                  index,
                  blocker.sourceRowOrdinal(),
                  cohort.get((int) blocker.sourceRowOrdinal()).sourceRowDigest(),
                  blocker.code());
            })
        .toList();
  }

  private static void requireIssuerSourceLinks(
      List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohortRows,
      Map<UUID, ReservationSource> reservations,
      Map<UUID, Long> bindingOrdinalByReservation,
      Map<UUID, List<BindingSource>> bindings,
      List<PlanBlocker> blockers) {
    for (var row : cohortRows) {
      if (row.kind() != CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.TRANSITION
          && row.kind()
              != CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.ISSUER_OBLIGATION) {
        continue;
      }
      try {
        JsonNode json = parseObject(row.sourceRowJson());
        UUID transitionId = uuid(json, "transition_id");
        boolean transitionRow =
            row.kind() == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.TRANSITION;
        UUID reservationId = uuid(json, transitionRow ? "issuer_reservation_id" : "reservation_id");
        byte[] bindingRef = bytea(json, transitionRow ? "candidate_binding_ref" : "binding_ref");
        BigInteger bindingGeneration =
            integer(json, transitionRow ? "candidate_binding_generation" : "binding_generation");
        ReservationSource reservation = reservations.get(reservationId);
        List<BindingSource> matchingBindings = bindings.get(reservationId);
        Long linkedBindingOrdinal = bindingOrdinalByReservation.get(reservationId);
        if (reservation == null
            || !reservation.transitionId().equals(transitionId)
            || !Arrays.equals(reservation.bindingRef(), bindingRef)
            || !reservation.bindingGeneration().equals(bindingGeneration)
            || matchingBindings == null
            || matchingBindings.size() != 1
            || linkedBindingOrdinal == null
            || !matchingBindings.get(0).matches(reservation)) {
          blockers.add(
              new PlanBlocker(
                  row.rowOrdinal(),
                  CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                      .CONFLICTING_RESERVATION_LINK));
        }
        if (transitionRow && !row.sourceKey().equals(transitionId.toString())) {
          blockers.add(
              new PlanBlocker(
                  row.rowOrdinal(),
                  CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.UNREPRESENTABLE_SOURCE));
        }
        if (!transitionRow) {
          String expectedKey = HexFormat.of().formatHex(bindingRef) + ":" + transitionId;
          if (!row.sourceKey().equals(expectedKey)) {
            blockers.add(
                new PlanBlocker(
                    row.rowOrdinal(),
                    CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.UNREPRESENTABLE_SOURCE));
          }
        }
      } catch (RuntimeException malformed) {
        blockers.add(
            new PlanBlocker(
                row.rowOrdinal(),
                CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.UNREPRESENTABLE_SOURCE));
      }
    }
  }

  private static Set<UUID> collectSourceIdentities(
      CanonicalGameplayIssuerLayoutBootstrapIntent intent, DerivedPlan derived) {
    Set<UUID> result = new HashSet<>();
    result.add(intent.operationId());
    result.add(intent.requestId());
    for (var reservation : derived.reservations()) {
      result.add(reservation.reservation().reservationId());
      result.add(reservation.reservation().transitionId());
      result.add(reservation.reservation().reservationFence());
      result.add(reservation.reservation().coverageOperationId());
    }
    for (var row : intent.cohortRows()) {
      try {
        result.add(UUID.fromString(row.sourceKey()));
      } catch (IllegalArgumentException ignored) {
        // Binding and issuer-obligation source keys are exact byte encodings, not UUIDs.
      }
    }
    return result;
  }

  private static UUID nextUniqueUuid(Set<UUID> reserved) {
    UUID candidate;
    do {
      candidate = UUID.randomUUID();
    } while (candidate.equals(NIL_UUID) || !reserved.add(candidate));
    return candidate;
  }

  private static void insertPlan(
      DSLContext transaction, CanonicalGameplayIssuerPartitionBuildPlan plan) {
    var intent = plan.bootstrapIntent();
    var descriptor = intent.layoutDescriptor();
    transaction.execute(
        "INSERT INTO "
            + BUILD_PLAN
            + " (build_operation_id, build_preparation_fence_id, bootstrap_operation_id,"
            + " bootstrap_request_id, issuer_id, control_epoch, bootstrap_operation_fence,"
            + " bootstrap_coverage_fence, issuer_inventory_snapshot_revision, layout_version,"
            + " partition_count, partition_capacity, mapping_identity, layout_descriptor_bytes,"
            + " layout_descriptor_digest, cohort_row_count, cohort_digest, member_work_count,"
            + " blocker_count, plan_state, coverage_generation_state, coverage_generation, plan_digest)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)",
        plan.buildOperationId(),
        plan.buildPreparationFenceId(),
        intent.operationId(),
        intent.requestId(),
        intent.issuerId(),
        decimal(intent.controlEpoch()),
        decimal(intent.operationFence()),
        decimal(intent.coverageFence()),
        decimal(intent.inventorySnapshotRevision()),
        decimal(descriptor.layoutVersion()),
        decimal(descriptor.partitionCount()),
        decimal(descriptor.partitionCapacity()),
        descriptor.mappingIdentity(),
        descriptor.exactDescriptorBytes(),
        descriptor.descriptorDigest(),
        (long) plan.cohortRows().size(),
        intent.cohortDigest(),
        (long) plan.memberWorks().size(),
        (long) plan.blockers().size(),
        plan.state().name(),
        plan.coverageGenerationState().name(),
        plan.planDigest());
    for (var row : plan.cohortRows()) {
      transaction.execute(
          "INSERT INTO "
              + BUILD_COHORT
              + " (build_operation_id, source_operation_id, issuer_id, source_row_ordinal,"
              + " source_kind, source_key, source_inventory_revision, source_row_json, source_row_digest)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          plan.buildOperationId(),
          intent.operationId(),
          intent.issuerId(),
          row.rowOrdinal(),
          row.kind().name(),
          row.sourceKey(),
          decimal(row.sourceInventoryRevision()),
          row.sourceRowJson(),
          row.sourceRowDigest());
    }
    for (var work : plan.memberWorks()) insertMemberWork(transaction, plan, work);
    for (var blocker : plan.blockers()) insertBlocker(transaction, plan, blocker);
  }

  private static void insertMemberWork(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work) {
    var intent = plan.bootstrapIntent();
    transaction.execute(
        "INSERT INTO "
            + MEMBER_WORK
            + " (build_operation_id, bootstrap_operation_id, issuer_id, work_ordinal,"
            + " member_operation_id, member_preparation_fence_id, reservation_id,"
            + " reservation_source_row_ordinal, reservation_source_row_digest,"
            + " binding_source_row_ordinal, binding_source_row_digest, binding_ref, transition_id,"
            + " reservation_lifecycle, reservation_fence, original_partition_id,"
            + " original_layout_version, original_partition_count, original_partition_capacity,"
            + " original_issuer_coverage_operation_id, original_issuer_coverage_operation_fence,"
            + " original_coverage_fence, original_inventory_snapshot_revision, planned_partition_id,"
            + " planned_anchor_operation_id, planned_anchor_operation_fence,"
            + " planned_anchor_coverage_fence, planned_anchor_inventory_snapshot_revision,"
            + " counts_toward_capacity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        plan.buildOperationId(),
        intent.operationId(),
        intent.issuerId(),
        work.workOrdinal(),
        work.memberOperationId(),
        work.memberPreparationFenceId(),
        work.reservationId(),
        work.reservationSourceRowOrdinal(),
        work.reservationSourceRowDigest(),
        work.bindingSourceRowOrdinal(),
        work.bindingSourceRowDigest(),
        work.bindingRef(),
        work.transitionId(),
        work.reservationLifecycle(),
        work.reservationFence(),
        decimal(work.originalPartitionId()),
        decimal(work.originalLayoutVersion()),
        decimal(work.originalPartitionCount()),
        decimal(work.originalPartitionCapacity()),
        work.originalIssuerCoverageOperationId(),
        decimal(work.originalIssuerCoverageOperationFence()),
        decimal(work.originalCoverageFence()),
        decimal(work.originalInventorySnapshotRevision()),
        decimal(work.plannedPartitionId()),
        work.plannedAnchorOperationId(),
        decimal(work.plannedAnchorOperationFence()),
        decimal(work.plannedAnchorCoverageFence()),
        decimal(work.plannedAnchorInventorySnapshotRevision()),
        work.countsTowardCapacity());
  }

  private static void insertBlocker(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      CanonicalGameplayIssuerPartitionBuildPlan.Blocker blocker) {
    transaction.execute(
        "INSERT INTO "
            + BUILD_BLOCKER
            + " (build_operation_id, blocker_ordinal, source_row_ordinal, source_row_digest, blocker_code)"
            + " VALUES (?, ?, ?, ?, ?)",
        plan.buildOperationId(),
        blocker.blockerOrdinal(),
        blocker.sourceRowOrdinal(),
        blocker.sourceRowDigest(),
        blocker.code().name());
  }

  private static CanonicalGameplayIssuerPartitionBuildPlan loadAndRequireExactPlan(
      DSLContext transaction,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      List<IssuerCohortSourceRow> currentRows,
      DerivedPlan derived,
      Record header) {
    UUID buildOperationId =
        requireNonNil(header.get("build_operation_id", UUID.class), "build_operation_id");
    UUID buildFence =
        requireNonNil(
            header.get("build_preparation_fence_id", UUID.class), "build_preparation_fence_id");
    var descriptor = intent.layoutDescriptor();
    if (!Objects.equals(intent.operationId(), header.get("bootstrap_operation_id", UUID.class))
        || !Objects.equals(intent.requestId(), header.get("bootstrap_request_id", UUID.class))
        || !Objects.equals(intent.issuerId(), header.get("issuer_id", String.class))
        || !intent.controlEpoch().equals(readBigInteger(header, "control_epoch"))
        || !intent.operationFence().equals(readBigInteger(header, "bootstrap_operation_fence"))
        || !intent.coverageFence().equals(readBigInteger(header, "bootstrap_coverage_fence"))
        || !intent
            .inventorySnapshotRevision()
            .equals(readBigInteger(header, "issuer_inventory_snapshot_revision"))
        || !descriptor.layoutVersion().equals(readBigInteger(header, "layout_version"))
        || !descriptor.partitionCount().equals(readBigInteger(header, "partition_count"))
        || !descriptor.partitionCapacity().equals(readBigInteger(header, "partition_capacity"))
        || !descriptor.mappingIdentity().equals(header.get("mapping_identity", String.class))
        || !Arrays.equals(
            descriptor.exactDescriptorBytes(), header.get("layout_descriptor_bytes", byte[].class))
        || !descriptor
            .descriptorDigest()
            .equals(header.get("layout_descriptor_digest", String.class))
        || !"NOT_YET_ISSUED".equals(header.get("coverage_generation_state", String.class))
        || header.get("coverage_generation", BigDecimal.class) != null) {
      throw conflict(
          "Persisted build plan changed its exact pending bootstrap layout or anchor link");
    }

    List<IssuerCohortSourceRow> planRows =
        transaction
            .fetch(
                "SELECT * FROM "
                    + BUILD_COHORT
                    + " WHERE build_operation_id = ? ORDER BY source_row_ordinal",
                buildOperationId)
            .map(
                row -> {
                  String raw = requireText(row, "source_row_json");
                  String digest = requireText(row, "source_row_digest");
                  if (!sha256(raw.getBytes(StandardCharsets.UTF_8)).equals(digest)
                      || !intent.operationId().equals(row.get("source_operation_id", UUID.class))
                      || !intent.issuerId().equals(row.get("issuer_id", String.class))) {
                    throw conflict("Build plan cohort row lost its exact bootstrap source link");
                  }
                  return new IssuerCohortSourceRow(
                      new CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow(
                          requireLong(row, "source_row_ordinal"),
                          CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.valueOf(
                              requireText(row, "source_kind")),
                          requireText(row, "source_key"),
                          readBigInteger(row, "source_inventory_revision"),
                          raw,
                          digest));
                });
    requireSameCohort(intent.cohortRows(), planRows);
    if (!intent.cohortDigest().equals(header.get("cohort_digest", String.class))
        || planRows.size() != requireLong(header, "cohort_row_count")) {
      throw conflict("Build plan no longer retains the complete original cohort");
    }

    List<CanonicalGameplayIssuerPartitionBuildPlan.MemberWork> work =
        transaction
            .fetch(
                "SELECT * FROM "
                    + MEMBER_WORK
                    + " WHERE build_operation_id = ? ORDER BY work_ordinal",
                buildOperationId)
            .map(
                row -> {
                  if (!intent.operationId().equals(row.get("bootstrap_operation_id", UUID.class))
                      || !intent.issuerId().equals(row.get("issuer_id", String.class))) {
                    throw conflict("Member work lost its exact bootstrap plan linkage");
                  }
                  CanonicalGameplayIssuerPartitionBuildPlan.MemberWork memberWork =
                      toMemberWork(row);
                  Boolean counted = row.get("counts_toward_capacity", Boolean.class);
                  if (!Objects.equals(counted, memberWork.countsTowardCapacity())) {
                    throw conflict(
                        "Member work capacity classification differs from its lifecycle");
                  }
                  return memberWork;
                });
    if (work.size() != requireLong(header, "member_work_count")
        || work.size() != derived.reservations().size()) {
      throw conflict("Build plan member work is incomplete or contains unplanned reservations");
    }
    for (int index = 0; index < work.size(); index++) {
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork actual = work.get(index);
      PlannedReservation expected = derived.reservations().get(index);
      if (!sameWorkMapping(actual, expected, intent, index)) {
        throw conflict("Build plan member work differs from its exact frozen reservation source");
      }
    }

    List<CanonicalGameplayIssuerPartitionBuildPlan.Blocker> blockers =
        transaction
            .fetch(
                "SELECT * FROM "
                    + BUILD_BLOCKER
                    + " WHERE build_operation_id = ? ORDER BY blocker_ordinal",
                buildOperationId)
            .map(
                row ->
                    new CanonicalGameplayIssuerPartitionBuildPlan.Blocker(
                        requireLong(row, "blocker_ordinal"),
                        requireLong(row, "source_row_ordinal"),
                        requireText(row, "source_row_digest"),
                        CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.valueOf(
                            requireText(row, "blocker_code"))));
    List<CanonicalGameplayIssuerPartitionBuildPlan.Blocker> expectedBlockers =
        toBlockers(derived.blockers(), intent.cohortRows());
    if (!sameBlockers(blockers, expectedBlockers)
        || blockers.size() != requireLong(header, "blocker_count")) {
      throw conflict("Build plan blocker set changed or omitted an unresolved cohort source");
    }
    String expectedState = blockers.isEmpty() ? "PREPARED" : "BLOCKED";
    if (!expectedState.equals(header.get("plan_state", String.class))) {
      throw conflict("Build plan state does not reflect its complete blocker set");
    }
    requireUniquePreparationIdentities(buildOperationId, buildFence, intent, work);
    String computedDigest =
        digestPlan(
            buildOperationId,
            buildFence,
            intent,
            planRows.stream().map(IssuerCohortSourceRow::cohortRow).toList(),
            work,
            blockers);
    String storedDigest = requireText(header, "plan_digest");
    if (!computedDigest.equals(storedDigest)) {
      throw conflict(
          "Persisted immutable build-plan digest does not match its source and work set");
    }
    return new CanonicalGameplayIssuerPartitionBuildPlan(
        buildOperationId,
        buildFence,
        intent,
        planRows.stream().map(IssuerCohortSourceRow::cohortRow).toList(),
        work,
        blockers,
        storedDigest);
  }

  private static CanonicalGameplayIssuerPartitionBuildPlan.MemberWork toMemberWork(Record row) {
    return new CanonicalGameplayIssuerPartitionBuildPlan.MemberWork(
        requireLong(row, "work_ordinal"),
        requireNonNil(row.get("member_operation_id", UUID.class), "member_operation_id"),
        requireNonNil(
            row.get("member_preparation_fence_id", UUID.class), "member_preparation_fence_id"),
        requireLong(row, "reservation_source_row_ordinal"),
        requireText(row, "reservation_source_row_digest"),
        row.get("binding_source_row_ordinal", Long.class),
        row.get("binding_source_row_digest", String.class),
        requireNonNil(row.get("reservation_id", UUID.class), "reservation_id"),
        requireBytes(row, "binding_ref"),
        requireNonNil(row.get("transition_id", UUID.class), "transition_id"),
        requireText(row, "reservation_lifecycle"),
        requireNonNil(row.get("reservation_fence", UUID.class), "reservation_fence"),
        readBigInteger(row, "original_partition_id"),
        readBigInteger(row, "original_layout_version"),
        readBigInteger(row, "original_partition_count"),
        readBigInteger(row, "original_partition_capacity"),
        requireNonNil(
            row.get("original_issuer_coverage_operation_id", UUID.class),
            "original_issuer_coverage_operation_id"),
        readBigInteger(row, "original_issuer_coverage_operation_fence"),
        readBigInteger(row, "original_coverage_fence"),
        readBigInteger(row, "original_inventory_snapshot_revision"),
        readBigInteger(row, "planned_partition_id"),
        requireNonNil(
            row.get("planned_anchor_operation_id", UUID.class), "planned_anchor_operation_id"),
        readBigInteger(row, "planned_anchor_operation_fence"),
        readBigInteger(row, "planned_anchor_coverage_fence"),
        readBigInteger(row, "planned_anchor_inventory_snapshot_revision"));
  }

  private static boolean sameWorkMapping(
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork actual,
      PlannedReservation expected,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      int ordinal) {
    ReservationSource reservation = expected.reservation();
    return actual.workOrdinal() == ordinal
        && actual.reservationSourceRowOrdinal() == reservation.rowOrdinal()
        && actual.reservationSourceRowDigest().equals(reservation.sourceRowDigest())
        && Objects.equals(actual.bindingSourceRowOrdinal(), expected.bindingSourceRowOrdinal())
        && Objects.equals(actual.bindingSourceRowDigest(), expected.bindingSourceRowDigest())
        && actual.reservationId().equals(reservation.reservationId())
        && Arrays.equals(actual.bindingRef(), reservation.bindingRef())
        && actual.transitionId().equals(reservation.transitionId())
        && actual.reservationLifecycle().equals(reservation.lifecycle())
        && actual.reservationFence().equals(reservation.reservationFence())
        && actual.originalPartitionId().equals(reservation.partitionId())
        && actual.originalLayoutVersion().equals(reservation.layoutVersion())
        && actual.originalPartitionCount().equals(reservation.partitionCount())
        && actual.originalPartitionCapacity().equals(reservation.partitionCapacity())
        && actual.originalIssuerCoverageOperationId().equals(reservation.coverageOperationId())
        && actual
            .originalIssuerCoverageOperationFence()
            .equals(reservation.coverageOperationFence())
        && actual.originalCoverageFence().equals(reservation.coverageFence())
        && actual
            .originalInventorySnapshotRevision()
            .equals(reservation.inventorySnapshotRevision())
        && actual.plannedPartitionId().equals(expected.plannedPartitionId())
        && actual.plannedAnchorOperationId().equals(intent.operationId())
        && actual.plannedAnchorOperationFence().equals(intent.operationFence())
        && actual.plannedAnchorCoverageFence().equals(intent.coverageFence())
        && actual
            .plannedAnchorInventorySnapshotRevision()
            .equals(intent.inventorySnapshotRevision());
  }

  private static boolean sameBlockers(
      List<CanonicalGameplayIssuerPartitionBuildPlan.Blocker> left,
      List<CanonicalGameplayIssuerPartitionBuildPlan.Blocker> right) {
    if (left.size() != right.size()) return false;
    for (int index = 0; index < left.size(); index++) {
      var a = left.get(index);
      var b = right.get(index);
      if (a.blockerOrdinal() != b.blockerOrdinal()
          || a.sourceRowOrdinal() != b.sourceRowOrdinal()
          || !a.sourceRowDigest().equals(b.sourceRowDigest())
          || a.code() != b.code()) return false;
    }
    return true;
  }

  private static void requireUniquePreparationIdentities(
      UUID buildOperationId,
      UUID buildFence,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      List<CanonicalGameplayIssuerPartitionBuildPlan.MemberWork> work) {
    Set<UUID> originalIdentities = new HashSet<>();
    originalIdentities.add(intent.operationId());
    if (!originalIdentities.add(intent.requestId())) {
      throw conflict("Original bootstrap operation and request identities overlap");
    }
    for (var row : intent.cohortRows()) {
      try {
        UUID parsed = UUID.fromString(row.sourceKey());
        if (parsed.toString().equals(row.sourceKey())) originalIdentities.add(parsed);
      } catch (IllegalArgumentException ignored) {
        // Non-UUID source keys are retained byte-for-byte by the cohort artifact.
      }
    }
    for (var item : work) {
      originalIdentities.add(item.reservationId());
      originalIdentities.add(item.transitionId());
      originalIdentities.add(item.reservationFence());
      originalIdentities.add(item.originalIssuerCoverageOperationId());
    }

    Set<UUID> preparationIdentities = new HashSet<>();
    if (!isNewPreparationIdentity(buildOperationId, originalIdentities, preparationIdentities)
        || !isNewPreparationIdentity(buildFence, originalIdentities, preparationIdentities)) {
      throw conflict("Build preparation identity collides with original source provenance");
    }
    for (var item : work) {
      if (!isNewPreparationIdentity(
              item.memberOperationId(), originalIdentities, preparationIdentities)
          || !isNewPreparationIdentity(
              item.memberPreparationFenceId(), originalIdentities, preparationIdentities)) {
        throw conflict("Member preparation identity collides with an original source identity");
      }
      if (item.memberOperationId().equals(item.transitionId())
          || item.memberOperationId().equals(item.reservationId())
          || item.memberPreparationFenceId().equals(item.reservationFence())) {
        throw conflict("Member preparation identity reused original reservation provenance");
      }
    }
  }

  private static boolean isNewPreparationIdentity(
      UUID candidate, Set<UUID> originalIdentities, Set<UUID> preparationIdentities) {
    return !originalIdentities.contains(candidate) && preparationIdentities.add(candidate);
  }

  private static String digestPlan(
      UUID buildOperationId,
      UUID buildFence,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohort,
      List<CanonicalGameplayIssuerPartitionBuildPlan.MemberWork> work,
      List<CanonicalGameplayIssuerPartitionBuildPlan.Blocker> blockers) {
    MessageDigest digest = sha256Digest();
    frame(digest, buildOperationId.toString());
    frame(digest, buildFence.toString());
    frame(digest, intent.operationId().toString());
    frame(digest, intent.requestId().toString());
    frame(digest, intent.issuerId());
    frame(digest, intent.controlEpoch().toString());
    frame(digest, intent.operationFence().toString());
    frame(digest, intent.coverageFence().toString());
    frame(digest, intent.inventorySnapshotRevision().toString());
    var layout = intent.layoutDescriptor();
    frame(digest, layout.layoutVersion().toString());
    frame(digest, layout.partitionCount().toString());
    frame(digest, layout.partitionCapacity().toString());
    frame(digest, layout.mappingIdentity());
    frame(digest, layout.exactDescriptorBytes());
    frame(digest, layout.descriptorDigest());
    var source = intent.sourceCheckpoint();
    frame(digest, source.issuerId());
    frame(digest, source.issuerAuthGeneration().toString());
    frame(digest, source.exactCheckpointBytes());
    frame(digest, source.checkpointDigest());
    nullableFrame(digest, source.latestIssuerEventBytes());
    nullableFrame(digest, source.latestIssuerEventDigest());
    var legacy = intent.legacyDisposition();
    frame(digest, legacy.cohortId().toString());
    frame(digest, legacy.ownerOperationId().toString());
    frame(digest, legacy.legacyWriterFence().toString());
    frame(digest, legacy.sourceSnapshotRevision().toString());
    frame(digest, legacy.canonicalInventoryRevision().toString());
    frame(digest, legacy.namespaceIndexReadbackRevision().toString());
    frame(digest, legacy.evidenceDigest());
    frame(digest, blockers.isEmpty() ? "PREPARED" : "BLOCKED");
    frame(
        digest,
        CanonicalGameplayIssuerPartitionBuildPlan.CoverageGenerationState.NOT_YET_ISSUED.name());
    for (var row : cohort) {
      frame(digest, Long.toString(row.rowOrdinal()));
      frame(digest, row.kind().name());
      frame(digest, row.sourceKey());
      frame(digest, row.sourceInventoryRevision().toString());
      frame(digest, row.sourceRowJson());
      frame(digest, row.sourceRowDigest());
    }
    for (var item : work) {
      frame(digest, Long.toString(item.workOrdinal()));
      frame(digest, item.memberOperationId().toString());
      frame(digest, item.memberPreparationFenceId().toString());
      frame(digest, Long.toString(item.reservationSourceRowOrdinal()));
      frame(digest, item.reservationSourceRowDigest());
      nullableFrame(
          digest,
          item.bindingSourceRowOrdinal() == null
              ? null
              : item.bindingSourceRowOrdinal().toString());
      nullableFrame(digest, item.bindingSourceRowDigest());
      frame(digest, item.reservationId().toString());
      frame(digest, item.bindingRef());
      frame(digest, item.transitionId().toString());
      frame(digest, item.reservationLifecycle());
      frame(digest, item.reservationFence().toString());
      frame(digest, item.originalPartitionId().toString());
      frame(digest, item.originalLayoutVersion().toString());
      frame(digest, item.originalPartitionCount().toString());
      frame(digest, item.originalPartitionCapacity().toString());
      frame(digest, item.originalIssuerCoverageOperationId().toString());
      frame(digest, item.originalIssuerCoverageOperationFence().toString());
      frame(digest, item.originalCoverageFence().toString());
      frame(digest, item.originalInventorySnapshotRevision().toString());
      frame(digest, item.plannedPartitionId().toString());
      frame(digest, item.plannedAnchorOperationId().toString());
      frame(digest, item.plannedAnchorOperationFence().toString());
      frame(digest, item.plannedAnchorCoverageFence().toString());
      frame(digest, item.plannedAnchorInventorySnapshotRevision().toString());
      frame(digest, Boolean.toString(item.countsTowardCapacity()));
    }
    for (var blocker : blockers) {
      frame(digest, Long.toString(blocker.blockerOrdinal()));
      frame(digest, Long.toString(blocker.sourceRowOrdinal()));
      frame(digest, blocker.sourceRowDigest());
      frame(digest, blocker.code().name());
    }
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }

  private static void frame(MessageDigest digest, String value) {
    frame(digest, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void frame(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }

  private static void nullableFrame(MessageDigest digest, String value) {
    if (value == null) {
      frame(digest, new byte[] {0});
    } else {
      frame(digest, new byte[] {1});
      frame(digest, value);
    }
  }

  private static void nullableFrame(MessageDigest digest, byte[] value) {
    if (value == null) {
      frame(digest, new byte[] {0});
    } else {
      frame(digest, new byte[] {1});
      frame(digest, value);
    }
  }

  private static String digestCohortRows(
      List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> rows) {
    MessageDigest digest = sha256Digest();
    rows.stream()
        .sorted(
            Comparator.comparingLong(
                CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::rowOrdinal))
        .forEach(
            row -> {
              frame(digest, Long.toString(row.rowOrdinal()));
              frame(digest, row.kind().name());
              frame(digest, row.sourceKey());
              frame(digest, row.sourceInventoryRevision().toString());
              frame(digest, row.sourceRowDigest());
            });
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }

  private static void requireNoFutureRows(DSLContext transaction, BigInteger revision) {
    Record row =
        Objects.requireNonNull(
            transaction.fetchOne(
                FUTURE_REVISION_SQL,
                decimal(revision),
                decimal(revision),
                decimal(revision),
                decimal(revision),
                decimal(revision),
                decimal(revision)),
            "future inventory query must return one row");
    if (Boolean.TRUE.equals(row.get("has_future_revision", Boolean.class))) {
      throw conflict("Current issuer inventory has durable rows beyond its locked clock");
    }
  }

  private static BigInteger lockInventoryRevision(DSLContext transaction) {
    Record row =
        transaction.fetchOne(
            "SELECT inventory_revision FROM "
                + INVENTORY_CLOCK
                + " WHERE singleton_id = 1 FOR UPDATE");
    if (row == null) throw conflict("Canonical binding inventory clock row is missing");
    return readBigInteger(row, "inventory_revision");
  }

  private static Record lockControl(DSLContext transaction, String issuerId) {
    return transaction.fetchOne(
        "SELECT * FROM " + BOOTSTRAP_CONTROL + " WHERE issuer_id = ? FOR UPDATE", issuerId);
  }

  private static Record lockIntent(DSLContext transaction, UUID operationId) {
    return transaction.fetchOne(
        "SELECT * FROM "
            + BOOTSTRAP_INTENT
            + " WHERE operation_id = ? AND issuer_id = ? FOR UPDATE",
        operationId,
        ISSUER);
  }

  private static void requireValidSourceEvent(
      String issuerId, BigInteger generation, byte[] eventBytes) {
    final String eventJson;
    try {
      eventJson =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(eventBytes))
              .toString();
    } catch (CharacterCodingException malformed) {
      throw conflict("Persisted Account issuer source event is not exact UTF-8");
    }
    final AccountAuthoritySourceEventV1Codec.SourceEvent parsed;
    try {
      parsed = AccountAuthoritySourceEventV1Codec.verify(eventJson);
    } catch (RuntimeException malformed) {
      throw conflict("Persisted Account issuer source event is not codec-verified");
    }
    if (!(parsed instanceof IssuerEvent event)
        || !event.issuerId().equals(issuerId)
        || !new BigInteger(event.issuerAuthGeneration()).equals(generation)
        || !Arrays.equals(event.canonicalJsonUtf8(), eventBytes)) {
      throw conflict("Persisted Account issuer source event differs from its exact checkpoint");
    }
  }

  private static JsonNode parseObject(String raw) {
    try {
      JsonNode result = JSON.readTree(raw);
      if (result == null || !result.isObject())
        throw conflict("Cohort source row is not a JSON object");
      return result;
    } catch (JsonProcessingException malformed) {
      throw conflict("Cohort source row JSON is malformed");
    }
  }

  private static String text(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || value.isNull() || !value.isValueNode()) {
      throw conflict("Cohort source row is missing scalar field " + field);
    }
    return value.asText();
  }

  private static BigInteger integer(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || value.isNull() || (!value.isNumber() && !value.isTextual())) {
      throw conflict("Cohort source row is missing integer field " + field);
    }
    try {
      return value.isNumber()
          ? value.decimalValue().toBigIntegerExact()
          : new BigInteger(value.textValue());
    } catch (RuntimeException malformed) {
      throw conflict("Cohort source row integer field is malformed: " + field);
    }
  }

  private static UUID uuid(JsonNode json, String field) {
    String value = text(json, field);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(NIL_UUID)) {
        throw new IllegalArgumentException("noncanonical UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw conflict("Cohort source row UUID field is malformed: " + field);
    }
  }

  private static byte[] bytea(JsonNode json, String field) {
    String value = text(json, field);
    if (!value.startsWith("\\x") || (value.length() & 1) != 0) {
      throw conflict("Cohort source row bytea field is not PostgreSQL hex text: " + field);
    }
    try {
      return HexFormat.of().parseHex(value.substring(2));
    } catch (IllegalArgumentException malformed) {
      throw conflict("Cohort source row bytea field is malformed: " + field);
    }
  }

  private static BigInteger readBigInteger(Record row, String field) {
    BigDecimal value = row.get(field, BigDecimal.class);
    if (value == null) throw conflict("Persisted field is missing: " + field);
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Persisted numeric field is not an integer: " + field);
    }
  }

  private static long requireLong(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null) throw conflict("Persisted field is missing: " + field);
    return value;
  }

  private static String requireText(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw conflict("Persisted field is missing: " + field);
    return value;
  }

  private static byte[] requireBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0)
      throw conflict("Persisted byte field is missing: " + field);
    return value;
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
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

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw conflict("Issuer partition build owner cannot join an ambient transaction");
    }
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(NIL_UUID)) throw conflict(name + " must not be nil");
    return value;
  }

  private static void requireNonNegative(BigInteger value, String name) {
    if (value.signum() < 0) throw conflict(name + " must be non-negative");
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  private record IssuerCohortSourceRow(
      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow cohortRow) {}

  private record PlanBlocker(
      long sourceRowOrdinal, CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode code) {}

  private record DerivedPlan(List<PlannedReservation> reservations, List<PlanBlocker> blockers) {}

  private record PlannedReservation(
      ReservationSource reservation,
      Long bindingSourceRowOrdinal,
      String bindingSourceRowDigest,
      BigInteger plannedPartitionId) {}

  private record ReservationSource(
      long rowOrdinal,
      String sourceRowDigest,
      UUID reservationId,
      byte[] bindingRef,
      UUID transitionId,
      UUID reservationFence,
      String lifecycle,
      BigInteger partitionId,
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      UUID coverageOperationId,
      BigInteger coverageOperationFence,
      BigInteger coverageFence,
      BigInteger inventorySnapshotRevision,
      BigInteger bindingGeneration,
      String accountId,
      String tenantId,
      String namespaceId,
      String characterId,
      String playableStateScope,
      String gameInstanceId,
      BigInteger runtimeGameInstanceId,
      String sessionId,
      String regionId,
      BigInteger regionEpoch,
      String issuerId,
      BigInteger issuerAuthGeneration) {
    ReservationSource {
      bindingRef = bindingRef.clone();
    }

    @Override
    public byte[] bindingRef() {
      return bindingRef.clone();
    }
  }

  private record BindingSource(
      long rowOrdinal,
      String sourceRowDigest,
      UUID reservationId,
      byte[] bindingRef,
      UUID transitionId,
      String accountId,
      String tenantId,
      String namespaceId,
      String characterId,
      String playableStateScope,
      String gameInstanceId,
      BigInteger runtimeGameInstanceId,
      String sessionId,
      String regionId,
      BigInteger regionEpoch,
      String issuerId,
      BigInteger issuerAuthGeneration,
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      BigInteger partitionId,
      String lifecycle,
      BigInteger bindingGeneration) {
    BindingSource {
      bindingRef = bindingRef.clone();
    }

    @Override
    public byte[] bindingRef() {
      return bindingRef.clone();
    }

    boolean matches(ReservationSource reservation) {
      return reservationId.equals(reservation.reservationId())
          && Arrays.equals(bindingRef, reservation.bindingRef())
          && transitionId.equals(reservation.transitionId())
          && accountId.equals(reservation.accountId())
          && tenantId.equals(reservation.tenantId())
          && namespaceId.equals(reservation.namespaceId())
          && characterId.equals(reservation.characterId())
          && playableStateScope.equals(reservation.playableStateScope())
          && gameInstanceId.equals(reservation.gameInstanceId())
          && runtimeGameInstanceId.equals(reservation.runtimeGameInstanceId())
          && sessionId.equals(reservation.sessionId())
          && regionId.equals(reservation.regionId())
          && regionEpoch.equals(reservation.regionEpoch())
          && issuerId.equals(reservation.issuerId())
          && issuerAuthGeneration.equals(reservation.issuerAuthGeneration())
          && bindingGeneration.equals(reservation.bindingGeneration())
          && layoutVersion.equals(reservation.layoutVersion())
          && partitionCount.equals(reservation.partitionCount())
          && partitionCapacity.equals(reservation.partitionCapacity())
          && partitionId.equals(reservation.partitionId());
    }
  }

  record PreparedMemberWork(
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work,
      CanonicalIssuerPartitionReservation originalReservation,
      CanonicalGameplayBindingInventoryEntry.Lifecycle bindingLifecycle) {
    PreparedMemberWork {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(work, "work");
      Objects.requireNonNull(originalReservation, "originalReservation");
    }
  }
}
