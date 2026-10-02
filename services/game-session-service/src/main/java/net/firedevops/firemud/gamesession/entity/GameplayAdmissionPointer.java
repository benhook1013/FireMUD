package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
public class GameplayAdmissionPointer {
  private Long id;
  private String worldSlug;
  private String worldDisplayName;
  private String realmSlug;
  private String realmDisplayName;
  private Long tenantId;
  private Long gameInstanceId;
  private Long pointerVersion;
  private Long catalogRevision;
  private UUID realmId;
  private UUID playableStateNamespaceId;
  private boolean visible;
  private boolean publicProductionRealm;
  private boolean requiresCharacterSelection;
  private String stateScope;
  private String characterCreationPolicy;
  private String lastUpdatedBy;
  private String lastUpdateReason;
  private Instant createdAt;
  private Instant updatedAt;
}
