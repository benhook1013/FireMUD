package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.gamedesign.client.WorldManagementClient;
import net.firedevops.firemud.gamedesign.dto.AppliedWorldDesignMutationDto;
import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.RevisionMapper;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

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
            revisionRepository,
            gameRepository,
            versionRepository,
            mapper,
            worldManagementClient,
            new ObjectMapper());
  }

  @Test
  void saveRevisionPersistsEntity() {
    Game game = setupGameAndVersion();
    Revision saved = new Revision();
    saved.setId(10L);
    saved.setTenantId(game.getTenantId());
    saved.setVersionId(7L);
    saved.setRevisionKind("GENERIC");
    saved.setData("{}");
    when(revisionRepository.save(any(Revision.class))).thenReturn(saved);

    RevisionDto dto = new RevisionDto(null, "1", 7L, 3L, "{}", "GENERIC", null, null, null, null);
    RevisionDto result = service.saveRevision(dto);

    assertEquals(10L, result.id());
  }

  @Test
  void saveRevisionRejectsPublishedVersionBeforePersistingOrApplyingMutations() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    when(gameRepository.findByTenantId("1")).thenReturn(game);
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    when(versionRepository.findByTenantIdAndId("1", 7L)).thenReturn(java.util.Optional.of(version));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.saveRevision(
                    new RevisionDto(
                        null, "1", 7L, 3L, "{}", "COMMAND_DEFINITION", null, null, null, null)));

    assertEquals("INVALID_ARGUMENT: published versions are immutable", ex.getMessage());
    verify(revisionRepository, never()).save(any(Revision.class));
    verify(worldManagementClient, never())
        .applyWorldDesignMutation(any(), anyLong(), any(WorldDesignMutationRevisionDto.class));
  }

  @Test
  void saveRevisionRejectsMalformedCommandDefinitionBeforePersisting() {
    setupGameAndVersion();

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.saveRevision(
                    new RevisionDto(
                        null,
                        "1",
                        7L,
                        3L,
                        "{\"schemaVersion\":1,\"commandId\":\"block\"}",
                        "COMMAND_DEFINITION",
                        null,
                        null,
                        null,
                        null)));

    assertEquals("INVALID_ARGUMENT: commandDefinition semanticOwner is required", ex.getMessage());
    verify(revisionRepository, never()).save(any(Revision.class));
  }

  @Test
  void saveRevisionPersistsValidCommandDefinition() {
    Game game = setupGameAndVersion();
    Revision saved = new Revision();
    saved.setId(12L);
    saved.setTenantId(game.getTenantId());
    saved.setVersionId(7L);
    saved.setRevisionKind("COMMAND_DEFINITION");
    saved.setData(validCommandDefinition());
    when(revisionRepository.save(any(Revision.class))).thenReturn(saved);

    RevisionDto result =
        service.saveRevision(
            new RevisionDto(
                null,
                "1",
                7L,
                3L,
                validCommandDefinition(),
                "COMMAND_DEFINITION",
                null,
                null,
                null,
                null));

    assertEquals(12L, result.id());
    verify(revisionRepository).save(any(Revision.class));
  }

  @Test
  void saveRevisionAppliesWorldMutationBeforePersisting() {
    Game game = setupGameAndVersion();
    when(worldManagementClient.applyWorldDesignMutation(
            eq("1"), eq(7L), any(WorldDesignMutationRevisionDto.class)))
        .thenReturn(
            new AppliedWorldDesignMutationDto(
                "WORLD_DESIGN_MUTATION_RESULT_APPLIED", "44", 2L, 5L));
    Revision saved = new Revision();
    saved.setId(11L);
    saved.setTenantId(game.getTenantId());
    saved.setVersionId(7L);
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
                "",
                new WorldDesignMutationRevisionDto.RegionMutationDto(
                    "Region", "clear", 0, 0L, "", "", 0.0d),
                null,
                null,
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

  @Test
  void saveRevisionAllowsWorldGenerationSubtreeMutation() {
    Game game = setupGameAndVersion();
    when(worldManagementClient.applyWorldDesignMutation(
            eq("1"), eq(7L), any(WorldDesignMutationRevisionDto.class)))
        .thenReturn(
            new AppliedWorldDesignMutationDto(
                "WORLD_DESIGN_MUTATION_RESULT_APPLIED", "2000000000012", 1L, 1L));
    Revision saved = new Revision();
    saved.setId(12L);
    saved.setTenantId(game.getTenantId());
    saved.setVersionId(7L);
    saved.setRevisionKind("WORLD_DESIGN_MUTATION");
    saved.setLogicalRevisionId("rev-subtree");
    saved.setData("{\"revisionKind\":\"WORLD_DESIGN_MUTATION\"}");
    when(revisionRepository.save(any(Revision.class))).thenReturn(saved);

    RevisionDto result =
        service.saveRevision(worldMutationRevisionDto(generationSubtreeMutation()));

    assertEquals(12L, result.id());
    assertEquals("2000000000012", result.appliedWorldDesignMutation().aggregateId());
    verify(worldManagementClient)
        .applyWorldDesignMutation(eq("1"), eq(7L), any(WorldDesignMutationRevisionDto.class));
  }

  @Test
  void saveRevisionRejectsUnsupportedWorldMutationOperationBeforeClientCall() {
    setupGameAndVersion();
    RevisionDto dto =
        worldMutationRevisionDto(
            new WorldDesignMutationRevisionDto(
                "rev-1",
                "commit-1",
                "WORLD_DESIGN_MUTATION_OPERATION_UNSPECIFIED",
                "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                "44",
                1L,
                "",
                "",
                0L,
                "",
                new WorldDesignMutationRevisionDto.RegionMutationDto(
                    "Region", "clear", 0, 0L, "", "", 0.0d),
                null,
                null,
                null,
                null,
                null,
                null));

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> service.saveRevision(dto));

    assertEquals("INVALID_ARGUMENT: unsupported worldDesignMutation.operation", ex.getMessage());
    verify(worldManagementClient, never())
        .applyWorldDesignMutation(any(), anyLong(), any(WorldDesignMutationRevisionDto.class));
    verify(revisionRepository, never()).save(any(Revision.class));
  }

  @Test
  void saveRevisionRejectsMismatchedWorldMutationPayloadBeforeClientCall() {
    setupGameAndVersion();
    RevisionDto dto =
        worldMutationRevisionDto(
            new WorldDesignMutationRevisionDto(
                "rev-1",
                "commit-1",
                "WORLD_DESIGN_MUTATION_OPERATION_UPSERT",
                "WORLD_DESIGN_AGGREGATE_TYPE_ROOM",
                "44",
                1L,
                "",
                "",
                0L,
                "",
                new WorldDesignMutationRevisionDto.RegionMutationDto(
                    "Region", "clear", 0, 0L, "", "", 0.0d),
                null,
                null,
                null,
                null,
                null,
                null));

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> service.saveRevision(dto));

    assertEquals(
        "INVALID_ARGUMENT: worldDesignMutation payload must match aggregateType", ex.getMessage());
    verify(worldManagementClient, never())
        .applyWorldDesignMutation(any(), anyLong(), any(WorldDesignMutationRevisionDto.class));
    verify(revisionRepository, never()).save(any(Revision.class));
  }

  private Game setupGameAndVersion() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    when(gameRepository.findByTenantId("1")).thenReturn(game);
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("1");
    when(versionRepository.findByTenantIdAndId("1", 7L)).thenReturn(java.util.Optional.of(version));
    return game;
  }

  private RevisionDto worldMutationRevisionDto(WorldDesignMutationRevisionDto mutation) {
    return new RevisionDto(
        null,
        "1",
        7L,
        3L,
        "{\"foo\":\"bar\"}",
        "WORLD_DESIGN_MUTATION",
        "rev-1",
        mutation,
        null,
        null);
  }

  private String validCommandDefinition() {
    return """
        {
          "schemaVersion": 1,
          "commandId": "block",
          "semanticOwner": "GAME_LOGIC",
          "executionDiscipline": "DURABLE_GAMEPLAY",
          "stageRequirement": "GAMEPLAY",
          "promptPolicy": "WHEN_GAMEPLAY",
          "actionCategory": "GAMEPLAY",
          "aliases": ["block", "guard"],
          "actionTags": ["COMBAT"],
          "effects": []
        }
        """;
  }

  private WorldDesignMutationRevisionDto generationSubtreeMutation() {
    return new WorldDesignMutationRevisionDto(
        "rev-subtree",
        "commit-subtree",
        "WORLD_DESIGN_MUTATION_OPERATION_UPSERT",
        "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_GENERATION_SUBTREE",
        "",
        0L,
        "WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE",
        "12",
        0L,
        "WORLD_DESIGN_SCOPE_MUTATION_POLICY_REPLACE_SCOPE",
        null,
        null,
        null,
        null,
        null,
        null,
        new WorldDesignMutationRevisionDto.WorldGenerationSubtreeMutationDto(
            List.of(
                new WorldDesignMutationRevisionDto.GenerationRuleMutationDto(
                    "population", "dense")),
            List.of(
                new WorldDesignMutationRevisionDto.GeneratedRoomMutationDto(
                    "a", "room-a", "A generated room", "12", null, null)),
            List.of(),
            List.of(
                new WorldDesignMutationRevisionDto.GeneratedWorldEntitySpawnBindingMutationDto(
                    "a", "NPC", "55", 2, 30))));
  }
}
