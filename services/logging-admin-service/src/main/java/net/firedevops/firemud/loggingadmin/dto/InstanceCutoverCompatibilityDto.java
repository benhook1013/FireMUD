package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;
import java.util.List;

public record InstanceCutoverCompatibilityDto(
    String result,
    List<String> reasons,
    List<String> checkedParticipants,
    Instant checkedAt,
    String remapSetId,
    List<CutoverParticipantResultDto> participantResults) {
  public InstanceCutoverCompatibilityDto {
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
