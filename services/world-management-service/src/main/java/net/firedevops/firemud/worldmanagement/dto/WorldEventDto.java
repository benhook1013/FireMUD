package net.firedevops.firemud.worldmanagement.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record WorldEventDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long gameInstanceId,
    Long regionId,
    @NotNull String eventType,
    String eventData,
    LocalDateTime executeAt,
    boolean processed,
    LocalDateTime processedAt) {}
