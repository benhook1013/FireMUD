package net.firedevops.firemud.gamesession.command.text;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class BuiltInTextCommandAliasResolver {
  private final TextCommandRegistry textCommandRegistry;

  public BuiltInTextCommandAliasResolver(TextCommandRegistry textCommandRegistry) {
    this.textCommandRegistry = textCommandRegistry;
  }

  public static BuiltInTextCommandAliasResolver unsupported() {
    return new BuiltInTextCommandAliasResolver(null);
  }

  public Optional<String> resolve(String alias) {
    if (textCommandRegistry == null) {
      return Optional.empty();
    }
    if (alias == null || alias.isBlank()) {
      return Optional.empty();
    }
    String normalized = normalize(alias);
    return textCommandRegistry
        .findDefinitionByAlias(normalized)
        .filter(definition -> definition.source() == TextCommandSource.PLATFORM_BUILT_IN)
        .filter(definition -> definition.type() != TextCommandType.AUTHORED)
        .map(ignored -> normalized);
  }

  private static String normalize(String alias) {
    return alias.trim().toLowerCase(Locale.ROOT);
  }
}
