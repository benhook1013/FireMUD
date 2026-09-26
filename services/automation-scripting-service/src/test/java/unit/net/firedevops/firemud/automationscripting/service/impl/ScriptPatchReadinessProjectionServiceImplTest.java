package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ScriptPatchReadinessProjectionServiceImplTest {
  @Test
  void rejectsNonPostgresDialectBeforeReadinessMutation() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    DSLContext dsl = Mockito.mock(DSLContext.class);
    when(dsl.dialect()).thenReturn(SQLDialect.H2);

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository, dsl);

    assertThatThrownBy(() -> service.beginPatchReadiness("1", "patch-h2", List.of("script-a")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script_patch_readiness_requires_postgres");
    Mockito.verifyNoInteractions(repository, workItemRepository);
  }

  @Test
  void supersedesOlderActivePatchAndCancelsPendingOnLoadWork() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection oldProjection = new ScriptPatchReadinessProjection();
    oldProjection.setTenantId("1");
    oldProjection.setScriptPatchVersion("patch-old");
    oldProjection.setReadinessStatus("ONLOAD_RUNNING");
    oldProjection.setScriptSetManifest(List.of("script-old"));
    oldProjection.setReadinessGeneration(1L);
    ScriptWorkItem pendingOnLoad = new ScriptWorkItem();
    pendingOnLoad.setTenantId("1");
    pendingOnLoad.setScriptPatchVersion("patch-old");
    pendingOnLoad.setEventType("onLoad");
    pendingOnLoad.setStatus("PENDING_EVALUATION");
    when(repository.findByTenantIdAndReadinessStatusInOrderByLastChangedAtAsc(
            "1", List.of("PENDING_VALIDATION", "ONLOAD_RUNNING")))
        .thenReturn(List.of(oldProjection));
    when(repository.nextReadinessGeneration("1")).thenReturn(2L);
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-new"))
        .thenReturn(Optional.empty());
    when(workItemRepository.findByTenantIdAndEventTypeAndStatusInOrderByCreatedAtAscIdAsc(
            "1", "onLoad", List.of("PENDING_EVALUATION")))
        .thenReturn(List.of(pendingOnLoad));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.beginPatchReadiness("1", "patch-new", List.of("script-b", "script-a"));

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
    assertThat(newProjectionCaptor.getValue().getScriptSetManifest())
        .containsExactly("script-a", "script-b");
    assertThat(newProjectionCaptor.getValue().getReadinessGeneration()).isEqualTo(2L);
    verify(workItemRepository).saveAll(List.of(pendingOnLoad));
  }

  @Test
  void doesNotReopenTerminalReadinessIdentity() {
    for (String terminalStatus : List.of("READY", "FAILED", "ROLLED_BACK", "SUPERSEDED")) {
      ScriptPatchReadinessProjectionRepository repository =
          Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
      ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
      ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
      projection.setTenantId("1");
      projection.setScriptPatchVersion("patch-terminal-" + terminalStatus);
      projection.setReadinessStatus(terminalStatus);
      projection.setScriptSetManifest(List.of("script-a"));
      projection.setReadinessGeneration(1L);
      projection.setStatusReason("original-reason");
      projection.setSupersededByScriptPatchVersion("original-superseding-patch");
      projection.setLastChangedAt(Instant.ofEpochMilli(123));
      when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-terminal-" + terminalStatus))
          .thenReturn(Optional.of(projection));

      ScriptPatchReadinessProjectionServiceImpl service =
          new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

      service.beginPatchReadiness("1", "patch-terminal-" + terminalStatus, List.of("script-a"));

      assertThat(projection.getReadinessStatus()).isEqualTo(terminalStatus);
      assertThat(projection.getStatusReason()).isEqualTo("original-reason");
      assertThat(projection.getLastChangedAt()).isEqualTo(Instant.ofEpochMilli(123));
      Mockito.verify(repository, Mockito.never())
          .findByTenantIdAndReadinessStatusInOrderByLastChangedAtAsc(Mockito.any(), Mockito.any());
      Mockito.verify(repository, Mockito.never()).save(Mockito.any());
      Mockito.verifyNoInteractions(workItemRepository);
    }
  }

  @Test
  void doesNotResetActiveReadinessIdentityWhenRetried() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-active");
    projection.setReadinessStatus("ONLOAD_RUNNING");
    projection.setStatusReason("tenant_readiness_running");
    projection.setScriptSetManifest(List.of("script-a"));
    projection.setReadinessGeneration(1L);
    projection.setLastChangedAt(Instant.ofEpochMilli(123));
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-active"))
        .thenReturn(Optional.of(projection));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.beginPatchReadiness("1", "patch-active", List.of("script-a"));

    assertThat(projection.getReadinessStatus()).isEqualTo("ONLOAD_RUNNING");
    assertThat(projection.getStatusReason()).isEqualTo("tenant_readiness_running");
    assertThat(projection.getLastChangedAt()).isEqualTo(Instant.ofEpochMilli(123));
    Mockito.verify(repository, Mockito.never())
        .findByTenantIdAndReadinessStatusInOrderByLastChangedAtAsc(Mockito.any(), Mockito.any());
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(workItemRepository);
  }

  @Test
  void rejectsChangedScriptSetBeforeAnyDownstreamWork() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-ready");
    projection.setReadinessStatus("READY");
    projection.setScriptSetManifest(List.of("script-original"));
    projection.setReadinessGeneration(1L);
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-ready"))
        .thenReturn(Optional.of(projection));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    assertThatThrownBy(
            () -> service.beginPatchReadiness("1", "patch-ready", List.of("script-changed")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("script_patch_manifest_changed");

    Mockito.verify(repository, Mockito.never())
        .findByTenantIdAndReadinessStatusInOrderByLastChangedAtAsc(Mockito.any(), Mockito.any());
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(workItemRepository);
  }

  @Test
  void retainedProjectionWithoutScriptManifestCannotBeRetried() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection retainedProjection = new ScriptPatchReadinessProjection();
    retainedProjection.setTenantId("1");
    retainedProjection.setScriptPatchVersion("patch-retained");
    retainedProjection.setReadinessStatus("READY");
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-retained"))
        .thenReturn(Optional.of(retainedProjection));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    assertThatThrownBy(
            () -> service.beginPatchReadiness("1", "patch-retained", List.of("script-a")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script_patch_script_manifest_unavailable");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(workItemRepository);
  }

  @Test
  void refusesDownstreamWorkWhenANewerGenerationIsCurrent() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection oldProjection = new ScriptPatchReadinessProjection();
    oldProjection.setId(10L);
    oldProjection.setTenantId("1");
    oldProjection.setScriptPatchVersion("patch-old");
    oldProjection.setReadinessStatus("ONLOAD_RUNNING");
    oldProjection.setScriptSetManifest(List.of("script-a"));
    oldProjection.setReadinessGeneration(1L);
    ScriptPatchReadinessProjection latestProjection = new ScriptPatchReadinessProjection();
    latestProjection.setId(11L);
    latestProjection.setTenantId("1");
    latestProjection.setScriptPatchVersion("patch-new");
    latestProjection.setScriptSetManifest(List.of("script-a"));
    latestProjection.setReadinessGeneration(2L);
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-old"))
        .thenReturn(Optional.of(oldProjection));
    when(repository.findLatestGenerationByTenantId("1")).thenReturn(Optional.of(latestProjection));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);
    java.util.concurrent.atomic.AtomicBoolean downstreamRan =
        new java.util.concurrent.atomic.AtomicBoolean();

    assertThat(
            service.applyIfCurrent(
                "1", "patch-old", List.of("script-a"), ignored -> downstreamRan.set(true)))
        .isFalse();
    assertThat(downstreamRan).isFalse();
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(workItemRepository);
  }

  @Test
  void readyRetryRepairsDatabaseProgressWithoutReopeningOnLoad() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setId(10L);
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-ready");
    projection.setReadinessStatus("READY");
    projection.setScriptSetManifest(List.of("script-a"));
    projection.setReadinessGeneration(1L);
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-ready"))
        .thenReturn(Optional.of(projection));
    when(repository.findLatestGenerationByTenantId("1")).thenReturn(Optional.of(projection));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);
    java.util.concurrent.atomic.AtomicReference<Boolean> admitOnLoad =
        new java.util.concurrent.atomic.AtomicReference<>();

    assertThat(service.applyIfCurrent("1", "patch-ready", List.of("script-a"), admitOnLoad::set))
        .isTrue();
    assertThat(admitOnLoad.get()).isFalse();
    assertThat(projection.isDatabaseDownstreamReconciled()).isTrue();

    java.util.concurrent.atomic.AtomicBoolean registryRebuilt =
        new java.util.concurrent.atomic.AtomicBoolean();
    assertThat(
            service.rebuildRegistryIfCurrent(
                "1", "patch-ready", List.of("script-a"), () -> registryRebuilt.set(true)))
        .isTrue();
    assertThat(registryRebuilt).isTrue();
    Mockito.verify(repository).save(projection);
    Mockito.verifyNoInteractions(workItemRepository);
  }

  @Test
  void skipsRegistryRebuildWhenANewerGenerationBecameCurrentAfterDatabaseWork() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection oldProjection = new ScriptPatchReadinessProjection();
    oldProjection.setId(10L);
    oldProjection.setTenantId("1");
    oldProjection.setScriptPatchVersion("patch-old");
    oldProjection.setReadinessStatus("ONLOAD_RUNNING");
    oldProjection.setScriptSetManifest(List.of("script-a"));
    oldProjection.setReadinessGeneration(1L);
    oldProjection.setDatabaseDownstreamReconciled(true);
    ScriptPatchReadinessProjection latestProjection = new ScriptPatchReadinessProjection();
    latestProjection.setId(11L);
    latestProjection.setTenantId("1");
    latestProjection.setScriptPatchVersion("patch-new");
    latestProjection.setScriptSetManifest(List.of("script-a"));
    latestProjection.setReadinessGeneration(2L);
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-old"))
        .thenReturn(Optional.of(oldProjection));
    when(repository.findLatestGenerationByTenantId("1")).thenReturn(Optional.of(latestProjection));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);
    java.util.concurrent.atomic.AtomicBoolean registryRan =
        new java.util.concurrent.atomic.AtomicBoolean();

    assertThat(
            service.rebuildRegistryIfCurrent(
                "1", "patch-old", List.of("script-a"), () -> registryRan.set(true)))
        .isFalse();
    assertThat(registryRan).isFalse();
  }

  @Test
  void failedDownstreamWorkDoesNotRecordDatabaseCompletion() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setId(10L);
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-active");
    projection.setReadinessStatus("ONLOAD_RUNNING");
    projection.setScriptSetManifest(List.of("script-a"));
    projection.setReadinessGeneration(1L);
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-active"))
        .thenReturn(Optional.of(projection));
    when(repository.findLatestGenerationByTenantId("1")).thenReturn(Optional.of(projection));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    assertThatThrownBy(
            () ->
                service.applyIfCurrent(
                    "1",
                    "patch-active",
                    List.of("script-a"),
                    ignored -> {
                      throw new IllegalStateException("schedule_refresh_failed");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("schedule_refresh_failed");
    assertThat(projection.isDatabaseDownstreamReconciled()).isFalse();
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
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

  @Test
  void marksPatchRolledBackWhenOnLoadWorkWasCanceled() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-1");
    projection.setReadinessStatus("ONLOAD_RUNNING");
    ScriptWorkItem canceledOnLoad = new ScriptWorkItem();
    canceledOnLoad.setTenantId("1");
    canceledOnLoad.setScriptPatchVersion("patch-1");
    canceledOnLoad.setEventType("onLoad");
    canceledOnLoad.setStatus("CANCELED");
    canceledOnLoad.setCancelReason("rollback_epoch_advanced");
    canceledOnLoad.setUpdatedAt(Instant.ofEpochMilli(250));
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(Optional.of(projection));
    when(workItemRepository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(canceledOnLoad));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.refreshFromOnLoadWorkItems("1", "patch-1");

    assertThat(projection.getReadinessStatus()).isEqualTo("ROLLED_BACK");
    assertThat(projection.getStatusReason()).isEqualTo("rollback_epoch_advanced");
    assertThat(service.getProjection("1", "patch-1")).isPresent();
    assertThat(service.getProjection("1", "patch-1").get().status())
        .isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_ROLLED_BACK);
  }

  @Test
  void marksPatchFailedWithConcreteDeadLetterReason() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-1");
    projection.setReadinessStatus("ONLOAD_RUNNING");
    ScriptWorkItem deadLetteredOnLoad = new ScriptWorkItem();
    deadLetteredOnLoad.setTenantId("1");
    deadLetteredOnLoad.setScriptPatchVersion("patch-1");
    deadLetteredOnLoad.setEventType("onLoad");
    deadLetteredOnLoad.setStatus("DEAD_LETTERED");
    deadLetteredOnLoad.setCancelReason("onload_commands_not_allowed");
    deadLetteredOnLoad.setUpdatedAt(Instant.ofEpochMilli(400));
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(Optional.of(projection));
    when(workItemRepository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(deadLetteredOnLoad));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.refreshFromOnLoadWorkItems("1", "patch-1");

    assertThat(projection.getReadinessStatus()).isEqualTo("FAILED");
    assertThat(projection.getStatusReason()).isEqualTo("onload_commands_not_allowed");
    assertThat(service.getProjection("1", "patch-1")).isPresent();
    assertThat(service.getProjection("1", "patch-1").get().status())
        .isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED);
  }

  @Test
  void deadLetteredOnLoadTakesPrecedenceOverActiveSibling() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-1");
    projection.setReadinessStatus("ONLOAD_RUNNING");
    ScriptWorkItem deadLetteredOnLoad = new ScriptWorkItem();
    deadLetteredOnLoad.setTenantId("1");
    deadLetteredOnLoad.setScriptPatchVersion("patch-1");
    deadLetteredOnLoad.setEventType("onLoad");
    deadLetteredOnLoad.setStatus("DEAD_LETTERED");
    deadLetteredOnLoad.setCancelReason("onload_commands_not_allowed");
    deadLetteredOnLoad.setUpdatedAt(Instant.ofEpochMilli(400));
    ScriptWorkItem activeOnLoad = new ScriptWorkItem();
    activeOnLoad.setTenantId("1");
    activeOnLoad.setScriptPatchVersion("patch-1");
    activeOnLoad.setEventType("onLoad");
    activeOnLoad.setStatus("EVALUATING");
    ScriptWorkItem newerCanceledOnLoad = new ScriptWorkItem();
    newerCanceledOnLoad.setTenantId("1");
    newerCanceledOnLoad.setScriptPatchVersion("patch-1");
    newerCanceledOnLoad.setEventType("onLoad");
    newerCanceledOnLoad.setStatus("CANCELED");
    newerCanceledOnLoad.setCancelReason("rollback_epoch_advanced");
    newerCanceledOnLoad.setUpdatedAt(Instant.ofEpochMilli(500));
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(Optional.of(projection));
    when(workItemRepository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(deadLetteredOnLoad, activeOnLoad, newerCanceledOnLoad));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.refreshFromOnLoadWorkItems("1", "patch-1");

    assertThat(projection.getReadinessStatus()).isEqualTo("FAILED");
    assertThat(projection.getStatusReason()).isEqualTo("onload_commands_not_allowed");
  }

  @Test
  void marksPatchFailedWhenOnLoadReadinessCapacityIsDenied() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-1");
    projection.setReadinessStatus("ONLOAD_RUNNING");
    ScriptWorkItem canceledOnLoad = new ScriptWorkItem();
    canceledOnLoad.setTenantId("1");
    canceledOnLoad.setScriptPatchVersion("patch-1");
    canceledOnLoad.setEventType("onLoad");
    canceledOnLoad.setStatus("CANCELED");
    canceledOnLoad.setCancelReason("onload_budget_exceeded");
    canceledOnLoad.setUpdatedAt(Instant.ofEpochMilli(400));
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(Optional.of(projection));
    when(workItemRepository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(canceledOnLoad));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.refreshFromOnLoadWorkItems("1", "patch-1");

    assertThat(projection.getReadinessStatus()).isEqualTo("FAILED");
    assertThat(projection.getStatusReason()).isEqualTo("onload_budget_exceeded");
    assertThat(service.getProjection("1", "patch-1")).isPresent();
    assertThat(service.getProjection("1", "patch-1").get().status())
        .isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED);
  }

  @Test
  void keepsPatchOnLoadRunningWhileGameplayHandoffIsInFlight() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-1");
    projection.setReadinessStatus("ONLOAD_RUNNING");
    projection.setStatusReason("tenant_readiness_running");
    ScriptWorkItem handoffInFlightOnLoad = new ScriptWorkItem();
    handoffInFlightOnLoad.setTenantId("1");
    handoffInFlightOnLoad.setScriptPatchVersion("patch-1");
    handoffInFlightOnLoad.setEventType("onLoad");
    handoffInFlightOnLoad.setStatus("HANDOFF_IN_FLIGHT");
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(Optional.of(projection));
    when(workItemRepository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(handoffInFlightOnLoad));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.refreshFromOnLoadWorkItems("1", "patch-1");

    assertThat(projection.getReadinessStatus()).isEqualTo("ONLOAD_RUNNING");
    assertThat(projection.getStatusReason()).isEqualTo("tenant_readiness_running");
    verify(repository).save(projection);
  }

  @Test
  void failsPatchWhenCapacityDeniedSiblingHasActiveOnLoadWork() {
    ScriptPatchReadinessProjectionRepository repository =
        Mockito.mock(ScriptPatchReadinessProjectionRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId("1");
    projection.setScriptPatchVersion("patch-1");
    projection.setReadinessStatus("ONLOAD_RUNNING");
    ScriptWorkItem canceledOnLoad = new ScriptWorkItem();
    canceledOnLoad.setTenantId("1");
    canceledOnLoad.setScriptPatchVersion("patch-1");
    canceledOnLoad.setEventType("onLoad");
    canceledOnLoad.setStatus("CANCELED");
    canceledOnLoad.setCancelReason("onload_budget_exceeded");
    ScriptWorkItem activeOnLoad = new ScriptWorkItem();
    activeOnLoad.setTenantId("1");
    activeOnLoad.setScriptPatchVersion("patch-1");
    activeOnLoad.setEventType("onLoad");
    activeOnLoad.setStatus("EVALUATING");
    when(repository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(Optional.of(projection));
    when(workItemRepository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(canceledOnLoad, activeOnLoad));

    ScriptPatchReadinessProjectionServiceImpl service =
        new ScriptPatchReadinessProjectionServiceImpl(repository, workItemRepository);

    service.refreshFromOnLoadWorkItems("1", "patch-1");

    assertThat(projection.getReadinessStatus()).isEqualTo("FAILED");
    assertThat(projection.getStatusReason()).isEqualTo("onload_budget_exceeded");
  }
}
