package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextCommandEffectDeclarationTest {
  @Test
  void applyActionStateRejectsInvalidExecutionInvariants() {
    assertThatThrownBy(
            () ->
                new TextCommandEffectDeclaration.ApplyActionState(
                    " ", Duration.ofSeconds(1), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("conditionKey must not be null or blank");
    assertThatThrownBy(
            () ->
                new TextCommandEffectDeclaration.ApplyActionState(
                    "stunned", Duration.ZERO, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duration must be positive");
    assertThatThrownBy(
            () ->
                new TextCommandEffectDeclaration.ApplyActionState(
                    "stunned", Duration.ofSeconds(-1), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duration must be positive");
  }
}
