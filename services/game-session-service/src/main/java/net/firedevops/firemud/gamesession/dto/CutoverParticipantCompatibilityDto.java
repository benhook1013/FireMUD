package net.firedevops.firemud.gamesession.dto;

import java.util.List;

public record CutoverParticipantCompatibilityDto(
    String participant,
    List<String> stateClassesChecked,
    List<String> checkedFamilies,
    boolean hasS2Rows,
    String result,
    List<String> reasons) {

  public CutoverParticipantCompatibilityDto {
    stateClassesChecked =
        stateClassesChecked == null ? List.of() : List.copyOf(stateClassesChecked);
    checkedFamilies = checkedFamilies == null ? List.of() : List.copyOf(checkedFamilies);
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }
}
