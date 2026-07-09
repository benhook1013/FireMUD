package net.firedevops.firemud.gamesession.command.text;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
final class RegistryBackedTextCommandMetadataResolver implements TextCommandMetadataResolver {
  private final TextCommandRegistry textCommandRegistry;

  RegistryBackedTextCommandMetadataResolver(TextCommandRegistry textCommandRegistry) {
    this.textCommandRegistry = textCommandRegistry;
  }

  @Override
  public Optional<ResolvedTextCommandMetadata> resolve(String commandIdOrAlias) {
    if (commandIdOrAlias == null || commandIdOrAlias.isBlank()) {
      return Optional.empty();
    }
    return textCommandRegistry
        .findDefinition(commandIdOrAlias)
        .or(() -> textCommandRegistry.findDefinitionByAlias(commandIdOrAlias))
        .map(
            definition ->
                new ResolvedTextCommandMetadata(
                    definition.dispatchGroup(),
                    definition.actionCategory(),
                    definition.actionTags()));
  }
}
