package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Read-only SQL owner for a current, complete one-partition view of ordinary local issuer work. */
final class CanonicalGameplayIssuerBindingLocalProjectionRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String LOCAL_OPERATION =
      "game_session_canonical_issuer_binding_local_operation";
  private static final String BOOTSTRAP_OPERATION =
      "game_session_canonical_issuer_bootstrap_member_operation";
  private static final String RESERVATION = "game_session_canonical_issuer_partition_reservation";
  private static final String INVENTORY = "game_session_canonical_gameplay_binding_inventory";
  private static final String TRANSITION = "game_session_canonical_binding_transition";
  private static final String ISSUER_OBLIGATION =
      "game_session_canonical_binding_issuer_index_obligation";
  private static final String ACCOUNT_OBLIGATION =
      "game_session_canonical_binding_account_index_obligation";
  private static final String REGION_OBLIGATION =
      "game_session_canonical_binding_region_bridge_obligation";
  private static final String ISSUER_LOCAL_RELEASE =
      "game_session_canonical_issuer_binding_local_release_operation";
  private static final String INVENTORY_CLOCK = "game_session_canonical_binding_inventory_clock";
  private static final int MAX_LOCAL_OPERATIONS_PER_PARTITION =
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_MEMBER_OPERATIONS;

  private final DSLContext dsl;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentIssuerStateSource;

  CanonicalGameplayIssuerBindingLocalProjectionRepository(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentIssuerStateSource) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.currentIssuerStateSource =
        Objects.requireNonNull(currentIssuerStateSource, "currentIssuerStateSource");
  }

  /**
   * Reads only from the installed-anchor owner, the ordinary NEW SQL operation owner, and its
   * current V29 rows. There is deliberately no constructor that accepts caller-supplied members.
   */
  PreparedPartitionWork readPreparedPartitionWork(
      CanonicalGameplayIssuerBindingLocalOperation target) {
    return readPreparedPartitionWork(target, false, false);
  }

  PreparedPartitionWork prepareAddMutationWork(
      CanonicalGameplayIssuerBindingLocalOperation target) {
    return readPreparedPartitionWork(target, true, true);
  }

  private PreparedPartitionWork readPreparedPartitionWork(
      CanonicalGameplayIssuerBindingLocalOperation target,
      boolean prepareAddMutation,
      boolean requireCurrentTargetAccountControl) {
    Objects.requireNonNull(target, "target");
    requireNoAmbientTransaction();
    var before = readCurrentIssuerState();
    requireCurrentSourceMatches(target.anchor(), before);
    PreparedPartitionWork work =
        dsl.transactionResult(
            configuration -> {
              DSLContext transaction = org.jooq.impl.DSL.using(configuration);
              lockInventoryClock(transaction);
              CanonicalGameplayIssuerInstalledAnchor anchor =
                  CanonicalGameplayIssuerBootstrapCoverageRepository
                      .readInstalledAnchorForBindingPreparationInTransaction(transaction, before);
              requireSameAnchor(target.anchor(), anchor);
              return prepareInTransaction(
                  transaction,
                  target,
                  anchor,
                  before,
                  prepareAddMutation,
                  requireCurrentTargetAccountControl);
            });
    var after = readCurrentIssuerState();
    requireSameCurrentIssuerState(before, after);
    requireCurrentSourceMatches(work.anchor(), after);
    return work;
  }

  /**
   * Rebuilds the exact SQL proof inside a caller-owned transaction that already holds the
   * inventory-clock lock. The caller must obtain a fresh issuer source before entering SQL and
   * re-read it after commit; this method performs no remote work and never holds SQL over Redis.
   */
  PreparedPartitionWork readPreparedPartitionWorkInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerBindingLocalOperation target,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(target, "target");
    requireCurrentSourceMatches(target.anchor(), currentState);
    CanonicalGameplayIssuerInstalledAnchor anchor =
        CanonicalGameplayIssuerBootstrapCoverageRepository
            .readInstalledAnchorForBindingPreparationInTransaction(transaction, currentState);
    requireSameAnchor(target.anchor(), anchor);
    return prepareInTransaction(transaction, target, anchor, currentState, false, true);
  }

  /** Re-reads the durable set and all currentness inputs after the Redis round trip. */
  void requireStillCurrent(PreparedPartitionWork expected) {
    Objects.requireNonNull(expected, "expected");
    PreparedPartitionWork current =
        readPreparedPartitionWork(
            expected.targetOperation(), false, expected.requiresCurrentTargetAccountControl());
    if (!Arrays.equals(expected.sqlCurrentnessDigest(), current.sqlCurrentnessDigest())) {
      throw unavailable(
          "The registered issuer member set or current SQL phase changed during projection");
    }
  }

  /** Commits a physical revision only after an independent Redis readback has succeeded. */
  void completePhysicalMutation(
      CanonicalGameplayIssuerPartitionMutation mutation, byte[] exactReadbackFieldSetBytes) {
    Objects.requireNonNull(mutation, "mutation");
    Objects.requireNonNull(exactReadbackFieldSetBytes, "exactReadbackFieldSetBytes");
    requireNoAmbientTransaction();
    dsl.transaction(
        configuration ->
            CanonicalGameplayIssuerPartitionMutationRepository.completeInTransaction(
                org.jooq.impl.DSL.using(configuration), mutation, exactReadbackFieldSetBytes));
  }

  private PreparedPartitionWork prepareInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerBindingLocalOperation target,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState,
      boolean prepareAddMutation,
      boolean requireCurrentTargetAccountControl) {
    if (requireCurrentTargetAccountControl) {
      // ADD and admitting ACK join live OPEN control. Historical release/retirement reads
      // retain their original source guards without claiming new admission authority.
      CanonicalGameplayIssuerAdmissionControlRepository.requireOpenInTransaction(
          transaction, anchor, currentState);
    }
    BigInteger targetPartition = target.reservation().partitionId();
    CanonicalGameplayIssuerPartitionMutationRepository.PartitionMutationHead mutationHead =
        CanonicalGameplayIssuerPartitionMutationRepository.readHeadInTransaction(
            transaction, anchor.bootstrapOperationId(), targetPartition);
    CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof
        targetBaseline =
            CanonicalGameplayIssuerBootstrapCoverageRepository.readInstalledAnchorPartitionProof(
                transaction, anchor, targetPartition);
    Map<String, byte[]> exactTargetBaselineFields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
            targetBaseline.exactPhysicalFieldSetBytes());
    requireQualifiedBaseline(anchor, targetBaseline, exactTargetBaselineFields);
    byte[] initialRevisionField =
        exactTargetBaselineFields.remove(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MUTATION_REVISION_FIELD);
    if (!Arrays.equals(
        initialRevisionField,
        mutationHead.initialRevision().toString().getBytes(StandardCharsets.US_ASCII))) {
      throw unavailable("The exact installed partition revision differs from its SQL head");
    }
    Map<String, byte[]> targetBaselineFields = exactTargetBaselineFields;

    List<Record> operationRows =
        transaction.fetch(
            "SELECT operation.* FROM "
                + LOCAL_OPERATION
                + " operation WHERE operation.issuer_id = ? AND operation.partition_id = ? AND NOT EXISTS (SELECT 1 FROM "
                + "game_session_canonical_issuer_binding_local_retirement retirement"
                + " WHERE retirement.original_local_operation_id = operation.operation_id"
                + " AND retirement.status = 'RETIRED')"
                + " ORDER BY operation.operation_fence, operation.operation_id",
            ISSUER,
            targetPartition);
    PartitionSources sources =
        readPartitionSources(transaction, operationRows, anchor, targetPartition);
    List<LocalEntry> entries = new ArrayList<>(operationRows.size());
    Set<UUID> localReservationIds = new HashSet<>();
    Set<String> localBindingRefs = new HashSet<>();
    Set<UUID> localOperationIds = new HashSet<>();
    Map<
            BigInteger,
            CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof>
        proofsByPartition = new HashMap<>();
    proofsByPartition.put(targetPartition, targetBaseline);

    int partitionLocalOperationCount = 0;
    BigInteger localAggregateCapacity = readIssuerLocalCapacity(transaction);
    BigInteger localPartitionCapacity = BigInteger.ZERO;
    boolean foundTarget = false;
    for (Record row : operationRows) {
      LocalEntry entry =
          readLocalEntry(
              transaction,
              row,
              anchor,
              target,
              proofsByPartition,
              sources,
              requireCurrentTargetAccountControl
                  && uuid(row, "operation_id").equals(target.operationId()));
      if (!localOperationIds.add(entry.operationId())
          || (!entry.registeredOperationId().equals(entry.operationId())
              && !localOperationIds.add(entry.registeredOperationId()))
          || !localReservationIds.add(entry.memberValue().reservation().reservationId())
          || !localBindingRefs.add(
              new String(
                  entry.memberValue().owner().bindingRef().bytes(), StandardCharsets.UTF_8))) {
        throw unavailable("The registered local issuer operations contain duplicate identities");
      }
      BigInteger partitionId = entry.memberValue().reservation().partitionId();
      var baseline = proofsByPartition.get(partitionId);
      if (baseline == null) {
        baseline = readQualifiedBaseline(transaction, anchor, partitionId);
        proofsByPartition.put(partitionId, baseline);
      }
      CanonicalGameplayIssuerBootstrapCoverageRepository.requireNewMemberAbsentFromBaseline(
          baseline,
          entry.memberValue().owner().bindingRef(),
          entry.memberValue().reservation().reservationId());
      if (partitionId.equals(targetPartition)) {
        partitionLocalOperationCount++;
        if (entry.countsTowardCapacity())
          localPartitionCapacity = localPartitionCapacity.add(BigInteger.ONE);
      }
      if (entry.registeredOperationId().equals(target.operationId())) {
        if (!Arrays.equals(entry.exactRegisteredMemberValueBytes(), target.exactMemberValueBytes())
            || !entry.registeredMemberValue().equals(target.memberValue())
            || !targetPartition.equals(partitionId)) {
          throw unavailable("The requested operation differs from its current SQL registration");
        }
        foundTarget = true;
      }
      entries.add(entry);
    }
    if (!foundTarget)
      throw unavailable("The requested ordinary NEW operation is not durably registered");
    if (partitionLocalOperationCount > MAX_LOCAL_OPERATIONS_PER_PARTITION) {
      throw unavailable("The local partition operation set exceeds the physical owner bound");
    }

    Set<UUID> baselineReservationIds = readAndVerifyBootstrapReservationIds(transaction, anchor);
    if (baselineReservationIds.size() != anchor.physicalReservationCount()) {
      throw unavailable("The installed baseline reservation cohort is incomplete");
    }
    for (UUID reservationId : localReservationIds) {
      if (baselineReservationIds.contains(reservationId)) {
        throw unavailable(
            "A post-baseline issuer operation reuses an installed reservation identity");
      }
    }
    verifyCurrentReservationAccounting(transaction, anchor, baselineReservationIds.size());
    verifyRetirementAccounting(transaction);

    BigInteger aggregateCapacity = anchor.aggregateCountedCapacity().add(localAggregateCapacity);
    BigInteger aggregateBound = anchor.partitionCount().multiply(anchor.partitionCapacity());
    BigInteger partitionCapacity =
        BigInteger.valueOf(targetBaseline.physicalCountedCapacityCount())
            .add(localPartitionCapacity);
    if (partitionCapacity.compareTo(anchor.partitionCapacity()) > 0
        || aggregateCapacity.compareTo(aggregateBound) > 0) {
      throw unavailable(
          "Current SQL issuer reservations exceed the pinned partition or aggregate capacity");
    }

    LocalEntry targetEntry =
        entries.stream()
            .filter(entry -> entry.registeredOperationId().equals(target.operationId()))
            .findFirst()
            .orElseThrow(() -> unavailable("The requested operation disappeared from its SQL set"));
    CanonicalGameplayIssuerPartitionMutation physicalMutation = null;
    if (prepareAddMutation) {
      CanonicalGameplayIssuerPartitionMutation.Kind kind =
          targetEntry.bindingPresent()
              ? CanonicalGameplayIssuerPartitionMutation.Kind.ADD_BINDING
              : CanonicalGameplayIssuerPartitionMutation.Kind.ADD_CAPACITY;
      physicalMutation = sources.mutations().get(new MutationKey(kind, target.operationId()));
      // A completed ADD is a historical receipt. Later release changes the current projection,
      // not the frozen intended change; its replay performs independent current readback only.
      if (physicalMutation == null
          || physicalMutation.status()
              != CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED) {
        if (!targetEntry.operationId().equals(target.operationId())) {
          throw unavailable("A changed terminal projection cannot issue or resume an ADD mutation");
        }
        byte[] intent =
            CanonicalGameplayIssuerPartitionMutationRepository.encodeIntendedChange(
                kind,
                target.operationId(),
                targetPartition,
                targetEntry.memberValue().owner().bindingRef().bytes(),
                CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                        targetEntry.memberValue().reservation().reservationId())
                    .getBytes(StandardCharsets.US_ASCII),
                targetEntry.exactMemberValueBytes(),
                (targetEntry.bindingPresent() ? "1" : "0").getBytes(StandardCharsets.US_ASCII),
                (targetEntry.countsTowardCapacity() ? "1" : "0")
                    .getBytes(StandardCharsets.US_ASCII));
        physicalMutation =
            CanonicalGameplayIssuerPartitionMutationRepository.prepareInTransaction(
                transaction, kind, target.operationId(), targetPartition, intent);
      }
      mutationHead =
          CanonicalGameplayIssuerPartitionMutationRepository.readHeadInTransaction(
              transaction, anchor.bootstrapOperationId(), targetPartition);
      if (physicalMutation.status()
              == CanonicalGameplayIssuerPartitionMutation.Status.PENDING_PHYSICAL_PROOF
          && !physicalMutation.mutationId().equals(mutationHead.pendingMutationId())) {
        throw unavailable("The original ADD no longer owns its pending partition revision");
      }
      if (physicalMutation.status() == CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED
          && mutationHead.pendingMutationId() != null) {
        throw unavailable("A later same-partition mutation is unresolved during ADD replay");
      }
    }
    Map<String, byte[]> allowedFields = new HashMap<>(targetBaselineFields);
    for (LocalEntry entry : entries) {
      if (!entry.memberValue().reservation().partitionId().equals(targetPartition)) continue;
      putUnique(
          allowedFields,
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              entry.memberValue().reservation().reservationId()),
          entry.exactMemberValueBytes());
      if (entry.bindingPresent()) {
        putUnique(
            allowedFields,
            new String(entry.memberValue().owner().bindingRef().bytes(), StandardCharsets.UTF_8),
            entry.exactMemberValueBytes());
      }
    }
    if (allowedFields.size() + 1
            > CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_LOCAL_HASH_FIELDS
        || totalFieldBytes(allowedFields)
                + CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD.length()
                + 36L
            > CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_TOTAL_HASH_BYTES) {
      throw unavailable("The prepared local issuer partition exceeds its bounded field set");
    }
    byte[] currentnessDigest =
        currentnessDigest(
            anchor,
            targetBaseline.exactPhysicalFieldSetBytes(),
            entries,
            partitionCapacity,
            mutationHead.currentRevision(),
            mutationHead.pendingMutationId());
    return new PreparedPartitionWork(
        anchor,
        target,
        targetBaseline,
        targetBaselineFields,
        allowedFields,
        entries,
        targetEntry,
        aggregateCapacity,
        aggregateBound,
        partitionCapacity,
        mutationHead.currentRevision(),
        mutationHead.pendingMutationId(),
        physicalMutation,
        currentnessDigest,
        requireCurrentTargetAccountControl);
  }

  private CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof
      readQualifiedBaseline(
          DSLContext transaction,
          CanonicalGameplayIssuerInstalledAnchor anchor,
          BigInteger partitionId) {
    var baseline =
        CanonicalGameplayIssuerBootstrapCoverageRepository.readInstalledAnchorPartitionProof(
            transaction, anchor, partitionId);
    Map<String, byte[]> baselineFields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
            baseline.exactPhysicalFieldSetBytes());
    requireQualifiedBaseline(anchor, baseline, baselineFields);
    CanonicalGameplayIssuerPartitionMutationRepository.PartitionMutationHead partitionHead =
        CanonicalGameplayIssuerPartitionMutationRepository.readHeadInTransaction(
            transaction, anchor.bootstrapOperationId(), partitionId);
    byte[] initialRevision =
        baselineFields.remove(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MUTATION_REVISION_FIELD);
    if (!Arrays.equals(
        initialRevision,
        partitionHead.initialRevision().toString().getBytes(StandardCharsets.US_ASCII))) {
      throw unavailable("A retained issuer partition baseline lost its initial revision");
    }
    return baseline;
  }

  private static BigInteger readIssuerLocalCapacity(DSLContext transaction) {
    Record row =
        transaction.fetchOne(
            "SELECT COUNT(*) AS count FROM "
                + LOCAL_OPERATION
                + " operation JOIN "
                + RESERVATION
                + " reservation ON reservation.reservation_id = operation.reservation_id"
                + " AND reservation.issuer_id = operation.issuer_id"
                + " WHERE operation.issuer_id = ? AND reservation.lifecycle <> 'RELEASED'"
                + " AND NOT EXISTS (SELECT 1 FROM game_session_canonical_issuer_binding_local_retirement retirement"
                + " WHERE retirement.original_local_operation_id = operation.operation_id AND retirement.status = 'RETIRED')",
            ISSUER);
    if (row == null) throw unavailable("The issuer-wide local capacity count is unavailable");
    return number(row, "count");
  }

  private static PartitionSources readPartitionSources(
      DSLContext transaction,
      List<Record> operations,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      BigInteger partitionId) {
    List<UUID> reservationIds =
        operations.stream().map(row -> uuid(row, "reservation_id")).toList();
    List<UUID> transitionIds = operations.stream().map(row -> uuid(row, "transition_id")).toList();
    List<byte[]> bindingRefs = operations.stream().map(row -> bytes(row, "binding_ref")).toList();
    Map<UUID, Record> reservations =
        readSourceRows(transaction, RESERVATION, "reservation_id", reservationIds);
    Map<UUID, Record> transitions =
        readSourceRows(transaction, TRANSITION, "transition_id", transitionIds);
    Map<UUID, Record> obligations =
        readSourceRows(transaction, ISSUER_OBLIGATION, "transition_id", transitionIds);
    Map<ByteBuffer, Record> inventory = new HashMap<>();
    for (Record row :
        transaction
            .select()
            .from(org.jooq.impl.DSL.table(INVENTORY))
            .where(org.jooq.impl.DSL.field("binding_ref", byte[].class).in(bindingRefs))
            .fetch()) {
      if (inventory.put(ByteBuffer.wrap(bytes(row, "binding_ref")), row) != null)
        throw unavailable("Duplicate local issuer inventory source rows");
    }
    Map<MutationKey, CanonicalGameplayIssuerPartitionMutation> mutations = new HashMap<>();
    for (var mutation :
        CanonicalGameplayIssuerPartitionMutationRepository.readPartitionInTransaction(
            transaction, anchor.bootstrapOperationId(), partitionId)) {
      if (mutations.put(new MutationKey(mutation.kind(), mutation.ownerOperationId()), mutation)
          != null) throw unavailable("Duplicate local issuer mutation source rows");
    }
    return new PartitionSources(reservations, inventory, transitions, obligations, mutations);
  }

  private static Map<UUID, Record> readSourceRows(
      DSLContext transaction, String table, String identityColumn, List<UUID> identities) {
    Map<UUID, Record> rows = new HashMap<>();
    for (Record row :
        transaction
            .select()
            .from(org.jooq.impl.DSL.table(table))
            .where(org.jooq.impl.DSL.field(identityColumn, UUID.class).in(identities))
            .fetch()) {
      if (rows.put(uuid(row, identityColumn), row) != null)
        throw unavailable("Duplicate local issuer source rows in " + table);
    }
    return rows;
  }

  private record MutationKey(
      CanonicalGameplayIssuerPartitionMutation.Kind kind, UUID ownerOperationId) {}

  private record PartitionSources(
      Map<UUID, Record> reservations,
      Map<ByteBuffer, Record> inventory,
      Map<UUID, Record> transitions,
      Map<UUID, Record> obligations,
      Map<MutationKey, CanonicalGameplayIssuerPartitionMutation> mutations) {}

  private LocalEntry readLocalEntry(
      DSLContext transaction,
      Record row,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBindingLocalOperation target,
      Map<
              BigInteger,
              CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof>
          proofsByPartition,
      PartitionSources sources,
      boolean requireCurrentAccountControl) {
    if (!ISSUER.equals(text(row, "issuer_id"))
        || !"PENDING_PHYSICAL_PROOF".equals(text(row, "member_status"))
        || !Objects.equals(row.get("member_schema_version", Short.class), (short) 2)
        || !"ABSENT".equals(text(row, "expected_old_capacity_state"))
        || !"ABSENT".equals(text(row, "expected_old_member_state"))
        || row.get("expected_prior_binding_ref", byte[].class) != null
        || row.get("expected_prior_binding_generation") != null) {
      throw unavailable("A durable local issuer operation is malformed or not an ordinary NEW");
    }
    byte[] exactValue = bytes(row, "member_value_bytes");
    String valueDigest = text(row, "member_value_digest");
    if (!valueDigest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(exactValue))) {
      throw unavailable("A durable local issuer member digest does not match its exact bytes");
    }
    CanonicalGameplayIssuerMemberV2Value member;
    try {
      member = CanonicalGameplayIssuerMemberV2Codec.decode(exactValue);
    } catch (RuntimeException malformed) {
      throw unavailable("A durable local issuer operation is not closed schema v2", malformed);
    }
    UUID operationId = uuid(row, "operation_id");
    BigInteger operationFence = positive(row, "operation_fence");
    BigInteger localGeneration = positive(row, "local_member_generation");
    if (operationId.equals(target.operationId())
        && (!uuid(row, "request_id").equals(target.request().requestId())
            || !uuid(row, "transition_id").equals(target.request().transitionId())
            || !uuid(row, "candidate_account_index_fence")
                .equals(target.candidateAccountIndexFence())
            || !operationFence.equals(target.operationFence())
            || !localGeneration.equals(target.localMemberGeneration()))) {
      throw unavailable(
          "The requested local issuer operation identity differs from its current SQL row");
    }
    CanonicalIssuerPartitionReservation reservation = member.reservation();
    if (reservation.lifecycle() != CanonicalIssuerPartitionReservation.Lifecycle.RESERVED
        || !operationId.equals(member.memberOperation().operationId())
        || !operationFence.equals(member.memberOperation().operationFence())
        || !localGeneration.equals(member.memberOperation().coverageGeneration())
        || !bytesEqual(row, "binding_ref", member.owner().bindingRef().bytes())
        || !positive(row, "binding_generation").equals(member.bindingGeneration())
        || !uuid(row, "transition_id").equals(reservation.transitionId())
        || !uuid(row, "reservation_id").equals(reservation.reservationId())
        || !uuid(row, "reservation_fence").equals(reservation.reservationFence())
        || !nonnegative(row, "partition_id").equals(reservation.partitionId())
        || !positive(row, "local_inventory_snapshot_revision")
            .equals(reservation.inventoryRevision())
        || !uuid(row, "original_issuer_coverage_operation_id")
            .equals(reservation.issuerCoverageOperationId())
        || !positive(row, "original_issuer_coverage_operation_fence")
            .equals(reservation.issuerCoverageOperationFence())
        || !positive(row, "original_coverage_fence").equals(reservation.coverageFence())
        || !positive(row, "original_inventory_snapshot_revision")
            .equals(reservation.inventorySnapshotRevision())
        || !member.anchor().issuerCoverageOperationId().equals(anchor.issuerCoverageOperationId())
        || !member.anchor().operationFence().equals(anchor.operationFence())
        || !member.anchor().coverageFence().equals(anchor.coverageFence())
        || !member
            .anchor()
            .issuerInventorySnapshotRevision()
            .equals(anchor.issuerInventorySnapshotRevision())
        || !member.owner().issuerId().equals(anchor.issuerId())
        || !Arrays.equals(
            member.layoutDescriptor().exactDescriptorBytes(), anchor.layoutDescriptorBytes())
        || !Arrays.equals(
            member.sourceCheckpoint().exactCheckpointBytes(), anchor.sourceCheckpointBytes())
        || !Arrays.equals(
            member.sourceCheckpoint().latestIssuerEventBytes(), anchor.sourceEventBytes())
        || !Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(member.incarnation()),
            anchor.incarnationBytes())
        || !Arrays.equals(bytes(row, "exact_anchor_bytes"), anchor.exactAnchorBytes())
        || !text(row, "exact_anchor_digest").equals(anchor.exactAnchorDigest())
        || !bytesEqual(row, "anchor_layout_descriptor_bytes", anchor.layoutDescriptorBytes())
        || !text(row, "anchor_layout_descriptor_digest").equals(anchor.layoutDescriptorDigest())
        || !bytesEqual(row, "anchor_source_checkpoint_bytes", anchor.sourceCheckpointBytes())
        || !text(row, "anchor_source_checkpoint_digest").equals(anchor.sourceCheckpointDigest())
        || !nullableBytesEqual(row, "anchor_source_event_bytes", anchor.sourceEventBytes())
        || !Objects.equals(
            row.get("anchor_source_event_digest", String.class), anchor.sourceEventDigest())
        || !bytesEqual(row, "anchor_incarnation_bytes", anchor.incarnationBytes())
        || !text(row, "anchor_incarnation_digest").equals(anchor.incarnationDigest())
        || !text(row, "baseline_field_set_digest")
            .equals(
                CanonicalGameplayIssuerMemberV2Codec.sha256(
                    bytes(row, "baseline_field_set_bytes")))) {
      throw unavailable(
          "A durable local issuer operation differs from its original reservation or installed anchor");
    }
    requireAnchorColumns(row, anchor);

    var baseline = proofsByPartition.get(reservation.partitionId());
    if (baseline == null) {
      baseline = readQualifiedBaseline(transaction, anchor, reservation.partitionId());
      proofsByPartition.put(reservation.partitionId(), baseline);
    }
    if (!Arrays.equals(
            bytes(row, "baseline_field_set_bytes"), baseline.exactPhysicalFieldSetBytes())
        || !nonnegative(row, "baseline_partition_id").equals(baseline.partitionId())
        || integer(row, "baseline_counted_capacity") != baseline.physicalCountedCapacityCount()
        || integer(row, "baseline_reservation_count") != baseline.physicalReservationCount()
        || integer(row, "baseline_binding_count") != baseline.physicalBindingCount()) {
      throw unavailable(
          "A durable local issuer operation no longer links to its exact immutable baseline partition");
    }

    Record reservationRow = sources.reservations().get(reservation.reservationId());
    Record inventoryRow =
        sources.inventory().get(ByteBuffer.wrap(member.owner().bindingRef().bytes()));
    Record transitionRow = sources.transitions().get(reservation.transitionId());
    Record issuerObligation = sources.obligations().get(reservation.transitionId());
    if (reservationRow == null
        || inventoryRow == null
        || transitionRow == null
        || issuerObligation == null) {
      throw unavailable("The local issuer operation is missing its current V29 source rows");
    }
    CurrentPhase phase =
        verifyCurrentSourceRows(
            transaction,
            row,
            member,
            reservationRow,
            inventoryRow,
            transitionRow,
            issuerObligation);
    CanonicalGameplayIssuerBindingLocalOperationRepository
        .requireCapturedAccountControlMatchesTransition(
            row, transitionRow, member.owner().accountId());
    if (requireCurrentAccountControl) {
      CanonicalGameplayIssuerBindingLocalOperationRepository
          .requireCurrentAccountControlMatchesCaptured(
              transaction, row, member.owner().accountId());
    }
    CanonicalGameplayIssuerPartitionMutation capacityMutation =
        sources
            .mutations()
            .get(
                new MutationKey(
                    CanonicalGameplayIssuerPartitionMutation.Kind.ADD_CAPACITY, operationId));
    CanonicalGameplayIssuerPartitionMutation bindingMutation =
        sources
            .mutations()
            .get(
                new MutationKey(
                    CanonicalGameplayIssuerPartitionMutation.Kind.ADD_BINDING, operationId));
    boolean capacityMustBePresent =
        (capacityMutation != null
                && capacityMutation.status()
                    == CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED)
            || phase.memberValue().reservation().lifecycle()
                == CanonicalIssuerPartitionReservation.Lifecycle.RELEASED;
    boolean bindingMustBePresent =
        phase.bindingPresent()
            && bindingMutation != null
            && bindingMutation.status()
                == CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED;
    return new LocalEntry(
        operationId,
        phase.operationId(),
        exactValue,
        phase.memberValueBytes(),
        member,
        phase.memberValue(),
        phase.bindingPresent(),
        phase.countsTowardCapacity(),
        phase.lifecycle(),
        phase.transitionStatus(),
        phase.inventoryRevision(),
        capacityMustBePresent,
        bindingMustBePresent);
  }

  private static CurrentPhase verifyCurrentSourceRows(
      DSLContext transaction,
      Record operationRow,
      CanonicalGameplayIssuerMemberV2Value member,
      Record reservationRow,
      Record inventoryRow,
      Record transitionRow,
      Record issuerObligation) {
    CanonicalGameplayBindingIdentity owner = member.owner();
    CanonicalIssuerPartitionReservation original = member.reservation();
    requireOwnerColumns(reservationRow, owner, original.partitionId(), "partition_id");
    requireOwnerColumns(inventoryRow, owner, original.partitionId(), "issuer_partition_id");
    BigInteger currentBindingGeneration = positive(inventoryRow, "binding_generation");
    String candidateLifecycle = text(inventoryRow, "lifecycle");
    String transitionStatus = text(transitionRow, "status");
    String reservationLifecycle = text(reservationRow, "lifecycle");
    BigInteger currentReservationRevision = positive(reservationRow, "inventory_revision");
    BigInteger currentRevision = positive(transitionRow, "inventory_revision");
    String issuerStatus = text(issuerObligation, "status");
    UUID releaseOperationId = issuerObligation.get("local_release_operation_id", UUID.class);
    if (!"REQUIRED".equals(issuerStatus) && !"RELEASED".equals(issuerStatus)) {
      throw unavailable("The current local issuer obligation has an unsupported terminal state");
    }
    if (!uuid(reservationRow, "reservation_id").equals(original.reservationId())
        || !uuid(reservationRow, "transition_id").equals(original.transitionId())
        || !uuid(reservationRow, "reservation_fence").equals(original.reservationFence())
        || !uuid(reservationRow, "issuer_coverage_operation_id")
            .equals(original.issuerCoverageOperationId())
        || !positive(reservationRow, "issuer_coverage_operation_fence")
            .equals(original.issuerCoverageOperationFence())
        || !positive(reservationRow, "coverage_fence").equals(original.coverageFence())
        || !positive(reservationRow, "inventory_snapshot_revision")
            .equals(original.inventorySnapshotRevision())
        || currentReservationRevision.compareTo(original.inventoryRevision()) < 0
        || !bytesEqual(reservationRow, "binding_ref", owner.bindingRef().bytes())
        || !currentBindingGeneration.equals(member.bindingGeneration())
        || !positive(inventoryRow, "inventory_revision")
            .equals(positive(transitionRow, "inventory_revision"))
        || !uuid(transitionRow, "transition_id").equals(original.transitionId())
        || !bytesEqual(transitionRow, "candidate_binding_ref", owner.bindingRef().bytes())
        || !uuid(transitionRow, "tenant_id").equals(owner.tenantId())
        || !uuid(transitionRow, "playable_state_namespace_id")
            .equals(owner.playableStateNamespaceId())
        || !uuid(transitionRow, "character_id").equals(owner.characterId())
        || !positive(transitionRow, "candidate_binding_generation")
            .equals(member.bindingGeneration())
        || !uuid(transitionRow, "candidate_account_index_fence")
            .equals(uuid(operationRow, "candidate_account_index_fence"))
        || !uuid(transitionRow, "issuer_reservation_id").equals(original.reservationId())
        || !uuid(inventoryRow, "transition_id").equals(original.transitionId())
        || !uuid(inventoryRow, "account_index_fence")
            .equals(uuid(operationRow, "candidate_account_index_fence"))
        || !uuid(inventoryRow, "issuer_reservation_id").equals(original.reservationId())
        || transitionRow.get("expected_prior_binding_ref", byte[].class) != null
        || transitionRow.get("expected_prior_binding_generation") != null
        || !bytesEqual(issuerObligation, "binding_ref", owner.bindingRef().bytes())
        || !uuid(issuerObligation, "reservation_id").equals(original.reservationId())
        || !positive(issuerObligation, "binding_generation").equals(member.bindingGeneration())) {
      throw unavailable(
          "The actual V29 issuer transition no longer matches its immutable local operation");
    }
    if (!Arrays.equals(bytes(operationRow, "binding_ref"), owner.bindingRef().bytes())
        || !positive(operationRow, "binding_generation").equals(member.bindingGeneration())) {
      throw unavailable(
          "The issuer operation's frozen candidate differs from the current V29 source");
    }

    boolean bindingPresent;
    boolean counted;
    UUID projectedOperationId = uuid(operationRow, "operation_id");
    byte[] projectedBytes = bytes(operationRow, "member_value_bytes");
    CanonicalGameplayIssuerMemberV2Value projectedMember = member;
    if ("PREPARED".equals(transitionStatus)
        && "CANDIDATE_PREPARED".equals(candidateLifecycle)
        && "RESERVED".equals(reservationLifecycle)
        && "REQUIRED".equals(issuerStatus)
        && releaseOperationId == null) {
      bindingPresent = false;
      counted = true;
    } else if ("PROVISIONAL".equals(transitionStatus)
        && "PROVISIONAL".equals(candidateLifecycle)
        && "RESERVED".equals(reservationLifecycle)
        && "REQUIRED".equals(issuerStatus)
        && releaseOperationId == null) {
      bindingPresent = true;
      counted = true;
    } else if ("COMMITTED".equals(transitionStatus)
        && "ACTIVE".equals(candidateLifecycle)
        && "BOUND".equals(reservationLifecycle)
        && "REQUIRED".equals(issuerStatus)
        && releaseOperationId == null) {
      bindingPresent = true;
      counted = true;
    } else if ("ABORTED".equals(transitionStatus)
        && "TERMINAL_UNRESOLVED".equals(candidateLifecycle)
        && "RELEASE_PENDING".equals(reservationLifecycle)
        && "REQUIRED".equals(issuerStatus)
        && releaseOperationId == null) {
      CanonicalGameplayIssuerBindingLocalReleaseOperationRepository.readProjection(
          transaction,
          operationRow,
          member,
          currentRevision,
          transitionStatus,
          candidateLifecycle,
          reservationLifecycle,
          issuerStatus,
          null);
      bindingPresent = true;
      counted = true;
    } else if ("ABORTED".equals(transitionStatus)
        && "TERMINAL_UNRESOLVED".equals(candidateLifecycle)
        && "RELEASED".equals(reservationLifecycle)
        && "RELEASED".equals(issuerStatus)
        && releaseOperationId != null) {
      var release =
          CanonicalGameplayIssuerBindingLocalReleaseOperationRepository.readProjection(
              transaction,
              operationRow,
              member,
              currentRevision,
              transitionStatus,
              candidateLifecycle,
              reservationLifecycle,
              issuerStatus,
              releaseOperationId);
      if (release == null)
        throw unavailable("Released issuer reservation lacks its durable exact successor");
      projectedOperationId = release.operationId();
      projectedBytes = release.exactMemberValueBytes();
      projectedMember = release.memberValue();
      bindingPresent = false;
      counted = false;
    } else {
      throw unavailable(
          "The current SQL transition phase does not qualify an issuer reservation/member projection");
    }
    if (!positive(inventoryRow, "inventory_revision").equals(currentRevision)
        || !positive(issuerObligation, "inventory_revision").equals(currentRevision)
        || !positive(reservationRow, "inventory_revision").equals(currentRevision)) {
      throw unavailable(
          "The V29 candidate, reservation, and issuer obligation revisions are not one current transition");
    }
    return new CurrentPhase(
        bindingPresent,
        counted,
        reservationLifecycle,
        transitionStatus,
        currentRevision,
        projectedOperationId,
        projectedBytes,
        projectedMember);
  }

  private static void requireOwnerColumns(
      Record row,
      CanonicalGameplayBindingIdentity owner,
      BigInteger partitionId,
      String partitionField) {
    if (!uuid(row, "account_id").equals(owner.accountId())
        || !uuid(row, "tenant_id").equals(owner.tenantId())
        || !uuid(row, "game_instance_id").equals(owner.gameInstanceId())
        || !number(row, "runtime_game_instance_id")
            .equals(BigInteger.valueOf(owner.runtimeGameInstanceId()))
        || !text(row, "session_id").equals(owner.sessionId())
        || !text(row, "issuer_id").equals(owner.issuerId())
        || !positive(row, "issuer_auth_generation").equals(owner.issuerAuthGeneration())
        || !uuid(row, "playable_state_namespace_id").equals(owner.playableStateNamespaceId())
        || !uuid(row, "character_id").equals(owner.characterId())
        || !text(row, "playable_state_scope").equals(owner.playableStateScope())
        || !uuid(row, "region_id").equals(owner.regionId())
        || !positive(row, "region_epoch").equals(owner.regionEpoch())
        || !positive(row, "issuer_index_layout_version").equals(owner.issuerIndexLayoutVersion())
        || !positive(row, "issuer_index_partition_count").equals(owner.issuerIndexPartitionCount())
        || !positive(row, "issuer_index_partition_capacity")
            .equals(owner.issuerIndexPartitionCapacity())
        || !nonnegative(row, partitionField).equals(partitionId)) {
      throw unavailable(
          "The actual issuer reservation or candidate owner differs from the full v2 owner tuple");
    }
  }

  private static Set<UUID> readAndVerifyBootstrapReservationIds(
      DSLContext transaction, CanonicalGameplayIssuerInstalledAnchor anchor) {
    List<Record> rows =
        transaction.fetch(
            "SELECT * FROM "
                + BOOTSTRAP_OPERATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? ORDER BY work_ordinal",
            ISSUER,
            anchor.bootstrapOperationId());
    Set<UUID> reservationIds = new HashSet<>();
    for (Record row : rows) {
      byte[] valueBytes = bytes(row, "member_value_bytes");
      if (!text(row, "member_value_digest")
              .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(valueBytes))
          || !"PENDING_PHYSICAL_PROOF".equals(text(row, "member_status"))
          || !Objects.equals(row.get("member_schema_version", Short.class), (short) 2)) {
        throw unavailable("The installed bootstrap member history is malformed");
      }
      CanonicalGameplayIssuerMemberV2Value value;
      try {
        value = CanonicalGameplayIssuerMemberV2Codec.decode(valueBytes);
      } catch (RuntimeException malformed) {
        throw unavailable(
            "The installed bootstrap member history is not closed schema v2", malformed);
      }
      if (!value.anchor().issuerCoverageOperationId().equals(anchor.issuerCoverageOperationId())
          || !value.anchor().operationFence().equals(anchor.operationFence())
          || !value.anchor().coverageFence().equals(anchor.coverageFence())
          || !value
              .anchor()
              .issuerInventorySnapshotRevision()
              .equals(anchor.issuerInventorySnapshotRevision())
          || !value.owner().issuerId().equals(anchor.issuerId())
          || !value
              .reservation()
              .partitionId()
              .equals(value.owner().bindingRef().partitionId(anchor.partitionCount()))
          || !reservationIds.add(value.reservation().reservationId())
          || !uuid(row, "reservation_id").equals(value.reservation().reservationId())) {
        throw unavailable(
            "The installed bootstrap member history differs from its exact anchor cohort");
      }
    }
    return reservationIds;
  }

  private static BigInteger verifyCurrentReservationAccounting(
      DSLContext transaction,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      int baselineReservationCount) {
    Record localCountRow =
        transaction.fetchOne(
            "SELECT COUNT(*) AS count FROM " + LOCAL_OPERATION + " WHERE issuer_id = ?", ISSUER);
    Record reservationCountRow =
        transaction.fetchOne(
            "SELECT COUNT(*) AS count FROM " + RESERVATION + " WHERE issuer_id = ?", ISSUER);
    Record orphanCountRow =
        transaction.fetchOne(
            "SELECT COUNT(*) AS count FROM "
                + RESERVATION
                + " reservation WHERE reservation.issuer_id = ? AND NOT EXISTS (SELECT 1 FROM "
                + BOOTSTRAP_OPERATION
                + " bootstrap WHERE bootstrap.bootstrap_operation_id = ?"
                + " AND bootstrap.reservation_id = reservation.reservation_id)"
                + " AND NOT EXISTS (SELECT 1 FROM "
                + LOCAL_OPERATION
                + " operation WHERE operation.issuer_id = reservation.issuer_id"
                + " AND operation.reservation_id = reservation.reservation_id)",
            ISSUER,
            anchor.bootstrapOperationId());
    Record missingCountRow =
        transaction.fetchOne(
            "SELECT COUNT(*) AS count FROM "
                + LOCAL_OPERATION
                + " operation WHERE operation.issuer_id = ? AND NOT EXISTS (SELECT 1 FROM "
                + RESERVATION
                + " reservation WHERE reservation.issuer_id = operation.issuer_id"
                + " AND reservation.reservation_id = operation.reservation_id)",
            ISSUER);
    if (localCountRow == null
        || reservationCountRow == null
        || orphanCountRow == null
        || missingCountRow == null) {
      throw unavailable("The complete retained issuer reservation accounting is unavailable");
    }
    BigInteger localCount = number(localCountRow, "count");
    BigInteger reservationCount = number(reservationCountRow, "count");
    if (number(orphanCountRow, "count").signum() != 0
        || number(missingCountRow, "count").signum() != 0
        || !reservationCount.equals(localCount.add(BigInteger.valueOf(baselineReservationCount)))) {
      throw unavailable(
          "Durable issuer reservations differ from full bootstrap and local SQL history");
    }
    return reservationCount;
  }

  private static void verifyRetirementAccounting(DSLContext transaction) {
    Record invalidRows =
        transaction.fetchOne(
            "SELECT COUNT(*) AS count FROM game_session_canonical_issuer_binding_local_retirement retirement"
                + " LEFT JOIN "
                + LOCAL_OPERATION
                + " original ON original.operation_id = retirement.original_local_operation_id"
                + " LEFT JOIN "
                + ISSUER_LOCAL_RELEASE
                + " release ON release.operation_id = retirement.release_operation_id"
                + " LEFT JOIN game_session_canonical_issuer_partition_mutation mutation"
                + " ON mutation.mutation_id = retirement.physical_mutation_id"
                + " LEFT JOIN "
                + RESERVATION
                + " reservation ON reservation.reservation_id = retirement.reservation_id"
                + " LEFT JOIN "
                + INVENTORY
                + " inventory ON inventory.binding_ref = retirement.binding_ref"
                + " LEFT JOIN "
                + TRANSITION
                + " transition ON transition.transition_id = retirement.transition_id"
                + " LEFT JOIN "
                + ISSUER_OBLIGATION
                + " obligation ON obligation.transition_id = retirement.transition_id"
                + " AND obligation.binding_ref = retirement.binding_ref"
                + " LEFT JOIN "
                + ACCOUNT_OBLIGATION
                + " account_obligation ON account_obligation.transition_id = retirement.transition_id"
                + " AND account_obligation.obligation_ordinal = 0"
                + " LEFT JOIN "
                + REGION_OBLIGATION
                + " region_obligation ON region_obligation.transition_id = retirement.transition_id"
                + " WHERE retirement.issuer_id = ? AND (original.operation_id IS NULL OR release.operation_id IS NULL"
                + " OR mutation.mutation_id IS NULL OR original.reservation_id <> retirement.reservation_id"
                + " OR original.binding_ref <> retirement.binding_ref"
                + " OR release.status <> 'RELEASED' OR release.original_local_operation_id <> retirement.original_local_operation_id"
                + " OR release.transition_id <> retirement.transition_id"
                + " OR release.reservation_id <> retirement.reservation_id"
                + " OR release.binding_ref <> retirement.binding_ref"
                + " OR release.original_member_value_bytes <> retirement.original_member_value_bytes"
                + " OR release.released_member_value_bytes <> retirement.released_member_value_bytes"
                + " OR release.source_inventory_revision <> retirement.release_source_inventory_revision"
                + " OR release.completion_inventory_revision <> retirement.release_completion_inventory_revision"
                + " OR mutation.mutation_kind <> 'RETIRE' OR mutation.owner_operation_id <> retirement.retirement_operation_id"
                + " OR mutation.expected_revision <> retirement.expected_revision"
                + " OR mutation.successor_revision <> retirement.successor_revision"
                + " OR mutation.exact_intended_change_bytes <> retirement.exact_intended_change_bytes"
                + " OR mutation.exact_intended_change_digest <> retirement.exact_intended_change_digest"
                + " OR (retirement.status = 'RETIRED' AND (mutation.status <> 'COMMITTED'"
                + " OR mutation.exact_readback_field_set_bytes <> retirement.exact_absence_readback_field_set_bytes))"
                + " OR (retirement.status = 'PENDING_PHYSICAL_PROOF' AND (mutation.status <> 'PENDING_PHYSICAL_PROOF'"
                + " OR reservation.reservation_id IS NULL OR reservation.lifecycle <> 'RELEASED'"
                + " OR reservation.inventory_revision <> retirement.release_completion_inventory_revision"
                + " OR reservation.binding_ref <> retirement.binding_ref"
                + " OR inventory.binding_ref IS NULL OR inventory.lifecycle <> 'TERMINAL_UNRESOLVED'"
                + " OR inventory.inventory_revision <> retirement.release_completion_inventory_revision"
                + " OR inventory.transition_id <> retirement.transition_id"
                + " OR transition.transition_id IS NULL OR transition.status <> 'ABORTED'"
                + " OR transition.inventory_revision <> retirement.release_completion_inventory_revision"
                + " OR transition.candidate_binding_ref <> retirement.binding_ref"
                + " OR transition.issuer_reservation_id <> retirement.reservation_id"
                + " OR obligation.transition_id IS NULL OR obligation.status <> 'RELEASED'"
                + " OR obligation.local_release_operation_id <> retirement.release_operation_id"
                + " OR obligation.inventory_revision <> retirement.release_completion_inventory_revision"
                + " OR account_obligation.transition_id IS NULL"
                + " OR account_obligation.inventory_revision <> retirement.release_completion_inventory_revision"
                + " OR region_obligation.transition_id IS NULL"
                + " OR region_obligation.inventory_revision <> retirement.release_completion_inventory_revision)))",
            ISSUER);
    if (invalidRows == null || number(invalidRows, "count").signum() != 0) {
      throw unavailable(
          "A retained physical retirement does not match its exact SQL release and mutation history");
    }
  }

  private static void requireQualifiedBaseline(
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof proof,
      Map<String, byte[]> fields) {
    String headerField = CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD;
    byte[] header = fields.get(headerField);
    if (header == null || !Arrays.equals(header, proofMetadata(proof))) {
      throw unavailable("The installed issuer baseline header is missing or changed");
    }
    CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.requireExactMetadata(header, header);
    Set<UUID> reservations = new HashSet<>();
    Set<String> bindings = new HashSet<>();
    Map<UUID, byte[]> capacityMembers = new HashMap<>();
    Map<String, UUID> bindingReservations = new HashMap<>();
    int countedCapacity = 0;
    int reservationCount = 0;
    int bindingCount = 0;
    for (Map.Entry<String, byte[]> field : fields.entrySet()) {
      if (field.getKey().equals(headerField)
          || field
              .getKey()
              .equals(
                  CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MUTATION_REVISION_FIELD)) {
        continue;
      }
      CanonicalGameplayIssuerMemberV2Value value;
      try {
        value = CanonicalGameplayIssuerMemberV2Codec.decode(field.getValue());
      } catch (RuntimeException malformed) {
        throw unavailable(
            "The exact issuer baseline contains a malformed or legacy member value", malformed);
      }
      requireMemberMatchesAnchor(value, anchor);
      if (!value.reservation().partitionId().equals(proof.partitionId())
          || !value
              .owner()
              .bindingRef()
              .partitionId(anchor.partitionCount())
              .equals(proof.partitionId())) {
        throw unavailable(
            "An immutable issuer baseline member is stored under the wrong partition");
      }
      String name = field.getKey();
      if (name.startsWith(
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.RESERVATION_FIELD_PREFIX)) {
        UUID reservationId = parseReservationField(name);
        if (!reservationId.equals(value.reservation().reservationId())
            || !reservations.add(reservationId)) {
          throw unavailable(
              "The immutable issuer baseline has a duplicate or conflicting capacity field");
        }
        capacityMembers.put(reservationId, field.getValue());
        reservationCount++;
        if (value.reservation().lifecycle()
            != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED) {
          countedCapacity++;
        }
      } else {
        if (name.startsWith("!")
            || !Arrays.equals(
                name.getBytes(StandardCharsets.UTF_8), value.owner().bindingRef().bytes())
            || !bindings.add(name)) {
          throw unavailable("The immutable issuer baseline has an invalid canonical binding field");
        }
        UUID reservationId = value.reservation().reservationId();
        if (bindingReservations.putIfAbsent(name, reservationId) != null) {
          throw unavailable("The immutable issuer baseline repeats a binding owner");
        }
        bindingCount++;
      }
    }
    Set<UUID> boundReservations = new HashSet<>();
    for (Map.Entry<String, UUID> binding : bindingReservations.entrySet()) {
      byte[] capacityValue = capacityMembers.get(binding.getValue());
      byte[] bindingValue = fields.get(binding.getKey());
      if (capacityValue == null
          || !Arrays.equals(capacityValue, bindingValue)
          || !boundReservations.add(binding.getValue())) {
        throw unavailable(
            "An immutable issuer baseline binding has no exact unique capacity reservation");
      }
    }
    if (reservationCount != proof.physicalReservationCount()
        || bindingCount != proof.physicalBindingCount()
        || countedCapacity != proof.physicalCountedCapacityCount()
        || fields.size() != 2 + reservationCount + bindingCount) {
      throw unavailable(
          "The immutable issuer baseline field set does not match its retained counts");
    }
  }

  private static byte[] proofMetadata(
      CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof proof) {
    Map<String, byte[]> fields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
            proof.exactPhysicalFieldSetBytes());
    byte[] metadata =
        fields.get(CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD);
    if (metadata == null) throw unavailable("The installed issuer baseline has no metadata header");
    return metadata;
  }

  static void requireMemberMatchesAnchor(
      CanonicalGameplayIssuerMemberV2Value value, CanonicalGameplayIssuerInstalledAnchor anchor) {
    if (!value.owner().issuerId().equals(anchor.issuerId())
        || !value.owner().issuerAuthGeneration().equals(anchor.issuerAuthGeneration())
        || !value.owner().issuerIndexLayoutVersion().equals(anchor.layoutVersion())
        || !value.owner().issuerIndexPartitionCount().equals(anchor.partitionCount())
        || !value.owner().issuerIndexPartitionCapacity().equals(anchor.partitionCapacity())
        || !value.issuerControlEpoch().equals(anchor.controlEpoch())
        || !value.anchor().issuerCoverageOperationId().equals(anchor.issuerCoverageOperationId())
        || !value.anchor().operationFence().equals(anchor.operationFence())
        || !value.anchor().coverageFence().equals(anchor.coverageFence())
        || !value
            .anchor()
            .issuerInventorySnapshotRevision()
            .equals(anchor.issuerInventorySnapshotRevision())
        || !Arrays.equals(
            value.layoutDescriptor().exactDescriptorBytes(), anchor.layoutDescriptorBytes())
        || !value.layoutDescriptor().descriptorDigest().equals(anchor.layoutDescriptorDigest())
        || !Arrays.equals(
            value.sourceCheckpoint().exactCheckpointBytes(), anchor.sourceCheckpointBytes())
        || !value.sourceCheckpoint().checkpointDigest().equals(anchor.sourceCheckpointDigest())
        || !Arrays.equals(
            value.sourceCheckpoint().latestIssuerEventBytes(), anchor.sourceEventBytes())
        || !Objects.equals(
            value.sourceCheckpoint().latestIssuerEventDigest(), anchor.sourceEventDigest())
        || !Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(value.incarnation()),
            anchor.incarnationBytes())) {
      throw unavailable(
          "An issuer member differs from the exact installed anchor, layout, source, or incarnation");
    }
  }

  private static void requireAnchorColumns(
      Record row, CanonicalGameplayIssuerInstalledAnchor anchor) {
    if (!uuid(row, "anchor_bootstrap_operation_id").equals(anchor.bootstrapOperationId())
        || !uuid(row, "anchor_bootstrap_request_id").equals(anchor.bootstrapRequestId())
        || !uuid(row, "anchor_build_operation_id").equals(anchor.buildOperationId())
        || !text(row, "anchor_build_plan_digest").equals(anchor.buildPlanDigest())
        || !positive(row, "anchor_control_epoch").equals(anchor.controlEpoch())
        || !uuid(row, "anchor_issuer_coverage_operation_id")
            .equals(anchor.issuerCoverageOperationId())
        || !positive(row, "anchor_operation_fence").equals(anchor.operationFence())
        || !positive(row, "anchor_coverage_fence").equals(anchor.coverageFence())
        || !positive(row, "anchor_issuer_inventory_snapshot_revision")
            .equals(anchor.issuerInventorySnapshotRevision())
        || !positive(row, "anchor_layout_version").equals(anchor.layoutVersion())
        || !positive(row, "anchor_partition_count").equals(anchor.partitionCount())
        || !positive(row, "anchor_partition_capacity").equals(anchor.partitionCapacity())
        || !text(row, "anchor_mapping_identity").equals(anchor.mappingIdentity())
        || !positive(row, "anchor_issuer_auth_generation").equals(anchor.issuerAuthGeneration())) {
      throw unavailable("A durable local issuer anchor column differs from its immutable anchor");
    }
  }

  private static void requireCurrentSourceMatches(
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState state) {
    if (!sameCheckpoint(state.accountCheckpoint(), anchor)
        || !Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(state.incarnation()),
            anchor.incarnationBytes())) {
      throw unavailable(
          "Current Account issuer source or storage incarnation differs from the installed anchor");
    }
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      readCurrentIssuerState() {
    requireNoAmbientTransaction();
    try {
      return Objects.requireNonNull(
          currentIssuerStateSource.readFreshCurrentState(),
          "current issuer state source returned null");
    } catch (RuntimeException failure) {
      throw unavailable(
          "The current issuer checkpoint or storage incarnation is unavailable", failure);
    }
  }

  private static void requireSameCurrentIssuerState(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState left,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState right) {
    var leftCheckpoint = left.accountCheckpoint();
    var rightCheckpoint = right.accountCheckpoint();
    if (!leftCheckpoint.issuerId().equals(rightCheckpoint.issuerId())
        || !leftCheckpoint.issuerAuthGeneration().equals(rightCheckpoint.issuerAuthGeneration())
        || !Arrays.equals(
            leftCheckpoint.exactCheckpointBytes(), rightCheckpoint.exactCheckpointBytes())
        || !leftCheckpoint.checkpointDigest().equals(rightCheckpoint.checkpointDigest())
        || !Arrays.equals(
            leftCheckpoint.latestIssuerEventBytes(), rightCheckpoint.latestIssuerEventBytes())
        || !Objects.equals(
            leftCheckpoint.latestIssuerEventDigest(), rightCheckpoint.latestIssuerEventDigest())
        || !Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(left.incarnation()),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(right.incarnation()))) {
      throw unavailable("The issuer source or storage incarnation changed during the SQL read");
    }
  }

  private static boolean sameCheckpoint(
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint current,
      CanonicalGameplayIssuerInstalledAnchor anchor) {
    return current != null
        && current.issuerId().equals(anchor.issuerId())
        && current.issuerAuthGeneration().equals(anchor.issuerAuthGeneration())
        && Arrays.equals(current.exactCheckpointBytes(), anchor.sourceCheckpointBytes())
        && current.checkpointDigest().equals(anchor.sourceCheckpointDigest())
        && Arrays.equals(current.latestIssuerEventBytes(), anchor.sourceEventBytes())
        && Objects.equals(current.latestIssuerEventDigest(), anchor.sourceEventDigest());
  }

  private static void requireSameAnchor(
      CanonicalGameplayIssuerInstalledAnchor expected,
      CanonicalGameplayIssuerInstalledAnchor current) {
    if (!Arrays.equals(expected.exactAnchorBytes(), current.exactAnchorBytes())
        || !expected.exactAnchorDigest().equals(current.exactAnchorDigest())) {
      throw unavailable("The positively installed issuer anchor changed");
    }
  }

  private static void lockInventoryClock(DSLContext transaction) {
    Record row =
        transaction.fetchOne(
            "SELECT inventory_revision FROM "
                + INVENTORY_CLOCK
                + " WHERE singleton_id = 1 FOR UPDATE");
    if (row == null || number(row, "inventory_revision").signum() < 0) {
      throw unavailable("The durable binding inventory serialization clock is unavailable");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw unavailable("Issuer physical projection cannot run inside an ambient SQL transaction");
    }
  }

  private static void putUnique(Map<String, byte[]> fields, String field, byte[] value) {
    if (fields.putIfAbsent(field, value.clone()) != null) {
      throw unavailable("Two SQL owner operations claim the same issuer partition field");
    }
  }

  private static long totalFieldBytes(Map<String, byte[]> fields) {
    long total = 0;
    for (Map.Entry<String, byte[]> field : fields.entrySet()) {
      total =
          Math.addExact(
              total,
              field.getKey().getBytes(StandardCharsets.UTF_8).length
                  + (long) field.getValue().length);
    }
    return total;
  }

  private static UUID parseReservationField(String field) {
    try {
      String suffix =
          field.substring(
              CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.RESERVATION_FIELD_PREFIX
                  .length());
      UUID value = UUID.fromString(suffix);
      if (!value.toString().equals(suffix) || value.equals(new UUID(0L, 0L)))
        throw unavailable("Capacity field identity is not canonical");
      return value;
    } catch (RuntimeException malformed) {
      throw unavailable("Capacity field identity is malformed", malformed);
    }
  }

  private static byte[] currentnessDigest(
      CanonicalGameplayIssuerInstalledAnchor anchor,
      byte[] baselineBytes,
      List<LocalEntry> entries,
      BigInteger partitionCapacity,
      UUID currentRevision,
      UUID pendingMutationId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, anchor.exactAnchorBytes());
      update(digest, baselineBytes);
      for (LocalEntry entry :
          entries.stream().sorted(Comparator.comparing(LocalEntry::operationId)).toList()) {
        update(digest, entry.operationId().toString().getBytes(StandardCharsets.US_ASCII));
        update(
            digest, entry.registeredOperationId().toString().getBytes(StandardCharsets.US_ASCII));
        update(digest, entry.exactRegisteredMemberValueBytes());
        update(digest, entry.exactMemberValueBytes());
        update(digest, entry.transitionStatus().getBytes(StandardCharsets.US_ASCII));
        update(digest, entry.lifecycle().getBytes(StandardCharsets.US_ASCII));
        update(digest, entry.inventoryRevision().toString().getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) (entry.bindingPresent() ? 1 : 0));
        digest.update((byte) (entry.countsTowardCapacity() ? 1 : 0));
        digest.update((byte) (entry.capacityMustBePresent() ? 1 : 0));
        digest.update((byte) (entry.bindingMustBePresent() ? 1 : 0));
      }
      update(digest, partitionCapacity.toString().getBytes(StandardCharsets.US_ASCII));
      update(digest, currentRevision.toString().getBytes(StandardCharsets.US_ASCII));
      update(
          digest,
          pendingMutationId == null
              ? new byte[0]
              : pendingMutationId.toString().getBytes(StandardCharsets.US_ASCII));
      return digest.digest();
    } catch (Exception impossible) {
      throw new IllegalStateException("Could not retain local issuer SQL currentness", impossible);
    }
  }

  private static void update(MessageDigest digest, byte[] bytes) {
    digest.update(BigInteger.valueOf(bytes.length).toByteArray());
    digest.update(bytes);
  }

  private static BigInteger number(Record row, String field) {
    Object value = row.get(field);
    if (!(value instanceof Number number)) throw unavailable(field + " is missing or nonnumeric");
    try {
      if (number instanceof BigDecimal decimal) return decimal.toBigIntegerExact();
      return BigInteger.valueOf(number.longValue());
    } catch (ArithmeticException malformed) {
      throw unavailable(field + " is not an exact integer", malformed);
    }
  }

  private static BigInteger positive(Record row, String field) {
    BigInteger value = number(row, field);
    if (value.signum() <= 0
        || value.toString().length() > CanonicalGameplayIssuerMemberV2Codec.MAX_COUNTER_DIGITS) {
      throw unavailable(field + " is not a bounded positive counter");
    }
    return value;
  }

  private static BigInteger nonnegative(Record row, String field) {
    BigInteger value = number(row, field);
    if (value.signum() < 0
        || value.toString().length() > CanonicalGameplayIssuerMemberV2Codec.MAX_COUNTER_DIGITS) {
      throw unavailable(field + " is not a bounded nonnegative counter");
    }
    return value;
  }

  private static int integer(Record row, String field) {
    try {
      return number(row, field).intValueExact();
    } catch (ArithmeticException malformed) {
      throw unavailable(field + " is outside its bounded integer range", malformed);
    }
  }

  private static UUID uuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null) throw unavailable(field + " is missing");
    return value;
  }

  private static String text(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw unavailable(field + " is missing");
    return value;
  }

  private static byte[] bytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0) throw unavailable(field + " is missing or empty");
    return value;
  }

  private static boolean bytesEqual(Record row, String field, byte[] expected) {
    return Arrays.equals(bytes(row, field), expected);
  }

  private static boolean nullableBytesEqual(Record row, String field, byte[] expected) {
    return Arrays.equals(row.get(field, byte[].class), expected);
  }

  private static IllegalStateException unavailable(String message) {
    return new IllegalStateException("Issuer binding-local projection is unavailable: " + message);
  }

  private static IllegalStateException unavailable(String message, Throwable cause) {
    return new IllegalStateException(
        "Issuer binding-local projection is unavailable: " + message, cause);
  }

  private record CurrentPhase(
      boolean bindingPresent,
      boolean countsTowardCapacity,
      String lifecycle,
      String transitionStatus,
      BigInteger inventoryRevision,
      UUID operationId,
      byte[] memberValueBytes,
      CanonicalGameplayIssuerMemberV2Value memberValue) {}

  record LocalEntry(
      UUID registeredOperationId,
      UUID operationId,
      byte[] exactRegisteredMemberValueBytes,
      byte[] exactMemberValueBytes,
      CanonicalGameplayIssuerMemberV2Value registeredMemberValue,
      CanonicalGameplayIssuerMemberV2Value memberValue,
      boolean bindingPresent,
      boolean countsTowardCapacity,
      String lifecycle,
      String transitionStatus,
      BigInteger inventoryRevision,
      boolean capacityMustBePresent,
      boolean bindingMustBePresent) {
    LocalEntry {
      exactRegisteredMemberValueBytes = exactRegisteredMemberValueBytes.clone();
      exactMemberValueBytes = exactMemberValueBytes.clone();
    }

    @Override
    public byte[] exactRegisteredMemberValueBytes() {
      return exactRegisteredMemberValueBytes.clone();
    }

    @Override
    public byte[] exactMemberValueBytes() {
      return exactMemberValueBytes.clone();
    }
  }

  /** Private-construction output of the SQL owner; it has no public/raw-list factory. */
  static final class PreparedPartitionWork {
    private final CanonicalGameplayIssuerInstalledAnchor anchor;
    private final CanonicalGameplayIssuerBindingLocalOperation targetOperation;
    private final CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof
        baselineProof;
    private final Map<String, byte[]> baselineFields;
    private final Map<String, byte[]> allowedFields;
    private final List<LocalEntry> localEntries;
    private final LocalEntry targetEntry;
    private final BigInteger aggregateCapacity;
    private final BigInteger aggregateBound;
    private final BigInteger partitionCapacity;
    private final UUID currentRevision;
    private final UUID pendingMutationId;
    private final CanonicalGameplayIssuerPartitionMutation physicalMutation;
    private final byte[] sqlCurrentnessDigest;
    private final boolean requiresCurrentTargetAccountControl;

    private PreparedPartitionWork(
        CanonicalGameplayIssuerInstalledAnchor anchor,
        CanonicalGameplayIssuerBindingLocalOperation targetOperation,
        CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof
            baselineProof,
        Map<String, byte[]> baselineFields,
        Map<String, byte[]> allowedFields,
        List<LocalEntry> localEntries,
        LocalEntry targetEntry,
        BigInteger aggregateCapacity,
        BigInteger aggregateBound,
        BigInteger partitionCapacity,
        UUID currentRevision,
        UUID pendingMutationId,
        CanonicalGameplayIssuerPartitionMutation physicalMutation,
        byte[] sqlCurrentnessDigest,
        boolean requiresCurrentTargetAccountControl) {
      this.anchor = anchor;
      this.targetOperation = targetOperation;
      this.baselineProof = baselineProof;
      this.baselineFields = copyFields(baselineFields);
      this.allowedFields = copyFields(allowedFields);
      this.localEntries = List.copyOf(localEntries);
      this.targetEntry = targetEntry;
      this.aggregateCapacity = aggregateCapacity;
      this.aggregateBound = aggregateBound;
      this.partitionCapacity = partitionCapacity;
      this.currentRevision = Objects.requireNonNull(currentRevision, "currentRevision");
      this.pendingMutationId = pendingMutationId;
      this.physicalMutation = physicalMutation;
      this.sqlCurrentnessDigest = sqlCurrentnessDigest.clone();
      this.requiresCurrentTargetAccountControl = requiresCurrentTargetAccountControl;
    }

    CanonicalGameplayIssuerInstalledAnchor anchor() {
      return anchor;
    }

    CanonicalGameplayIssuerBindingLocalOperation targetOperation() {
      return targetOperation;
    }

    boolean requiresCurrentTargetAccountControl() {
      return requiresCurrentTargetAccountControl;
    }

    CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof
        baselineProof() {
      return baselineProof;
    }

    Map<String, byte[]> baselineFields() {
      return copyFields(baselineFields);
    }

    Map<String, byte[]> allowedFields() {
      return copyFields(allowedFields);
    }

    List<LocalEntry> localEntries() {
      return localEntries;
    }

    LocalEntry targetEntry() {
      return targetEntry;
    }

    BigInteger aggregateCapacity() {
      return aggregateCapacity;
    }

    BigInteger aggregateBound() {
      return aggregateBound;
    }

    BigInteger partitionCapacity() {
      return partitionCapacity;
    }

    UUID currentRevision() {
      return currentRevision;
    }

    UUID pendingMutationId() {
      return pendingMutationId;
    }

    CanonicalGameplayIssuerPartitionMutation physicalMutation() {
      return physicalMutation;
    }

    byte[] sqlCurrentnessDigest() {
      return sqlCurrentnessDigest.clone();
    }

    private static Map<String, byte[]> copyFields(Map<String, byte[]> source) {
      Map<String, byte[]> copy = new HashMap<>();
      source.forEach((key, value) -> copy.put(key, value.clone()));
      return Map.copyOf(copy);
    }
  }
}
