package net.firedevops.firemud.gamedesign.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;

public record GameTemplateDto(
    Long id,
    @NotNull @Size(max = 36) String tenantId,
    @NotNull @Size(max = 100) String name,
    String description,
    @NotNull String config,
    Long defaultVersionId,
    String defaultScriptPatchVersion,
    String defaultRuntimeFlagsJson,
    TemplateReferencePhase templateReferencePhase,
    LocalDateTime createdAt) {}
