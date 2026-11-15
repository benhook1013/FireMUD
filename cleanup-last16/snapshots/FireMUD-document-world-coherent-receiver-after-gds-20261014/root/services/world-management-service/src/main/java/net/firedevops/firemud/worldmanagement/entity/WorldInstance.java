package net.firedevops.firemud.worldmanagement.entity;

import java.time.Instant;
import java.util.UUID;
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
  private UUID canonicalGameInstanceId;
  private String canonicalAuthorityKind;
  private String canonicalTargetNamespace;
  private UUID canonicalTenantId;
  private String canonicalWorldSlug;
  private UUID playableStateNamespaceId;
  private String playableStateScope;
  private Boolean publicProduction;
  private UUID canonicalRealmId;
  private Long catalogRevision;
  private UUID canonicalLaunchBindingOperationId;
  private String fixtureProvenanceDigest;
  private Long gameSessionTenantId;
  private Long gameSessionGameInstanceId;
  private Long gameDesignVersionId;
  private String runOwnedFixtureBindingSha256;
  private Long runOwnedFixturePreparingLifecycleEpoch;
  private Long lifecycleEpoch = 1L;
  private String status;
  private String failureReason;
  private String terminationRequestId;
  private Instant terminatedAt;
  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();
  private Long rowVersion = 0L;

  void touchUpdatedAt() {
    updatedAt = Instant.now();
  }
}
