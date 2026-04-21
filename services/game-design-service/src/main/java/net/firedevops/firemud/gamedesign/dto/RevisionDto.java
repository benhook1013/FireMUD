package net.firedevops.firemud.gamedesign.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record RevisionDto(
    Long id,
    @NotNull @Size(max = 36) String tenantId,
    @NotNull Long versionId,
    @NotNull Long authorAccountId,
    @NotNull @Size(min = 1) String data,
    @NotNull @Size(min = 1, max = 64) String revisionKind,
    String logicalRevisionId,
    WorldDesignMutationRevisionDto worldDesignMutation,
    AppliedWorldDesignMutationDto appliedWorldDesignMutation,
    LocalDateTime createdAt) {}
