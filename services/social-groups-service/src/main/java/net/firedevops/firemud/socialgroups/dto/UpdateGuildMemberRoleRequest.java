package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateGuildMemberRoleRequest(
    @NotNull Long tenantId,
    @NotNull Long guildId,
    @NotNull Long accountId,
    @NotBlank String role) {}
