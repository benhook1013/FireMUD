package net.firedevops.firemud.socialgroups.dto;

import java.time.Instant;

public record ChatMessageDto(
    Long id,
    Long tenantId,
    String senderAccountId,
    String content,
    Instant timestamp,
    Long guildId,
    Long cityId,
    String recipientAccountId,
    net.firedevops.firemud.socialgroups.enums.ChatType type,
    String effectId) {}
