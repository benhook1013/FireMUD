package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class PluginActivationPreflightServiceImplTest {
  @Test
  void rejectsUnsupportedBuiltInCommandAlias() {
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    PluginRuntimeStateRepository runtimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(definitionRepository.findByTenantIdAndScriptVersionOrderByNameAsc(1L, "patch-1"))
        .thenReturn(List.of(pluginScript("plugin-town-crier", "town-crier", "town-crier-v2")));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(binding("plugin-town-crier", "onCommand", "COMMAND_ALIAS", "warp", false)));
    when(runtimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(runtimeStateResponse("region-1"));
    when(gameSessionClient.validateBuiltInCommandAlias("warp"))
        .thenReturn(ValidateBuiltInCommandAliasResponse.newBuilder().build());
    PluginActivationPreflightServiceImpl service =
        new PluginActivationPreflightServiceImpl(
            definitionRepository,
            bindingRepository,
            runtimeStateRepository,
            gameSessionClient,
            new ObjectMapper());

    assertThatThrownBy(
            () ->
                service.validateActivation("1", "game-1", "patch-1", "town-crier", "town-crier-v2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PLUGIN_COMMAND_ALIAS_INVALID");
  }

  @Test
  void rejectsCommandAliasCollisionWithinInstanceScope() {
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    PluginRuntimeStateRepository runtimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(definitionRepository.findByTenantIdAndScriptVersionOrderByNameAsc(1L, "patch-1"))
        .thenReturn(
            List.of(
                baseScript("npc-guard"),
                pluginScript("plugin-town-crier", "town-crier", "town-crier-v2")));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("npc-guard", "onCommand", "COMMAND_ALIAS", "look", false),
                binding("plugin-town-crier", "onCommand", "COMMAND_ALIAS", "look", false)));
    when(runtimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(runtimeStateResponse("region-1"));
    when(gameSessionClient.validateBuiltInCommandAlias("look"))
        .thenReturn(
            ValidateBuiltInCommandAliasResponse.newBuilder()
                .setSupported(true)
                .setNormalizedAlias("look")
                .build());
    PluginActivationPreflightServiceImpl service =
        new PluginActivationPreflightServiceImpl(
            definitionRepository,
            bindingRepository,
            runtimeStateRepository,
            gameSessionClient,
            new ObjectMapper());

    assertThatThrownBy(
            () ->
                service.validateActivation("1", "game-1", "patch-1", "town-crier", "town-crier-v2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PLUGIN_COMMAND_ALIAS_CONFLICT");
  }

  @Test
  void rejectsExclusiveBindingConflictWithActivePlugin() {
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    PluginRuntimeStateRepository runtimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(definitionRepository.findByTenantIdAndScriptVersionOrderByNameAsc(1L, "patch-1"))
        .thenReturn(
            List.of(
                pluginScript("plugin-market-bell", "market-bell", "market-bell-v1"),
                pluginScript("plugin-town-crier", "town-crier", "town-crier-v2")));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("plugin-market-bell", "onEnterRegion", "REGION", "market-square", false),
                binding("plugin-town-crier", "onEnterRegion", "REGION", "market-square", true)));
    when(runtimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(
            List.of(activePlugin("market-bell", "market-bell-v1"), disabledPlugin("town-crier")));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(runtimeStateResponse("region-1"));
    PluginActivationPreflightServiceImpl service =
        new PluginActivationPreflightServiceImpl(
            definitionRepository,
            bindingRepository,
            runtimeStateRepository,
            gameSessionClient,
            new ObjectMapper());

    assertThatThrownBy(
            () ->
                service.validateActivation("1", "game-1", "patch-1", "town-crier", "town-crier-v2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PLUGIN_INSTANCE_BINDING_CONFLICT");
  }

  @Test
  void ignoresActivePluginRowsFromDifferentObservedRuntimeRegion() {
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    PluginRuntimeStateRepository runtimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(definitionRepository.findByTenantIdAndScriptVersionOrderByNameAsc(1L, "patch-1"))
        .thenReturn(
            List.of(
                pluginScript("plugin-market-bell", "market-bell", "market-bell-v1"),
                pluginScript("plugin-town-crier", "town-crier", "town-crier-v2")));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("plugin-market-bell", "onEnterRegion", "REGION", "market-square", false),
                binding("plugin-town-crier", "onEnterRegion", "REGION", "market-square", true)));
    PluginRuntimeState staleRegion = activePlugin("market-bell", "market-bell-v1");
    staleRegion.setRuntimeRegionId("region-stale");
    when(runtimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of(staleRegion));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(runtimeStateResponse("region-live"));
    PluginActivationPreflightServiceImpl service =
        new PluginActivationPreflightServiceImpl(
            definitionRepository,
            bindingRepository,
            runtimeStateRepository,
            gameSessionClient,
            new ObjectMapper());

    service.validateActivation("1", "game-1", "patch-1", "town-crier", "town-crier-v2");
  }

  private static ScriptDefinition baseScript(String scriptId) {
    ScriptDefinition definition = new ScriptDefinition();
    definition.setTenantId(1L);
    definition.setName(scriptId);
    definition.setScriptVersion("patch-1");
    definition.setDefinition("{\"eventHandlers\":{}}");
    return definition;
  }

  private static ScriptDefinition pluginScript(
      String scriptId, String pluginId, String pluginVersionId) {
    ScriptDefinition definition = new ScriptDefinition();
    definition.setTenantId(1L);
    definition.setName(scriptId);
    definition.setScriptVersion("patch-1");
    definition.setDefinition(
        String.format(
            "{%n"
                + "  \"plugin\": {%n"
                + "    \"pluginId\": \"%s\",%n"
                + "    \"pluginVersionId\": \"%s\"%n"
                + "  },%n"
                + "  \"eventHandlers\": {}%n"
                + "}%n",
            pluginId, pluginVersionId));
    return definition;
  }

  private static ScriptEventBinding binding(
      String scriptId,
      String eventType,
      String targetScopeType,
      String targetScopeId,
      boolean requiresExclusiveEvent) {
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setTenantId(1L);
    binding.setScriptPatchVersion("patch-1");
    binding.setScriptId(scriptId);
    binding.setEventType(eventType);
    binding.setEventSchemaVersion("v1");
    binding.setTargetScopeType(targetScopeType);
    binding.setTargetScopeId(targetScopeId);
    binding.setPriority(10);
    binding.setPriorityTag("normal");
    binding.setRequiresExclusiveEvent(requiresExclusiveEvent);
    binding.setEnabled(true);
    return binding;
  }

  private static PluginRuntimeState activePlugin(String pluginId, String pluginVersionId) {
    PluginRuntimeState state = new PluginRuntimeState();
    state.setTenantId("1");
    state.setGameInstanceId("game-1");
    state.setRuntimeRegionId("region-1");
    state.setPluginId(pluginId);
    state.setActivePluginVersionId(pluginVersionId);
    state.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    return state;
  }

  private static PluginRuntimeState disabledPlugin(String pluginId) {
    PluginRuntimeState state = new PluginRuntimeState();
    state.setTenantId("1");
    state.setGameInstanceId("game-1");
    state.setPluginId(pluginId);
    state.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    return state;
  }

  private static GetGameInstanceRuntimeStateResponse runtimeStateResponse(String regionId) {
    return GetGameInstanceRuntimeStateResponse.newBuilder()
        .setRuntimeState(
            net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                .setRegionId(regionId))
        .build();
  }
}
