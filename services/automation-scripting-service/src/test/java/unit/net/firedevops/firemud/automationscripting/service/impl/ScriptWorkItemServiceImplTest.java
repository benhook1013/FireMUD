package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.config.ScriptOutboxProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

class ScriptWorkItemServiceImplTest {
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
        new ScriptWorkItemServiceImpl(workItemRepository, auditRepository, outboxProperties());

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
        new ScriptWorkItemServiceImpl(workItemRepository, auditRepository, outboxProperties());

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
            outboxProperties());

    assertThatThrownBy(() -> service.claimPendingForEvaluation(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("max_items must be positive");
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
        new ScriptWorkItemServiceImpl(workItemRepository, auditRepository, properties);

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
        new ScriptWorkItemServiceImpl(workItemRepository, auditRepository, properties);

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
        new ScriptWorkItemServiceImpl(workItemRepository, auditRepository, outboxProperties());

    Optional<ScriptWorkItemService.PatchStatusSummary> status =
        service.getPatchStatus("1", "patch-1");

    assertThat(status).isPresent();
    assertThat(status.get().status())
        .isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_ONLOAD_RUNNING);
    assertThat(status.get().statusReason()).isEqualTo("runtime_work_active");
    assertThat(status.get().lastChangedAtMs()).isEqualTo(200L);
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
        new ScriptWorkItemServiceImpl(workItemRepository, auditRepository, outboxProperties());

    List<ScriptWorkItemService.PatchStatusSummary> statuses =
        service.listPatchStatuses("1", ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED, 250L, 0L);

    assertThat(statuses).hasSize(1);
    assertThat(statuses.get(0).scriptPatchVersion()).isEqualTo("patch-failed");
    assertThat(statuses.get(0).status()).isEqualTo(ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED);
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
    deadLetter.setCreatedAt(Instant.ofEpochMilli(100));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(workItemRepository.findByTenantIdAndStatusOrderByUpdatedAtDescIdDesc(
            "1", "DEAD_LETTERED", PageRequest.of(0, 25)))
        .thenReturn(List.of(deadLetter));
    ScriptWorkItemService service =
        new ScriptWorkItemServiceImpl(workItemRepository, auditRepository, outboxProperties());

    List<ScriptWorkItemService.DeadLetterSummary> deadLetters =
        service.listDeadLetters("1", "game-1", "patch-1", 25);

    assertThat(deadLetters).hasSize(1);
    assertThat(deadLetters.get(0).workItemId()).isEqualTo("99");
    assertThat(deadLetters.get(0).reason()).isEqualTo("STALE_TIMELINE");
    assertThat(deadLetters.get(0).updatedAtMs()).isEqualTo(300L);
  }

  private static ScriptWorkItem workItem(String patchVersion, String status, Instant updatedAt) {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setScriptPatchVersion(patchVersion);
    item.setStatus(status);
    item.setUpdatedAt(updatedAt);
    return item;
  }

  private static ScriptOutboxProperties outboxProperties() {
    return new ScriptOutboxProperties();
  }
}
