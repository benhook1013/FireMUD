package net.firedevops.firemud.gamesession.command.text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.AuthoredActionProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
final class ConfiguredAuthoredActionCatalog {
  private final Map<String, ConfiguredAuthoredAction> actionsByCommandId;
  private final Map<String, ConfiguredAuthoredAction> actionsByAlias;

  ConfiguredAuthoredActionCatalog(AuthoredActionProperties properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    LinkedHashMap<String, ConfiguredAuthoredAction> actions = new LinkedHashMap<>();
    LinkedHashMap<String, ConfiguredAuthoredAction> aliases = new LinkedHashMap<>();
    for (AuthoredActionProperties.Action action : properties.getActions()) {
      if (action == null || !StringUtils.hasText(action.getCommandId())) {
        continue;
      }
      validateSupportedFirstPass(action);
      ConfiguredAuthoredAction normalized =
          new ConfiguredAuthoredAction(
              action.getActionId(),
              action.getCommandId().trim(),
              action.getAliases(),
              action.getStageRequirement(),
              action.getPromptPolicy(),
              action.getActionCategory(),
              action.getActionTags(),
              action.getTargetingMode(),
              action.getCooldownKey(),
              action.getCooldownMs(),
              action.getCostKey(),
              action.getCostAmount(),
              action.getExecutionHook(),
              action.getHelpSummary(),
              action.getHelpDetails());
      ConfiguredAuthoredAction previous = actions.putIfAbsent(normalized.commandId(), normalized);
      if (previous != null) {
        throw new IllegalStateException(
            "Duplicate authored action commandId " + normalized.commandId());
      }
      for (String alias : normalized.aliases()) {
        if (!StringUtils.hasText(alias)) {
          continue;
        }
        String normalizedAlias = alias.trim().toLowerCase(java.util.Locale.ROOT);
        ConfiguredAuthoredAction existingAlias = aliases.putIfAbsent(normalizedAlias, normalized);
        if (existingAlias != null) {
          throw new IllegalStateException(
              "Duplicate authored action alias "
                  + normalizedAlias
                  + " for "
                  + existingAlias.commandId()
                  + " and "
                  + normalized.commandId());
        }
      }
    }
    this.actionsByCommandId = Map.copyOf(actions);
    this.actionsByAlias = Map.copyOf(aliases);
  }

  Optional<ConfiguredAuthoredAction> find(String commandId) {
    if (commandId == null || commandId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(actionsByCommandId.get(commandId));
  }

  Optional<ConfiguredAuthoredAction> findByAlias(String alias) {
    if (alias == null || alias.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(actionsByAlias.get(alias.trim().toLowerCase(java.util.Locale.ROOT)));
  }

  java.util.List<ConfiguredAuthoredAction> all() {
    return java.util.List.copyOf(actionsByCommandId.values());
  }

  record ConfiguredAuthoredAction(
      String actionId,
      String commandId,
      java.util.List<String> aliases,
      TextCommandStageRequirement stageRequirement,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory,
      java.util.List<TextCommandActionTag> actionTags,
      String targetingMode,
      String cooldownKey,
      long cooldownMs,
      String costKey,
      long costAmount,
      String executionHook,
      String helpSummary,
      String helpDetails) {
    ConfiguredAuthoredAction {
      aliases = java.util.List.copyOf(aliases == null ? java.util.List.of() : aliases);
      actionTags = java.util.List.copyOf(actionTags == null ? java.util.List.of() : actionTags);
    }

    String primaryHelpTopic() {
      if (!aliases.isEmpty()) {
        return aliases.getFirst();
      }
      return commandId;
    }
  }

  private static void validateSupportedFirstPass(AuthoredActionProperties.Action action) {
    String commandId = action.getCommandId().trim();
    if (action.getCooldownMs() < 0) {
      throw new IllegalStateException("Invalid authored action cooldownMs for " + commandId);
    }
    if (action.getCostAmount() < 0) {
      throw new IllegalStateException("Invalid authored action costAmount for " + commandId);
    }
    if (action.getCooldownMs() > 0 && !StringUtils.hasText(action.getCooldownKey())) {
      throw new IllegalStateException(
          "authored action cooldown metadata requires cooldownKey for " + commandId);
    }
    if (action.getCooldownMs() == 0 && StringUtils.hasText(action.getCooldownKey())) {
      throw new IllegalStateException(
          "authored action cooldownKey requires positive cooldownMs for " + commandId);
    }
    if (action.getCostAmount() > 0 && !StringUtils.hasText(action.getCostKey())) {
      throw new IllegalStateException(
          "authored action cost metadata requires costKey for " + commandId);
    }
    if (action.getCostAmount() == 0 && StringUtils.hasText(action.getCostKey())) {
      throw new IllegalStateException(
          "authored action costKey requires positive costAmount for " + commandId);
    }
  }
}
