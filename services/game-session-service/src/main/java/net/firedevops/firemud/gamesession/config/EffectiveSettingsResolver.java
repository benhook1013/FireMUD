package net.firedevops.firemud.gamesession.config;

import java.util.Objects;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Resolves first-pass effective gameplay settings for Game Session-owned domains. */
@Component
public class EffectiveSettingsResolver {
  private final PresentationProperties presentationDefaults;
  private final MovementProperties movementDefaults;
  private final WorldTopologyProperties worldTopologyDefaults;
  private final GameSessionSettingsOverridesProperties overrides;

  public EffectiveSettingsResolver(
      PresentationProperties presentationDefaults,
      MovementProperties movementDefaults,
      WorldTopologyProperties worldTopologyDefaults,
      GameSessionSettingsOverridesProperties overrides) {
    this.presentationDefaults =
        Objects.requireNonNull(presentationDefaults, "presentationDefaults must not be null");
    this.movementDefaults =
        Objects.requireNonNull(movementDefaults, "movementDefaults must not be null");
    this.worldTopologyDefaults =
        Objects.requireNonNull(worldTopologyDefaults, "worldTopologyDefaults must not be null");
    this.overrides = Objects.requireNonNull(overrides, "overrides must not be null");
  }

  public PresentationProperties presentation(SessionContext context) {
    PresentationProperties effective = presentationDefaults;
    effective =
        merge(effective, lookup(overrides.presentationByTenant(), resolveTenantKey(context)));
    effective =
        merge(
            effective,
            lookup(overrides.presentationByGameInstance(), resolveGameInstanceKey(context)));
    return effective;
  }

  public MovementProperties movement(SessionContext context) {
    MovementProperties effective = movementDefaults;
    effective = merge(effective, lookup(overrides.movementByTenant(), resolveTenantKey(context)));
    effective =
        merge(
            effective, lookup(overrides.movementByGameInstance(), resolveGameInstanceKey(context)));
    return effective;
  }

  public WorldTopologyProperties worldTopology(SessionContext context) {
    WorldTopologyProperties effective = worldTopologyDefaults;
    effective =
        merge(effective, lookup(overrides.worldTopologyByTenant(), resolveTenantKey(context)));
    effective =
        merge(
            effective,
            lookup(overrides.worldTopologyByGameInstance(), resolveGameInstanceKey(context)));
    return effective;
  }

  private static <T> T lookup(java.util.Map<String, T> source, String key) {
    if (!StringUtils.hasText(key)) {
      return null;
    }
    return source.get(key);
  }

  private PresentationProperties merge(
      PresentationProperties base,
      GameSessionSettingsOverridesProperties.PresentationOverride override) {
    if (override == null) {
      return base;
    }
    PresentationProperties.Prompt prompt = merge(base.prompt(), override.prompt());
    return new PresentationProperties(
        StringUtils.hasText(override.defaultLocaleTag())
            ? override.defaultLocaleTag()
            : base.defaultLocaleTag(),
        override.defaultColorMode() != null ? override.defaultColorMode() : base.defaultColorMode(),
        override.briefEnabledByDefault() != null
            ? override.briefEnabledByDefault()
            : base.briefEnabledByDefault(),
        prompt);
  }

  private PresentationProperties.Prompt merge(
      PresentationProperties.Prompt base,
      GameSessionSettingsOverridesProperties.PresentationOverride.PromptOverride override) {
    if (override == null) {
      return base;
    }
    return new PresentationProperties.Prompt(
        override.enabled() != null ? override.enabled() : base.enabled(),
        override.emitAfterReconnectRestore() != null
            ? override.emitAfterReconnectRestore()
            : base.emitAfterReconnectRestore(),
        override.coalesceWindowMs() != null
            ? override.coalesceWindowMs()
            : base.coalesceWindowMs());
  }

  private MovementProperties merge(
      MovementProperties base, GameSessionSettingsOverridesProperties.MovementOverride override) {
    if (override == null || override.postMoveLookEnabled() == null) {
      return base;
    }
    return new MovementProperties(override.postMoveLookEnabled());
  }

  private WorldTopologyProperties merge(
      WorldTopologyProperties base,
      GameSessionSettingsOverridesProperties.WorldTopologyOverride override) {
    if (override == null) {
      return base;
    }
    return new WorldTopologyProperties(
        override.scopeModel() != null ? override.scopeModel() : base.scopeModel(),
        override.regionsEnabled() != null ? override.regionsEnabled() : base.regionsEnabled());
  }

  private String resolveTenantKey(SessionContext context) {
    return context != null && context.tenantId() > 0 ? Long.toString(context.tenantId()) : null;
  }

  private String resolveGameInstanceKey(SessionContext context) {
    if (context == null) {
      return null;
    }
    if (context.gameInstanceId() > 0) {
      return Long.toString(context.gameInstanceId());
    }
    if (context.bootstrapGameInstanceId() > 0) {
      return Long.toString(context.bootstrapGameInstanceId());
    }
    return null;
  }
}
