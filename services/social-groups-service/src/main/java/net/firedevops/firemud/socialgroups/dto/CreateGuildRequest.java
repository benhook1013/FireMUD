package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request body for creating a guild. */
public record CreateGuildRequest(
    @NotNull Long tenantId, @NotNull Long ownerAccountId, @NotBlank String name) {}
