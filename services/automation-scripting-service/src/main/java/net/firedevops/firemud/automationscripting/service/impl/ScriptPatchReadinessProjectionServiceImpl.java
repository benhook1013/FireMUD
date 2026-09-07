package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchReadinessProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchReadinessProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories are retained only as internal Spring collaborators.")
public class ScriptPatchReadinessProjectionServiceImpl
    implements ScriptPatchReadinessProjectionService {
  private static final List<String> ACTIVE_STATUSES =
      List.of("PENDING_VALIDATION", "ONLOAD_RUNNING");
  private static final List<String> CANCELABLE_ONLOAD_WORK_STATUSES = List.of("PENDING_EVALUATION");

  private final ScriptPatchReadinessProjectionRepository repository;
  private final ScriptWorkItemRepository workItemRepository;

  public ScriptPatchReadinessProjectionServiceImpl(
      ScriptPatchReadinessProjectionRepository repository,
      ScriptWorkItemRepository workItemRepository) {
    this.repository = repository;
    this.workItemRepository = workItemRepository;
  }

  @Override
  @Transactional
  public void beginPatchReadiness(
      String tenantId, String scriptPatchVersion, int affectedScriptCount) {
    requireText(tenantId, "tenant_id");
    requireText(scriptPatchVersion, "script_patch_version");
    Instant now = Instant.now();
    supersedeOlderActivePatches(tenantId, scriptPatchVersion, now);
    ScriptPatchReadinessProjection projection =
        repository
            .findByTenantIdAndScriptPatchVersion(tenantId, scriptPatchVersion)
            .orElseGet(ScriptPatchReadinessProjection::new);
    projection.setTenantId(tenantId);
    projection.setScriptPatchVersion(scriptPatchVersion);
    projection.setSupersededByScriptPatchVersion("");
    if (affectedScriptCount <= 0) {
      projection.setReadinessStatus("READY");
      projection.setStatusReason("no_scripts_in_patch");
    } else {
      projection.setReadinessStatus("ONLOAD_RUNNING");
      projection.setStatusReason("tenant_readiness_running");
    }
    projection.setLastChangedAt(now);
    repository.save(projection);
  }

  @Override
  @Transactional
  public void refreshFromOnLoadWorkItems(String tenantId, String scriptPatchVersion) {
    Optional<ScriptPatchReadinessProjection> maybeProjection =
        repository.findByTenantIdAndScriptPatchVersion(tenantId, scriptPatchVersion);
    if (maybeProjection.isEmpty()) {
      return;
    }
    ScriptPatchReadinessProjection projection = maybeProjection.get();
    if (isTerminal(projection.getReadinessStatus())) {
      return;
    }
    List<ScriptWorkItem> onLoadWorkItems =
        workItemRepository
            .findByTenantIdAndScriptPatchVersion(tenantId, scriptPatchVersion)
            .stream()
            .filter(item -> "onLoad".equals(item.getEventType()))
            .toList();
    if (onLoadWorkItems.isEmpty()) {
      projection.setReadinessStatus("READY");
      projection.setStatusReason("no_scripts_in_patch");
    } else if (onLoadWorkItems.stream().anyMatch(this::isActiveOnLoadStatus)) {
      projection.setReadinessStatus("ONLOAD_RUNNING");
      projection.setStatusReason("tenant_readiness_running");
    } else if (onLoadWorkItems.stream()
        .anyMatch(item -> "DEAD_LETTERED".equals(item.getStatus()))) {
      projection.setReadinessStatus("FAILED");
      projection.setStatusReason(latestCanceledReason(onLoadWorkItems, "onload_failed"));
    } else if (onLoadWorkItems.stream().anyMatch(this::isFailedOnLoadCancellation)) {
      projection.setReadinessStatus("FAILED");
      projection.setStatusReason("onload_budget_exceeded");
    } else if (onLoadWorkItems.stream().anyMatch(item -> "CANCELED".equals(item.getStatus()))) {
      projection.setReadinessStatus("ROLLED_BACK");
      projection.setStatusReason(
          latestCanceledReason(onLoadWorkItems, "tenant_readiness_canceled"));
    } else {
      projection.setReadinessStatus("READY");
      projection.setStatusReason("ready_for_tenant");
    }
    projection.setLastChangedAt(Instant.now());
    repository.save(projection);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ReadinessStatusSummary> getProjection(
      String tenantId, String scriptPatchVersion) {
    return repository
        .findByTenantIdAndScriptPatchVersion(tenantId, scriptPatchVersion)
        .map(this::toSummary);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ReadinessStatusSummary> listProjections(String tenantId) {
    return repository.findByTenantIdOrderByLastChangedAtDesc(tenantId).stream()
        .map(this::toSummary)
        .toList();
  }

  private void supersedeOlderActivePatches(
      String tenantId, String scriptPatchVersion, Instant now) {
    List<ScriptPatchReadinessProjection> active =
        repository.findByTenantIdAndReadinessStatusInOrderByLastChangedAtAsc(
            tenantId, ACTIVE_STATUSES);
    for (ScriptPatchReadinessProjection projection : active) {
      if (scriptPatchVersion.equals(projection.getScriptPatchVersion())) {
        continue;
      }
      projection.setReadinessStatus("SUPERSEDED");
      projection.setStatusReason("superseded_by_newer_patch");
      projection.setSupersededByScriptPatchVersion(scriptPatchVersion);
      projection.setLastChangedAt(now);
      cancelPendingOnLoadWork(tenantId, projection.getScriptPatchVersion(), now);
    }
    if (!active.isEmpty()) {
      repository.saveAll(
          active.stream()
              .filter(projection -> !scriptPatchVersion.equals(projection.getScriptPatchVersion()))
              .toList());
    }
  }

  private void cancelPendingOnLoadWork(String tenantId, String scriptPatchVersion, Instant now) {
    List<ScriptWorkItem> cancelable =
        workItemRepository
            .findByTenantIdAndEventTypeAndStatusInOrderByCreatedAtAscIdAsc(
                tenantId, "onLoad", CANCELABLE_ONLOAD_WORK_STATUSES)
            .stream()
            .filter(item -> scriptPatchVersion.equals(item.getScriptPatchVersion()))
            .toList();
    if (cancelable.isEmpty()) {
      return;
    }
    cancelable.forEach(
        item -> {
          item.setStatus("CANCELED");
          item.setCancelReason("superseded_by_newer_patch");
          item.setUpdatedAt(now);
        });
    workItemRepository.saveAll(cancelable);
  }

  private boolean isActiveOnLoadStatus(ScriptWorkItem item) {
    return switch (item.getStatus()) {
      case "PENDING_EVALUATION", "EVALUATING", "HANDOFF_IN_FLIGHT" -> true;
      default -> false;
    };
  }

  private boolean isFailedOnLoadCancellation(ScriptWorkItem item) {
    return "CANCELED".equals(item.getStatus())
        && "onload_budget_exceeded".equals(item.getCancelReason());
  }

  private static String latestCanceledReason(List<ScriptWorkItem> workItems, String fallback) {
    return workItems.stream()
        .filter(
            item -> "DEAD_LETTERED".equals(item.getStatus()) || "CANCELED".equals(item.getStatus()))
        .sorted(Comparator.comparing(ScriptWorkItem::getUpdatedAt).reversed())
        .map(ScriptWorkItem::getCancelReason)
        .filter(reason -> reason != null && !reason.isBlank())
        .findFirst()
        .orElse(fallback);
  }

  private boolean isTerminal(String readinessStatus) {
    return switch (readinessStatus) {
      case "READY", "FAILED", "ROLLED_BACK", "SUPERSEDED" -> true;
      default -> false;
    };
  }

  private ReadinessStatusSummary toSummary(ScriptPatchReadinessProjection projection) {
    return new ReadinessStatusSummary(
        projection.getTenantId(),
        projection.getScriptPatchVersion(),
        toProtoStatus(projection.getReadinessStatus()),
        projection.getStatusReason(),
        blankToEmpty(projection.getSupersededByScriptPatchVersion()),
        projection.getLastChangedAt().toEpochMilli());
  }

  private static ScriptPatchStatus toProtoStatus(String status) {
    return switch (blankToEmpty(status)) {
      case "READY" -> ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY;
      case "FAILED" -> ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED;
      case "ROLLED_BACK" -> ScriptPatchStatus.SCRIPT_PATCH_STATUS_ROLLED_BACK;
      case "PENDING_VALIDATION" -> ScriptPatchStatus.SCRIPT_PATCH_STATUS_PENDING_VALIDATION;
      case "ONLOAD_RUNNING" -> ScriptPatchStatus.SCRIPT_PATCH_STATUS_ONLOAD_RUNNING;
      case "SUPERSEDED" -> ScriptPatchStatus.SCRIPT_PATCH_STATUS_SUPERSEDED;
      default -> ScriptPatchStatus.SCRIPT_PATCH_STATUS_UNSPECIFIED;
    };
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }
}
