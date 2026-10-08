package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LaunchDescriptorServiceImplTest {
  private final GameTemplateRepository gameTemplateRepository = mock(GameTemplateRepository.class);
  private final LaunchDescriptorRepository launchDescriptorRepository =
      mock(LaunchDescriptorRepository.class);
  private final VersionRepository versionRepository = mock(VersionRepository.class);
  private final PublishedReleaseBundleService publishedReleaseBundleService =
      mock(PublishedReleaseBundleService.class);
  private final TemplateRemapSetService templateRemapSetService =
      mock(TemplateRemapSetService.class);
  private final ObjectMapper objectMapper = mock(ObjectMapper.class);
  private final LaunchDescriptorServiceImpl service =
      new LaunchDescriptorServiceImpl(
          gameTemplateRepository,
          launchDescriptorRepository,
          versionRepository,
          publishedReleaseBundleService,
          templateRemapSetService,
          objectMapper);

  @Test
  void legacyNumericResolveFailsClosedBeforeAnyOwnerInteraction() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.resolveLaunchDescriptor("1", 9L, "cp-1", null, null, null, null));

    assertEquals(
        "AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED: canonical authored-world source binding is"
            + " required to resolve a launch descriptor",
        thrown.getMessage());
    verifyNoInteractions(
        gameTemplateRepository,
        launchDescriptorRepository,
        versionRepository,
        publishedReleaseBundleService,
        templateRemapSetService,
        objectMapper);
  }
}
