package net.firedevops.firemud.automationscripting.service.impl;

import java.time.Instant;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionState;
import net.firedevops.firemud.automationscripting.repository.AutomationAdmissionStateRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomationAdmissionStateServiceImpl implements AutomationAdmissionStateService {
  private static final String MODE_NORMAL = "NORMAL";
  private static final String MODE_PAUSED_FOR_ROLLBACK = "PAUSED_FOR_ROLLBACK";

  private final AutomationAdmissionStateRepository repository;

  public AutomationAdmissionStateServiceImpl(AutomationAdmissionStateRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public AdmissionStateSummary getState(String tenantId, String gameInstanceId, String regionId) {
    return toSummary(findOrCreate(tenantId, gameInstanceId, regionId));
  }

  @Override
  @Transactional
  public AdmissionStateSummary setMode(SetAdmissionModeCommand command) {
    String tenantId = requireText(command.tenantId(), "tenant_id");
    String gameInstanceId = requireText(command.gameInstanceId(), "game_instance_id");
    String regionId = normalize(command.regionId());
    String mode = normalizeMode(command.mode());
    AutomationAdmissionState state = findOrCreate(tenantId, gameInstanceId, regionId);
    Instant now = Instant.now();
    if (!state.getMode().equals(mode)) {
      state.setMode(mode);
      if (MODE_PAUSED_FOR_ROLLBACK.equals(mode)) {
        state.setAdmissionEpoch(state.getAdmissionEpoch() + 1);
      }
    }
    state.setControlPlaneRequestId(normalize(command.controlPlaneRequestId()));
    state.setActorPrincipal(normalize(command.actorPrincipal()));
    state.setReason(normalize(command.reason()));
    state.setUpdatedAt(now);
    return toSummary(repository.save(state));
  }

  private AutomationAdmissionState findOrCreate(
      String tenantId, String gameInstanceId, String regionId) {
    String normalizedRegionId = normalize(regionId);
    return repository
        .findByTenantIdAndGameInstanceIdAndRegionId(tenantId, gameInstanceId, normalizedRegionId)
        .orElseGet(
            () -> {
              AutomationAdmissionState state = new AutomationAdmissionState();
              state.setTenantId(requireText(tenantId, "tenant_id"));
              state.setGameInstanceId(requireText(gameInstanceId, "game_instance_id"));
              state.setRegionId(normalizedRegionId);
              state.setMode(MODE_NORMAL);
              state.setAdmissionEpoch(1L);
              return repository.save(state);
            });
  }

  private static AdmissionStateSummary toSummary(AutomationAdmissionState state) {
    return new AdmissionStateSummary(
        state.getTenantId(),
        state.getGameInstanceId(),
        state.getRegionId(),
        state.getMode(),
        state.getAdmissionEpoch(),
        blankToEmpty(state.getControlPlaneRequestId()),
        blankToEmpty(state.getActorPrincipal()),
        blankToEmpty(state.getReason()),
        state.getUpdatedAt().toEpochMilli());
  }

  private static String normalizeMode(String mode) {
    String normalized = requireText(mode, "mode");
    if (MODE_NORMAL.equals(normalized) || MODE_PAUSED_FOR_ROLLBACK.equals(normalized)) {
      return normalized;
    }
    throw new IllegalArgumentException("mode must be NORMAL or PAUSED_FOR_ROLLBACK");
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }
}
