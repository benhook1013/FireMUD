package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Request body for creating a guild. */
public record CreateGuildRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long ownerAccountId,
    @NotBlank String name) {}
