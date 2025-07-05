package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record RevisionDto(
        Long id,
        @NotNull Long tenantId,
        @NotNull Long gameId,
        @NotNull Long authorAccountId,
        @NotNull @Size(min = 1) String data,
        LocalDateTime createdAt
) {}
