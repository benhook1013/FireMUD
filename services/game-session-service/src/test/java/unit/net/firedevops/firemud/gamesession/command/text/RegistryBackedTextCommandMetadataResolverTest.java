package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RegistryBackedTextCommandMetadataResolverTest {
  private final TextCommandDefinitionProvider authoredActionsProvider =
      () ->
          List.of(
              new TextCommandDefinition(
                  "wave",
                  TextCommandType.AUTHORED,
                  List.of("wave"),
                  TextCommandDispatchGroup.AUTHORED,
                  TextCommandStageRequirement.GAMEPLAY,
                  TextCommandPromptPolicy.WHEN_GAMEPLAY,
                  TextCommandActionCategory.SOCIAL,
                  List.of(TextCommandActionTag.COMMUNICATION),
                  TextCommandSource.GAME_AUTHORED));

  private final RegistryBackedTextCommandMetadataResolver resolver =
      new RegistryBackedTextCommandMetadataResolver(
          new AggregatingTextCommandRegistry(
              List.of(new BuiltInTextCommandDefinitionProvider(), authoredActionsProvider)));

  @Test
  void resolvesBuiltInAliasMetadata() {
    assertThat(resolver.resolve("LoGoFf"))
        .get()
        .satisfies(
            metadata -> {
              assertThat(metadata.actionCategory()).isEqualTo(TextCommandActionCategory.META);
              assertThat(metadata.actionTags()).containsExactly(TextCommandActionTag.SESSION);
            });
  }

  @Test
  void resolvesAuthoredCommandIdMetadata() {
    assertThat(resolver.resolve("wave"))
        .get()
        .satisfies(
            metadata -> {
              assertThat(metadata.actionCategory()).isEqualTo(TextCommandActionCategory.SOCIAL);
              assertThat(metadata.actionTags()).containsExactly(TextCommandActionTag.COMMUNICATION);
            });
  }
}
