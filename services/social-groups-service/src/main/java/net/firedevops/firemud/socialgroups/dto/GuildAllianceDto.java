package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record GuildAllianceDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long guildId,
    @NotNull Long allyGuildId,
    Instant createdAt) {}
