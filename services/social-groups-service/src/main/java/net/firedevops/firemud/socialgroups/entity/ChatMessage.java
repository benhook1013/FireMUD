package net.firedevops.firemud.socialgroups.entity;

import java.time.Instant;
import lombok.Data;
import net.firedevops.firemud.socialgroups.enums.ChatType;

@Data
public class ChatMessage {
  private Long id;
  private Long tenantId;
  private String senderAccountId;
  private String content;
  private Instant timestamp;

  private Long guildId;

  private Long cityId;

  private String recipientAccountId;
  private String effectId;
  private ChatType type;
}
