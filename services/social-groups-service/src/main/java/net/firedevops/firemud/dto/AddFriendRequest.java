package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;

public record AddFriendRequest(
    @NotNull Long tenantId, @NotNull Long accountId, @NotNull Long friendAccountId) {}
