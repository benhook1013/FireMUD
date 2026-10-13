package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.OpenOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class WorldDraftRegionStagingServiceTest {
  private final WorldDesignPublicationFenceRepository fence =
      mock(WorldDesignPublicationFenceRepository.class);
  private final WorldAuthoredGraphReader reader = mock(WorldAuthoredGraphReader.class);
  private final WorldDraftRegionGraphStager stager = mock(WorldDraftRegionGraphStager.class);
  private final WorldDraftRegionStagingService service =
      new WorldDraftRegionStagingService(fence, reader, stager);

  @Test
  void resolvesExactOwnerBeforeReadingServerPrivateKeysAndInvokingExistingStager() {
    // Synthetic unit prerequisites: these doubles prove sequencing, not authenticated intake.
    WorldDraftRegionCommitPlan plan = mock(WorldDraftRegionCommitPlan.class);
    OwnerBinding binding = ownerBinding();
    WorldAuthoredSourceIntakeReceipt receipt = mock(WorldAuthoredSourceIntakeReceipt.class);
    bindSyntheticSource(plan, receipt);
    when(receipt.localTenantKey()).thenReturn(123L);
    when(plan.ownerBinding()).thenReturn(binding);
    when(fence.lockOpenAndResolve(binding)).thenReturn(new OpenOwner(binding, receipt, 456L));
    WorldAuthoredGraph graph =
        new WorldAuthoredGraph(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    when(reader.readAndValidateGraph(123L, 456L)).thenReturn(graph);
    WorldDraftRegionGraphStager.UnverifiedStagedContent content =
        new WorldDraftRegionGraphStager.UnverifiedStagedContent(plan.binding(), binding, graph);
    when(stager.stage(plan, graph)).thenReturn(content);

    assertThat(service.stage(plan)).isSameAs(content);
    InOrder order = inOrder(fence, reader, stager);
    order.verify(fence).lockOpenAndResolve(binding);
    order.verify(reader).readAndValidateGraph(123L, 456L);
    order.verify(stager).stage(plan, graph);
    order.verifyNoMoreInteractions();
    assertThat(binding.gameDesignVersionId()).isNotEqualTo(456L);
  }

  @Test
  void rejectsClosedOwnerBeforeAnyGraphReadOrStaging() {
    WorldDraftRegionCommitPlan plan = mock(WorldDraftRegionCommitPlan.class);
    OwnerBinding binding = ownerBinding();
    when(plan.ownerBinding()).thenReturn(binding);
    when(fence.lockOpenAndResolve(binding))
        .thenThrow(new WorldDesignPublicationFenceRepository.ConflictException("not OPEN"));

    assertThatThrownBy(() -> service.stage(plan))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    verifyNoInteractions(reader, stager);
  }

  @Test
  void incompletePriorGraphCannotReachStager() {
    WorldDraftRegionCommitPlan plan = mock(WorldDraftRegionCommitPlan.class);
    OwnerBinding binding = ownerBinding();
    WorldAuthoredSourceIntakeReceipt receipt = mock(WorldAuthoredSourceIntakeReceipt.class);
    bindSyntheticSource(plan, receipt);
    when(receipt.localTenantKey()).thenReturn(123L);
    when(plan.ownerBinding()).thenReturn(binding);
    when(fence.lockOpenAndResolve(binding)).thenReturn(new OpenOwner(binding, receipt, 456L));
    when(reader.readAndValidateGraph(123L, 456L))
        .thenThrow(new WorldAuthoredGraphSnapshotRepository.SnapshotConflictException("bad graph"));

    assertThatThrownBy(() -> service.stage(plan))
        .isInstanceOf(WorldAuthoredGraphSnapshotRepository.SnapshotConflictException.class);
    verifyNoInteractions(stager);
  }

  @ParameterizedTest
  @ValueSource(strings = {"sourceGameRowId", "sourceGameTenantKey", "sourceProvenanceKind"})
  void rejectsEachChangedSourceFieldBeforeAnyGraphReadOrStaging(String changedField) {
    WorldDraftRegionCommitPlan plan = mock(WorldDraftRegionCommitPlan.class);
    OwnerBinding binding = ownerBinding();
    WorldAuthoredSourceIntakeReceipt receipt = mock(WorldAuthoredSourceIntakeReceipt.class);
    TargetProof target = bindSyntheticSource(plan, receipt);
    when(plan.ownerBinding()).thenReturn(binding);
    when(fence.lockOpenAndResolve(binding)).thenReturn(new OpenOwner(binding, receipt, 456L));
    switch (changedField) {
      case "sourceGameRowId" -> when(target.sourceGameRowId()).thenReturn(999L);
      case "sourceGameTenantKey" ->
          when(target.sourceGameTenantKey()).thenReturn("changed-source-key");
      case "sourceProvenanceKind" ->
          when(target.sourceProvenanceKind()).thenReturn("changed-provenance");
      default -> throw new IllegalArgumentException("Unexpected synthetic negative field");
    }

    assertThatThrownBy(() -> service.stage(plan))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("source provenance");
    verifyNoInteractions(reader, stager);
  }

  private static TargetProof bindSyntheticSource(
      WorldDraftRegionCommitPlan plan, WorldAuthoredSourceIntakeReceipt receipt) {
    // Complete compared provenance is stipulated here; these doubles do not prove authentication.
    DraftCommitBinding binding = mock(DraftCommitBinding.class);
    TargetProof target = mock(TargetProof.class);
    AuthoredWorldSourceEvidence source = mock(AuthoredWorldSourceEvidence.class);
    when(plan.binding()).thenReturn(binding);
    when(binding.target()).thenReturn(target);
    when(receipt.source()).thenReturn(source);
    when(target.sourceGameRowId()).thenReturn(789L);
    when(target.sourceGameTenantKey()).thenReturn("gd-row-789");
    when(target.sourceProvenanceKind()).thenReturn("NEW_GAME_ROW");
    when(source.sourceGameRowId()).thenReturn(789L);
    when(source.sourceGameTenantKey()).thenReturn("gd-row-789");
    when(source.provenanceKind()).thenReturn("NEW_GAME_ROW");
    return target;
  }

  private static OwnerBinding ownerBinding() {
    return new OwnerBinding(
        "firemud",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        987_654L,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sha256:" + "a".repeat(64),
        UUID.randomUUID(),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64));
  }
}
