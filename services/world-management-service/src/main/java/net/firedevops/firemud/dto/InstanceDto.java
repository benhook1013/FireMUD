package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record InstanceDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long zoneId,
    Long ownerAccountId,
    LocalDateTime createdAt,
    LocalDateTime expiresAt) {}
