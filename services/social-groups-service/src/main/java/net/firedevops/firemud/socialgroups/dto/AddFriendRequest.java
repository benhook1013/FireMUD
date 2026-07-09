package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddFriendRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long accountId,
    @NotNull @Positive Long friendAccountId) {}
