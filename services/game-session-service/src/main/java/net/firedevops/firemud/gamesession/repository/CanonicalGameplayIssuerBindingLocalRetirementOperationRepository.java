package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL owner for original-operation physical retirement after a committed issuer release. */
final class CanonicalGameplayIssuerBindingLocalRetirementOperationRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String RETIREMENT = "game_session_canonical_issuer_binding_local_retirement";
  private static final String RELEASE =
      "game_session_canonical_issuer_binding_local_release_operation";
  private static final String LOCAL_OPERATION =
      "game_session_canonical_issuer_binding_local_operation";
  private static final String RESERVATION = "game_session_canonical_issuer_partition_reservation";
  private static final String INVENTORY = "game_session_canonical_gameplay_binding_inventory";
  private static final String ISSUER_OBLIGATION =
      "game_session_canonical_binding_issuer_index_obligation";
  private static final String INVENTORY_CLOCK = "game_session_canonical_binding_inventory_clock";

  private final DSLContext dsl;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentIssuerStateSource;
  private final CanonicalGameplayIssuerBindingLocalReleaseOperationRepository releaseRepository;

  CanonicalGameplayIssuerBindingLocalRetirementOperationRepository(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentIssuerStateSource) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.currentIssuerStateSource =
        Objects.requireNonNull(currentIssuerStateSource, "currentIssuerStateSource");
    this.releaseRepository =
        new CanonicalGameplayIssuerBindingLocalReleaseOperationRepository(
            dsl, currentIssuerStateSource);
  }

  /** Creates or exactly resumes retirement only from the committed original release receipt. */
  CanonicalGameplayIssuerBindingLocalRetirementOperation prepareOrResume(UUID transitionId) {
    Objects.requireNonNull(transitionId, "transitionId");
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBindingLocalRetirementOperation completed =
        readCompletedRetirement(transitionId);
    if (completed != null) return completed;
    CanonicalGameplayIssuerBindingLocalReleaseOperation release =
        releaseRepository.readCommittedRelease(transitionId);
    var before = readCurrentIssuerState();
    requireSourceMatchesRelease(release.cleanupSource(), release);
    CanonicalGameplayIssuerBindingLocalRetirementOperation prepared =
        dsl.transactionResult(
            configuration -> {
              DSLContext transaction = org.jooq.impl.DSL.using(configuration);
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              CanonicalGameplayIssuerCleanupSource currentSource =
                  CanonicalGameplayBindingInventoryRepository.readIssuerCleanupSourceInTransaction(
                      transaction, transitionId, true);
              requireSameReleaseSource(release.cleanupSource(), currentSource, release);
              requireExactCommittedReleaseRow(transaction, release);
              protectCurrentReferences(transaction, release);
              BigInteger partitionId = release.releasedMemberValue().reservation().partitionId();
              Record existing =
                  transaction.fetchOne(
                      "SELECT * FROM " + RETIREMENT + " WHERE release_operation_id = ? FOR UPDATE",
                      release.operationId());
              if (existing != null) return readOperation(transaction, existing, release);

              CanonicalGameplayIssuerPartitionMutationRepository.PartitionMutationHead head =
                  CanonicalGameplayIssuerPartitionMutationRepository.readHeadInTransaction(
                      transaction,
                      release.originalSource().anchor().bootstrapOperationId(),
                      partitionId);
              if (head.pendingMutationId() != null) {
                throw conflict("Another same-partition mutation is unresolved before retirement");
              }
              UUID retirementId = freshNonNilUuid();
              byte[] intendedChange = encodeRetirementIntent(retirementId, release);
              CanonicalGameplayIssuerPartitionMutation mutation =
                  CanonicalGameplayIssuerPartitionMutationRepository.prepareInTransaction(
                      transaction,
                      CanonicalGameplayIssuerPartitionMutation.Kind.RETIRE,
                      retirementId,
                      partitionId,
                      intendedChange);
              insertRetirement(transaction, retirementId, release, mutation);
              Record saved =
                  transaction.fetchOne(
                      "SELECT * FROM "
                          + RETIREMENT
                          + " WHERE retirement_operation_id = ? FOR UPDATE",
                      retirementId);
              if (saved == null)
                throw conflict("Pending issuer retirement intent was not retained");
              return readOperation(transaction, saved, release);
            });
    requireSameCurrentIssuerState(
        before, readCurrentIssuerState(), prepared.release().originalSource().anchor());
    CanonicalGameplayIssuerBindingLocalReleaseOperation after =
        releaseRepository.readCommittedRelease(transitionId);
    requireSameReleaseOperation(release, after);
    return prepared;
  }

  /** Returns only immutable completed history; it does not depend on later mutable V29 rows. */
  private CanonicalGameplayIssuerBindingLocalRetirementOperation readCompletedRetirement(
      UUID transitionId) {
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = org.jooq.impl.DSL.using(configuration);
          // The release has a unique transition key; its operation is the retirement's unique
          // release key. Resolve those two point identities instead of filtering retained history.
          Record release =
              transaction.fetchOne(
                  "SELECT operation_id FROM "
                      + RELEASE
                      + " WHERE transition_id = ? AND issuer_id = ? AND status = 'RELEASED'",
                  transitionId,
                  ISSUER);
          if (release == null) return null;
          Record retirement =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + RETIREMENT
                      + " WHERE release_operation_id = ? AND status = 'RETIRED'",
                  uuid(release, "operation_id"));
          if (retirement == null) return null;
          return readCompletedRetirementInTransaction(transaction, retirement);
        });
  }

  static CanonicalGameplayIssuerBindingLocalRetirementOperation
      readCompletedRetirementInTransaction(DSLContext transaction, Record retirement) {
    UUID operationId = uuid(retirement, "retirement_operation_id");
    BigInteger partitionId = nonnegative(retirement, "partition_id");
    UUID reservationId = uuid(retirement, "reservation_id");
    UUID releaseId = uuid(retirement, "release_operation_id");
    UUID localId = retirement.get("original_local_operation_id", UUID.class);
    UUID bootstrapId = retirement.get("original_bootstrap_member_operation_id", UUID.class);
    if ((localId == null) == (bootstrapId == null)) throw conflict("Retirement requires exactly one original source");
    UUID originalId = localId == null ? bootstrapId : localId;
    UUID transitionId = uuid(retirement, "transition_id");
    byte[] bindingRef = bytes(retirement, "binding_ref");
    byte[] originalBytes = bytes(retirement, "original_member_value_bytes");
    byte[] releasedBytes = bytes(retirement, "released_member_value_bytes");
    byte[] absenceBytes = bytes(retirement, "exact_absence_readback_field_set_bytes");
    String absenceDigest = text(retirement, "exact_absence_readback_field_set_digest");
    if (!ISSUER.equals(text(retirement, "issuer_id"))
        || !text(retirement, "status").equals("RETIRED")
        || !CanonicalGameplayIssuerMemberV2Codec.sha256(originalBytes)
            .equals(text(retirement, "original_member_value_digest"))
        || !CanonicalGameplayIssuerMemberV2Codec.sha256(releasedBytes)
            .equals(text(retirement, "released_member_value_digest"))
        || !CanonicalGameplayIssuerMemberV2Codec.sha256(absenceBytes).equals(absenceDigest)) {
      throw conflict("Committed retirement history has a changed exact byte receipt");
    }

    CanonicalGameplayIssuerMemberV2Value originalMember;
    CanonicalGameplayIssuerMemberV2Value releasedMember;
    try {
      originalMember = CanonicalGameplayIssuerMemberV2Codec.decode(originalBytes);
      releasedMember = CanonicalGameplayIssuerMemberV2Codec.decode(releasedBytes);
    } catch (RuntimeException malformed) {
      throw conflict("Committed retirement release tuple is not canonical schema v2");
    }
    Record release =
        transaction.fetchOne("SELECT * FROM " + RELEASE + " WHERE operation_id = ?", releaseId);
    Record original =
        transaction.fetchOne(
            "SELECT * FROM " + (localId == null ? "game_session_canonical_issuer_bootstrap_member_operation" : LOCAL_OPERATION)
                + " WHERE " + (localId == null ? "member_operation_id" : "operation_id") + " = ?", originalId);
    if (release == null
        || original == null
        || !ISSUER.equals(text(release, "issuer_id"))
        || !"RELEASED".equals(text(release, "status"))
        || !Objects.equals(release.get("original_local_operation_id", UUID.class), localId)
        || !Objects.equals(release.get("original_bootstrap_member_operation_id", UUID.class), bootstrapId)
        || !uuid(release, "transition_id").equals(transitionId)
        || !uuid(release, "reservation_id").equals(reservationId)
        || !nonnegative(release, "partition_id").equals(partitionId)
        || !Arrays.equals(bytes(release, "binding_ref"), bindingRef)
        || !uuid(release, "original_reservation_fence")
            .equals(uuid(retirement, "original_reservation_fence"))
        || !uuid(release, "original_issuer_coverage_operation_id")
            .equals(uuid(retirement, "original_issuer_coverage_operation_id"))
        || !positive(release, "original_issuer_coverage_operation_fence")
            .equals(positive(retirement, "original_issuer_coverage_operation_fence"))
        || !positive(release, "original_coverage_fence")
            .equals(positive(retirement, "original_coverage_fence"))
        || !positive(release, "original_inventory_snapshot_revision")
            .equals(positive(retirement, "original_inventory_snapshot_revision"))
        || !positive(release, "original_reservation_inventory_revision")
            .equals(positive(retirement, "original_reservation_inventory_revision"))
        || !uuid(release, "anchor_issuer_coverage_operation_id")
            .equals(uuid(retirement, "anchor_issuer_coverage_operation_id"))
        || !positive(release, "anchor_operation_fence")
            .equals(positive(retirement, "anchor_operation_fence"))
        || !positive(release, "anchor_coverage_fence")
            .equals(positive(retirement, "anchor_coverage_fence"))
        || !positive(release, "anchor_inventory_snapshot_revision")
            .equals(positive(retirement, "anchor_inventory_snapshot_revision"))
        || !Arrays.equals(bytes(release, "original_member_value_bytes"), originalBytes)
        || !Arrays.equals(bytes(release, "released_member_value_bytes"), releasedBytes)
        || !text(release, "original_member_value_digest")
            .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(originalBytes))
        || !text(release, "released_member_value_digest")
            .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(releasedBytes))
        || !positive(release, "source_inventory_revision")
            .equals(positive(retirement, "release_source_inventory_revision"))
        || !positive(release, "completion_inventory_revision")
            .equals(positive(retirement, "release_completion_inventory_revision"))
        || !text(release, "physical_readback_field_set_digest")
            .equals(
                CanonicalGameplayIssuerMemberV2Codec.sha256(readReleaseReceiptFieldSet(release)))
        || !uuid(original, localId == null ? "member_operation_id" : "operation_id").equals(originalId)
        || !uuid(original, "reservation_id").equals(reservationId)
        || (localId != null && (!uuid(original, "transition_id").equals(transitionId)
            || !Arrays.equals(bytes(original, "binding_ref"), bindingRef)
            || !positive(original, "binding_generation").equals(originalMember.bindingGeneration())))
        || !positive(original, "operation_fence")
            .equals(originalMember.memberOperation().operationFence())
        || !positive(original, "local_member_generation")
            .equals(originalMember.memberOperation().coverageGeneration())
        || !originalMember.memberOperation().operationId().equals(originalId)
        || !originalMember.reservation().transitionId().equals(transitionId)
        || !Arrays.equals(bytes(original, "member_value_bytes"), originalBytes)
        || !text(original, "member_value_digest")
            .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(originalBytes))) {
      throw conflict("Committed retirement no longer links to its exact immutable release source");
    }
    if (bootstrapId != null
        && (!ISSUER.equals(text(original, "issuer_id"))
            || !"CANDIDATE_PREPARED".equals(text(original, "binding_lifecycle"))
            || !"RESERVED".equals(text(original, "reservation_lifecycle"))
            || !Boolean.FALSE.equals(original.get("expected_binding_present", Boolean.class))
            || !Boolean.TRUE.equals(original.get("counts_toward_capacity", Boolean.class))
            || !"PENDING_PHYSICAL_PROOF".equals(text(original, "member_status"))
            || !Objects.equals(original.get("member_schema_version", Short.class), (short) 2)
            || !uuid(original, "bootstrap_operation_id").equals(uuid(retirement, "anchor_issuer_coverage_operation_id"))
            || !uuid(original, "bootstrap_anchor_operation_id").equals(uuid(retirement, "anchor_issuer_coverage_operation_id"))
            || !positive(original, "bootstrap_anchor_operation_fence").equals(positive(retirement, "anchor_operation_fence"))
            || !positive(original, "bootstrap_anchor_coverage_fence").equals(positive(retirement, "anchor_coverage_fence"))
            || !positive(original, "bootstrap_anchor_inventory_snapshot_revision").equals(positive(retirement, "anchor_inventory_snapshot_revision")))) {
      throw conflict("Committed retirement lost its genuine frozen bootstrap member lineage");
    }
    CanonicalIssuerPartitionReservation originalReservation = originalMember.reservation();
    var originalAnchor = originalMember.anchor();
    BigInteger releaseSourceRevision = positive(retirement, "release_source_inventory_revision");
    CanonicalGameplayIssuerMemberV2Value expectedReleasedMember =
        new CanonicalGameplayIssuerMemberV2Value(
            originalMember.owner(),
            originalMember.bindingGeneration(),
            new CanonicalIssuerPartitionReservation(
                originalReservation.reservationId(),
                originalReservation.owner(),
                originalReservation.bindingGeneration(),
                originalReservation.transitionId(),
                originalReservation.partitionId(),
                CanonicalIssuerPartitionReservation.Lifecycle.RELEASED,
                originalReservation.reservationFence(),
                originalReservation.issuerCoverageOperationId(),
                originalReservation.issuerCoverageOperationFence(),
                originalReservation.coverageFence(),
                originalReservation.inventorySnapshotRevision(),
                releaseSourceRevision),
            originalAnchor,
            new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
                releaseId,
                positive(release, "release_operation_fence"),
                positive(release, "release_local_member_generation")),
            originalMember.issuerControlEpoch(),
            originalMember.layoutDescriptor(),
            originalMember.sourceCheckpoint(),
            originalMember.incarnation());
    if (originalMember.reservation().lifecycle()
            != CanonicalIssuerPartitionReservation.Lifecycle.RESERVED
        || releasedMember.reservation().lifecycle()
            != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED
        || !originalReservation
            .reservationFence()
            .equals(uuid(retirement, "original_reservation_fence"))
        || !originalReservation
            .issuerCoverageOperationId()
            .equals(uuid(retirement, "original_issuer_coverage_operation_id"))
        || !originalReservation
            .issuerCoverageOperationFence()
            .equals(positive(retirement, "original_issuer_coverage_operation_fence"))
        || !originalReservation
            .coverageFence()
            .equals(positive(retirement, "original_coverage_fence"))
        || !originalReservation
            .inventorySnapshotRevision()
            .equals(positive(retirement, "original_inventory_snapshot_revision"))
        || !originalReservation
            .inventoryRevision()
            .equals(positive(retirement, "original_reservation_inventory_revision"))
        || !originalAnchor
            .issuerCoverageOperationId()
            .equals(uuid(retirement, "anchor_issuer_coverage_operation_id"))
        || !originalAnchor.operationFence().equals(positive(retirement, "anchor_operation_fence"))
        || !originalAnchor.coverageFence().equals(positive(retirement, "anchor_coverage_fence"))
        || !originalAnchor
            .issuerInventorySnapshotRevision()
            .equals(positive(retirement, "anchor_inventory_snapshot_revision"))
        || !originalMember.reservation().reservationId().equals(reservationId)
        || !releasedMember.reservation().reservationId().equals(reservationId)
        || !releasedMember.reservation().partitionId().equals(partitionId)
        || !Arrays.equals(releasedMember.owner().bindingRef().bytes(), bindingRef)
        || !releasedMember.memberOperation().operationId().equals(releaseId)
        || !releasedMember.equals(expectedReleasedMember)
        || !Arrays.equals(
            releasedBytes, CanonicalGameplayIssuerMemberV2Codec.encode(expectedReleasedMember))) {
      throw conflict("Committed retirement does not retain the original RELEASED member value");
    }

    byte[] expectedRetirementIntent =
        CanonicalGameplayIssuerPartitionMutationRepository.encodeIntendedChange(
            CanonicalGameplayIssuerPartitionMutation.Kind.RETIRE,
            operationId,
            partitionId,
            releaseId.toString().getBytes(StandardCharsets.US_ASCII),
            originalId.toString().getBytes(StandardCharsets.US_ASCII),
            transitionId.toString().getBytes(StandardCharsets.US_ASCII),
            reservationId.toString().getBytes(StandardCharsets.US_ASCII),
            bindingRef,
            releaseSourceRevision.toString().getBytes(StandardCharsets.US_ASCII),
            positive(retirement, "release_completion_inventory_revision")
                .toString()
                .getBytes(StandardCharsets.US_ASCII),
            originalBytes,
            releasedBytes);
    if (!Arrays.equals(expectedRetirementIntent, bytes(retirement, "exact_intended_change_bytes"))
        || !text(retirement, "exact_intended_change_digest")
            .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(expectedRetirementIntent))) {
      throw conflict("Committed retirement intent differs from its exact immutable release tuple");
    }

    CanonicalGameplayIssuerPartitionMutation releaseMutation =
        CanonicalGameplayIssuerPartitionMutationRepository.readInTransaction(
            transaction,
            CanonicalGameplayIssuerPartitionMutation.Kind.RELEASE,
            releaseId,
            partitionId);
    byte[] releaseReadback = readReleaseReceiptFieldSet(release);
    byte[] releaseMutationIntent =
        CanonicalGameplayIssuerPartitionMutationRepository.encodeIntendedChange(
            CanonicalGameplayIssuerPartitionMutation.Kind.RELEASE,
            releaseId,
            partitionId,
            originalId.toString().getBytes(StandardCharsets.US_ASCII),
            reservationId.toString().getBytes(StandardCharsets.US_ASCII),
            bindingRef,
            originalBytes,
            releasedBytes,
            positive(retirement, "release_source_inventory_revision")
                .toString()
                .getBytes(StandardCharsets.US_ASCII));
    if (releaseMutation.status() != CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED
        || !Arrays.equals(releaseMutation.exactIntendedChangeBytes(), releaseMutationIntent)
        || !Arrays.equals(releaseMutation.exactReadbackFieldSetBytes(), releaseReadback)) {
      throw conflict("Committed retirement lost its original physical release proof");
    }
    Map<String, byte[]> releaseFields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(releaseReadback);
    String releasedCapacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(reservationId);
    String releasedBindingField = new String(bindingRef, StandardCharsets.UTF_8);
    if (!Arrays.equals(
            releaseFields.get(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD),
            releaseMutation.successorRevision().toString().getBytes(StandardCharsets.US_ASCII))
        || !Arrays.equals(releaseFields.get(releasedCapacityField), releasedBytes)
        || releaseFields.containsKey(releasedBindingField)) {
      throw conflict("Original committed release receipt lacks its exact RELEASED physical proof");
    }

    CanonicalGameplayIssuerPartitionMutation mutation =
        CanonicalGameplayIssuerPartitionMutationRepository.readInTransaction(
            transaction,
            CanonicalGameplayIssuerPartitionMutation.Kind.RETIRE,
            operationId,
            partitionId);
    if (mutation.status() != CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED
        || !uuid(retirement, "physical_mutation_id").equals(mutation.mutationId())
        || !mutation.expectedRevision().equals(uuid(retirement, "expected_revision"))
        || !mutation.successorRevision().equals(uuid(retirement, "successor_revision"))
        || !Arrays.equals(
            mutation.exactIntendedChangeBytes(), bytes(retirement, "exact_intended_change_bytes"))
        || !mutation
            .exactIntendedChangeDigest()
            .equals(text(retirement, "exact_intended_change_digest"))
        || !Arrays.equals(mutation.exactReadbackFieldSetBytes(), absenceBytes)) {
      throw conflict("Committed retirement lacks its exact successor-fence and absence proof");
    }
    Map<String, byte[]> fields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(absenceBytes);
    String targetCapacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(reservationId);
    byte[] revision = fields.get(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD);
    if (fields.containsKey(targetCapacityField)
        || !Arrays.equals(
            revision,
            mutation.successorRevision().toString().getBytes(StandardCharsets.US_ASCII))) {
      throw conflict("Committed retirement receipt does not prove exact field absence and fence");
    }
    return CanonicalGameplayIssuerBindingLocalRetirementOperation.completedHistorical(
        operationId, partitionId, reservationId, mutation, absenceBytes, absenceDigest);
  }

  private static byte[] readReleaseReceiptFieldSet(Record release) {
    return bytes(release, "physical_readback_field_set_bytes");
  }

  /** Commits exact absence and fence evidence with its physical-mutation receipt. */
  CanonicalGameplayIssuerBindingLocalRetirementOperation completeWithReadback(
      CanonicalGameplayIssuerBindingLocalRetirementOperation expected,
      CanonicalGameplayIssuerBindingLocalRetirementRedisOwner.Readback readback) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(readback, "readback");
    requireNoAmbientTransaction();
    if (!expected.operationId().equals(readback.operationId())) {
      throw conflict("Issuer retirement readback belongs to a different original operation");
    }
    byte[] exactBytes = readback.exactAbsenceReadbackFieldSetBytes();
    String exactDigest = CanonicalGameplayIssuerMemberV2Codec.sha256(exactBytes);
    verifyExactAbsence(expected, exactBytes, exactDigest);
    if (expected.status()
        == CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED) {
      if (!Arrays.equals(expected.exactAbsenceReadbackFieldSetBytes(), exactBytes)) {
        throw conflict("Completed issuer retirement retry changed its original absence readback");
      }
      return expected;
    }
    if (expected.release() == null) {
      throw conflict("Pending issuer retirement lacks its current-source release context");
    }
    var before = readCurrentIssuerState();
    CanonicalGameplayIssuerBindingLocalRetirementOperation completed =
        dsl.transactionResult(
            configuration -> {
              DSLContext transaction = org.jooq.impl.DSL.using(configuration);
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              CanonicalGameplayIssuerBindingLocalReleaseOperation release = expected.release();
              CanonicalGameplayIssuerCleanupSource source =
                  CanonicalGameplayBindingInventoryRepository.readIssuerCleanupSourceInTransaction(
                      transaction, release.cleanupSource().transition().transitionId(), true);
              requireSameReleaseSource(release.cleanupSource(), source, release);
              requireExactCommittedReleaseRow(transaction, release);
              protectCurrentReferences(transaction, release);
              Record row =
                  transaction.fetchOne(
                      "SELECT * FROM "
                          + RETIREMENT
                          + " WHERE retirement_operation_id = ? FOR UPDATE",
                      expected.operationId());
              if (row == null) throw conflict("Pending issuer retirement disappeared");
              CanonicalGameplayIssuerBindingLocalRetirementOperation current =
                  readOperation(transaction, row, release);
              requireSameRetirement(expected, current);
              if (current.status()
                  == CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED) {
                verifyExactAbsence(current, exactBytes, exactDigest);
                CanonicalGameplayIssuerPartitionMutationRepository.completeInTransaction(
                    transaction, current.physicalMutation(), exactBytes);
                return current;
              }
              CanonicalGameplayIssuerPartitionMutationRepository.completeInTransaction(
                  transaction, current.physicalMutation(), exactBytes);
              int updated =
                  transaction.execute(
                      "UPDATE "
                          + RETIREMENT
                          + " SET status = 'RETIRED', exact_absence_readback_field_set_bytes = ?"
                          + ", exact_absence_readback_field_set_digest = ?, completed_at = CURRENT_TIMESTAMP"
                          + " WHERE retirement_operation_id = ? AND status = 'PENDING_PHYSICAL_PROOF'"
                          + " AND physical_mutation_id = ? AND expected_revision = ? AND successor_revision = ?",
                      exactBytes,
                      exactDigest,
                      current.operationId(),
                      current.physicalMutation().mutationId(),
                      current.physicalMutation().expectedRevision(),
                      current.physicalMutation().successorRevision());
              if (updated != 1) throw conflict("Issuer retirement proof compare-and-set refused");
              Record saved =
                  transaction.fetchOne(
                      "SELECT * FROM " + RETIREMENT + " WHERE retirement_operation_id = ?",
                      current.operationId());
              if (saved == null) throw conflict("Committed issuer retirement proof is unreadable");
              return readOperation(transaction, saved, release);
            });
    requireSameCurrentIssuerState(
        before, readCurrentIssuerState(), completed.release().originalSource().anchor());
    return completed;
  }

  private static void protectCurrentReferences(
      DSLContext transaction, CanonicalGameplayIssuerBindingLocalReleaseOperation release) {
    var original = release.originalSource();
    byte[] bindingRef = release.releasedMemberValue().owner().bindingRef().bytes();
    UUID reservationId = release.releasedMemberValue().reservation().reservationId();
    UUID operationId = original.operationId();
    if (count(
                transaction,
                "SELECT COUNT(*) FROM game_session_canonical_issuer_current_local_operation membership"
                    + " JOIN "
                    + LOCAL_OPERATION
                    + " operation ON operation.operation_id = membership.operation_id"
                    + " WHERE membership.issuer_id = ? AND membership.anchor_bootstrap_operation_id = ?"
                    + " AND operation.binding_ref = ? AND operation.operation_id <> ?",
                ISSUER,
                original.anchor().bootstrapOperationId(),
                bindingRef,
                operationId)
            != 0
        || count(
                transaction,
                "SELECT COUNT(*) FROM "
                    + RESERVATION
                    + " reservation WHERE reservation.issuer_id = ? AND reservation.lifecycle <> 'RELEASED'"
                    + " AND reservation.binding_ref = ? AND reservation.reservation_id <> ?",
                ISSUER,
                bindingRef,
                reservationId)
            != 0
        || count(
                transaction,
                "SELECT COUNT(*) FROM game_session_canonical_issuer_bootstrap_member_operation bootstrap"
                    + " JOIN "
                    + RESERVATION
                    + " reservation ON reservation.reservation_id = bootstrap.reservation_id"
                    + " WHERE bootstrap.issuer_id = ? AND bootstrap.bootstrap_operation_id = ?"
                    + " AND reservation.binding_ref = ? AND reservation.reservation_id <> ?",
                ISSUER,
                original.anchor().bootstrapOperationId(),
                bindingRef,
                reservationId)
            != 0) {
      throw conflict("A newer or conflicting SQL issuer reference protects this local member");
    }
    Record reservation =
        transaction.fetchOne(
            "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ? FOR UPDATE", reservationId);
    Record candidate =
        transaction.fetchOne(
            "SELECT * FROM " + INVENTORY + " WHERE binding_ref = ? FOR UPDATE", bindingRef);
    Record obligation =
        transaction.fetchOne(
            "SELECT * FROM "
                + ISSUER_OBLIGATION
                + " WHERE transition_id = ? AND binding_ref = ? FOR UPDATE",
            release.cleanupSource().transition().transitionId(),
            bindingRef);
    if (reservation == null
        || !"RELEASED".equals(text(reservation, "lifecycle"))
        || !number(reservation, "inventory_revision").equals(release.completionInventoryRevision())
        || candidate == null
        || !"TERMINAL_UNRESOLVED".equals(text(candidate, "lifecycle"))
        || !number(candidate, "inventory_revision").equals(release.completionInventoryRevision())
        || !uuid(candidate, "transition_id")
            .equals(release.cleanupSource().transition().transitionId())
        || obligation == null
        || !"RELEASED".equals(text(obligation, "status"))
        || !uuid(obligation, "local_release_operation_id").equals(release.operationId())
        || !number(obligation, "inventory_revision")
            .equals(release.completionInventoryRevision())) {
      throw conflict("Current SQL issuer references no longer match the committed release");
    }
  }

  private static void requireExactCommittedReleaseRow(
      DSLContext transaction, CanonicalGameplayIssuerBindingLocalReleaseOperation release) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + RELEASE + " WHERE operation_id = ? FOR UPDATE",
            release.operationId());
    if (row == null
        || !ISSUER.equals(text(row, "issuer_id"))
        || !"RELEASED".equals(text(row, "status"))
        || !Objects.equals(row.get("original_local_operation_id", UUID.class), release.originalSource().localOperationId())
        || !Objects.equals(row.get("original_bootstrap_member_operation_id", UUID.class), release.originalSource().bootstrapMemberOperationId())
        || !positive(row, "source_inventory_revision").equals(release.sourceInventoryRevision())
        || !positive(row, "completion_inventory_revision")
            .equals(release.completionInventoryRevision())
        || !Arrays.equals(
            bytes(row, "original_member_value_bytes"), release.exactOriginalMemberValueBytes())
        || !Arrays.equals(
            bytes(row, "released_member_value_bytes"), release.exactReleasedMemberValueBytes())
        || !text(row, "physical_readback_field_set_digest").equals(release.physicalReadbackDigest())
        || !Arrays.equals(
            bytes(row, "physical_readback_field_set_bytes"),
            release.exactPhysicalReadbackFieldSetBytes())) {
      throw conflict("The original committed release receipt changed before retirement");
    }
  }

  private static CanonicalGameplayIssuerBindingLocalRetirementOperation readOperation(
      DSLContext transaction,
      Record row,
      CanonicalGameplayIssuerBindingLocalReleaseOperation release) {
    UUID operationId = uuid(row, "retirement_operation_id");
    UUID mutationId = uuid(row, "physical_mutation_id");
    CanonicalGameplayIssuerPartitionMutation mutation =
        CanonicalGameplayIssuerPartitionMutationRepository.readInTransaction(
            transaction,
            CanonicalGameplayIssuerPartitionMutation.Kind.RETIRE,
            operationId,
            release.releasedMemberValue().reservation().partitionId());
    CanonicalGameplayIssuerPartitionMutationRepository.PartitionMutationHead head =
        CanonicalGameplayIssuerPartitionMutationRepository.readHeadInTransaction(
            transaction,
            release.originalSource().anchor().bootstrapOperationId(),
            release.releasedMemberValue().reservation().partitionId());
    if ((mutation.status() == CanonicalGameplayIssuerPartitionMutation.Status.PENDING_PHYSICAL_PROOF
            && (!mutation.mutationId().equals(head.pendingMutationId())
                || !mutation.expectedRevision().equals(head.currentRevision())))
        || (mutation.status() == CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED
            && mutation.mutationId().equals(head.pendingMutationId()))) {
      throw conflict("Original retirement does not match its current partition-head custody");
    }
    byte[] intended = encodeRetirementIntent(operationId, release);
    var original = release.originalSource();
    var reservation = original.reservation();
    var anchor = original.anchor();
    if (!mutationId.equals(mutation.mutationId())
        || !Arrays.equals(intended, mutation.exactIntendedChangeBytes())
        || !ISSUER.equals(text(row, "issuer_id"))
        || !nonnegative(row, "partition_id")
            .equals(release.releasedMemberValue().reservation().partitionId())
        || !uuid(row, "release_operation_id").equals(release.operationId())
        || !Objects.equals(row.get("original_local_operation_id", UUID.class), release.originalSource().localOperationId())
        || !Objects.equals(row.get("original_bootstrap_member_operation_id", UUID.class), release.originalSource().bootstrapMemberOperationId())
        || !uuid(row, "transition_id").equals(release.cleanupSource().transition().transitionId())
        || !uuid(row, "reservation_id")
            .equals(release.releasedMemberValue().reservation().reservationId())
        || !Arrays.equals(
            bytes(row, "binding_ref"), release.releasedMemberValue().owner().bindingRef().bytes())
        || !uuid(row, "original_reservation_fence").equals(reservation.reservationFence())
        || !uuid(row, "original_issuer_coverage_operation_id")
            .equals(reservation.issuerCoverageOperationId())
        || !positive(row, "original_issuer_coverage_operation_fence")
            .equals(reservation.issuerCoverageOperationFence())
        || !positive(row, "original_coverage_fence").equals(reservation.coverageFence())
        || !positive(row, "original_inventory_snapshot_revision")
            .equals(reservation.inventorySnapshotRevision())
        || !positive(row, "original_reservation_inventory_revision")
            .equals(reservation.inventoryRevision())
        || !uuid(row, "anchor_issuer_coverage_operation_id")
            .equals(anchor.issuerCoverageOperationId())
        || !positive(row, "anchor_operation_fence").equals(anchor.operationFence())
        || !positive(row, "anchor_coverage_fence").equals(anchor.coverageFence())
        || !positive(row, "anchor_inventory_snapshot_revision")
            .equals(anchor.issuerInventorySnapshotRevision())
        || !positive(row, "release_source_inventory_revision")
            .equals(release.sourceInventoryRevision())
        || !positive(row, "release_completion_inventory_revision")
            .equals(release.completionInventoryRevision())
        || !Arrays.equals(
            bytes(row, "original_member_value_bytes"), release.exactOriginalMemberValueBytes())
        || !Arrays.equals(
            bytes(row, "released_member_value_bytes"), release.exactReleasedMemberValueBytes())
        || !text(row, "original_member_value_digest")
            .equals(
                CanonicalGameplayIssuerMemberV2Codec.sha256(
                    release.exactOriginalMemberValueBytes()))
        || !text(row, "released_member_value_digest")
            .equals(
                CanonicalGameplayIssuerMemberV2Codec.sha256(
                    release.exactReleasedMemberValueBytes()))
        || !uuid(row, "expected_revision").equals(mutation.expectedRevision())
        || !uuid(row, "successor_revision").equals(mutation.successorRevision())) {
      throw conflict("Stored issuer retirement differs from the exact release or revision tuple");
    }
    CanonicalGameplayIssuerBindingLocalRetirementOperation.Status status;
    try {
      status =
          CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.valueOf(
              text(row, "status"));
    } catch (RuntimeException malformed) {
      throw conflict("Stored issuer retirement status is malformed");
    }
    byte[] absence = optionalBytes(row, "exact_absence_readback_field_set_bytes");
    String digest = row.get("exact_absence_readback_field_set_digest", String.class);
    if ((status
                == CanonicalGameplayIssuerBindingLocalRetirementOperation.Status
                    .PENDING_PHYSICAL_PROOF
            && (absence != null || digest != null || row.get("completed_at") != null))
        || (status == CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED
            && (absence == null
                || digest == null
                || !digest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(absence))
                || row.get("completed_at") == null))) {
      throw conflict("Stored issuer retirement lacks its exact bounded absence phase");
    }
    try {
      return new CanonicalGameplayIssuerBindingLocalRetirementOperation(
          release, operationId, mutation, status, absence, digest);
    } catch (RuntimeException malformed) {
      throw conflict("Stored issuer retirement proof is malformed");
    }
  }

  private static void insertRetirement(
      DSLContext transaction,
      UUID operationId,
      CanonicalGameplayIssuerBindingLocalReleaseOperation release,
      CanonicalGameplayIssuerPartitionMutation mutation) {
    var original = release.originalSource();
    var released = release.releasedMemberValue();
    var reservation = original.reservation();
    var anchor = original.anchor();
    byte[] originalBytes = release.exactOriginalMemberValueBytes();
    byte[] releasedBytes = release.exactReleasedMemberValueBytes();
    transaction.execute(
        "INSERT INTO "
            + RETIREMENT
            + " (retirement_operation_id, issuer_id, partition_id, release_operation_id, physical_mutation_id,"
            + " original_local_operation_id, original_bootstrap_member_operation_id, transition_id, reservation_id, binding_ref, original_reservation_fence,"
            + " original_issuer_coverage_operation_id, original_issuer_coverage_operation_fence, original_coverage_fence,"
            + " original_inventory_snapshot_revision, original_reservation_inventory_revision,"
            + " anchor_issuer_coverage_operation_id, anchor_operation_fence, anchor_coverage_fence,"
            + " anchor_inventory_snapshot_revision, release_source_inventory_revision, release_completion_inventory_revision,"
            + " original_member_value_bytes, original_member_value_digest, released_member_value_bytes, released_member_value_digest,"
            + " expected_revision, successor_revision, exact_intended_change_bytes, exact_intended_change_digest, status)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_PHYSICAL_PROOF')",
        operationId,
        ISSUER,
        decimal(released.reservation().partitionId()),
        release.operationId(),
        mutation.mutationId(),
        original.localOperationId(),
        original.bootstrapMemberOperationId(),
        original.transitionId(),
        reservation.reservationId(),
        released.owner().bindingRef().bytes(),
        reservation.reservationFence(),
        reservation.issuerCoverageOperationId(),
        decimal(reservation.issuerCoverageOperationFence()),
        decimal(reservation.coverageFence()),
        decimal(reservation.inventorySnapshotRevision()),
        decimal(reservation.inventoryRevision()),
        anchor.issuerCoverageOperationId(),
        decimal(anchor.operationFence()),
        decimal(anchor.coverageFence()),
        decimal(anchor.issuerInventorySnapshotRevision()),
        decimal(release.sourceInventoryRevision()),
        decimal(release.completionInventoryRevision()),
        originalBytes,
        CanonicalGameplayIssuerMemberV2Codec.sha256(originalBytes),
        releasedBytes,
        CanonicalGameplayIssuerMemberV2Codec.sha256(releasedBytes),
        mutation.expectedRevision(),
        mutation.successorRevision(),
        mutation.exactIntendedChangeBytes(),
        mutation.exactIntendedChangeDigest());
  }

  private static byte[] encodeRetirementIntent(
      UUID retirementOperationId, CanonicalGameplayIssuerBindingLocalReleaseOperation release) {
    return CanonicalGameplayIssuerPartitionMutationRepository.encodeIntendedChange(
        CanonicalGameplayIssuerPartitionMutation.Kind.RETIRE,
        retirementOperationId,
        release.releasedMemberValue().reservation().partitionId(),
        release.operationId().toString().getBytes(StandardCharsets.US_ASCII),
        release.originalOperationId().toString().getBytes(StandardCharsets.US_ASCII),
        release
            .cleanupSource()
            .transition()
            .transitionId()
            .toString()
            .getBytes(StandardCharsets.US_ASCII),
        release
            .releasedMemberValue()
            .reservation()
            .reservationId()
            .toString()
            .getBytes(StandardCharsets.US_ASCII),
        release.releasedMemberValue().owner().bindingRef().bytes(),
        release.sourceInventoryRevision().toString().getBytes(StandardCharsets.US_ASCII),
        release.completionInventoryRevision().toString().getBytes(StandardCharsets.US_ASCII),
        release.exactOriginalMemberValueBytes(),
        release.exactReleasedMemberValueBytes());
  }

  private static void verifyExactAbsence(
      CanonicalGameplayIssuerBindingLocalRetirementOperation operation,
      byte[] exactBytes,
      String digest) {
    if (exactBytes == null
        || exactBytes.length == 0
        || exactBytes.length > 17_825_792
        || !digest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(exactBytes))) {
      throw conflict("Issuer retirement readback is missing, oversized, or has a wrong digest");
    }
    Map<String, byte[]> fields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(exactBytes);
    String capacity =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            operation.reservationId());
    byte[] revision = fields.get(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD);
    if (fields.containsKey(capacity)
        || !Arrays.equals(
            revision,
            operation
                .physicalMutation()
                .successorRevision()
                .toString()
                .getBytes(StandardCharsets.US_ASCII))) {
      throw conflict("Issuer retirement readback does not prove exact capacity-field absence");
    }
  }

  private static void requireSameRetirement(
      CanonicalGameplayIssuerBindingLocalRetirementOperation expected,
      CanonicalGameplayIssuerBindingLocalRetirementOperation actual) {
    if (!expected.operationId().equals(actual.operationId())
        || !expected.physicalMutation().mutationId().equals(actual.physicalMutation().mutationId())
        || !expected
            .physicalMutation()
            .expectedRevision()
            .equals(actual.physicalMutation().expectedRevision())
        || !expected
            .physicalMutation()
            .successorRevision()
            .equals(actual.physicalMutation().successorRevision())
        || !Arrays.equals(
            expected.physicalMutation().exactIntendedChangeBytes(),
            actual.physicalMutation().exactIntendedChangeBytes())) {
      throw conflict("Original issuer retirement changed across the physical proof boundary");
    }
  }

  private static void requireSameReleaseOperation(
      CanonicalGameplayIssuerBindingLocalReleaseOperation expected,
      CanonicalGameplayIssuerBindingLocalReleaseOperation actual) {
    if (!expected.operationId().equals(actual.operationId())
        || expected.status() != actual.status()
        || !Arrays.equals(
            expected.exactOriginalMemberValueBytes(), actual.exactOriginalMemberValueBytes())
        || !Arrays.equals(
            expected.exactReleasedMemberValueBytes(), actual.exactReleasedMemberValueBytes())
        || !Arrays.equals(
            expected.exactPhysicalReadbackFieldSetBytes(),
            actual.exactPhysicalReadbackFieldSetBytes())) {
      throw conflict("Committed release changed while preparing physical retirement");
    }
  }

  private static void requireSameReleaseSource(
      CanonicalGameplayIssuerCleanupSource expected,
      CanonicalGameplayIssuerCleanupSource actual,
      CanonicalGameplayIssuerBindingLocalReleaseOperation release) {
    if (!expected.transition().equals(actual.transition())
        || !expected.candidate().equals(actual.candidate())
        || !expected.reservation().equals(actual.reservation())
        || !expected.originalDecision().requestId().equals(actual.originalDecision().requestId())
        || !expected.originalDecision().leaseId().equals(actual.originalDecision().leaseId())
        || !expected
            .originalDecision()
            .leaseEvidenceJson()
            .equals(actual.originalDecision().leaseEvidenceJson())
        || !expected
            .originalDecision()
            .targetEvidence()
            .equals(actual.originalDecision().targetEvidence())
        || !expected.isAccountAborted()
        || !actual.isAccountAborted()
        || !expected.transition().transitionId().equals(actual.transition().transitionId())
        || actual.transition().status()
            != net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
                .Status.ABORTED
        || !actual.transition().inventoryRevision().equals(release.completionInventoryRevision())
        || !actual.transition().bindingRef().equals(expected.transition().bindingRef())
        || !actual
            .transition()
            .bindingGeneration()
            .equals(expected.transition().bindingGeneration())
        || !actual.orphanCleanupId().equals(expected.orphanCleanupId())
        || actual.reservation().lifecycle()
            != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED
        || !actual
            .reservation()
            .reservationId()
            .equals(release.releasedMemberValue().reservation().reservationId())
        || !actual.reservation().inventoryRevision().equals(release.completionInventoryRevision())
        || !actual.reservation().owner().equals(release.releasedMemberValue().owner())) {
      throw conflict("Current Account-aborted source differs from the committed original release");
    }
  }

  private static void requireSourceMatchesRelease(
      CanonicalGameplayIssuerCleanupSource source,
      CanonicalGameplayIssuerBindingLocalReleaseOperation release) {
    requireSameReleaseSource(source, source, release);
  }

  private static void requireSameCurrentIssuerState(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState expected,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState actual,
      CanonicalGameplayIssuerInstalledAnchor anchor) {
    var a = expected == null ? null : expected.accountCheckpoint();
    var b = actual == null ? null : actual.accountCheckpoint();
    if (a == null
        || b == null
        || !a.issuerId().equals(b.issuerId())
        || !a.issuerAuthGeneration().equals(b.issuerAuthGeneration())
        || !Arrays.equals(a.exactCheckpointBytes(), b.exactCheckpointBytes())
        || !a.checkpointDigest().equals(b.checkpointDigest())
        || !Objects.equals(a.latestIssuerEventDigest(), b.latestIssuerEventDigest())
        || !Arrays.equals(a.latestIssuerEventBytes(), b.latestIssuerEventBytes())
        || !Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(expected.incarnation()),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(actual.incarnation()))
        || !Arrays.equals(a.exactCheckpointBytes(), anchor.sourceCheckpointBytes())
        || !a.checkpointDigest().equals(anchor.sourceCheckpointDigest())
        || !Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(actual.incarnation()),
            anchor.incarnationBytes())) {
      throw conflict("Issuer source or storage incarnation changed during retirement SQL work");
    }
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      readCurrentIssuerState() {
    requireNoAmbientTransaction();
    return Objects.requireNonNull(
        currentIssuerStateSource.readFreshCurrentState(), "Current issuer state is required");
  }

  private static long count(DSLContext transaction, String query, Object... bindings) {
    Record row = transaction.fetchOne(query, bindings);
    if (row == null) throw conflict("Issuer retirement SQL count is unavailable");
    return number(row, "count").longValueExact();
  }

  private static UUID freshNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (value.equals(new UUID(0L, 0L)));
    return value;
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
  }

  private static UUID uuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || value.equals(new UUID(0L, 0L)))
      throw conflict(field + " is missing or nil");
    return value;
  }

  private static BigInteger number(Record row, String field) {
    Object value = row.get(field);
    if (!(value instanceof Number number)) throw conflict(field + " is missing or nonnumeric");
    try {
      return number instanceof BigDecimal decimal
          ? decimal.toBigIntegerExact()
          : BigInteger.valueOf(number.longValue());
    } catch (ArithmeticException malformed) {
      throw conflict(field + " is not an exact integer");
    }
  }

  private static BigInteger positive(Record row, String field) {
    BigInteger value = number(row, field);
    if (value.signum() <= 0) throw conflict(field + " is not positive");
    return value;
  }

  private static BigInteger nonnegative(Record row, String field) {
    BigInteger value = number(row, field);
    if (value.signum() < 0) throw conflict(field + " is negative");
    return value;
  }

  private static String text(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw conflict(field + " is missing");
    return value;
  }

  private static byte[] bytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0) throw conflict(field + " is missing or empty");
    return value.clone();
  }

  private static byte[] optionalBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    return value == null ? null : value.clone();
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw conflict("Issuer retirement cannot cross Redis from an ambient SQL transaction");
    }
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }
}
