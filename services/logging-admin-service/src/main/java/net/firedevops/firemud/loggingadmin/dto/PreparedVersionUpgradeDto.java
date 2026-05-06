package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;
import java.util.List;

public record PreparedVersionUpgradeDto(
    String preparationId,
    long tenantId,
    long sourceGameInstanceId,
    long sourceVersionId,
    long targetVersionId,
    long targetLaunchDescriptorId,
    String remapSetId,
    String result,
    List<String> reasons,
    List<String> checkedParticipants,
    Instant checkedAt,
    List<CutoverParticipantResultDto> participantResults,
    String controlPlaneRequestId,
    Long executedTargetGameInstanceId,
    Long executedPointerVersion,
    Instant executedAt,
    String executionControlPlaneRequestId) {

  public PreparedVersionUpgradeDto {
    reasons = List.copyOf(reasons);
    checkedParticipants = List.copyOf(checkedParticipants);
    participantResults = List.copyOf(participantResults);
  }

  public record CutoverParticipantResultDto(
      String participant,
      String result,
      List<String> reasons,
      List<String> stateClassesChecked,
      List<String> checkedFamilies,
      boolean hasS2Rows) {
    public CutoverParticipantResultDto {
      reasons = List.copyOf(reasons);
      stateClassesChecked = List.copyOf(stateClassesChecked);
      checkedFamilies = List.copyOf(checkedFamilies);
    }
  }
}
