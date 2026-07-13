package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdmittedTextCommandRegistryResolverTest {
  @Mock private AdmittedCommandDefinitionReader admittedCommandDefinitionReader;

  @Test
  void fallsBackToBuiltInsWhenNoArtifactIsAdmitted() {
    when(admittedCommandDefinitionReader.definitionsFor(null)).thenReturn(Optional.empty());

    TextCommandRegistry registry = resolver().resolve(null);

    assertThat(registry.findDefinitionByAlias("look")).isPresent();
    assertThat(registry.findDefinitionByAlias("salute")).isEmpty();
  }

  @Test
  void keepsAValidEmptyAdmittedArtifactLimitedToBuiltIns() {
    when(admittedCommandDefinitionReader.definitionsFor(null)).thenReturn(Optional.of(List.of()));

    TextCommandRegistry registry = resolver().resolve(null);

    assertThat(registry.findDefinitionByAlias("look")).isPresent();
    assertThat(registry.findDefinitionByAlias("salute")).isEmpty();
  }

  @Test
  void fallsBackToBuiltInsWhenAnAdmittedDefinitionCollidesWithABuiltIn() {
    TextCommandDefinition collidingDefinition =
        new BuiltInTextCommandDefinitionProvider().definitions().getFirst();
    when(admittedCommandDefinitionReader.definitionsFor(null))
        .thenReturn(Optional.of(List.of(collidingDefinition)));

    TextCommandRegistry registry = resolver().resolve(null);

    assertThat(registry.findDefinitionByAlias("look")).isPresent();
  }

  private AdmittedTextCommandRegistryResolver resolver() {
    return new AdmittedTextCommandRegistryResolver(admittedCommandDefinitionReader);
  }
}
