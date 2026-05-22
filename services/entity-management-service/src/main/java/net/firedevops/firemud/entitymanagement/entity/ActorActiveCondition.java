package net.firedevops.firemud.entitymanagement.entity;

import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ActorActiveCondition {
  @EqualsAndHashCode.Include private Long id;
  private Long tenantId;
  private String playableStateKey;
  private Long characterId;
  private String conditionKey;
  private Integer stackCount = 1;
  private String sourceType;
  private String sourceId;
  private Instant startedAt = Instant.now();

  private Instant expiresAt;
  private String effectPayloadJson;
  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();

  private int version;
}
