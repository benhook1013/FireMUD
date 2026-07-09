package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateFriendPresencePolicyRequest(
    @Positive long tenantId,
    @Positive long accountId,
    @NotNull FriendPresenceVisibilityPolicyValue visibilityPolicy) {}
