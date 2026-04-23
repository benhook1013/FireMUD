package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchInstanceRolloutEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchInstanceRolloutProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are internal Spring dependencies")
public class ScriptPatchInstanceRolloutProjectionServiceImpl
    implements ScriptPatchInstanceRolloutProjectionService {
  private final ScriptPatchInstanceRolloutProjectionRepository repository;
  private final ScriptPatchInstanceRolloutEventRepository eventRepository;
  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptPatchPinProjectionService pinProjectionService;

  public ScriptPatchInstanceRolloutProjectionServiceImpl(
      ScriptPatchInstanceRolloutProjectionRepository repository,
      ScriptPatchInstanceRolloutEventRepository eventRepository,
      ScriptWorkItemRepository workItemRepository,
      ScriptPatchPinProjectionService pinProjectionService) {
    this.repository = repository;
    this.eventRepository = eventRepository;
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
  public List<ScriptWorkItemService.PatchInstanceRolloutEventSummary> listEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit) {
    requireText(tenantId, "tenant_id");
    int boundedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
    String rolloutStatusFilter =
        rolloutStatus
                == ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_UNSPECIFIED
            ? ""
            : rolloutStatus.name();
    return eventRepository
        .findEvents(
            tenantId,
            normalize(gameInstanceId),
            normalize(scriptPatchVersion),
            rolloutStatusFilter,
            changedAfterMs <= 0 ? null : Instant.ofEpochMilli(changedAfterMs),
            changedBeforeMs <= 0 ? null : Instant.ofEpochMilli(changedBeforeMs),
            PageRequest.of(0, boundedLimit))
        .stream()
        .map(this::toEventSummary)
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
    Optional<ScriptPatchInstanceRolloutProjection> existing =
        repository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            tenantId, gameInstanceId, scriptPatchVersion);
    Optional<ProjectionSnapshot> snapshot =
        buildSnapshot(
            tenantId,
            gameInstanceId,
            scriptPatchVersion,
            workItems,
            pin,
            existing.map(ScriptPatchInstanceRolloutProjection::getRolloutStatus),
            now);
    if (snapshot.isEmpty()) {
      existing.ifPresent(repository::delete);
      return;
    }
    ScriptPatchInstanceRolloutProjection projection =
        existing.orElseGet(ScriptPatchInstanceRolloutProjection::new);
    boolean appendEvent = shouldAppendEvent(existing, snapshot.get());
    projection.setTenantId(tenantId);
    projection.setGameInstanceId(gameInstanceId);
    projection.setScriptPatchVersion(scriptPatchVersion);
    projection.setRolloutStatus(snapshot.get().rolloutStatus().name());
    projection.setStatusReason(snapshot.get().statusReason());
    projection.setLastChangedAt(Instant.ofEpochMilli(snapshot.get().lastChangedAtMs()));
    projection.setProjectionRefreshedAt(now);
    if (appendEvent) {
      appendEvent(tenantId, gameInstanceId, scriptPatchVersion, snapshot.get(), now);
    }
    repository.save(projection);
  }

  private Optional<ProjectionSnapshot> buildSnapshot(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      List<ScriptWorkItem> workItems,
      Optional<ScriptPatchPinProjectionService.PinConvergenceSummary> pin,
      Optional<String> existingRolloutStatus,
      Instant now) {
    if (pin.isPresent()) {
      ScriptPatchPinProjectionService.PinConvergenceSummary runtime = pin.get();
      if (scriptPatchVersion.equals(runtime.observedPinnedScriptPatchVersion())) {
        ScriptPatchInstanceRolloutStatus rolloutStatus =
            priorRollbackObserved(existingRolloutStatus)
                ? ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_REPINNED
                : ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED;
        String reason =
            rolloutStatus
                    == ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_REPINNED
                ? "runtime_pin_restored_after_rollback"
                : "runtime_pin_matches_patch";
        return Optional.of(
            new ProjectionSnapshot(
                rolloutStatus, reason, maxLastChangedAtMs(workItems, runtime.observedAtMs(), now)));
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

  private ScriptWorkItemService.PatchInstanceRolloutEventSummary toEventSummary(
      ScriptPatchInstanceRolloutEvent event) {
    return new ScriptWorkItemService.PatchInstanceRolloutEventSummary(
        event.getEventId(),
        event.getTenantId(),
        event.getGameInstanceId(),
        event.getScriptPatchVersion(),
        ScriptPatchInstanceRolloutStatus.valueOf(event.getRolloutStatus()),
        event.getStatusReason(),
        event.getObservedAt().toEpochMilli(),
        event.getProjectionRefreshedAt().toEpochMilli());
  }

  private boolean shouldAppendEvent(
      Optional<ScriptPatchInstanceRolloutProjection> existing, ProjectionSnapshot snapshot) {
    if (existing.isEmpty()) {
      return true;
    }
    ScriptPatchInstanceRolloutProjection current = existing.get();
    return !current.getRolloutStatus().equals(snapshot.rolloutStatus().name())
        || !current.getStatusReason().equals(snapshot.statusReason());
  }

  private void appendEvent(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ProjectionSnapshot snapshot,
      Instant projectionRefreshedAt) {
    ScriptPatchInstanceRolloutEvent event = new ScriptPatchInstanceRolloutEvent();
    event.setEventId("spiro-" + UUID.randomUUID());
    event.setTenantId(tenantId);
    event.setGameInstanceId(gameInstanceId);
    event.setScriptPatchVersion(scriptPatchVersion);
    event.setRolloutStatus(snapshot.rolloutStatus().name());
    event.setStatusReason(snapshot.statusReason());
    event.setObservedAt(Instant.ofEpochMilli(snapshot.lastChangedAtMs()));
    event.setProjectionRefreshedAt(projectionRefreshedAt);
    eventRepository.save(event);
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

  private static boolean priorRollbackObserved(Optional<String> existingRolloutStatus) {
    return existingRolloutStatus
        .map(
            status ->
                ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK
                        .name()
                        .equals(status)
                    || ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_REPINNED
                        .name()
                        .equals(status))
        .orElse(false);
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private record ProjectionSnapshot(
      ScriptPatchInstanceRolloutStatus rolloutStatus, String statusReason, long lastChangedAtMs) {}
}
