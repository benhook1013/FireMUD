package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** DTO for account-level friend links. */
public record AccountFriendLinkDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long accountId,
    @NotNull Long friendAccountId,
    String status,
    Instant createdAt) {}
