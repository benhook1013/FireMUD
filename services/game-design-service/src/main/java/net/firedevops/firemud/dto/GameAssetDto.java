package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record GameAssetDto(
    Long id,
    @NotNull @Size(max = 36) String tenantId,
    @NotNull @Size(max = 255) String fileName,
    @NotNull @Size(max = 100) String contentType,
    byte[] data,
    LocalDateTime createdAt) {}
