package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.socialgroups.enums.ChatType;

public record SendMessageRequestDto(
    @NotNull @Positive Long tenantId,
    @NotNull String senderAccountId,
    ChatType type,
    String channelId,
    String recipientAccountId,
    @Positive Long guildId,
    @Positive Long cityId,
    @NotBlank String content,
    String effectId) {
  public SendMessageRequestDto {
    senderAccountId = JwtClaims.requireCanonicalAccountUuid(senderAccountId, "senderAccountId");
    recipientAccountId =
        recipientAccountId == null
            ? null
            : JwtClaims.requireCanonicalAccountUuid(recipientAccountId, "recipientAccountId");
  }
}
