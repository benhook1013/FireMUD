package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Builds an instance-scoped command registry from built-ins and the admitted release artifact. */
@Component
public final class AdmittedTextCommandRegistryResolver {
  private final AdmittedCommandDefinitionReader admittedCommandDefinitionReader;
  private final ConfiguredAuthoredActionDefinitionProvider testFixtureDefinitionProvider;
  private final Environment environment;
  private final TextCommandRegistry builtIns =
      new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider()));

  AdmittedTextCommandRegistryResolver(
      AdmittedCommandDefinitionReader admittedCommandDefinitionReader,
      ConfiguredAuthoredActionDefinitionProvider testFixtureDefinitionProvider,
      Environment environment) {
    this.admittedCommandDefinitionReader = admittedCommandDefinitionReader;
    this.testFixtureDefinitionProvider = testFixtureDefinitionProvider;
    this.environment = environment;
  }

  public TextCommandRegistry resolve(SessionContext context) {
    List<TextCommandDefinition> resolvedDefinitions = definitions(context);
    if (resolvedDefinitions.isEmpty() && environment.acceptsProfiles(Profiles.of("test"))) {
      resolvedDefinitions = testFixtureDefinitionProvider.definitions();
    }
    if (resolvedDefinitions.isEmpty()) {
      return builtIns;
    }
    List<TextCommandDefinition> registryDefinitions = resolvedDefinitions;
    try {
      return new AggregatingTextCommandRegistry(
          List.of(new BuiltInTextCommandDefinitionProvider(), () -> registryDefinitions));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return builtIns;
    }
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
