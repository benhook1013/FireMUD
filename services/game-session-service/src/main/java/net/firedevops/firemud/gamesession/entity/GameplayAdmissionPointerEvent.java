package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class GameplayAdmissionPointerEvent {
  private Long id;
  private String worldSlug;
  private String realmSlug;
  private String worldDisplayName;
  private String realmDisplayName;
  private Long tenantId;
  private Long gameInstanceId;
  private Long pointerVersion;
  private boolean visible;
  private boolean publicProductionRealm;
  private boolean requiresCharacterSelection;
  private String stateScope;
  private String characterCreationPolicy;
  private String actorPrincipal;
  private String reason;
  private String controlPlaneRequestId;
  private String preparedVersionUpgradeId;
  private Instant occurredAt;
}
