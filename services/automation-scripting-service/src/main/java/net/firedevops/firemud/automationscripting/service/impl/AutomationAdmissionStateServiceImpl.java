package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionState;
import net.firedevops.firemud.automationscripting.repository.AutomationAdmissionStateRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repository is an internal Spring collaborator.")
public class AutomationAdmissionStateServiceImpl implements AutomationAdmissionStateService {
  private static final int ADMISSION_SCOPE_LOCK_NAMESPACE = 0x41534D44;
  private static final String MODE_NORMAL = "NORMAL";
  private static final String MODE_PAUSED_FOR_ROLLBACK = "PAUSED_FOR_ROLLBACK";

  private final AutomationAdmissionStateRepository repository;
  private final DSLContext dsl;

  public AutomationAdmissionStateServiceImpl(AutomationAdmissionStateRepository repository) {
    this(repository, null);
  }

  @Autowired
  public AutomationAdmissionStateServiceImpl(
      AutomationAdmissionStateRepository repository, DSLContext dsl) {
    this.repository = repository;
    this.dsl = dsl;
  }

  @Override
  @Transactional
  public AdmissionStateSummary getState(String tenantId, String gameInstanceId, String regionId) {
    String requiredTenantId = requireText(tenantId, "tenant_id");
    String requiredGameInstanceId = requireText(gameInstanceId, "game_instance_id");
    // The first read may create the regional row. It must take the same instance-wide lock as
    // setMode so a concurrent mode mutation cannot be lost while both callers observe no row.
    lockMutationScope(dsl, requiredTenantId, requiredGameInstanceId);
    return toSummary(findOrCreate(requiredTenantId, requiredGameInstanceId, regionId));
  }

  @Override
  @Transactional
  public AdmissionStateSummary setMode(SetAdmissionModeCommand command) {
    String tenantId = requireText(command.tenantId(), "tenant_id");
    String gameInstanceId = requireText(command.gameInstanceId(), "game_instance_id");
    String regionId = normalize(command.regionId());
    String mode = normalizeMode(command.mode());
    lockMutationScope(dsl, tenantId, gameInstanceId);
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

  /** Serializes admission mutations for one game instance across all regional scope rows. */
  static void lockMutationScope(DSLContext dsl, String tenantId, String gameInstanceId) {
    if (dsl == null || dsl.dialect().family() != SQLDialect.POSTGRES) {
      return;
    }
    dsl.execute(
        "select pg_advisory_xact_lock(?, ?)",
        ADMISSION_SCOPE_LOCK_NAMESPACE,
        (tenantId + "\u0000" + gameInstanceId).hashCode());
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
