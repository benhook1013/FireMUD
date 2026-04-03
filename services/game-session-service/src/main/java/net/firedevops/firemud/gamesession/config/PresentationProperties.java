package net.firedevops.firemud.gamesession.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Player-facing presentation defaults for the first renderer/model proof. */
@ConfigurationProperties(prefix = "firemud.presentation")
public record PresentationProperties(
    ColorMode defaultColorMode, boolean briefEnabledByDefault, Prompt prompt) {

  public PresentationProperties {
    defaultColorMode = defaultColorMode == null ? ColorMode.NONE : defaultColorMode;
    prompt = prompt == null ? new Prompt(true, true, 150L) : prompt.normalize();
  }

  public enum ColorMode {
    NONE,
    BASIC,
    RICH
  }

  public record Prompt(boolean enabled, boolean emitAfterReconnectRestore, long coalesceWindowMs) {

    Prompt normalize() {
      return new Prompt(
          enabled, emitAfterReconnectRestore, coalesceWindowMs > 0 ? coalesceWindowMs : 150L);
    }
  }
}
