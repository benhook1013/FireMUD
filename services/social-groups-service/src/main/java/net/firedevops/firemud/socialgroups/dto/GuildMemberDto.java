package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** DTO for guild member information. */
public record GuildMemberDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long guildId,
    @NotNull Long accountId,
    @NotBlank String role) {}
