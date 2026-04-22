package net.firedevops.firemud.gamesession.dto;

import java.time.Instant;
import java.util.List;

public record PreparedVersionUpgradeDto(
    String preparationId,
    String controlPlaneRequestId,
    long tenantId,
    long sourceGameInstanceId,
    long sourceVersionId,
    long targetVersionId,
    String targetLaunchDescriptorId,
    String remapSetId,
    String result,
    List<String> reasons,
    List<String> checkedParticipants,
    Instant checkedAt,
    List<CutoverParticipantCompatibilityDto> participantResults,
    Long executedTargetGameInstanceId,
    Long executedPointerVersion,
    Instant executedAt,
    String executionControlPlaneRequestId) {

  public PreparedVersionUpgradeDto {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
    checkedParticipants =
        checkedParticipants == null ? List.of() : List.copyOf(checkedParticipants);
    participantResults = participantResults == null ? List.of() : List.copyOf(participantResults);
  }
}
