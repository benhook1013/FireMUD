package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import net.firedevops.firemud.common.security.JwtClaims;

public record AddGuildMemberRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long guildId,
    @NotNull String accountId,
    @NotBlank String role) {
  public AddGuildMemberRequest {
    accountId = JwtClaims.requireCanonicalAccountUuid(accountId, "accountId");
  }
}
