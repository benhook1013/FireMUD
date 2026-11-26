package net.firedevops.firemud.recovery.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Typed, operation-specific records for the initial gameplay release owner. */
public final class InitialGameplayReleaseModel {
  private InitialGameplayReleaseModel() {}

  public enum Phase {
    COLLECTING("collecting"),
    READY_TO_REOPEN("ready_to_reopen"),
    AWAITING_RESUME("awaiting_resume"),
    RESUME_AUTHORIZED("resume_authorized"),
    RELEASING("releasing"),
    FINALIZED("finalized");

    private final String databaseValue;

    Phase(String databaseValue) {
      this.databaseValue = databaseValue;
    }

    public String databaseValue() {
      return databaseValue;
    }

    public static Phase fromDatabaseValue(String value) {
      for (Phase phase : values()) {
        if (phase.databaseValue.equals(value)) {
          return phase;
        }
      }
      throw new IllegalArgumentException("Unknown initial gameplay release phase");
    }
  }

  public enum TransitionVerb {
    CONTINUE_RECOVERY("continueRecovery", Phase.READY_TO_REOPEN, Phase.AWAITING_RESUME),
    RESUME("resume", Phase.AWAITING_RESUME, Phase.RESUME_AUTHORIZED);

    private final String wireValue;
    private final Phase expectedPhase;
    private final Phase resultPhase;

    TransitionVerb(String wireValue, Phase expectedPhase, Phase resultPhase) {
      this.wireValue = wireValue;
      this.expectedPhase = expectedPhase;
      this.resultPhase = resultPhase;
    }

    public String wireValue() {
      return wireValue;
    }

    public Phase expectedPhase() {
      return expectedPhase;
    }

    public Phase resultPhase() {
      return resultPhase;
    }
  }

  public enum ProofDisposition {
    VERIFIED,
    FAILED,
    UNAVAILABLE
  }

  public enum WriterDisposition {
    STOPPED,
    FENCED,
    ACTIVE,
    UNKNOWN
  }

  public enum OwnerService {
    WORLD_MANAGEMENT("world-management-service"),
    ENTITY_MANAGEMENT("entity-management-service");

    private final String serviceName;

    OwnerService(String serviceName) {
      this.serviceName = serviceName;
    }

    public String serviceName() {
      return serviceName;
    }
  }

  public enum EffectKind {
    PREPARE_PLAYABLE_STATE_FENCED("prepare_playable_state_fenced"),
    COMMIT_OWNER_AUTHORIZED_HANDOFF("commit_owner_authorized_handoff");

    private final String databaseValue;

    EffectKind(String databaseValue) {
      this.databaseValue = databaseValue;
    }

    public String databaseValue() {
      return databaseValue;
    }

    public static EffectKind fromDatabaseValue(String value) {
      for (EffectKind kind : values()) {
        if (kind.databaseValue.equals(value)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown initial gameplay release effect kind");
    }
  }

  public enum EffectOutboxState {
    PENDING("pending"),
    DISPATCHED("dispatched"),
    RETRYABLE("retryable"),
    AMBIGUOUS("ambiguous"),
    ACKNOWLEDGED("acknowledged");

    private final String databaseValue;

    EffectOutboxState(String databaseValue) {
      this.databaseValue = databaseValue;
    }

    public String databaseValue() {
      return databaseValue;
    }

    public static EffectOutboxState fromDatabaseValue(String value) {
      for (EffectOutboxState state : values()) {
        if (state.databaseValue.equals(value)) {
          return state;
        }
      }
      throw new IllegalArgumentException("Unknown initial gameplay release outbox state");
    }
  }

  public enum EffectReadbackState {
    PREPARED_FENCED,
    OWNER_HANDOFF_APPLIED,
    PROVEN_NO_EFFECT,
    UNKNOWN_OR_PARTIAL
  }

  public enum EffectReadbackAuthority {
    GAME_SESSION_DURABLE_CURRENT_STATE,
    // Owner has durably closed this exact attempt against any delayed apply.
    GAME_SESSION_DURABLE_CLOSED_ATTEMPT
  }

  public record PeerStorageBinding(
      String targetNamespace,
      String clusterIdentity,
      String peerWorkloadIdentity,
      String storageIdentity,
      String storageRevision,
      String bindingDigest) {
    public PeerStorageBinding {
      targetNamespace = requiredNamespace(targetNamespace, "targetNamespace");
      clusterIdentity = requiredText(clusterIdentity, "clusterIdentity");
      peerWorkloadIdentity = requiredText(peerWorkloadIdentity, "peerWorkloadIdentity");
      storageIdentity = requiredText(storageIdentity, "storageIdentity");
      storageRevision = requiredText(storageRevision, "storageRevision");
      bindingDigest = requiredDigest(bindingDigest, "bindingDigest");
    }
  }

  /**
   * Caller-requested scope. The peer/storage binding is still revalidated by the configured owner
   * evidence provider; this record alone is never an authorization or a proof of provisioning.
   */
  public record Scope(
      UUID operationId,
      UUID canonicalTenantId,
      String targetNamespace,
      UUID playableStateNamespaceId,
      PeerStorageBinding peerStorageBinding,
      String evidenceRef) {
    public Scope {
      operationId = requiredUuid(operationId, "operationId");
      // This immutable identity binds the operation to a tenant; it is not authorization or
      // proof that the requested namespace belongs to that tenant.
      canonicalTenantId = requiredUuid(canonicalTenantId, "canonicalTenantId");
      targetNamespace = requiredNamespace(targetNamespace, "targetNamespace");
      playableStateNamespaceId = requiredUuid(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(peerStorageBinding, "peerStorageBinding must not be null");
      evidenceRef = requiredText(evidenceRef, "evidenceRef");
      if (!targetNamespace.equals(peerStorageBinding.targetNamespace())) {
        throw new IllegalArgumentException(
            "peer/storage binding targetNamespace must exactly match targetNamespace");
      }
    }
  }

  public record Operation(
      Scope scope,
      UUID maintenanceLockIdentity,
      Phase phase,
      long revision,
      InitialGameplayReleaseEvidence evidence,
      Instant createdAt,
      Instant updatedAt,
      Instant finalizedAt) {
    public Operation {
      Objects.requireNonNull(scope, "scope must not be null");
      maintenanceLockIdentity = requiredUuid(maintenanceLockIdentity, "maintenanceLockIdentity");
      Objects.requireNonNull(phase, "phase must not be null");
      if (revision < 1) {
        throw new IllegalArgumentException("revision must be positive");
      }
      Objects.requireNonNull(createdAt, "createdAt must not be null");
      Objects.requireNonNull(updatedAt, "updatedAt must not be null");
      if ((phase == Phase.FINALIZED) != (finalizedAt != null)) {
        throw new IllegalArgumentException("finalizedAt must exactly match the finalized phase");
      }
      if (evidence != null && phase == Phase.COLLECTING) {
        throw new IllegalArgumentException("collecting operation cannot retain readiness evidence");
      }
      if (phase != Phase.COLLECTING && evidence == null) {
        throw new IllegalArgumentException("non-collecting operation requires owner evidence");
      }
      if (evidence != null) {
        evidence.validateFor(scope);
      }
    }

    @Override
    public String toString() {
      return "Operation[scope="
          + scope
          + ", maintenanceLockIdentity=redacted, phase="
          + phase
          + ", revision="
          + revision
          + ", evidence="
          + evidence
          + ", createdAt="
          + createdAt
          + ", updatedAt="
          + updatedAt
          + ", finalizedAt="
          + finalizedAt
          + "]";
    }
  }

  public record MigrationProof(
      UUID migrationOperationId,
      ProofDisposition disposition,
      String storageIdentity,
      String inventoryRevision,
      String inventoryDigest) {
    public MigrationProof {
      migrationOperationId = requiredUuid(migrationOperationId, "migrationOperationId");
      if (disposition == null) {
        throw new IllegalArgumentException("disposition must not be null");
      }
      storageIdentity = requiredText(storageIdentity, "storageIdentity");
      inventoryRevision = requiredText(inventoryRevision, "inventoryRevision");
      inventoryDigest = requiredDigest(inventoryDigest, "inventoryDigest");
    }
  }

  public record LayoutDescriptor(
      String descriptorIdentity, String revision, String digest, List<Integer> partitionIds) {
    public LayoutDescriptor {
      descriptorIdentity = requiredText(descriptorIdentity, "descriptorIdentity");
      revision = requiredText(revision, "revision");
      digest = requiredDigest(digest, "layout digest");
      partitionIds = sortedUniquePositiveIds(partitionIds, "partitionIds", true);
    }

    public List<Integer> partitionIds() {
      return List.copyOf(partitionIds);
    }
  }

  public record PartitionAcknowledgement(
      @com.fasterxml.jackson.annotation.JsonProperty(value = "partitionId", required = true)
          int partitionId,
      String acknowledgementIdentity,
      String acknowledgedLayoutDigest,
      String readbackDigest,
      int capacity) {
    public PartitionAcknowledgement {
      if (partitionId < 0) {
        throw new IllegalArgumentException("partitionId must be non-negative");
      }
      acknowledgementIdentity = requiredText(acknowledgementIdentity, "acknowledgementIdentity");
      acknowledgedLayoutDigest =
          requiredDigest(acknowledgedLayoutDigest, "acknowledgedLayoutDigest");
      readbackDigest = requiredDigest(readbackDigest, "readbackDigest");
      if (capacity <= 0) {
        throw new IllegalArgumentException("partition capacity must be positive");
      }
    }
  }

  public record IssuerLayoutProof(
      UUID layoutOperationId,
      LayoutDescriptor descriptor,
      String accountIssuerSourceIdentity,
      long operationFence,
      UUID coverageFence,
      String inventoryRevision,
      String inventoryDigest,
      List<PartitionAcknowledgement> acknowledgements,
      Map<Integer, Integer> capacityVector) {
    public IssuerLayoutProof {
      layoutOperationId = requiredUuid(layoutOperationId, "layoutOperationId");
      Objects.requireNonNull(descriptor, "descriptor must not be null");
      accountIssuerSourceIdentity =
          requiredText(accountIssuerSourceIdentity, "accountIssuerSourceIdentity");
      if (operationFence <= 0) {
        throw new IllegalArgumentException("operationFence must be positive");
      }
      coverageFence = requiredUuid(coverageFence, "coverageFence");
      inventoryRevision = requiredText(inventoryRevision, "inventoryRevision");
      inventoryDigest = requiredDigest(inventoryDigest, "inventoryDigest");
      acknowledgements = sortedAcknowledgements(acknowledgements);
      capacityVector = positiveCapacityVector(capacityVector);
    }

    public List<PartitionAcknowledgement> acknowledgements() {
      return List.copyOf(acknowledgements);
    }
  }

  public record WriterInventory(
      String revision,
      String digest,
      String legacyWriterIdentity,
      List<String> applicableWriterIdentities) {
    public WriterInventory {
      revision = requiredText(revision, "writer inventory revision");
      digest = requiredDigest(digest, "writer inventory digest");
      legacyWriterIdentity = requiredText(legacyWriterIdentity, "legacyWriterIdentity");
      applicableWriterIdentities =
          sortedUniqueTexts(applicableWriterIdentities, "applicableWriterIdentities");
      if (applicableWriterIdentities.contains(legacyWriterIdentity)) {
        throw new IllegalArgumentException("legacy writer must be separate from fenced writers");
      }
    }

    public List<String> applicableWriterIdentities() {
      return List.copyOf(applicableWriterIdentities);
    }
  }

  public record WriterFenceAcknowledgement(
      String writerIdentity,
      WriterDisposition disposition,
      String fenceIdentity,
      String inventoryDigest) {
    public WriterFenceAcknowledgement {
      writerIdentity = requiredText(writerIdentity, "writerIdentity");
      Objects.requireNonNull(disposition, "disposition must not be null");
      fenceIdentity = requiredText(fenceIdentity, "fenceIdentity");
      inventoryDigest = requiredDigest(inventoryDigest, "writer fence inventoryDigest");
    }
  }

  public record WriterFenceProof(
      WriterInventory inventory,
      WriterDisposition legacyWriterDisposition,
      List<WriterFenceAcknowledgement> writerAcknowledgements) {
    public WriterFenceProof {
      Objects.requireNonNull(inventory, "inventory must not be null");
      Objects.requireNonNull(legacyWriterDisposition, "legacyWriterDisposition must not be null");
      writerAcknowledgements = sortedWriterAcknowledgements(writerAcknowledgements);
    }

    public List<WriterFenceAcknowledgement> writerAcknowledgements() {
      return List.copyOf(writerAcknowledgements);
    }
  }

  public record OwnerInitializationProof(
      OwnerService ownerService,
      UUID initializationOperationId,
      UUID playableStateNamespaceId,
      ProofDisposition disposition,
      String sourceIdentity,
      String resultDigest) {
    public OwnerInitializationProof {
      Objects.requireNonNull(ownerService, "ownerService must not be null");
      initializationOperationId =
          requiredUuid(initializationOperationId, "initializationOperationId");
      playableStateNamespaceId = requiredUuid(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(disposition, "disposition must not be null");
      sourceIdentity = requiredText(sourceIdentity, "sourceIdentity");
      resultDigest = requiredDigest(resultDigest, "resultDigest");
    }
  }

  public record RegionLeaseProof(
      String regionIdentity, String leaseIdentity, long executorGeneration) {
    public RegionLeaseProof {
      regionIdentity = requiredText(regionIdentity, "regionIdentity");
      leaseIdentity = requiredText(leaseIdentity, "leaseIdentity");
      if (executorGeneration <= 0) {
        throw new IllegalArgumentException("executorGeneration must be positive");
      }
    }
  }

  /** Immutable, owner-resolved proof snapshot; command DTOs cannot supply or replace this value. */
  public record InitialGameplayReleaseEvidence(
      String evidenceRef,
      String evidenceIdentity,
      MigrationProof migration,
      IssuerLayoutProof issuerLayout,
      WriterFenceProof writerFencing,
      OwnerInitializationProof worldInitialization,
      OwnerInitializationProof entityInitialization,
      RegionLeaseProof regionLease) {
    public InitialGameplayReleaseEvidence {
      evidenceRef = requiredText(evidenceRef, "evidenceRef");
      evidenceIdentity = requiredText(evidenceIdentity, "evidenceIdentity");
      Objects.requireNonNull(migration, "migration must not be null");
      Objects.requireNonNull(issuerLayout, "issuerLayout must not be null");
      Objects.requireNonNull(writerFencing, "writerFencing must not be null");
      Objects.requireNonNull(worldInitialization, "worldInitialization must not be null");
      Objects.requireNonNull(entityInitialization, "entityInitialization must not be null");
      Objects.requireNonNull(regionLease, "regionLease must not be null");
    }

    public void validateFor(Scope scope) {
      Objects.requireNonNull(scope, "scope must not be null");
      requireExact(scope.evidenceRef(), evidenceRef, "evidenceRef");
      requireExact(
          scope.peerStorageBinding().storageIdentity(),
          migration.storageIdentity(),
          "storageIdentity");
      if (migration.disposition() != ProofDisposition.VERIFIED) {
        throw new IllegalArgumentException("storage migration is not owner-verified");
      }
      requireExact(
          migration.inventoryRevision(),
          issuerLayout.inventoryRevision(),
          "issuer inventoryRevision");
      requireExact(
          migration.inventoryDigest(), issuerLayout.inventoryDigest(), "issuer inventoryDigest");
      requireExact(
          migration.inventoryRevision(),
          writerFencing.inventory().revision(),
          "writer inventory revision");
      requireExact(
          migration.inventoryDigest(),
          writerFencing.inventory().digest(),
          "writer inventory digest");
      validateIssuerLayout(issuerLayout);
      validateWriterFencing(writerFencing);
      validateInitialization(worldInitialization, OwnerService.WORLD_MANAGEMENT, scope);
      validateInitialization(entityInitialization, OwnerService.ENTITY_MANAGEMENT, scope);
      if (regionLease.leaseIdentity().isBlank()) {
        throw new IllegalArgumentException("current region lease identity is required");
      }
    }

    private static void validateIssuerLayout(IssuerLayoutProof proof) {
      if (!proof
          .descriptor()
          .partitionIds()
          .equals(
              proof.acknowledgements().stream()
                  .map(PartitionAcknowledgement::partitionId)
                  .toList())) {
        throw new IllegalArgumentException(
            "issuer layout lacks an exact partition acknowledgement set");
      }
      if (!proof
          .descriptor()
          .partitionIds()
          .equals(new ArrayList<>(new TreeMap<>(proof.capacityVector()).keySet()))) {
        throw new IllegalArgumentException("issuer layout capacity vector is incomplete");
      }
      for (PartitionAcknowledgement acknowledgement : proof.acknowledgements()) {
        requireExact(
            proof.descriptor().digest(),
            acknowledgement.acknowledgedLayoutDigest(),
            "partition acknowledged layout digest");
        requireExact(
            proof.capacityVector().get(acknowledgement.partitionId()),
            acknowledgement.capacity(),
            "partition capacity");
      }
    }

    private static void validateWriterFencing(WriterFenceProof proof) {
      if (proof.legacyWriterDisposition() != WriterDisposition.STOPPED) {
        throw new IllegalArgumentException("legacy writer is not proven stopped");
      }
      List<String> expected = proof.inventory().applicableWriterIdentities();
      List<String> actual =
          proof.writerAcknowledgements().stream()
              .map(WriterFenceAcknowledgement::writerIdentity)
              .toList();
      if (!expected.equals(actual)) {
        throw new IllegalArgumentException(
            "writer fencing acknowledgements do not cover the inventory");
      }
      for (WriterFenceAcknowledgement acknowledgement : proof.writerAcknowledgements()) {
        if (acknowledgement.disposition() != WriterDisposition.FENCED) {
          throw new IllegalArgumentException("an applicable writer is not proven fenced");
        }
        requireExact(
            proof.inventory().digest(),
            acknowledgement.inventoryDigest(),
            "writer inventory digest");
      }
    }

    private static void validateInitialization(
        OwnerInitializationProof proof, OwnerService expectedOwner, Scope scope) {
      if (proof.ownerService() != expectedOwner
          || proof.disposition() != ProofDisposition.VERIFIED) {
        throw new IllegalArgumentException(
            "initialization proof has the wrong owner or is not verified");
      }
      if (!scope.playableStateNamespaceId().equals(proof.playableStateNamespaceId())) {
        throw new IllegalArgumentException(
            "initialization proof uses another playable-state namespace");
      }
    }
  }

  public record PublicTransitionTuple(
      UUID operationId,
      String scopeDigest,
      TransitionVerb verb,
      Phase expectedPhase,
      UUID maintenanceLockIdentity,
      String evidenceRef,
      String evidenceIdentity,
      String evidenceDigest) {
    public PublicTransitionTuple {
      operationId = requiredUuid(operationId, "operationId");
      scopeDigest = requiredDigest(scopeDigest, "scopeDigest");
      Objects.requireNonNull(verb, "verb must not be null");
      Objects.requireNonNull(expectedPhase, "expectedPhase must not be null");
      maintenanceLockIdentity = requiredUuid(maintenanceLockIdentity, "maintenanceLockIdentity");
      evidenceRef = requiredText(evidenceRef, "evidenceRef");
      evidenceIdentity = requiredText(evidenceIdentity, "evidenceIdentity");
      evidenceDigest = requiredDigest(evidenceDigest, "evidenceDigest");
      if (verb.expectedPhase() != expectedPhase) {
        throw new IllegalArgumentException("public transition uses an invalid expected phase");
      }
    }

    @Override
    public String toString() {
      return "PublicTransitionTuple[operationId="
          + operationId
          + ", scopeDigest="
          + scopeDigest
          + ", verb="
          + verb
          + ", expectedPhase="
          + expectedPhase
          + ", maintenanceLockIdentity=redacted, evidenceRef="
          + evidenceRef
          + ", evidenceIdentity="
          + evidenceIdentity
          + ", evidenceDigest="
          + evidenceDigest
          + "]";
    }
  }

  public sealed interface TypedEffect
      permits PreparePlayableStateFencedEffect, CommitOwnerAuthorizedHandoffEffect {
    UUID operationId();

    UUID effectId();

    UUID maintenanceLockIdentity();

    Scope scope();

    String operationTupleDigest();

    String evidenceIdentity();

    EffectKind kind();
  }

  public record PreparePlayableStateFencedEffect(
      UUID operationId,
      UUID effectId,
      UUID maintenanceLockIdentity,
      Scope scope,
      String operationTupleDigest,
      String evidenceIdentity)
      implements TypedEffect {
    public PreparePlayableStateFencedEffect {
      operationId = requiredUuid(operationId, "operationId");
      effectId = requiredUuid(effectId, "effectId");
      maintenanceLockIdentity = requiredUuid(maintenanceLockIdentity, "maintenanceLockIdentity");
      Objects.requireNonNull(scope, "scope must not be null");
      operationTupleDigest = requiredDigest(operationTupleDigest, "operationTupleDigest");
      evidenceIdentity = requiredText(evidenceIdentity, "evidenceIdentity");
      if (!operationId.equals(scope.operationId())) {
        throw new IllegalArgumentException("effect operationId must match its immutable scope");
      }
    }

    @Override
    public EffectKind kind() {
      return EffectKind.PREPARE_PLAYABLE_STATE_FENCED;
    }

    @Override
    public String toString() {
      return "PreparePlayableStateFencedEffect[operationId="
          + operationId
          + ", effectId="
          + effectId
          + ", maintenanceLockIdentity=redacted, scope="
          + scope
          + ", operationTupleDigest="
          + operationTupleDigest
          + ", evidenceIdentity="
          + evidenceIdentity
          + "]";
    }
  }

  public record CommitOwnerAuthorizedHandoffEffect(
      UUID operationId,
      UUID effectId,
      UUID ownerHandoffIdentity,
      UUID maintenanceLockIdentity,
      Scope scope,
      String operationTupleDigest,
      String evidenceIdentity)
      implements TypedEffect {
    public CommitOwnerAuthorizedHandoffEffect {
      operationId = requiredUuid(operationId, "operationId");
      effectId = requiredUuid(effectId, "effectId");
      ownerHandoffIdentity = requiredUuid(ownerHandoffIdentity, "ownerHandoffIdentity");
      maintenanceLockIdentity = requiredUuid(maintenanceLockIdentity, "maintenanceLockIdentity");
      Objects.requireNonNull(scope, "scope must not be null");
      operationTupleDigest = requiredDigest(operationTupleDigest, "operationTupleDigest");
      evidenceIdentity = requiredText(evidenceIdentity, "evidenceIdentity");
      if (!operationId.equals(scope.operationId())) {
        throw new IllegalArgumentException("effect operationId must match its immutable scope");
      }
    }

    @Override
    public EffectKind kind() {
      return EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF;
    }

    @Override
    public String toString() {
      return "CommitOwnerAuthorizedHandoffEffect[operationId="
          + operationId
          + ", effectId="
          + effectId
          + ", ownerHandoffIdentity="
          + ownerHandoffIdentity
          + ", maintenanceLockIdentity=redacted, scope="
          + scope
          + ", operationTupleDigest="
          + operationTupleDigest
          + ", evidenceIdentity="
          + evidenceIdentity
          + "]";
    }
  }

  public record EffectReadback(
      UUID operationId,
      UUID effectId,
      UUID attemptId,
      int attemptOrdinal,
      EffectKind effectKind,
      String operationTupleDigest,
      String payloadDigest,
      EffectReadbackState state,
      EffectReadbackAuthority authority,
      String readbackIdentity,
      Instant observedAt) {
    public EffectReadback {
      attemptId = requiredUuid(attemptId, "attemptId");
      operationId = requiredUuid(operationId, "operationId");
      effectId = requiredUuid(effectId, "effectId");
      if (attemptOrdinal < 1) {
        throw new IllegalArgumentException("attemptOrdinal must be positive");
      }
      Objects.requireNonNull(effectKind, "effectKind must not be null");
      operationTupleDigest = requiredDigest(operationTupleDigest, "operationTupleDigest");
      payloadDigest = requiredDigest(payloadDigest, "payloadDigest");
      Objects.requireNonNull(state, "state must not be null");
      Objects.requireNonNull(authority, "authority must not be null");
      if ((state == EffectReadbackState.PROVEN_NO_EFFECT)
          != (authority == EffectReadbackAuthority.GAME_SESSION_DURABLE_CLOSED_ATTEMPT)) {
        throw new IllegalArgumentException(
            "No-effect proof requires durable closure of the exact owner attempt");
      }
      readbackIdentity = requiredText(readbackIdentity, "readbackIdentity");
      Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
  }

  public record OutboxEntry(
      UUID outboxId,
      TypedEffect effect,
      String payloadDigest,
      EffectOutboxState state,
      int attemptCount,
      UUID lastAttemptId,
      String lastOutcomeCode,
      Instant acknowledgedAt) {
    public OutboxEntry {
      outboxId = requiredUuid(outboxId, "outboxId");
      Objects.requireNonNull(effect, "effect must not be null");
      payloadDigest = requiredDigest(payloadDigest, "payloadDigest");
      Objects.requireNonNull(state, "state must not be null");
      if (attemptCount < 0) {
        throw new IllegalArgumentException("attemptCount must not be negative");
      }
      if (lastAttemptId != null) {
        lastAttemptId = requiredUuid(lastAttemptId, "lastAttemptId");
      }
      if (state == EffectOutboxState.PENDING && (attemptCount != 0 || lastAttemptId != null)) {
        throw new IllegalArgumentException(
            "pending outbox states must not retain a dispatch attempt");
      }
      if (state != EffectOutboxState.PENDING && (attemptCount == 0 || lastAttemptId == null)) {
        throw new IllegalArgumentException("dispatched outbox states require an attempt identity");
      }
      if ((state == EffectOutboxState.ACKNOWLEDGED) != (acknowledgedAt != null)) {
        throw new IllegalArgumentException("acknowledgedAt must exactly match acknowledged state");
      }
    }
  }

  public static String requiredDigest(String value, String name) {
    String text = requiredText(value, name);
    if (!text.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hex digest");
    }
    return text;
  }

  public static UUID requiredUuid(UUID value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
      throw new IllegalArgumentException(name + " must not be the nil UUID");
    }
    return value;
  }

  private static String requiredNamespace(String value, String name) {
    if (!GrpcPeerIdentity.isValidNamespace(value)) {
      throw new IllegalArgumentException(name + " must be a valid Kubernetes namespace");
    }
    return value;
  }

  public static String requiredOutcomeCode(String value) {
    requiredText(value, "outcomeCode");
    if (!value.matches("[A-Z0-9_]{1,64}")) {
      throw new IllegalArgumentException("outcomeCode must be a bounded uppercase code");
    }
    return value;
  }

  public static String requiredText(String value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.indexOf(0) >= 0) {
      throw new IllegalArgumentException(name + " must not contain NUL");
    }
    for (int index = 0; index < value.length(); index++) {
      char unit = value.charAt(index);
      if (Character.isHighSurrogate(unit)) {
        if (++index < value.length() && Character.isLowSurrogate(value.charAt(index))) {
          continue;
        }
        throw new IllegalArgumentException(name + " must contain well-formed UTF-16");
      }
      if (Character.isLowSurrogate(unit)) {
        throw new IllegalArgumentException(name + " must contain well-formed UTF-16");
      }
    }
    return value;
  }

  private static List<Integer> sortedUniquePositiveIds(
      List<Integer> values, String name, boolean allowZero) {
    Objects.requireNonNull(values, name + " must not be null");
    if (values.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    if (values.stream().anyMatch(value -> value == null || (allowZero ? value < 0 : value <= 0))) {
      throw new IllegalArgumentException(name + " contains an invalid id");
    }
    List<Integer> sorted = values.stream().sorted().toList();
    if (sorted.stream().distinct().count() != sorted.size()) {
      throw new IllegalArgumentException(name + " contains duplicate ids");
    }
    return List.copyOf(sorted);
  }

  private static List<String> sortedUniqueTexts(List<String> values, String name) {
    Objects.requireNonNull(values, name + " must not be null");
    if (values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(name + " must not contain null elements");
    }
    List<String> sorted = values.stream().map(value -> requiredText(value, name)).sorted().toList();
    if (sorted.stream().distinct().count() != sorted.size()) {
      throw new IllegalArgumentException(name + " contains duplicate identities");
    }
    return List.copyOf(sorted);
  }

  private static List<PartitionAcknowledgement> sortedAcknowledgements(
      List<PartitionAcknowledgement> values) {
    Objects.requireNonNull(values, "acknowledgements must not be null");
    if (values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("acknowledgements must not contain null elements");
    }
    List<PartitionAcknowledgement> sorted =
        values.stream()
            .sorted(Comparator.comparingInt(PartitionAcknowledgement::partitionId))
            .toList();
    if (sorted.stream().map(PartitionAcknowledgement::partitionId).distinct().count()
        != sorted.size()) {
      throw new IllegalArgumentException("acknowledgements contain duplicate partitions");
    }
    return List.copyOf(sorted);
  }

  private static List<WriterFenceAcknowledgement> sortedWriterAcknowledgements(
      List<WriterFenceAcknowledgement> values) {
    Objects.requireNonNull(values, "writerAcknowledgements must not be null");
    if (values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("writerAcknowledgements must not contain null elements");
    }
    List<WriterFenceAcknowledgement> sorted =
        values.stream()
            .sorted(Comparator.comparing(WriterFenceAcknowledgement::writerIdentity))
            .toList();
    if (sorted.stream().map(WriterFenceAcknowledgement::writerIdentity).distinct().count()
        != sorted.size()) {
      throw new IllegalArgumentException("writer acknowledgements contain duplicate writers");
    }
    return List.copyOf(sorted);
  }

  private static Map<Integer, Integer> positiveCapacityVector(Map<Integer, Integer> values) {
    Objects.requireNonNull(values, "capacityVector must not be null");
    TreeMap<Integer, Integer> sorted = new TreeMap<>();
    values.forEach((partitionId, capacity) -> {
      if (partitionId == null || partitionId < 0 || capacity == null || capacity <= 0) {
        throw new IllegalArgumentException("capacityVector contains an invalid entry");
      }
      sorted.put(partitionId, capacity);
    });
    if (sorted.isEmpty()) {
      throw new IllegalArgumentException("capacityVector must not be empty");
    }
    return Collections.unmodifiableMap(sorted);
  }

  private static void requireExact(String expected, String actual, String name) {
    if (!Objects.equals(expected, actual)) {
      throw new IllegalArgumentException(name + " does not match the immutable operation scope");
    }
  }

  private static void requireExact(Integer expected, int actual, String name) {
    if (expected == null || expected != actual) {
      throw new IllegalArgumentException(name + " does not match the acknowledged value");
    }
  }
}
