package net.firedevops.firemud.gamedesign.service;

import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;

public interface LaunchDescriptorService {
  /** Legacy caller cannot resolve a launch without the exact authored-world source binding. */
  default ResolvedLaunchDescriptorDto resolveLaunchDescriptor(
      String tenantId,
      long gameTemplateId,
      String controlPlaneRequestId,
      String requestedScriptPatchVersion,
      Long sourceVersionId,
      Long targetVersionId,
      String requestedRuntimeFlagsJson) {
    throw new IllegalStateException(
        "Canonical authored-world source binding is required for launch descriptor resolution");
  }

  ResolvedLaunchDescriptorDto resolveLaunchDescriptor(
      AuthoredWorldLaunchDescriptorEvidence.Request request);

  ResolvedLaunchDescriptorDto getLaunchDescriptor(
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      String expectedRequestDigest,
      String expectedResultDigest);

  ResolvedLaunchDescriptorDto getLaunchDescriptorInOwnerSnapshot(
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      String expectedRequestDigest,
      String expectedResultDigest);
}
