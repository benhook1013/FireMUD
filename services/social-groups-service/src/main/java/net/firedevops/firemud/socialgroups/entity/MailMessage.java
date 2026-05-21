package net.firedevops.firemud.socialgroups.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class MailMessage {
  private Long id;
  private Long tenantId;
  private Long senderAccountId;
  private Long recipientAccountId;
  private String subject;
  private String content;
  private Instant sentAt;

  private Instant readAt;
}
