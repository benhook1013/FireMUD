package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.gamedesign.client.WorldManagementClient;
import net.firedevops.firemud.gamedesign.dto.AppliedWorldDesignMutationDto;
import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.RevisionMapper;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RevisionServiceImplTest {
  @Mock private RevisionRepository revisionRepository;
  @Mock private GameRepository gameRepository;
  @Mock private VersionRepository versionRepository;
  @Mock private WorldManagementClient worldManagementClient;

  private RevisionServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    RevisionMapper mapper = Mappers.getMapper(RevisionMapper.class);
    service =
        new RevisionServiceImpl(
            revisionRepository, gameRepository, versionRepository, mapper, worldManagementClient);
  }

  @Test
  void saveRevisionPersistsEntity() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    when(gameRepository.findByTenantId("1")).thenReturn(game);
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    when(versionRepository.findByTenantIdAndId("1", 7L)).thenReturn(java.util.Optional.of(version));
    Revision saved = new Revision();
    saved.setId(10L);
    saved.setTenantId(game.getTenantId());
    saved.setVersionId(version.getId());
    saved.setRevisionKind("GENERIC");
    saved.setData("{}");
    when(revisionRepository.save(any(Revision.class))).thenReturn(saved);

    RevisionDto dto = new RevisionDto(null, "1", 7L, 3L, "{}", "GENERIC", null, null, null, null);
    RevisionDto result = service.saveRevision(dto);

    assertEquals(10L, result.id());
  }

  @Test
  void saveRevisionAppliesWorldMutationBeforePersisting() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    when(gameRepository.findByTenantId("1")).thenReturn(game);
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    when(versionRepository.findByTenantIdAndId("1", 7L)).thenReturn(java.util.Optional.of(version));
    when(worldManagementClient.applyWorldDesignMutation(
            eq("1"), eq(7L), any(WorldDesignMutationRevisionDto.class)))
        .thenReturn(
            new AppliedWorldDesignMutationDto(
                "WORLD_DESIGN_MUTATION_RESULT_APPLIED", "44", 2L, 5L));
    Revision saved = new Revision();
    saved.setId(11L);
    saved.setTenantId(game.getTenantId());
    saved.setVersionId(version.getId());
    saved.setRevisionKind("WORLD_DESIGN_MUTATION");
    saved.setLogicalRevisionId("rev-1");
    saved.setData("{\"foo\":\"bar\"}");
    when(revisionRepository.save(any(Revision.class))).thenReturn(saved);

    RevisionDto dto =
        new RevisionDto(
            null,
            "1",
            7L,
            3L,
            "{\"foo\":\"bar\"}",
            "WORLD_DESIGN_MUTATION",
            "rev-1",
            new WorldDesignMutationRevisionDto(
                "rev-1",
                "commit-1",
                "WORLD_DESIGN_MUTATION_OPERATION_UPSERT",
                "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                "44",
                1L,
                "",
                "",
                0L,
                new WorldDesignMutationRevisionDto.RegionMutationDto(
                    "Region", "clear", 0, 0L, "", "", 0.0d),
                null,
                null,
                null,
                null),
            null,
            null);

    RevisionDto result = service.saveRevision(dto);

    assertEquals(11L, result.id());
    assertEquals(
        "WORLD_DESIGN_MUTATION_RESULT_APPLIED", result.appliedWorldDesignMutation().result());
  }
}
