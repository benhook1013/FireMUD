package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

/** Builds an instance-scoped command registry from built-ins and the admitted release artifact. */
@Component
public final class AdmittedTextCommandRegistryResolver {
  private final AdmittedCommandDefinitionReader admittedCommandDefinitionReader;
  private final TextCommandRegistry builtIns =
      new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider()));

  AdmittedTextCommandRegistryResolver(
      AdmittedCommandDefinitionReader admittedCommandDefinitionReader) {
    this.admittedCommandDefinitionReader = admittedCommandDefinitionReader;
  }

  public TextCommandRegistry resolve(SessionContext context) {
    List<TextCommandDefinition> definitions = definitions(context);
    return definitions.isEmpty()
        ? builtIns
        : new AggregatingTextCommandRegistry(
            List.of(new BuiltInTextCommandDefinitionProvider(), () -> definitions));
  }

  List<TextCommandDefinition> definitions(SessionContext context) {
    return admittedCommandDefinitionReader.definitionsFor(context).orElse(List.of());
  }

  public Optional<TextCommandMetadataResolver.ResolvedTextCommandMetadata> resolveMetadata(
      SessionContext context, String commandId) {
    return resolve(context)
        .findDefinition(commandId)
        .map(
            definition ->
                new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
                    definition.dispatchGroup(),
                    definition.actionCategory(),
                    definition.actionTags()));
  }
}
