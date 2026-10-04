package net.firedevops.firemud.socialgroups.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import net.firedevops.firemud.socialgroups.enums.ChatType;

@Data
public class ChatMessage {
  private Long id;
  private Long tenantId;
  private UUID senderAccountId;
  private String content;
  private Instant timestamp;

  private Long guildId;

  private Long cityId;

  private UUID recipientAccountId;
  private String effectId;
  private ChatType type;
}
