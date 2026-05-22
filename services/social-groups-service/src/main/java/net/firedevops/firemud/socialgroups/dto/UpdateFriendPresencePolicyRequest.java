package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateFriendPresencePolicyRequest(
    long tenantId, long accountId, @NotNull FriendPresenceVisibilityPolicyValue visibilityPolicy) {}
