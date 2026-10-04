package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddFriendRequest(
    @NotNull @Positive Long tenantId,
    @NotBlank String accountId,
    @NotBlank String friendAccountId) {}
