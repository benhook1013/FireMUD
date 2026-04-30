package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchReadinessProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchReadinessProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ScriptPatchReadinessProjectionServiceImplTest {
  @Test
  void supersedesOlderActivePatchAndCancelsPendingOnLoadWork() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection oldProjection = new ScriptPatchReadinessProjection();
    oldProjection.setTenantId("1");
    oldProjection.setScriptPatchVersion("patch-old");
    oldProjection.setReadinessStatus("ONLOAD_RUNNING");
    ScriptWorkItem pendingOnLoad = new ScriptWorkItem();
    pendingOnLoad.setTenantId("1");
    pendingOnLoad.setScriptPatchVersion("patch-old");
    pendingOnLoad.setEventType("onLoad");
    pendingOnLoad.setStatus("PENDING_EVALUATION");
    when(repository.findByTenantIdAndReadinessStatusInOrderByLastChangedAtAsc(
            "1", List.of("PENDING_VALIDATION", "ONLOAD_RUNNING")))
        .thenReturn(List.of(oldProjection));
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-new"))
        .thenReturn(Optional.empty());
    when(workItemRepository.findByTenantIdAndEventTypeAndStatusInOrderByCreatedAtAscIdAsc(
            "1", "onLoad", List.of("PENDING_EVALUATION")))
        .thenReturn(List.of(pendingOnLoad));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.beginPatchReadiness("1", "patch-new", 2);

    assertThat(oldProjection.getReadinessStatus()).isEqualTo("SUPERSEDED");
    assertThat(oldProjection.getStatusReason()).isEqualTo("superseded_by_newer_patch");
    assertThat(oldProjection.getSupersededByScriptPatchVersion()).isEqualTo("patch-new");
    assertThat(pendingOnLoad.getStatus()).isEqualTo("CANCELED");
    assertThat(pendingOnLoad.getCancelReason()).isEqualTo("superseded_by_newer_patch");
    ArgumentCaptor<ScriptPatchReadinessProjection> newProjectionCaptor =
        ArgumentCaptor.forClass(ScriptPatchReadinessProjection.class);
    verify(repository).save(newProjectionCaptor.capture());
    assertThat(newProjectionCaptor.getValue().getScriptPatchVersion()).isEqualTo("patch-new");
    assertThat(newProjectionCaptor.getValue().getReadinessStatus()).isEqualTo("ONLOAD_RUNNING");
    verify(workItemRepository).saveAll(List.of(pendingOnLoad));
  }

  @Test
  void doesNotReopenSupersededPatchAfterLateOnLoadCompletion() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-old");
    projection.setReadinessStatus("SUPERSEDED");
    projection.setStatusReason("superseded_by_newer_patch");
    projection.setSupersededByScriptPatchVersion("patch-new");
    projection.setLastChangedAt(Instant.ofEpochMilli(123));
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-old"))
        .thenReturn(Optional.of(projection));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.refreshFromOnLoadWorkItems("1", "patch-old");

    verify(workItemRepository, Mockito.never())
        .findByTenantIdAndScriptPatchVersion(Mockito.any(), Mockito.any());
    assertThat(service.getProjection("1", "patch-old")).isPresent();
    assertThat(service.getProjection("1", "patch-old").get().status())
        .isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_SUPERSEDED);
  }
}
