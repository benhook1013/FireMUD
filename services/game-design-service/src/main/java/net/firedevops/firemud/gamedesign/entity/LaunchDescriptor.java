package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class LaunchDescriptor {
  public static final String OUTCOME_SUCCESS = "SUCCESS";
  public static final String OUTCOME_FAILED = "FAILED";

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
  private Integer descriptorSchemaVersion;
  private String targetNamespace;
  private String canonicalTenantId;
  private String authoredWorldSourceTenantSlug;
  private String worldSlug;
  private String authoredWorldSourceOperationId;
  private Long authoredWorldSourceGameRowId;
  private String authoredWorldSourceGameTenantKey;
  private String authoredWorldSourceProvenanceKind;
  private String authoredWorldSourceEvidenceDigest;
  private String requestDigest;
  private String resultDigest;
  private String originalRequestJson;
  private String sourceEvidenceJson;
  private String outcomeStatus = OUTCOME_SUCCESS;
  private String failureCode;
  private String failureMessage;
  private LocalDateTime createdAt = LocalDateTime.now();
}
