package net.firedevops.firemud.gamedesign.dto;

public record AppliedWorldDesignMutationDto(
    String result, String aggregateId, Long draftRevisionEpoch, Long draftScopeRevisionEpoch) {}
