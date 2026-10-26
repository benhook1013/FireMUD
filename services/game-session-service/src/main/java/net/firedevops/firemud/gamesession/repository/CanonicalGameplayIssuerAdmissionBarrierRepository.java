package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL projection owner for immutable tenant OPEN initializers and issuer-wide CLOSED cutoffs. */
final class CanonicalGameplayIssuerAdmissionBarrierRepository
    implements CanonicalGameplayIssuerAdmissionBarrierRedisOwner.ProjectionRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String CONTROL = "game_session_canonical_issuer_admission_control";
  private static final String INITIALIZER = "game_session_canonical_issuer_admission_initializer";
  private static final String INITIALIZER_RECEIPT =
      "game_session_canonical_issuer_admission_initializer_receipt";
  private static final String CUTOFF_TARGET =
      "game_session_canonical_issuer_admission_cutoff_target";
  private static final String LOCAL_OPERATION =
      "game_session_canonical_issuer_binding_local_operation";
  private static final String TENANT_TAG = "game_session_canonical_tenant_gameplay_tag";

  private final DSLContext dsl;
  private final CanonicalGameplayTenantGameplayTagRepository tenantTags;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentStateSource;

  CanonicalGameplayIssuerAdmissionBarrierRepository(
      DSLContext dsl,
      CanonicalGameplayTenantGameplayTagRepository tenantTags,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentStateSource) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.tenantTags = Objects.requireNonNull(tenantTags, "tenantTags");
    this.currentStateSource = Objects.requireNonNull(currentStateSource, "currentStateSource");
  }

  /** Persists the original complete OPEN payload before the Redis owner is allowed to write it. */
  @Override
  public CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer
      readPreparedInitializer(UUID operationId) {
    requireNonNil(operationId, "operationId");
    requireNoAmbientTransaction();
    Record originalOperationInitializer =
        dsl.fetchOne(
            "SELECT operation_id FROM " + INITIALIZER + " WHERE operation_id = ? AND issuer_id = ?",
            operationId,
            ISSUER);
    CanonicalGameplayTenantGameplayTagRepository.Assignment requestAssignment =
        originalOperationInitializer == null
            ? tenantTags.assignmentForTransition(transitionForLocalOperation(operationId))
            : null;
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState before =
        readCurrentState();
    PreparedInitializerSource source =
        dsl.transactionResult(
            configuration -> {
              DSLContext transaction = DSL.using(configuration);
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              if (requestAssignment != null) {
                requireCurrentTagMapping(
                    transaction,
                    requestAssignment.tenantId(),
                    requestAssignment.tenantGameplayTag());
              }
              CanonicalGameplayIssuerInstalledAnchor anchor =
                  CanonicalGameplayIssuerBootstrapCoverageRepository
                      .readInstalledAnchorForBindingPreparationInTransaction(transaction, before);
              CanonicalGameplayIssuerAdmissionControlValue control =
                  CanonicalGameplayIssuerAdmissionControlRepository.requireOpenInTransaction(
                      transaction, anchor, before);
              Record byOperation =
                  transaction.fetchOne(
                      "SELECT * FROM "
                          + INITIALIZER
                          + " WHERE operation_id = ? AND issuer_id = ? FOR UPDATE",
                      operationId,
                      ISSUER);
              if (byOperation != null) {
                CanonicalGameplayIssuerAdmissionBarrierValue frozen =
                    readFrozenInitializer(transaction, byOperation, null, anchor, control, before);
                if (requestAssignment != null) {
                  requireSameTargetAssignment(requestAssignment, frozen.initializer());
                }
                return new PreparedInitializerSource(frozen, null);
              }
              if (requestAssignment == null) {
                throw conflict("The exact original tenant initializer disappeared");
              }

              CurrentRequestTarget requestTarget =
                  requireCurrentRequestTarget(transaction, operationId, requestAssignment, before);
              Record targetInitializer =
                  transaction.fetchOne(
                      "SELECT * FROM "
                          + INITIALIZER
                          + " WHERE issuer_id = ? AND tenant_id = ?"
                          + " AND playable_state_namespace_id = ? AND live_barrier_epoch = ?"
                          + " FOR UPDATE",
                      ISSUER,
                      requestAssignment.tenantId(),
                      requestAssignment.playableStateNamespaceId(),
                      control.liveBarrierEpoch());
              if (targetInitializer != null) {
                CanonicalGameplayIssuerAdmissionBarrierValue frozen =
                    readFrozenInitializer(
                        transaction, targetInitializer, requestAssignment, anchor, control, before);
                return new PreparedInitializerSource(frozen, requestAssignment);
              }

              CanonicalGameplayAdmissionBarrierTuple tuple =
                  buildOriginalInitializer(
                      transaction,
                      operationId,
                      requestTarget,
                      requestAssignment,
                      anchor,
                      control,
                      before);
              CanonicalGameplayIssuerAdmissionBarrierValue original =
                  CanonicalGameplayIssuerAdmissionBarrierValue.open(tuple.initializer());
              persistOriginalInitializer(transaction, original);
              return new PreparedInitializerSource(original, requestAssignment);
            });
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current =
        readCurrentState();
    CanonicalGameplayIssuerAdmissionBarrierValue value = source.value();
    if (!value.initializer().matchesCurrentIssuerState(current)) {
      throw conflict("Original OPEN initializer no longer matches current Account issuer source");
    }
    boolean createPermit =
        claimFirstCreatePermit(value.initializer().operationId(), value, current);
    CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared =
        prepared(value, source.requestAssignment(), operationId, createPermit);
    requireStillCurrent(prepared);
    return prepared;
  }

  /** Starts the one issuer-wide live cutoff after resolving the complete durable target set. */
  CanonicalGameplayIssuerAdmissionControlValue beginCutoff(UUID operationId, UUID requestId) {
    requireNonNil(operationId, "operationId");
    requireNonNil(requestId, "requestId");
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState before =
        readCurrentState();
    CanonicalGameplayIssuerInstalledAnchor anchor = readInstalledAnchor(before);
    CanonicalGameplayIssuerAdmissionControlValue pending =
        new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
            .beginCutoff(operationId, requestId, anchor, before);
    if (!pending.matchesCurrentIssuerState(readCurrentState())) {
      throw conflict("Current issuer source or stable incarnation moved during cutoff capture");
    }
    return pending;
  }

  /** Completes the durable cutoff only after every frozen target has a CLOSED readback. */
  CanonicalGameplayIssuerAdmissionControlValue completeCutoff(UUID operationId) {
    requireNonNil(operationId, "operationId");
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState before =
        readCurrentState();
    CanonicalGameplayIssuerInstalledAnchor anchor = readInstalledAnchor(before);
    CanonicalGameplayIssuerAdmissionControlValue installed =
        new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
            .completeCutoff(operationId, anchor, before);
    if (!installed.matchesCurrentIssuerState(readCurrentState())) {
      throw conflict("Current issuer source or stable incarnation moved during cutoff completion");
    }
    return installed;
  }

  List<CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget>
      readPreparedCutoffTargets(UUID cutoffOperationId) {
    requireNonNil(cutoffOperationId, "cutoffOperationId");
    List<Record> targets =
        dsl.fetch(
            "SELECT tenant_id, playable_state_namespace_id FROM "
                + CUTOFF_TARGET
                + " WHERE operation_id = ? AND issuer_id = ? ORDER BY target_ordinal",
            cutoffOperationId,
            ISSUER);
    List<CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget> prepared =
        new ArrayList<>();
    for (Record target : targets) {
      prepared.add(
          readPreparedCutoffTarget(
              cutoffOperationId,
              requiredUuid(target, "tenant_id"),
              requiredUuid(target, "playable_state_namespace_id")));
    }
    return List.copyOf(prepared);
  }

  @Override
  public CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget
      readPreparedCutoffTarget(UUID cutoffOperationId, UUID tenantId, UUID namespaceId) {
    requireNonNil(cutoffOperationId, "cutoffOperationId");
    requireNonNil(tenantId, "tenantId");
    requireNonNil(namespaceId, "namespaceId");
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current =
        readCurrentState();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          CanonicalGameplayIssuerInstalledAnchor anchor =
              CanonicalGameplayIssuerBootstrapCoverageRepository
                  .readInstalledAnchorForBindingPreparationInTransaction(transaction, current);
          CanonicalGameplayIssuerAdmissionControlValue control =
              readCurrentControl(transaction, anchor, current);
          if (!cutoffOperationId.equals(control.currentOperationId())
              || control.state()
                  != CanonicalGameplayIssuerAdmissionControlValue.State.CUTOFF_PENDING) {
            throw conflict("The exact current issuer cutoff is not pending");
          }
          new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
              .verifyCurrentCutoffTarget(transaction, control, tenantId, namespaceId, null);
          Record target =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + CUTOFF_TARGET
                      + " WHERE operation_id = ? AND issuer_id = ? AND tenant_id = ?"
                      + " AND playable_state_namespace_id = ? FOR UPDATE",
                  cutoffOperationId,
                  ISSUER,
                  tenantId,
                  namespaceId);
          if (target == null
              || !tenantId.equals(requiredUuid(target, "tenant_id"))
              || !namespaceId.equals(requiredUuid(target, "playable_state_namespace_id"))) {
            throw conflict("The exact durable cutoff target is unavailable");
          }
          String tenantTag = requireText(target, "tenant_gameplay_tag");
          requireCurrentTagMapping(transaction, tenantId, tenantTag);
          CanonicalGameplayIssuerAdmissionBarrierValue closed =
              CanonicalGameplayIssuerAdmissionBarrierValue.decode(
                  requireBytes(target, "closed_payload_bytes"));
          byte[] capturedOpen = target.get("captured_open_payload_bytes", byte[].class);
          CanonicalGameplayIssuerAdmissionBarrierValue expectedOpen =
              capturedOpen == null
                  ? null
                  : CanonicalGameplayIssuerAdmissionBarrierValue.decode(capturedOpen);
          if (!closed.exactDigest().equals(target.get("closed_payload_digest", String.class))) {
            throw conflict("Persisted issuer cutoff target digest is invalid");
          }
          return new CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget(
              closed,
              expectedOpen,
              CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(
                  ISSUER, tenantId, namespaceId, tenantTag));
        });
  }

  @Override
  public void requireStillCurrent(
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared) {
    Objects.requireNonNull(prepared, "prepared");
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current =
        readCurrentState();
    CanonicalGameplayIssuerAdmissionBarrierValue.Initializer initializer =
        prepared.value().initializer();
    if (!initializer.matchesCurrentIssuerState(current)) {
      throw conflict("Original OPEN initializer no longer matches current Account issuer source");
    }
    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
        prepared.assignment() == null
            ? null
            : tenantTags.assignmentForTransition(prepared.assignment().transitionId());
    if (assignment != null) requireSameAssignment(prepared.assignment(), assignment);
    dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          CanonicalGameplayIssuerInstalledAnchor anchor =
              CanonicalGameplayIssuerBootstrapCoverageRepository
                  .readInstalledAnchorForBindingPreparationInTransaction(transaction, current);
          CanonicalGameplayIssuerAdmissionControlValue control =
              CanonicalGameplayIssuerAdmissionControlRepository.requireOpenInTransaction(
                  transaction, anchor, current);
          if (!control.liveBarrierEpoch().equals(initializer.admissionEpoch())
              || !control.currentOperationId().equals(anchor.bootstrapOperationId())) {
            throw conflict("Original initializer no longer belongs to current OPEN live control");
          }
          if (assignment != null) {
            requireCurrentTagMapping(
                transaction, assignment.tenantId(), assignment.tenantGameplayTag());
            requireCurrentRequestTarget(
                transaction, prepared.requestOperationId(), assignment, current);
          }
          Record saved =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + INITIALIZER
                      + " WHERE operation_id = ? AND issuer_id = ? FOR UPDATE",
                  initializer.operationId(),
                  ISSUER);
          readFrozenInitializer(
              transaction, saved, prepared.assignment(), anchor, control, current);
          return null;
        });
  }

  @Override
  public void requireStillCurrent(
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget prepared) {
    Objects.requireNonNull(prepared, "prepared");
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current =
        readCurrentState();
    CanonicalGameplayIssuerAdmissionBarrierValue closed = prepared.closedValue();
    dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          CanonicalGameplayIssuerInstalledAnchor anchor =
              CanonicalGameplayIssuerBootstrapCoverageRepository
                  .readInstalledAnchorForBindingPreparationInTransaction(transaction, current);
          CanonicalGameplayIssuerAdmissionControlValue value =
              readCurrentControl(transaction, anchor, current);
          if (!closed.ownerOperationId().equals(value.currentOperationId())
              || value.state() != CanonicalGameplayIssuerAdmissionControlValue.State.CUTOFF_PENDING
              || !closed.admissionEpoch().equals(value.liveBarrierEpoch())) {
            throw conflict("Prepared CLOSED target no longer belongs to current pending cutoff");
          }
          new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
              .verifyCurrentCutoffTarget(
                  transaction,
                  value,
                  closed.tenantId(),
                  closed.playableStateNamespaceId(),
                  closed.exactBytes());
          requireCurrentTagMapping(transaction, closed.tenantId(), closed.tenantGameplayTag());
          return null;
        });
  }

  @Override
  public void recordOpenReadback(
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
    Objects.requireNonNull(prepared, "prepared");
    Objects.requireNonNull(readback, "readback");
    requireStillCurrent(prepared);
    CanonicalGameplayIssuerAdmissionBarrierValue value = prepared.value();
    if (!Arrays.equals(value.exactBytes(), readback.exactPhysicalPayload())
        || !Arrays.equals(value.exactBytes(), readback.value().exactBytes())
        || readback.scriptOutcome() < 1
        || readback.scriptOutcome() > 2) {
      throw conflict("OPEN readback does not match the exact original initializer");
    }
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current =
        readCurrentState();
    dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          CanonicalGameplayIssuerInstalledAnchor anchor =
              CanonicalGameplayIssuerBootstrapCoverageRepository
                  .readInstalledAnchorForBindingPreparationInTransaction(transaction, current);
          CanonicalGameplayIssuerAdmissionControlRepository.requireOpenInTransaction(
              transaction, anchor, current);
          Record initializer =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + INITIALIZER
                      + " WHERE operation_id = ? AND issuer_id = ? FOR UPDATE",
                  value.initializer().operationId(),
                  ISSUER);
          requirePersistedInitializer(initializer, value);
          String initializationState = initializer.get("initialization_state", String.class);
          if (!List.of("INITIALIZING", "OPEN_CONFIRMED").contains(initializationState)) {
            throw conflict("OPEN readback has no durable first-attempt initializer state");
          }
          transaction.execute(
              "INSERT INTO "
                  + INITIALIZER_RECEIPT
                  + " (operation_id, issuer_id, exact_open_payload_bytes,"
                  + " exact_open_payload_digest, script_outcome) VALUES (?, ?, ?, ?, ?)"
                  + " ON CONFLICT (operation_id) DO NOTHING",
              value.initializer().operationId(),
              ISSUER,
              value.exactBytes(),
              value.exactDigest(),
              (short) readback.scriptOutcome());
          Record receipt =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + INITIALIZER_RECEIPT
                      + " WHERE operation_id = ? AND issuer_id = ? FOR UPDATE",
                  value.initializer().operationId(),
                  ISSUER);
          if (receipt == null
              || !Arrays.equals(
                  requireBytes(receipt, "exact_open_payload_bytes"), value.exactBytes())
              || !value.exactDigest().equals(receipt.get("exact_open_payload_digest", String.class))
              || !List.of((short) 1, (short) 2)
                  .contains(receipt.get("script_outcome", Short.class))) {
            throw conflict(
                "Original OPEN readback receipt conflicts with durable initializer bytes");
          }
          if ("INITIALIZING".equals(initializationState)) {
            int confirmed =
                transaction.execute(
                    "UPDATE "
                        + INITIALIZER
                        + " SET initialization_state = 'OPEN_CONFIRMED'"
                        + " WHERE operation_id = ? AND issuer_id = ?"
                        + " AND initialization_state = 'INITIALIZING'",
                    value.initializer().operationId(),
                    ISSUER);
            if (confirmed != 1) {
              throw conflict("Original OPEN receipt could not confirm its initializer state");
            }
          }
          return null;
        });
  }

  @Override
  public void recordClosedReadback(
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget prepared,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
    Objects.requireNonNull(prepared, "prepared");
    Objects.requireNonNull(readback, "readback");
    requireStillCurrent(prepared);
    CanonicalGameplayIssuerAdmissionBarrierValue value = prepared.closedValue();
    if (!Arrays.equals(value.exactBytes(), readback.exactPhysicalPayload())
        || !Arrays.equals(value.exactBytes(), readback.value().exactBytes())) {
      throw conflict("CLOSED readback differs from the exact cutoff target");
    }
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current =
        readCurrentState();
    dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          CanonicalGameplayIssuerInstalledAnchor anchor =
              CanonicalGameplayIssuerBootstrapCoverageRepository
                  .readInstalledAnchorForBindingPreparationInTransaction(transaction, current);
          CanonicalGameplayIssuerAdmissionControlValue control =
              readCurrentControl(transaction, anchor, current);
          if (!value.ownerOperationId().equals(control.currentOperationId())
              || control.state()
                  != CanonicalGameplayIssuerAdmissionControlValue.State.CUTOFF_PENDING) {
            throw conflict("CLOSED readback is not owned by the current pending cutoff");
          }
          new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
              .verifyCurrentCutoffTarget(
                  transaction,
                  control,
                  value.tenantId(),
                  value.playableStateNamespaceId(),
                  value.exactBytes());
          requireCurrentTagMapping(transaction, value.tenantId(), value.tenantGameplayTag());
          Record target =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + CUTOFF_TARGET
                      + " WHERE operation_id = ? AND issuer_id = ? AND tenant_id = ?"
                      + " AND playable_state_namespace_id = ? FOR UPDATE",
                  value.ownerOperationId(),
                  ISSUER,
                  value.tenantId(),
                  value.playableStateNamespaceId());
          if (target == null
              || !Arrays.equals(requireBytes(target, "closed_payload_bytes"), value.exactBytes())
              || !value.exactDigest().equals(target.get("closed_payload_digest", String.class))) {
            throw conflict("CLOSED readback target changed before durable acknowledgement");
          }
          String state = target.get("target_state", String.class);
          if ("REQUIRED".equals(state)) {
            int updated =
                transaction.execute(
                    "UPDATE "
                        + CUTOFF_TARGET
                        + " SET exact_closed_readback_bytes = ?, exact_closed_readback_digest = ? ,"
                        + " script_outcome = ?, target_state = 'CLOSED'"
                        + " WHERE operation_id = ? AND target_ordinal = ? AND target_state = 'REQUIRED'",
                    value.exactBytes(),
                    value.exactDigest(),
                    (short) readback.scriptOutcome(),
                    value.ownerOperationId(),
                    target.get("target_ordinal", Long.class));
            if (updated != 1) throw conflict("CLOSED target changed during receipt persistence");
          } else if (!"CLOSED".equals(state)
              || !Arrays.equals(
                  requireBytes(target, "exact_closed_readback_bytes"), value.exactBytes())
              || !value
                  .exactDigest()
                  .equals(target.get("exact_closed_readback_digest", String.class))
              || !List.of((short) 1, (short) 2, (short) 3)
                  .contains(target.get("script_outcome", Short.class))) {
            throw conflict("CLOSED retry conflicts with its immutable durable readback");
          }
          return null;
        });
  }

  private CurrentRequestTarget requireCurrentRequestTarget(
      DSLContext transaction,
      UUID operationId,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    Record row =
        transaction.fetchOne(
            "SELECT op.operation_id, op.transition_id, op.binding_ref, op.member_value_bytes,"
                + " op.member_value_digest, op.member_status, op.issuer_id,"
                + " t.status AS transition_status, t.tenant_id AS transition_tenant_id,"
                + " t.playable_state_namespace_id AS transition_namespace_id,"
                + " t.admission_lease_id, t.admission_target_tenant_id,"
                + " t.admission_target_playable_state_namespace_id,"
                + " t.admission_target_owner_proof_outcome, t.admission_target_positive_durable_abort,"
                + " i.account_id, i.tenant_id, i.playable_state_namespace_id, i.lifecycle,"
                + " i.binding_ref AS inventory_binding_ref, r.reservation_id"
                + " FROM "
                + LOCAL_OPERATION
                + " op"
                + " JOIN game_session_canonical_binding_transition t ON t.transition_id = op.transition_id"
                + " JOIN game_session_canonical_gameplay_binding_inventory i"
                + " ON i.binding_ref = op.binding_ref AND i.transition_id = op.transition_id"
                + " JOIN game_session_canonical_issuer_partition_reservation r"
                + " ON r.reservation_id = op.reservation_id AND r.transition_id = op.transition_id"
                + " WHERE op.operation_id = ? AND op.issuer_id = ?"
                + " FOR UPDATE OF op, t, i, r",
            operationId,
            ISSUER);
    if (row == null) throw conflict("The exact original issuer local operation is unavailable");
    UUID transitionId = requiredUuid(row, "transition_id");
    UUID tenantId = requiredUuid(row, "transition_tenant_id");
    UUID namespaceId = requiredUuid(row, "transition_namespace_id");
    if (!transitionId.equals(assignment.transitionId())
        || !tenantId.equals(assignment.tenantId())
        || !namespaceId.equals(assignment.playableStateNamespaceId())
        || !tenantId.equals(requiredUuid(row, "tenant_id"))
        || !namespaceId.equals(requiredUuid(row, "playable_state_namespace_id"))
        || !requiredUuid(row, "account_id").equals(assignment.accountId())
        || !List.of("PROVISIONAL", "COMMITTED").contains(row.get("transition_status", String.class))
        || !List.of("PROVISIONAL", "ACTIVE").contains(row.get("lifecycle", String.class))
        || row.get("admission_lease_id", UUID.class) == null
        || !tenantId.equals(row.get("admission_target_tenant_id", UUID.class))
        || !namespaceId.equals(row.get("admission_target_playable_state_namespace_id", UUID.class))
        || !"COMMITTED".equals(row.get("admission_target_owner_proof_outcome", String.class))
        || !Boolean.FALSE.equals(
            row.get("admission_target_positive_durable_abort", Boolean.class))) {
      throw conflict("Current issuer NEW request is not the exact registered tenant target");
    }
    byte[] memberBytes = requireBytes(row, "member_value_bytes");
    if (!CanonicalGameplayIssuerMemberV2Codec.sha256(memberBytes)
            .equals(row.get("member_value_digest", String.class))
        || !"PENDING_PHYSICAL_PROOF".equals(row.get("member_status", String.class))) {
      throw conflict("Current local issuer operation has malformed pending member evidence");
    }
    CanonicalGameplayIssuerMemberV2Value member =
        CanonicalGameplayIssuerMemberV2Codec.decode(memberBytes);
    byte[] bindingRef = requireBytes(row, "binding_ref");
    if (!Arrays.equals(bindingRef, requireBytes(row, "inventory_binding_ref"))
        || !member.owner().tenantId().equals(tenantId)
        || !member.owner().playableStateNamespaceId().equals(namespaceId)
        || !member.owner().accountId().equals(assignment.accountId())
        || !Arrays.equals(member.owner().bindingRef().bytes(), bindingRef)
        || !member.sourceCheckpoint().issuerId().equals(ISSUER)
        || !member
            .sourceCheckpoint()
            .issuerAuthGeneration()
            .equals(current.accountCheckpoint().issuerAuthGeneration())
        || !Arrays.equals(
            member.sourceCheckpoint().exactCheckpointBytes(),
            current.accountCheckpoint().exactCheckpointBytes())
        || !Objects.equals(
            member.sourceCheckpoint().latestIssuerEventDigest(),
            current.accountCheckpoint().latestIssuerEventDigest())
        || !Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(member.incarnation()),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(current.incarnation()))) {
      throw conflict("Current local operation differs from the Account or incarnation source");
    }

    return new CurrentRequestTarget(
        transitionId, tenantId, namespaceId, assignment.accountId(), bindingRef);
  }

  private CanonicalGameplayAdmissionBarrierTuple buildOriginalInitializer(
      DSLContext transaction,
      UUID operationId,
      CurrentRequestTarget expected,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerAdmissionControlValue control,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    Record row =
        transaction.fetchOne(
            "SELECT op.transition_id, op.binding_ref, to_jsonb(t)::text AS registered_target_json,"
                + " to_jsonb(r)::text AS reservation_json"
                + " FROM "
                + LOCAL_OPERATION
                + " op"
                + " JOIN game_session_canonical_binding_transition t ON t.transition_id = op.transition_id"
                + " JOIN game_session_canonical_gameplay_binding_inventory i"
                + " ON i.binding_ref = op.binding_ref AND i.transition_id = op.transition_id"
                + " JOIN game_session_canonical_issuer_partition_reservation r"
                + " ON r.reservation_id = op.reservation_id AND r.transition_id = op.transition_id"
                + " WHERE op.operation_id = ? AND op.issuer_id = ?"
                + " FOR UPDATE OF op, t, i, r",
            operationId,
            ISSUER);
    if (row == null
        || !expected.transitionId().equals(requiredUuid(row, "transition_id"))
        || !Arrays.equals(expected.bindingRef(), requireBytes(row, "binding_ref"))) {
      throw conflict(
          "Current issuer NEW target changed before its original initializer was captured");
    }
    byte[] reservationBytes = requireText(row, "reservation_json").getBytes(StandardCharsets.UTF_8);
    byte[] targetBytes =
        requireText(row, "registered_target_json").getBytes(StandardCharsets.UTF_8);
    CanonicalGameplayIssuerAdmissionBarrierValue.Initializer initializer =
        new CanonicalGameplayIssuerAdmissionBarrierValue.Initializer(
            ISSUER,
            expected.tenantId(),
            expected.namespaceId(),
            assignment.tenantGameplayTag(),
            operationId,
            control.liveBarrierEpoch(),
            expected.transitionId(),
            expected.accountId(),
            expected.bindingRef(),
            reservationBytes,
            CanonicalGameplayIssuerAdmissionBarrierValue.sha256(reservationBytes),
            anchor.exactAnchorBytes(),
            anchor.exactAnchorDigest(),
            current.accountCheckpoint().issuerAuthGeneration(),
            current.accountCheckpoint().exactCheckpointBytes(),
            current.accountCheckpoint().checkpointDigest(),
            current.accountCheckpoint().latestIssuerEventBytes(),
            current.accountCheckpoint().latestIssuerEventDigest(),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(current.incarnation()),
            CanonicalGameplayIssuerMemberV2Codec.sha256(
                CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(current.incarnation())),
            targetBytes,
            CanonicalGameplayIssuerAdmissionBarrierValue.sha256(targetBytes));
    return new CanonicalGameplayAdmissionBarrierTuple(initializer);
  }

  private CanonicalGameplayIssuerAdmissionBarrierValue readFrozenInitializer(
      DSLContext transaction,
      Record row,
      CanonicalGameplayTenantGameplayTagRepository.Assignment expectedTarget,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerAdmissionControlValue control,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    if (row == null) throw conflict("The durable original tenant initializer is missing");
    byte[] payload = requireBytes(row, "open_payload_bytes");
    CanonicalGameplayIssuerAdmissionBarrierValue value =
        CanonicalGameplayIssuerAdmissionBarrierValue.decode(payload);
    CanonicalGameplayIssuerAdmissionBarrierValue.Initializer initializer = value.initializer();
    requirePersistedInitializer(row, value);
    String state = row.get("initialization_state", String.class);
    if (initializer == null
        || value.state() != CanonicalGameplayIssuerAdmissionBarrierValue.State.OPEN
        || !initializer.operationId().equals(row.get("operation_id", UUID.class))
        || !initializer.issuerId().equals(row.get("issuer_id", String.class))
        || !control.liveBarrierEpoch().equals(initializer.admissionEpoch())
        || !initializer.matchesCurrentIssuerState(current)
        || !Arrays.equals(initializer.anchorBytes(), anchor.exactAnchorBytes())
        || !initializer.anchorDigest().equals(anchor.exactAnchorDigest())
        || !List.of("PREPARED", "INITIALIZING", "OPEN_CONFIRMED").contains(state)) {
      throw conflict("Frozen tenant initializer differs from current positive source and control");
    }
    requireCurrentTagMapping(transaction, initializer.tenantId(), initializer.tenantGameplayTag());
    if (expectedTarget != null) requireSameTargetAssignment(expectedTarget, initializer);
    return value;
  }

  private static void requireCurrentTagMapping(
      DSLContext transaction, UUID tenantId, String expectedTag) {
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
        || !tag.equals(expectedTag)
        || !tag.equals("g" + version + "-" + tenantId.toString().replace("-", ""))) {
      throw conflict("Frozen issuer target has no exact current SQL tenant gameplay-tag mapping");
    }
  }

  private static void persistOriginalInitializer(
      DSLContext transaction, CanonicalGameplayIssuerAdmissionBarrierValue value) {
    CanonicalGameplayIssuerAdmissionBarrierValue.Initializer initializer = value.initializer();
    Record prior =
        transaction.fetchOne(
            "SELECT * FROM " + INITIALIZER + " WHERE operation_id = ? AND issuer_id = ? FOR UPDATE",
            initializer.operationId(),
            ISSUER);
    if (prior != null) {
      requirePersistedInitializer(prior, value);
      return;
    }
    int inserted =
        transaction.execute(
            "INSERT INTO "
                + INITIALIZER
                + " (operation_id, issuer_id, tenant_id, playable_state_namespace_id,"
                + " tenant_gameplay_tag, live_barrier_epoch, initialization_state,"
                + " initializer_exact_bytes,"
                + " initializer_digest, open_payload_bytes, open_payload_digest)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?)",
            initializer.operationId(),
            initializer.issuerId(),
            initializer.tenantId(),
            initializer.playableStateNamespaceId(),
            initializer.tenantGameplayTag(),
            initializer.admissionEpoch(),
            initializer.exactBytes(),
            initializer.exactDigest(),
            value.exactBytes(),
            value.exactDigest());
    if (inserted != 1) throw conflict("Original issuer initializer did not persist before OPEN");
    Record saved =
        transaction.fetchOne(
            "SELECT * FROM " + INITIALIZER + " WHERE operation_id = ? AND issuer_id = ? FOR UPDATE",
            initializer.operationId(),
            ISSUER);
    requirePersistedInitializer(saved, value);
  }

  private static void requirePersistedInitializer(
      Record row, CanonicalGameplayIssuerAdmissionBarrierValue expected) {
    if (row == null
        || !expected.issuerId().equals(row.get("issuer_id", String.class))
        || !expected.tenantId().equals(row.get("tenant_id", UUID.class))
        || !expected
            .playableStateNamespaceId()
            .equals(row.get("playable_state_namespace_id", UUID.class))
        || !expected.tenantGameplayTag().equals(row.get("tenant_gameplay_tag", String.class))
        || !expected.admissionEpoch().equals(readBigInteger(row, "live_barrier_epoch"))
        || !Arrays.equals(
            expected.initializer().exactBytes(), requireBytes(row, "initializer_exact_bytes"))
        || !expected.initializer().exactDigest().equals(row.get("initializer_digest", String.class))
        || !Arrays.equals(expected.exactBytes(), requireBytes(row, "open_payload_bytes"))
        || !expected.exactDigest().equals(row.get("open_payload_digest", String.class))) {
      throw conflict("Persisted original issuer initializer differs from its exact retry source");
    }
  }

  private CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared(
      CanonicalGameplayIssuerAdmissionBarrierValue value,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      UUID requestOperationId,
      boolean createPermit) {
    return new CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer(
        value,
        assignment,
        requestOperationId,
        CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(
            ISSUER, value.tenantId(), value.playableStateNamespaceId(), value.tenantGameplayTag()),
        createPermit);
  }

  /**
   * Consumes the sole durable authority to create the first Redis OPEN. The state transition is
   * committed before the Redis owner can attempt its command; every later call is replay-only.
   */
  private boolean claimFirstCreatePermit(
      UUID operationId,
      CanonicalGameplayIssuerAdmissionBarrierValue value,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          CanonicalGameplayIssuerInstalledAnchor anchor =
              CanonicalGameplayIssuerBootstrapCoverageRepository
                  .readInstalledAnchorForBindingPreparationInTransaction(transaction, current);
          CanonicalGameplayIssuerAdmissionControlValue control =
              CanonicalGameplayIssuerAdmissionControlRepository.requireOpenInTransaction(
                  transaction, anchor, current);
          if (!control.liveBarrierEpoch().equals(value.admissionEpoch())
              || !control.currentOperationId().equals(anchor.bootstrapOperationId())) {
            throw conflict(
                "Original initializer no longer belongs to the current OPEN live control");
          }
          Record saved =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + INITIALIZER
                      + " WHERE operation_id = ? AND issuer_id = ? FOR UPDATE",
                  operationId,
                  ISSUER);
          requirePersistedInitializer(saved, value);
          String state = saved.get("initialization_state", String.class);
          if ("PREPARED".equals(state)) {
            Record origin =
                transaction.fetchOne(
                    "SELECT transition.status AS transition_status FROM "
                        + LOCAL_OPERATION
                        + " operation JOIN game_session_canonical_binding_transition transition"
                        + " ON transition.transition_id = operation.transition_id"
                        + " WHERE operation.operation_id = ? AND operation.issuer_id = ?"
                        + " FOR SHARE OF operation, transition",
                    operationId,
                    ISSUER);
            if (origin == null) {
              throw conflict("Original initializer lost its persisted local-operation provenance");
            }
            if ("ABORTED".equals(origin.get("transition_status", String.class))) {
              return false;
            }
            if (!List.of("PROVISIONAL", "COMMITTED")
                .contains(origin.get("transition_status", String.class))) {
              throw conflict("Original initializer source is no longer an eligible first NEW");
            }
            int claimed =
                transaction.execute(
                    "UPDATE "
                        + INITIALIZER
                        + " SET initialization_state = 'INITIALIZING'"
                        + " WHERE operation_id = ? AND issuer_id = ?"
                        + " AND initialization_state = 'PREPARED'",
                    operationId,
                    ISSUER);
            if (claimed != 1) {
              throw conflict("Original initializer first-create permit was not uniquely claimed");
            }
            return true;
          }
          if ("INITIALIZING".equals(state) || "OPEN_CONFIRMED".equals(state)) return false;
          throw conflict("Original initializer has an unknown or non-admitting durable state");
        });
  }

  private UUID transitionForLocalOperation(UUID operationId) {
    Record row =
        dsl.fetchOne(
            "SELECT transition_id FROM "
                + LOCAL_OPERATION
                + " WHERE operation_id = ? AND issuer_id = ?",
            operationId,
            ISSUER);
    if (row == null) throw conflict("The exact original issuer local operation is unavailable");
    return requiredUuid(row, "transition_id");
  }

  private CanonicalGameplayIssuerInstalledAnchor readInstalledAnchor(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          return CanonicalGameplayIssuerBootstrapCoverageRepository
              .readInstalledAnchorForBindingPreparationInTransaction(transaction, current);
        });
  }

  private CanonicalGameplayIssuerAdmissionControlValue readCurrentControl(
      DSLContext transaction,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + CONTROL + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (row == null) throw conflict("The positive live issuer admission-control row is missing");
    CanonicalGameplayIssuerAdmissionControlValue value =
        CanonicalGameplayIssuerAdmissionControlValue.decode(
            requireBytes(row, "control_exact_bytes"));
    if (!CanonicalGameplayIssuerAdmissionControlValue.sha256(value.exactBytes())
            .equals(row.get("control_exact_digest", String.class))
        || !value.issuerId().equals(row.get("issuer_id", String.class))
        || !value.matchesCurrentIssuerState(current)
        || !Arrays.equals(value.anchorExactBytes(), anchor.exactAnchorBytes())
        || !value.anchorExactDigest().equals(anchor.exactAnchorDigest())) {
      throw conflict(
          "Issuer control differs from its exact installed anchor and fresh current source");
    }
    return value;
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      readCurrentState() {
    try {
      return Objects.requireNonNull(
          currentStateSource.readFreshCurrentState(), "Current issuer source returned null");
    } catch (RuntimeException failure) {
      throw conflict("Current Account issuer source or stable incarnation is unavailable");
    }
  }

  private static void requireSameAssignment(
      CanonicalGameplayTenantGameplayTagRepository.Assignment expected,
      CanonicalGameplayTenantGameplayTagRepository.Assignment actual) {
    if (!expected.tenantId().equals(actual.tenantId())
        || !expected.accountId().equals(actual.accountId())
        || !expected.playableStateNamespaceId().equals(actual.playableStateNamespaceId())
        || !expected.tenantGameplayTag().equals(actual.tenantGameplayTag())
        || !Objects.equals(expected.transitionId(), actual.transitionId())) {
      throw conflict("Current tenant target assignment differs from the exact durable original");
    }
  }

  private static void requireSameTargetAssignment(
      CanonicalGameplayTenantGameplayTagRepository.Assignment expected,
      CanonicalGameplayIssuerAdmissionBarrierValue.Initializer actual) {
    if (!expected.tenantId().equals(actual.tenantId())
        || !expected.playableStateNamespaceId().equals(actual.playableStateNamespaceId())
        || !expected.tenantGameplayTag().equals(actual.tenantGameplayTag())) {
      throw conflict("Fresh NEW request scope differs from the frozen tenant initializer target");
    }
  }

  private static byte[] requireBytes(Record row, String field) {
    byte[] value = row == null ? null : row.get(field, byte[].class);
    if (value == null) throw conflict("Persisted issuer admission bytes are missing: " + field);
    return value.clone();
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row == null ? null : row.get(field, UUID.class);
    if (value == null) throw conflict("Persisted issuer admission UUID is missing: " + field);
    return value;
  }

  private static String requireText(Record row, String field) {
    String value = row == null ? null : row.get(field, String.class);
    if (value == null) throw conflict("Persisted issuer admission text is missing: " + field);
    return value;
  }

  private static BigInteger readBigInteger(Record row, String field) {
    java.math.BigDecimal value = row == null ? null : row.get(field, java.math.BigDecimal.class);
    if (value == null) throw conflict("Persisted issuer admission integer is missing: " + field);
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException malformed) {
      throw conflict("Persisted issuer admission integer is malformed: " + field);
    }
  }

  private static void requireNonNil(UUID value, String name) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw conflict("Issuer tenant barrier owner cannot join an ambient SQL transaction");
    }
    try {
      dsl.connectionResult(
          connection -> {
            if (!connection.getAutoCommit()) {
              throw conflict("Issuer tenant barrier owner cannot join a jOOQ transaction");
            }
            return Boolean.TRUE;
          });
    } catch (RuntimeException failure) {
      if (failure instanceof CanonicalGameplayBindingInventoryConflictException) throw failure;
      throw new CanonicalGameplayBindingInventoryConflictException(
          "Issuer tenant barrier SQL connection state is unavailable", failure);
    }
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict(String message) {
    return new CanonicalGameplayBindingInventoryConflictException(message);
  }

  private record CanonicalGameplayAdmissionBarrierTuple(
      CanonicalGameplayIssuerAdmissionBarrierValue.Initializer initializer) {}

  private record PreparedInitializerSource(
      CanonicalGameplayIssuerAdmissionBarrierValue value,
      CanonicalGameplayTenantGameplayTagRepository.Assignment requestAssignment) {}

  private record CurrentRequestTarget(
      UUID transitionId, UUID tenantId, UUID namespaceId, UUID accountId, byte[] bindingRef) {
    private CurrentRequestTarget {
      Objects.requireNonNull(transitionId, "transitionId");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(namespaceId, "namespaceId");
      Objects.requireNonNull(accountId, "accountId");
      bindingRef = Objects.requireNonNull(bindingRef, "bindingRef").clone();
    }

    @Override
    public byte[] bindingRef() {
      return bindingRef.clone();
    }
  }
}
