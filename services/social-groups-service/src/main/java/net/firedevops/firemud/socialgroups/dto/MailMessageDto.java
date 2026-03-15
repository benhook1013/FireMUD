package net.firedevops.firemud.socialgroups.dto;

import java.time.Instant;

public record MailMessageDto(
    Long id,
    Long tenantId,
    Long senderAccountId,
    Long recipientAccountId,
    String subject,
    String content,
    Instant sentAt,
    Instant readAt) {}
