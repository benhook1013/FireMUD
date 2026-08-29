package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Player-facing presentation defaults for the first renderer/model proof. */
@ConfigurationProperties(prefix = "firemud.presentation")
public record PresentationProperties(
    String defaultLocaleTag,
    ColorMode defaultColorMode,
    boolean briefEnabledByDefault,
    Prompt prompt) {

  public PresentationProperties {
    defaultLocaleTag =
        defaultLocaleTag == null || defaultLocaleTag.isBlank() ? "en-NZ" : defaultLocaleTag;
    defaultColorMode = defaultColorMode == null ? ColorMode.NONE : defaultColorMode;
    prompt = prompt == null ? new Prompt(true, true, 150L) : prompt.normalize();
  }

  public PresentationProperties(
      ColorMode defaultColorMode, boolean briefEnabledByDefault, Prompt prompt) {
    this("en-NZ", defaultColorMode, briefEnabledByDefault, prompt);
  }

  public PresentationProperties() {
    this("en-NZ", ColorMode.NONE, false, new Prompt(true, true, 150L));
  }

  public enum ColorMode {
    NONE,
    BASIC,
    RICH
  }

  public record Prompt(boolean enabled, boolean emitAfterReconnectRestore, long coalesceWindowMs) {

    Prompt normalize() {
      long boundedCoalesceWindowMs =
          coalesceWindowMs > 0 ? coalesceWindowMs : 150L;
      return new Prompt(
          enabled,
          emitAfterReconnectRestore,
          Math.min(
              boundedCoalesceWindowMs,
              ScopedSettingsOverrides.MAX_PROMPT_COALESCE_WINDOW_MS));
    }
  }
}
