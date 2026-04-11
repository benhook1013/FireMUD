package net.firedevops.firemud.gamesession.command.text;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
final class AggregatingTextCommandRegistry implements TextCommandRegistry {
  private final Map<TextCommandType, TextCommandDefinition> definitions;
  private final Map<String, TextCommandDefinition> definitionsByAlias;

  AggregatingTextCommandRegistry(List<TextCommandDefinitionProvider> providers) {
    EnumMap<TextCommandType, TextCommandDefinition> definitionMap =
        new EnumMap<>(TextCommandType.class);
    HashMap<String, TextCommandDefinition> aliasMap = new HashMap<>();
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
        for (String alias : definition.aliases()) {
          String normalized = normalizeAlias(alias);
          TextCommandDefinition existingAlias = aliasMap.putIfAbsent(normalized, definition);
          if (existingAlias != null) {
            throw new IllegalStateException(
                "Duplicate text command alias '"
                    + normalized
                    + "' from "
                    + existingAlias.source()
                    + " and "
                    + definition.source());
          }
        }
      }
    }
    this.definitions = Map.copyOf(definitionMap);
    this.definitionsByAlias = Map.copyOf(aliasMap);
  }

  @Override
  public Optional<TextCommandDefinition> findDefinition(TextCommandType type) {
    return Optional.ofNullable(definitions.get(type));
  }

  @Override
  public Optional<TextCommandDefinition> findDefinitionByAlias(String alias) {
    if (alias == null || alias.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(definitionsByAlias.get(normalizeAlias(alias)));
  }

  private static String normalizeAlias(String alias) {
    if (alias == null || alias.isBlank()) {
      throw new IllegalArgumentException("alias must not be blank");
    }
    return alias.trim().toLowerCase(Locale.ROOT);
  }
}
