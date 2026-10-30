package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record FriendLinkDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull String accountId,
    @NotNull String friendAccountId,
    String status,
    Instant createdAt) {}
