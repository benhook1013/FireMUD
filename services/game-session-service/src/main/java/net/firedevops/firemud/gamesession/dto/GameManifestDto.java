package net.firedevops.firemud.gamesession.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GameManifestDto(
    Long id, @NotNull @Size(max = 100) String versionId, @Size(max = 500) String description) {}
