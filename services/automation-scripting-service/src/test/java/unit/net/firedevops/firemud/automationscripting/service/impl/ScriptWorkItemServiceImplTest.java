package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        new ScriptWorkItemServiceImpl(workItemRepository, auditRepository);

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
}
