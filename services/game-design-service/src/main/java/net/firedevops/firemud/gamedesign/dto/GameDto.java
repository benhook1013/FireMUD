package net.firedevops.firemud.gamedesign.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GameDto(
    Long id,
    @NotNull @Size(max = 36) String tenantId,
    @NotNull @Size(max = 100) String name,
    String description) {}
