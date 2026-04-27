package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "gameplay_admission_pointer",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_gameplay_admission_pointer_world_realm",
          columnNames = {"world_slug", "realm_slug"})
    })
public class GameplayAdmissionPointer {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "world_slug", nullable = false, length = 120)
  private String worldSlug;

  @Column(name = "world_display_name", nullable = false, length = 200)
  private String worldDisplayName;

  @Column(name = "realm_slug", nullable = false, length = 120)
  private String realmSlug;

  @Column(name = "realm_display_name", nullable = false, length = 200)
  private String realmDisplayName;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @Column(name = "pointer_version", nullable = false)
  private Long pointerVersion;

  @Column(name = "visible", nullable = false)
  private boolean visible;

  @Column(name = "public_production_realm", nullable = false)
  private boolean publicProductionRealm;

  @Column(name = "requires_character_selection", nullable = false)
  private boolean requiresCharacterSelection;

  @Column(name = "state_scope", nullable = false, length = 32)
  private String stateScope;

  @Column(name = "character_creation_policy", nullable = false, length = 32)
  private String characterCreationPolicy;

  @Column(name = "last_updated_by", nullable = false, length = 200)
  private String lastUpdatedBy;

  @Column(name = "last_update_reason", nullable = false, length = 500)
  private String lastUpdateReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
