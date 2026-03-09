package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GuildStorageItemDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long guildId,
    @NotBlank String itemName,
    int quantity) {}
