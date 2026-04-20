package net.firedevops.firemud.gamesession.dto;

import java.util.List;

public record CutoverParticipantCompatibilityDto(
    String participant,
    List<String> stateClassesChecked,
    List<String> checkedFamilies,
    boolean hasS2Rows,
    String result,
    List<String> reasons) {}
