package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import net.firedevops.firemud.common.security.JwtClaims;

/** Request to obtain a temporary WebRTC token for voice chat. */
public record VoiceTokenRequestDto(
    @NotNull @Positive Long tenantId, @NotNull String accountId, @NotBlank String channelId) {
  public VoiceTokenRequestDto {
    accountId = JwtClaims.requireCanonicalAccountUuid(accountId, "accountId");
  }
}
