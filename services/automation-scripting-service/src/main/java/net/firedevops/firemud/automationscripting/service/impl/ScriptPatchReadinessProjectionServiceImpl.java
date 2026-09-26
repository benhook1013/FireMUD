package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchReadinessProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchReadinessProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories are retained only as internal Spring collaborators.")
public class ScriptPatchReadinessProjectionServiceImpl
    implements ScriptPatchReadinessProjectionService {
  private static final int READINESS_SCOPE_LOCK_NAMESPACE = 0x41535052;
  private static final List<String> ACTIVE_STATUSES =
      List.of("PENDING_VALIDATION", "ONLOAD_RUNNING");
  private static final List<String> CANCELABLE_ONLOAD_WORK_STATUSES = List.of("PENDING_EVALUATION");

  private final ScriptPatchReadinessProjectionRepository repository;
  private final ScriptWorkItemRepository workItemRepository;
  private final DSLContext dsl;

  public ScriptPatchReadinessProjectionServiceImpl(
      ScriptPatchReadinessProjectionRepository repository,
      ScriptWorkItemRepository workItemRepository) {
    this(repository, workItemRepository, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ScriptPatchReadinessProjectionServiceImpl(
      ScriptPatchReadinessProjectionRepository repository,
      ScriptWorkItemRepository workItemRepository,
      DSLContext dsl) {
    this.repository = repository;
    this.workItemRepository = workItemRepository;
    this.dsl = dsl;
  }

  @Override
  @Transactional
  public boolean beginPatchReadiness(
      String tenantId, String scriptPatchVersion, List<String> canonicalScriptNames) {
    requireText(tenantId, "tenant_id");
    requireText(scriptPatchVersion, "script_patch_version");
    List<String> scriptSet = canonicalScriptNames(canonicalScriptNames);
    lockTenantMutationScope(tenantId);
    Optional<ScriptPatchReadinessProjection> existing =
        repository.findByTenantIdAndScriptPatchVersion(tenantId, scriptPatchVersion);
    if (existing.isPresent()) {
      requireSameScriptSet(existing.get(), scriptSet);
      // A retry preserves the first admission's script set and generation. Legacy rows without
      // either value fail closed because their original request cannot be reconstructed.
      return false;
    }
    Instant now = Instant.now();
    supersedeOlderActivePatches(tenantId, scriptPatchVersion, now);
    ScriptPatchReadinessProjection projection = new ScriptPatchReadinessProjection();
    projection.setTenantId(tenantId);
    projection.setScriptPatchVersion(scriptPatchVersion);
    projection.setScriptSetManifest(scriptSet);
    projection.setReadinessGeneration(repository.nextReadinessGeneration(tenantId));
    projection.setSupersededByScriptPatchVersion("");
    if (scriptSet.isEmpty()) {
      projection.setReadinessStatus("READY");
      projection.setStatusReason("no_scripts_in_patch");
    } else {
      projection.setReadinessStatus("ONLOAD_RUNNING");
      projection.setStatusReason("tenant_readiness_running");
    }
    projection.setLastChangedAt(now);
    repository.save(projection);
    return true;
  }

  @Override
  @Transactional
  public boolean applyIfCurrent(
      String tenantId,
      String scriptPatchVersion,
      List<String> canonicalScriptNames,
      Consumer<Boolean> downstreamWork) {
    requireText(tenantId, "tenant_id");
    requireText(scriptPatchVersion, "script_patch_version");
    if (downstreamWork == null) {
      throw new IllegalArgumentException("downstream_work_required");
    }
    List<String> scriptSet = canonicalScriptNames(canonicalScriptNames);
    lockTenantMutationScope(tenantId);
    Optional<ScriptPatchReadinessProjection> current =
        findCurrentProjection(tenantId, scriptPatchVersion, scriptSet);
    if (current.isEmpty()) {
      return false;
    }
    ScriptPatchReadinessProjection projection = current.get();
    boolean admitOnLoad =
        switch (projection.getReadinessStatus()) {
          case "PENDING_VALIDATION", "ONLOAD_RUNNING" -> true;
          case "READY" -> false;
          default -> false;
        };
    if (!admitOnLoad && !"READY".equals(projection.getReadinessStatus())) {
      return false;
    }

    // The callback's schedule and ingress collaborators join this transaction. A newer begin
    // cannot supersede this generation until these durable effects commit or roll back together.
    downstreamWork.accept(admitOnLoad);
    projection.setDatabaseDownstreamReconciled(true);
    projection.setLastChangedAt(Instant.now());
    repository.save(projection);
    return true;
  }

  @Override
  @Transactional
  public boolean rebuildRegistryIfCurrent(
      String tenantId,
      String scriptPatchVersion,
      List<String> canonicalScriptNames,
      Runnable registryRebuild) {
    requireText(tenantId, "tenant_id");
    requireText(scriptPatchVersion, "script_patch_version");
    if (registryRebuild == null) {
      throw new IllegalArgumentException("registry_rebuild_required");
    }
    List<String> scriptSet = canonicalScriptNames(canonicalScriptNames);
    lockTenantMutationScope(tenantId);
    Optional<ScriptPatchReadinessProjection> current =
        findCurrentProjection(tenantId, scriptPatchVersion, scriptSet);
    if (current.isEmpty()) {
      return false;
    }
    ScriptPatchReadinessProjection projection = current.get();
    if (!projection.isDatabaseDownstreamReconciled()
        || !("PENDING_VALIDATION".equals(projection.getReadinessStatus())
            || "ONLOAD_RUNNING".equals(projection.getReadinessStatus())
            || "READY".equals(projection.getReadinessStatus()))) {
      return false;
    }
    // This method runs after applyIfCurrent's transaction committed. Keeping the same tenant lock
    // through the in-memory mutation prevents an older registry rebuild from overtaking a newer
    // readiness generation.
    registryRebuild.run();
    return true;
  }

  @Override
  @Transactional
  public void refreshFromOnLoadWorkItems(String tenantId, String scriptPatchVersion) {
    lockTenantMutationScope(tenantId);
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
    } else if (onLoadWorkItems.stream()
        .anyMatch(item -> "DEAD_LETTERED".equals(item.getStatus()))) {
      projection.setReadinessStatus("FAILED");
      projection.setStatusReason(latestDeadLetterReason(onLoadWorkItems, "onload_failed"));
    } else if (onLoadWorkItems.stream().anyMatch(this::isFailedOnLoadCancellation)) {
      projection.setReadinessStatus("FAILED");
      projection.setStatusReason("onload_budget_exceeded");
    } else if (onLoadWorkItems.stream().anyMatch(this::isActiveOnLoadStatus)) {
      projection.setReadinessStatus("ONLOAD_RUNNING");
      projection.setStatusReason("tenant_readiness_running");
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

  private Optional<ScriptPatchReadinessProjection> findCurrentProjection(
      String tenantId, String scriptPatchVersion, List<String> scriptSet) {
    Optional<ScriptPatchReadinessProjection> maybeProjection =
        repository.findByTenantIdAndScriptPatchVersion(tenantId, scriptPatchVersion);
    if (maybeProjection.isEmpty()) {
      return Optional.empty();
    }
    ScriptPatchReadinessProjection projection = maybeProjection.get();
    requireSameScriptSet(projection, scriptSet);
    Long generation = projection.getReadinessGeneration();
    if (generation == null) {
      return Optional.empty();
    }
    return repository
        .findLatestGenerationByTenantId(tenantId)
        .filter(
            latest ->
                generation.equals(latest.getReadinessGeneration())
                    && projection.getId() != null
                    && projection.getId().equals(latest.getId()))
        .map(latest -> projection);
  }

  private static void requireSameScriptSet(
      ScriptPatchReadinessProjection projection, List<String> scriptSet) {
    if (projection.getScriptSetManifest() == null) {
      throw new IllegalStateException("script_patch_script_manifest_unavailable");
    }
    if (!projection.getScriptSetManifest().equals(scriptSet)) {
      throw new IllegalArgumentException("script_patch_manifest_changed");
    }
    if (projection.getReadinessGeneration() == null) {
      throw new IllegalStateException("script_patch_readiness_generation_unavailable");
    }
  }

  private static List<String> canonicalScriptNames(List<String> scriptNames) {
    if (scriptNames == null) {
      throw new IllegalArgumentException("script_set_manifest_required");
    }
    if (scriptNames.stream().anyMatch(name -> name == null || name.isBlank())) {
      throw new IllegalArgumentException("script_set_manifest_contains_blank_name");
    }
    List<String> canonical = scriptNames.stream().sorted().toList();
    if (canonical.stream().distinct().count() != canonical.size()) {
      throw new IllegalArgumentException("script_set_manifest_contains_duplicate_name");
    }
    return canonical;
  }

  /** Serializes readiness projection mutations for one tenant in PostgreSQL transactions. */
  private void lockTenantMutationScope(String tenantId) {
    if (dsl == null) {
      return;
    }
    if (dsl.dialect().family() != SQLDialect.POSTGRES) {
      throw new IllegalStateException("script_patch_readiness_requires_postgres");
    }
    dsl.execute(
        "select pg_advisory_xact_lock(?, ?)", READINESS_SCOPE_LOCK_NAMESPACE, tenantId.hashCode());
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

  private static String latestDeadLetterReason(List<ScriptWorkItem> workItems, String fallback) {
    return workItems.stream()
        .filter(item -> "DEAD_LETTERED".equals(item.getStatus()))
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
