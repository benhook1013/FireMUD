package net.firedevops.firemud.entitymanagement.dto;

import java.util.List;

public record EntityUpgradeValidationResultDto(
    List<String> stateClassesChecked,
    List<String> checkedFamilies,
    boolean hasS2Rows,
    String result,
    boolean remapSetRequired,
    List<String> reasons,
    String remapSetId) {
  public EntityUpgradeValidationResultDto {
    stateClassesChecked =
        stateClassesChecked == null ? List.of() : List.copyOf(stateClassesChecked);
    checkedFamilies = checkedFamilies == null ? List.of() : List.copyOf(checkedFamilies);
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }
}
