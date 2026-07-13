package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.AuthoredCommandAdmission;
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

  @Test
  void treatsPublishedBundleReadFailuresAsUnavailable() {
    GameInstance instance = new GameInstance();
    instance.setId(44L);
    instance.setTenantId(7L);
    instance.setVersionId(9L);
    instance.setReleaseBundleId(12L);
    when(gameInstanceRepository.findById(44L)).thenReturn(Optional.of(instance));
    when(gameDesignClient.getPublishedReleaseBundle(7L, 9L))
        .thenThrow(new IllegalStateException("game design unavailable"));

    assertTrue(reader.definitionsFor(context()).isEmpty());
  }

  @Test
  void treatsAdmittedInstanceLookupFailuresAsUnavailable() {
    when(gameInstanceRepository.findById(44L))
        .thenThrow(new IllegalStateException("game instance store unavailable"));

    assertTrue(reader.definitionsFor(context()).isEmpty());
  }

  @Test
  void rejectsNumericRequiredTextFieldsFromTheAdmittedBundle() {
    GameInstance instance = admittedInstance();
    when(gameInstanceRepository.findById(44L)).thenReturn(Optional.of(instance));
    when(gameDesignClient.getPublishedReleaseBundle(7L, 9L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(12L)
                        .setVersionId(9L)
                        .addCommandDefinitions(
                            validDefinition()
                                .replace("\"commandId\":\"salute\"", "\"commandId\":7"))
                        .build())
                .build());

    assertTrue(reader.definitionsFor(context()).isEmpty());
  }

  @Test
  void carriesRegisteredActionStateDeclarationsFromTheAdmittedBundle() {
    GameInstance instance = admittedInstance();
    when(gameInstanceRepository.findById(44L)).thenReturn(Optional.of(instance));
    when(gameDesignClient.getPublishedReleaseBundle(7L, 9L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(12L)
                        .setVersionId(9L)
                        .addCommandDefinitions(validActionStateDefinition())
                        .build())
                .build());

    var definitions = reader.definitionsFor(context());

    assertTrue(definitions.isPresent());
    var effect = definitions.orElseThrow().getFirst().effects().getFirst();
    var actionState = (TextCommandEffectDeclaration.ApplyActionState) effect;
    assertEquals("blocking", actionState.conditionKey());
    assertEquals(5L, actionState.duration().toSeconds());
    assertEquals("ADD", actionState.modifiers().getFirst().operation());
    assertEquals("block_mitigation", actionState.modifiers().getFirst().targetKey());
  }

  @Test
  void snapshotsValidatedAuthoredEffectsFromTheAdmittedReleaseBundle() {
    GameInstance instance = admittedInstance();
    when(gameInstanceRepository.findById(44L)).thenReturn(Optional.of(instance));
    when(gameDesignClient.getPublishedReleaseBundle(7L, 9L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(12L)
                        .setVersionId(9L)
                        .addCommandDefinitions(validActionStateDefinition())
                        .build())
                .build());

    AuthoredCommandAdmission admission = reader.admissionFor(context(), "block").orElseThrow();

    assertEquals(12L, admission.releaseBundleId());
    assertEquals(9L, admission.versionId());
    assertEquals("block", admission.commandId());
    assertTrue(admission.declaredEffectsJson().contains("APPLY_ACTION_STATE"));
  }

  @Test
  void doesNotAdmitDefinitionsWithoutTheSingleSupportedExecutionEffect() {
    GameInstance instance = admittedInstance();
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

    assertTrue(reader.admissionFor(context(), "salute").isEmpty());
  }

  @Test
  void preservesOmittedActionStateModifierScopesAsAbsent() {
    GameInstance instance = admittedInstance();
    when(gameInstanceRepository.findById(44L)).thenReturn(Optional.of(instance));
    when(gameDesignClient.getPublishedReleaseBundle(7L, 9L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(12L)
                        .setVersionId(9L)
                        .addCommandDefinitions(validActionStateDefinitionWithoutScopes())
                        .build())
                .build());

    var effect = reader.definitionsFor(context()).orElseThrow().getFirst().effects().getFirst();
    var actionState = (TextCommandEffectDeclaration.ApplyActionState) effect;

    assertNull(actionState.modifiers().getFirst().scopeKind());
    assertNull(actionState.modifiers().getFirst().scopeKey());
  }

  @Test
  void rejectsUnregisteredCommandEffectsFromTheAdmittedBundle() {
    GameInstance instance = admittedInstance();
    when(gameInstanceRepository.findById(44L)).thenReturn(Optional.of(instance));
    when(gameDesignClient.getPublishedReleaseBundle(7L, 9L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(12L)
                        .setVersionId(9L)
                        .addCommandDefinitions(
                            validActionStateDefinition().replace("APPLY_ACTION_STATE", "RUN_SQL"))
                        .build())
                .build());

    assertTrue(reader.definitionsFor(context()).isEmpty());
  }

  private SessionContext context() {
    return new SessionContext(1L, 7L, 2L, "player", 3L, "hero", 44L, "room", "jwt", 0L);
  }

  private GameInstance admittedInstance() {
    GameInstance instance = new GameInstance();
    instance.setId(44L);
    instance.setTenantId(7L);
    instance.setVersionId(9L);
    instance.setReleaseBundleId(12L);
    return instance;
  }

  private String validDefinition() {
    return """
        {"schemaVersion":1,"commandId":"salute","semanticOwner":"GAME_LOGIC","executionDiscipline":"DURABLE_GAMEPLAY","stageRequirement":"GAMEPLAY","promptPolicy":"WHEN_GAMEPLAY","actionCategory":"SOCIAL","aliases":["salute"],"actionTags":["COMMUNICATION"],"effects":[]}
        """;
  }

  private String validActionStateDefinition() {
    return """
        {"schemaVersion":1,"commandId":"block","semanticOwner":"GAME_LOGIC","executionDiscipline":"DURABLE_GAMEPLAY","stageRequirement":"GAMEPLAY","promptPolicy":"WHEN_GAMEPLAY","actionCategory":"GAMEPLAY","aliases":["block"],"actionTags":["COMBAT"],"effects":[{"effectKind":"APPLY_ACTION_STATE","schemaVersion":1,"targeting":"SELF","replayPolicy":"EFFECT_IDEMPOTENT","payload":{"conditionKey":"blocking","durationSeconds":5,"effectPayload":{"modifiers":[{"operation":"ADD","target_key":"block_mitigation","value":1,"scope_kind":"ACTION_FAMILY","scope_key":"defense"}]}}}]}
        """;
  }

  private String validActionStateDefinitionWithoutScopes() {
    return """
        {"schemaVersion":1,"commandId":"block","semanticOwner":"GAME_LOGIC","executionDiscipline":"DURABLE_GAMEPLAY","stageRequirement":"GAMEPLAY","promptPolicy":"WHEN_GAMEPLAY","actionCategory":"GAMEPLAY","aliases":["block"],"actionTags":["COMBAT"],"effects":[{"effectKind":"APPLY_ACTION_STATE","schemaVersion":1,"targeting":"SELF","replayPolicy":"EFFECT_IDEMPOTENT","payload":{"conditionKey":"blocking","durationSeconds":5,"effectPayload":{"modifiers":[{"operation":"ADD","target_key":"block_mitigation","value":1}]}}}]}
        """;
  }
}
