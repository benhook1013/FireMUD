package net.firedevops.firemud.worldmanagement.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorldInstance {
  private Long id;
  private Long tenantId;
  private Long gameInstanceId;
  private Long gameTemplateId;
  private String controlPlaneRequestId;
  private String launchDescriptorId;
  private Long versionId;
  private String scriptPatchVersion;
  private String runtimeFlagsJson;
  private String generationConfigRevision;
  private Long releaseBundleId;
  private String publishedReleaseBundleRef;
  private Long versionStateEpoch;
  private String remapSetId;
  private Long lifecycleEpoch = 1L;
  private String status;
  private String failureReason;
  private String terminationRequestId;
  private Instant terminatedAt;
  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();
  private Long rowVersion;

  void touchUpdatedAt() {
    updatedAt = Instant.now();
  }
}
