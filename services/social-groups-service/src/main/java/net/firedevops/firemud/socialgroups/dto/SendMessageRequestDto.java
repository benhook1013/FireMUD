package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import net.firedevops.firemud.socialgroups.enums.ChatType;

public record SendMessageRequestDto(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long senderAccountId,
    ChatType type,
    String channelId,
    @Positive Long recipientAccountId,
    @Positive Long guildId,
    @Positive Long cityId,
    @NotBlank String content,
    String effectId) {}
