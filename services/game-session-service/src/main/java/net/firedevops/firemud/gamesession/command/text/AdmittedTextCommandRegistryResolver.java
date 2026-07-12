package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.gamesession.service.AuthoredCommandAdmission;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Builds an instance-scoped command registry from built-ins and the admitted release artifact. */
@Component
public final class AdmittedTextCommandRegistryResolver {
  private static final Logger LOG =
      LoggerFactory.getLogger(AdmittedTextCommandRegistryResolver.class);
  private final AdmittedCommandDefinitionReader admittedCommandDefinitionReader;
  private final Optional<ConfiguredAuthoredActionDefinitionProvider> testFixtureDefinitionProvider;
  private final Environment environment;
  private final TextCommandRegistry builtIns =
      new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider()));
  private final ConcurrentMap<List<TextCommandDefinition>, TextCommandRegistry>
      registriesByDefinitionSnapshot = new ConcurrentHashMap<>();

  AdmittedTextCommandRegistryResolver(
      AdmittedCommandDefinitionReader admittedCommandDefinitionReader,
      Optional<ConfiguredAuthoredActionDefinitionProvider> testFixtureDefinitionProvider,
      Environment environment) {
    this.admittedCommandDefinitionReader = admittedCommandDefinitionReader;
    this.testFixtureDefinitionProvider = testFixtureDefinitionProvider;
    this.environment = environment;
  }

  public TextCommandRegistry resolve(SessionContext context) {
    Optional<List<TextCommandDefinition>> admittedDefinitions =
        admittedCommandDefinitionReader.definitionsFor(context);
    List<TextCommandDefinition> resolvedDefinitions = admittedDefinitions.orElse(List.of());
    if (admittedDefinitions.isEmpty() && environment.acceptsProfiles(Profiles.of("test"))) {
      resolvedDefinitions =
          testFixtureDefinitionProvider
              .map(ConfiguredAuthoredActionDefinitionProvider::definitions)
              .orElse(List.of());
    }
    if (resolvedDefinitions.isEmpty()) {
      return builtIns;
    }
    try {
      List<TextCommandDefinition> registryDefinitions = List.copyOf(resolvedDefinitions);
      return registriesByDefinitionSnapshot.computeIfAbsent(
          registryDefinitions,
          definitions ->
              new AggregatingTextCommandRegistry(
                  List.of(new BuiltInTextCommandDefinitionProvider(), () -> definitions)));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      LOG.warn("Unable to build admitted text command registry; using built-in commands", ex);
      return builtIns;
    }
  }

  List<TextCommandDefinition> definitions(SessionContext context) {
    return admittedCommandDefinitionReader.definitionsFor(context).orElse(List.of());
  }

  public List<TextCommandDefinition> authoredDefinitions(SessionContext context) {
    return resolve(context).definitions().stream()
        .filter(definition -> definition.type() == TextCommandType.AUTHORED)
        .toList();
  }

  public Optional<TextCommandDefinition> resolveDefinition(SessionContext context, String token) {
    return resolveDefinition(resolve(context), token);
  }

  Optional<AuthoredCommandAdmission> resolveAdmission(SessionContext context, String commandId) {
    return admittedCommandDefinitionReader.admissionFor(context, commandId);
  }

  public Optional<TextCommandDefinition> resolveDefinition(
      TextCommandRegistry registry, String token) {
    return registry.findDefinitionByAlias(token).or(() -> registry.findDefinition(token));
  }

  public Optional<TextCommandMetadataResolver.ResolvedTextCommandMetadata> resolveMetadata(
      SessionContext context, String commandId) {
    return resolveMetadata(resolve(context), commandId);
  }

  public Optional<TextCommandMetadataResolver.ResolvedTextCommandMetadata> resolveMetadata(
      SessionContext context, String... commandTokens) {
    return resolveMetadata(resolve(context), commandTokens);
  }

  public Optional<TextCommandMetadataResolver.ResolvedTextCommandMetadata> resolveMetadata(
      TextCommandRegistry registry, String... commandTokens) {
    for (String commandToken : commandTokens) {
      Optional<TextCommandMetadataResolver.ResolvedTextCommandMetadata> metadata =
          resolveDefinition(registry, commandToken)
              .map(
                  definition ->
                      new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
                          definition.dispatchGroup(),
                          definition.actionCategory(),
                          definition.actionTags()));
      if (metadata.isPresent()) {
        return metadata;
      }
    }
    return Optional.empty();
  }
}
