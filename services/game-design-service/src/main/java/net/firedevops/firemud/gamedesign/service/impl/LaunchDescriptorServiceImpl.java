package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class LaunchDescriptorServiceImpl implements LaunchDescriptorService {
  private final GameTemplateRepository gameTemplateRepository;
  private final LaunchDescriptorRepository launchDescriptorRepository;
  private final VersionRepository versionRepository;
  private final PublishedReleaseBundleService publishedReleaseBundleService;
  private final TemplateRemapSetService templateRemapSetService;
  private final ObjectMapper objectMapper;

  @Override
  @Timed(value = "gamedesign.launchDescriptor.resolve")
  public ResolvedLaunchDescriptorDto resolveLaunchDescriptor(
      String tenantId,
      long gameTemplateId,
      String controlPlaneRequestId,
      String requestedScriptPatchVersion,
      Long sourceVersionId,
      Long targetVersionId,
      String requestedRuntimeFlagsJson) {
    throw new IllegalArgumentException(
        "AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED: canonical authored-world source binding is"
            + " required to resolve a launch descriptor");
  }
}
