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

  ConfiguredAuthoredActionCatalog(AuthoredActionProperties properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    LinkedHashMap<String, ConfiguredAuthoredAction> actions = new LinkedHashMap<>();
    for (AuthoredActionProperties.Action action : properties.getActions()) {
      if (action == null || !StringUtils.hasText(action.getCommandId())) {
        continue;
      }
      ConfiguredAuthoredAction normalized =
          new ConfiguredAuthoredAction(
              action.getActionId(),
              action.getCommandId().trim(),
              action.getAliases(),
              action.getStageRequirement(),
              action.getPromptPolicy(),
              action.getActionCategory(),
              action.getTargetingMode(),
              action.getCooldownKey(),
              action.getCooldownMs(),
              action.getCostKey(),
              action.getCostAmount(),
              action.getNoticeText());
      ConfiguredAuthoredAction previous = actions.putIfAbsent(normalized.commandId(), normalized);
      if (previous != null) {
        throw new IllegalStateException(
            "Duplicate authored action commandId " + normalized.commandId());
      }
    }
    this.actionsByCommandId = Map.copyOf(actions);
  }

  Optional<ConfiguredAuthoredAction> find(String commandId) {
    if (commandId == null || commandId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(actionsByCommandId.get(commandId));
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
      String targetingMode,
      String cooldownKey,
      long cooldownMs,
      String costKey,
      long costAmount,
      String noticeText) {
    ConfiguredAuthoredAction {
      aliases = java.util.List.copyOf(aliases == null ? java.util.List.of() : aliases);
    }
  }
}
