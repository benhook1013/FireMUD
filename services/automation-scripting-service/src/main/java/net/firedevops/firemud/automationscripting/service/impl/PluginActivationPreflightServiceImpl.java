package net.firedevops.firemud.automationscripting.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.service.PluginActivationPreflightService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PluginActivationPreflightServiceImpl implements PluginActivationPreflightService {
  private static final String SCOPE_COMMAND_ALIAS = "COMMAND_ALIAS";

  private final ScriptDefinitionRepository scriptDefinitionRepository;
  private final ScriptEventBindingRepository scriptEventBindingRepository;
  private final PluginRuntimeStateRepository pluginRuntimeStateRepository;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final ObjectMapper objectMapper;

  public PluginActivationPreflightServiceImpl(
      ScriptDefinitionRepository scriptDefinitionRepository,
      ScriptEventBindingRepository scriptEventBindingRepository,
      PluginRuntimeStateRepository pluginRuntimeStateRepository,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      ObjectMapper objectMapper) {
    this.scriptDefinitionRepository =
        Objects.requireNonNull(
            scriptDefinitionRepository, "scriptDefinitionRepository must not be null");
    this.scriptEventBindingRepository =
        Objects.requireNonNull(
            scriptEventBindingRepository, "scriptEventBindingRepository must not be null");
    this.pluginRuntimeStateRepository =
        Objects.requireNonNull(
            pluginRuntimeStateRepository, "pluginRuntimeStateRepository must not be null");
    this.gameSessionControlPlaneClient =
        Objects.requireNonNull(
            gameSessionControlPlaneClient, "gameSessionControlPlaneClient must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public void validateActivation(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String pluginId,
      String pluginVersionId) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    requireText(pluginId, "plugin_id");
    requireText(pluginVersionId, "plugin_version_id");
    requireText(
        scriptPatchVersion, "PLUGIN_BINDINGS_UNAVAILABLE: pinned script patch version is missing");
    long tenantKey = parseTenantId(tenantId);
    List<ScriptDefinition> definitions =
        scriptDefinitionRepository.findByTenantIdAndScriptVersionOrderByNameAsc(
            tenantKey, scriptPatchVersion);
    Map<String, PluginOwner> ownersByScriptId = resolveOwners(definitions);
    List<ResolvedBinding> allBindings =
        resolveBindings(
            scriptEventBindingRepository
                .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                    tenantKey, scriptPatchVersion),
            ownersByScriptId);

    List<ResolvedBinding> targetBindings =
        allBindings.stream()
            .filter(
                binding ->
                    binding.pluginOwner().matches(pluginId, pluginVersionId) && binding.enabled())
            .toList();
    if (targetBindings.isEmpty()) {
      return;
    }

    List<PluginRuntimeState> runtimeStates =
        pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    RuntimeScope runtimeScope =
        currentRuntimeScope(tenantId, gameInstanceId, preferredRuntimeRegionId(runtimeStates));
    Map<String, String> activePluginVersions = activePluginVersions(runtimeStates, runtimeScope);
    List<ResolvedBinding> existingBindings =
        allBindings.stream()
            .filter(ResolvedBinding::enabled)
            .filter(
                binding ->
                    !binding.pluginOwner().matches(pluginId, pluginVersionId)
                        && participatesInResolvedHandlerSet(
                            binding.pluginOwner(), activePluginVersions))
            .toList();

    validateCommandAliases(targetBindings);
    validateActivationConflicts(targetBindings, existingBindings);
  }

  private Map<String, PluginOwner> resolveOwners(List<ScriptDefinition> definitions) {
    Map<String, PluginOwner> owners = new HashMap<>();
    for (ScriptDefinition definition : definitions) {
      owners.put(definition.getName(), resolveOwner(definition));
    }
    return owners;
  }

  private PluginOwner resolveOwner(ScriptDefinition definition) {
    Map<String, Object> root = parseDefinition(definition.getDefinition());
    String pluginId =
        firstPresent(
            normalizedText(root.get("pluginId")),
            normalizedText(asObjectMap(root.get("plugin")).get("pluginId")),
            normalizedText(asObjectMap(root.get("owner")).get("pluginId")));
    if (pluginId.isBlank()) {
      return PluginOwner.NONE;
    }
    String pluginVersionId =
        firstPresent(
            normalizedText(root.get("pluginVersionId")),
            normalizedText(asObjectMap(root.get("plugin")).get("pluginVersionId")),
            normalizedText(asObjectMap(root.get("owner")).get("pluginVersionId")));
    if (pluginVersionId.isBlank()) {
      throw new IllegalArgumentException(
          "PLUGIN_BINDINGS_UNAVAILABLE: plugin owner metadata is incomplete for script "
              + definition.getName());
    }
    return new PluginOwner(pluginId, pluginVersionId);
  }

  private List<ResolvedBinding> resolveBindings(
      List<ScriptEventBinding> bindings, Map<String, PluginOwner> ownersByScriptId) {
    List<ResolvedBinding> resolved = new ArrayList<>(bindings.size());
    for (ScriptEventBinding binding : bindings) {
      PluginOwner owner = ownersByScriptId.getOrDefault(binding.getScriptId(), PluginOwner.NONE);
      String scopeType = normalizeScopeType(binding.getTargetScopeType());
      String scopeId = normalizeScopeId(scopeType, binding.getTargetScopeId());
      resolved.add(
          new ResolvedBinding(
              binding.getScriptId(),
              binding.getEventType(),
              scopeType,
              scopeId,
              binding.isRequiresExclusiveEvent(),
              binding.isEnabled(),
              owner));
    }
    return List.copyOf(resolved);
  }

  private Map<String, String> activePluginVersions(
      List<PluginRuntimeState> runtimeStates, RuntimeScope runtimeScope) {
    Map<String, String> active = new LinkedHashMap<>();
    for (PluginRuntimeState state : runtimeStates) {
      if (!PluginState.PLUGIN_STATE_ENABLED.name().equals(state.getPluginState())) {
        continue;
      }
      if (!matchesRuntimeScope(state, runtimeScope)) {
        continue;
      }
      String pluginId = blankToEmpty(state.getPluginId());
      String pluginVersionId = blankToEmpty(state.getActivePluginVersionId());
      if (!pluginId.isBlank() && !pluginVersionId.isBlank()) {
        active.put(pluginId, pluginVersionId);
      }
    }
    return active;
  }

  private RuntimeScope currentRuntimeScope(
      String tenantId, String gameInstanceId, String preferredRegionId) {
    GetGameInstanceRuntimeStateResponse runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            tenantId, gameInstanceId, preferredRegionId);
    if (runtime.hasError() && !runtime.getError().getCode().isBlank()) {
      return RuntimeScope.UNKNOWN;
    }
    return new RuntimeScope(
        blankToEmpty(runtime.getRuntimeState().getRegionId()),
        runtime.getRuntimeState().getRegionEpoch());
  }

  private static String preferredRuntimeRegionId(List<PluginRuntimeState> runtimeStates) {
    for (PluginRuntimeState state : runtimeStates) {
      if (!PluginState.PLUGIN_STATE_ENABLED.name().equals(state.getPluginState())) {
        continue;
      }
      String runtimeRegionId = blankToEmpty(state.getRuntimeRegionId());
      if (!runtimeRegionId.isBlank() && zeroIfNull(state.getRuntimeRegionEpoch()) > 0) {
        return runtimeRegionId;
      }
    }
    return "";
  }

  private static boolean matchesRuntimeScope(PluginRuntimeState state, RuntimeScope runtimeScope) {
    if (!runtimeScope.known()) {
      return true;
    }
    String stateRegionId = blankToEmpty(state.getRuntimeRegionId());
    long stateRegionEpoch = zeroIfNull(state.getRuntimeRegionEpoch());
    if (stateRegionId.isBlank() || stateRegionEpoch <= 0) {
      return false;
    }
    return stateRegionId.equals(runtimeScope.regionId())
        && stateRegionEpoch == runtimeScope.regionEpoch();
  }

  private record RuntimeScope(String regionId, long regionEpoch) {
    private static final RuntimeScope UNKNOWN = new RuntimeScope("", 0L);

    private boolean known() {
      return !regionId.isBlank() && regionEpoch > 0;
    }
  }

  private boolean participatesInResolvedHandlerSet(
      PluginOwner pluginOwner, Map<String, String> activePluginVersions) {
    if (pluginOwner.isBaseScript()) {
      return true;
    }
    return pluginOwner.pluginVersionId().equals(activePluginVersions.get(pluginOwner.pluginId()));
  }

  private void validateCommandAliases(List<ResolvedBinding> targetBindings) {
    for (ResolvedBinding binding : targetBindings) {
      if (!SCOPE_COMMAND_ALIAS.equals(binding.targetScopeType())) {
        continue;
      }
      ValidateBuiltInCommandAliasResponse response =
          gameSessionControlPlaneClient.validateBuiltInCommandAlias(binding.targetScopeId());
      if (response.hasError() && !response.getError().getCode().isBlank()) {
        throw new IllegalArgumentException(
            "PLUGIN_COMMAND_ALIAS_UNAVAILABLE: " + response.getError().getMessage());
      }
      if (!response.getSupported()) {
        throw new IllegalArgumentException(
            "PLUGIN_COMMAND_ALIAS_INVALID: unsupported built-in command alias "
                + binding.targetScopeId());
      }
    }
  }

  private void validateActivationConflicts(
      List<ResolvedBinding> targetBindings, List<ResolvedBinding> existingBindings) {
    Map<String, List<ResolvedBinding>> targetGroups = groupByConflictKey(targetBindings);
    Map<String, List<ResolvedBinding>> existingGroups = groupByConflictKey(existingBindings);

    for (Map.Entry<String, List<ResolvedBinding>> entry : targetGroups.entrySet()) {
      List<ResolvedBinding> targetGroup = entry.getValue();
      ResolvedBinding sample = targetGroup.getFirst();
      if (SCOPE_COMMAND_ALIAS.equals(sample.targetScopeType()) && targetGroup.size() > 1) {
        throw new IllegalArgumentException(
            "PLUGIN_COMMAND_ALIAS_CONFLICT: duplicate command alias binding within target activation scope");
      }
      if (hasExclusiveConflict(targetGroup)) {
        throw new IllegalArgumentException(
            "PLUGIN_INSTANCE_BINDING_CONFLICT: exclusive binding conflict within target activation scope");
      }
      List<ResolvedBinding> existingGroup = existingGroups.getOrDefault(entry.getKey(), List.of());
      if (!existingGroup.isEmpty() && SCOPE_COMMAND_ALIAS.equals(sample.targetScopeType())) {
        throw new IllegalArgumentException(
            "PLUGIN_COMMAND_ALIAS_CONFLICT: command alias already bound in this instance scope");
      }
      if (!existingGroup.isEmpty() && (anyExclusive(targetGroup) || anyExclusive(existingGroup))) {
        throw new IllegalArgumentException(
            "PLUGIN_INSTANCE_BINDING_CONFLICT: instance binding conflicts with an already active handler");
      }
    }
  }

  private static Map<String, List<ResolvedBinding>> groupByConflictKey(
      List<ResolvedBinding> bindings) {
    Map<String, List<ResolvedBinding>> grouped = new LinkedHashMap<>();
    for (ResolvedBinding binding : bindings) {
      grouped.computeIfAbsent(binding.conflictKey(), ignored -> new ArrayList<>()).add(binding);
    }
    return grouped;
  }

  private static boolean hasExclusiveConflict(List<ResolvedBinding> bindings) {
    return bindings.size() > 1 && anyExclusive(bindings);
  }

  private static boolean anyExclusive(List<ResolvedBinding> bindings) {
    return bindings.stream().anyMatch(ResolvedBinding::requiresExclusiveEvent);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObjectMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }

  private Map<String, Object> parseDefinition(String definitionJson) {
    try {
      return objectMapper.readValue(definitionJson, Map.class);
    } catch (Exception ex) {
      throw new IllegalArgumentException(
          "PLUGIN_BINDINGS_UNAVAILABLE: script definition json is invalid");
    }
  }

  private static long parseTenantId(String tenantId) {
    try {
      return Long.parseLong(tenantId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("tenant_id must be numeric");
    }
  }

  private static String normalizeScopeType(String scopeType) {
    return blankToEmpty(scopeType).trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizeScopeId(String scopeType, String scopeId) {
    String normalized = blankToEmpty(scopeId).trim();
    if (SCOPE_COMMAND_ALIAS.equals(scopeType)) {
      return normalized.toLowerCase(Locale.ROOT);
    }
    return normalized;
  }

  private static String firstPresent(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String normalizedText(Object value) {
    return value == null ? "" : value.toString().trim();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }

  private static void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }

  private record PluginOwner(String pluginId, String pluginVersionId) {
    private static final PluginOwner NONE = new PluginOwner("", "");

    private boolean isBaseScript() {
      return pluginId.isBlank();
    }

    private boolean matches(String candidatePluginId, String candidatePluginVersionId) {
      return pluginId.equals(candidatePluginId) && pluginVersionId.equals(candidatePluginVersionId);
    }
  }

  private record ResolvedBinding(
      String scriptId,
      String eventType,
      String targetScopeType,
      String targetScopeId,
      boolean requiresExclusiveEvent,
      boolean enabled,
      PluginOwner pluginOwner) {
    private String conflictKey() {
      return eventType + "\u0000" + targetScopeType + "\u0000" + targetScopeId;
    }
  }
}
