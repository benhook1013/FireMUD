package net.firedevops.firemud.worldmanagement.dto;

import java.util.List;

public record WorldUpgradeValidationResultDto(
    List<String> stateClassesChecked,
    List<String> checkedFamilies,
    boolean hasS2Rows,
    String result,
    boolean remapSetRequired,
    List<String> reasons,
    String remapSetId) {
  public WorldUpgradeValidationResultDto {
    stateClassesChecked = List.copyOf(stateClassesChecked);
    checkedFamilies = List.copyOf(checkedFamilies);
    reasons = List.copyOf(reasons);
  }
}
