package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record FriendLinkDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long accountId,
    @NotNull Long friendAccountId,
    String status,
    Instant createdAt) {}
