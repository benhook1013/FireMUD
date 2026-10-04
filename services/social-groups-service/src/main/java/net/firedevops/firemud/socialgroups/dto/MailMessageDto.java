package net.firedevops.firemud.socialgroups.dto;

import java.time.Instant;

public record MailMessageDto(
    Long id,
    Long tenantId,
    String senderAccountId,
    String recipientAccountId,
    String subject,
    String content,
    Instant sentAt,
    Instant readAt) {}
