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
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalAcknowledgement.Anchor;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalAcknowledgement.Capacity;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalAcknowledgement.CurrentSource;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalAcknowledgement.Evidence;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalAcknowledgement.MemberOperation;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalAcknowledgement.PhysicalReadback;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalAcknowledgement.Reservation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL owner for immutable ordinary NEW issuer binding-local physical acknowledgement receipts. */
final class CanonicalGameplayIssuerBindingLocalAcknowledgementRepository {
  private static final String ACKNOWLEDGEMENT =
      "game_session_canonical_issuer_binding_local_acknowledgement";
  private static final String GENERATION_COUNTER =
      "game_session_canonical_issuer_binding_local_ack_gen_counter";
  private static final int MAX_FIELD_COUNT =
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_HASH_FIELDS
          + 2 * CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_MEMBER_OPERATIONS;

  private final DSLContext dsl;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentIssuerStateSource;
  private final CanonicalGameplayIssuerBindingLocalProjectionRepository projectionRepository;

  CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentIssuerStateSource) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.currentIssuerStateSource =
        Objects.requireNonNull(currentIssuerStateSource, "currentIssuerStateSource");
    this.projectionRepository =
        new CanonicalGameplayIssuerBindingLocalProjectionRepository(dsl, currentIssuerStateSource);
  }

  /**
   * Records the exact first physical acknowledgement only from the private readback produced by the
   * real one-key Redis owner. SQL rechecks the installed anchor and all current source rows under
   * the inventory clock; no transaction spans Redis or the external current-source reads.
   */
  CanonicalGameplayIssuerBindingLocalAcknowledgement acknowledge(
      CanonicalGameplayIssuerBindingLocalRedisOwner.Readback readback) {
    Objects.requireNonNull(readback, "readback");
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBindingLocalOperation operation = readback.operation();
    var before = readCurrentIssuerState("before acknowledgement");
    CanonicalGameplayIssuerBindingLocalAcknowledgement acknowledged =
        dsl.transactionResult(
            configuration -> {
              DSLContext transaction = org.jooq.impl.DSL.using(configuration);
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              var work =
                  projectionRepository.readPreparedPartitionWorkInTransaction(
                      transaction, operation, before);
              requireCurrentIssuerSource(operation, before);
              requireEligibleCandidate(work);

              Record existing =
                  transaction.fetchOne(
                      "SELECT * FROM " + ACKNOWLEDGEMENT + " WHERE operation_id = ? FOR UPDATE",
                      operation.operationId());
              if (existing != null) {
                var prior = readAcknowledgement(transaction, existing);
                requireSameOperation(prior.evidence(), operation);
                requireSameCurrentSources(prior.evidence(), operation, before);
                requireReadbackTargetStillMatches(prior.evidence(), readback);
                return prior;
              }

              if (!Arrays.equals(readback.sqlCurrentnessDigest(), work.sqlCurrentnessDigest())) {
                throw unavailable(
                    "SQL issuer cohort changed after physical readback; retry the original operation");
              }
              ReadbackProof proof = validateReadback(operation, work, readback, before);
              // The independent transition generation advances only in this SQL transaction,
              // after both the exact current SQL operation and private physical readback validate.
              // Counter advancement and immutable receipt insertion commit or roll back together.
              BigInteger acknowledgementGeneration =
                  allocateAcknowledgementGeneration(transaction, operation.anchor().issuerId());
              Evidence evidence =
                  buildEvidence(operation, readback, before, proof, acknowledgementGeneration);
              var result = CanonicalGameplayIssuerBindingLocalAcknowledgement.create(evidence);
              insert(transaction, result);
              Record stored =
                  transaction.fetchOne(
                      "SELECT * FROM " + ACKNOWLEDGEMENT + " WHERE operation_id = ? FOR UPDATE",
                      operation.operationId());
              if (stored == null) throw unavailable("Durable issuer acknowledgement disappeared");
              var durable = readAcknowledgement(transaction, stored);
              if (!Arrays.equals(result.exactBytes(), durable.exactBytes())
                  || !result.digest().equals(durable.digest())) {
                throw unavailable(
                    "Durable issuer acknowledgement readback differs from its source");
              }
              return durable;
            });
    var after = readCurrentIssuerState("after acknowledgement");
    if (!sameCurrentIssuerState(before, after)) {
      throw unavailable(
          "Issuer source or storage incarnation moved after acknowledgement persistence; the exact historical receipt remains retained");
    }
    return acknowledged;
  }

  /** Reads and integrity-checks retained evidence without implying that it is currently usable. */
  CanonicalGameplayIssuerBindingLocalAcknowledgement readHistorical(UUID operationId) {
    Objects.requireNonNull(operationId, "operationId");
    requireNoAmbientTransaction();
    Record row =
        dsl.fetchOne("SELECT * FROM " + ACKNOWLEDGEMENT + " WHERE operation_id = ?", operationId);
    if (row == null) return null;
    return readAcknowledgement(dsl, row);
  }

  private void insert(
      DSLContext transaction, CanonicalGameplayIssuerBindingLocalAcknowledgement acknowledgement) {
    Evidence evidence = acknowledgement.evidence();
    Reservation reservation = evidence.reservation();
    Anchor anchor = evidence.anchor();
    MemberOperation memberOperation = evidence.memberOperation();
    transaction.execute(
        "INSERT INTO "
            + ACKNOWLEDGEMENT
            + " (operation_id, acknowledgement_generation, issuer_id, request_id, transition_id, binding_ref, binding_generation,"
            + " reservation_id, reservation_fence, original_issuer_coverage_operation_id,"
            + " original_issuer_coverage_operation_fence, original_coverage_fence,"
            + " original_inventory_snapshot_revision, original_inventory_revision,"
            + " anchor_bootstrap_operation_id, anchor_issuer_coverage_operation_id,"
            + " anchor_operation_fence, anchor_coverage_fence, anchor_inventory_snapshot_revision,"
            + " anchor_global_coverage_generation,"
            + " issuer_control_epoch, local_member_operation_id, local_member_operation_fence,"
            + " local_member_generation, physical_partition_id, physical_field_set_digest,"
            + " sql_currentness_digest, acknowledgement_schema_version, acknowledgement_bytes,"
            + " acknowledgement_digest)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (operation_id) DO NOTHING",
        evidence.operationId(),
        evidence.issuerBindingLocalAcknowledgementGeneration(),
        evidence.issuerId(),
        evidence.requestId(),
        evidence.transitionId(),
        evidence.bindingRef(),
        evidence.bindingGeneration(),
        reservation.reservationId(),
        reservation.reservationFence(),
        reservation.issuerCoverageOperationId(),
        reservation.issuerCoverageOperationFence(),
        reservation.coverageFence(),
        reservation.inventorySnapshotRevision(),
        reservation.inventoryRevision(),
        anchor.bootstrapOperationId(),
        anchor.issuerCoverageOperationId(),
        anchor.operationFence(),
        anchor.coverageFence(),
        anchor.inventorySnapshotRevision(),
        anchor.globalCoverageGeneration(),
        anchor.controlEpoch(),
        memberOperation.operationId(),
        memberOperation.operationFence(),
        memberOperation.generation(),
        reservation.partitionId(),
        evidence.physicalReadback().partitionFieldSetDigest(),
        evidence.sqlCurrentnessDigest(),
        (short) CanonicalGameplayIssuerBindingLocalAcknowledgement.SCHEMA_VERSION,
        acknowledgement.exactBytes(),
        acknowledgement.digest());
  }

  private static BigInteger allocateAcknowledgementGeneration(
      DSLContext transaction, String issuerId) {
    transaction.execute(
        "INSERT INTO "
            + GENERATION_COUNTER
            + " (issuer_id, last_issued_binding_local_acknowledgement_generation, counter_version)"
            + " VALUES (?, 0, 0) ON CONFLICT (issuer_id) DO NOTHING",
        issuerId);
    Record counter =
        transaction.fetchOne(
            "SELECT * FROM " + GENERATION_COUNTER + " WHERE issuer_id = ? FOR UPDATE", issuerId);
    if (counter == null)
      throw unavailable("Issuer binding-local ACK generation counter is missing");

    BigInteger previous =
        nonnegative(counter, "last_issued_binding_local_acknowledgement_generation");
    Long versionValue = counter.get("counter_version", Long.class);
    if (versionValue == null
        || versionValue < 0
        || (versionValue == 0 && previous.signum() != 0)
        || (versionValue > 0 && previous.signum() == 0)) {
      throw unavailable("Issuer binding-local ACK generation counter is malformed");
    }
    Record latestReceipt =
        transaction.fetchOne(
            "SELECT MAX(acknowledgement_generation) AS latest_generation FROM "
                + ACKNOWLEDGEMENT
                + " WHERE issuer_id = ?",
            issuerId);
    Object latestValue = latestReceipt == null ? null : latestReceipt.get("latest_generation");
    BigInteger latest =
        latestValue == null
            ? BigInteger.ZERO
            : numberValue(latestValue, "latest acknowledgement generation");
    if (!previous.equals(latest)) {
      throw unavailable(
          "Issuer binding-local ACK generation counter differs from retained receipts");
    }

    BigInteger next = previous.add(BigInteger.ONE);
    if (next.toString().length() > 256) {
      throw unavailable("Issuer binding-local ACK generation is outside its bound");
    }
    long nextVersion;
    try {
      nextVersion = Math.addExact(versionValue, 1L);
    } catch (ArithmeticException overflow) {
      throw unavailable(
          "Issuer binding-local ACK generation counter version is exhausted", overflow);
    }
    Record updated =
        transaction.fetchOne(
            "UPDATE "
                + GENERATION_COUNTER
                + " SET last_issued_binding_local_acknowledgement_generation = ?,"
                + " counter_version = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE issuer_id = ? AND last_issued_binding_local_acknowledgement_generation = ?"
                + " AND counter_version = ? RETURNING issuer_id",
            next,
            nextVersion,
            issuerId,
            previous,
            versionValue);
    if (updated == null) {
      throw unavailable("Issuer binding-local ACK generation compare-and-set lost");
    }
    return next;
  }

  private static BigInteger numberValue(Object value, String field) {
    if (!(value instanceof Number number)) throw unavailable(field + " is missing or nonnumeric");
    try {
      if (number instanceof BigDecimal decimal) return decimal.toBigIntegerExact();
      return BigInteger.valueOf(number.longValue());
    } catch (ArithmeticException malformed) {
      throw unavailable(field + " is not an exact integer", malformed);
    }
  }

  private static CanonicalGameplayIssuerBindingLocalAcknowledgement readAcknowledgement(
      DSLContext query, Record row) {
    byte[] exactBytes = bytes(row, "acknowledgement_bytes");
    CanonicalGameplayIssuerBindingLocalAcknowledgement acknowledgement;
    try {
      acknowledgement = CanonicalGameplayIssuerBindingLocalAcknowledgement.decode(exactBytes);
    } catch (RuntimeException malformed) {
      throw unavailable("Stored issuer binding-local acknowledgement is malformed", malformed);
    }
    Evidence evidence = acknowledgement.evidence();
    Record operation =
        query.fetchOne(
            "SELECT * FROM "
                + "game_session_canonical_issuer_binding_local_operation"
                + " WHERE operation_id = ?",
            evidence.operationId());
    Record installedAnchor =
        query.fetchOne(
            "SELECT global_coverage_generation, exact_anchor_bytes"
                + " FROM game_session_canonical_issuer_installed_anchor WHERE issuer_id = ?",
            evidence.issuerId());
    Record generationCounter =
        query.fetchOne(
            "SELECT last_issued_binding_local_acknowledgement_generation"
                + " FROM game_session_canonical_issuer_binding_local_ack_gen_counter"
                + " WHERE issuer_id = ?",
            evidence.issuerId());
    if (!uuid(row, "operation_id").equals(evidence.operationId())
        || !positive(row, "acknowledgement_generation")
            .equals(evidence.issuerBindingLocalAcknowledgementGeneration())
        || !GameSessionAccountDelegationProfile.ISSUER.equals(text(row, "issuer_id"))
        || !text(row, "issuer_id").equals(evidence.issuerId())
        || !uuid(row, "request_id").equals(evidence.requestId())
        || !uuid(row, "transition_id").equals(evidence.transitionId())
        || !Arrays.equals(bytes(row, "binding_ref"), evidence.bindingRef())
        || !positive(row, "binding_generation").equals(evidence.bindingGeneration())
        || !uuid(row, "reservation_id").equals(evidence.reservation().reservationId())
        || !uuid(row, "reservation_fence").equals(evidence.reservation().reservationFence())
        || !uuid(row, "original_issuer_coverage_operation_id")
            .equals(evidence.reservation().issuerCoverageOperationId())
        || !positive(row, "original_issuer_coverage_operation_fence")
            .equals(evidence.reservation().issuerCoverageOperationFence())
        || !positive(row, "original_coverage_fence").equals(evidence.reservation().coverageFence())
        || !positive(row, "original_inventory_snapshot_revision")
            .equals(evidence.reservation().inventorySnapshotRevision())
        || !positive(row, "original_inventory_revision")
            .equals(evidence.reservation().inventoryRevision())
        || !uuid(row, "anchor_bootstrap_operation_id")
            .equals(evidence.anchor().bootstrapOperationId())
        || !uuid(row, "anchor_issuer_coverage_operation_id")
            .equals(evidence.anchor().issuerCoverageOperationId())
        || !positive(row, "anchor_operation_fence").equals(evidence.anchor().operationFence())
        || !positive(row, "anchor_coverage_fence").equals(evidence.anchor().coverageFence())
        || !positive(row, "anchor_inventory_snapshot_revision")
            .equals(evidence.anchor().inventorySnapshotRevision())
        || !positive(row, "anchor_global_coverage_generation")
            .equals(evidence.anchor().globalCoverageGeneration())
        || !positive(row, "issuer_control_epoch").equals(evidence.anchor().controlEpoch())
        || !uuid(row, "local_member_operation_id").equals(evidence.memberOperation().operationId())
        || !positive(row, "local_member_operation_fence")
            .equals(evidence.memberOperation().operationFence())
        || !positive(row, "local_member_generation").equals(evidence.memberOperation().generation())
        || !nonnegative(row, "physical_partition_id").equals(evidence.reservation().partitionId())
        || !text(row, "physical_field_set_digest")
            .equals(evidence.physicalReadback().partitionFieldSetDigest())
        || !text(row, "sql_currentness_digest").equals(evidence.sqlCurrentnessDigest())
        || !Objects.equals(row.get("acknowledgement_schema_version", Short.class), (short) 1)
        || !acknowledgement.digest().equals(text(row, "acknowledgement_digest"))
        || installedAnchor == null
        || !positive(installedAnchor, "global_coverage_generation")
            .equals(evidence.anchor().globalCoverageGeneration())
        || !Arrays.equals(
            bytes(installedAnchor, "exact_anchor_bytes"), evidence.anchor().exactBytes())
        || generationCounter == null
        || nonnegative(generationCounter, "last_issued_binding_local_acknowledgement_generation")
                .compareTo(evidence.issuerBindingLocalAcknowledgementGeneration())
            < 0
        || operation == null
        || !"PENDING_PHYSICAL_PROOF".equals(text(operation, "member_status"))
        || !Objects.equals(operation.get("member_schema_version", Short.class), (short) 2)
        || !text(operation, "issuer_id").equals(evidence.issuerId())
        || !uuid(operation, "request_id").equals(evidence.requestId())
        || !uuid(operation, "transition_id").equals(evidence.transitionId())
        || !uuid(operation, "candidate_account_index_fence")
            .equals(evidence.candidateAccountIndexFence())
        || !Arrays.equals(bytes(operation, "binding_ref"), evidence.bindingRef())
        || !positive(operation, "binding_generation").equals(evidence.bindingGeneration())
        || !uuid(operation, "reservation_id").equals(evidence.reservation().reservationId())
        || !uuid(operation, "reservation_fence").equals(evidence.reservation().reservationFence())
        || !positive(operation, "original_issuer_coverage_operation_fence")
            .equals(evidence.reservation().issuerCoverageOperationFence())
        || !uuid(operation, "original_issuer_coverage_operation_id")
            .equals(evidence.reservation().issuerCoverageOperationId())
        || !positive(operation, "original_coverage_fence")
            .equals(evidence.reservation().coverageFence())
        || !positive(operation, "original_inventory_snapshot_revision")
            .equals(evidence.reservation().inventorySnapshotRevision())
        || !positive(operation, "local_inventory_snapshot_revision")
            .equals(evidence.reservation().inventoryRevision())
        || !uuid(operation, "anchor_bootstrap_operation_id")
            .equals(evidence.anchor().bootstrapOperationId())
        || !uuid(operation, "anchor_issuer_coverage_operation_id")
            .equals(evidence.anchor().issuerCoverageOperationId())
        || !positive(operation, "anchor_operation_fence").equals(evidence.anchor().operationFence())
        || !positive(operation, "anchor_coverage_fence").equals(evidence.anchor().coverageFence())
        || !positive(operation, "anchor_issuer_inventory_snapshot_revision")
            .equals(evidence.anchor().inventorySnapshotRevision())
        || !positive(operation, "anchor_control_epoch").equals(evidence.anchor().controlEpoch())
        || !Arrays.equals(bytes(operation, "exact_anchor_bytes"), evidence.anchor().exactBytes())
        || !text(operation, "exact_anchor_digest").equals(evidence.anchor().exactDigest())
        || !Arrays.equals(
            bytes(operation, "anchor_layout_descriptor_bytes"),
            evidence.anchor().layoutDescriptorBytes())
        || !text(operation, "anchor_layout_descriptor_digest")
            .equals(evidence.anchor().layoutDescriptorDigest())
        || !Arrays.equals(
            bytes(operation, "anchor_source_checkpoint_bytes"),
            evidence.currentSource().checkpointBytes())
        || !text(operation, "anchor_source_checkpoint_digest")
            .equals(evidence.currentSource().checkpointDigest())
        || !Arrays.equals(
            operation.get("anchor_source_event_bytes", byte[].class),
            evidence.currentSource().eventBytes())
        || !Objects.equals(
            operation.get("anchor_source_event_digest", String.class),
            evidence.currentSource().eventDigest())
        || !Arrays.equals(
            bytes(operation, "anchor_incarnation_bytes"),
            evidence.currentSource().incarnationBytes())
        || !text(operation, "anchor_incarnation_digest")
            .equals(evidence.currentSource().incarnationDigest())
        || !uuid(operation, "operation_id").equals(evidence.memberOperation().operationId())
        || !positive(operation, "operation_fence")
            .equals(evidence.memberOperation().operationFence())
        || !positive(operation, "local_member_generation")
            .equals(evidence.memberOperation().generation())
        || !nonnegative(operation, "partition_id").equals(evidence.reservation().partitionId())
        || !Arrays.equals(bytes(operation, "member_value_bytes"), evidence.memberValueBytes())
        || !text(operation, "member_value_digest").equals(evidence.memberValueDigest())) {
      throw unavailable(
          "Stored issuer acknowledgement columns differ from their exact receipt bytes");
    }
    return acknowledgement;
  }

  private static ReadbackProof validateReadback(
      CanonicalGameplayIssuerBindingLocalOperation operation,
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work,
      CanonicalGameplayIssuerBindingLocalRedisOwner.Readback readback,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    var member = operation.memberValue();
    var anchor = operation.anchor();
    if (!sameCurrentIssuerStateForOperation(operation, currentState)) {
      throw unavailable("Current issuer source or incarnation differs from the installed anchor");
    }
    if (!work.targetEntry().bindingPresent()
        || !work.targetEntry().countsTowardCapacity()
        || !"PROVISIONAL".equals(work.targetEntry().transitionStatus())) {
      throw unavailable("Issuer local acknowledgement requires the current provisional binding");
    }
    Map<String, byte[]> fieldSet = decodeReadback(readback.exactReadbackBytes());
    if (!Arrays.equals(
        fieldSet.get(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MUTATION_REVISION_FIELD),
        work.currentRevision().toString().getBytes(StandardCharsets.US_ASCII))) {
      throw unavailable("Physical issuer revision differs from the current committed SQL head");
    }
    String bindingField = new String(member.owner().bindingRef().bytes(), StandardCharsets.UTF_8);
    String capacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            member.reservation().reservationId());
    byte[] bindingBytes = fieldSet.get(bindingField);
    byte[] capacityBytes = fieldSet.get(capacityField);
    if (!Arrays.equals(bindingBytes, operation.exactMemberValueBytes())
        || !Arrays.equals(capacityBytes, operation.exactMemberValueBytes())) {
      throw unavailable("Physical issuer readback lacks the exact member and reservation fields");
    }

    int reservations = 0;
    int bindings = 0;
    int countedCapacity = 0;
    for (Map.Entry<String, byte[]> entry : fieldSet.entrySet()) {
      if (entry
          .getKey()
          .startsWith(
              CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.RESERVATION_FIELD_PREFIX)) {
        var value = CanonicalGameplayIssuerMemberV2Codec.decode(entry.getValue());
        reservations++;
        if (value.reservation().lifecycle()
            != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED) countedCapacity++;
      } else if (!entry
              .getKey()
              .equals(CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD)
          && !entry
              .getKey()
              .equals(
                  CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MUTATION_REVISION_FIELD)) {
        CanonicalGameplayIssuerMemberV2Codec.decode(entry.getValue());
        bindings++;
      }
    }
    var proof = work.baselineProof();
    byte[] readbackBytes = readback.exactReadbackBytes();
    var physical =
        new PhysicalReadback(
            bindingBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(bindingBytes),
            capacityBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(capacityBytes),
            CanonicalGameplayIssuerMemberV2Codec.sha256(readbackBytes),
            Math.toIntExact(readback.scriptOutcome()),
            fieldSet.size(),
            reservations,
            bindings,
            countedCapacity);
    var capacity =
        new Capacity(
            member.reservation().partitionId(),
            anchor.partitionCapacity(),
            BigInteger.valueOf(proof.physicalCountedCapacityCount()),
            BigInteger.valueOf(proof.physicalReservationCount()),
            BigInteger.valueOf(proof.physicalBindingCount()),
            work.partitionCapacity(),
            work.aggregateCapacity(),
            work.aggregateBound());
    return new ReadbackProof(physical, capacity);
  }

  private static Evidence buildEvidence(
      CanonicalGameplayIssuerBindingLocalOperation operation,
      CanonicalGameplayIssuerBindingLocalRedisOwner.Readback readback,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState,
      ReadbackProof proof,
      BigInteger acknowledgementGeneration) {
    var member = operation.memberValue();
    var anchor = operation.anchor();
    var stateSource = currentState.accountCheckpoint();
    byte[] sourceCheckpointBytes = stateSource.exactCheckpointBytes();
    byte[] sourceEventBytes = stateSource.latestIssuerEventBytes();
    byte[] incarnationBytes =
        CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(currentState.incarnation());
    var originalReservation = member.reservation();
    var anchorTuple = member.anchor();
    return new Evidence(
        operation.operationId(),
        acknowledgementGeneration,
        operation.request().requestId(),
        operation.request().transitionId(),
        operation.candidateAccountIndexFence(),
        member.owner().issuerId(),
        member.owner().bindingRef().bytes(),
        member.bindingGeneration(),
        operation.exactMemberValueBytes(),
        operation.memberValueDigest(),
        new Reservation(
            originalReservation.reservationId(),
            originalReservation.transitionId(),
            originalReservation.reservationFence(),
            originalReservation.partitionId(),
            originalReservation.lifecycle().name(),
            originalReservation.issuerCoverageOperationId(),
            originalReservation.issuerCoverageOperationFence(),
            originalReservation.coverageFence(),
            originalReservation.inventorySnapshotRevision(),
            originalReservation.inventoryRevision()),
        new Anchor(
            anchor.bootstrapOperationId(),
            anchorTuple.issuerCoverageOperationId(),
            anchorTuple.operationFence(),
            anchorTuple.coverageFence(),
            anchorTuple.issuerInventorySnapshotRevision(),
            anchor.globalCoverageGeneration(),
            anchor.controlEpoch(),
            anchor.layoutVersion(),
            anchor.partitionCount(),
            anchor.partitionCapacity(),
            anchor.mappingIdentity(),
            anchor.layoutDescriptorBytes(),
            anchor.layoutDescriptorDigest(),
            anchor.exactAnchorBytes(),
            anchor.exactAnchorDigest()),
        new MemberOperation(
            member.memberOperation().operationId(),
            member.memberOperation().operationFence(),
            member.memberOperation().coverageGeneration()),
        new CurrentSource(
            stateSource.issuerAuthGeneration(),
            sourceCheckpointBytes,
            stateSource.checkpointDigest(),
            sourceEventBytes,
            stateSource.latestIssuerEventDigest(),
            incarnationBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(incarnationBytes)),
        proof.physical(),
        proof.capacity(),
        sha256Hex(readback.sqlCurrentnessDigest()));
  }

  private record ReadbackProof(PhysicalReadback physical, Capacity capacity) {}

  private static Map<String, byte[]> decodeReadback(byte[] readbackBytes) {
    Map<String, byte[]> fields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(readbackBytes);
    if (fields.isEmpty() || fields.size() > MAX_FIELD_COUNT) {
      throw unavailable("Physical issuer readback field count is outside its bound");
    }
    return fields;
  }

  private static void requireEligibleCandidate(
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work) {
    if (!work.targetEntry().bindingPresent()
        || !work.targetEntry().countsTowardCapacity()
        || !"PROVISIONAL".equals(work.targetEntry().transitionStatus())) {
      throw unavailable("Only the original nonterminal binding may receive a local issuer ACK");
    }
  }

  private static void requireSameOperation(
      Evidence evidence, CanonicalGameplayIssuerBindingLocalOperation operation) {
    if (!evidence.operationId().equals(operation.operationId())
        || !evidence.requestId().equals(operation.request().requestId())
        || !evidence.transitionId().equals(operation.request().transitionId())
        || !evidence.reservation().reservationId().equals(operation.reservation().reservationId())
        || !evidence.anchor().exactDigest().equals(operation.anchor().exactAnchorDigest())
        || !Arrays.equals(evidence.memberValueBytes(), operation.exactMemberValueBytes())) {
      throw unavailable(
          "Retained issuer acknowledgement differs from the exact original operation");
    }
  }

  private static void requireSameCurrentSources(
      Evidence evidence,
      CanonicalGameplayIssuerBindingLocalOperation operation,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    if (!sameCurrentIssuerStateForOperation(operation, currentState)
        || !evidence.anchor().exactDigest().equals(operation.anchor().exactAnchorDigest())
        || !evidence
            .currentSource()
            .checkpointDigest()
            .equals(currentState.accountCheckpoint().checkpointDigest())
        || !Arrays.equals(
            evidence.currentSource().checkpointBytes(),
            currentState.accountCheckpoint().exactCheckpointBytes())
        || !Objects.equals(
            evidence.currentSource().eventDigest(),
            currentState.accountCheckpoint().latestIssuerEventDigest())
        || !Arrays.equals(
            evidence.currentSource().eventBytes(),
            currentState.accountCheckpoint().latestIssuerEventBytes())
        || !Arrays.equals(
            evidence.currentSource().incarnationBytes(),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(currentState.incarnation()))) {
      throw unavailable(
          "Retained issuer acknowledgement is stale for current source or incarnation");
    }
  }

  private static void requireReadbackTargetStillMatches(
      Evidence evidence, CanonicalGameplayIssuerBindingLocalRedisOwner.Readback readback) {
    if (!readback.operationId().equals(evidence.operationId())) {
      throw unavailable("Physical issuer readback belongs to a different local operation");
    }
    Map<String, byte[]> fields = decodeReadback(readback.exactReadbackBytes());
    var prior = evidence.physicalReadback();
    var member = CanonicalGameplayIssuerMemberV2Codec.decode(evidence.memberValueBytes());
    byte[] binding =
        fields.get(new String(member.owner().bindingRef().bytes(), StandardCharsets.UTF_8));
    byte[] capacity =
        fields.get(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                evidence.reservation().reservationId()));
    if (!Arrays.equals(binding, prior.bindingValueBytes())
        || !Arrays.equals(capacity, prior.capacityValueBytes())) {
      throw unavailable("Physical issuer retry differs from the retained target readback");
    }
  }

  private void requireCurrentIssuerSource(
      CanonicalGameplayIssuerBindingLocalOperation operation,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState state) {
    if (!sameCurrentIssuerStateForOperation(operation, state)) {
      throw unavailable(
          "Current Account source or issuer incarnation differs from the installed anchor");
    }
  }

  private static boolean sameCurrentIssuerStateForOperation(
      CanonicalGameplayIssuerBindingLocalOperation operation,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState state) {
    var anchor = operation.anchor();
    var source = state.accountCheckpoint();
    return source.issuerId().equals(anchor.issuerId())
        && source.issuerAuthGeneration().equals(anchor.issuerAuthGeneration())
        && source.checkpointDigest().equals(anchor.sourceCheckpointDigest())
        && Arrays.equals(source.exactCheckpointBytes(), anchor.sourceCheckpointBytes())
        && Objects.equals(source.latestIssuerEventDigest(), anchor.sourceEventDigest())
        && Arrays.equals(source.latestIssuerEventBytes(), anchor.sourceEventBytes())
        && Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(state.incarnation()),
            anchor.incarnationBytes());
  }

  private boolean sameCurrentIssuerState(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState left,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState right) {
    return left.accountCheckpoint().issuerId().equals(right.accountCheckpoint().issuerId())
        && left.accountCheckpoint()
            .issuerAuthGeneration()
            .equals(right.accountCheckpoint().issuerAuthGeneration())
        && Arrays.equals(
            left.accountCheckpoint().exactCheckpointBytes(),
            right.accountCheckpoint().exactCheckpointBytes())
        && left.accountCheckpoint()
            .checkpointDigest()
            .equals(right.accountCheckpoint().checkpointDigest())
        && Arrays.equals(
            left.accountCheckpoint().latestIssuerEventBytes(),
            right.accountCheckpoint().latestIssuerEventBytes())
        && Objects.equals(
            left.accountCheckpoint().latestIssuerEventDigest(),
            right.accountCheckpoint().latestIssuerEventDigest())
        && Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(left.incarnation()),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(right.incarnation()));
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      readCurrentIssuerState(String boundary) {
    try {
      requireNoAmbientTransaction();
      var state = currentIssuerStateSource.readFreshCurrentState();
      requireNoAmbientTransaction();
      if (state == null) throw unavailable("Current issuer source returned no state " + boundary);
      return state;
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().contains("Issuer binding-local acknowledgement is unavailable")) {
        throw state;
      }
      throw unavailable("Could not read current issuer source " + boundary, failure);
    }
  }

  private static BigInteger positive(Record row, String field) {
    BigInteger value = number(row, field);
    if (value.signum() <= 0) throw unavailable(field + " must be positive");
    return value;
  }

  private static BigInteger nonnegative(Record row, String field) {
    BigInteger value = number(row, field);
    if (value.signum() < 0) throw unavailable(field + " must not be negative");
    return value;
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

  private static UUID uuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || value.equals(new UUID(0L, 0L))) throw unavailable(field + " is missing");
    return value;
  }

  private static String text(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null || value.isEmpty()) throw unavailable(field + " is missing");
    return value;
  }

  private static byte[] bytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0) throw unavailable(field + " is missing");
    return value.clone();
  }

  private static String sha256Hex(byte[] digestBytes) {
    if (digestBytes.length != 32) throw unavailable("SQL currentness digest is not SHA-256");
    return "sha256:" + java.util.HexFormat.of().formatHex(digestBytes);
  }

  private void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable("Issuer acknowledgement owner cannot join an ambient SQL transaction");
    }
    try {
      dsl.connectionResult(
          connection -> {
            if (!connection.getAutoCommit()) {
              throw unavailable("Issuer acknowledgement owner cannot join a jOOQ transaction");
            }
            return Boolean.TRUE;
          });
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().contains("Issuer binding-local acknowledgement is unavailable")) {
        throw state;
      }
      throw unavailable("Issuer acknowledgement SQL connection state is unavailable", failure);
    }
  }

  private static IllegalStateException unavailable(String message) {
    return new IllegalStateException(
        "Issuer binding-local acknowledgement is unavailable: " + message);
  }

  private static IllegalStateException unavailable(String message, Throwable cause) {
    return new IllegalStateException(
        "Issuer binding-local acknowledgement is unavailable: " + message, cause);
  }
}
