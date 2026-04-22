package net.firedevops.firemud.worldmanagement.dto;

public record WorldDesignMutationResultDto(
    String result,
    long tenantId,
    long versionId,
    long aggregateId,
    long draftRevisionEpoch,
    Long draftScopeRevisionEpoch) {}
