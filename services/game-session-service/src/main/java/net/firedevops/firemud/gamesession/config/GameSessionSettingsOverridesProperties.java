package net.firedevops.firemud.gamesession.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** File/env-backed scoped overrides used by the first effective-settings resolver. */
@ConfigurationProperties(prefix = "firemud.settings-overrides")
public record GameSessionSettingsOverridesProperties(
    Map<String, PresentationOverride> presentationByTenant,
    Map<String, PresentationOverride> presentationByGameInstance,
    Map<String, MovementOverride> movementByTenant,
    Map<String, MovementOverride> movementByGameInstance,
    Map<String, WorldTopologyOverride> worldTopologyByTenant,
    Map<String, WorldTopologyOverride> worldTopologyByGameInstance) {
  public GameSessionSettingsOverridesProperties {
    presentationByTenant = copyNormalized(presentationByTenant);
    presentationByGameInstance = copyNormalized(presentationByGameInstance);
    movementByTenant = copyNormalized(movementByTenant);
    movementByGameInstance = copyNormalized(movementByGameInstance);
    worldTopologyByTenant = copyNormalized(worldTopologyByTenant);
    worldTopologyByGameInstance = copyNormalized(worldTopologyByGameInstance);
  }

  private static <T> Map<String, T> copyNormalized(Map<String, T> source) {
    return source == null ? Map.of() : Map.copyOf(source);
  }

  public record PresentationOverride(
      String defaultLocaleTag,
      PresentationProperties.ColorMode defaultColorMode,
      Boolean briefEnabledByDefault,
      PromptOverride prompt) {
    public PresentationOverride {
      prompt = prompt == null ? null : prompt.normalize();
    }

    public record PromptOverride(
        Boolean enabled, Boolean emitAfterReconnectRestore, Long coalesceWindowMs) {
      PromptOverride normalize() {
        return new PromptOverride(
            enabled,
            emitAfterReconnectRestore,
            coalesceWindowMs != null && coalesceWindowMs > 0 ? coalesceWindowMs : coalesceWindowMs);
      }
    }
  }

  public record MovementOverride(Boolean postMoveLookEnabled) {}

  public record WorldTopologyOverride(
      WorldTopologyProperties.ScopeModel scopeModel, Boolean regionsEnabled) {}
}
