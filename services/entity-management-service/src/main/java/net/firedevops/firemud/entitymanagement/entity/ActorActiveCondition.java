package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@Table(name = "actor_active_conditions")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ActorActiveCondition {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @EqualsAndHashCode.Include
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private String gameInstanceId;

  @Column(nullable = false)
  private Long characterId;

  @Column(nullable = false, length = 120)
  private String conditionKey;

  @Column(nullable = false)
  private Integer stackCount = 1;

  @Column(nullable = false, length = 64)
  private String sourceType;

  @Column(length = 160)
  private String sourceId;

  @Column(nullable = false)
  private Instant startedAt = Instant.now();

  private Instant expiresAt;

  @Column(columnDefinition = "TEXT")
  private String effectPayloadJson;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant updatedAt = Instant.now();

  @Version private int version;
}
