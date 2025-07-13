package net.firedevops.firemud.dto;

import java.time.Instant;

public record ChatMessageDto(
    Long id,
    Long tenantId,
    Long senderAccountId,
    String content,
    Instant timestamp,
    Long guildId,
    Long cityId,
    Long recipientAccountId,
    net.firedevops.firemud.enums.ChatType type) {}
