package net.firedevops.firemud.gamesession.dto;

import java.time.Instant;
import java.util.List;

public record InstanceCutoverCompatibilityDto(
    String result,
    List<String> reasons,
    List<String> checkedParticipants,
    Instant checkedAt,
    String remapSetId,
    List<CutoverParticipantCompatibilityDto> participantResults) {}
