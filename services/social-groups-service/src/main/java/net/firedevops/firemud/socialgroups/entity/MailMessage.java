package net.firedevops.firemud.socialgroups.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "mail_messages")
public class MailMessage {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long senderAccountId;

  @Column(nullable = false)
  private Long recipientAccountId;

  @Column(nullable = false, length = 100)
  private String subject;

  @Column(nullable = false, length = 1000)
  private String content;

  @Column(nullable = false)
  private Instant sentAt;

  @Column private Instant readAt;
}
