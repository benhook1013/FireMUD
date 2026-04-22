package net.firedevops.firemud.automationscripting.service.impl;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScriptWorkItemServiceImpl implements ScriptWorkItemService {
  private static final List<String> CANCELABLE_STATUSES = List.of("PENDING_EVALUATION");

  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository auditRepository;

  public ScriptWorkItemServiceImpl(
      ScriptWorkItemRepository workItemRepository, ScriptEventAuditRepository auditRepository) {
    this.workItemRepository = workItemRepository;
    this.auditRepository = auditRepository;
  }

  @Override
  @Transactional
  public long cancelPendingForPatch(CancelPendingForPatchCommand command) {
    requireText(command.tenantId(), "tenant_id");
    requireText(command.scriptPatchVersion(), "script_patch_version");
    String reason = normalizeReason(command.reason());
    Instant now = Instant.now();
    List<ScriptWorkItem> candidates =
        workItemRepository
            .findByTenantIdAndScriptPatchVersionAndStatusInOrderByCreatedAtAscIdAsc(
                command.tenantId(), command.scriptPatchVersion(), CANCELABLE_STATUSES)
            .stream()
            .filter(
                item ->
                    command.gameInstanceId().isBlank()
                        || item.getGameInstanceId().equals(command.gameInstanceId()))
            .filter(
                item ->
                    command.regionId().isBlank() || item.getRegionId().equals(command.regionId()))
            .toList();
    candidates.forEach(item -> cancel(item, reason, now));
    workItemRepository.saveAll(candidates);
    return candidates.size();
  }

  private void cancel(ScriptWorkItem item, String reason, Instant now) {
    item.setStatus("CANCELED");
    item.setCancelReason(reason);
    item.setUpdatedAt(now);
    auditRepository
        .findByWorkItemId(item.getId())
        .ifPresent(
            audit -> {
              audit.setFinalStage("ADMISSION");
              audit.setFinalOutcome("canceled");
              audit.setFinalReason(reason);
              audit.setUpdatedAt(now);
              auditRepository.save(audit);
            });
  }

  private static String normalizeReason(String reason) {
    return reason == null || reason.isBlank() ? "operator_cancel" : reason;
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }
}
