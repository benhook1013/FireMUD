package net.firedevops.firemud.entitymanagement.entity;

import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ActorResourceState {
  @EqualsAndHashCode.Include private Long id;
  private Long tenantId;
  private String playableStateKey;
  private Long characterId;
  private String statKey;
  private Long currentValue;

  private Long maxValue;

  private Long baseValue;
  private String sourceType = "CHARACTER_BASELINE";
  private String sourceId;
  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();

  private int version;
}
