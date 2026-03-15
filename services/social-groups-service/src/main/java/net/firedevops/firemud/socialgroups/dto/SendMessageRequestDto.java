package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import net.firedevops.firemud.socialgroups.enums.ChatType;

public record SendMessageRequestDto(
    @NotNull Long tenantId,
    @NotNull Long senderAccountId,
    ChatType type,
    String channelId,
    Long recipientAccountId,
    Long guildId,
    Long cityId,
    @NotBlank String content) {}
