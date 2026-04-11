package net.firedevops.firemud.gamesession.command.text;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
final class AggregatingTextCommandRegistry implements TextCommandRegistry {
  private final Map<TextCommandType, TextCommandDefinition> definitions;

  AggregatingTextCommandRegistry(List<TextCommandDefinitionProvider> providers) {
    EnumMap<TextCommandType, TextCommandDefinition> definitionMap =
        new EnumMap<>(TextCommandType.class);
    for (TextCommandDefinitionProvider provider : providers) {
      for (TextCommandDefinition definition : provider.definitions()) {
        TextCommandDefinition existing = definitionMap.putIfAbsent(definition.type(), definition);
        if (existing != null) {
          throw new IllegalStateException(
              "Duplicate text command definition for "
                  + definition.type()
                  + " from "
                  + existing.source()
                  + " and "
                  + definition.source());
        }
      }
    }
    this.definitions = Map.copyOf(definitionMap);
  }

  @Override
  public Optional<TextCommandDefinition> findDefinition(TextCommandType type) {
    return Optional.ofNullable(definitions.get(type));
  }
}
