package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutboxProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventIngressAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptHandoffEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

class ScriptWorkItemServiceImplTest {
  private static ScriptEventIngressAuditRepository ingressAuditRepository() {
    return Mockito.mock(ScriptEventIngressAuditRepository.class);
  }

  private static ScriptPatchInstanceRolloutProjectionService rolloutProjectionService() {
    return Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
  }

  private static AutomationAdmissionStateService admissionStateService() {
    AutomationAdmissionStateService service = Mockito.mock(AutomationAdmissionStateService.class);
    when(service.getState(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "NORMAL", 1L, "", "", "", 100L));
    return service;
  }

  private static GameDesignControlPlaneClient gameDesignClient() {
    GameDesignControlPlaneClient client = Mockito.mock(GameDesignControlPlaneClient.class);
    when(client.getPublishedScriptPatchVersion(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setScriptPatch(
                    PublishedScriptPatchVersion.newBuilder()
                        .setTenantId("1")
                        .setScriptPatchVersion("patch-1")
                        .setBaseVersionId(7L)
                        .build())
                .build());
    when(client.getPublishedReleaseBundle(Mockito.anyString(), Mockito.eq(7L)))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .addParticipantDigests(
                            ParticipantDigest.newBuilder()
                                .setParticipantKey("AUTOMATION_SCRIPTING")
                                .setContentDigest("ability-1")
                                .build())
                        .build())
                .build());
    return client;
  }

  @Test
  void cancelsPendingPatchWorkAndUpdatesAuditOutcome() {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(42L);
    item.setTenantId("1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setScriptPatchVersion("patch-1");
    item.setStatus("PENDING_EVALUATION");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByTenantIdAndScriptPatchVersionAndStatusInOrderByCreatedAtAscIdAsc(
            "1", "patch-1", List.of("PENDING_EVALUATION")))
        .thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(42L)).thenReturn(Optional.of(audit));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    long canceled =
        service.cancelPendingForPatch(
            new ScriptWorkItemService.CancelPendingForPatchCommand(
                "1", "patch-1", "game-1", "region-1", "req-1", "admin", "rollback"));

    assertThat(canceled).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("rollback");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("rollback");
    verify(workItemRepository).saveAll(List.of(item));
    verify(auditRepository).save(audit);
  }

  @Test
  void refreshesReadinessProjectionWhenCancelingPendingOnLoadWork() {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(43L);
    item.setTenantId("1");
    item.setScriptPatchVersion("patch-1");
    item.setEventType("onLoad");
    item.setStatus("PENDING_EVALUATION");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(workItemRepository.findByTenantIdAndScriptPatchVersionAndStatusInOrderByCreatedAtAscIdAsc(
            "1", "patch-1", List.of("PENDING_EVALUATION")))
        .thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(43L)).thenReturn(Optional.of(audit));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    long canceled =
        service.cancelPendingForPatch(
            new ScriptWorkItemService.CancelPendingForPatchCommand(
                "1", "patch-1", "", "", "req-1", "admin", "rollback"));

    assertThat(canceled).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    verify(readinessProjectionService).refreshFromOnLoadWorkItems("1", "patch-1");
  }

  @Test
  void cancelsPendingPluginVersionWorkAndUpdatesAuditOutcome() {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(43L);
    item.setTenantId("1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setPluginId("plugin-1");
    item.setPluginVersionId("plugin-v1");
    item.setScriptPatchVersion("patch-1");
    item.setStatus("PENDING_EVALUATION");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository
            .findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInOrderByCreatedAtAscIdAsc(
                "1", "plugin-1", "plugin-v1", List.of("PENDING_EVALUATION")))
        .thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(43L)).thenReturn(Optional.of(audit));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    long canceled =
        service.cancelPendingForPluginVersion(
            new ScriptWorkItemService.CancelPendingForPluginVersionCommand(
                "1", "plugin-1", "plugin-v1", "game-1", "region-1", "req-1", "admin", ""));

    assertThat(canceled).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("operator_cancel");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("operator_cancel");
    verify(workItemRepository).saveAll(List.of(item));
    verify(auditRepository).save(audit);
  }

  @Test
  void claimsPendingItemsForEvaluationInStableOrder() {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setStatus("PENDING_EVALUATION");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByStatusOrderByCreatedAtAscIdAsc(
            "PENDING_EVALUATION", PageRequest.of(0, 10)))
        .thenReturn(List.of(item));
    when(workItemRepository.saveAll(List.of(item))).thenReturn(List.of(item));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItem> claimed = service.claimPendingForEvaluation(10);

    assertThat(claimed).containsExactly(item);
    assertThat(item.getStatus()).isEqualTo("EVALUATING");
    assertThat(item.getUpdatedAt()).isNotNull();
    verify(workItemRepository).saveAll(List.of(item));
  }

  @Test
  void rejectsInvalidClaimLimit() {
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    assertThatThrownBy(() -> service.claimPendingForEvaluation(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("max_items must be positive");
  }

  @Test
  void claimsPendingWorkItemsByQueuePointerIds() {
    ScriptWorkItem item = workItem("patch-1", "PENDING_EVALUATION", Instant.EPOCH);
    item.setId(99L);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByIdInAndStatusOrderByCreatedAtAscIdAsc(
            List.of(99L, 100L), "PENDING_EVALUATION", PageRequest.of(0, 10)))
        .thenReturn(List.of(item));
    when(workItemRepository.saveAll(List.of(item))).thenReturn(List.of(item));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItem> claimed = service.claimPendingForEvaluation(List.of(99L, 100L), 10);

    assertThat(claimed).containsExactly(item);
    assertThat(item.getStatus()).isEqualTo("EVALUATING");
    verify(workItemRepository).saveAll(List.of(item));
  }

  @Test
  void cleansTerminalOutboxRowsUsingConfiguredRetentionWindows() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutboxProperties properties = outboxProperties();
    when(workItemRepository.deleteByStatusAndUpdatedAtBefore(
            Mockito.eq("HANDED_OFF"), Mockito.any()))
        .thenReturn(2L);
    when(workItemRepository.deleteByStatusAndUpdatedAtBefore(Mockito.eq("CANCELED"), Mockito.any()))
        .thenReturn(3L);
    when(workItemRepository.deleteByStatusAndUpdatedAtBefore(
            Mockito.eq("DEAD_LETTERED"), Mockito.any()))
        .thenReturn(5L);
    when(workItemRepository.countByStatus("DEAD_LETTERED")).thenReturn(100000L);
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            properties,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.TerminalCleanupResult result = service.cleanupTerminalWorkItems();

    assertThat(result.handedOffDeleted()).isEqualTo(2L);
    assertThat(result.canceledDeleted()).isEqualTo(3L);
    assertThat(result.deadLetteredDeleted()).isEqualTo(5L);
    assertThat(result.totalDeleted()).isEqualTo(10L);
    verify(workItemRepository)
        .deleteByStatusAndUpdatedAtBefore(Mockito.eq("HANDED_OFF"), Mockito.any());
    verify(workItemRepository)
        .deleteByStatusAndUpdatedAtBefore(Mockito.eq("CANCELED"), Mockito.any());
    verify(workItemRepository)
        .deleteByStatusAndUpdatedAtBefore(Mockito.eq("DEAD_LETTERED"), Mockito.any());
  }

  @Test
  void deletesOldestDeadLettersWhenRowCapIsExceeded() {
    ScriptWorkItem old = new ScriptWorkItem();
    old.setStatus("DEAD_LETTERED");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutboxProperties properties = outboxProperties();
    properties.setDeadLetterMaxRows(1);
    when(workItemRepository.countByStatus("DEAD_LETTERED")).thenReturn(2L);
    when(workItemRepository.findByStatusOrderByUpdatedAtAscIdAsc(
            "DEAD_LETTERED", PageRequest.of(0, 1)))
        .thenReturn(List.of(old));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            properties,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.TerminalCleanupResult result = service.cleanupTerminalWorkItems();

    assertThat(result.deadLetteredDeleted()).isEqualTo(1L);
    verify(workItemRepository).deleteAll(List.of(old));
  }

  @Test
  void summarizesPatchStatusFromDurableWorkItems() {
    ScriptWorkItem pending = workItem("patch-1", "PENDING_EVALUATION", Instant.ofEpochMilli(100));
    ScriptWorkItem handedOff = workItem("patch-1", "HANDED_OFF", Instant.ofEpochMilli(200));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByTenantIdAndScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(pending, handedOff));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    Optional<ScriptWorkItemService.PatchStatusSummary> status =
        service.getPatchStatus("1", "patch-1");

    assertThat(status).isPresent();
    assertThat(status.get().status())
        .isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_ONLOAD_RUNNING);
    assertThat(status.get().statusReason()).isEqualTo("runtime_work_active");
    assertThat(status.get().lastChangedAtMs()).isEqualTo(200L);
    assertThat(status.get().baseVersionId()).isEqualTo(7L);
    assertThat(status.get().abilitySchemaDigest()).isEqualTo("ability-1");
  }

  @Test
  void listsPatchStatusesWithFilters() {
    ScriptWorkItem ready = workItem("patch-ready", "HANDED_OFF", Instant.ofEpochMilli(200));
    ScriptWorkItem failed = workItem("patch-failed", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findDistinctScriptPatchVersionsByTenantId("1"))
        .thenReturn(List.of("patch-ready", "patch-failed"));
    when(workItemRepository.findByTenantIdAndScriptPatchVersion("1", "patch-ready"))
        .thenReturn(List.of(ready));
    when(workItemRepository.findByTenantIdAndScriptPatchVersion("1", "patch-failed"))
        .thenReturn(List.of(failed));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItemService.PatchStatusSummary> statuses =
        service.listPatchStatuses("1", ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED, 250L, 0L);

    assertThat(statuses).hasSize(1);
    assertThat(statuses.get(0).scriptPatchVersion()).isEqualTo("patch-failed");
    assertThat(statuses.get(0).status()).isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED);
    assertThat(statuses.get(0).baseVersionId()).isEqualTo(7L);
    assertThat(statuses.get(0).abilitySchemaDigest()).isEqualTo("ability-1");
  }

  @Test
  void reportsAutomationDrainStatusForScopedWorkItems() {
    ScriptWorkItem pending = workItem("patch-1", "PENDING_EVALUATION", Instant.ofEpochMilli(200));
    pending.setTenantId("1");
    pending.setGameInstanceId("game-1");
    pending.setRegionId("region-1");
    ScriptWorkItem evaluating = workItem("patch-1", "EVALUATING", Instant.ofEpochMilli(250));
    evaluating.setTenantId("1");
    evaluating.setGameInstanceId("game-1");
    evaluating.setRegionId("region-1");
    evaluating.setCreatedAt(Instant.ofEpochMilli(120));
    ScriptWorkItem inflight = workItem("patch-1", "HANDOFF_IN_FLIGHT", Instant.ofEpochMilli(260));
    inflight.setTenantId("1");
    inflight.setGameInstanceId("game-1");
    inflight.setRegionId("region-1");
    inflight.setCreatedAt(Instant.ofEpochMilli(140));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of(evaluating, inflight, pending));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus("1", "game-1", "region-1");

    assertThat(summary.tenantId()).isEqualTo("1");
    assertThat(summary.gameInstanceId()).isEqualTo("game-1");
    assertThat(summary.regionId()).isEqualTo("region-1");
    assertThat(summary.admissionMode()).isEqualTo("NORMAL");
    assertThat(summary.admissionEpoch()).isEqualTo(1L);
    assertThat(summary.activeExecutionCount()).isEqualTo(2L);
    assertThat(summary.oldestActiveExecutionStartedAtMs()).isEqualTo(120L);
    assertThat(summary.pendingCancelableWorkItemCount()).isEqualTo(1L);
    assertThat(summary.observedAtMs()).isPositive();
  }

  @Test
  void reportsZeroedAutomationDrainStatusWhenScopedWorkIsEmpty() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            "1", "game-1", "", List.of("PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT")))
        .thenReturn(List.of());
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        service.getAutomationDrainStatus("1", "game-1", "");

    assertThat(summary.regionId()).isEmpty();
    assertThat(summary.activeExecutionCount()).isZero();
    assertThat(summary.oldestActiveExecutionStartedAtMs()).isZero();
    assertThat(summary.pendingCancelableWorkItemCount()).isZero();
  }

  @Test
  void getsInstanceRolloutStatusFromRuntimePin() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    when(workItemRepository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            "1", "game-1", "patch-1"))
        .thenReturn(List.of());
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-1", "req-1", 150L, 151L, 0L, false, "", "", "")),
                "",
                ""));
    when(rolloutProjectionService.getProjection("1", "game-1", "patch-1"))
        .thenReturn(
            Optional.of(
                new ScriptWorkItemService.PatchInstanceRolloutSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED,
                    "runtime_pin_matches_patch",
                    150L,
                    151L,
                    0L,
                    false)));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService,
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> summary =
        service.getPatchInstanceRolloutStatus("1", "game-1", "patch-1");

    assertThat(summary).isPresent();
    assertThat(summary.get().rolloutStatus())
        .isEqualTo(ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED);
    assertThat(summary.get().statusReason()).isEqualTo("runtime_pin_matches_patch");
    assertThat(summary.get().projectionLagMs()).isZero();
    assertThat(summary.get().projectionStale()).isFalse();
  }

  @Test
  void fallsBackToStaleLocalInstanceRolloutWhenRuntimeUnavailable() {
    ScriptWorkItem canceled = workItem("patch-1", "CANCELED", Instant.ofEpochMilli(200));
    canceled.setTenantId("1");
    canceled.setGameInstanceId("game-1");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    when(workItemRepository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            "1", "game-1", "patch-1"))
        .thenReturn(List.of(canceled));
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(Optional.empty(), "", ""));
    when(rolloutProjectionService.getProjection("1", "game-1", "patch-1"))
        .thenReturn(
            Optional.of(
                new ScriptWorkItemService.PatchInstanceRolloutSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
                    "projection_lag_exceeded",
                    200L,
                    200L,
                    5_100L,
                    true)));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService,
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> summary =
        service.getPatchInstanceRolloutStatus("1", "game-1", "patch-1");

    assertThat(summary).isPresent();
    assertThat(summary.get().rolloutStatus())
        .isEqualTo(
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK);
    assertThat(summary.get().statusReason()).isEqualTo("projection_lag_exceeded");
    assertThat(summary.get().projectionStale()).isTrue();
  }

  @Test
  void listsInstanceRolloutsFromDistinctPairsAndRuntimeFilter() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptWorkItem ready = workItem("patch-1", "HANDED_OFF", Instant.ofEpochMilli(250));
    ready.setTenantId("1");
    ready.setGameInstanceId("game-1");
    ScriptWorkItemRepository.ScriptPatchInstanceProjection pair =
        new ScriptWorkItemRepository.ScriptPatchInstanceProjection() {
          @Override
          public String getGameInstanceId() {
            return "game-1";
          }

          @Override
          public String getScriptPatchVersion() {
            return "patch-1";
          }
        };
    when(workItemRepository.findDistinctInstancePatchPairs("1", "", "")).thenReturn(List.of(pair));
    when(workItemRepository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            "1", "game-1", "patch-1"))
        .thenReturn(List.of(ready));
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-2", "req-2", 260L, 261L, 0L, false, "", "", "")),
                "",
                ""));
    when(rolloutProjectionService.listProjections(
            "1",
            "",
            "",
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
            0L,
            0L))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.PatchInstanceRolloutSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
                    "runtime_pin_differs_from_patch",
                    260L,
                    261L,
                    0L,
                    false)));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService,
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItemService.PatchInstanceRolloutSummary> summaries =
        service.listPatchInstanceRollouts(
            "1",
            "",
            "",
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
            0L,
            0L);

    assertThat(summaries).hasSize(1);
    assertThat(summaries.get(0).gameInstanceId()).isEqualTo("game-1");
    assertThat(summaries.get(0).scriptPatchVersion()).isEqualTo("patch-1");
    assertThat(summaries.get(0).rolloutStatus())
        .isEqualTo(
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK);
  }

  @Test
  void listsDeadLettersWithBoundedFilters() {
    ScriptWorkItem deadLetter = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    deadLetter.setId(99L);
    deadLetter.setTenantId("1");
    deadLetter.setGameInstanceId("game-1");
    deadLetter.setRegionId("region-1");
    deadLetter.setRegionEpoch(12L);
    deadLetter.setEntityId("entity-1");
    deadLetter.setScriptId("script-1");
    deadLetter.setEventType("onCommand");
    deadLetter.setScriptEventId("event-1");
    deadLetter.setCancelReason("STALE_TIMELINE");
    deadLetter.setSourceKind("GAMEPLAY_EVENT");
    deadLetter.setSourceState("WORK_ITEM_PERSISTED");
    deadLetter.setPluginId("plugin-1");
    deadLetter.setPluginVersionId("plugin-v1");
    deadLetter.setCreatedAt(Instant.ofEpochMilli(100));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByTenantIdAndStatusOrderByUpdatedAtDescIdDesc(
            "1", "DEAD_LETTERED", PageRequest.of(0, 25)))
        .thenReturn(List.of(deadLetter));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItemService.DeadLetterSummary> deadLetters =
        service.listDeadLetters("1", "game-1", "patch-1", 25);

    assertThat(deadLetters).hasSize(1);
    assertThat(deadLetters.get(0).workItemId()).isEqualTo("99");
    assertThat(deadLetters.get(0).sourceKind()).isEqualTo("GAMEPLAY_EVENT");
    assertThat(deadLetters.get(0).sourceState()).isEqualTo("WORK_ITEM_PERSISTED");
    assertThat(deadLetters.get(0).pluginId()).isEqualTo("plugin-1");
    assertThat(deadLetters.get(0).pluginVersionId()).isEqualTo("plugin-v1");
    assertThat(deadLetters.get(0).reason()).isEqualTo("STALE_TIMELINE");
    assertThat(deadLetters.get(0).updatedAtMs()).isEqualTo(300L);
  }

  @Test
  void listsHandoffEventsWithBoundedFilters() {
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId("event-1");
    event.setTenantId("1");
    event.setGameInstanceId("game-1");
    event.setScriptPatchVersion("patch-1");
    event.setScriptId("script-1");
    event.setPluginId("plugin-1");
    event.setPluginVersionId("plugin-v1");
    event.setWorkItemId(99L);
    event.setCommandOrdinal(0);
    event.setAutomationDispatchId("workItem:99#0");
    event.setGameSessionCommandId("command-1");
    event.setTargetEntityId("target-1");
    event.setSourceKind("SCHEDULE_TIMER");
    event.setSourceState("SCHEDULE_DUE_CLAIMED");
    event.setSourceOrdinal(5000L);
    event.setSourceDueAtMs(5000L);
    event.setEmittedCommandText("LOOK AT old chest");
    event.setHandoffOutcome("enqueued");
    event.setHandoffReason("game_session_accepted");
    event.setObservedAt(Instant.ofEpochMilli(300L));
    when(handoffEventRepository.findEvents(
            Mockito.eq("1"),
            Mockito.eq("game-1"),
            Mockito.eq("patch-1"),
            Mockito.eq(99L),
            Mockito.eq("enqueued"),
            Mockito.any(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(List.of(event));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            handoffEventRepository,
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    List<ScriptWorkItemService.HandoffEventSummary> events =
        service.listHandoffEvents("1", "game-1", "patch-1", "99", "enqueued", 10L, 20L, 25);

    assertThat(events).hasSize(1);
    assertThat(events.get(0).automationDispatchId()).isEqualTo("workItem:99#0");
    assertThat(events.get(0).gameSessionCommandId()).isEqualTo("command-1");
    assertThat(events.get(0).sourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(events.get(0).sourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(events.get(0).sourceOrdinal()).isEqualTo(5000L);
    assertThat(events.get(0).emittedCommandText()).isEqualTo("LOOK AT old chest");
    assertThat(events.get(0).handoffOutcome()).isEqualTo("enqueued");
  }

  @Test
  void replaysEligibleDeadLetteredWorkItem() {
    ScriptWorkItem item = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    item.setId(77L);
    item.setTenantId("1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setRegionEpoch(3L);
    item.setEntityId("entity-1");
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setScriptEventId("event-1");
    item.setCreatedAt(Instant.ofEpochMilli(100));
    item.setCancelReason("GAME_SESSION_UNAVAILABLE");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptEventIngressAudit ingressAudit = new ScriptEventIngressAudit();
    ingressAudit.setPluginId("");
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptEventIngressAuditRepository ingressAuditRepository = ingressAuditRepository();
    when(workItemRepository.findById(77L)).thenReturn(Optional.of(item));
    when(workItemRepository.save(item)).thenReturn(item);
    when(auditRepository.findByWorkItemId(77L)).thenReturn(Optional.of(audit));
    when(ingressAuditRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                3L,
                "entity-1",
                "",
                "",
                "",
                "",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false))
        .thenReturn(Optional.of(ingressAudit));
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-1", "req-1", 500L, 501L, 0L, false, "", "", "")),
                "",
                ""));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository,
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService(),
            pluginRuntimeStateService,
            gameDesignClient());

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1",
                "game-1",
                "region-1",
                List.of("77"),
                "patch-1",
                0L,
                0L,
                10,
                "req-1",
                "admin",
                "retry"));

    assertThat(result.replayedCount()).isEqualTo(1L);
    assertThat(result.rejectedCount()).isEqualTo(0L);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getCancelReason()).isEmpty();
    assertThat(audit.getFinalStage()).isEqualTo("REPLAY");
    assertThat(audit.getFinalOutcome()).isEqualTo("requeued");
    assertThat(audit.getFinalReason()).isEqualTo("retry");
  }

  @Test
  void rejectsReplayWhenPinnedPatchDoesNotMatch() {
    ScriptWorkItem item = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    item.setId(77L);
    item.setTenantId("1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setRegionEpoch(3L);
    item.setEntityId("entity-1");
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setScriptEventId("event-1");
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptEventIngressAuditRepository ingressAuditRepository = ingressAuditRepository();
    when(workItemRepository.findById(77L)).thenReturn(Optional.of(item));
    when(auditRepository.findByWorkItemId(77L)).thenReturn(Optional.empty());
    when(ingressAuditRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                3L,
                "entity-1",
                "",
                "",
                "",
                "",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false))
        .thenReturn(Optional.empty());
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-2", "req-2", 600L, 601L, 0L, false, "", "", "")),
                "",
                ""));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository,
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            pinProjectionService,
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient());

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1", "game-1", "", List.of("77"), "", 0L, 0L, 10, "", "", ""));

    assertThat(result.replayedCount()).isEqualTo(0L);
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
  }

  @Test
  void replaysFailedOnLoadDeadLetterWhenReadinessProjectionMatches() {
    ScriptWorkItem item = workItem("patch-1", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    item.setId(88L);
    item.setTenantId("1");
    item.setEventType("onLoad");
    item.setScriptId("boot-script");
    item.setScriptEventId("onload:1:patch-1:boot-script");
    item.setCreatedAt(Instant.ofEpochMilli(100));
    item.setCancelReason("definition_invalid");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(workItemRepository.findById(88L)).thenReturn(Optional.of(item));
    when(workItemRepository.save(item)).thenReturn(item);
    when(auditRepository.findByWorkItemId(88L)).thenReturn(Optional.of(audit));
    when(readinessProjectionService.getProjection("1", "patch-1"))
        .thenReturn(
            Optional.of(
                new ScriptPatchReadinessProjectionService.ReadinessStatusSummary(
                    "1",
                    "patch-1",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED,
                    "onload_failed",
                    "",
                    900L)));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1", "", "", List.of("88"), "patch-1", 0L, 0L, 10, "req-1", "admin", "retry"));

    assertThat(result.replayedCount()).isEqualTo(1L);
    assertThat(result.rejectedCount()).isEqualTo(0L);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getCancelReason()).isEmpty();
    verify(readinessProjectionService).refreshFromOnLoadWorkItems("1", "patch-1");
    assertThat(audit.getFinalStage()).isEqualTo("REPLAY");
    assertThat(audit.getFinalOutcome()).isEqualTo("requeued");
  }

  @Test
  void rejectsReplayForSupersededOnLoadDeadLetter() {
    ScriptWorkItem item = workItem("patch-old", "DEAD_LETTERED", Instant.ofEpochMilli(300));
    item.setId(89L);
    item.setTenantId("1");
    item.setEventType("onLoad");
    item.setScriptId("boot-script");
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    when(workItemRepository.findById(89L)).thenReturn(Optional.of(item));
    when(auditRepository.findByWorkItemId(89L)).thenReturn(Optional.empty());
    when(readinessProjectionService.getProjection("1", "patch-old"))
        .thenReturn(
            Optional.of(
                new ScriptPatchReadinessProjectionService.ReadinessStatusSummary(
                    "1",
                    "patch-old",
                    ScriptPatchStatus.SCRIPT_PATCH_STATUS_SUPERSEDED,
                    "superseded_by_newer_patch",
                    "patch-new",
                    901L)));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(
            workItemRepository,
            auditRepository,
            ingressAuditRepository(),
            Mockito.mock(ScriptHandoffEventRepository.class),
            outboxProperties(),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            rolloutProjectionService(),
            Mockito.mock(PluginRuntimeStateService.class),
            gameDesignClient(),
            readinessProjectionService);

    ScriptWorkItemService.ReplayResult result =
        service.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                "1", "", "", List.of("89"), "patch-old", 0L, 0L, 10, "", "", ""));

    assertThat(result.replayedCount()).isEqualTo(0L);
    assertThat(result.rejectedCount()).isEqualTo(1L);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    verify(readinessProjectionService, Mockito.never())
        .refreshFromOnLoadWorkItems(Mockito.anyString(), Mockito.anyString());
  }

  private static ScriptWorkItem workItem(String patchVersion, String status, Instant updatedAt) {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId("1");
    item.setScriptPatchVersion(patchVersion);
    item.setStatus(status);
    item.setUpdatedAt(updatedAt);
    return item;
  }

  private static ScriptOutboxProperties outboxProperties() {
    return new ScriptOutboxProperties();
  }
}
