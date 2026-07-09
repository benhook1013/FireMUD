package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddGuildMemberRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long guildId,
    @NotNull @Positive Long accountId,
    @NotBlank String role) {}
