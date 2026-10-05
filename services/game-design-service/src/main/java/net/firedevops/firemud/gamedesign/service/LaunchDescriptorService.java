package net.firedevops.firemud.gamedesign.service;

import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;

public interface LaunchDescriptorService {
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
