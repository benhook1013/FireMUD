package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import net.firedevops.firemud.common.security.JwtClaims;

/** Request body for creating a guild. */
public record CreateGuildRequest(
    @NotNull @Positive Long tenantId, @NotNull String ownerAccountId, @NotBlank String name) {
  public CreateGuildRequest {
    ownerAccountId = JwtClaims.requireCanonicalAccountUuid(ownerAccountId, "ownerAccountId");
  }
}
