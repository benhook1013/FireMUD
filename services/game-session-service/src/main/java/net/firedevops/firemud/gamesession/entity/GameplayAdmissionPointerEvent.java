package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "gameplay_admission_pointer_event")
public class GameplayAdmissionPointerEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "world_slug", nullable = false, length = 120)
  private String worldSlug;

  @Column(name = "realm_slug", nullable = false, length = 120)
  private String realmSlug;

  @Column(name = "world_display_name", nullable = false, length = 200)
  private String worldDisplayName;

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

  @Column(name = "requires_character_selection", nullable = false)
  private boolean requiresCharacterSelection;

  @Column(name = "state_scope", nullable = false, length = 32)
  private String stateScope;

  @Column(name = "character_creation_policy", nullable = false, length = 32)
  private String characterCreationPolicy;

  @Column(name = "actor_principal", nullable = false, length = 200)
  private String actorPrincipal;

  @Column(name = "reason", nullable = false, length = 500)
  private String reason;

  @Column(name = "control_plane_request_id", nullable = false, length = 120)
  private String controlPlaneRequestId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;
}
