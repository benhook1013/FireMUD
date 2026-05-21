package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class LaunchDescriptor {
  private Long id;
  private String launchDescriptorId;
  private String tenantId;
  private Long gameTemplateId;
  private String controlPlaneRequestId;
  private String requestHash;
  private Long versionId;
  private String scriptPatchVersion;
  private String runtimeFlagsJson;
  private String generationConfigRevision;
  private Long versionStateEpoch;
  private Long releaseBundleId;
  private String publishedReleaseBundleRef;
  private String remapSetId;
  private LocalDateTime createdAt = LocalDateTime.now();
}
