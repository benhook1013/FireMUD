package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record WorldEventDto(
    Long id,
    @NotNull Long tenantId,
    Long regionId,
    @NotNull String eventType,
    String eventData,
    LocalDateTime executeAt,
    boolean processed,
    LocalDateTime processedAt) {}
