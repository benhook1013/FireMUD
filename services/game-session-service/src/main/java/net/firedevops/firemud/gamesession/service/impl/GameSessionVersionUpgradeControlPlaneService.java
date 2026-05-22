package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.gamesession.service.InstanceCutoverCompatibilityService;
import net.firedevops.firemud.gamesession.service.VersionUpgradePreparationService;
import net.firedevops.firemud.gamesession.v1.CutoverCompatibilityResult;
import net.firedevops.firemud.gamesession.v1.CutoverParticipantResult;
import net.firedevops.firemud.gamesession.v1.GetPreparedVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.PreparedVersionUpgrade;
import net.firedevops.firemud.gamesession.v1.ValidateInstanceCutoverCompatibilityResponse;
import org.springframework.stereotype.Service;

@Service
final class GameSessionVersionUpgradeControlPlaneService {
  private final InstanceCutoverCompatibilityService instanceCutoverCompatibilityService;
  private final VersionUpgradePreparationService versionUpgradePreparationService;

  GameSessionVersionUpgradeControlPlaneService(
      InstanceCutoverCompatibilityService instanceCutoverCompatibilityService,
      VersionUpgradePreparationService versionUpgradePreparationService) {
    this.instanceCutoverCompatibilityService = instanceCutoverCompatibilityService;
    this.versionUpgradePreparationService = versionUpgradePreparationService;
  }

  ValidateInstanceCutoverCompatibilityResponse validateInstanceCutoverCompatibility(
      long tenantId, long sourceGameInstanceId, long targetVersionId) {
    var validation =
        instanceCutoverCompatibilityService.validateInstanceCutoverCompatibility(
            tenantId, sourceGameInstanceId, targetVersionId);
    ValidateInstanceCutoverCompatibilityResponse.Builder response =
        ValidateInstanceCutoverCompatibilityResponse.newBuilder()
            .setResult(toCutoverCompatibilityResult(validation.result()))
            .addAllReasons(validation.reasons())
            .addAllCheckedParticipants(validation.checkedParticipants())
            .setCheckedAtMs(validation.checkedAt().toEpochMilli())
            .addAllParticipantResults(
                validation.participantResults().stream().map(this::toParticipantResult).toList());
    if (validation.remapSetId() != null) {
      response.setRemapSetId(validation.remapSetId());
    }
    return response.build();
  }

  PrepareVersionUpgradeResponse prepareVersionUpgrade(
      long tenantId,
      long sourceGameInstanceId,
      long targetVersionId,
      String controlPlaneRequestId) {
    var preparation =
        versionUpgradePreparationService.prepareVersionUpgrade(
            tenantId, sourceGameInstanceId, targetVersionId, controlPlaneRequestId);
    return PrepareVersionUpgradeResponse.newBuilder()
        .setPreparation(toPreparedVersionUpgrade(preparation))
        .build();
  }

  GetPreparedVersionUpgradeResponse getPreparedVersionUpgrade(long tenantId, String preparationId) {
    var preparation =
        versionUpgradePreparationService.getPreparedVersionUpgrade(tenantId, preparationId);
    return GetPreparedVersionUpgradeResponse.newBuilder()
        .setPreparation(toPreparedVersionUpgrade(preparation))
        .build();
  }

  private CutoverParticipantResult toParticipantResult(
      net.firedevops.firemud.gamesession.dto.CutoverParticipantCompatibilityDto result) {
    return CutoverParticipantResult.newBuilder()
        .setParticipant(result.participant())
        .addAllStateClassesChecked(result.stateClassesChecked())
        .addAllCheckedFamilies(result.checkedFamilies())
        .setHasS2Rows(result.hasS2Rows())
        .setResult(toCutoverCompatibilityResult(result.result()))
        .addAllReasons(result.reasons())
        .build();
  }

  private PreparedVersionUpgrade toPreparedVersionUpgrade(
      net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto preparation) {
    PreparedVersionUpgrade.Builder builder =
        PreparedVersionUpgrade.newBuilder()
            .setPreparationId(preparation.preparationId())
            .setControlPlaneRequestId(preparation.controlPlaneRequestId())
            .setTenantId(Long.toString(preparation.tenantId()))
            .setSourceGameInstanceId(Long.toString(preparation.sourceGameInstanceId()))
            .setSourceVersionId(Long.toString(preparation.sourceVersionId()))
            .setTargetVersionId(Long.toString(preparation.targetVersionId()))
            .setTargetLaunchDescriptorId(preparation.targetLaunchDescriptorId())
            .setResult(toCutoverCompatibilityResult(preparation.result()))
            .addAllReasons(preparation.reasons())
            .addAllCheckedParticipants(preparation.checkedParticipants())
            .setCheckedAtMs(preparation.checkedAt().toEpochMilli())
            .addAllParticipantResults(
                preparation.participantResults().stream().map(this::toParticipantResult).toList());
    if (preparation.remapSetId() != null) {
      builder.setRemapSetId(preparation.remapSetId());
    }
    if (preparation.executedTargetGameInstanceId() != null) {
      builder.setExecutedTargetGameInstanceId(
          Long.toString(preparation.executedTargetGameInstanceId()));
    }
    if (preparation.executedPointerVersion() != null) {
      builder.setExecutedPointerVersion(preparation.executedPointerVersion());
    }
    if (preparation.executedAt() != null) {
      builder.setExecutedAtMs(preparation.executedAt().toEpochMilli());
    }
    if (preparation.executionControlPlaneRequestId() != null) {
      builder.setExecutionControlPlaneRequestId(preparation.executionControlPlaneRequestId());
    }
    return builder.build();
  }

  private CutoverCompatibilityResult toCutoverCompatibilityResult(String result) {
    return switch (result) {
      case "COMPATIBLE" -> CutoverCompatibilityResult.CUTOVER_COMPATIBILITY_RESULT_COMPATIBLE;
      case "INCOMPATIBLE" -> CutoverCompatibilityResult.CUTOVER_COMPATIBILITY_RESULT_INCOMPATIBLE;
      default -> CutoverCompatibilityResult.CUTOVER_COMPATIBILITY_RESULT_UNSPECIFIED;
    };
  }
}
