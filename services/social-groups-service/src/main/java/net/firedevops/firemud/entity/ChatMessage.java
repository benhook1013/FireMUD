package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "chat_messages")
public class ChatMessage {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long senderAccountId;

  @Column(length = 255, nullable = false)
  private String content;

  @Column(nullable = false)
  private Instant timestamp;

  @Column private Long guildId;

  @Column private Long recipientAccountId;
}
