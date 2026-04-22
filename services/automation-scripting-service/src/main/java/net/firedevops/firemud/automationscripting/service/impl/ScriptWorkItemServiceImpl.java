package net.firedevops.firemud.automationscripting.service.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.config.ScriptOutboxProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScriptWorkItemServiceImpl implements ScriptWorkItemService {
  private static final String STATUS_PENDING_EVALUATION = "PENDING_EVALUATION";
  private static final String STATUS_EVALUATING = "EVALUATING";
  private static final String STATUS_CANCELED = "CANCELED";
  private static final String STATUS_HANDED_OFF = "HANDED_OFF";
  private static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_HANDOFF_IN_FLIGHT = "HANDOFF_IN_FLIGHT";
  private static final List<String> CANCELABLE_STATUSES = List.of(STATUS_PENDING_EVALUATION);

  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository auditRepository;
  private final ScriptOutboxProperties outboxProperties;

  public ScriptWorkItemServiceImpl(
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptOutboxProperties outboxProperties) {
    this.workItemRepository = workItemRepository;
    this.auditRepository = auditRepository;
    this.outboxProperties = outboxProperties;
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

  @Override
  @Transactional
  public List<ScriptWorkItem> claimPendingForEvaluation(int maxItems) {
    if (maxItems <= 0) {
      throw new IllegalArgumentException("max_items must be positive");
    }
    Instant now = Instant.now();
    List<ScriptWorkItem> items =
        workItemRepository.findByStatusOrderByCreatedAtAscIdAsc(
            STATUS_PENDING_EVALUATION, PageRequest.of(0, maxItems));
    items.forEach(
        item -> {
          item.setStatus(STATUS_EVALUATING);
          item.setUpdatedAt(now);
        });
    return List.copyOf(workItemRepository.saveAll(items));
  }

  @Override
  @Transactional
  public TerminalCleanupResult cleanupTerminalWorkItems() {
    Instant now = Instant.now();
    long handedOffDeleted =
        workItemRepository.deleteByStatusAndUpdatedAtBefore(
            STATUS_HANDED_OFF,
            now.minus(outboxProperties.getHandedOffRetentionDays(), ChronoUnit.DAYS));
    long canceledDeleted =
        workItemRepository.deleteByStatusAndUpdatedAtBefore(
            STATUS_CANCELED,
            now.minus(outboxProperties.getCanceledRetentionDays(), ChronoUnit.DAYS));
    long deadLetteredDeleted =
        workItemRepository.deleteByStatusAndUpdatedAtBefore(
            STATUS_DEAD_LETTERED,
            now.minus(outboxProperties.getDeadLetterMaxAgeSeconds(), ChronoUnit.SECONDS));
    deadLetteredDeleted += deleteExcessDeadLetters();
    return new TerminalCleanupResult(handedOffDeleted, canceledDeleted, deadLetteredDeleted);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PatchStatusSummary> getPatchStatus(String tenantId, String scriptPatchVersion) {
    requireText(tenantId, "tenant_id");
    requireText(scriptPatchVersion, "script_patch_version");
    return summarize(
        scriptPatchVersion,
        workItemRepository.findByTenantIdAndScriptPatchVersion(tenantId, scriptPatchVersion));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PatchStatusSummary> listPatchStatuses(
      String tenantId, ScriptPatchStatus status, long changedAfterMs, long changedBeforeMs) {
    requireText(tenantId, "tenant_id");
    return workItemRepository.findDistinctScriptPatchVersionsByTenantId(tenantId).stream()
        .map(
            patchVersion ->
                summarize(
                    patchVersion,
                    workItemRepository.findByTenantIdAndScriptPatchVersion(tenantId, patchVersion)))
        .flatMap(Optional::stream)
        .filter(
            summary ->
                status == ScriptPatchStatus.SCRIPT_PATCH_STATUS_UNSPECIFIED
                    || summary.status() == status)
        .filter(summary -> changedAfterMs <= 0 || summary.lastChangedAtMs() > changedAfterMs)
        .filter(summary -> changedBeforeMs <= 0 || summary.lastChangedAtMs() < changedBeforeMs)
        .sorted(Comparator.comparingLong(PatchStatusSummary::lastChangedAtMs).reversed())
        .toList();
  }

  private Optional<PatchStatusSummary> summarize(
      String scriptPatchVersion, List<ScriptWorkItem> workItems) {
    if (workItems.isEmpty()) {
      return Optional.empty();
    }
    Instant lastChanged =
        workItems.stream()
            .map(ScriptWorkItem::getUpdatedAt)
            .max(Comparator.naturalOrder())
            .orElse(Instant.EPOCH);
    ScriptPatchStatus status = statusFor(workItems);
    return Optional.of(
        new PatchStatusSummary(
            scriptPatchVersion, status, statusReasonFor(status), lastChanged.toEpochMilli()));
  }

  private ScriptPatchStatus statusFor(List<ScriptWorkItem> workItems) {
    if (hasStatus(workItems, STATUS_FAILED) || hasStatus(workItems, STATUS_DEAD_LETTERED)) {
      return ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED;
    }
    if (hasStatus(workItems, STATUS_PENDING_EVALUATION)
        || hasStatus(workItems, STATUS_EVALUATING)
        || hasStatus(workItems, STATUS_HANDOFF_IN_FLIGHT)) {
      return ScriptPatchStatus.SCRIPT_PATCH_STATUS_ONLOAD_RUNNING;
    }
    if (workItems.stream().allMatch(item -> STATUS_CANCELED.equals(item.getStatus()))) {
      return ScriptPatchStatus.SCRIPT_PATCH_STATUS_ROLLED_BACK;
    }
    return ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY;
  }

  private static boolean hasStatus(List<ScriptWorkItem> workItems, String status) {
    return workItems.stream().anyMatch(item -> status.equals(item.getStatus()));
  }

  private static String statusReasonFor(ScriptPatchStatus status) {
    return switch (status) {
      case SCRIPT_PATCH_STATUS_FAILED -> "terminal_work_failed";
      case SCRIPT_PATCH_STATUS_ONLOAD_RUNNING -> "runtime_work_active";
      case SCRIPT_PATCH_STATUS_ROLLED_BACK -> "runtime_work_canceled";
      case SCRIPT_PATCH_STATUS_READY -> "runtime_work_terminal";
      default -> "runtime_status_unknown";
    };
  }

  private long deleteExcessDeadLetters() {
    long deadLetteredCount = workItemRepository.countByStatus(STATUS_DEAD_LETTERED);
    long excess = deadLetteredCount - outboxProperties.getDeadLetterMaxRows();
    if (excess <= 0) {
      return 0;
    }
    int batchSize = Math.toIntExact(Math.min(excess, Integer.MAX_VALUE));
    List<ScriptWorkItem> oldest =
        workItemRepository.findByStatusOrderByUpdatedAtAscIdAsc(
            STATUS_DEAD_LETTERED, PageRequest.of(0, batchSize));
    workItemRepository.deleteAll(oldest);
    return oldest.size();
  }

  private void cancel(ScriptWorkItem item, String reason, Instant now) {
    item.setStatus(STATUS_CANCELED);
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
