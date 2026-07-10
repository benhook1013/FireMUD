package net.firedevops.firemud.gamedesign.entity;

import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
public class GameAuthoredHelpTopic {
  private Long id;
  private String tenantId;
  private Long gameTemplateId;
  private String canonicalTopicKey;
  private String title;
  private String body;
  private List<String> aliases = List.of();
  private boolean published;
  private Instant createdAt;
  private Instant updatedAt;
}
