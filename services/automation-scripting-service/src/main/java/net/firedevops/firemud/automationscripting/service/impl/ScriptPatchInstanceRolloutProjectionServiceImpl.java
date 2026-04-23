package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchInstanceRolloutProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are internal Spring dependencies")
public class ScriptPatchInstanceRolloutProjectionServiceImpl
    implements ScriptPatchInstanceRolloutProjectionService {
  private final ScriptPatchInstanceRolloutProjectionRepository repository;
  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptPatchPinProjectionService pinProjectionService;

  public ScriptPatchInstanceRolloutProjectionServiceImpl(
      ScriptPatchInstanceRolloutProjectionRepository repository,
      ScriptWorkItemRepository workItemRepository,
      ScriptPatchPinProjectionService pinProjectionService) {
    this.repository = repository;
    this.workItemRepository = workItemRepository;
    this.pinProjectionService = pinProjectionService;
  }

  @Override
  @Transactional
  public Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> getProjection(
      String tenantId, String gameInstanceId, String scriptPatchVersion) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    requireText(scriptPatchVersion, "script_patch_version");
    refreshProjection(tenantId, gameInstanceId, scriptPatchVersion);
    return repository
        .findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            tenantId, gameInstanceId, scriptPatchVersion)
        .map(this::toSummary);
  }

  @Override
  @Transactional
  public List<ScriptWorkItemService.PatchInstanceRolloutSummary> listProjections(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs) {
    requireText(tenantId, "tenant_id");
    if (gameInstanceId != null && !gameInstanceId.isBlank()) {
      refreshForInstance(tenantId, gameInstanceId);
    }
    List<ScriptPatchInstanceRolloutProjection> projections =
        (gameInstanceId == null || gameInstanceId.isBlank())
            ? repository
                .findByTenantIdOrderByLastChangedAtDescGameInstanceIdAscScriptPatchVersionAsc(
                    tenantId)
            : repository
                .findByTenantIdAndGameInstanceIdOrderByLastChangedAtDescScriptPatchVersionAsc(
                    tenantId, gameInstanceId);
    return projections.stream()
        .filter(
            projection ->
                scriptPatchVersion == null
                    || scriptPatchVersion.isBlank()
                    || projection.getScriptPatchVersion().equals(scriptPatchVersion))
        .map(this::toSummary)
        .filter(
            summary ->
                rolloutStatus
                        == ScriptPatchInstanceRolloutStatus
                            .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_UNSPECIFIED
                    || summary.rolloutStatus() == rolloutStatus)
        .filter(summary -> changedAfterMs <= 0 || summary.lastChangedAtMs() > changedAfterMs)
        .filter(summary -> changedBeforeMs <= 0 || summary.lastChangedAtMs() < changedBeforeMs)
        .sorted(
            Comparator.comparingLong(
                    ScriptWorkItemService.PatchInstanceRolloutSummary::lastChangedAtMs)
                .reversed()
                .thenComparing(ScriptWorkItemService.PatchInstanceRolloutSummary::gameInstanceId)
                .thenComparing(
                    ScriptWorkItemService.PatchInstanceRolloutSummary::scriptPatchVersion))
        .toList();
  }

  @Override
  @Transactional
  public void refreshForWorkItem(ScriptWorkItem workItem) {
    if (workItem == null) {
      return;
    }
    refreshProjection(
        workItem.getTenantId(), workItem.getGameInstanceId(), workItem.getScriptPatchVersion());
  }

  @Override
  @Transactional
  public void refreshForInstance(String tenantId, String gameInstanceId) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    Set<String> patchVersions = new LinkedHashSet<>();
    workItemRepository
        .findDistinctInstancePatchPairs(tenantId, gameInstanceId, "")
        .forEach(pair -> patchVersions.add(pair.getScriptPatchVersion()));
    pinProjectionService
        .getPinConvergence(tenantId, gameInstanceId)
        .summary()
        .ifPresent(
            pin -> {
              if (!pin.observedPinnedScriptPatchVersion().isBlank()) {
                patchVersions.add(pin.observedPinnedScriptPatchVersion());
              }
            });
    patchVersions.forEach(
        patchVersion -> refreshProjection(tenantId, gameInstanceId, patchVersion));
  }

  private void refreshProjection(
      String tenantId, String gameInstanceId, String scriptPatchVersion) {
    if (tenantId == null
        || tenantId.isBlank()
        || gameInstanceId == null
        || gameInstanceId.isBlank()
        || scriptPatchVersion == null
        || scriptPatchVersion.isBlank()) {
      return;
    }
    Instant now = Instant.now();
    List<ScriptWorkItem> workItems =
        workItemRepository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            tenantId, gameInstanceId, scriptPatchVersion);
    Optional<ScriptPatchPinProjectionService.PinConvergenceSummary> pin =
        pinProjectionService.getPinConvergence(tenantId, gameInstanceId).summary();
    Optional<ProjectionSnapshot> snapshot =
        buildSnapshot(tenantId, gameInstanceId, scriptPatchVersion, workItems, pin, now);
    Optional<ScriptPatchInstanceRolloutProjection> existing =
        repository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            tenantId, gameInstanceId, scriptPatchVersion);
    if (snapshot.isEmpty()) {
      existing.ifPresent(repository::delete);
      return;
    }
    ScriptPatchInstanceRolloutProjection projection =
        existing.orElseGet(ScriptPatchInstanceRolloutProjection::new);
    projection.setTenantId(tenantId);
    projection.setGameInstanceId(gameInstanceId);
    projection.setScriptPatchVersion(scriptPatchVersion);
    projection.setRolloutStatus(snapshot.get().rolloutStatus().name());
    projection.setStatusReason(snapshot.get().statusReason());
    projection.setLastChangedAt(Instant.ofEpochMilli(snapshot.get().lastChangedAtMs()));
    projection.setProjectionRefreshedAt(now);
    repository.save(projection);
  }

  private Optional<ProjectionSnapshot> buildSnapshot(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      List<ScriptWorkItem> workItems,
      Optional<ScriptPatchPinProjectionService.PinConvergenceSummary> pin,
      Instant now) {
    if (pin.isPresent()) {
      ScriptPatchPinProjectionService.PinConvergenceSummary runtime = pin.get();
      if (scriptPatchVersion.equals(runtime.observedPinnedScriptPatchVersion())) {
        return Optional.of(
            new ProjectionSnapshot(
                ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED,
                "runtime_pin_matches_patch",
                maxLastChangedAtMs(workItems, runtime.observedAtMs(), now)));
      }
      if (!workItems.isEmpty()) {
        return Optional.of(
            new ProjectionSnapshot(
                ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK,
                "runtime_pin_differs_from_patch",
                maxLastChangedAtMs(workItems, runtime.observedAtMs(), now)));
      }
      return Optional.empty();
    }
    if (workItems.isEmpty()) {
      return Optional.empty();
    }
    long lastChangedAtMs = maxWorkItemUpdatedAtMs(workItems);
    return Optional.of(
        new ProjectionSnapshot(
            localFallbackRolloutStatus(workItems), "projection_lag_exceeded", lastChangedAtMs));
  }

  private ScriptWorkItemService.PatchInstanceRolloutSummary toSummary(
      ScriptPatchInstanceRolloutProjection projection) {
    Instant now = Instant.now();
    long projectionAsOfMs = projection.getProjectionRefreshedAt().toEpochMilli();
    long projectionLagMs = Math.max(0L, now.toEpochMilli() - projectionAsOfMs);
    boolean projectionStale = projectionLagMs >= 5_000L;
    return new ScriptWorkItemService.PatchInstanceRolloutSummary(
        projection.getTenantId(),
        projection.getGameInstanceId(),
        projection.getScriptPatchVersion(),
        ScriptPatchInstanceRolloutStatus.valueOf(projection.getRolloutStatus()),
        projectionStale ? "projection_lag_exceeded" : projection.getStatusReason(),
        projection.getLastChangedAt().toEpochMilli(),
        projectionAsOfMs,
        projectionLagMs,
        projectionStale);
  }

  private static ScriptPatchInstanceRolloutStatus localFallbackRolloutStatus(
      List<ScriptWorkItem> workItems) {
    if (workItems.stream().allMatch(item -> "CANCELED".equals(item.getStatus()))) {
      return ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK;
    }
    return ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED;
  }

  private static long maxLastChangedAtMs(
      List<ScriptWorkItem> workItems, long runtimeChangedAtMs, Instant fallbackNow) {
    long workItemChangedAtMs = workItems.isEmpty() ? 0L : maxWorkItemUpdatedAtMs(workItems);
    long effectiveRuntimeChangedAtMs =
        runtimeChangedAtMs > 0 ? runtimeChangedAtMs : fallbackNow.toEpochMilli();
    return Math.max(workItemChangedAtMs, effectiveRuntimeChangedAtMs);
  }

  private static long maxWorkItemUpdatedAtMs(List<ScriptWorkItem> workItems) {
    return workItems.stream()
        .map(ScriptWorkItem::getUpdatedAt)
        .max(Comparator.naturalOrder())
        .orElse(Instant.EPOCH)
        .toEpochMilli();
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }

  private record ProjectionSnapshot(
      ScriptPatchInstanceRolloutStatus rolloutStatus, String statusReason, long lastChangedAtMs) {}
}
