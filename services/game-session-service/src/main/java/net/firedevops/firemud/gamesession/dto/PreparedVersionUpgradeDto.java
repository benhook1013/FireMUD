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
    List<CutoverParticipantCompatibilityDto> participantResults) {}
