package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Local SQL owner for issuing one immutable member operation from an exact prepared initial
 * bootstrap work item. It does not execute physical Redis work or record proof completion.
 */
final class CanonicalGameplayIssuerBootstrapMemberOperationRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String COUNTER = "game_session_canonical_issuer_bootstrap_member_op_counter";
  private static final String OPERATION =
      "game_session_canonical_issuer_bootstrap_member_operation";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;

  CanonicalGameplayIssuerBootstrapMemberOperationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Begins or resumes one exact planned member work item. The current Account checkpoint and stable
   * storage-incarnation inputs are explicitly controlled local collaborators: this method checks
   * them against the persisted bootstrap tuple but does not authenticate their authority.
   */
  CanonicalGameplayIssuerBootstrapMemberOperation beginOrResumePendingPhysicalProof(
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      long workOrdinal,
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
          currentAccountCheckpoint,
      TrustedStableIncarnation stableIncarnation) {
    requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    if (workOrdinal < 0) throw new IllegalArgumentException("workOrdinal must not be negative");
    Objects.requireNonNull(currentAccountCheckpoint, "currentAccountCheckpoint");
    Objects.requireNonNull(stableIncarnation, "stableIncarnation");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration ->
            beginOrResumeInTransaction(
                org.jooq.impl.DSL.using(configuration),
                bootstrapOperationId,
                bootstrapRequestId,
                workOrdinal,
                currentAccountCheckpoint,
                stableIncarnation));
  }

  /**
   * Qualifies and issues the complete member-operation set for one exact partition in one SQL
   * transaction. The partition id selects from the persisted plan; no member list is accepted.
   */
  CanonicalGameplayIssuerBootstrapPartitionWork preparePartitionWork(
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      BigInteger partitionId,
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
          currentAccountCheckpoint,
      TrustedStableIncarnation stableIncarnation) {
    requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    Objects.requireNonNull(partitionId, "partitionId");
    Objects.requireNonNull(currentAccountCheckpoint, "currentAccountCheckpoint");
    Objects.requireNonNull(stableIncarnation, "stableIncarnation");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = org.jooq.impl.DSL.using(configuration);
          CanonicalGameplayIssuerPartitionBuildRepository buildRepository =
              new CanonicalGameplayIssuerPartitionBuildRepository(transaction);
          CanonicalGameplayIssuerPartitionBuildPlan plan =
              buildRepository.readPreparedPlanInTransaction(
                  transaction, bootstrapOperationId, bootstrapRequestId);
          CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
          if (partitionId.signum() < 0
              || partitionId.compareTo(intent.layoutDescriptor().partitionCount()) >= 0) {
            throw conflict("Requested partition is outside the exact prepared bootstrap layout");
          }
          UUID initialPhysicalRevision =
              CanonicalGameplayIssuerPartitionMutationRepository.ensureInitialRevisionInTransaction(
                  transaction, bootstrapOperationId, partitionId);
          requireCurrentAccountCheckpoint(currentAccountCheckpoint, intent.sourceCheckpoint());
          CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation = stableIncarnation.value();
          List<CanonicalGameplayIssuerBootstrapMemberOperation> operations = new ArrayList<>();
          for (CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work : plan.memberWorks()) {
            if (!work.plannedPartitionId().equals(partitionId)) continue;
            CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared =
                buildRepository.readPreparedMemberWorkFromVerifiedPlan(plan, work.workOrdinal());
            operations.add(
                beginOrResumePreparedInTransaction(
                    transaction, prepared, currentAccountCheckpoint, incarnation));
          }
          return new CanonicalGameplayIssuerBootstrapPartitionWork(
              plan, partitionId, initialPhysicalRevision, operations, incarnation);
        });
  }

  private static CanonicalGameplayIssuerBootstrapMemberOperation beginOrResumeInTransaction(
      DSLContext transaction,
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      long workOrdinal,
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
          currentAccountCheckpoint,
      TrustedStableIncarnation stableIncarnation) {
    CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared =
        new CanonicalGameplayIssuerPartitionBuildRepository(transaction)
            .readPreparedMemberWorkInTransaction(
                transaction, bootstrapOperationId, bootstrapRequestId, workOrdinal);
    return beginOrResumePreparedInTransaction(
        transaction, prepared, currentAccountCheckpoint, stableIncarnation.value());
  }

  private static CanonicalGameplayIssuerBootstrapMemberOperation beginOrResumePreparedInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared,
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
          currentAccountCheckpoint,
      CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation) {
    CanonicalGameplayIssuerPartitionBuildPlan plan = prepared.plan();
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work = prepared.work();
    requireCurrentAccountCheckpoint(currentAccountCheckpoint, intent.sourceCheckpoint());

    Record existing =
        transaction.fetchOne(
            "SELECT * FROM "
                + OPERATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND work_ordinal = ? FOR UPDATE",
            ISSUER,
            intent.operationId(),
            work.workOrdinal());
    if (existing != null) {
      return loadAndRequireExactOperation(existing, prepared, incarnation);
    }

    IssuedCounters counters = allocateLocalCounters(transaction, prepared);
    UUID operationId = nextOperationId(transaction, prepared);
    CanonicalGameplayIssuerMemberV2Value memberValue =
        createMemberValue(
            prepared, operationId, counters.operationFence(), counters.generation(), incarnation);
    byte[] memberValueBytes = CanonicalGameplayIssuerMemberV2Codec.encode(memberValue);
    String memberValueDigest = CanonicalGameplayIssuerMemberV2Codec.sha256(memberValueBytes);
    insertOperation(
        transaction, prepared, operationId, counters, memberValueBytes, memberValueDigest);
    Record saved =
        transaction.fetchOne(
            "SELECT * FROM "
                + OPERATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND work_ordinal = ? FOR UPDATE",
            ISSUER,
            intent.operationId(),
            work.workOrdinal());
    if (saved == null) throw conflict("Persisted bootstrap member operation is unreadable");
    CanonicalGameplayIssuerBootstrapMemberOperation result =
        loadAndRequireExactOperation(saved, prepared, incarnation);
    if (!result.operationId().equals(operationId)
        || !result.operationFence().equals(counters.operationFence())
        || !result.localMemberGeneration().equals(counters.generation())
        || !Arrays.equals(result.exactMemberValueBytes(), memberValueBytes)) {
      throw conflict("Persisted member operation differs from the SQL owner-issued value");
    }
    return result;
  }

  private static void requireCurrentAccountCheckpoint(
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint current,
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint persisted) {
    if (!ISSUER.equals(current.issuerId())
        || !ISSUER.equals(persisted.issuerId())
        || !current.issuerAuthGeneration().equals(persisted.issuerAuthGeneration())
        || !Arrays.equals(current.checkpointBytes(), persisted.exactCheckpointBytes())
        || !Arrays.equals(current.latestEventBytes(), persisted.latestIssuerEventBytes())) {
      throw conflict(
          "Controlled current Account checkpoint differs from the exact pending bootstrap source");
    }
  }

  private static CanonicalGameplayIssuerBootstrapMemberOperation loadAndRequireExactOperation(
      Record row,
      CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared,
      CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation) {
    CanonicalGameplayIssuerPartitionBuildPlan plan = prepared.plan();
    CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work = prepared.work();
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    CanonicalIssuerPartitionReservation original = prepared.originalReservation();
    byte[] bytes = requireBytes(row, "member_value_bytes");
    String digest = requireText(row, "member_value_digest");
    if (!digest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(bytes))) {
      throw conflict("Persisted pending member value digest differs from its exact bytes");
    }
    CanonicalGameplayIssuerMemberV2Value value;
    try {
      value = CanonicalGameplayIssuerMemberV2Codec.decode(bytes);
    } catch (RuntimeException malformed) {
      throw conflict("Persisted pending member value is not canonical schema v2");
    }
    UUID operationId = requireUuid(row, "member_operation_id");
    BigInteger operationFence = readPositive(row, "operation_fence");
    BigInteger generation = readPositive(row, "local_member_generation");
    UUID bootstrapOperationId = requireUuid(row, "bootstrap_operation_id");
    UUID bootstrapRequestId = requireUuid(row, "bootstrap_request_id");
    UUID buildOperationId = requireUuid(row, "build_operation_id");
    String planDigest = requireText(row, "build_plan_digest");
    long workOrdinal = requireNonNegativeLong(row, "work_ordinal");
    UUID preparationOperationId = requireUuid(row, "preparation_operation_id");
    UUID preparationFenceId = requireUuid(row, "preparation_fence_id");
    long sourceOrdinal = requireNonNegativeLong(row, "reservation_source_row_ordinal");
    String sourceDigest = requireText(row, "reservation_source_row_digest");
    Long bindingSourceOrdinal = row.get("binding_source_row_ordinal", Long.class);
    String bindingSourceDigest = row.get("binding_source_row_digest", String.class);
    String bindingLifecycleText = row.get("binding_lifecycle", String.class);
    CanonicalGameplayBindingInventoryEntry.Lifecycle bindingLifecycle = null;
    if (bindingLifecycleText != null) {
      try {
        bindingLifecycle =
            CanonicalGameplayBindingInventoryEntry.Lifecycle.valueOf(bindingLifecycleText);
      } catch (RuntimeException malformed) {
        throw conflict("Persisted pending operation has an unsupported binding lifecycle");
      }
    }
    boolean expectedBindingPresent =
        Objects.requireNonNull(row.get("expected_binding_present", Boolean.class));
    boolean countsTowardCapacity =
        Objects.requireNonNull(row.get("counts_toward_capacity", Boolean.class));
    String status = requireText(row, "member_status");
    BindingAction action = bindingAction(prepared);

    requireSameRowLinkage(
        row,
        prepared,
        plan,
        work,
        original,
        intent,
        operationId,
        operationFence,
        generation,
        bootstrapOperationId,
        bootstrapRequestId,
        buildOperationId,
        planDigest,
        workOrdinal,
        preparationOperationId,
        preparationFenceId,
        sourceOrdinal,
        sourceDigest,
        bindingSourceOrdinal,
        bindingSourceDigest,
        bindingLifecycleText,
        action,
        expectedBindingPresent,
        countsTowardCapacity,
        status,
        value);
    CanonicalGameplayIssuerMemberV2Value expectedValue =
        createMemberValue(prepared, operationId, operationFence, generation, incarnation);
    if (!expectedValue.equals(value)
        || !Arrays.equals(bytes, CanonicalGameplayIssuerMemberV2Codec.encode(expectedValue))) {
      throw conflict("Pending member operation retry changed an exact schema-v2 field or tuple");
    }
    try {
      return new CanonicalGameplayIssuerBootstrapMemberOperation(
          operationId,
          operationFence,
          generation,
          bootstrapOperationId,
          bootstrapRequestId,
          buildOperationId,
          planDigest,
          workOrdinal,
          preparationOperationId,
          preparationFenceId,
          sourceOrdinal,
          sourceDigest,
          value,
          bindingLifecycle,
          expectedBindingPresent,
          countsTowardCapacity,
          bytes,
          digest,
          CanonicalGameplayIssuerBootstrapMemberOperation.Status.valueOf(status));
    } catch (RuntimeException malformed) {
      throw conflict("Persisted bootstrap member operation metadata is malformed");
    }
  }

  private static void requireSameRowLinkage(
      Record row,
      CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work,
      CanonicalIssuerPartitionReservation original,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      UUID operationId,
      BigInteger operationFence,
      BigInteger generation,
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      UUID buildOperationId,
      String planDigest,
      long workOrdinal,
      UUID preparationOperationId,
      UUID preparationFenceId,
      long sourceOrdinal,
      String sourceDigest,
      Long bindingSourceOrdinal,
      String bindingSourceDigest,
      String bindingLifecycle,
      BindingAction action,
      boolean expectedBindingPresent,
      boolean countsTowardCapacity,
      String status,
      CanonicalGameplayIssuerMemberV2Value value) {
    if (!ISSUER.equals(requireText(row, "issuer_id"))
        || !bootstrapOperationId.equals(intent.operationId())
        || !bootstrapRequestId.equals(intent.requestId())
        || !buildOperationId.equals(plan.buildOperationId())
        || !planDigest.equals(plan.planDigest())
        || workOrdinal != work.workOrdinal()
        || !preparationOperationId.equals(work.memberOperationId())
        || !preparationFenceId.equals(work.memberPreparationFenceId())
        || sourceOrdinal != work.reservationSourceRowOrdinal()
        || !sourceDigest.equals(work.reservationSourceRowDigest())
        || !Objects.equals(bindingSourceOrdinal, work.bindingSourceRowOrdinal())
        || !Objects.equals(bindingSourceDigest, work.bindingSourceRowDigest())
        || !Objects.equals(
            bindingLifecycle,
            prepared.bindingLifecycle() == null ? null : prepared.bindingLifecycle().name())
        || expectedBindingPresent != action.expectedBindingPresent()
        || countsTowardCapacity != work.countsTowardCapacity()
        || !original.lifecycle().name().equals(requireText(row, "reservation_lifecycle"))
        || !original.reservationId().equals(row.get("reservation_id", UUID.class))
        || !original
            .issuerCoverageOperationId()
            .equals(row.get("original_issuer_coverage_operation_id", UUID.class))
        || !original
            .issuerCoverageOperationFence()
            .equals(readPositive(row, "original_issuer_coverage_operation_fence"))
        || !original.coverageFence().equals(readPositive(row, "original_coverage_fence"))
        || !original
            .inventorySnapshotRevision()
            .equals(readPositive(row, "original_inventory_snapshot_revision"))
        || !intent.operationId().equals(row.get("bootstrap_anchor_operation_id", UUID.class))
        || !intent.operationFence().equals(readPositive(row, "bootstrap_anchor_operation_fence"))
        || !intent.coverageFence().equals(readPositive(row, "bootstrap_anchor_coverage_fence"))
        || !intent
            .inventorySnapshotRevision()
            .equals(readPositive(row, "bootstrap_anchor_inventory_snapshot_revision"))
        || !"PENDING_PHYSICAL_PROOF".equals(status)
        || row.get("member_schema_version", Short.class) == null
        || row.get("member_schema_version", Short.class)
            != CanonicalGameplayIssuerMemberV2Value.SCHEMA_VERSION
        || !operationId.equals(value.memberOperation().operationId())
        || !operationFence.equals(value.memberOperation().operationFence())
        || !generation.equals(value.memberOperation().coverageGeneration())) {
      throw conflict("Persisted member operation linkage differs from its exact prepared work");
    }
  }

  private static CanonicalGameplayIssuerMemberV2Value createMemberValue(
      CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared,
      UUID operationId,
      BigInteger operationFence,
      BigInteger generation,
      CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation) {
    CanonicalGameplayIssuerPartitionBuildPlan plan = prepared.plan();
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    return new CanonicalGameplayIssuerMemberV2Value(
        prepared.originalReservation().owner(),
        prepared.originalReservation().bindingGeneration(),
        prepared.originalReservation(),
        new CanonicalGameplayIssuerMemberV2Value.Anchor(
            intent.operationId(),
            intent.operationFence(),
            intent.coverageFence(),
            intent.inventorySnapshotRevision()),
        new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
            operationId, operationFence, generation),
        intent.controlEpoch(),
        intent.layoutDescriptor(),
        intent.sourceCheckpoint(),
        incarnation);
  }

  private static BindingAction bindingAction(
      CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared) {
    CanonicalIssuerPartitionReservation.Lifecycle reservationLifecycle =
        prepared.originalReservation().lifecycle();
    CanonicalGameplayBindingInventoryEntry.Lifecycle bindingLifecycle = prepared.bindingLifecycle();
    if (bindingLifecycle == null) {
      if (reservationLifecycle == CanonicalIssuerPartitionReservation.Lifecycle.RESERVED
          || reservationLifecycle == CanonicalIssuerPartitionReservation.Lifecycle.RELEASED) {
        return new BindingAction(false);
      }
      throw conflict(
          "A BOUND or RELEASE_PENDING reservation without a linked binding cannot qualify physical work");
    }
    return switch (bindingLifecycle) {
      case CANDIDATE_PREPARED -> {
        if (reservationLifecycle != CanonicalIssuerPartitionReservation.Lifecycle.RESERVED) {
          throw conflict("CANDIDATE_PREPARED binding must retain its exact RESERVED reservation");
        }
        yield new BindingAction(false);
      }
      case PROVISIONAL -> {
        if (reservationLifecycle != CanonicalIssuerPartitionReservation.Lifecycle.RESERVED) {
          throw conflict("PROVISIONAL binding must retain its exact RESERVED reservation");
        }
        yield new BindingAction(true);
      }
      case ACTIVE -> {
        if (reservationLifecycle != CanonicalIssuerPartitionReservation.Lifecycle.BOUND
            && reservationLifecycle
                != CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING) {
          throw conflict(
              "ACTIVE binding must retain its exact BOUND or RELEASE_PENDING reservation");
        }
        yield new BindingAction(true);
      }
      case TERMINAL -> {
        if (reservationLifecycle != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED) {
          throw conflict("TERMINAL binding must retain its exact RELEASED reservation");
        }
        yield new BindingAction(false);
      }
      case TERMINAL_UNRESOLVED ->
          throw conflict("TERMINAL_UNRESOLVED source cannot qualify initial physical member work");
    };
  }

  private static IssuedCounters allocateLocalCounters(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared) {
    Record counter =
        transaction.fetchOne(
            "SELECT * FROM " + COUNTER + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (counter == null) {
      Record priorOperation =
          transaction.fetchOne(
              "SELECT 1 FROM " + OPERATION + " WHERE issuer_id = ? LIMIT 1", ISSUER);
      if (priorOperation != null) {
        throw conflict("Issuer-local member-operation counter is missing after prior issuance");
      }
      transaction.execute(
          "INSERT INTO "
              + COUNTER
              + " (issuer_id, last_issued_operation_fence, last_issued_member_generation, counter_version)"
              + " VALUES (?, 0, 0, 0) ON CONFLICT (issuer_id) DO NOTHING",
          ISSUER);
      counter =
          transaction.fetchOne(
              "SELECT * FROM " + COUNTER + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
      if (counter == null)
        throw conflict("Issuer-local member-operation counter could not initialize");
    }
    BigInteger previousFence = readNonNegative(counter, "last_issued_operation_fence");
    BigInteger previousGeneration = readNonNegative(counter, "last_issued_member_generation");
    long previousVersion = Objects.requireNonNull(counter.get("counter_version", Long.class));
    if (previousVersion < 0
        || (previousVersion == 0
            && (previousFence.signum() != 0 || previousGeneration.signum() != 0))
        || (previousVersion > 0
            && (!previousFence.add(BigInteger.ONE).equals(previousGeneration)
                || previousFence.signum() <= 0))) {
      throw conflict("Issuer-local member-operation counter state is malformed");
    }

    CanonicalGameplayIssuerPartitionBuildPlan plan = prepared.plan();
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    CanonicalIssuerPartitionReservation original = prepared.originalReservation();
    BigInteger qualifiedSeed =
        max(
            intent.controlEpoch(),
            intent.operationFence(),
            intent.coverageFence(),
            intent.inventorySnapshotRevision(),
            original.issuerCoverageOperationFence(),
            original.coverageFence(),
            original.inventorySnapshotRevision(),
            original.inventoryRevision(),
            previousFence,
            previousGeneration);
    BigInteger newFence = qualifiedSeed.add(BigInteger.ONE);
    BigInteger newGeneration = newFence.add(BigInteger.ONE);
    requireCounterLimit(newFence, "operationFence");
    requireCounterLimit(newGeneration, "localMemberGeneration");
    long nextVersion;
    try {
      nextVersion = Math.addExact(previousVersion, 1L);
    } catch (ArithmeticException overflow) {
      throw conflict("Issuer-local member-operation counter version is exhausted");
    }
    Record updated =
        transaction.fetchOne(
            "UPDATE "
                + COUNTER
                + " SET last_issued_operation_fence = ?, last_issued_member_generation = ?, counter_version = ?"
                + " WHERE issuer_id = ? AND last_issued_operation_fence = ?"
                + " AND last_issued_member_generation = ? AND counter_version = ?"
                + " RETURNING issuer_id",
            decimal(newFence),
            decimal(newGeneration),
            nextVersion,
            ISSUER,
            decimal(previousFence),
            decimal(previousGeneration),
            previousVersion);
    if (updated == null)
      throw conflict("Issuer-local member-operation counter compare-and-set lost");
    return new IssuedCounters(newFence, newGeneration);
  }

  private static UUID nextOperationId(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared) {
    Set<UUID> reserved = new HashSet<>();
    CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work = prepared.work();
    CanonicalIssuerPartitionReservation original = prepared.originalReservation();
    CanonicalGameplayBindingIdentity owner = original.owner();
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = prepared.plan().bootstrapIntent();
    reserved.add(NIL_UUID);
    reserved.add(intent.operationId());
    reserved.add(intent.requestId());
    reserved.add(prepared.plan().buildOperationId());
    reserved.add(work.memberOperationId());
    reserved.add(work.memberPreparationFenceId());
    reserved.add(original.reservationId());
    reserved.add(original.transitionId());
    reserved.add(original.reservationFence());
    reserved.add(original.issuerCoverageOperationId());
    reserved.add(owner.accountId());
    reserved.add(owner.tenantId());
    reserved.add(owner.playableStateNamespaceId());
    reserved.add(owner.characterId());
    reserved.add(owner.gameInstanceId());
    reserved.add(owner.regionId());
    reserved.add(UUID.fromString(owner.sessionId()));
    while (true) {
      UUID candidate = UUID.randomUUID();
      if (reserved.contains(candidate)) continue;
      Record collision =
          transaction.fetchOne(
              "SELECT 1 FROM " + OPERATION + " WHERE member_operation_id = ?", candidate);
      if (collision == null) return candidate;
    }
  }

  private static void insertOperation(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionBuildRepository.PreparedMemberWork prepared,
      UUID operationId,
      IssuedCounters counters,
      byte[] bytes,
      String digest) {
    CanonicalGameplayIssuerPartitionBuildPlan plan = prepared.plan();
    CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work = prepared.work();
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    CanonicalIssuerPartitionReservation original = prepared.originalReservation();
    BindingAction action = bindingAction(prepared);
    transaction.execute(
        "INSERT INTO "
            + OPERATION
            + " (member_operation_id, issuer_id, bootstrap_operation_id, bootstrap_request_id,"
            + " build_operation_id, build_plan_digest, work_ordinal, preparation_operation_id,"
            + " preparation_fence_id, reservation_source_row_ordinal, reservation_source_row_digest,"
            + " binding_source_row_ordinal, binding_source_row_digest, binding_lifecycle,"
            + " reservation_id, reservation_lifecycle, expected_binding_present, counts_toward_capacity,"
            + " original_issuer_coverage_operation_id,"
            + " original_issuer_coverage_operation_fence, original_coverage_fence,"
            + " original_inventory_snapshot_revision, bootstrap_anchor_operation_id,"
            + " bootstrap_anchor_operation_fence, bootstrap_anchor_coverage_fence,"
            + " bootstrap_anchor_inventory_snapshot_revision, operation_fence,"
            + " local_member_generation, member_status, member_schema_version, member_value_bytes,"
            + " member_value_digest)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'PENDING_PHYSICAL_PROOF', 2, ?, ?)",
        operationId,
        ISSUER,
        intent.operationId(),
        intent.requestId(),
        plan.buildOperationId(),
        plan.planDigest(),
        work.workOrdinal(),
        work.memberOperationId(),
        work.memberPreparationFenceId(),
        work.reservationSourceRowOrdinal(),
        work.reservationSourceRowDigest(),
        work.bindingSourceRowOrdinal(),
        work.bindingSourceRowDigest(),
        prepared.bindingLifecycle() == null ? null : prepared.bindingLifecycle().name(),
        original.reservationId(),
        original.lifecycle().name(),
        action.expectedBindingPresent(),
        work.countsTowardCapacity(),
        original.issuerCoverageOperationId(),
        decimal(original.issuerCoverageOperationFence()),
        decimal(original.coverageFence()),
        decimal(original.inventorySnapshotRevision()),
        intent.operationId(),
        decimal(intent.operationFence()),
        decimal(intent.coverageFence()),
        decimal(intent.inventorySnapshotRevision()),
        decimal(counters.operationFence()),
        decimal(counters.generation()),
        bytes,
        digest);
  }

  private static BigInteger max(BigInteger... values) {
    BigInteger greatest = BigInteger.ZERO;
    for (BigInteger value : values) {
      if (value.compareTo(greatest) > 0) greatest = value;
    }
    return greatest;
  }

  private static BigInteger readPositive(Record row, String field) {
    BigInteger value = readNonNegative(row, field);
    if (value.signum() <= 0) throw conflict(field + " must be positive");
    return value;
  }

  private static BigInteger readNonNegative(Record row, String field) {
    BigDecimal value = row.get(field, BigDecimal.class);
    if (value == null) throw conflict(field + " is missing");
    try {
      BigInteger integer = value.toBigIntegerExact();
      if (integer.signum() < 0) throw conflict(field + " must not be negative");
      requireCounterLimit(integer, field);
      return integer;
    } catch (ArithmeticException malformed) {
      throw conflict(field + " must be an exact integer");
    }
  }

  private static long requireNonNegativeLong(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value < 0) throw conflict(field + " must be a non-negative bigint");
    return value;
  }

  private static String requireText(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw conflict(field + " is missing");
    return value;
  }

  private static byte[] requireBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0) throw conflict(field + " is missing");
    return value;
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
  }

  private static void requireCounterLimit(BigInteger value, String name) {
    if (value.signum() < 0
        || value.toString().length() > CanonicalGameplayIssuerMemberV2Codec.MAX_COUNTER_DIGITS) {
      throw conflict(name + " is outside the canonical unsigned local counter range");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(NIL_UUID)) throw new IllegalArgumentException(name + " must not be nil");
  }

  private static UUID requireUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || NIL_UUID.equals(value)) throw conflict(field + " must be a non-nil UUID");
    return value;
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw conflict("Issuer bootstrap member operation owner cannot join an ambient transaction");
    }
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  private record BindingAction(boolean expectedBindingPresent) {}

  private record IssuedCounters(BigInteger operationFence, BigInteger generation) {}

  /**
   * Shape-only controlled local input for a stable coordination-storage incarnation. Its name
   * describes the required consumer input, not an authenticated attestation or production source.
   */
  static final class TrustedStableIncarnation {
    private final String attestorBinding;
    private final String acceptedAttestationDigest;
    private final CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
        targetDeploymentEvidence;
    private final String coordOpsRoAclDigest;
    private final String coordRedisConfigObservedDigest;

    TrustedStableIncarnation(
        String attestorBinding,
        String acceptedAttestationDigest,
        CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
            targetDeploymentEvidence,
        String coordOpsRoAclDigest,
        String coordRedisConfigObservedDigest) {
      this.attestorBinding = Objects.requireNonNull(attestorBinding, "attestorBinding");
      this.acceptedAttestationDigest =
          Objects.requireNonNull(acceptedAttestationDigest, "acceptedAttestationDigest");
      this.targetDeploymentEvidence =
          Objects.requireNonNull(targetDeploymentEvidence, "targetDeploymentEvidence");
      this.coordOpsRoAclDigest = Objects.requireNonNull(coordOpsRoAclDigest, "coordOpsRoAclDigest");
      this.coordRedisConfigObservedDigest =
          Objects.requireNonNull(coordRedisConfigObservedDigest, "coordRedisConfigObservedDigest");
      // Reuse the v2 typed carrier's closed local shape validation without claiming custody.
      value();
    }

    private CanonicalGameplayIssuerMemberV2Value.Incarnation value() {
      return new CanonicalGameplayIssuerMemberV2Value.Incarnation(
          attestorBinding,
          acceptedAttestationDigest,
          targetDeploymentEvidence,
          coordOpsRoAclDigest,
          coordRedisConfigObservedDigest);
    }
  }
}
