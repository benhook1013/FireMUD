package net.firedevops.firemud.worldmanagement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegionDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String name,
    String weather,
    Integer shardId,
    Long generationSeed,
    String generatorType,
    String generatorParams,
    Double spacingMultiplier) {}
