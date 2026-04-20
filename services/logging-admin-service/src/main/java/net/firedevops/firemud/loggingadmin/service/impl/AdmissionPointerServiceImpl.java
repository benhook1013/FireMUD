package net.firedevops.firemud.loggingadmin.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.AdmissionPointerDto;
import net.firedevops.firemud.loggingadmin.dto.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.loggingadmin.dto.SetAdmissionPointerRequest;
import net.firedevops.firemud.loggingadmin.service.AdmissionPointerService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdmissionPointerServiceImpl implements AdmissionPointerService {
  private static final Logger logger = LoggingUtil.getLogger(AdmissionPointerServiceImpl.class);

  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public AdmissionPointerServiceImpl(GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
  }

  @Override
  @Timed(value = "loggingadmin.admissionPointer.list")
  public List<AdmissionPointerDto> listPointers() {
    ListAdmissionPointersResponse response = gameSessionControlPlaneClient.listAdmissionPointers();
    requireNoError(response.getError());
    return response.getPointersList().stream()
        .filter(this::hasTenantAccess)
        .map(this::toDto)
        .toList();
  }

  @Override
  @Timed(value = "loggingadmin.admissionPointer.audit")
  public List<AdmissionPointerDto> listPointerAudit(String worldSlug, String realmSlug) {
    ListAdmissionPointerAuditResponse response =
        gameSessionControlPlaneClient.listAdmissionPointerAudit(worldSlug, realmSlug);
    requireNoError(response.getError());
    if (response.getAuditCount() == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Admission pointer audit not found");
    }
    AdmissionPointerControlPlaneEntry first = response.getAudit(0);
    SessionContext.requireTenantAccess(parseLong(first.getTenantId(), "tenant_id"));
    return response.getAuditList().stream().map(this::toDto).toList();
  }

  @Override
  @Timed(value = "loggingadmin.admissionPointer.set")
  public AdmissionPointerDto setPointer(SetAdmissionPointerRequest request) {
    String actorPrincipal = resolveActorPrincipal();
    String controlPlaneRequestId =
        request.controlPlaneRequestId() == null || request.controlPlaneRequestId().isBlank()
            ? UUID.randomUUID().toString()
            : request.controlPlaneRequestId();
    net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest.Builder builder =
        net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest.newBuilder()
            .setWorldSlug(request.worldSlug())
            .setWorldDisplayName(request.worldDisplayName())
            .setRealmSlug(request.realmSlug())
            .setRealmDisplayName(request.realmDisplayName())
            .setTenantId(Long.toString(request.tenantId()))
            .setGameInstanceId(Long.toString(request.gameInstanceId()))
            .setVisible(request.visible())
            .setRequiresCharacterSelection(request.requiresCharacterSelection())
            .setStateScope(request.stateScope())
            .setCharacterCreationPolicy(request.characterCreationPolicy())
            .setActorPrincipal(actorPrincipal)
            .setReason(request.reason() == null ? "" : request.reason())
            .setControlPlaneRequestId(controlPlaneRequestId);
    if (request.expectedPointerVersion() != null) {
      builder.setExpectedPointerVersion(request.expectedPointerVersion());
    }
    if (request.preparedVersionUpgradeId() != null
        && !request.preparedVersionUpgradeId().isBlank()) {
      builder.setPreparedVersionUpgradeId(request.preparedVersionUpgradeId());
    }
    SetAdmissionPointerResponse response =
        gameSessionControlPlaneClient.setAdmissionPointer(builder.build());
    requireNoError(response.getError());
    logger.info(
        "Updated admission pointer {}:{} to tenant {} instance {}",
        request.worldSlug(),
        request.realmSlug(),
        request.tenantId(),
        request.gameInstanceId());
    return toDto(response.getPointer());
  }

  @Override
  @Timed(value = "loggingadmin.admissionPointer.executePreparedCutover")
  public AdmissionPointerDto executePreparedVersionCutover(
      ExecutePreparedVersionCutoverRequest request) {
    String actorPrincipal = resolveActorPrincipal();
    String controlPlaneRequestId =
        request.controlPlaneRequestId() == null || request.controlPlaneRequestId().isBlank()
            ? UUID.randomUUID().toString()
            : request.controlPlaneRequestId();
    net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverRequest.Builder builder =
        net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverRequest.newBuilder()
            .setWorldSlug(request.worldSlug())
            .setRealmSlug(request.realmSlug())
            .setTenantId(Long.toString(request.tenantId()))
            .setTargetGameInstanceId(Long.toString(request.targetGameInstanceId()))
            .setPreparedVersionUpgradeId(request.preparedVersionUpgradeId())
            .setActorPrincipal(actorPrincipal)
            .setReason(request.reason() == null ? "" : request.reason())
            .setControlPlaneRequestId(controlPlaneRequestId);
    if (request.expectedPointerVersion() != null) {
      builder.setExpectedPointerVersion(request.expectedPointerVersion());
    }
    ExecutePreparedVersionCutoverResponse response =
        gameSessionControlPlaneClient.executePreparedVersionCutover(builder.build());
    requireNoError(response.getError());
    logger.info(
        "Executed prepared version cutover {}:{} to tenant {} instance {} using preparation {}",
        request.worldSlug(),
        request.realmSlug(),
        request.tenantId(),
        request.targetGameInstanceId(),
        request.preparedVersionUpgradeId());
    return toDto(response.getPointer());
  }

  private boolean hasTenantAccess(AdmissionPointerControlPlaneEntry entry) {
    return SessionContext.hasTenantAccess(parseLong(entry.getTenantId(), "tenant_id"));
  }

  private AdmissionPointerDto toDto(AdmissionPointerControlPlaneEntry entry) {
    return new AdmissionPointerDto(
        entry.getWorldSlug(),
        entry.getWorldDisplayName(),
        entry.getRealmSlug(),
        entry.getRealmDisplayName(),
        parseLong(entry.getTenantId(), "tenant_id"),
        parseLong(entry.getGameInstanceId(), "game_instance_id"),
        entry.getPointerVersion(),
        entry.getVisible(),
        entry.getRequiresCharacterSelection(),
        entry.getStateScope(),
        entry.getCharacterCreationPolicy(),
        entry.getActorPrincipal(),
        entry.getReason(),
        entry.getControlPlaneRequestId(),
        entry.getPreparedVersionUpgradeId().isBlank() ? null : entry.getPreparedVersionUpgradeId(),
        entry.getOccurredAtMs() <= 0 ? null : Instant.ofEpochMilli(entry.getOccurredAtMs()));
  }

  private void requireNoError(ErrorDetail error) {
    if (error == null || error.getCode().isBlank()) {
      return;
    }
    HttpStatus status =
        switch (error.getCode()) {
          case "INVALID_ARGUMENT" -> HttpStatus.BAD_REQUEST;
          case "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
          case "POINTER_VERSION_MISMATCH" -> HttpStatus.CONFLICT;
          case "CUTOVER_PREPARATION_INVALID" -> HttpStatus.CONFLICT;
          default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    throw new ResponseStatusException(status, error.getMessage());
  }

  private long parseLong(String value, String field) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, field + " was not numeric in control-plane response");
    }
  }

  private String resolveActorPrincipal() {
    String accountId = SessionContext.getAccountId();
    return accountId == null || accountId.isBlank() ? "internal-service" : accountId;
  }
}
