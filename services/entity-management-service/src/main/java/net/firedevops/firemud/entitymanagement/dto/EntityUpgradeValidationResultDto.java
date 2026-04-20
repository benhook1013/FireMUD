package net.firedevops.firemud.entitymanagement.dto;

import java.util.List;

public record EntityUpgradeValidationResultDto(
    List<String> stateClassesChecked,
    List<String> checkedFamilies,
    boolean hasS2Rows,
    String result,
    boolean remapSetRequired,
    List<String> reasons,
    String remapSetId) {}
