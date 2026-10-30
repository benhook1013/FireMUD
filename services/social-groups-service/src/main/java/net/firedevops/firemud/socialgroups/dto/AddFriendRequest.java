package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import net.firedevops.firemud.common.security.JwtClaims;

public record AddFriendRequest(
    @NotNull @Positive Long tenantId, @NotNull String accountId, @NotNull String friendAccountId) {
  public AddFriendRequest {
    accountId = JwtClaims.requireCanonicalAccountUuid(accountId, "accountId");
    friendAccountId = JwtClaims.requireCanonicalAccountUuid(friendAccountId, "friendAccountId");
  }
}
