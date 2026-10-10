package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.util.JsonFormat;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalFrozenTopology.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeMutationPolicy;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class WorldCanonicalInstancePreparationTest {
  @Test
  void exactReleaseGraphJoinAcceptsMatchingCheckpointAndDistinctPublishedEpoch() throws Exception {
    var fixture = releaseGraphFixture();
    WorldCanonicalInstancePreparation.requireExactReleaseGraph(fixture.release(), fixture.plan());
    assertThat(fixture.release().versionStateEpoch())
        .isGreaterThan(fixture.plan().sourceBinding().freeze().versionStateEpoch());
  }

  @Test
  void exactReleaseGraphJoinRejectsChangedCommitDigestSchemaVersionAndWorkflow() throws Exception {
    var fixture = releaseGraphFixture();
    var source = fixture.plan().sourceBinding();
    var freeze = source.freeze();
    for (var changed :
        List.of(
            releaseFor(
                source,
                UUID.randomUUID(),
                freeze.appliedCommitId(),
                freeze.contentDigest(),
                freeze.publishWorkflowId()),
            releaseFor(
                source,
                freeze.canonicalVersionId(),
                UUID.randomUUID().toString(),
                freeze.contentDigest(),
                freeze.publishWorkflowId()),
            releaseFor(
                source,
                freeze.canonicalVersionId(),
                freeze.appliedCommitId(),
                "6".repeat(64),
                freeze.publishWorkflowId()),
            releaseFor(
                source,
                freeze.canonicalVersionId(),
                freeze.appliedCommitId(),
                freeze.contentDigest(),
                "another-workflow"))) {
      assertThatThrownBy(
              () ->
                  WorldCanonicalInstancePreparation.requireExactReleaseGraph(
                      changed, fixture.plan()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var changedSchema =
        new CaptureRequest(
            freeze.targetNamespace(),
            freeze.canonicalTenantId(),
            freeze.canonicalVersionId(),
            freeze.intakeRequestId(),
            freeze.publicationFence(),
            freeze.publicationRequestId(),
            freeze.requestDigest(),
            freeze.versionStateEpoch(),
            freeze.publishWorkflowId(),
            freeze.appliedCommitId(),
            freeze.contentDigest(),
            2,
            freeze.suppliedOwnedAffectedTuples());
    when(fixture.plan().sourceBinding()).thenReturn(new Request(source.plan(), changedSchema));
    assertThatThrownBy(
            () ->
                WorldCanonicalInstancePreparation.requireExactReleaseGraph(
                    fixture.release(), fixture.plan()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("World participant differs");
  }

  private record ReleaseGraphFixture(
      AuthoredWorldReleaseAttestationEvidence release, WorldCanonicalInstanceTopologyPlan plan) {}

  private static ReleaseGraphFixture releaseGraphFixture() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID commit = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    UUID region = UUID.randomUUID();
    var owner = ownerBinding(tenant, version);
    var binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                tenant, version, 41L, "gd-tenant", 42L, "gd-tenant", "NEW_GAME_ROW"),
            UUID.randomUUID(),
            commit,
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    revision,
                    Owner.WORLD_MANAGEMENT,
                    JsonFormat.printer()
                        .print(regionMutation(commit, revision, region, "Region")))),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "REGION",
                    region.toString(),
                    "AGGREGATE",
                    region.toString(),
                    "0"),
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "REGION",
                    region.toString(),
                    "REGION_SUBTREE",
                    region.toString(),
                    "0")));
    var freeze = freezeRequest(owner, binding);
    var source = new Request(WorldDraftTopologyCommitPlan.create(binding, owner), freeze);
    var plan = mock(WorldCanonicalInstanceTopologyPlan.class);
    when(plan.sourceBinding()).thenReturn(source);
    when(plan.tenantId()).thenReturn(tenant);
    when(plan.versionId()).thenReturn(version);
    return new ReleaseGraphFixture(
        releaseFor(
            source, version, commit.toString(), freeze.contentDigest(), freeze.publishWorkflowId()),
        plan);
  }

  private static AuthoredWorldReleaseAttestationEvidence releaseFor(
      Request source, UUID version, String commit, String worldDigest, String workflow) {
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(source.plan().ownerBinding().gameDesignVersionId()),
                        false,
                        null,
                        commit,
                        "WORLD_MANAGEMENT".equals(owner) ? worldDigest : "a".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "b".repeat(64) : null))
            .toList();
    return AuthoredWorldReleaseAttestationEvidence.create(
        source.freeze().targetNamespace(),
        "sha256:" + "1".repeat(64),
        source.freeze().canonicalTenantId(),
        version,
        "synthetic-world",
        UUID.randomUUID(),
        "sha256:" + "2".repeat(64),
        "descriptor",
        "release",
        8L,
        workflow,
        commit,
        participants,
        "sha256:" + "3".repeat(64),
        1,
        List.of(),
        List.of(),
        List.of(),
        "generation");
  }

  @Test
  void translatesV35DifferentPreparationConflictAndPreservesCause() {
    DuplicateKeyException databaseFailure =
        duplicateKeyFailure(
            "23505", "Canonical gameInstanceId is already bound to a different preparation");

    RuntimeException translated =
        WorldCanonicalInstancePreparationRepository.translateCanonicalPreparationConflict(
            databaseFailure);

    assertThat(translated)
        .isInstanceOf(
            WorldCanonicalInstancePreparationRepository.ConflictingPreparationException.class);
    assertThat(translated.getCause()).isSameAs(databaseFailure);
  }

  @Test
  void translatesV35ReservedIdentityConflictAndPreservesCause() {
    DuplicateKeyException databaseFailure =
        duplicateKeyFailure(
            "23505",
            "ERROR: Canonical game instance or request identity is already reserved\n  Where: owner function");

    RuntimeException translated =
        WorldCanonicalInstancePreparationRepository.translateCanonicalPreparationConflict(
            databaseFailure);

    assertThat(translated)
        .isInstanceOf(
            WorldCanonicalInstancePreparationRepository.ConflictingPreparationException.class);
    assertThat(translated.getCause()).isSameAs(databaseFailure);
  }

  @Test
  void preservesUnrelatedDuplicateKeyFailures() {
    DuplicateKeyException wrongSqlState =
        duplicateKeyFailure(
            "23503", "Canonical game instance or request identity is already reserved");
    DuplicateKeyException unrelatedUniqueViolation =
        duplicateKeyFailure("23505", "Unexpected unique constraint violation");
    DuplicateKeyException unrelatedViolationWithMatchingDetail =
        duplicateKeyFailure(
            "23505",
            "ERROR: duplicate key violates a different constraint\nDetail: Canonical game instance or request identity is already reserved");

    assertThat(
            WorldCanonicalInstancePreparationRepository.translateCanonicalPreparationConflict(
                wrongSqlState))
        .isSameAs(wrongSqlState);
    assertThat(
            WorldCanonicalInstancePreparationRepository.translateCanonicalPreparationConflict(
                unrelatedUniqueViolation))
        .isSameAs(unrelatedUniqueViolation);
    assertThat(
            WorldCanonicalInstancePreparationRepository.translateCanonicalPreparationConflict(
                unrelatedViolationWithMatchingDetail))
        .isSameAs(unrelatedViolationWithMatchingDetail);
  }

  @Test
  void defaultServiceDeniesBeforeRepositoryAccess() {
    WorldCanonicalInstancePreparationRepository repository =
        mock(WorldCanonicalInstancePreparationRepository.class);
    WorldCanonicalInstancePreparation.Input input =
        mock(WorldCanonicalInstancePreparation.Input.class);
    WorldCanonicalInstanceTopologyPlan plan = mock(WorldCanonicalInstanceTopologyPlan.class);
    when(input.topologyPlan()).thenReturn(plan);
    when(plan.generationRules()).thenReturn(List.of());
    when(plan.spawnBindings()).thenReturn(List.of());

    assertThatThrownBy(
            () -> new WorldCanonicalInstancePreparationService(repository).prepare(input))
        .isInstanceOf(WorldCanonicalInstancePreparationService.PreparationDeniedException.class)
        .hasMessageContaining("no authenticated source/release terminal verifier");

    verifyNoInteractions(repository);
  }

  private static DuplicateKeyException duplicateKeyFailure(String sqlState, String message) {
    return new DuplicateKeyException(
        "Database rejected canonical preparation", new SQLException(message, sqlState));
  }

  @Test
  void requiredGenerationIntentFailsClosedBeforeAnyPreparation() {
    WorldCanonicalInstanceTopologyPlan plan = mock(WorldCanonicalInstanceTopologyPlan.class);
    when(plan.generationRules())
        .thenReturn(List.of(mock(WorldCanonicalInstanceTopologyPlan.GenerationRuleIntent.class)));
    when(plan.spawnBindings()).thenReturn(List.of());

    assertThatThrownBy(() -> WorldCanonicalInstancePreparation.requireGenerationFree(plan))
        .isInstanceOf(WorldCanonicalInstancePreparation.GenerationIntentNotSupportedException.class)
        .hasMessageContaining("configured generation or spawn intent");
  }

  @Test
  void requiredSpawnIntentFailsClosedBeforeAnyPreparation() {
    WorldCanonicalInstanceTopologyPlan plan = mock(WorldCanonicalInstanceTopologyPlan.class);
    when(plan.generationRules()).thenReturn(List.of());
    when(plan.spawnBindings())
        .thenReturn(List.of(mock(WorldCanonicalInstanceTopologyPlan.SpawnBindingIntent.class)));

    assertThatThrownBy(() -> WorldCanonicalInstancePreparation.requireGenerationFree(plan))
        .isInstanceOf(WorldCanonicalInstancePreparation.GenerationIntentNotSupportedException.class)
        .hasMessageContaining("configured generation or spawn intent");
  }

  @Test
  void separatelyReconstructedFrozenSourceMatchesEveryValueButChangedPayloadIsDenied()
      throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID commitId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    UUID secondRevisionId = UUID.randomUUID();
    UUID secondRegionId = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenantId, versionId, 41L, "gd-tenant", 42L, "gd-tenant", "NEW_GAME_ROW");
    var originalMutation = regionMutation(commitId, revisionId, regionId, "Original region");
    var secondMutation =
        regionMutation(commitId, secondRevisionId, secondRegionId, "Second region");
    DraftCommitBinding binding =
        draftBinding(
            target,
            requestId,
            commitId,
            revisionId,
            regionId,
            originalMutation,
            secondRevisionId,
            secondRegionId,
            secondMutation);
    OwnerBinding owner = ownerBinding(tenantId, versionId);
    CaptureRequest freeze = freezeRequest(owner, binding);
    WorldDraftTopologyInputGraph graph =
        topologyGraph(
            tenantId,
            versionId,
            revisionId,
            regionId,
            originalMutation,
            secondRevisionId,
            secondRegionId,
            secondMutation);
    Request supplied = sourceRequest(binding, owner, freeze, graph);

    DraftCommitBinding reconstructedBinding =
        DraftCommitBinding.fromStored(binding.canonicalJson(), binding.digest());
    Request reconstructed =
        sourceRequest(
            reconstructedBinding,
            copyOwnerBinding(owner),
            copyFreezeRequest(freeze),
            topologyGraph(
                tenantId,
                versionId,
                revisionId,
                regionId,
                originalMutation,
                secondRevisionId,
                secondRegionId,
                secondMutation));

    assertThat(reconstructed).isNotEqualTo(supplied);
    assertThat(
            WorldCanonicalInstancePreparationRepository.matchesCompleteFrozenSource(
                reconstructed, supplied))
        .isTrue();

    var changedMutation = regionMutation(commitId, revisionId, regionId, "Changed region");
    Request changedSource =
        sourceRequest(
            draftBinding(
                target,
                requestId,
                commitId,
                revisionId,
                regionId,
                changedMutation,
                secondRevisionId,
                secondRegionId,
                secondMutation),
            owner,
            freeze,
            topologyGraph(
                tenantId,
                versionId,
                revisionId,
                regionId,
                changedMutation,
                secondRevisionId,
                secondRegionId,
                secondMutation));
    assertThat(
            WorldCanonicalInstancePreparationRepository.matchesCompleteFrozenSource(
                reconstructed, changedSource))
        .isFalse();

    assertThat(
            WorldCanonicalInstancePreparationRepository.matchesCompleteFrozenSource(
                reconstructed, requestWithFreeze(supplied, withChangedAffectedTuple(freeze))))
        .isFalse();

    assertThat(
            WorldCanonicalInstancePreparationRepository.matchesCompleteFrozenSource(
                reconstructed,
                sourceRequest(binding, withChangedOwnerEvidence(owner), freeze, graph)))
        .isFalse();

    var changedGraphMutation =
        regionMutation(commitId, revisionId, regionId, "Graph-only payload change");
    assertThat(
            WorldCanonicalInstancePreparationRepository.matchesCompleteFrozenSource(
                reconstructed,
                sourceRequest(
                    binding,
                    owner,
                    freeze,
                    topologyGraph(
                        tenantId,
                        versionId,
                        revisionId,
                        regionId,
                        changedGraphMutation,
                        secondRevisionId,
                        secondRegionId,
                        secondMutation))))
        .isFalse();

    assertThat(
            WorldCanonicalInstancePreparationRepository.matchesCompleteFrozenSource(
                reconstructed, sourceRequest(binding, owner, freeze, reverseTopologyGraph(graph))))
        .isFalse();
  }

  private static DraftCommitBinding draftBinding(
      DraftCommitBinding.TargetProof target,
      UUID requestId,
      UUID commitId,
      UUID revisionId,
      UUID regionId,
      WorldDesignMutationRevision mutation,
      UUID secondRevisionId,
      UUID secondRegionId,
      WorldDesignMutationRevision secondMutation)
      throws Exception {
    return DraftCommitBinding.create(
        target,
        requestId,
        commitId,
        "base-commit",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", revisionId, Owner.WORLD_MANAGEMENT, JsonFormat.printer().print(mutation)),
            new DraftCommitBinding.RevisionPayload(
                "1",
                secondRevisionId,
                Owner.WORLD_MANAGEMENT,
                JsonFormat.printer().print(secondMutation))),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                regionId.toString(),
                "AGGREGATE",
                regionId.toString(),
                "0"),
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                regionId.toString(),
                "REGION_SUBTREE",
                regionId.toString(),
                "0"),
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                secondRegionId.toString(),
                "AGGREGATE",
                secondRegionId.toString(),
                "0"),
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                secondRegionId.toString(),
                "REGION_SUBTREE",
                secondRegionId.toString(),
                "0")));
  }

  private static WorldDesignMutationRevision regionMutation(
      UUID commitId, UUID revisionId, UUID regionId, String name) {
    return WorldDesignMutationRevision.newBuilder()
        .setLogicalRevisionId(revisionId.toString())
        .setCommitId(commitId.toString())
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
        .setAggregateId(regionId.toString())
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(regionId.toString())
        .setScopeMutationPolicy(
            WorldDesignScopeMutationPolicy.WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED)
        .setRegion(RegionDesignMutation.newBuilder().setName(name))
        .build();
  }

  private static WorldDraftTopologyInputGraph topologyGraph(
      UUID tenantId,
      UUID versionId,
      UUID revisionId,
      UUID regionId,
      WorldDesignMutationRevision mutation,
      UUID secondRevisionId,
      UUID secondRegionId,
      WorldDesignMutationRevision secondMutation) {
    return new WorldDraftTopologyInputGraph(
        tenantId,
        versionId,
        List.of(
            new WorldDraftTopologyInputGraph.Node(
                "0", revisionId, regionId, regionId, mutation, null),
            new WorldDraftTopologyInputGraph.Node(
                "1", secondRevisionId, secondRegionId, secondRegionId, secondMutation, null)),
        null);
  }

  private static WorldDraftTopologyInputGraph reverseTopologyGraph(
      WorldDraftTopologyInputGraph graph) {
    return new WorldDraftTopologyInputGraph(
        graph.tenantId(),
        graph.versionId(),
        List.of(graph.nodes().get(1), graph.nodes().get(0)),
        graph.freshGraphDeclaration().orElse(null));
  }

  private static OwnerBinding ownerBinding(UUID tenantId, UUID versionId) {
    return new OwnerBinding(
        "firemud",
        tenantId,
        versionId,
        UUID.randomUUID(),
        41L,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sha256:" + "1".repeat(64),
        UUID.randomUUID(),
        "sha256:" + "2".repeat(64),
        "sha256:" + "3".repeat(64));
  }

  private static CaptureRequest freezeRequest(OwnerBinding owner, DraftCommitBinding binding) {
    return new CaptureRequest(
        owner.targetNamespace(),
        owner.canonicalTenantId(),
        owner.canonicalVersionId(),
        owner.intakeRequestId(),
        UUID.randomUUID(),
        "publication-request",
        "4".repeat(64),
        7L,
        PublicationDigestRequestBinding.full(
                owner.canonicalTenantId().toString(),
                Long.toString(owner.gameDesignVersionId()),
                "publication-request")
            .derivedWorkflowIdentity(),
        binding.commitId().toString(),
        "5".repeat(64),
        3,
        binding.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList());
  }

  private static CaptureRequest copyFreezeRequest(CaptureRequest source) {
    return new CaptureRequest(
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.canonicalVersionId(),
        source.intakeRequestId(),
        source.publicationFence(),
        source.publicationRequestId(),
        source.requestDigest(),
        source.versionStateEpoch(),
        source.publishWorkflowId(),
        source.appliedCommitId(),
        source.contentDigest(),
        source.digestSchemaVersion(),
        List.copyOf(source.suppliedOwnedAffectedTuples()));
  }

  private static CaptureRequest withChangedAffectedTuple(CaptureRequest source) {
    List<OwnedAffectedTuple> tuples = new ArrayList<>(source.suppliedOwnedAffectedTuples());
    OwnedAffectedTuple first = tuples.get(0);
    tuples.set(
        0,
        new OwnedAffectedTuple(
            first.owner(),
            first.aggregateType(),
            first.aggregateId(),
            first.scopeType(),
            first.scopeId(),
            "1"));
    return new CaptureRequest(
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.canonicalVersionId(),
        source.intakeRequestId(),
        source.publicationFence(),
        source.publicationRequestId(),
        source.requestDigest(),
        source.versionStateEpoch(),
        source.publishWorkflowId(),
        source.appliedCommitId(),
        source.contentDigest(),
        source.digestSchemaVersion(),
        tuples);
  }

  private static OwnerBinding copyOwnerBinding(OwnerBinding source) {
    return new OwnerBinding(
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.canonicalVersionId(),
        source.versionIdentityOperationId(),
        source.gameDesignVersionId(),
        source.intakeRequestId(),
        source.intakeOperationId(),
        source.intakeRequestDigest(),
        source.sourceOperationId(),
        source.sourceEvidenceDigest(),
        source.intakeReceiptDigest());
  }

  private static OwnerBinding withChangedOwnerEvidence(OwnerBinding source) {
    return new OwnerBinding(
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.canonicalVersionId(),
        source.versionIdentityOperationId(),
        source.gameDesignVersionId(),
        source.intakeRequestId(),
        source.intakeOperationId(),
        source.intakeRequestDigest(),
        source.sourceOperationId(),
        "sha256:" + "8".repeat(64),
        source.intakeReceiptDigest());
  }

  private static Request requestWithFreeze(Request source, CaptureRequest freeze) {
    Request request = mock(Request.class);
    when(request.plan()).thenReturn(source.plan());
    when(request.freeze()).thenReturn(freeze);
    return request;
  }

  private static Request sourceRequest(
      DraftCommitBinding binding,
      OwnerBinding owner,
      CaptureRequest freeze,
      WorldDraftTopologyInputGraph graph) {
    WorldDraftTopologyCommitPlan plan = mock(WorldDraftTopologyCommitPlan.class);
    when(plan.binding()).thenReturn(binding);
    when(plan.ownerBinding()).thenReturn(owner);
    when(plan.graph()).thenReturn(graph);
    return new Request(plan, freeze);
  }
}
