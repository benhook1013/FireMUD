package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class AdmittedCommandDefinitionReaderTest {
  private final GameInstanceRepository gameInstanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final GameDesignClient gameDesignClient = Mockito.mock(GameDesignClient.class);
  private final AdmittedCommandDefinitionReader reader =
      new AdmittedCommandDefinitionReader(
          gameInstanceRepository, gameDesignClient, new ObjectMapper());

  @Test
  void resolvesOnlyDefinitionsFromTheMatchingAdmittedReleaseBundle() {
    GameInstance instance = new GameInstance();
    instance.setId(44L);
    instance.setTenantId(7L);
    instance.setVersionId(9L);
    instance.setReleaseBundleId(12L);
    when(gameInstanceRepository.findById(44L)).thenReturn(Optional.of(instance));
    when(gameDesignClient.getPublishedReleaseBundle(7L, 9L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(12L)
                        .setVersionId(9L)
                        .addCommandDefinitions(validDefinition())
                        .build())
                .build());

    var definitions = reader.definitionsFor(context());

    assertTrue(definitions.isPresent());
    assertEquals("salute", definitions.orElseThrow().getFirst().commandId());
    assertEquals(
        TextCommandDispatchGroup.AUTHORED, definitions.orElseThrow().getFirst().dispatchGroup());
  }

  @Test
  void rejectsDefinitionsWhenTheBundleDoesNotMatchTheAdmittedInstance() {
    GameInstance instance = new GameInstance();
    instance.setId(44L);
    instance.setTenantId(7L);
    instance.setVersionId(9L);
    instance.setReleaseBundleId(12L);
    when(gameInstanceRepository.findById(44L)).thenReturn(Optional.of(instance));
    when(gameDesignClient.getPublishedReleaseBundle(7L, 9L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(PublishedReleaseBundle.newBuilder().setId(13L).setVersionId(9L).build())
                .build());

    assertTrue(reader.definitionsFor(context()).isEmpty());
  }

  private SessionContext context() {
    return new SessionContext(1L, 7L, 2L, "player", 3L, "hero", 44L, "room", "jwt", 0L);
  }

  private String validDefinition() {
    return """
        {"schemaVersion":1,"commandId":"salute","semanticOwner":"GAME_LOGIC","executionDiscipline":"DURABLE_GAMEPLAY","stageRequirement":"GAMEPLAY","promptPolicy":"WHEN_GAMEPLAY","actionCategory":"SOCIAL","aliases":["salute"],"actionTags":["COMMUNICATION"],"effects":[]}
        """;
  }
}
