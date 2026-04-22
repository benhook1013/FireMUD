package net.firedevops.firemud.gamesession.dto;

import java.time.Instant;
import java.util.List;

public record InstanceCutoverCompatibilityDto(
    long sourceVersionId,
    long targetVersionId,
    String targetLaunchDescriptorId,
    String result,
    List<String> reasons,
    List<String> checkedParticipants,
    Instant checkedAt,
    String remapSetId,
    List<CutoverParticipantCompatibilityDto> participantResults) {

  public InstanceCutoverCompatibilityDto {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
    checkedParticipants =
        checkedParticipants == null ? List.of() : List.copyOf(checkedParticipants);
    participantResults = participantResults == null ? List.of() : List.copyOf(participantResults);
  }
}
