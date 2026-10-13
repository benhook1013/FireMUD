package net.firedevops.firemud.gamedesign.dto;

import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;

public record ResolvedLaunchDescriptorDto(
    String launchDescriptorId,
    String canonicalTenantId,
    long gameTemplateId,
    String controlPlaneRequestId,
    long versionId,
    String scriptPatchVersion,
    String runtimeFlagsJson,
    String generationConfigRevision,
    long versionStateEpoch,
    long releaseBundleId,
    String publishedReleaseBundleRef,
    String remapSetId,
    AuthoredWorldLaunchDescriptorEvidence authoredWorldBinding) {}
