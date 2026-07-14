package net.firedevops.firemud.common.settings;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.config.FiremudCommandCapabilitiesProperties;

/**
 * Resolves standard player-command capability policy from operator defaults and persisted scope.
 */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification = "Constructor validation protects the shared settings read boundary.")
public class EffectiveCommandCapabilitiesSettingsResolver {
  private final FiremudCommandCapabilitiesProperties defaults;
  private final SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver;

  public EffectiveCommandCapabilitiesSettingsResolver(
      FiremudCommandCapabilitiesProperties defaults,
      SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver) {
    this.defaults = Objects.requireNonNull(defaults, "defaults must not be null");
    this.sharedEffectiveSettingsResolver =
        Objects.requireNonNull(
            sharedEffectiveSettingsResolver, "sharedEffectiveSettingsResolver must not be null");
  }

  public EffectiveCommandCapabilitiesSettingsResolver(
      FiremudCommandCapabilitiesProperties defaults,
      SharedSettingsAuthorityReader settingsAuthorityReader) {
    this(defaults, new SharedEffectiveSettingsResolver(settingsAuthorityReader));
  }

  public boolean isEnabled(PlayerCommandCapability capability, long tenantId, Long gameInstanceId) {
    return switch (Objects.requireNonNull(capability, "capability must not be null")) {
      case MANDATORY -> true;
      case SOCIAL -> capabilities(tenantId, gameInstanceId).socialEnabled();
      case PRESENCE -> capabilities(tenantId, gameInstanceId).presenceEnabled();
      case INVENTORY -> capabilities(tenantId, gameInstanceId).inventoryEnabled();
      case COMMAND_HISTORY -> capabilities(tenantId, gameInstanceId).commandHistoryEnabled();
    };
  }

  public FiremudCommandCapabilitiesProperties capabilities(long tenantId, Long gameInstanceId) {
    return resolvedCapabilities(tenantId, gameInstanceId).effective();
  }

  public ResolvedValue<FiremudCommandCapabilitiesProperties> resolvedCapabilities(
      long tenantId, Long gameInstanceId) {
    if (tenantId <= 0L) {
      return new ResolvedValue<>(defaults, List.of("operatorDefaults"));
    }
    SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides =
        sharedEffectiveSettingsResolver.resolve(tenantId, gameInstanceId);
    ScopedSettingsOverrides.CommandCapabilitiesOverride override =
        persistedOverrides.effectiveOverrides().commandCapabilities();
    if (override == null) {
      return new ResolvedValue<>(defaults, List.of("operatorDefaults"));
    }
    return new ResolvedValue<>(
        merge(defaults, override), sources(persistedOverrides, tenantId, gameInstanceId));
  }

  private List<String> sources(
      SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides,
      long tenantId,
      Long gameInstanceId) {
    List<String> sources = new java.util.ArrayList<>();
    sources.add("operatorDefaults");
    sources.addAll(
        persistedOverrides.sourcesFor(
            ScopedSettingsOverrides.SettingsDomain.COMMAND_CAPABILITIES, tenantId, gameInstanceId));
    return List.copyOf(sources);
  }

  private FiremudCommandCapabilitiesProperties merge(
      FiremudCommandCapabilitiesProperties base,
      ScopedSettingsOverrides.CommandCapabilitiesOverride override) {
    return new FiremudCommandCapabilitiesProperties(
        override.socialEnabled() != null ? override.socialEnabled() : base.socialEnabled(),
        override.presenceEnabled() != null ? override.presenceEnabled() : base.presenceEnabled(),
        override.inventoryEnabled() != null ? override.inventoryEnabled() : base.inventoryEnabled(),
        override.commandHistoryEnabled() != null
            ? override.commandHistoryEnabled()
            : base.commandHistoryEnabled());
  }

  public record ResolvedValue<T>(T effective, List<String> sources) {
    public ResolvedValue {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }
}
