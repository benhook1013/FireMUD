package net.firedevops.firemud.gamesession.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.SharedEffectiveSettingsResolver;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Resolves first-pass effective gameplay settings for Game Session-owned domains. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation fails fast for injected settings collaborators without exposing a"
            + " partially initialized resolver.")
@Component
public class EffectiveSettingsResolver {
  private final PresentationProperties presentationDefaults;
  private final MovementProperties movementDefaults;
  private final WorldTopologyProperties worldTopologyDefaults;
  private final SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver;

  @Autowired
  public EffectiveSettingsResolver(
      PresentationProperties presentationDefaults,
      MovementProperties movementDefaults,
      WorldTopologyProperties worldTopologyDefaults,
      SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver) {
    this.presentationDefaults =
        Objects.requireNonNull(presentationDefaults, "presentationDefaults must not be null");
    this.movementDefaults =
        Objects.requireNonNull(movementDefaults, "movementDefaults must not be null");
    this.worldTopologyDefaults =
        Objects.requireNonNull(worldTopologyDefaults, "worldTopologyDefaults must not be null");
    this.sharedEffectiveSettingsResolver =
        Objects.requireNonNull(
            sharedEffectiveSettingsResolver, "sharedEffectiveSettingsResolver must not be null");
  }

  public EffectiveSettingsResolver(
      PresentationProperties presentationDefaults,
      MovementProperties movementDefaults,
      WorldTopologyProperties worldTopologyDefaults,
      SharedSettingsAuthorityReader settingsAuthorityReader) {
    this(
        presentationDefaults,
        movementDefaults,
        worldTopologyDefaults,
        new SharedEffectiveSettingsResolver(settingsAuthorityReader));
  }

  public PresentationProperties presentation(SessionContext context) {
    return resolvedPresentation(context).effective();
  }

  public ResolvedValue<PresentationProperties> resolvedPresentation(SessionContext context) {
    SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides =
        resolvedPersistedOverrides(context);
    PresentationProperties effective =
        merge(presentationDefaults, persistedOverrides.effectiveOverrides().presentation());
    List<String> sources =
        sources(
            persistedOverrides,
            ScopedSettingsOverrides.SettingsDomain.PRESENTATION,
            context == null ? 0L : context.tenantId(),
            resolveGameInstanceId(context));
    return new ResolvedValue<>(effective, List.copyOf(sources));
  }

  public MovementProperties movement(SessionContext context) {
    return resolvedMovement(context).effective();
  }

  public ResolvedValue<MovementProperties> resolvedMovement(SessionContext context) {
    SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides =
        resolvedPersistedOverrides(context);
    MovementProperties effective =
        merge(movementDefaults, persistedOverrides.effectiveOverrides().movement());
    List<String> sources =
        sources(
            persistedOverrides,
            ScopedSettingsOverrides.SettingsDomain.MOVEMENT,
            context == null ? 0L : context.tenantId(),
            resolveGameInstanceId(context));
    return new ResolvedValue<>(effective, List.copyOf(sources));
  }

  public WorldTopologyProperties worldTopology(SessionContext context) {
    return resolvedWorldTopology(context).effective();
  }

  public ResolvedValue<WorldTopologyProperties> resolvedWorldTopology(SessionContext context) {
    SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides =
        resolvedPersistedOverrides(context);
    WorldTopologyProperties effective =
        merge(worldTopologyDefaults, persistedOverrides.effectiveOverrides().worldTopology());
    List<String> sources =
        sources(
            persistedOverrides,
            ScopedSettingsOverrides.SettingsDomain.WORLD_TOPOLOGY,
            context == null ? 0L : context.tenantId(),
            resolveGameInstanceId(context));
    return new ResolvedValue<>(effective, List.copyOf(sources));
  }

  public record ResolvedValue<T>(T effective, List<String> sources) {
    public ResolvedValue {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }

  private PresentationProperties merge(
      PresentationProperties base, ScopedSettingsOverrides.PresentationOverride override) {
    if (override == null) {
      return base;
    }
    PresentationProperties.Prompt prompt = merge(base.prompt(), override.prompt());
    return new PresentationProperties(
        StringUtils.hasText(override.defaultLocaleTag())
            ? override.defaultLocaleTag()
            : base.defaultLocaleTag(),
        override.defaultColorMode() != null
            ? map(override.defaultColorMode())
            : base.defaultColorMode(),
        override.briefEnabledByDefault() != null
            ? override.briefEnabledByDefault()
            : base.briefEnabledByDefault(),
        prompt);
  }

  private PresentationProperties.Prompt merge(
      PresentationProperties.Prompt base,
      ScopedSettingsOverrides.PresentationOverride.PromptOverride override) {
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
      MovementProperties base, ScopedSettingsOverrides.MovementOverride override) {
    if (override == null || override.postMoveLookEnabled() == null) {
      return base;
    }
    return new MovementProperties(override.postMoveLookEnabled());
  }

  private WorldTopologyProperties merge(
      WorldTopologyProperties base, ScopedSettingsOverrides.WorldTopologyOverride override) {
    if (override == null) {
      return base;
    }
    return new WorldTopologyProperties(
        override.scopeModel() != null ? map(override.scopeModel()) : base.scopeModel(),
        override.regionsEnabled() != null ? override.regionsEnabled() : base.regionsEnabled());
  }

  private SharedEffectiveSettingsResolver.ResolvedScopedSettings resolvedPersistedOverrides(
      SessionContext context) {
    if (context == null || context.tenantId() <= 0L) {
      return new SharedEffectiveSettingsResolver.ResolvedScopedSettings(
          ScopedSettingsOverrides.empty(),
          ScopedSettingsOverrides.empty(),
          ScopedSettingsOverrides.empty());
    }
    return sharedEffectiveSettingsResolver.resolve(
        context.tenantId(), resolveGameInstanceId(context));
  }

  private List<String> sources(
      SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides,
      ScopedSettingsOverrides.SettingsDomain domain,
      long tenantId,
      Long gameInstanceId) {
    List<String> sources = new ArrayList<>();
    sources.add("operatorDefaults");
    sources.addAll(persistedOverrides.sourcesFor(domain, tenantId, gameInstanceId));
    return sources;
  }

  private Long resolveGameInstanceId(SessionContext context) {
    if (context == null) {
      return null;
    }
    if (context.gameInstanceId() > 0) {
      return context.gameInstanceId();
    }
    if (context.bootstrapGameInstanceId() > 0) {
      return context.bootstrapGameInstanceId();
    }
    return null;
  }

  private PresentationProperties.ColorMode map(
      ScopedSettingsOverrides.PresentationOverride.ColorMode colorMode) {
    return switch (colorMode) {
      case NONE -> PresentationProperties.ColorMode.NONE;
      case BASIC -> PresentationProperties.ColorMode.BASIC;
      case RICH -> PresentationProperties.ColorMode.RICH;
    };
  }

  private WorldTopologyProperties.ScopeModel map(
      ScopedSettingsOverrides.WorldTopologyOverride.ScopeModel scopeModel) {
    return switch (scopeModel) {
      case MAP_ONLY -> WorldTopologyProperties.ScopeModel.MAP_ONLY;
      case AREA_AND_MAP -> WorldTopologyProperties.ScopeModel.AREA_AND_MAP;
      case REGION_AREA_AND_MAP -> WorldTopologyProperties.ScopeModel.REGION_AREA_AND_MAP;
    };
  }
}
