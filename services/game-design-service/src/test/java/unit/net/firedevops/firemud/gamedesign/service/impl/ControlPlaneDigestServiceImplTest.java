package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import tools.jackson.databind.ObjectMapper;

class ControlPlaneDigestServiceImplTest {

  private final GameTemplateRepository gameTemplateRepository =
      Mockito.mock(GameTemplateRepository.class);
  private final GameAssetRepository gameAssetRepository = Mockito.mock(GameAssetRepository.class);
  private final RevisionRepository revisionRepository = Mockito.mock(RevisionRepository.class);
  private final ControlPlaneDigestServiceImpl service =
      new ControlPlaneDigestServiceImpl(
          gameTemplateRepository, gameAssetRepository, revisionRepository, new ObjectMapper());

  @Test
  void commandDefinitionRevisionsChangeTheVersionDigest() {
    VersionDto version =
        new VersionDto(
            7L, "tenant-1", 1, VersionLifecycleState.DRAFT, 1L, null, null, false, "", null, null);
    when(gameTemplateRepository.findByTenantId(Mockito.eq("tenant-1"), Mockito.any()))
        .thenReturn(Page.empty());
    when(gameAssetRepository.findByTenantId("tenant-1")).thenReturn(List.of());
    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(revision("{\"commandId\":\"block\"}")));

    String first = service.getDigestForVersion(version).contentDigest();

    when(revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            "tenant-1", 7L, "COMMAND_DEFINITION"))
        .thenReturn(List.of(revision("{\"commandId\":\"guard\"}")));

    assertThat(service.getDigestForVersion(version).contentDigest()).isNotEqualTo(first);
  }

  private Revision revision(String data) {
    Revision revision = new Revision();
    revision.setData(data);
    revision.setLogicalRevisionId("command:block");
    return revision;
  }
}
