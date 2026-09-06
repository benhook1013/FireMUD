package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class GameInstance {
  private Long id;
  private Long tenantId;
  private String runtimeVersion;
  private String scriptPatchVersion;
  private Long scriptPinEpoch;
  private Long gameTemplateId;
  private String launchDescriptorId;
  private Long versionId;
  private Long releaseBundleId;
  private Long versionStateEpoch;
  private String generationConfigRevision;
  private String remapSetId;
  private Instant scriptPatchPinnedAt;
  private String scriptPatchPinnedBy;
  private String scriptPatchPinnedReason;
  private String scriptPatchPinnedControlPlaneRequestId;
  private Long ownerAccountId;
  private String status;
  private Long rowVersion;
}
