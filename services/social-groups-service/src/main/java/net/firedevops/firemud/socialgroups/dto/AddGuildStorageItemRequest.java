package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddGuildStorageItemRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long guildId,
    @NotBlank String itemName,
    int quantity) {}
