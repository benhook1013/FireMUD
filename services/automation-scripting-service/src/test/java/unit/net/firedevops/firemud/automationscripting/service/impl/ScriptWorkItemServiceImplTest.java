package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.config.ScriptOutboxProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
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

  private static ScriptOutboxProperties outboxProperties() {
    return new ScriptOutboxProperties();
  }
}
