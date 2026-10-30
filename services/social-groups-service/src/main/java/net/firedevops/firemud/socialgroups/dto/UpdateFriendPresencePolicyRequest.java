package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import net.firedevops.firemud.common.security.JwtClaims;

public record UpdateFriendPresencePolicyRequest(
    @Positive long tenantId,
    String accountId,
    @NotNull FriendPresenceVisibilityPolicyValue visibilityPolicy) {
  public UpdateFriendPresencePolicyRequest {
    accountId = JwtClaims.requireCanonicalAccountUuid(accountId, "accountId");
  }
}
