package net.firedevops.firemud.socialgroups.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
public class MailMessage {
  private Long id;
  private Long tenantId;
  private UUID senderAccountId;
  private UUID recipientAccountId;
  private String subject;
  private String content;
  private Instant sentAt;

  private Instant readAt;
}
