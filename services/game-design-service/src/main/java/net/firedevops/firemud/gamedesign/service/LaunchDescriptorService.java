package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;

public interface LaunchDescriptorService {
  ResolvedLaunchDescriptorDto resolveLaunchDescriptor(
      String tenantId,
      long gameTemplateId,
      String controlPlaneRequestId,
      String requestedScriptPatchVersion,
      Long sourceVersionId,
      Long targetVersionId,
      String requestedRuntimeFlagsJson);
}
