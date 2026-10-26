package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL owner for one immutable, source-linked physical terminal release successor. */
final class CanonicalGameplayIssuerBindingLocalReleaseOperationRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String RELEASE =
      "game_session_canonical_issuer_binding_local_release_operation";
  private static final String RESERVATION = "game_session_canonical_issuer_partition_reservation";
  private static final String INVENTORY = "game_session_canonical_gameplay_binding_inventory";
  private static final String TRANSITION = "game_session_canonical_binding_transition";
  private static final String ISSUER_OBLIGATION =
      "game_session_canonical_binding_issuer_index_obligation";
  private static final String ACCOUNT_OBLIGATION =
      "game_session_canonical_binding_account_index_obligation";
  private static final String REGION_OBLIGATION =
      "game_session_canonical_binding_region_bridge_obligation";
  private static final String INVENTORY_CLOCK = "game_session_canonical_binding_inventory_clock";

  private final DSLContext dsl;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentIssuerStateSource;
  private final CanonicalGameplayBindingInventoryRepository inventoryRepository;
  private final CanonicalGameplayIssuerBindingLocalOperationRepository localOperationRepository;

  CanonicalGameplayIssuerBindingLocalReleaseOperationRepository(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentIssuerStateSource) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.currentIssuerStateSource =
        Objects.requireNonNull(currentIssuerStateSource, "currentIssuerStateSource");
    this.inventoryRepository = new CanonicalGameplayBindingInventoryRepository(dsl);
    this.localOperationRepository =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, currentIssuerStateSource);
  }

  /** Creates one SQL operation only from the current durable Account-aborted source. */
  CanonicalGameplayIssuerBindingLocalReleaseOperation prepareOrResume(UUID transitionId) {
    Objects.requireNonNull(transitionId, "transitionId");
    requireNoAmbientTransaction();
    var sourceBefore =
        inventoryRepository.readRetainedDefinitiveFirstNewAccountAbortSource(transitionId);
    requireAbortedSource(sourceBefore);
    var issuerStateBefore = readCurrentIssuerState();
    var original = localOperationRepository.readOriginalForIssuerRelease(sourceBefore);
    var operation =
        dsl.transactionResult(
            configuration -> {
              DSLContext transaction = org.jooq.impl.DSL.using(configuration);
              BigInteger currentRevision =
                  CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              var source =
                  CanonicalGameplayBindingInventoryRepository
                      .readRetainedDefinitiveFirstNewAccountAbortSourceInTransaction(
                          transaction, transitionId, true);
              requireSameAbortSource(sourceBefore, source);
              Record existing =
                  transaction.fetchOne(
                      "SELECT * FROM " + RELEASE + " WHERE transition_id = ? FOR UPDATE",
                      transitionId);
              if (existing != null) return readOperation(transaction, existing, source, original);
              if (source.reservation().lifecycle()
                      != CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING
                  || !sameOperationSource(original, source)) {
                throw conflict("Issuer release requires its exact pending local registration");
              }
              BigInteger priorRevision = source.transition().inventoryRevision();
              if (currentRevision.compareTo(priorRevision) < 0) {
                throw conflict(
                    "Inventory clock regressed behind the retained Account-abort source");
              }
              BigInteger sourceRevision = currentRevision.add(BigInteger.ONE);
              requireCounterLimit(sourceRevision, "releasePreparationInventoryRevision");
              advanceAbortSourceRevision(
                  transaction, source, priorRevision, sourceRevision, currentRevision);
              source =
                  CanonicalGameplayBindingInventoryRepository
                      .readRetainedDefinitiveFirstNewAccountAbortSourceInTransaction(
                          transaction, transitionId, false);
              var counters =
                  CanonicalGameplayIssuerBindingLocalOperationRepository.allocateLocalCounters(
                      transaction,
                      original.anchor(),
                      withLifecycleAndRevision(
                          original.reservation(),
                          CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING,
                          sourceRevision));
              UUID releaseId = freshNonNilUuid();
              var releasedValue =
                  createReleasedValue(
                      original.memberValue(),
                      sourceRevision,
                      releaseId,
                      counters.operationFence(),
                      counters.localMemberGeneration());
              byte[] releasedBytes = CanonicalGameplayIssuerMemberV2Codec.encode(releasedValue);
              byte[] originalBytes = original.exactMemberValueBytes();
              insertRelease(
                  transaction,
                  source,
                  original,
                  releaseId,
                  counters.operationFence(),
                  counters.localMemberGeneration(),
                  sourceRevision,
                  originalBytes,
                  releasedBytes);
              Record saved =
                  transaction.fetchOne(
                      "SELECT * FROM " + RELEASE + " WHERE operation_id = ? FOR UPDATE", releaseId);
              if (saved == null) throw conflict("Issuer release operation was not retained");
              byte[] mutationIntent =
                  CanonicalGameplayIssuerPartitionMutationRepository.encodeIntendedChange(
                      CanonicalGameplayIssuerPartitionMutation.Kind.RELEASE,
                      releaseId,
                      original.reservation().partitionId(),
                      original.operationId().toString().getBytes(StandardCharsets.US_ASCII),
                      source
                          .reservation()
                          .reservationId()
                          .toString()
                          .getBytes(StandardCharsets.US_ASCII),
                      source.transition().bindingRef().bytes(),
                      originalBytes,
                      releasedBytes,
                      sourceRevision.toString().getBytes(StandardCharsets.US_ASCII));
              CanonicalGameplayIssuerPartitionMutationRepository.prepareInTransaction(
                  transaction,
                  CanonicalGameplayIssuerPartitionMutation.Kind.RELEASE,
                  releaseId,
                  original.reservation().partitionId(),
                  mutationIntent);
              return readOperation(transaction, saved, source, original);
            });
    requireSameCurrentIssuerState(issuerStateBefore, readCurrentIssuerState());
    requireSameAbortIdentity(
        sourceBefore,
        inventoryRepository.readRetainedDefinitiveFirstNewAccountAbortSource(transitionId));
    return operation;
  }

  /** Reads only an existing, committed release receipt for a physical-retirement caller. */
  CanonicalGameplayIssuerBindingLocalReleaseOperation readCommittedRelease(UUID transitionId) {
    Objects.requireNonNull(transitionId, "transitionId");
    requireNoAmbientTransaction();
    CanonicalGameplayAdmissionAbortSource sourceBefore =
        inventoryRepository.readRetainedDefinitiveFirstNewAccountAbortSource(transitionId);
    requireAbortedSource(sourceBefore);
    var issuerStateBefore = readCurrentIssuerState();
    CanonicalGameplayIssuerBindingLocalOperation original =
        localOperationRepository.readOriginalForIssuerRelease(sourceBefore);
    CanonicalGameplayIssuerBindingLocalReleaseOperation committed =
        dsl.transactionResult(
            configuration -> {
              DSLContext transaction = org.jooq.impl.DSL.using(configuration);
              transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
              CanonicalGameplayAdmissionAbortSource source =
                  CanonicalGameplayBindingInventoryRepository
                      .readRetainedDefinitiveFirstNewAccountAbortSourceInTransaction(
                          transaction, transitionId, false);
              requireSameAbortSource(sourceBefore, source);
              Record existing =
                  transaction.fetchOne(
                      "SELECT * FROM " + RELEASE + " WHERE transition_id = ?", transitionId);
              if (existing == null) {
                throw conflict(
                    "Physical retirement requires an existing committed release receipt");
              }
              CanonicalGameplayIssuerBindingLocalReleaseOperation operation =
                  readOperation(transaction, existing, source, original);
              if (operation.status()
                  != CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED) {
                throw conflict(
                    "Physical retirement requires a committed original RELEASED receipt");
              }
              return operation;
            });
    requireSameCurrentIssuerState(issuerStateBefore, readCurrentIssuerState());
    requireSameAbortSource(
        committed.abortSource(),
        inventoryRepository.readRetainedDefinitiveFirstNewAccountAbortSource(transitionId));
    return committed;
  }

  /** Completes only the exact private physical-owner readback and returns its durable receipt. */
  CanonicalGameplayIssuerBindingLocalReleaseOperation completeWithReadback(
      CanonicalGameplayIssuerBindingLocalReleaseOperation expected,
      CanonicalGameplayIssuerBindingLocalReleaseRedisOwner.Readback readback) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(readback, "readback");
    requireNoAmbientTransaction();
    if (!expected.operationId().equals(readback.operationId())) {
      throw conflict("Issuer release readback belongs to a different SQL operation");
    }
    byte[] readbackBytes = readback.exactPhysicalFieldSetBytes();
    String readbackDigest = CanonicalGameplayIssuerMemberV2Codec.sha256(readbackBytes);
    var issuerStateBefore = readCurrentIssuerState();
    var completed =
        dsl.transactionResult(
            configuration -> {
              DSLContext transaction = org.jooq.impl.DSL.using(configuration);
              BigInteger currentRevision =
                  CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              UUID transitionId = expected.abortSource().transition().transitionId();
              var source =
                  CanonicalGameplayBindingInventoryRepository
                      .readRetainedDefinitiveFirstNewAccountAbortSourceInTransaction(
                          transaction, transitionId, true);
              requireExactReleaseSource(expected, source);
              Record releaseRow =
                  transaction.fetchOne(
                      "SELECT * FROM " + RELEASE + " WHERE operation_id = ? FOR UPDATE",
                      expected.operationId());
              if (releaseRow == null) throw conflict("SQL issuer release operation disappeared");
              var current =
                  readOperation(transaction, releaseRow, source, expected.originalOperation());
              requireSameOperation(expected, current);
              if (current.status()
                  == CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED) {
                verifyReadbackMatchesOperation(current, readbackBytes, readbackDigest);
                CanonicalGameplayIssuerPartitionMutationRepository.completeInTransaction(
                    transaction, current.physicalMutation(), readbackBytes);
                return current;
              }
              if (source.reservation().lifecycle()
                  != CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING) {
                throw conflict(
                    "Pending issuer release no longer has its exact RELEASE_PENDING source");
              }
              if (currentRevision.compareTo(current.sourceInventoryRevision()) < 0) {
                throw conflict(
                    "Inventory clock regressed behind the durable issuer release preparation");
              }
              verifyReadbackMatchesOperation(current, readbackBytes, readbackDigest);
              CanonicalGameplayIssuerPartitionMutationRepository.completeInTransaction(
                  transaction, current.physicalMutation(), readbackBytes);
              BigInteger completionRevision = currentRevision.add(BigInteger.ONE);
              requireCounterLimit(completionRevision, "completionInventoryRevision");
              requireOne(
                  transaction.execute(
                      "UPDATE "
                          + RELEASE
                          + " SET status = 'RELEASED', physical_readback_field_set_bytes = ?,"
                          + " physical_readback_field_set_digest = ?, completion_inventory_revision = ?,"
                          + " completed_at = CURRENT_TIMESTAMP WHERE operation_id = ?"
                          + " AND status = 'PENDING_PHYSICAL_PROOF' AND source_inventory_revision = ?",
                      readbackBytes,
                      readbackDigest,
                      decimal(completionRevision),
                      current.operationId(),
                      decimal(current.sourceInventoryRevision())),
                  "Issuer release completion lost its guarded SQL operation compare-and-set");
              requireOne(
                  transaction.execute(
                      "UPDATE "
                          + ISSUER_OBLIGATION
                          + " SET status = 'RELEASED', local_release_operation_id = ?, inventory_revision = ?"
                          + " WHERE transition_id = ? AND binding_ref = ? AND reservation_id = ?"
                          + " AND status = 'REQUIRED' AND local_release_operation_id IS NULL"
                          + " AND inventory_revision = ?",
                      current.operationId(),
                      decimal(completionRevision),
                      transitionId,
                      source.transition().bindingRef().bytes(),
                      source.reservation().reservationId(),
                      decimal(current.sourceInventoryRevision())),
                  "Physical issuer release lost its exact local V29 obligation compare-and-set");
              requireOne(
                  transaction.execute(
                      "UPDATE "
                          + RESERVATION
                          + " SET lifecycle = 'RELEASED', inventory_revision = ?"
                          + " WHERE reservation_id = ? AND transition_id = ? AND lifecycle = 'RELEASE_PENDING'"
                          + " AND inventory_revision = ? AND binding_ref = ? AND binding_generation = ?"
                          + " AND reservation_fence = ? AND issuer_coverage_operation_id = ?"
                          + " AND issuer_coverage_operation_fence = ? AND coverage_fence = ?"
                          + " AND inventory_snapshot_revision = ? AND account_id = ? AND tenant_id = ?"
                          + " AND playable_state_namespace_id = ? AND character_id = ?"
                          + " AND game_instance_id = ? AND runtime_game_instance_id = ? AND session_id = ?"
                          + " AND region_id = ? AND region_epoch = ? AND issuer_id = ? AND issuer_auth_generation = ?",
                      decimal(completionRevision),
                      source.reservation().reservationId(),
                      transitionId,
                      decimal(current.sourceInventoryRevision()),
                      source.transition().bindingRef().bytes(),
                      decimal(source.transition().bindingGeneration()),
                      source.reservation().reservationFence(),
                      source.reservation().issuerCoverageOperationId(),
                      decimal(source.reservation().issuerCoverageOperationFence()),
                      decimal(source.reservation().coverageFence()),
                      decimal(source.reservation().inventorySnapshotRevision()),
                      source.candidate().identity().accountId(),
                      source.candidate().identity().tenantId(),
                      source.candidate().identity().playableStateNamespaceId(),
                      source.candidate().identity().characterId(),
                      source.candidate().identity().gameInstanceId(),
                      source.candidate().identity().runtimeGameInstanceId(),
                      source.candidate().identity().sessionId(),
                      source.candidate().identity().regionId(),
                      decimal(source.candidate().identity().regionEpoch()),
                      source.candidate().identity().issuerId(),
                      decimal(source.candidate().identity().issuerAuthGeneration())),
                  "Issuer release lost its exact original reservation compare-and-set");
              requireOne(
                  transaction.execute(
                      "UPDATE "
                          + INVENTORY
                          + " SET inventory_revision = ?"
                          + " WHERE binding_ref = ? AND transition_id = ?"
                          + " AND lifecycle = 'TERMINAL_UNRESOLVED' AND inventory_revision = ?"
                          + " AND binding_generation = ? AND issuer_reservation_id = ?",
                      decimal(completionRevision),
                      source.transition().bindingRef().bytes(),
                      transitionId,
                      decimal(current.sourceInventoryRevision()),
                      decimal(source.transition().bindingGeneration()),
                      source.reservation().reservationId()),
                  "Issuer release lost its retained unresolved candidate compare-and-set");
              requireOne(
                  transaction.execute(
                      "UPDATE "
                          + TRANSITION
                          + " SET inventory_revision = ?"
                          + " WHERE transition_id = ? AND status = 'ABORTED'"
                          + " AND admission_orphan_cleanup_id = ? AND inventory_revision = ?"
                          + " AND candidate_binding_ref = ? AND candidate_binding_generation = ?"
                          + " AND issuer_reservation_id = ?",
                      decimal(completionRevision),
                      transitionId,
                      source.orphanCleanupId(),
                      decimal(current.sourceInventoryRevision()),
                      source.transition().bindingRef().bytes(),
                      decimal(source.transition().bindingGeneration()),
                      source.reservation().reservationId()),
                  "Issuer release lost its immutable Account-abort source revision compare-and-set");
              requireOne(
                  transaction.execute(
                      "UPDATE "
                          + ACCOUNT_OBLIGATION
                          + " SET inventory_revision = ?"
                          + " WHERE transition_id = ? AND inventory_revision = ?",
                      decimal(completionRevision),
                      transitionId,
                      decimal(current.sourceInventoryRevision())),
                  "Issuer release lost its retained Account obligation revision compare-and-set");
              requireOne(
                  transaction.execute(
                      "UPDATE "
                          + REGION_OBLIGATION
                          + " SET inventory_revision = ?"
                          + " WHERE transition_id = ? AND inventory_revision = ?",
                      decimal(completionRevision),
                      transitionId,
                      decimal(current.sourceInventoryRevision())),
                  "Issuer release lost its retained region obligation revision compare-and-set");
              requireOne(
                  transaction.execute(
                      "UPDATE "
                          + INVENTORY_CLOCK
                          + " SET inventory_revision = ?"
                          + " WHERE singleton_id = 1 AND inventory_revision = ?",
                      decimal(completionRevision),
                      decimal(currentRevision)),
                  "Issuer release lost the current inventory-clock compare-and-set");
              var completedSource =
                  CanonicalGameplayBindingInventoryRepository
                      .readRetainedDefinitiveFirstNewAccountAbortSourceInTransaction(
                          transaction, transitionId, false);
              Record completedRow =
                  transaction.fetchOne(
                      "SELECT * FROM " + RELEASE + " WHERE operation_id = ?",
                      expected.operationId());
              if (completedRow == null)
                throw conflict("Released issuer operation disappeared after completion");
              return readOperation(
                  transaction, completedRow, completedSource, expected.originalOperation());
            });
    requireSameCurrentIssuerState(issuerStateBefore, readCurrentIssuerState());
    CanonicalGameplayAdmissionAbortSource sourceAfter =
        inventoryRepository.readRetainedDefinitiveFirstNewAccountAbortSource(
            completed.abortSource().transition().transitionId());
    // The decision is reparsed on each read and deliberately has no object-identity equality.
    // Compare the complete durable value source and its exact original lease/target instead.
    requireSameAbortSource(completed.abortSource(), sourceAfter);
    return completed;
  }

  private CanonicalGameplayIssuerBindingLocalReleaseOperation readOperation(
      DSLContext transaction,
      Record row,
      CanonicalGameplayAdmissionAbortSource source,
      CanonicalGameplayIssuerBindingLocalOperation original) {
    if (!ISSUER.equals(text(row, "issuer_id"))
        || !uuid(row, "transition_id").equals(source.transition().transitionId())
        || !uuid(row, "orphan_cleanup_id").equals(source.orphanCleanupId())
        || !uuid(row, "reservation_id").equals(source.reservation().reservationId())
        || !uuid(row, "original_local_operation_id").equals(original.operationId())
        || !Arrays.equals(bytes(row, "binding_ref"), source.transition().bindingRef().bytes())
        || !positive(row, "binding_generation").equals(source.transition().bindingGeneration())
        || !nonnegative(row, "partition_id").equals(source.reservation().partitionId())
        || !uuid(row, "original_reservation_fence")
            .equals(original.reservation().reservationFence())
        || !uuid(row, "original_issuer_coverage_operation_id")
            .equals(original.reservation().issuerCoverageOperationId())
        || !positive(row, "original_issuer_coverage_operation_fence")
            .equals(original.reservation().issuerCoverageOperationFence())
        || !positive(row, "original_coverage_fence").equals(original.reservation().coverageFence())
        || !positive(row, "original_inventory_snapshot_revision")
            .equals(original.reservation().inventorySnapshotRevision())
        || !positive(row, "original_reservation_inventory_revision")
            .equals(original.reservation().inventoryRevision())
        || !positive(row, "original_local_operation_fence").equals(original.operationFence())
        || !positive(row, "original_local_member_generation")
            .equals(original.localMemberGeneration())
        || !uuid(row, "anchor_issuer_coverage_operation_id")
            .equals(original.anchor().issuerCoverageOperationId())
        || !positive(row, "anchor_operation_fence").equals(original.anchor().operationFence())
        || !positive(row, "anchor_coverage_fence").equals(original.anchor().coverageFence())
        || !positive(row, "anchor_inventory_snapshot_revision")
            .equals(original.anchor().issuerInventorySnapshotRevision())
        || !Arrays.equals(
            bytes(row, "original_member_value_bytes"), original.exactMemberValueBytes())
        || !text(row, "original_member_value_digest").equals(original.memberValueDigest())
        || !text(row, "original_member_value_digest")
            .equals(
                CanonicalGameplayIssuerMemberV2Codec.sha256(
                    bytes(row, "original_member_value_bytes")))
        || !text(row, "released_member_value_digest")
            .equals(
                CanonicalGameplayIssuerMemberV2Codec.sha256(
                    bytes(row, "released_member_value_bytes")))) {
      throw conflict(
          "Stored issuer release differs from its immutable original registration or abort source");
    }
    UUID operationId = uuid(row, "operation_id");
    BigInteger sourceRevision = positive(row, "source_inventory_revision");
    CanonicalGameplayIssuerMemberV2Value releasedValue;
    try {
      releasedValue =
          CanonicalGameplayIssuerMemberV2Codec.decode(bytes(row, "released_member_value_bytes"));
    } catch (RuntimeException malformed) {
      throw conflict("Stored issuer release postimage is not canonical schema v2");
    }
    CanonicalGameplayIssuerBindingLocalReleaseOperation.Status status;
    try {
      status =
          CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.valueOf(text(row, "status"));
    } catch (RuntimeException malformed) {
      throw conflict("Stored issuer release status is malformed");
    }
    byte[] physicalReadback = optionalBytes(row, "physical_readback_field_set_bytes");
    String physicalDigest = row.get("physical_readback_field_set_digest", String.class);
    BigInteger completionRevision = nullablePositive(row, "completion_inventory_revision");
    CanonicalGameplayIssuerPartitionMutation physicalMutation =
        CanonicalGameplayIssuerPartitionMutationRepository.readInTransaction(
            transaction,
            CanonicalGameplayIssuerPartitionMutation.Kind.RELEASE,
            operationId,
            original.reservation().partitionId());
    byte[] expectedMutationIntent =
        CanonicalGameplayIssuerPartitionMutationRepository.encodeIntendedChange(
            CanonicalGameplayIssuerPartitionMutation.Kind.RELEASE,
            operationId,
            original.reservation().partitionId(),
            original.operationId().toString().getBytes(StandardCharsets.US_ASCII),
            source.reservation().reservationId().toString().getBytes(StandardCharsets.US_ASCII),
            source.transition().bindingRef().bytes(),
            bytes(row, "original_member_value_bytes"),
            bytes(row, "released_member_value_bytes"),
            sourceRevision.toString().getBytes(StandardCharsets.US_ASCII));
    if (!Arrays.equals(expectedMutationIntent, physicalMutation.exactIntendedChangeBytes())) {
      throw conflict(
          "Stored release revision intent differs from its exact original release tuple");
    }
    if ((status == CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.PENDING_PHYSICAL_PROOF
            && (source.reservation().lifecycle()
                    != CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING
                || !source.reservation().inventoryRevision().equals(sourceRevision)))
        || (status == CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED
            && (source.reservation().lifecycle()
                    != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED
                || !Objects.equals(source.reservation().inventoryRevision(), completionRevision)
                || completionRevision == null
                || completionRevision.compareTo(sourceRevision) <= 0))) {
      throw conflict("Stored issuer release phase differs from its current V29 reservation");
    }
    try {
      return new CanonicalGameplayIssuerBindingLocalReleaseOperation(
          source,
          original,
          operationId,
          positive(row, "release_operation_fence"),
          positive(row, "release_local_member_generation"),
          sourceRevision,
          completionRevision,
          releasedValue,
          bytes(row, "original_member_value_bytes"),
          bytes(row, "released_member_value_bytes"),
          physicalReadback,
          physicalDigest,
          physicalMutation,
          status);
    } catch (RuntimeException malformed) {
      throw conflict("Stored SQL issuer release operation is malformed");
    }
  }

  /** Read-only physical postimage used by ordinary partition readers after verified completion. */
  static ReleaseProjection readProjection(
      DSLContext transaction,
      Record originalOperationRow,
      CanonicalGameplayIssuerMemberV2Value originalValue,
      BigInteger currentInventoryRevision,
      String currentTransitionStatus,
      String currentCandidateLifecycle,
      String currentReservationLifecycle,
      String currentIssuerObligationStatus,
      UUID currentReleaseOperationId) {
    UUID originalOperationId = uuid(originalOperationRow, "operation_id");
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + RELEASE + " WHERE original_local_operation_id = ?",
            originalOperationId);
    if (row == null) return null;
    byte[] originalBytes = bytes(row, "original_member_value_bytes");
    byte[] releasedBytes = bytes(row, "released_member_value_bytes");
    BigInteger sourceRevision = positive(row, "source_inventory_revision");
    UUID releaseId = uuid(row, "operation_id");
    String status = text(row, "status");
    Record transition =
        transaction.fetchOne(
            "SELECT admission_orphan_cleanup_id, candidate_binding_ref,"
                + " candidate_binding_generation FROM "
                + TRANSITION
                + " WHERE transition_id = ?",
            uuid(row, "transition_id"));
    if (!originalOperationId.equals(uuid(row, "original_local_operation_id"))
        || !uuid(row, "transition_id").equals(uuid(originalOperationRow, "transition_id"))
        || !uuid(row, "reservation_id").equals(originalValue.reservation().reservationId())
        || !Arrays.equals(bytes(row, "binding_ref"), originalValue.owner().bindingRef().bytes())
        || !positive(row, "binding_generation").equals(originalValue.bindingGeneration())
        || !nonnegative(row, "partition_id").equals(originalValue.reservation().partitionId())
        || !uuid(row, "original_reservation_fence")
            .equals(originalValue.reservation().reservationFence())
        || !uuid(row, "original_issuer_coverage_operation_id")
            .equals(originalValue.reservation().issuerCoverageOperationId())
        || !positive(row, "original_issuer_coverage_operation_fence")
            .equals(originalValue.reservation().issuerCoverageOperationFence())
        || !positive(row, "original_coverage_fence")
            .equals(originalValue.reservation().coverageFence())
        || !positive(row, "original_inventory_snapshot_revision")
            .equals(originalValue.reservation().inventorySnapshotRevision())
        || !positive(row, "original_reservation_inventory_revision")
            .equals(originalValue.reservation().inventoryRevision())
        || !positive(row, "original_local_operation_fence")
            .equals(positive(originalOperationRow, "operation_fence"))
        || !positive(row, "original_local_member_generation")
            .equals(positive(originalOperationRow, "local_member_generation"))
        || !uuid(row, "anchor_issuer_coverage_operation_id")
            .equals(originalValue.anchor().issuerCoverageOperationId())
        || !positive(row, "anchor_operation_fence").equals(originalValue.anchor().operationFence())
        || !positive(row, "anchor_coverage_fence").equals(originalValue.anchor().coverageFence())
        || !positive(row, "anchor_inventory_snapshot_revision")
            .equals(originalValue.anchor().issuerInventorySnapshotRevision())
        || transition == null
        || !uuid(row, "orphan_cleanup_id")
            .equals(transition.get("admission_orphan_cleanup_id", UUID.class))
        || !Arrays.equals(
            bytes(row, "binding_ref"), transition.get("candidate_binding_ref", byte[].class))
        || !positive(row, "binding_generation")
            .equals(number(transition, "candidate_binding_generation"))
        || !Arrays.equals(originalBytes, bytes(originalOperationRow, "member_value_bytes"))
        || !text(row, "original_member_value_digest")
            .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(originalBytes))
        || !text(row, "released_member_value_digest")
            .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(releasedBytes))) {
      throw conflict(
          "Registered issuer release differs from its immutable original local operation");
    }
    CanonicalGameplayIssuerMemberV2Value released;
    try {
      released = CanonicalGameplayIssuerMemberV2Codec.decode(releasedBytes);
    } catch (RuntimeException malformed) {
      throw conflict("Registered issuer release postimage is not canonical schema v2");
    }
    BigInteger fence = positive(row, "release_operation_fence");
    BigInteger generation = positive(row, "release_local_member_generation");
    CanonicalGameplayIssuerMemberV2Value expected =
        createReleasedValue(originalValue, sourceRevision, releaseId, fence, generation);
    if (!expected.equals(released)
        || !Arrays.equals(releasedBytes, CanonicalGameplayIssuerMemberV2Codec.encode(expected))
        || !originalValue.owner().issuerId().equals(text(row, "issuer_id"))) {
      throw conflict("Registered issuer release changes the original owner or anchor provenance");
    }
    if ("PENDING_PHYSICAL_PROOF".equals(status)) {
      if (!currentTransitionStatus.equals("ABORTED")
          || !currentCandidateLifecycle.equals("TERMINAL_UNRESOLVED")
          || !currentReservationLifecycle.equals("RELEASE_PENDING")
          || !currentIssuerObligationStatus.equals("REQUIRED")
          || currentReleaseOperationId != null
          || !currentInventoryRevision.equals(sourceRevision)
          || nullablePositive(row, "completion_inventory_revision") != null
          || optionalBytes(row, "physical_readback_field_set_bytes") != null) {
        throw conflict("Pending issuer release differs from its retained abort source");
      }
      return null;
    }
    if (!"RELEASED".equals(status)
        || !currentTransitionStatus.equals("ABORTED")
        || !currentCandidateLifecycle.equals("TERMINAL_UNRESOLVED")
        || !currentReservationLifecycle.equals("RELEASED")
        || !currentIssuerObligationStatus.equals("RELEASED")
        || !releaseId.equals(currentReleaseOperationId)
        || !Objects.equals(
            nullablePositive(row, "completion_inventory_revision"), currentInventoryRevision)
        || currentInventoryRevision.compareTo(sourceRevision) <= 0) {
      throw conflict("Completed issuer release lacks its current V29 completion linkage");
    }
    CanonicalGameplayBindingInventoryRepository.requireCompletedIssuerLocalRelease(
        transaction,
        uuid(row, "transition_id"),
        bytes(row, "binding_ref"),
        currentInventoryRevision,
        releaseId);
    return new ReleaseProjection(releaseId, releasedBytes, released);
  }

  record ReleaseProjection(
      UUID operationId,
      byte[] exactMemberValueBytes,
      CanonicalGameplayIssuerMemberV2Value memberValue) {
    ReleaseProjection {
      exactMemberValueBytes = exactMemberValueBytes.clone();
    }

    @Override
    public byte[] exactMemberValueBytes() {
      return exactMemberValueBytes.clone();
    }
  }

  private static void insertRelease(
      DSLContext transaction,
      CanonicalGameplayAdmissionAbortSource source,
      CanonicalGameplayIssuerBindingLocalOperation original,
      UUID operationId,
      BigInteger operationFence,
      BigInteger localGeneration,
      BigInteger sourceRevision,
      byte[] originalBytes,
      byte[] releasedBytes) {
    var reservation = original.reservation();
    var anchor = original.anchor();
    transaction.execute(
        "INSERT INTO "
            + RELEASE
            + " (operation_id, issuer_id, transition_id, orphan_cleanup_id, reservation_id,"
            + " original_local_operation_id, binding_ref, binding_generation, partition_id,"
            + " original_reservation_fence, original_issuer_coverage_operation_id,"
            + " original_issuer_coverage_operation_fence, original_coverage_fence,"
            + " original_inventory_snapshot_revision, original_reservation_inventory_revision,"
            + " original_local_operation_fence, original_local_member_generation,"
            + " anchor_issuer_coverage_operation_id, anchor_operation_fence, anchor_coverage_fence,"
            + " anchor_inventory_snapshot_revision, source_inventory_revision, release_operation_fence,"
            + " release_local_member_generation, original_member_value_bytes, original_member_value_digest,"
            + " released_member_value_bytes, released_member_value_digest, status)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'PENDING_PHYSICAL_PROOF')",
        operationId,
        ISSUER,
        source.transition().transitionId(),
        source.orphanCleanupId(),
        source.reservation().reservationId(),
        original.operationId(),
        source.transition().bindingRef().bytes(),
        decimal(source.transition().bindingGeneration()),
        decimal(source.reservation().partitionId()),
        reservation.reservationFence(),
        reservation.issuerCoverageOperationId(),
        decimal(reservation.issuerCoverageOperationFence()),
        decimal(reservation.coverageFence()),
        decimal(reservation.inventorySnapshotRevision()),
        decimal(reservation.inventoryRevision()),
        decimal(original.operationFence()),
        decimal(original.localMemberGeneration()),
        anchor.issuerCoverageOperationId(),
        decimal(anchor.operationFence()),
        decimal(anchor.coverageFence()),
        decimal(anchor.issuerInventorySnapshotRevision()),
        decimal(sourceRevision),
        decimal(operationFence),
        decimal(localGeneration),
        originalBytes,
        CanonicalGameplayIssuerMemberV2Codec.sha256(originalBytes),
        releasedBytes,
        CanonicalGameplayIssuerMemberV2Codec.sha256(releasedBytes));
  }

  private static void advanceAbortSourceRevision(
      DSLContext transaction,
      CanonicalGameplayAdmissionAbortSource source,
      BigInteger priorRevision,
      BigInteger nextRevision,
      BigInteger clockRevision) {
    UUID transitionId = source.transition().transitionId();
    byte[] bindingRef = source.transition().bindingRef().bytes();
    CanonicalIssuerPartitionReservation reservation = source.reservation();
    requireOne(
        transaction.execute(
            "UPDATE "
                + TRANSITION
                + " SET inventory_revision = ?"
                + " WHERE transition_id = ? AND status = 'ABORTED'"
                + " AND admission_orphan_cleanup_id = ? AND inventory_revision = ?"
                + " AND candidate_binding_ref = ? AND candidate_binding_generation = ?"
                + " AND issuer_reservation_id = ? AND expected_prior_binding_ref IS NULL"
                + " AND expected_prior_binding_generation IS NULL",
            decimal(nextRevision),
            transitionId,
            source.orphanCleanupId(),
            decimal(priorRevision),
            bindingRef,
            decimal(source.transition().bindingGeneration()),
            reservation.reservationId()),
        "Issuer release preparation lost its immutable Account-abort transition source");
    requireOne(
        transaction.execute(
            "UPDATE "
                + INVENTORY
                + " SET inventory_revision = ?"
                + " WHERE binding_ref = ? AND transition_id = ? AND lifecycle = 'TERMINAL_UNRESOLVED'"
                + " AND inventory_revision = ? AND binding_generation = ? AND issuer_reservation_id = ?",
            decimal(nextRevision),
            bindingRef,
            transitionId,
            decimal(priorRevision),
            decimal(source.transition().bindingGeneration()),
            reservation.reservationId()),
        "Issuer release preparation lost its retained candidate source");
    requireOne(
        transaction.execute(
            "UPDATE "
                + RESERVATION
                + " SET inventory_revision = ?"
                + " WHERE reservation_id = ? AND transition_id = ? AND lifecycle = 'RELEASE_PENDING'"
                + " AND inventory_revision = ? AND binding_ref = ? AND binding_generation = ?"
                + " AND reservation_fence = ? AND issuer_coverage_operation_id = ?"
                + " AND issuer_coverage_operation_fence = ? AND coverage_fence = ?"
                + " AND inventory_snapshot_revision = ?",
            decimal(nextRevision),
            reservation.reservationId(),
            transitionId,
            decimal(priorRevision),
            bindingRef,
            decimal(source.transition().bindingGeneration()),
            reservation.reservationFence(),
            reservation.issuerCoverageOperationId(),
            decimal(reservation.issuerCoverageOperationFence()),
            decimal(reservation.coverageFence()),
            decimal(reservation.inventorySnapshotRevision())),
        "Issuer release preparation lost its original pending-reservation source");
    requireOne(
        transaction.execute(
            "UPDATE "
                + ACCOUNT_OBLIGATION
                + " SET inventory_revision = ?"
                + " WHERE transition_id = ? AND inventory_revision = ?"
                + " AND status IN ('REQUIRED', 'ACKNOWLEDGED')",
            decimal(nextRevision),
            transitionId,
            decimal(priorRevision)),
        "Issuer release preparation lost its retained Account-obligation source");
    requireOne(
        transaction.execute(
            "UPDATE "
                + ISSUER_OBLIGATION
                + " SET inventory_revision = ?"
                + " WHERE transition_id = ? AND binding_ref = ? AND status = 'REQUIRED'"
                + " AND local_release_operation_id IS NULL AND inventory_revision = ?",
            decimal(nextRevision),
            transitionId,
            bindingRef,
            decimal(priorRevision)),
        "Issuer release preparation lost its retained issuer-obligation source");
    requireOne(
        transaction.execute(
            "UPDATE "
                + REGION_OBLIGATION
                + " SET inventory_revision = ?"
                + " WHERE transition_id = ? AND status = 'REQUIRED' AND inventory_revision = ?",
            decimal(nextRevision),
            transitionId,
            decimal(priorRevision)),
        "Issuer release preparation lost its retained region-obligation source");
    requireOne(
        transaction.execute(
            "UPDATE "
                + INVENTORY_CLOCK
                + " SET inventory_revision = ?"
                + " WHERE singleton_id = 1 AND inventory_revision = ?",
            decimal(nextRevision),
            decimal(clockRevision)),
        "Issuer release preparation lost the current inventory-clock compare-and-set");
  }

  private static CanonicalGameplayIssuerMemberV2Value createReleasedValue(
      CanonicalGameplayIssuerMemberV2Value original,
      BigInteger sourceRevision,
      UUID operationId,
      BigInteger operationFence,
      BigInteger localGeneration) {
    return new CanonicalGameplayIssuerMemberV2Value(
        original.owner(),
        original.bindingGeneration(),
        withLifecycleAndRevision(
            original.reservation(),
            CanonicalIssuerPartitionReservation.Lifecycle.RELEASED,
            sourceRevision),
        original.anchor(),
        new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
            operationId, operationFence, localGeneration),
        original.issuerControlEpoch(),
        original.layoutDescriptor(),
        original.sourceCheckpoint(),
        original.incarnation());
  }

  private static CanonicalIssuerPartitionReservation withLifecycleAndRevision(
      CanonicalIssuerPartitionReservation source,
      CanonicalIssuerPartitionReservation.Lifecycle lifecycle,
      BigInteger revision) {
    return new CanonicalIssuerPartitionReservation(
        source.reservationId(),
        source.owner(),
        source.bindingGeneration(),
        source.transitionId(),
        source.partitionId(),
        lifecycle,
        source.reservationFence(),
        source.issuerCoverageOperationId(),
        source.issuerCoverageOperationFence(),
        source.coverageFence(),
        source.inventorySnapshotRevision(),
        revision);
  }

  private static void verifyReadbackMatchesOperation(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation,
      byte[] readbackBytes,
      String readbackDigest) {
    if (readbackBytes.length == 0
        || readbackBytes.length > 17_825_792
        || !CanonicalGameplayIssuerMemberV2Codec.sha256(readbackBytes).equals(readbackDigest)) {
      throw conflict("Issuer release exact readback is malformed");
    }
    var fields = CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(readbackBytes);
    String capacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            operation.releasedMemberValue().reservation().reservationId());
    String bindingField =
        new String(
            operation.releasedMemberValue().owner().bindingRef().bytes(), StandardCharsets.UTF_8);
    if (!Arrays.equals(fields.get(capacityField), operation.exactReleasedMemberValueBytes())
        || fields.containsKey(bindingField)) {
      throw conflict(
          "Issuer release readback does not prove the exact RELEASED capacity postimage");
    }
  }

  private static void requireExactReleaseSource(
      CanonicalGameplayIssuerBindingLocalReleaseOperation expected,
      CanonicalGameplayAdmissionAbortSource current) {
    requireAbortedSource(current);
    var originalSource = expected.abortSource();
    var originalReservation = originalSource.reservation();
    var currentReservation = current.reservation();
    boolean currentMatchesPending =
        currentReservation.lifecycle()
                == CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING
            && originalSource
                .transition()
                .inventoryRevision()
                .equals(current.transition().inventoryRevision())
            && originalReservation.equals(currentReservation);
    boolean currentMatchesReleased =
        currentReservation.lifecycle() == CanonicalIssuerPartitionReservation.Lifecycle.RELEASED
            && current
                    .transition()
                    .inventoryRevision()
                    .compareTo(expected.sourceInventoryRevision())
                > 0
            && currentReservation
                .inventoryRevision()
                .equals(current.transition().inventoryRevision())
            && originalReservation.reservationId().equals(currentReservation.reservationId())
            && originalReservation.owner().equals(currentReservation.owner())
            && originalReservation
                .bindingGeneration()
                .equals(currentReservation.bindingGeneration())
            && originalReservation.transitionId().equals(currentReservation.transitionId())
            && originalReservation.partitionId().equals(currentReservation.partitionId())
            && originalReservation.reservationFence().equals(currentReservation.reservationFence())
            && originalReservation
                .issuerCoverageOperationId()
                .equals(currentReservation.issuerCoverageOperationId())
            && originalReservation
                .issuerCoverageOperationFence()
                .equals(currentReservation.issuerCoverageOperationFence())
            && originalReservation.coverageFence().equals(currentReservation.coverageFence())
            && originalReservation
                .inventorySnapshotRevision()
                .equals(currentReservation.inventorySnapshotRevision());
    if (!originalSource.transition().transitionId().equals(current.transition().transitionId())
        || !originalSource.orphanCleanupId().equals(current.orphanCleanupId())
        || !originalSource.candidate().identity().equals(current.candidate().identity())
        || !originalSource.transition().bindingRef().equals(current.transition().bindingRef())
        || !originalSource
            .transition()
            .bindingGeneration()
            .equals(current.transition().bindingGeneration())
        || !(currentMatchesPending || currentMatchesReleased)) {
      throw conflict(
          "Current first-NEW abort source differs from the SQL-issued release operation");
    }
  }

  private static boolean sameOperationSource(
      CanonicalGameplayIssuerBindingLocalOperation original,
      CanonicalGameplayAdmissionAbortSource source) {
    return original.request().transitionId().equals(source.transition().transitionId())
        && original.request().candidate().equals(source.candidate().identity())
        && original.reservation().reservationId().equals(source.reservation().reservationId())
        && original.reservation().reservationFence().equals(source.reservation().reservationFence())
        && original.reservation().owner().equals(source.reservation().owner())
        && original
            .reservation()
            .bindingGeneration()
            .equals(source.reservation().bindingGeneration())
        && original
            .reservation()
            .issuerCoverageOperationId()
            .equals(source.reservation().issuerCoverageOperationId())
        && original
            .reservation()
            .issuerCoverageOperationFence()
            .equals(source.reservation().issuerCoverageOperationFence())
        && original.reservation().coverageFence().equals(source.reservation().coverageFence())
        && original
            .reservation()
            .inventorySnapshotRevision()
            .equals(source.reservation().inventorySnapshotRevision());
  }

  private static void requireSameOperation(
      CanonicalGameplayIssuerBindingLocalReleaseOperation expected,
      CanonicalGameplayIssuerBindingLocalReleaseOperation current) {
    if (!expected.operationId().equals(current.operationId())
        || !expected.operationFence().equals(current.operationFence())
        || !expected.localMemberGeneration().equals(current.localMemberGeneration())
        || !expected.sourceInventoryRevision().equals(current.sourceInventoryRevision())
        || !Arrays.equals(
            expected.exactOriginalMemberValueBytes(), current.exactOriginalMemberValueBytes())
        || !Arrays.equals(
            expected.exactReleasedMemberValueBytes(), current.exactReleasedMemberValueBytes())
        || !expected.physicalMutation().mutationId().equals(current.physicalMutation().mutationId())
        || !expected
            .physicalMutation()
            .expectedRevision()
            .equals(current.physicalMutation().expectedRevision())
        || !expected
            .physicalMutation()
            .successorRevision()
            .equals(current.physicalMutation().successorRevision())
        || !Arrays.equals(
            expected.physicalMutation().exactIntendedChangeBytes(),
            current.physicalMutation().exactIntendedChangeBytes())) {
      throw conflict("SQL issuer release operation changed across its physical proof boundary");
    }
  }

  private static void requireSameAbortSource(
      CanonicalGameplayAdmissionAbortSource expected,
      CanonicalGameplayAdmissionAbortSource current) {
    if (!expected.transition().equals(current.transition())
        || !expected.candidate().equals(current.candidate())
        || !expected
            .originalDecision()
            .targetEvidence()
            .equals(current.originalDecision().targetEvidence())
        || !expected.transition().transitionId().equals(current.transition().transitionId())
        || expected.transition().status() != current.transition().status()
        || !expected
            .transition()
            .inventoryRevision()
            .equals(current.transition().inventoryRevision())
        || !expected.orphanCleanupId().equals(current.orphanCleanupId())
        || !expected.transition().bindingRef().equals(current.transition().bindingRef())
        || !expected
            .transition()
            .bindingGeneration()
            .equals(current.transition().bindingGeneration())
        || !expected.transition().reservationFence().equals(current.transition().reservationFence())
        || !expected
            .transition()
            .candidateAccountIndexFence()
            .equals(current.transition().candidateAccountIndexFence())
        || !expected.reservation().equals(current.reservation())
        || !expected.candidate().identity().equals(current.candidate().identity())
        || !expected.candidate().bindingRef().equals(current.candidate().bindingRef())
        || !expected.candidate().bindingGeneration().equals(current.candidate().bindingGeneration())
        || !expected.candidate().accountIndexFence().equals(current.candidate().accountIndexFence())
        || expected.candidate().lifecycle() != current.candidate().lifecycle()
        || expected.candidate().accountIndexState() != current.candidate().accountIndexState()
        || expected.candidate().issuerIndexState() != current.candidate().issuerIndexState()
        || !expected
            .originalDecision()
            .leaseEvidenceJson()
            .equals(current.originalDecision().leaseEvidenceJson())) {
      throw conflict("Actual Account abort source changed during issuer release");
    }
  }

  private static void requireSameAbortIdentity(
      CanonicalGameplayAdmissionAbortSource expected,
      CanonicalGameplayAdmissionAbortSource current) {
    if (!expected.transition().transitionId().equals(current.transition().transitionId())
        || expected.transition().status() != current.transition().status()
        || !expected.orphanCleanupId().equals(current.orphanCleanupId())
        || !expected.transition().bindingRef().equals(current.transition().bindingRef())
        || !expected
            .transition()
            .bindingGeneration()
            .equals(current.transition().bindingGeneration())
        || !expected.transition().reservationFence().equals(current.transition().reservationFence())
        || !expected
            .transition()
            .candidateAccountIndexFence()
            .equals(current.transition().candidateAccountIndexFence())
        || !expected.reservation().reservationId().equals(current.reservation().reservationId())
        || expected.reservation().lifecycle() != current.reservation().lifecycle()
        || !expected.reservation().owner().equals(current.reservation().owner())
        || !expected
            .reservation()
            .reservationFence()
            .equals(current.reservation().reservationFence())
        || !expected
            .reservation()
            .issuerCoverageOperationId()
            .equals(current.reservation().issuerCoverageOperationId())
        || !expected
            .reservation()
            .issuerCoverageOperationFence()
            .equals(current.reservation().issuerCoverageOperationFence())
        || !expected.reservation().coverageFence().equals(current.reservation().coverageFence())
        || !expected
            .reservation()
            .inventorySnapshotRevision()
            .equals(current.reservation().inventorySnapshotRevision())
        || !expected.candidate().identity().equals(current.candidate().identity())
        || !expected.candidate().bindingRef().equals(current.candidate().bindingRef())
        || !expected.candidate().bindingGeneration().equals(current.candidate().bindingGeneration())
        || !expected.candidate().accountIndexFence().equals(current.candidate().accountIndexFence())
        || expected.candidate().lifecycle() != current.candidate().lifecycle()
        || expected.candidate().accountIndexState() != current.candidate().accountIndexState()
        || expected.candidate().issuerIndexState() != current.candidate().issuerIndexState()
        || !expected
            .originalDecision()
            .leaseEvidenceJson()
            .equals(current.originalDecision().leaseEvidenceJson())) {
      throw conflict("Actual first-NEW abort identity changed while preparing issuer release");
    }
  }

  private static void requireAbortedSource(CanonicalGameplayAdmissionAbortSource source) {
    if (!source.isAccountAborted()
        || source.transition().status() != CanonicalGameplayBindingTransitionSnapshot.Status.ABORTED
        || source.candidate().lifecycle()
            != net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry
                .Lifecycle.TERMINAL_UNRESOLVED
        || (source.reservation().lifecycle()
                != CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING
            && source.reservation().lifecycle()
                != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED)) {
      throw conflict("Issuer release requires the actual Account ABORTED and retained GS source");
    }
  }

  private static void requireSameCurrentIssuerState(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState expected,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    if (!CanonicalGameplayIssuerBindingLocalOperationRepository.sameCurrentIssuerState(
        expected, current)) {
      throw conflict("Issuer source or incarnation moved during local release");
    }
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      readCurrentIssuerState() {
    return Objects.requireNonNull(
        currentIssuerStateSource.readFreshCurrentState(),
        "current issuer state source returned null");
  }

  private static UUID freshNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (value.equals(new UUID(0L, 0L)));
    return value;
  }

  private static UUID uuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || value.equals(new UUID(0L, 0L)))
      throw conflict("Issuer release SQL " + field + " is unavailable");
    return value;
  }

  private static String text(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null || value.isBlank())
      throw conflict("Issuer release SQL " + field + " is unavailable");
    return value;
  }

  private static byte[] bytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null) throw conflict("Issuer release SQL " + field + " is unavailable");
    return value.clone();
  }

  private static byte[] optionalBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    return value == null ? null : value.clone();
  }

  private static BigInteger positive(Record row, String field) {
    BigInteger value = number(row, field);
    if (value.signum() <= 0) throw conflict("Issuer release SQL " + field + " is not positive");
    return value;
  }

  private static BigInteger nonnegative(Record row, String field) {
    BigInteger value = number(row, field);
    if (value.signum() < 0) throw conflict("Issuer release SQL " + field + " is negative");
    return value;
  }

  private static BigInteger nullablePositive(Record row, String field) {
    BigDecimal raw = row.get(field, BigDecimal.class);
    BigInteger value = raw == null ? null : number(row, field);
    if (value != null && value.signum() <= 0)
      throw conflict("Issuer release SQL " + field + " is not positive");
    return value;
  }

  private static BigInteger number(Record row, String field) {
    BigDecimal value = row.get(field, BigDecimal.class);
    if (value == null) throw conflict("Issuer release SQL " + field + " is unavailable");
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Issuer release SQL " + field + " is not an integer");
    }
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
  }

  private static void requireCounterLimit(BigInteger value, String name) {
    if (value.signum() <= 0
        || value.toString().length() > CanonicalGameplayIssuerMemberV2Codec.MAX_COUNTER_DIGITS) {
      throw conflict(name + " is outside the canonical issuer-operation counter bound");
    }
  }

  private static void requireOne(int changed, String message) {
    if (changed != 1) throw conflict(message);
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw conflict("Issuer release requires its own SQL boundary outside Redis calls");
    }
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException("Issuer binding-local release is unavailable: " + message);
  }
}
