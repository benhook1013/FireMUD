package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.AuthoredActionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
class AdmittedTextCommandRegistryResolverTest {
  @Mock private AdmittedCommandDefinitionReader admittedCommandDefinitionReader;

  @Test
  void resolvesConfiguredAuthoredDefinitionsOnlyForTestFixturesWithoutAnArtifact() {
    when(admittedCommandDefinitionReader.definitionsFor(null))
        .thenReturn(java.util.Optional.empty());
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");

    TextCommandRegistry registry = resolver(environment).resolve(null);

    assertThat(registry.findDefinitionByAlias("salute")).isPresent();
  }

  @Test
  void doesNotResolveConfiguredAuthoredDefinitionsOutsideTestFixtures() {
    when(admittedCommandDefinitionReader.definitionsFor(null))
        .thenReturn(java.util.Optional.empty());

    TextCommandRegistry registry = resolver(new MockEnvironment()).resolve(null);

    assertThat(registry.findDefinitionByAlias("salute")).isEmpty();
  }

  @Test
  void keepsAValidEmptyAdmittedArtifactEmptyInTestFixtures() {
    when(admittedCommandDefinitionReader.definitionsFor(null)).thenReturn(Optional.of(List.of()));
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");

    TextCommandRegistry registry = resolver(environment).resolve(null);

    assertThat(registry.findDefinitionByAlias("salute")).isEmpty();
  }

  @Test
  void fallsBackToBuiltInsWhenNoTestFixtureProviderIsRegistered() {
    when(admittedCommandDefinitionReader.definitionsFor(null))
        .thenReturn(java.util.Optional.empty());
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");

    TextCommandRegistry registry =
        new AdmittedTextCommandRegistryResolver(
                admittedCommandDefinitionReader, Optional.empty(), environment)
            .resolve(null);

    assertThat(registry.findDefinitionByAlias("look")).isPresent();
    assertThat(registry.findDefinitionByAlias("salute")).isEmpty();
  }

  @Test
  void fallsBackToBuiltInsWhenAnAdmittedDefinitionCollidesWithABuiltIn() {
    TextCommandDefinition collidingDefinition =
        new BuiltInTextCommandDefinitionProvider().definitions().getFirst();
    when(admittedCommandDefinitionReader.definitionsFor(null))
        .thenReturn(Optional.of(List.of(collidingDefinition)));

    TextCommandRegistry registry = resolver(new MockEnvironment()).resolve(null);

    assertThat(registry.findDefinitionByAlias("look")).isPresent();
    assertThat(registry.findDefinitionByAlias("salute")).isEmpty();
  }

  private AdmittedTextCommandRegistryResolver resolver(MockEnvironment environment) {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action salute = new AuthoredActionProperties.Action();
    salute.setActionId("wave-salute");
    salute.setCommandId("wave-salute");
    salute.setAliases(List.of("salute"));
    properties.setActions(List.of(salute));
    return new AdmittedTextCommandRegistryResolver(
        admittedCommandDefinitionReader,
        Optional.of(
            new ConfiguredAuthoredActionDefinitionProvider(
                new ConfiguredAuthoredActionCatalog(properties))),
        environment);
  }
}
