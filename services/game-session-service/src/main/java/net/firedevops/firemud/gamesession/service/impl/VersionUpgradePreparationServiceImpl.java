package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto;
import net.firedevops.firemud.gamesession.entity.PreparedVersionUpgrade;
import net.firedevops.firemud.gamesession.repository.PreparedVersionUpgradeRepository;
import net.firedevops.firemud.gamesession.service.InstanceCutoverCompatibilityService;
import net.firedevops.firemud.gamesession.service.VersionUpgradePreparationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class VersionUpgradePreparationServiceImpl implements VersionUpgradePreparationService {
  private final InstanceCutoverCompatibilityService instanceCutoverCompatibilityService;
  private final PreparedVersionUpgradeRepository preparedVersionUpgradeRepository;
  private final ObjectMapper objectMapper;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected ObjectMapper is framework-managed and retained internally")
  public VersionUpgradePreparationServiceImpl(
      InstanceCutoverCompatibilityService instanceCutoverCompatibilityService,
      PreparedVersionUpgradeRepository preparedVersionUpgradeRepository,
      ObjectMapper objectMapper) {
    this.instanceCutoverCompatibilityService = instanceCutoverCompatibilityService;
    this.preparedVersionUpgradeRepository = preparedVersionUpgradeRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public PreparedVersionUpgradeDto prepareVersionUpgrade(
      long tenantId,
      long sourceGameInstanceId,
      long targetVersionId,
      String controlPlaneRequestId) {
    if (controlPlaneRequestId == null || controlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException("control_plane_request_id is required");
    }
    var existing =
        preparedVersionUpgradeRepository.findByTenantIdAndControlPlaneRequestId(
            tenantId, controlPlaneRequestId);
    if (existing.isPresent()) {
      PreparedVersionUpgrade prepared = existing.get();
      if (!matchesPreparedRequest(prepared, sourceGameInstanceId, targetVersionId)) {
        throw new IllegalArgumentException(
            "control_plane_request_id already used for a different version-upgrade preparation");
      }
      return toDto(prepared);
    }
    var compatibility =
        instanceCutoverCompatibilityService.validateInstanceCutoverCompatibility(
            tenantId, sourceGameInstanceId, targetVersionId);
    PreparedVersionUpgrade prepared = new PreparedVersionUpgrade();
    prepared.setPreparationId("pvu-" + UUID.randomUUID());
    prepared.setControlPlaneRequestId(controlPlaneRequestId);
    prepared.setTenantId(tenantId);
    prepared.setSourceGameInstanceId(sourceGameInstanceId);
    prepared.setSourceVersionId(compatibility.sourceVersionId());
    prepared.setTargetVersionId(compatibility.targetVersionId());
    prepared.setTargetLaunchDescriptorId(compatibility.targetLaunchDescriptorId());
    prepared.setRemapSetId(compatibility.remapSetId());
    prepared.setResult(compatibility.result());
    prepared.setReasonsJson(toJson(compatibility.reasons()));
    prepared.setCheckedParticipantsJson(toJson(compatibility.checkedParticipants()));
    prepared.setParticipantResultsJson(toJson(compatibility.participantResults()));
    prepared.setCheckedAt(compatibility.checkedAt());
    preparedVersionUpgradeRepository.save(prepared);
    return toDto(prepared);
  }

  @Override
  @Transactional(readOnly = true)
  public PreparedVersionUpgradeDto getPreparedVersionUpgrade(long tenantId, String preparationId) {
    if (preparationId == null || preparationId.isBlank()) {
      throw new IllegalArgumentException("preparation_id is required");
    }
    return toDto(requirePreparedVersionUpgradeForTenant(tenantId, preparationId));
  }

  @Override
  @Transactional
  public PreparedVersionUpgradeDto markPreparedVersionUpgradeExecuted(
      long tenantId,
      String preparationId,
      long targetGameInstanceId,
      long executedPointerVersion,
      String executionControlPlaneRequestId) {
    if (preparationId == null || preparationId.isBlank()) {
      throw new IllegalArgumentException("preparation_id is required");
    }
    if (executionControlPlaneRequestId == null || executionControlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException("execution_control_plane_request_id is required");
    }
    PreparedVersionUpgrade prepared =
        requirePreparedVersionUpgradeForTenant(tenantId, preparationId);
    if (prepared.getExecutedAt() != null) {
      if (!matchesPreparedExecution(
          prepared, targetGameInstanceId, executedPointerVersion, executionControlPlaneRequestId)) {
        throw new IllegalArgumentException(
            "prepared version upgrade already recorded for a different cutover execution");
      }
      return toDto(prepared);
    }
    prepared.setExecutedTargetGameInstanceId(targetGameInstanceId);
    prepared.setExecutedPointerVersion(executedPointerVersion);
    prepared.setExecutedAt(java.time.Instant.now());
    prepared.setExecutionControlPlaneRequestId(executionControlPlaneRequestId);
    preparedVersionUpgradeRepository.save(prepared);
    return toDto(prepared);
  }

  private PreparedVersionUpgrade requirePreparedVersionUpgradeForTenant(
      long tenantId, String preparationId) {
    PreparedVersionUpgrade prepared =
        preparedVersionUpgradeRepository
            .findByPreparationId(preparationId)
            .orElseThrow(() -> new IllegalArgumentException("Prepared version upgrade not found"));
    if (!Long.valueOf(tenantId).equals(prepared.getTenantId())) {
      throw new IllegalArgumentException("Prepared version upgrade not found");
    }
    return prepared;
  }

  private boolean matchesPreparedRequest(
      PreparedVersionUpgrade prepared, long sourceGameInstanceId, long targetVersionId) {
    return Long.valueOf(sourceGameInstanceId).equals(prepared.getSourceGameInstanceId())
        && Long.valueOf(targetVersionId).equals(prepared.getTargetVersionId());
  }

  private boolean matchesPreparedExecution(
      PreparedVersionUpgrade prepared,
      long targetGameInstanceId,
      long executedPointerVersion,
      String executionControlPlaneRequestId) {
    return Long.valueOf(targetGameInstanceId).equals(prepared.getExecutedTargetGameInstanceId())
        && Long.valueOf(executedPointerVersion).equals(prepared.getExecutedPointerVersion())
        && executionControlPlaneRequestId.equals(prepared.getExecutionControlPlaneRequestId());
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException ex) {
      throw new IllegalStateException(
          "Failed to serialize version-upgrade preparation payload", ex);
    }
  }

  private PreparedVersionUpgradeDto toDto(PreparedVersionUpgrade prepared) {
    return new PreparedVersionUpgradeDto(
        prepared.getPreparationId(),
        prepared.getControlPlaneRequestId(),
        prepared.getTenantId(),
        prepared.getSourceGameInstanceId(),
        prepared.getSourceVersionId(),
        prepared.getTargetVersionId(),
        prepared.getTargetLaunchDescriptorId(),
        prepared.getRemapSetId(),
        prepared.getResult(),
        fromJson(prepared.getReasonsJson(), new TypeReference<List<String>>() {}),
        fromJson(prepared.getCheckedParticipantsJson(), new TypeReference<List<String>>() {}),
        prepared.getCheckedAt(),
        fromJson(
            prepared.getParticipantResultsJson(),
            new TypeReference<
                List<
                    net.firedevops.firemud.gamesession.dto
                        .CutoverParticipantCompatibilityDto>>() {}),
        prepared.getExecutedTargetGameInstanceId(),
        prepared.getExecutedPointerVersion(),
        prepared.getExecutedAt(),
        prepared.getExecutionControlPlaneRequestId());
  }

  private <T> T fromJson(String json, TypeReference<T> typeReference) {
    try {
      return objectMapper.readValue(json, typeReference);
    } catch (JacksonException ex) {
      throw new IllegalStateException(
          "Failed to deserialize version-upgrade preparation payload", ex);
    }
  }
}
