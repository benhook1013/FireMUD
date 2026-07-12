package net.firedevops.firemud.gamesession.command.text;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** A runtime-safe typed execution effect carried from an admitted command definition. */
sealed interface TextCommandEffectDeclaration
    permits TextCommandEffectDeclaration.ApplyActionState {
  /** Applies one bounded condition carrying shared effect-engine modifier data to the caller. */
  record ApplyActionState(String conditionKey, Duration duration, List<Modifier> modifiers)
      implements TextCommandEffectDeclaration {
    public ApplyActionState {
      modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
    }
  }

  /** A modifier in the shared Entity Management effect-payload grammar. */
  record Modifier(
      String operation,
      String targetKey,
      BigDecimal value,
      String scopeKind,
      String scopeKey,
      int priority) {}
}
