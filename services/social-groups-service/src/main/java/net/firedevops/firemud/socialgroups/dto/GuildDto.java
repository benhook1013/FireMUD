package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Response DTO representing a guild. */
public record GuildDto(
    Long id,
    @NotNull Long tenantId,
    @NotBlank String name,
    @NotNull Long ownerAccountId,
    Instant createdAt) {}
