package net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL owner for exact per-partition proof retention and one local initial anchor installation. */
final class CanonicalGameplayIssuerBootstrapCoverageRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String OPERATION =
      "game_session_canonical_issuer_bootstrap_member_operation";
  private static final String PROOF = "game_session_canonical_issuer_bootstrap_partition_proof";
  private static final String COUNTER = "game_session_canonical_issuer_bootstrap_coverage_counter";
  private static final String ANCHOR = "game_session_canonical_issuer_installed_anchor";
  private static final String CONTROL = "game_session_canonical_issuer_layout_bootstrap_control";
  private static final String FIELDSET_MAGIC = "issuer-physical-field-set/v1";
  private static final int MAX_LOCAL_PHYSICAL_FIELD_COUNT =
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_LOCAL_HASH_FIELDS;
  private static final String METADATA_FIELD =
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD;
  private static final String CAPACITY_PREFIX =
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.RESERVATION_FIELD_PREFIX;

  private final DSLContext dsl;

  CanonicalGameplayIssuerBootstrapCoverageRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  void recordPartitionProof(CanonicalGameplayIssuerBootstrapPartitionRedisOwner.Readback readback) {
    Objects.requireNonNull(readback, "readback");
    CanonicalGameplayIssuerBootstrapPartitionWork work = readback.work();
    requireNoAmbientTransaction();
    ProofInput input = proofInput(readback);
    dsl.transaction(
        configuration -> {
          DSLContext transaction = org.jooq.impl.DSL.using(configuration);
          CanonicalGameplayIssuerPartitionBuildPlan currentPlan =
              new CanonicalGameplayIssuerPartitionBuildRepository(transaction)
                  .readPreparedPlanInTransaction(
                      transaction,
                      work.buildPlan().bootstrapIntent().operationId(),
                      work.buildPlan().bootstrapIntent().requestId());
          requireSamePlan(currentPlan, work.buildPlan());
          requireProofContext(input, currentPlan);
          Record existing =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + PROOF
                      + " WHERE bootstrap_operation_id = ? AND partition_id = ? FOR UPDATE",
                  currentPlan.bootstrapIntent().operationId(),
                  input.partitionId());
          if (existing != null) {
            requireSameProof(existing, input);
            CanonicalGameplayIssuerPartitionMutationRepository
                .recordInitialPhysicalReadbackInTransaction(
                    transaction,
                    work.buildPlan().bootstrapIntent().operationId(),
                    input.partitionId(),
                    work.initialPhysicalRevision(),
                    input.fieldSetBytes());
            return;
          }
          int inserted =
              transaction.execute(
                  "INSERT INTO "
                      + PROOF
                      + " ("
                      + "build_operation_id, bootstrap_operation_id, bootstrap_request_id, issuer_id, "
                      + "build_plan_digest, bootstrap_operation_fence, bootstrap_coverage_fence, "
                      + "issuer_inventory_snapshot_revision, layout_version, partition_count, "
                      + "partition_capacity, mapping_identity, layout_descriptor_bytes, "
                      + "layout_descriptor_digest, source_checkpoint_bytes, source_checkpoint_digest, "
                      + "source_event_bytes, source_event_digest, incarnation_bytes, incarnation_digest, "
                      + "partition_id, initial_physical_mutation_revision, exact_metadata_bytes, "
                      + "exact_physical_field_set_bytes, "
                      + "exact_physical_field_set_digest, physical_field_count, physical_reservation_count, "
                      + "physical_binding_count, physical_counted_capacity_count, "
                      + "planned_aggregate_capacity_count, proof_state) VALUES ("
                      + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EXACT_PHYSICAL_READBACK')",
                  input.buildOperationId(),
                  input.bootstrapOperationId(),
                  input.bootstrapRequestId(),
                  ISSUER,
                  input.planDigest(),
                  input.operationFence(),
                  input.coverageFence(),
                  input.snapshotRevision(),
                  input.layoutVersion(),
                  input.partitionCount(),
                  input.partitionCapacity(),
                  input.mappingIdentity(),
                  input.layoutDescriptorBytes(),
                  input.layoutDescriptorDigest(),
                  input.sourceCheckpointBytes(),
                  input.sourceCheckpointDigest(),
                  input.sourceEventBytes(),
                  input.sourceEventDigest(),
                  input.incarnationBytes(),
                  input.incarnationDigest(),
                  input.partitionId(),
                  input.initialPhysicalRevision(),
                  input.metadataBytes(),
                  input.fieldSetBytes(),
                  input.fieldSetDigest(),
                  input.fieldCount(),
                  input.reservationCount(),
                  input.bindingCount(),
                  input.countedCapacityCount(),
                  input.aggregateCountedCapacity());
          if (inserted != 1) throw conflict("Exact partition proof did not persist");
          Record saved =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + PROOF
                      + " WHERE bootstrap_operation_id = ? AND partition_id = ?",
                  input.bootstrapOperationId(),
                  input.partitionId());
          if (saved == null)
            throw conflict("Exact partition proof is unreadable after persistence");
          requireSameProof(saved, input);
          CanonicalGameplayIssuerPartitionMutationRepository
              .recordInitialPhysicalReadbackInTransaction(
                  transaction,
                  work.buildPlan().bootstrapIntent().operationId(),
                  input.partitionId(),
                  work.initialPhysicalRevision(),
                  input.fieldSetBytes());
        });
  }

  Optional<CanonicalGameplayIssuerInstalledAnchor> readInstalledAnchor(
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    Objects.requireNonNull(bootstrapOperationId, "bootstrapOperationId");
    Objects.requireNonNull(bootstrapRequestId, "bootstrapRequestId");
    Objects.requireNonNull(currentState, "currentState");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = org.jooq.impl.DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          Record anchorProbe =
              transaction.fetchOne(
                  "SELECT issuer_id FROM " + ANCHOR + " WHERE issuer_id = ?", ISSUER);
          if (anchorProbe == null) return Optional.empty();
          CanonicalGameplayIssuerPartitionBuildRepository buildRepository =
              new CanonicalGameplayIssuerPartitionBuildRepository(transaction);
          CanonicalGameplayIssuerPartitionBuildPlan plan =
              buildRepository.readInstalledPlanForAnchorInTransaction(
                  transaction, bootstrapOperationId, bootstrapRequestId);
          requireCurrentState(currentState, plan.bootstrapIntent());
          Record row =
              transaction.fetchOne(
                  "SELECT * FROM " + ANCHOR + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
          if (row == null) return Optional.empty();
          CanonicalGameplayIssuerInstalledAnchor installed = loadAnchor(row);
          requireAnchorMatchesPlan(installed, plan, currentState);
          requireProofSummaryMatchesAnchor(
              verifyCompleteProof(transaction, plan, installed.incarnationBytes()), installed);
          requireCounterCoversAnchor(transaction, installed);
          return Optional.of(installed);
        });
  }

  /**
   * Requalifies an installed anchor while the binding inventory clock is already locked. The caller
   * must acquire that clock before entering this method so the issuer-control lock remains second
   * in the shared inventory -> issuer -> account order.
   */
  static CanonicalGameplayIssuerInstalledAnchor
      readInstalledAnchorForBindingPreparationInTransaction(
          DSLContext transaction,
          CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(currentState, "currentState");

    Record control =
        transaction.fetchOne(
            "SELECT * FROM " + CONTROL + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (control == null
        || !ISSUER.equals(requireText(control, "issuer_id"))
        || !"INITIAL_BOOTSTRAP_INTENT".equals(requireText(control, "state"))) {
      throw conflict("Ordinary NEW requires the positive current installed issuer-control row");
    }
    UUID currentOperationId = requireUuid(control, "current_operation_id");
    BigInteger controlEpoch = readInteger(control, "control_epoch");
    BigInteger operationFence = readInteger(control, "current_operation_fence");
    BigInteger coverageFence = readInteger(control, "current_coverage_fence");
    if (controlEpoch.signum() <= 0
        || !controlEpoch.equals(operationFence)
        || coverageFence.signum() <= 0
        || coverageFence.equals(operationFence)
        || readInteger(control, "last_issued_fence").compareTo(coverageFence) < 0) {
      throw conflict("Current issuer-control tuple is malformed or no longer positive");
    }

    requireRetainedSupersessionLineage(transaction, currentOperationId);

    Record intentRequest =
        transaction.fetchOne(
            "SELECT request_id FROM "
                + "game_session_canonical_issuer_layout_bootstrap_intent"
                + " WHERE operation_id = ? AND issuer_id = ?",
            currentOperationId,
            ISSUER);
    if (intentRequest == null)
      throw conflict("Current issuer control points to no durable layout intent");
    CanonicalGameplayIssuerPartitionBuildPlan plan =
        new CanonicalGameplayIssuerPartitionBuildRepository(transaction)
            .readInstalledPlanForAnchorInTransaction(
                transaction, currentOperationId, requireUuid(intentRequest, "request_id"));
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    if (!currentOperationId.equals(intent.operationId())
        || !controlEpoch.equals(intent.controlEpoch())
        || !operationFence.equals(intent.operationFence())
        || !coverageFence.equals(intent.coverageFence())) {
      throw conflict("Current issuer control no longer points at its exact initial intent tuple");
    }
    requireCurrentState(currentState, intent);

    Record anchorRow =
        transaction.fetchOne("SELECT * FROM " + ANCHOR + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (anchorRow == null) {
      throw conflict("Ordinary NEW requires an actual immutable installed issuer anchor");
    }
    CanonicalGameplayIssuerInstalledAnchor actual = loadAnchor(anchorRow);
    requireAnchorMatchesPlan(actual, plan, currentState);
    requireProofSummaryMatchesAnchor(
        verifyCompleteProof(transaction, plan, actual.incarnationBytes()), actual);
    requireCounterCoversAnchor(transaction, actual);
    return actual;
  }

  /** Every retained intent must belong to the one issuer-bound chain ending at current control. */
  private static void requireRetainedSupersessionLineage(
      DSLContext transaction, UUID currentOperationId) {
    Map<UUID, Record> intents = new HashMap<>();
    for (Record intent :
        transaction.fetch(
            "SELECT * FROM game_session_canonical_issuer_layout_bootstrap_intent"
                + " WHERE issuer_id = ? ORDER BY operation_id FOR UPDATE",
            ISSUER)) {
      intents.put(requireUuid(intent, "operation_id"), intent);
    }
    Map<UUID, Record> incoming = new HashMap<>();
    Set<UUID> outgoing = new HashSet<>();
    for (Record relation :
        transaction.fetch(
            "SELECT * FROM game_session_canonical_issuer_bootstrap_supersession"
                + " WHERE issuer_id = ? ORDER BY old_operation_id FOR UPDATE",
            ISSUER)) {
      UUID oldId = requireUuid(relation, "old_operation_id");
      UUID newId = requireUuid(relation, "new_operation_id");
      Record oldIntent = intents.get(oldId);
      Record newIntent = intents.get(newId);
      if (oldIntent == null
          || newIntent == null
          || oldId.equals(newId)
          || !outgoing.add(oldId)
          || incoming.put(newId, relation) != null
          || !ISSUER.equals(requireText(relation, "issuer_id"))
          || !readInteger(relation, "old_inventory_revision")
              .equals(readInteger(oldIntent, "inventory_snapshot_revision"))
          || !readInteger(relation, "new_inventory_revision")
              .equals(readInteger(newIntent, "inventory_snapshot_revision"))
          || !readInteger(relation, "old_operation_fence")
              .equals(readInteger(oldIntent, "operation_fence"))
          || !readInteger(relation, "old_coverage_fence")
              .equals(readInteger(oldIntent, "coverage_fence"))
          || !readInteger(relation, "new_operation_fence")
              .equals(readInteger(newIntent, "operation_fence"))
          || !readInteger(relation, "new_coverage_fence")
              .equals(readInteger(newIntent, "coverage_fence"))
          || readInteger(newIntent, "inventory_snapshot_revision")
                  .compareTo(readInteger(oldIntent, "inventory_snapshot_revision"))
              <= 0
          || readInteger(newIntent, "layout_version")
                  .compareTo(readInteger(oldIntent, "layout_version"))
              <= 0
          || !readInteger(oldIntent, "control_epoch")
              .equals(readInteger(oldIntent, "operation_fence"))
          || !readInteger(newIntent, "control_epoch")
              .equals(readInteger(newIntent, "operation_fence"))
          || readInteger(relation, "previous_last_issued_fence")
                  .compareTo(readInteger(oldIntent, "operation_fence"))
              < 0
          || readInteger(relation, "previous_last_issued_fence")
                  .compareTo(readInteger(oldIntent, "coverage_fence"))
              < 0
          || !readInteger(newIntent, "operation_fence")
              .equals(readInteger(relation, "previous_last_issued_fence").add(BigInteger.ONE))
          || !readInteger(newIntent, "coverage_fence")
              .equals(readInteger(newIntent, "operation_fence").add(BigInteger.ONE))
          || requireUuid(newIntent, "request_id").equals(requireUuid(oldIntent, "request_id"))
          || requireUuid(newIntent, "request_id").equals(oldId)) {
        throw conflict("Malformed retained issuer bootstrap supersession lineage");
      }
    }
    Set<UUID> visited = new HashSet<>();
    UUID cursor = currentOperationId;
    if (outgoing.contains(cursor)) {
      throw conflict("Current issuer intent is not the terminal supersession lineage");
    }
    while (cursor != null) {
      if (!intents.containsKey(cursor) || !visited.add(cursor)) {
        throw conflict("Broken or cyclic retained issuer bootstrap supersession lineage");
      }
      Record edge = incoming.get(cursor);
      cursor = edge == null ? null : requireUuid(edge, "old_operation_id");
    }
    if (visited.size() != intents.size() || incoming.size() != intents.size() - 1) {
      throw conflict("A conflicting or unpointed issuer layout intent prevents ordinary NEW");
    }
  }

  /** Exact immutable baseline field set for one partition of the installed initial anchor. */
  static InstalledAnchorPartitionProof readInstalledAnchorPartitionProof(
      DSLContext transaction,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      BigInteger partitionId) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(partitionId, "partitionId");
    if (partitionId.signum() < 0 || partitionId.compareTo(anchor.partitionCount()) >= 0) {
      throw conflict("Candidate partition is outside the exact installed layout");
    }
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + PROOF + " WHERE bootstrap_operation_id = ? AND partition_id = ?",
            anchor.bootstrapOperationId(),
            new BigDecimal(partitionId));
    if (row == null
        || !"EXACT_PHYSICAL_READBACK".equals(requireText(row, "proof_state"))
        || !anchor.operationFence().equals(readInteger(row, "bootstrap_operation_fence"))
        || !anchor.coverageFence().equals(readInteger(row, "bootstrap_coverage_fence"))
        || !anchor
            .issuerInventorySnapshotRevision()
            .equals(readInteger(row, "issuer_inventory_snapshot_revision"))) {
      throw conflict("Exact installed-anchor partition proof is missing or mismatched");
    }
    byte[] fieldSetBytes = requireBytes(row, "exact_physical_field_set_bytes");
    String fieldSetDigest = requireText(row, "exact_physical_field_set_digest");
    if (!fieldSetDigest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(fieldSetBytes))) {
      throw conflict("Installed-anchor partition field set does not match its exact digest");
    }
    Record mutationHead =
        transaction.fetchOne(
            "SELECT * FROM "
                + CanonicalGameplayIssuerPartitionMutationRepository.HEAD_TABLE
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ?",
            ISSUER,
            anchor.bootstrapOperationId(),
            partitionId);
    if (mutationHead == null
        || !anchor
            .bootstrapOperationId()
            .equals(mutationHead.get("bootstrap_operation_id", UUID.class))
        || !"CURRENT".equals(mutationHead.get("state", String.class))) {
      throw conflict("Installed issuer partition lacks its positively installed mutation revision");
    }
    Map<String, byte[]> exactFields = decodeFieldSet(fieldSetBytes);
    UUID initialRevision = mutationHead.get("initial_revision", UUID.class);
    if (initialRevision == null) {
      throw conflict("Installed issuer partition initial mutation revision is missing");
    }
    if (!initialRevision.equals(row.get("initial_physical_mutation_revision", UUID.class))) {
      throw conflict("Installed issuer partition proof lacks its exact initial mutation revision");
    }
    CanonicalGameplayIssuerPartitionMutationRepository.requireRevisionField(
        exactFields, initialRevision);
    return new InstalledAnchorPartitionProof(
        partitionId,
        fieldSetBytes,
        fieldSetDigest,
        readInt(row, "physical_counted_capacity_count"),
        readInt(row, "physical_reservation_count"),
        readInt(row, "physical_binding_count"));
  }

  /** Refuses a post-anchor candidate whose exact field already exists in the frozen baseline. */
  static void requireNewMemberAbsentFromBaseline(
      InstalledAnchorPartitionProof proof,
      CanonicalGameplayBindingRef bindingRef,
      UUID reservationId) {
    Objects.requireNonNull(proof, "proof");
    Objects.requireNonNull(bindingRef, "bindingRef");
    Objects.requireNonNull(reservationId, "reservationId");
    Map<String, byte[]> fields = decodeFieldSet(proof.exactPhysicalFieldSetBytes());
    if (fields.containsKey(new String(bindingRef.bytes(), StandardCharsets.UTF_8))
        || fields.containsKey(CAPACITY_PREFIX + reservationId)) {
      throw conflict(
          "Installed issuer baseline already contains the requested NEW member or reservation");
    }
  }

  record InstalledAnchorPartitionProof(
      BigInteger partitionId,
      byte[] exactPhysicalFieldSetBytes,
      String exactPhysicalFieldSetDigest,
      int physicalCountedCapacityCount,
      int physicalReservationCount,
      int physicalBindingCount) {
    InstalledAnchorPartitionProof {
      Objects.requireNonNull(partitionId, "partitionId");
      exactPhysicalFieldSetBytes = Objects.requireNonNull(exactPhysicalFieldSetBytes).clone();
      Objects.requireNonNull(exactPhysicalFieldSetDigest, "exactPhysicalFieldSetDigest");
      if (physicalCountedCapacityCount < 0
          || physicalReservationCount < 0
          || physicalBindingCount < 0) {
        throw new IllegalArgumentException("Installed partition proof counts must be nonnegative");
      }
    }

    @Override
    public byte[] exactPhysicalFieldSetBytes() {
      return exactPhysicalFieldSetBytes.clone();
    }
  }

  void verifyExistingPartitionProof(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.Readback readback) {
    Objects.requireNonNull(readback, "readback");
    CanonicalGameplayIssuerBootstrapPartitionWork work = readback.work();
    ProofInput input = proofInput(readback);
    requireNoAmbientTransaction();
    dsl.transaction(
        configuration -> {
          DSLContext transaction = org.jooq.impl.DSL.using(configuration);
          CanonicalGameplayIssuerPartitionBuildPlan currentPlan =
              new CanonicalGameplayIssuerPartitionBuildRepository(transaction)
                  .readPreparedPlanInTransaction(
                      transaction,
                      work.buildPlan().bootstrapIntent().operationId(),
                      work.buildPlan().bootstrapIntent().requestId());
          requireSamePlan(currentPlan, work.buildPlan());
          requireProofContext(input, currentPlan);
          Record row =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + PROOF
                      + " WHERE bootstrap_operation_id = ? AND partition_id = ?",
                  input.bootstrapOperationId(),
                  input.partitionId());
          if (row == null) throw conflict("Installed anchor is missing an exact partition proof");
          requireSameProof(row, input);
        });
  }

  CanonicalGameplayIssuerInstalledAnchor installInitialAnchor(
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState) {
    Objects.requireNonNull(bootstrapOperationId, "bootstrapOperationId");
    Objects.requireNonNull(bootstrapRequestId, "bootstrapRequestId");
    Objects.requireNonNull(currentState, "currentState");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = org.jooq.impl.DSL.using(configuration);
          CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          CanonicalGameplayIssuerPartitionBuildRepository buildRepository =
              new CanonicalGameplayIssuerPartitionBuildRepository(transaction);
          Record anchorProbe =
              transaction.fetchOne(
                  "SELECT issuer_id FROM " + ANCHOR + " WHERE issuer_id = ?", ISSUER);
          CanonicalGameplayIssuerPartitionBuildPlan plan =
              anchorProbe == null
                  ? buildRepository.readPreparedPlanInTransaction(
                      transaction, bootstrapOperationId, bootstrapRequestId)
                  : buildRepository.readInstalledPlanForAnchorInTransaction(
                      transaction, bootstrapOperationId, bootstrapRequestId);
          CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
          requireCurrentState(currentState, intent);
          Record existing =
              transaction.fetchOne(
                  "SELECT * FROM " + ANCHOR + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
          if (existing != null) {
            CanonicalGameplayIssuerInstalledAnchor installed = loadAnchor(existing);
            requireAnchorMatchesPlan(installed, plan, currentState);
            requireProofSummaryMatchesAnchor(
                verifyCompleteProof(transaction, plan, installed.incarnationBytes()), installed);
            requireCounterCoversAnchor(transaction, installed);
            return installed;
          }

          ProofSummary proof =
              verifyCompleteProof(
                  transaction,
                  plan,
                  CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(
                      currentState.incarnation()));
          BigInteger generation = allocateGlobalCoverageGeneration(transaction);
          CanonicalGameplayIssuerInstalledAnchor anchor =
              createAnchor(plan, currentState, generation, proof);
          int inserted =
              transaction.execute(
                  "INSERT INTO "
                      + ANCHOR
                      + " ("
                      + "issuer_id, bootstrap_operation_id, bootstrap_request_id, build_operation_id, "
                      + "build_plan_digest, control_epoch, issuer_coverage_operation_id, operation_fence, "
                      + "coverage_fence, issuer_inventory_snapshot_revision, layout_version, partition_count, "
                      + "partition_capacity, mapping_identity, layout_descriptor_bytes, layout_descriptor_digest, "
                      + "issuer_auth_generation, source_checkpoint_bytes, source_checkpoint_digest, "
                      + "source_event_bytes, source_event_digest, incarnation_bytes, incarnation_digest, "
                      + "global_coverage_generation, partition_proof_count, physical_reservation_count, "
                      + "physical_binding_count, aggregate_counted_capacity, partition_proof_root_digest, "
                      + "exact_anchor_bytes, exact_anchor_digest, anchor_state) "
                      + "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INITIAL_INSTALLED' "
                      + "FROM "
                      + CONTROL
                      + " c WHERE c.issuer_id = ? AND c.state = 'INITIAL_BOOTSTRAP_INTENT' "
                      + "AND c.control_epoch = ? AND c.current_operation_id = ? "
                      + "AND c.current_operation_fence = ? AND c.current_coverage_fence = ? "
                      + "AND NOT EXISTS (SELECT 1 FROM "
                      + ANCHOR
                      + " a WHERE a.issuer_id = c.issuer_id)",
                  anchor.issuerId(),
                  anchor.bootstrapOperationId(),
                  anchor.bootstrapRequestId(),
                  plan.buildOperationId(),
                  plan.planDigest(),
                  anchor.controlEpoch(),
                  anchor.issuerCoverageOperationId(),
                  anchor.operationFence(),
                  anchor.coverageFence(),
                  anchor.issuerInventorySnapshotRevision(),
                  anchor.layoutVersion(),
                  anchor.partitionCount(),
                  anchor.partitionCapacity(),
                  anchor.mappingIdentity(),
                  anchor.layoutDescriptorBytes(),
                  anchor.layoutDescriptorDigest(),
                  anchor.issuerAuthGeneration(),
                  anchor.sourceCheckpointBytes(),
                  anchor.sourceCheckpointDigest(),
                  anchor.sourceEventBytes(),
                  anchor.sourceEventDigest(),
                  anchor.incarnationBytes(),
                  anchor.incarnationDigest(),
                  anchor.globalCoverageGeneration(),
                  anchor.partitionProofCount(),
                  anchor.physicalReservationCount(),
                  anchor.physicalBindingCount(),
                  anchor.aggregateCountedCapacity(),
                  anchor.partitionProofRootDigest(),
                  anchor.exactAnchorBytes(),
                  anchor.exactAnchorDigest(),
                  ISSUER,
                  intent.controlEpoch(),
                  intent.operationId(),
                  intent.operationFence(),
                  intent.coverageFence());
          if (inserted != 1) throw conflict("Exact initial issuer anchor compare-and-set refused");
          Record saved =
              transaction.fetchOne("SELECT * FROM " + ANCHOR + " WHERE issuer_id = ?", ISSUER);
          if (saved == null) throw conflict("Installed issuer anchor is unreadable after its CAS");
          CanonicalGameplayIssuerInstalledAnchor result = loadAnchor(saved);
          requireSameAnchor(result, anchor);
          return result;
        });
  }

  private CanonicalGameplayIssuerInstalledAnchor createAnchor(
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState,
      BigInteger generation,
      ProofSummary proof) {
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layout =
        intent.layoutDescriptor();
    CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint source =
        intent.sourceCheckpoint();
    byte[] incarnationBytes =
        CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(currentState.incarnation());
    return new CanonicalGameplayIssuerInstalledAnchor(
        intent.operationId(),
        intent.requestId(),
        plan.buildOperationId(),
        plan.planDigest(),
        intent.issuerId(),
        intent.controlEpoch(),
        intent.operationId(),
        intent.operationFence(),
        intent.coverageFence(),
        intent.inventorySnapshotRevision(),
        layout.layoutVersion(),
        layout.partitionCount(),
        layout.partitionCapacity(),
        layout.mappingIdentity(),
        layout.exactDescriptorBytes(),
        layout.descriptorDigest(),
        source.issuerAuthGeneration(),
        source.exactCheckpointBytes(),
        source.checkpointDigest(),
        source.latestIssuerEventBytes(),
        source.latestIssuerEventDigest(),
        incarnationBytes,
        CanonicalGameplayIssuerMemberV2Codec.sha256(incarnationBytes),
        generation,
        proof.partitionCount(),
        proof.reservationCount(),
        proof.bindingCount(),
        proof.aggregateCountedCapacity(),
        proof.rootDigest(),
        null,
        null);
  }

  private static ProofSummary verifyCompleteProof(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      byte[] expectedIncarnationBytes) {
    if (plan.state() != CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED) {
      throw conflict("Only a complete unblocked issuer plan can install an anchor");
    }
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    List<Record> proofRows =
        transaction.fetch(
            "SELECT * FROM " + PROOF + " WHERE bootstrap_operation_id = ? ORDER BY partition_id",
            intent.operationId());
    BigInteger partitionCount = intent.layoutDescriptor().partitionCount();
    if (!BigInteger.valueOf(proofRows.size()).equals(partitionCount)) {
      throw conflict(
          "Initial issuer anchor requires one positive physical proof per finite partition");
    }

    Map<Long, CanonicalGameplayIssuerPartitionBuildPlan.MemberWork> worksByOrdinal =
        new HashMap<>();
    Map<UUID, byte[]> exactOperationValues = new HashMap<>();
    Map<BigInteger, Integer> expectedReservationsByPartition = new HashMap<>();
    Map<BigInteger, Integer> expectedCapacityByPartition = new HashMap<>();
    int expectedCapacityCount = 0;
    Map<BigInteger, Integer> expectedBindingsByPartition = new HashMap<>();
    for (CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work : plan.memberWorks()) {
      worksByOrdinal.put(work.workOrdinal(), work);
      expectedReservationsByPartition.merge(work.plannedPartitionId(), 1, Integer::sum);
      if (work.countsTowardCapacity()) {
        expectedCapacityCount++;
        expectedCapacityByPartition.merge(work.plannedPartitionId(), 1, Integer::sum);
      }
    }
    List<Record> operations =
        transaction.fetch(
            "SELECT * FROM "
                + OPERATION
                + " WHERE bootstrap_operation_id = ? ORDER BY work_ordinal",
            intent.operationId());
    if (operations.size() != plan.memberWorks().size()) {
      throw conflict(
          "Finite coverage cannot complete while any SQL member operation is unresolved");
    }
    for (Record operation : operations) {
      long ordinal = requireNonNegativeLong(operation, "work_ordinal");
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work = worksByOrdinal.get(ordinal);
      if (work == null) throw conflict("SQL member operation is outside the exact persisted plan");
      byte[] bytes = requireBytes(operation, "member_value_bytes");
      String digest = requireText(operation, "member_value_digest");
      if (!digest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(bytes))) {
        throw conflict("SQL member operation value digest is not exact");
      }
      CanonicalGameplayIssuerMemberV2Value value;
      try {
        value = CanonicalGameplayIssuerMemberV2Codec.decode(bytes);
      } catch (RuntimeException malformed) {
        throw conflict("SQL member operation is not a closed schema-v2 value");
      }
      requireOperationMatches(operation, plan, work, value);
      if (exactOperationValues.putIfAbsent(work.reservationId(), bytes) != null) {
        throw conflict("Finite SQL work contains a duplicate reservation identity");
      }
      if (Boolean.TRUE.equals(operation.get("expected_binding_present", Boolean.class)))
        expectedBindingsByPartition.merge(work.plannedPartitionId(), 1, Integer::sum);
    }

    BigInteger expectedPartition = BigInteger.ZERO;
    BigInteger reservationCount = BigInteger.ZERO;
    BigInteger bindingCount = BigInteger.ZERO;
    BigInteger aggregateCountedCapacity = BigInteger.ZERO;
    BigInteger expectedAggregateCapacity = BigInteger.valueOf(expectedCapacityCount);
    BigInteger aggregateBound =
        partitionCount.multiply(intent.layoutDescriptor().partitionCapacity());
    MessageDigest root = sha256Digest();
    for (Record row : proofRows) {
      BigInteger partitionId = readInteger(row, "partition_id");
      if (!partitionId.equals(expectedPartition)) {
        throw conflict(
            "Initial issuer physical proof set is not the exact contiguous finite range");
      }
      requireProofMatchesPlan(row, plan, expectedIncarnationBytes, partitionId);
      int expectedPartitionReservations =
          expectedReservationsByPartition.getOrDefault(partitionId, 0);
      int expectedPartitionCapacity = expectedCapacityByPartition.getOrDefault(partitionId, 0);
      int expectedPartitionBindings = expectedBindingsByPartition.getOrDefault(partitionId, 0);
      Map<String, byte[]> fields =
          decodeFieldSet(requireBytes(row, "exact_physical_field_set_bytes"));
      UUID initialRevision = requireUuid(row, "initial_physical_mutation_revision");
      if (initialRevision.equals(new UUID(0L, 0L))) {
        throw conflict("Initial issuer physical proof has a nil mutation revision");
      }
      Record mutationHead =
          transaction.fetchOne(
              "SELECT * FROM "
                  + CanonicalGameplayIssuerPartitionMutationRepository.HEAD_TABLE
                  + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ?",
              ISSUER,
              intent.operationId(),
              partitionId);
      if (mutationHead == null
          || !intent.operationId().equals(mutationHead.get("bootstrap_operation_id", UUID.class))
          || !initialRevision.equals(mutationHead.get("initial_revision", UUID.class))
          || !"CURRENT".equals(mutationHead.get("state", String.class))) {
        throw conflict("Initial issuer proof does not match its current SQL mutation head");
      }
      CanonicalGameplayIssuerPartitionMutationRepository.requireRevisionField(
          fields, initialRevision);
      byte[] metadata = fields.get(METADATA_FIELD);
      if (metadata == null || !Arrays.equals(metadata, requireBytes(row, "exact_metadata_bytes"))) {
        throw conflict("Partition proof does not retain its exact common physical metadata");
      }
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.requireExactMetadata(
          metadata, metadata);
      Set<UUID> reservationIds = new HashSet<>();
      int physicalBindings = 0;
      int physicalCapacity = 0;
      int physicalReservations = 0;
      Set<String> expectedFieldNames = new HashSet<>();
      expectedFieldNames.add(METADATA_FIELD);
      expectedFieldNames.add(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD);
      for (Map.Entry<String, byte[]> field : fields.entrySet()) {
        String fieldName = field.getKey();
        if (fieldName.equals(METADATA_FIELD)
            || fieldName.equals(
                CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD)) {
          continue;
        }
        CanonicalGameplayIssuerMemberV2Value value;
        try {
          value = CanonicalGameplayIssuerMemberV2Codec.decode(field.getValue());
        } catch (RuntimeException malformed) {
          throw conflict("Exact physical proof contains a malformed v2 member value");
        }
        BigInteger mapped = value.owner().bindingRef().partitionId(partitionCount);
        if (!mapped.equals(partitionId)) {
          throw conflict("Physical issuer field is stored outside its canonical partition");
        }
        byte[] expectedValue = exactOperationValues.get(value.reservation().reservationId());
        if (expectedValue == null || !Arrays.equals(expectedValue, field.getValue())) {
          throw conflict("Physical issuer field differs from its exact SQL member operation");
        }
        if (fieldName.startsWith(CAPACITY_PREFIX)) {
          String reservationText = fieldName.substring(CAPACITY_PREFIX.length());
          UUID reservationId;
          try {
            reservationId = UUID.fromString(reservationText);
          } catch (RuntimeException malformed) {
            throw conflict("Physical issuer capacity field has malformed identity");
          }
          if (!reservationId.toString().equals(reservationText)
              || !reservationId.equals(value.reservation().reservationId())
              || !reservationIds.add(reservationId)) {
            throw conflict("Physical issuer capacity field does not match a unique reservation");
          }
          physicalReservations++;
          if (value.reservation().lifecycle()
              != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED) {
            physicalCapacity++;
          }
        } else {
          CanonicalGameplayBindingRef bindingRef;
          try {
            bindingRef =
                CanonicalGameplayBindingRef.fromExactBytes(
                    fieldName.getBytes(StandardCharsets.UTF_8));
          } catch (RuntimeException malformed) {
            throw conflict("Physical issuer binding field has invalid canonical identity");
          }
          if (!Arrays.equals(bindingRef.bytes(), value.owner().bindingRef().bytes())) {
            throw conflict("Physical issuer binding field and v2 owner identity disagree");
          }
          expectedFieldNames.add(fieldName);
          physicalBindings++;
        }
      }
      expectedFieldNames.addAll(
          reservationIds.stream()
              .map(CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec::reservationField)
              .toList());
      if (!expectedFieldNames.equals(fields.keySet())
          || physicalReservations != expectedPartitionReservations
          || physicalCapacity != expectedPartitionCapacity
          || physicalBindings != expectedPartitionBindings
          || physicalReservations != readInt(row, "physical_reservation_count")
          || physicalBindings != readInt(row, "physical_binding_count")
          || physicalCapacity != readInt(row, "physical_counted_capacity_count")
          || fields.size() != readInt(row, "physical_field_count")
          || !expectedAggregateCapacity.equals(
              readInteger(row, "planned_aggregate_capacity_count"))) {
        throw conflict("Physical partition proof differs from SQL work or capacity expectations");
      }
      if (BigInteger.valueOf(physicalCapacity)
              .compareTo(intent.layoutDescriptor().partitionCapacity())
          > 0) {
        throw conflict("Physical issuer partition exceeds its pinned reservation capacity");
      }
      reservationCount = reservationCount.add(BigInteger.valueOf(physicalReservations));
      bindingCount = bindingCount.add(BigInteger.valueOf(physicalBindings));
      aggregateCountedCapacity = aggregateCountedCapacity.add(BigInteger.valueOf(physicalCapacity));
      updateRoot(root, partitionId.toString().getBytes(StandardCharsets.US_ASCII));
      updateRoot(
          root,
          CanonicalGameplayIssuerMemberV2Codec.sha256(
                  requireBytes(row, "exact_physical_field_set_bytes"))
              .getBytes(StandardCharsets.US_ASCII));
      expectedPartition = expectedPartition.add(BigInteger.ONE);
    }
    if (!expectedPartition.equals(partitionCount)
        || !reservationCount.equals(BigInteger.valueOf(plan.memberWorks().size()))
        || !bindingCount.equals(
            BigInteger.valueOf(
                expectedBindingsByPartition.values().stream().mapToInt(Integer::intValue).sum()))
        || !aggregateCountedCapacity.equals(expectedAggregateCapacity)
        || aggregateCountedCapacity.compareTo(aggregateBound) > 0) {
      throw conflict(
          "Initial issuer proof does not cover complete reservation and aggregate capacity");
    }
    return new ProofSummary(
        proofRows.size(),
        reservationCount.longValueExact(),
        bindingCount.longValueExact(),
        aggregateCountedCapacity,
        "sha256:" + java.util.HexFormat.of().formatHex(root.digest()));
  }

  private static void requireOperationMatches(
      Record row,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work,
      CanonicalGameplayIssuerMemberV2Value value) {
    var intent = plan.bootstrapIntent();
    var reservation = value.reservation();
    var anchor = value.anchor();
    var operation = value.memberOperation();
    if (!requireUuid(row, "bootstrap_operation_id").equals(intent.operationId())
        || !requireUuid(row, "bootstrap_request_id").equals(intent.requestId())
        || !requireUuid(row, "build_operation_id").equals(plan.buildOperationId())
        || !plan.planDigest().equals(requireText(row, "build_plan_digest"))
        || work.workOrdinal() != requireNonNegativeLong(row, "work_ordinal")
        || !requireUuid(row, "preparation_operation_id").equals(work.memberOperationId())
        || !requireUuid(row, "preparation_fence_id").equals(work.memberPreparationFenceId())
        || work.reservationSourceRowOrdinal()
            != requireNonNegativeLong(row, "reservation_source_row_ordinal")
        || !work.reservationLifecycle().equals(requireText(row, "reservation_lifecycle"))
        || !Objects.equals(
            work.bindingSourceRowOrdinal(), row.get("binding_source_row_ordinal", Long.class))
        || !Objects.equals(
            work.bindingSourceRowDigest(), row.get("binding_source_row_digest", String.class))
        || !"PENDING_PHYSICAL_PROOF".equals(requireText(row, "member_status"))
        || readInt(row, "member_schema_version") != 2
        || !operation.operationId().equals(requireUuid(row, "member_operation_id"))
        || !operation.operationFence().equals(readInteger(row, "operation_fence"))
        || !operation.coverageGeneration().equals(readInteger(row, "local_member_generation"))
        || !reservation.reservationId().equals(work.reservationId())
        || !reservation.transitionId().equals(work.transitionId())
        || !reservation.partitionId().equals(work.plannedPartitionId())
        || !reservation.issuerCoverageOperationId().equals(work.originalIssuerCoverageOperationId())
        || !reservation
            .issuerCoverageOperationFence()
            .equals(work.originalIssuerCoverageOperationFence())
        || !reservation.coverageFence().equals(work.originalCoverageFence())
        || !reservation.inventorySnapshotRevision().equals(work.originalInventorySnapshotRevision())
        || !work.reservationLifecycle().equals(reservation.lifecycle().name())
        || !work.reservationFence().equals(reservation.reservationFence())
        || !anchor.issuerCoverageOperationId().equals(intent.operationId())
        || !anchor.operationFence().equals(intent.operationFence())
        || !anchor.coverageFence().equals(intent.coverageFence())
        || !anchor.issuerInventorySnapshotRevision().equals(intent.inventorySnapshotRevision())
        || !value.issuerControlEpoch().equals(intent.controlEpoch())
        || !Arrays.equals(
            value.layoutDescriptor().exactDescriptorBytes(),
            intent.layoutDescriptor().exactDescriptorBytes())
        || !Arrays.equals(work.bindingRef(), value.owner().bindingRef().bytes())
        || !Boolean.valueOf(work.countsTowardCapacity())
            .equals(row.get("counts_toward_capacity", Boolean.class))
        || !Boolean.valueOf(isBindingPresent(row))
            .equals(row.get("expected_binding_present", Boolean.class))
        || !work.reservationId().equals(requireUuid(row, "reservation_id"))
        || !work.reservationSourceRowDigest()
            .equals(requireText(row, "reservation_source_row_digest"))
        || !work.originalIssuerCoverageOperationId()
            .equals(requireUuid(row, "original_issuer_coverage_operation_id"))
        || !work.originalIssuerCoverageOperationFence()
            .equals(readInteger(row, "original_issuer_coverage_operation_fence"))
        || !work.originalCoverageFence().equals(readInteger(row, "original_coverage_fence"))
        || !work.originalInventorySnapshotRevision()
            .equals(readInteger(row, "original_inventory_snapshot_revision"))
        || !intent.operationId().equals(requireUuid(row, "bootstrap_anchor_operation_id"))
        || !intent.operationFence().equals(readInteger(row, "bootstrap_anchor_operation_fence"))
        || !intent.coverageFence().equals(readInteger(row, "bootstrap_anchor_coverage_fence"))
        || !intent
            .inventorySnapshotRevision()
            .equals(readInteger(row, "bootstrap_anchor_inventory_snapshot_revision"))) {
      throw conflict(
          "SQL member operation does not match its exact reservation, anchor, and plan tuple");
    }
  }

  private static void requireProofMatchesPlan(
      Record row,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      byte[] expectedIncarnationBytes,
      BigInteger partitionId) {
    var intent = plan.bootstrapIntent();
    var layout = intent.layoutDescriptor();
    var source = intent.sourceCheckpoint();
    byte[] expectedEvent = source.latestIssuerEventBytes();
    byte[] exactIncarnation = requireBytes(row, "incarnation_bytes");
    if (!requireUuid(row, "build_operation_id").equals(plan.buildOperationId())
        || !requireUuid(row, "bootstrap_operation_id").equals(intent.operationId())
        || !requireUuid(row, "bootstrap_request_id").equals(intent.requestId())
        || !ISSUER.equals(requireText(row, "issuer_id"))
        || !plan.planDigest().equals(requireText(row, "build_plan_digest"))
        || !intent.operationFence().equals(readInteger(row, "bootstrap_operation_fence"))
        || !intent.coverageFence().equals(readInteger(row, "bootstrap_coverage_fence"))
        || !intent
            .inventorySnapshotRevision()
            .equals(readInteger(row, "issuer_inventory_snapshot_revision"))
        || !layout.layoutVersion().equals(readInteger(row, "layout_version"))
        || !layout.partitionCount().equals(readInteger(row, "partition_count"))
        || !layout.partitionCapacity().equals(readInteger(row, "partition_capacity"))
        || !layout.mappingIdentity().equals(requireText(row, "mapping_identity"))
        || !Arrays.equals(
            layout.exactDescriptorBytes(), requireBytes(row, "layout_descriptor_bytes"))
        || !layout.descriptorDigest().equals(requireText(row, "layout_descriptor_digest"))
        || !Arrays.equals(
            source.exactCheckpointBytes(), requireBytes(row, "source_checkpoint_bytes"))
        || !source.checkpointDigest().equals(requireText(row, "source_checkpoint_digest"))
        || !Arrays.equals(expectedEvent, optionalBytes(row, "source_event_bytes"))
        || !Objects.equals(
            source.latestIssuerEventDigest(), row.get("source_event_digest", String.class))
        || !Arrays.equals(expectedIncarnationBytes, exactIncarnation)
        || !CanonicalGameplayIssuerMemberV2Codec.sha256(exactIncarnation)
            .equals(requireText(row, "incarnation_digest"))
        || !partitionId.equals(readInteger(row, "partition_id"))
        || !"EXACT_PHYSICAL_READBACK".equals(requireText(row, "proof_state"))) {
      throw conflict("Persisted physical proof differs from the exact current plan tuple");
    }
  }

  private static void requireCurrentState(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent) {
    var source = intent.sourceCheckpoint();
    if (!sameCheckpoint(current.accountCheckpoint(), source)) {
      throw conflict("Current Account issuer checkpoint moved from the frozen bootstrap source");
    }
  }

  private static boolean sameCheckpoint(
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint left,
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint right) {
    return left.issuerId().equals(right.issuerId())
        && left.issuerAuthGeneration().equals(right.issuerAuthGeneration())
        && Arrays.equals(left.exactCheckpointBytes(), right.exactCheckpointBytes())
        && left.checkpointDigest().equals(right.checkpointDigest())
        && Arrays.equals(left.latestIssuerEventBytes(), right.latestIssuerEventBytes())
        && Objects.equals(left.latestIssuerEventDigest(), right.latestIssuerEventDigest());
  }

  private static void requireAnchorMatchesPlan(
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    var intent = plan.bootstrapIntent();
    var layout = intent.layoutDescriptor();
    var source = intent.sourceCheckpoint();
    byte[] currentIncarnation =
        CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(current.incarnation());
    if (!anchor.bootstrapOperationId().equals(intent.operationId())
        || !anchor.bootstrapRequestId().equals(intent.requestId())
        || !anchor.buildOperationId().equals(plan.buildOperationId())
        || !anchor.buildPlanDigest().equals(plan.planDigest())
        || !anchor.issuerId().equals(intent.issuerId())
        || !anchor.controlEpoch().equals(intent.controlEpoch())
        || !anchor.issuerCoverageOperationId().equals(intent.operationId())
        || !anchor.operationFence().equals(intent.operationFence())
        || !anchor.coverageFence().equals(intent.coverageFence())
        || !anchor.issuerInventorySnapshotRevision().equals(intent.inventorySnapshotRevision())
        || !anchor.layoutVersion().equals(layout.layoutVersion())
        || !anchor.partitionCount().equals(layout.partitionCount())
        || !anchor.partitionCapacity().equals(layout.partitionCapacity())
        || !anchor.mappingIdentity().equals(layout.mappingIdentity())
        || !Arrays.equals(anchor.layoutDescriptorBytes(), layout.exactDescriptorBytes())
        || !anchor.layoutDescriptorDigest().equals(layout.descriptorDigest())
        || !anchor.issuerAuthGeneration().equals(source.issuerAuthGeneration())
        || !Arrays.equals(anchor.sourceCheckpointBytes(), source.exactCheckpointBytes())
        || !anchor.sourceCheckpointDigest().equals(source.checkpointDigest())
        || !Arrays.equals(anchor.sourceEventBytes(), source.latestIssuerEventBytes())
        || !Objects.equals(anchor.sourceEventDigest(), source.latestIssuerEventDigest())
        || !Arrays.equals(anchor.incarnationBytes(), currentIncarnation)
        || !sameCheckpoint(current.accountCheckpoint(), source)) {
      throw conflict(
          "Installed issuer anchor does not match the exact current source and initial layout");
    }
  }

  private static void requireProofSummaryMatchesAnchor(
      ProofSummary proof, CanonicalGameplayIssuerInstalledAnchor anchor) {
    if (!BigInteger.valueOf(proof.partitionCount()).equals(anchor.partitionCount())
        || proof.reservationCount() != anchor.physicalReservationCount()
        || proof.bindingCount() != anchor.physicalBindingCount()
        || !proof.aggregateCountedCapacity().equals(anchor.aggregateCountedCapacity())
        || !proof.rootDigest().equals(anchor.partitionProofRootDigest())) {
      throw conflict("Installed anchor does not retain the complete exact finite proof summary");
    }
  }

  private static void requireCounterCoversAnchor(
      DSLContext transaction, CanonicalGameplayIssuerInstalledAnchor anchor) {
    Record counter =
        transaction.fetchOne(
            "SELECT last_issued_coverage_generation FROM " + COUNTER + " WHERE issuer_id = ?",
            anchor.issuerId());
    if (counter == null
        || readInteger(counter, "last_issued_coverage_generation")
                .compareTo(anchor.globalCoverageGeneration())
            < 0) {
      throw conflict("Installed anchor generation is beyond the current global issuer counter");
    }
  }

  private static boolean isBindingPresent(Record row) {
    String lifecycle = row.get("binding_lifecycle", String.class);
    return CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE.name().equals(lifecycle)
        || CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL.name().equals(lifecycle);
  }

  private static void requireSameAnchor(
      CanonicalGameplayIssuerInstalledAnchor actual,
      CanonicalGameplayIssuerInstalledAnchor expected) {
    if (!Arrays.equals(actual.exactAnchorBytes(), expected.exactAnchorBytes())
        || !actual.exactAnchorDigest().equals(expected.exactAnchorDigest())) {
      throw conflict(
          "Installed issuer anchor readback differs from its exact owner-issued pointer");
    }
  }

  private static CanonicalGameplayIssuerInstalledAnchor loadAnchor(Record row) {
    try {
      return new CanonicalGameplayIssuerInstalledAnchor(
          requireUuid(row, "bootstrap_operation_id"),
          requireUuid(row, "bootstrap_request_id"),
          requireUuid(row, "build_operation_id"),
          requireText(row, "build_plan_digest"),
          requireText(row, "issuer_id"),
          readInteger(row, "control_epoch"),
          requireUuid(row, "issuer_coverage_operation_id"),
          readInteger(row, "operation_fence"),
          readInteger(row, "coverage_fence"),
          readInteger(row, "issuer_inventory_snapshot_revision"),
          readInteger(row, "layout_version"),
          readInteger(row, "partition_count"),
          readInteger(row, "partition_capacity"),
          requireText(row, "mapping_identity"),
          requireBytes(row, "layout_descriptor_bytes"),
          requireText(row, "layout_descriptor_digest"),
          readInteger(row, "issuer_auth_generation"),
          requireBytes(row, "source_checkpoint_bytes"),
          requireText(row, "source_checkpoint_digest"),
          optionalBytes(row, "source_event_bytes"),
          row.get("source_event_digest", String.class),
          requireBytes(row, "incarnation_bytes"),
          requireText(row, "incarnation_digest"),
          readInteger(row, "global_coverage_generation"),
          requireNonNegativeLong(row, "partition_proof_count"),
          requireNonNegativeLong(row, "physical_reservation_count"),
          requireNonNegativeLong(row, "physical_binding_count"),
          readInteger(row, "aggregate_counted_capacity"),
          requireText(row, "partition_proof_root_digest"),
          requireBytes(row, "exact_anchor_bytes"),
          requireText(row, "exact_anchor_digest"));
    } catch (RuntimeException malformed) {
      throw conflict("Persisted installed issuer anchor is malformed or noncanonical");
    }
  }

  private BigInteger allocateGlobalCoverageGeneration(DSLContext transaction) {
    transaction.execute(
        "INSERT INTO "
            + COUNTER
            + " (issuer_id, last_issued_coverage_generation, counter_version) "
            + "VALUES (?, 0, 0) ON CONFLICT (issuer_id) DO NOTHING",
        ISSUER);
    Record counter =
        transaction.fetchOne(
            "SELECT * FROM " + COUNTER + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (counter == null) throw conflict("Global issuer coverage generation counter is missing");
    BigInteger previous = readInteger(counter, "last_issued_coverage_generation");
    long version = requireNonNegativeLong(counter, "counter_version");
    BigInteger next = previous.add(BigInteger.ONE);
    if (next.toString().length() > 256)
      throw conflict("Global issuer coverage generation is outside its bound");
    int updated =
        transaction.execute(
            "UPDATE "
                + COUNTER
                + " SET last_issued_coverage_generation = ?, counter_version = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE issuer_id = ? AND last_issued_coverage_generation = ? AND counter_version = ?",
            next,
            Math.addExact(version, 1L),
            ISSUER,
            previous,
            version);
    if (updated != 1) throw conflict("Global issuer coverage generation compare-and-set refused");
    return next;
  }

  private static ProofInput proofInput(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.Readback readback) {
    CanonicalGameplayIssuerBootstrapPartitionWork work = readback.work();
    CanonicalGameplayIssuerPartitionBuildPlan plan = work.buildPlan();
    var intent = plan.bootstrapIntent();
    var layout = intent.layoutDescriptor();
    var source = intent.sourceCheckpoint();
    var physical = CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.expectedFields(work);
    byte[] fieldSet = encodeFieldSet(physical.fields());
    int expectedFieldCount = physical.fields().size();
    if (readback.physicalFieldCount() != expectedFieldCount
        || readback.physicalPartitionReservationCount() != physical.reservationCount()
        || readback.physicalBindingCount() != physical.bindingCount()
        || readback.physicalCountedCapacityCount() != physical.countedReservationCount()
        || !readback
            .plannedAggregateCapacityCount()
            .equals(physical.plannedAggregateCapacityCount())) {
      throw conflict(
          "Physical Redis readback capability differs from its exact prepared field set");
    }
    return new ProofInput(
        plan.buildOperationId(),
        intent.operationId(),
        intent.requestId(),
        plan.planDigest(),
        intent.operationFence(),
        intent.coverageFence(),
        intent.inventorySnapshotRevision(),
        layout.layoutVersion(),
        layout.partitionCount(),
        layout.partitionCapacity(),
        layout.mappingIdentity(),
        layout.exactDescriptorBytes(),
        layout.descriptorDigest(),
        source.exactCheckpointBytes(),
        source.checkpointDigest(),
        source.latestIssuerEventBytes(),
        source.latestIssuerEventDigest(),
        CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(work.incarnation()),
        CanonicalGameplayIssuerMemberV2Codec.sha256(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(work.incarnation())),
        work.partitionId(),
        work.initialPhysicalRevision(),
        physical.metadataBytes(),
        fieldSet,
        CanonicalGameplayIssuerMemberV2Codec.sha256(fieldSet),
        expectedFieldCount,
        physical.reservationCount(),
        physical.bindingCount(),
        physical.countedReservationCount(),
        physical.plannedAggregateCapacityCount());
  }

  private static void requireProofContext(
      ProofInput proof, CanonicalGameplayIssuerPartitionBuildPlan plan) {
    var intent = plan.bootstrapIntent();
    var layout = intent.layoutDescriptor();
    var source = intent.sourceCheckpoint();
    if (!proof.buildOperationId().equals(plan.buildOperationId())
        || !proof.bootstrapOperationId().equals(intent.operationId())
        || !proof.bootstrapRequestId().equals(intent.requestId())
        || !proof.planDigest().equals(plan.planDigest())
        || !proof.operationFence().equals(intent.operationFence())
        || !proof.coverageFence().equals(intent.coverageFence())
        || !proof.snapshotRevision().equals(intent.inventorySnapshotRevision())
        || !proof.layoutVersion().equals(layout.layoutVersion())
        || !proof.partitionCount().equals(layout.partitionCount())
        || !proof.partitionCapacity().equals(layout.partitionCapacity())
        || !proof.mappingIdentity().equals(layout.mappingIdentity())
        || !Arrays.equals(proof.layoutDescriptorBytes(), layout.exactDescriptorBytes())
        || !proof.layoutDescriptorDigest().equals(layout.descriptorDigest())
        || !Arrays.equals(proof.sourceCheckpointBytes(), source.exactCheckpointBytes())
        || !proof.sourceCheckpointDigest().equals(source.checkpointDigest())
        || !Arrays.equals(proof.sourceEventBytes(), source.latestIssuerEventBytes())
        || !Objects.equals(proof.sourceEventDigest(), source.latestIssuerEventDigest())
        || proof.partitionId().compareTo(proof.partitionCount()) >= 0) {
      throw conflict("Physical readback proof belongs to a different bootstrap plan or source");
    }
  }

  private static void requireSamePlan(
      CanonicalGameplayIssuerPartitionBuildPlan current,
      CanonicalGameplayIssuerPartitionBuildPlan expected) {
    if (!current.buildOperationId().equals(expected.buildOperationId())
        || !current.planDigest().equals(expected.planDigest())
        || !current.bootstrapIntent().operationId().equals(expected.bootstrapIntent().operationId())
        || !current.bootstrapIntent().requestId().equals(expected.bootstrapIntent().requestId())
        || current.memberWorks().size() != expected.memberWorks().size()
        || current.cohortRows().size() != expected.cohortRows().size()) {
      throw conflict("Current complete bootstrap plan differs from physical work");
    }
  }

  private static void requireSameProof(Record row, ProofInput expected) {
    if (!requireUuid(row, "build_operation_id").equals(expected.buildOperationId())
        || !requireUuid(row, "bootstrap_operation_id").equals(expected.bootstrapOperationId())
        || !requireUuid(row, "bootstrap_request_id").equals(expected.bootstrapRequestId())
        || !ISSUER.equals(requireText(row, "issuer_id"))
        || !expected.planDigest().equals(requireText(row, "build_plan_digest"))
        || !expected.operationFence().equals(readInteger(row, "bootstrap_operation_fence"))
        || !expected.coverageFence().equals(readInteger(row, "bootstrap_coverage_fence"))
        || !expected
            .snapshotRevision()
            .equals(readInteger(row, "issuer_inventory_snapshot_revision"))
        || !expected.layoutVersion().equals(readInteger(row, "layout_version"))
        || !expected.partitionCount().equals(readInteger(row, "partition_count"))
        || !expected.partitionCapacity().equals(readInteger(row, "partition_capacity"))
        || !expected.mappingIdentity().equals(requireText(row, "mapping_identity"))
        || !Arrays.equals(
            expected.layoutDescriptorBytes(), requireBytes(row, "layout_descriptor_bytes"))
        || !expected.layoutDescriptorDigest().equals(requireText(row, "layout_descriptor_digest"))
        || !Arrays.equals(
            expected.sourceCheckpointBytes(), requireBytes(row, "source_checkpoint_bytes"))
        || !expected.sourceCheckpointDigest().equals(requireText(row, "source_checkpoint_digest"))
        || !Arrays.equals(expected.sourceEventBytes(), optionalBytes(row, "source_event_bytes"))
        || !Objects.equals(
            expected.sourceEventDigest(), row.get("source_event_digest", String.class))
        || !Arrays.equals(expected.incarnationBytes(), requireBytes(row, "incarnation_bytes"))
        || !expected.incarnationDigest().equals(requireText(row, "incarnation_digest"))
        || !expected.partitionId().equals(readInteger(row, "partition_id"))
        || !expected
            .initialPhysicalRevision()
            .equals(row.get("initial_physical_mutation_revision", UUID.class))
        || !Arrays.equals(expected.metadataBytes(), requireBytes(row, "exact_metadata_bytes"))
        || !Arrays.equals(
            expected.fieldSetBytes(), requireBytes(row, "exact_physical_field_set_bytes"))
        || !expected.fieldSetDigest().equals(requireText(row, "exact_physical_field_set_digest"))
        || expected.fieldCount() != readInt(row, "physical_field_count")
        || expected.reservationCount() != readInt(row, "physical_reservation_count")
        || expected.bindingCount() != readInt(row, "physical_binding_count")
        || expected.countedCapacityCount() != readInt(row, "physical_counted_capacity_count")
        || !expected
            .aggregateCountedCapacity()
            .equals(readInteger(row, "planned_aggregate_capacity_count"))
        || !"EXACT_PHYSICAL_READBACK".equals(requireText(row, "proof_state"))) {
      throw conflict("Exact partition proof retry changed its original physical bytes or tuple");
    }
  }

  static byte[] encodeFieldSet(Map<String, byte[]> fields) {
    List<Map.Entry<String, byte[]>> entries = new ArrayList<>(fields.entrySet());
    if (entries.isEmpty() || entries.size() > MAX_LOCAL_PHYSICAL_FIELD_COUNT) {
      throw conflict("Physical field proof count is outside its bound");
    }
    entries.sort(
        (left, right) ->
            compareUnsigned(
                left.getKey().getBytes(StandardCharsets.UTF_8),
                right.getKey().getBytes(StandardCharsets.UTF_8)));
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writeBytes(output, FIELDSET_MAGIC.getBytes(StandardCharsets.US_ASCII));
        output.writeInt(entries.size());
        for (Map.Entry<String, byte[]> entry : entries) {
          writeBytes(output, entry.getKey().getBytes(StandardCharsets.UTF_8));
          writeBytes(output, entry.getValue());
        }
      }
      if (bytes.size() > 17 * 1024 * 1024)
        throw conflict("Physical field proof exceeds its bounded storage limit");
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Could not encode exact issuer physical readback", impossible);
    }
  }

  static Map<String, byte[]> decodeFieldSet(byte[] encoded) {
    if (encoded.length == 0 || encoded.length > 17 * 1024 * 1024) {
      throw conflict("Persisted issuer physical field proof is outside its bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      if (!Arrays.equals(
          readBytes(input, 256), FIELDSET_MAGIC.getBytes(StandardCharsets.US_ASCII))) {
        throw conflict("Persisted issuer physical field proof has an unsupported encoding");
      }
      int count = input.readInt();
      if (count < 1 || count > MAX_LOCAL_PHYSICAL_FIELD_COUNT) {
        throw conflict("Persisted issuer physical field count is outside its bound");
      }
      Map<String, byte[]> fields = new HashMap<>();
      byte[] previous = null;
      for (int index = 0; index < count; index++) {
        byte[] rawField =
            readBytes(
                input,
                CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_BINDING_REF_BYTES);
        if (rawField.length == 0
            || (previous != null && compareUnsigned(previous, rawField) >= 0)) {
          throw conflict("Persisted issuer physical fields are duplicated or noncanonical");
        }
        String field = decodeUtf8(rawField);
        byte[] value =
            readBytes(
                input, CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_VALUE_BYTES);
        if (value.length == 0 || fields.putIfAbsent(field, value) != null) {
          throw conflict("Persisted issuer physical field is empty or duplicated");
        }
        previous = rawField;
      }
      if (input.read() != -1) throw conflict("Persisted issuer physical proof has trailing bytes");
      return fields;
    } catch (IOException malformed) {
      throw conflict("Persisted issuer physical field proof is malformed");
    }
  }

  private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static byte[] readBytes(DataInputStream input, int maxBytes) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > maxBytes)
      throw conflict("Issuer physical proof field exceeds its bound");
    byte[] value = input.readNBytes(length);
    if (value.length != length) throw conflict("Issuer physical proof field is truncated");
    return value;
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw conflict("Issuer physical field name is not canonical UTF-8");
    }
  }

  private static int compareUnsigned(byte[] left, byte[] right) {
    int common = Math.min(left.length, right.length);
    for (int index = 0; index < common; index++) {
      int difference = Byte.toUnsignedInt(left[index]) - Byte.toUnsignedInt(right[index]);
      if (difference != 0) return difference;
    }
    return Integer.compare(left.length, right.length);
  }

  private static void updateRoot(MessageDigest digest, byte[] bytes) {
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }

  private static int readInt(Record row, String field) {
    return Math.toIntExact(requireNonNegativeLong(row, field));
  }

  private static long requireNonNegativeLong(Record row, String field) {
    Number value = row.get(field, Number.class);
    if (value == null) throw conflict(field + " is missing");
    long result = value.longValue();
    if (result < 0 || !BigInteger.valueOf(result).equals(toBigInteger(value))) {
      throw conflict(field + " is outside its exact nonnegative integer domain");
    }
    return result;
  }

  private static BigInteger readInteger(Record row, String field) {
    Object value = row.get(field);
    if (!(value instanceof Number number)) throw conflict(field + " is missing or nonnumeric");
    BigInteger result = toBigInteger(number);
    if (result.signum() < 0) throw conflict(field + " must not be negative");
    return result;
  }

  private static BigInteger toBigInteger(Number value) {
    try {
      if (value instanceof BigInteger integer) return integer;
      if (value instanceof BigDecimal decimal) return decimal.toBigIntegerExact();
      return BigInteger.valueOf(value.longValue());
    } catch (ArithmeticException malformed) {
      throw conflict("Stored counter is not an exact integer");
    }
  }

  private static UUID requireUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null) throw conflict(field + " is missing");
    return value;
  }

  private static String requireText(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw conflict(field + " is missing");
    return value;
  }

  private static byte[] requireBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0) throw conflict(field + " is missing or empty");
    return value;
  }

  private static byte[] optionalBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    return value == null ? null : value;
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw conflict("Issuer bootstrap coverage owner cannot join an ambient SQL transaction");
    }
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  private record ProofInput(
      UUID buildOperationId,
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      String planDigest,
      BigInteger operationFence,
      BigInteger coverageFence,
      BigInteger snapshotRevision,
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      String mappingIdentity,
      byte[] layoutDescriptorBytes,
      String layoutDescriptorDigest,
      byte[] sourceCheckpointBytes,
      String sourceCheckpointDigest,
      byte[] sourceEventBytes,
      String sourceEventDigest,
      byte[] incarnationBytes,
      String incarnationDigest,
      BigInteger partitionId,
      UUID initialPhysicalRevision,
      byte[] metadataBytes,
      byte[] fieldSetBytes,
      String fieldSetDigest,
      int fieldCount,
      int reservationCount,
      int bindingCount,
      int countedCapacityCount,
      BigInteger aggregateCountedCapacity) {
    private ProofInput {
      Objects.requireNonNull(initialPhysicalRevision, "initialPhysicalRevision");
      layoutDescriptorBytes = layoutDescriptorBytes.clone();
      sourceCheckpointBytes = sourceCheckpointBytes.clone();
      sourceEventBytes = sourceEventBytes == null ? null : sourceEventBytes.clone();
      incarnationBytes = incarnationBytes.clone();
      metadataBytes = metadataBytes.clone();
      fieldSetBytes = fieldSetBytes.clone();
    }

    @Override
    public byte[] layoutDescriptorBytes() {
      return layoutDescriptorBytes.clone();
    }

    @Override
    public byte[] sourceCheckpointBytes() {
      return sourceCheckpointBytes.clone();
    }

    @Override
    public byte[] sourceEventBytes() {
      return sourceEventBytes == null ? null : sourceEventBytes.clone();
    }

    @Override
    public byte[] incarnationBytes() {
      return incarnationBytes.clone();
    }

    @Override
    public byte[] metadataBytes() {
      return metadataBytes.clone();
    }

    @Override
    public byte[] fieldSetBytes() {
      return fieldSetBytes.clone();
    }
  }

  private record ProofSummary(
      long partitionCount,
      long reservationCount,
      long bindingCount,
      BigInteger aggregateCountedCapacity,
      String rootDigest) {}
}
