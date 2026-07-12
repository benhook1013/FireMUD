package net.firedevops.firemud.common.command;

import java.util.Set;

/** Canonical scalar constraints for authored command effect declarations. */
public final class CommandEffectDeclarationConstraints {
  public static final Set<String> SUPPORTED_MODIFIER_OPERATIONS =
      Set.of("ADD", "MULTIPLY", "CLAMP_MIN", "CLAMP_MAX", "GRANT_FLAG", "GRANT_CONDITION");
  public static final int MIN_DURATION_SECONDS = 1;
  public static final int MAX_DURATION_SECONDS = 3600;

  private CommandEffectDeclarationConstraints() {}

  public static boolean isIdentifier(String value) {
    return value != null && value.matches("[A-Za-z][A-Za-z0-9_]{0,63}");
  }

  public static boolean isValidDurationSeconds(int value) {
    return value >= MIN_DURATION_SECONDS && value <= MAX_DURATION_SECONDS;
  }
}
