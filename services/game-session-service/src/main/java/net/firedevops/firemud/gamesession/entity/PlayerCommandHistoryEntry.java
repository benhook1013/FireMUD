package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

/** Stores accepted player command text for durable command-history retrieval. */
@Data
public class PlayerCommandHistoryEntry {
  private Long id;
  private Long tenantId;
  private Long gameInstanceId;
  private Long characterId;
  private String commandText;
  private Instant acceptedAt;
}
