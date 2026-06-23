package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.VersionUpgradePreparationService;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import org.springframework.stereotype.Service;

@Service
final class GameSessionAdmissionPointerControlPlaneService {
  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final VersionUpgradePreparationService versionUpgradePreparationService;

  GameSessionAdmissionPointerControlPlaneService(
      GameInstanceRepository gameInstanceRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      VersionUpgradePreparationService versionUpgradePreparationService) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.versionUpgradePreparationService = versionUpgradePreparationService;
  }

  ListAdmissionPointersResponse listAdmissionPointers() {
    return ListAdmissionPointersResponse.newBuilder()
        .addAllPointers(
            gameplayAdmissionPointerAuthorityService.listPointers().stream()
                .flatMap(
                    pointer ->
                        gameplayAdmissionPointerAuthorityService
                            .listPointerAudit(
                                pointer.tenantId(), pointer.worldSlug(), pointer.realmSlug())
                            .stream()
                            .limit(1)
                            .map(this::toEntry))
                .toList())
        .build();
  }

  ListAdmissionPointerAuditResponse listAdmissionPointerAudit(
      ListAdmissionPointerAuditRequest request) {
    java.util.List<Long> matchingTenantIds =
        gameplayAdmissionPointerAuthorityService.listPointers().stream()
            .filter(
                pointer ->
                    pointer.worldSlug().equals(request.getWorldSlug())
                        && pointer.realmSlug().equals(request.getRealmSlug()))
            .map(GameplayAdmissionPointerSnapshot::tenantId)
            .distinct()
            .toList();
    if (matchingTenantIds.isEmpty()) {
      throw new IllegalArgumentException("Admission pointer not found");
    }
    if (matchingTenantIds.size() > 1) {
      throw new IllegalArgumentException("Admission pointer selection is ambiguous");
    }
    long tenantId = matchingTenantIds.get(0);
    return ListAdmissionPointerAuditResponse.newBuilder()
        .addAllAudit(
            gameplayAdmissionPointerAuthorityService
                .listPointerAudit(tenantId, request.getWorldSlug(), request.getRealmSlug())
                .stream()
                .map(this::toEntry)
                .toList())
        .build();
  }

  SetAdmissionPointerResponse setAdmissionPointer(
      long tenantId, long targetGameInstanceId, SetAdmissionPointerRequest request) {
    validatePreparedUpgradeForPointerChange(request, tenantId, targetGameInstanceId);
    gameplayAdmissionPointerAuthorityService.upsertPointer(
        new GameplayAdmissionPointerMutation(
            request.getWorldSlug(),
            request.getWorldDisplayName(),
            request.getRealmSlug(),
            request.getRealmDisplayName(),
            tenantId,
            targetGameInstanceId,
            request.getVisible(),
            request.getPublicProductionRealm(),
            request.getRequiresCharacterSelection(),
            request.getStateScope(),
            request.getCharacterCreationPolicy(),
            request.getActorPrincipal(),
            request.getReason(),
            request.getControlPlaneRequestId(),
            request.hasExpectedPointerVersion() ? request.getExpectedPointerVersion() : null,
            normalizeBlank(request.getPreparedVersionUpgradeId())));
    return SetAdmissionPointerResponse.newBuilder()
        .setPointer(latestAuditEntry(tenantId, request.getWorldSlug(), request.getRealmSlug()))
        .build();
  }

  ExecutePreparedVersionCutoverResponse executePreparedVersionCutover(
      long tenantId, long targetGameInstanceId, ExecutePreparedVersionCutoverRequest request) {
    requireText(request.getWorldSlug(), "world_slug is required");
    requireText(request.getRealmSlug(), "realm_slug is required");
    requireText(request.getPreparedVersionUpgradeId(), "prepared_version_upgrade_id is required");
    requireText(request.getActorPrincipal(), "actor_principal is required");
    requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
    GameplayAdmissionPointerSnapshot currentPointer =
        gameplayAdmissionPointerAuthorityService
            .findPointer(tenantId, request.getWorldSlug(), request.getRealmSlug())
            .orElseThrow(() -> new IllegalArgumentException("Admission pointer not found"));
    if (currentPointer.tenantId() != tenantId) {
      throw new IllegalArgumentException("tenant_id does not own admission pointer");
    }
    if (currentPointer.gameInstanceId() == targetGameInstanceId) {
      return ExecutePreparedVersionCutoverResponse.newBuilder()
          .setPointer(
              currentExecutedCutoverEntryIfSameRequest(
                  request.getWorldSlug(),
                  request.getRealmSlug(),
                  tenantId,
                  targetGameInstanceId,
                  request.getPreparedVersionUpgradeId(),
                  request.getControlPlaneRequestId()))
          .build();
    }
    validatePreparedUpgradeForPointerChange(
        request.getWorldSlug(),
        request.getRealmSlug(),
        tenantId,
        targetGameInstanceId,
        request.getPreparedVersionUpgradeId(),
        currentPointer);
    gameplayAdmissionPointerAuthorityService.upsertPointer(
        new GameplayAdmissionPointerMutation(
            currentPointer.worldSlug(),
            currentPointer.worldDisplayName(),
            currentPointer.realmSlug(),
            currentPointer.realmDisplayName(),
            tenantId,
            targetGameInstanceId,
            currentPointer.visible(),
            currentPointer.publicProductionRealm(),
            currentPointer.requiresCharacterSelection(),
            currentPointer.stateScope(),
            currentPointer.characterCreationPolicy(),
            request.getActorPrincipal(),
            request.getReason(),
            request.getControlPlaneRequestId(),
            request.hasExpectedPointerVersion() ? request.getExpectedPointerVersion() : null,
            request.getPreparedVersionUpgradeId()));
    AdmissionPointerControlPlaneEntry entry =
        latestAuditEntry(tenantId, request.getWorldSlug(), request.getRealmSlug());
    versionUpgradePreparationService.markPreparedVersionUpgradeExecuted(
        tenantId,
        request.getPreparedVersionUpgradeId(),
        targetGameInstanceId,
        entry.getPointerVersion(),
        request.getControlPlaneRequestId());
    return ExecutePreparedVersionCutoverResponse.newBuilder().setPointer(entry).build();
  }

  private AdmissionPointerControlPlaneEntry latestAuditEntry(
      long tenantId, String worldSlug, String realmSlug) {
    return gameplayAdmissionPointerAuthorityService
        .listPointerAudit(tenantId, worldSlug, realmSlug)
        .stream()
        .findFirst()
        .map(this::toEntry)
        .orElseThrow(() -> new IllegalStateException("Admission pointer audit missing"));
  }

  private AdmissionPointerControlPlaneEntry toEntry(GameplayAdmissionPointerAuditEntry entry) {
    AdmissionPointerControlPlaneEntry.Builder builder =
        AdmissionPointerControlPlaneEntry.newBuilder()
            .setWorldSlug(entry.worldSlug())
            .setWorldDisplayName(entry.worldDisplayName())
            .setRealmSlug(entry.realmSlug())
            .setRealmDisplayName(entry.realmDisplayName())
            .setTenantId(Long.toString(entry.tenantId()))
            .setGameInstanceId(Long.toString(entry.gameInstanceId()))
            .setPointerVersion(entry.pointerVersion())
            .setVisible(entry.visible())
            .setPublicProductionRealm(entry.publicProductionRealm())
            .setRequiresCharacterSelection(entry.requiresCharacterSelection())
            .setStateScope(entry.stateScope())
            .setCharacterCreationPolicy(entry.characterCreationPolicy())
            .setActorPrincipal(entry.actorPrincipal())
            .setReason(entry.reason())
            .setControlPlaneRequestId(entry.controlPlaneRequestId())
            .setOccurredAtMs(entry.occurredAt().toEpochMilli());
    if (!normalizeBlank(entry.preparedVersionUpgradeId()).isEmpty()) {
      builder.setPreparedVersionUpgradeId(entry.preparedVersionUpgradeId());
    }
    return builder.build();
  }

  private void validatePreparedUpgradeForPointerChange(
      SetAdmissionPointerRequest request, long tenantId, long targetGameInstanceId) {
    GameplayAdmissionPointerSnapshot currentPointer =
        gameplayAdmissionPointerAuthorityService
            .findPointer(tenantId, request.getWorldSlug(), request.getRealmSlug())
            .orElse(null);
    validatePreparedUpgradeForPointerChange(
        request.getWorldSlug(),
        request.getRealmSlug(),
        tenantId,
        targetGameInstanceId,
        request.getPreparedVersionUpgradeId(),
        currentPointer);
  }

  private void validatePreparedUpgradeForPointerChange(
      String worldSlug,
      String realmSlug,
      long tenantId,
      long targetGameInstanceId,
      String preparedVersionUpgradeId,
      GameplayAdmissionPointerSnapshot currentPointer) {
    GameInstance targetInstance = getInstanceOrThrow(targetGameInstanceId);
    if (!Long.valueOf(tenantId).equals(targetInstance.getTenantId())) {
      throw new IllegalArgumentException("tenant_id does not own game_instance_id");
    }
    if (currentPointer == null
        || currentPointer.gameInstanceId() == targetGameInstanceId
        || currentPointer.tenantId() != tenantId) {
      return;
    }
    if (preparedVersionUpgradeId == null || preparedVersionUpgradeId.isBlank()) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id is required when changing admission pointer target");
    }
    PreparedVersionUpgradeDto preparation =
        versionUpgradePreparationService.getPreparedVersionUpgrade(
            tenantId, preparedVersionUpgradeId);
    if (!"COMPATIBLE".equals(preparation.result())) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id must reference a COMPATIBLE preparation");
    }
    if (!Long.valueOf(currentPointer.gameInstanceId()).equals(preparation.sourceGameInstanceId())) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id does not match the current admission-pointer source instance");
    }
    if (!Long.valueOf(targetGameInstanceId).equals(targetInstance.getId())) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id target does not match game_instance_id");
    }
    if (!Long.valueOf(preparation.targetVersionId()).equals(targetInstance.getVersionId())) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id targetVersionId does not match target instance version");
    }
    if (!normalizeBlank(preparation.targetLaunchDescriptorId())
        .equals(normalizeBlank(targetInstance.getLaunchDescriptorId()))) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id targetLaunchDescriptorId does not match target instance");
    }
    if (!normalizeBlank(preparation.remapSetId())
        .equals(normalizeBlank(targetInstance.getRemapSetId()))) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id remapSetId does not match target instance");
    }
  }

  private AdmissionPointerControlPlaneEntry currentExecutedCutoverEntryIfSameRequest(
      String worldSlug,
      String realmSlug,
      long tenantId,
      long targetGameInstanceId,
      String preparedVersionUpgradeId,
      String controlPlaneRequestId) {
    PreparedVersionUpgradeDto preparation =
        versionUpgradePreparationService.getPreparedVersionUpgrade(
            tenantId, preparedVersionUpgradeId);
    if (!Long.valueOf(targetGameInstanceId).equals(preparation.executedTargetGameInstanceId())
        || !controlPlaneRequestId.equals(preparation.executionControlPlaneRequestId())) {
      throw new IllegalArgumentException(
          "target_game_instance_id must differ from the current admission pointer target");
    }
    AdmissionPointerControlPlaneEntry entry = latestAuditEntry(tenantId, worldSlug, realmSlug);
    if (preparation.executedPointerVersion() != null
        && entry.getPointerVersion() != preparation.executedPointerVersion()) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id execution state does not match current admission pointer");
    }
    return entry;
  }

  private GameInstance getInstanceOrThrow(long gameInstanceId) {
    return gameInstanceRepository
        .findById(gameInstanceId)
        .orElseThrow(() -> new IllegalArgumentException("Game instance not found"));
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  private void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }
}
