package net.firedevops.firemud.recovery.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseTupleCodec;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.InitialGameplayReleaseEvidence;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.IssuerLayoutProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.LayoutDescriptor;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.MigrationProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OwnerInitializationProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OwnerService;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PartitionAcknowledgement;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PeerStorageBinding;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Phase;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.ProofDisposition;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.RegionLeaseProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterDisposition;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterFenceAcknowledgement;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterFenceProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterInventory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InitialGameplayReleaseModelTest {
  private static final String DIGEST = "a".repeat(64);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  @Test
  void scopeKeepsKubernetesTargetSeparateFromPlayableStateNamespace() {
    UUID playableNamespace = UUID.randomUUID();
    Scope scope = scope(playableNamespace);

    assertThat(scope.targetNamespace()).isEqualTo("release-target");
    assertThat(scope.playableStateNamespaceId()).isEqualTo(playableNamespace);
    assertThatThrownBy(
            () ->
                new Scope(
                    scope.operationId(),
                    scope.canonicalTenantId(),
                    "another-target",
                    playableNamespace,
                    scope.peerStorageBinding(),
                    scope.evidenceRef()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetNamespace");
  }

  @Test
  void scopeAndPeerBindingRequireExactKubernetesNamespaceSyntax() {
    Scope validScope = scope(UUID.randomUUID());
    for (String invalidNamespace : List.of("Release-target", "release target", "a".repeat(64))) {
      assertThatThrownBy(
              () ->
                  new PeerStorageBinding(
                      invalidNamespace,
                      "cluster-peer",
                      "peer-workload",
                      "storage-uid",
                      "storage-r7",
                      DIGEST))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("valid Kubernetes namespace");
      assertThatThrownBy(
              () ->
                  new Scope(
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      invalidNamespace,
                      UUID.randomUUID(),
                      validScope.peerStorageBinding(),
                      validScope.evidenceRef()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("valid Kubernetes namespace");
    }
  }

  @Test
  void operationProofAndLockIdentitiesRejectNilUuid() {
    Scope validScope = scope(UUID.randomUUID());
    InitialGameplayReleaseEvidence validEvidence = evidence(validScope);

    assertThatThrownBy(
            () ->
                new Scope(
                    NIL_UUID,
                    validScope.canonicalTenantId(),
                    validScope.targetNamespace(),
                    validScope.playableStateNamespaceId(),
                    validScope.peerStorageBinding(),
                    validScope.evidenceRef()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
    assertThatThrownBy(
            () ->
                new Scope(
                    UUID.randomUUID(),
                    NIL_UUID,
                    validScope.targetNamespace(),
                    validScope.playableStateNamespaceId(),
                    validScope.peerStorageBinding(),
                    validScope.evidenceRef()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
    assertThatThrownBy(
            () ->
                new Scope(
                    UUID.randomUUID(),
                    validScope.canonicalTenantId(),
                    validScope.targetNamespace(),
                    NIL_UUID,
                    validScope.peerStorageBinding(),
                    validScope.evidenceRef()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
    assertThatThrownBy(
            () ->
                new InitialGameplayReleaseModel.Operation(
                    validScope,
                    NIL_UUID,
                    Phase.COLLECTING,
                    1,
                    null,
                    java.time.Instant.EPOCH,
                    java.time.Instant.EPOCH,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
    assertThatThrownBy(
            () ->
                new MigrationProof(
                    NIL_UUID,
                    ProofDisposition.VERIFIED,
                    validScope.peerStorageBinding().storageIdentity(),
                    "inventory-r3",
                    DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
    assertThatThrownBy(() -> MaintenanceLockCredential.issue(NIL_UUID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
    assertThatThrownBy(() -> MaintenanceLockCredential.fromProtectedTransport(NIL_UUID.toString()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new IssuerLayoutProof(
                    NIL_UUID,
                    validEvidence.issuerLayout().descriptor(),
                    validEvidence.issuerLayout().accountIssuerSourceIdentity(),
                    validEvidence.issuerLayout().operationFence(),
                    UUID.randomUUID(),
                    validEvidence.issuerLayout().inventoryRevision(),
                    validEvidence.issuerLayout().inventoryDigest(),
                    validEvidence.issuerLayout().acknowledgements(),
                    validEvidence.issuerLayout().capacityVector()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
    assertThatThrownBy(
            () ->
                new IssuerLayoutProof(
                    UUID.randomUUID(),
                    validEvidence.issuerLayout().descriptor(),
                    validEvidence.issuerLayout().accountIssuerSourceIdentity(),
                    validEvidence.issuerLayout().operationFence(),
                    NIL_UUID,
                    validEvidence.issuerLayout().inventoryRevision(),
                    validEvidence.issuerLayout().inventoryDigest(),
                    validEvidence.issuerLayout().acknowledgements(),
                    validEvidence.issuerLayout().capacityVector()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
    assertThatThrownBy(
            () ->
                new OwnerInitializationProof(
                    OwnerService.WORLD_MANAGEMENT,
                    NIL_UUID,
                    validScope.playableStateNamespaceId(),
                    ProofDisposition.VERIFIED,
                    "world-source",
                    DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
    assertThatThrownBy(
            () ->
                new OwnerInitializationProof(
                    OwnerService.WORLD_MANAGEMENT,
                    UUID.randomUUID(),
                    NIL_UUID,
                    ProofDisposition.VERIFIED,
                    "world-source",
                    DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");
  }

  @Test
  void exactOwnerEvidenceRequiresCompleteLayoutWriterAndIndependentOwnerProofs() {
    Scope scope = scope(UUID.randomUUID());
    InitialGameplayReleaseEvidence evidence = evidence(scope);

    evidence.validateFor(scope);
    assertThat(evidence.issuerLayout().operationFence()).isPositive();
    assertThat(evidence.issuerLayout().coverageFence()).isNotNull();
    assertThat(evidence.worldInitialization().ownerService())
        .isEqualTo(OwnerService.WORLD_MANAGEMENT);
    assertThat(evidence.entityInitialization().ownerService())
        .isEqualTo(OwnerService.ENTITY_MANAGEMENT);
  }

  @Test
  void proofCollectionAccessorsCannotExposeMutableInternalState() {
    InitialGameplayReleaseEvidence evidence = evidence(scope(UUID.randomUUID()));

    assertThatThrownBy(() -> evidence.issuerLayout().descriptor().partitionIds().add(2))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> evidence.issuerLayout().acknowledgements().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> evidence.issuerLayout().capacityVector().put(2, 14))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> evidence.writerFencing().inventory().applicableWriterIdentities().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> evidence.writerFencing().writerAcknowledgements().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void evidenceCannotUseWorldInitializationAsEntityInitializationProof() {
    Scope scope = scope(UUID.randomUUID());
    InitialGameplayReleaseEvidence valid = evidence(scope);
    InitialGameplayReleaseEvidence copiedWorldProof =
        new InitialGameplayReleaseEvidence(
            valid.evidenceRef(),
            valid.evidenceIdentity(),
            valid.migration(),
            valid.issuerLayout(),
            valid.writerFencing(),
            valid.worldInitialization(),
            valid.worldInitialization(),
            valid.regionLease());

    assertThatThrownBy(() -> copiedWorldProof.validateFor(scope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wrong owner");
  }

  @Test
  void readinessRejectsMissingPartitionAcknowledgementAndWriterFence() {
    Scope scope = scope(UUID.randomUUID());
    InitialGameplayReleaseEvidence valid = evidence(scope);
    IssuerLayoutProof incompleteLayout =
        new IssuerLayoutProof(
            valid.issuerLayout().layoutOperationId(),
            valid.issuerLayout().descriptor(),
            valid.issuerLayout().accountIssuerSourceIdentity(),
            valid.issuerLayout().operationFence(),
            valid.issuerLayout().coverageFence(),
            valid.issuerLayout().inventoryRevision(),
            valid.issuerLayout().inventoryDigest(),
            List.of(valid.issuerLayout().acknowledgements().getFirst()),
            Map.of(0, 10, 1, 12));
    InitialGameplayReleaseEvidence missingWriterFence =
        new InitialGameplayReleaseEvidence(
            valid.evidenceRef(),
            valid.evidenceIdentity(),
            valid.migration(),
            valid.issuerLayout(),
            new WriterFenceProof(
                valid.writerFencing().inventory(),
                WriterDisposition.STOPPED,
                List.of(valid.writerFencing().writerAcknowledgements().getFirst())),
            valid.worldInitialization(),
            valid.entityInitialization(),
            valid.regionLease());

    assertThatThrownBy(() -> withLayout(valid, incompleteLayout).validateFor(scope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("partition acknowledgement");
    assertThatThrownBy(() -> missingWriterFence.validateFor(scope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cover the inventory");
  }

  @Test
  void tenantAndUuidCoverageFenceSurviveCodecAndBindTheScopeDigest() {
    InitialGameplayReleaseTupleCodec codec =
        new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build());
    Scope original = scope(UUID.randomUUID());
    Scope otherTenant =
        new Scope(
            original.operationId(),
            UUID.randomUUID(),
            original.targetNamespace(),
            original.playableStateNamespaceId(),
            original.peerStorageBinding(),
            original.evidenceRef());
    InitialGameplayReleaseEvidence proof = evidence(original);

    assertThat(codec.readScope(codec.toJson(original))).isEqualTo(original);
    assertThat(codec.digest(original)).isNotEqualTo(codec.digest(otherTenant));
    assertThat(codec.readEvidence(codec.toJson(proof)).issuerLayout().coverageFence())
        .isEqualTo(proof.issuerLayout().coverageFence());

    String legacyScopeJson =
        codec
            .toJson(original)
            .replace("\"canonicalTenantId\":\"" + original.canonicalTenantId() + "\",", "");
    assertThatThrownBy(() -> codec.readScope(legacyScopeJson)).isInstanceOf(RuntimeException.class);
    String nilTenantScopeJson =
        codec
            .toJson(original)
            .replace(
                "\"canonicalTenantId\":\"" + original.canonicalTenantId() + "\"",
                "\"canonicalTenantId\":\"" + NIL_UUID + "\"");
    assertThatThrownBy(() -> codec.readScope(nilTenantScopeJson))
        .isInstanceOf(RuntimeException.class);

    String legacyNumericFenceJson =
        codec
            .toJson(proof)
            .replace(
                "\"coverageFence\":\"" + proof.issuerLayout().coverageFence() + "\"",
                "\"coverageFence\":34");
    assertThatThrownBy(() -> codec.readEvidence(legacyNumericFenceJson))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void continuationAndResumePhasesAreNotInterchangeable() {
    assertThatThrownBy(
            () ->
                new net.firedevops.firemud.recovery.model.InitialGameplayReleaseCommands
                    .ContinueRecovery(
                    UUID.randomUUID(),
                    Phase.AWAITING_RESUME,
                    MaintenanceLockCredential.issue(UUID.randomUUID()),
                    "evidence-ref"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ready_to_reopen");
    assertThatThrownBy(
            () ->
                new net.firedevops.firemud.recovery.model.InitialGameplayReleaseCommands.Resume(
                    UUID.randomUUID(),
                    Phase.READY_TO_REOPEN,
                    MaintenanceLockCredential.issue(UUID.randomUUID()),
                    "evidence-ref"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("awaiting_resume");
  }

  @Test
  void maintenanceLockCredentialRedactsItsWireValueFromDefaultRendering() {
    UUID lockIdentity = UUID.randomUUID();
    MaintenanceLockCredential credential = MaintenanceLockCredential.issue(lockIdentity);
    Scope scope = scope(UUID.randomUUID());
    InitialGameplayReleaseModel.Operation operation =
        new InitialGameplayReleaseModel.Operation(
            scope,
            lockIdentity,
            Phase.COLLECTING,
            1,
            null,
            java.time.Instant.EPOCH,
            java.time.Instant.EPOCH,
            null);
    InitialGameplayReleaseModel.PreparePlayableStateFencedEffect effect =
        new InitialGameplayReleaseModel.PreparePlayableStateFencedEffect(
            scope.operationId(), UUID.randomUUID(), lockIdentity, scope, DIGEST, "proof-identity");

    assertThat(credential.toString()).doesNotContain(lockIdentity.toString());
    assertThat(operation.toString()).doesNotContain(lockIdentity.toString());
    assertThat(effect.toString()).doesNotContain(lockIdentity.toString());
    assertThat(credential.protectedTransportValue()).isEqualTo(lockIdentity.toString());
  }

  private static Scope scope(UUID playableNamespace) {
    return new Scope(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "release-target",
        playableNamespace,
        new PeerStorageBinding(
            "release-target", "cluster-peer", "peer-workload", "storage-uid", "storage-r7", DIGEST),
        "initial-layout-evidence-17");
  }

  static InitialGameplayReleaseEvidence evidence(Scope scope) {
    LayoutDescriptor descriptor =
        new LayoutDescriptor("initial-layout-v1", "layout-r1", DIGEST, List.of(0, 1));
    IssuerLayoutProof layout =
        new IssuerLayoutProof(
            UUID.randomUUID(),
            descriptor,
            "account-issuer-source-29",
            21,
            UUID.randomUUID(),
            "inventory-r3",
            DIGEST,
            List.of(
                new PartitionAcknowledgement(0, "ack-0", DIGEST, DIGEST, 10),
                new PartitionAcknowledgement(1, "ack-1", DIGEST, DIGEST, 12)),
            Map.of(0, 10, 1, 12));
    WriterInventory inventory =
        new WriterInventory(
            "inventory-r3", DIGEST, "legacy-writer", List.of("writer-a", "writer-b"));
    WriterFenceProof writerFencing =
        new WriterFenceProof(
            inventory,
            WriterDisposition.STOPPED,
            List.of(
                new WriterFenceAcknowledgement(
                    "writer-a", WriterDisposition.FENCED, "fence-a", DIGEST),
                new WriterFenceAcknowledgement(
                    "writer-b", WriterDisposition.FENCED, "fence-b", DIGEST)));
    return new InitialGameplayReleaseEvidence(
        scope.evidenceRef(),
        "initial-release-proof-17",
        new MigrationProof(
            UUID.randomUUID(),
            ProofDisposition.VERIFIED,
            scope.peerStorageBinding().storageIdentity(),
            "inventory-r3",
            DIGEST),
        layout,
        writerFencing,
        new OwnerInitializationProof(
            OwnerService.WORLD_MANAGEMENT,
            UUID.randomUUID(),
            scope.playableStateNamespaceId(),
            ProofDisposition.VERIFIED,
            "world-source-4",
            DIGEST),
        new OwnerInitializationProof(
            OwnerService.ENTITY_MANAGEMENT,
            UUID.randomUUID(),
            scope.playableStateNamespaceId(),
            ProofDisposition.VERIFIED,
            "entity-source-8",
            DIGEST),
        new RegionLeaseProof("region-west-1", "lease-59", 11));
  }

  private static InitialGameplayReleaseEvidence withLayout(
      InitialGameplayReleaseEvidence source, IssuerLayoutProof layout) {
    return new InitialGameplayReleaseEvidence(
        source.evidenceRef(),
        source.evidenceIdentity(),
        source.migration(),
        layout,
        source.writerFencing(),
        source.worldInitialization(),
        source.entityInitialization(),
        source.regionLease());
  }
}
