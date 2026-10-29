package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import net.firedevops.firemud.common.security.JwtClaims;

public record SendMailRequest(
    @NotNull @Positive Long tenantId,
    @NotNull String senderAccountId,
    @NotNull String recipientAccountId,
    @NotBlank String subject,
    @NotBlank String content) {
  public SendMailRequest {
    senderAccountId = JwtClaims.requireCanonicalAccountUuid(senderAccountId, "senderAccountId");
    recipientAccountId =
        JwtClaims.requireCanonicalAccountUuid(recipientAccountId, "recipientAccountId");
  }
}
