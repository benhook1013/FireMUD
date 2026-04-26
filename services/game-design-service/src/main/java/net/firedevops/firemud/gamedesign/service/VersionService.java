package net.firedevops.firemud.gamedesign.service;

import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedPluginVersionDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

public interface VersionService {
  VersionDto publishVersion(String tenantId, String notes) throws Exception;

  VersionDto publishScriptPatchVersion(
      String tenantId, Long baseVersionId, String scriptPatchVersion, String notes)
      throws Exception;

  VersionDto getPublishedScriptPatchVersion(String tenantId, String scriptPatchVersion);

  PublishedPluginVersionDto uploadPluginBundle(String tenantId, byte[] bundleBytes, String notes);

  PublishedPluginVersionDto publishPluginVersion(
      String tenantId,
      String pluginId,
      String pluginVersionId,
      long baseVersionId,
      String abilitySchemaDigest,
      String bundleDigest,
      int manifestSchemaVersion,
      String distributionManifestHash,
      String distributionManifestPath,
      String signerKeyId,
      boolean signerRevoked,
      String componentPolicyDecision,
      String notes);

  PublishedPluginVersionDto getPublishedPluginVersion(
      String tenantId, String pluginId, String pluginVersionId);

  List<PublishedPluginVersionDto> listPublishedPluginVersions(
      String tenantId,
      String pluginId,
      VersionLifecycleState publicationState,
      LocalDateTime changedAfter,
      LocalDateTime changedBefore,
      int limit);

  List<VersionDto> listVersions(String tenantId);

  DesignControlPlaneDigestDto getDesignControlPlaneDigest(String tenantId, Long versionId);

  DesignControlPlaneDigestDto getDesignControlPlaneDigestForScriptPatch(
      String tenantId, String scriptPatchVersion);

  PublishedReleaseBundleDto getPublishedReleaseBundle(String tenantId, long versionId);

  VersionStateDto getVersionState(String tenantId, long versionId);

  VersionStateDto compareAndSetVersionState(
      String tenantId,
      long versionId,
      long expectedVersionStateEpoch,
      VersionLifecycleState newState,
      String reason);
}
