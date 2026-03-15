package net.firedevops.firemud.socialgroups.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;
import net.firedevops.firemud.socialgroups.enums.ChatType;

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

  @Column private Long cityId;

  @Column private Long recipientAccountId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ChatType type;
}
