package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

@Getter
@Setter
public class PublishedPluginVersion {
  private Long id;
  private String tenantId;
  private String pluginId;
  private String pluginVersionId;
  private Long baseVersionId;
  private VersionLifecycleState publicationState;
  private String abilitySchemaDigest;
  private String bundleDigest;
  private Integer manifestSchemaVersion;
  private String distributionManifestHash;
  private String distributionManifestPath;
  private String signerKeyId;
  private boolean signerRevoked;
  private String componentPolicyDecision;
  private String notes;
  private String statusReason;
  private LocalDateTime lastChangedAt;
}
