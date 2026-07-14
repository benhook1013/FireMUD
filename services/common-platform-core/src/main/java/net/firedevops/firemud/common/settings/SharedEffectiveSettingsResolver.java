package net.firedevops.firemud.common.settings;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.util.StringUtils;

/** Resolves one bounded effective persisted-override layer for surfaced pre-06 settings domains. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards the injected settings authority reader before use.")
public class SharedEffectiveSettingsResolver {
  private final SharedSettingsAuthorityReader settingsAuthorityReader;

  public SharedEffectiveSettingsResolver(SharedSettingsAuthorityReader settingsAuthorityReader) {
    this.settingsAuthorityReader =
        Objects.requireNonNull(settingsAuthorityReader, "settingsAuthorityReader must not be null");
  }

  public ResolvedScopedSettings resolve(long tenantId, Long gameInstanceId) {
    return resolveInternal(tenantId, gameInstanceId, false);
  }

  public ResolvedScopedSettings refresh(long tenantId, Long gameInstanceId) {
    return resolveInternal(tenantId, gameInstanceId, true);
  }

  public void invalidate(long tenantId, Long gameInstanceId) {
    settingsAuthorityReader.invalidateOverrides(tenantId, normalizeGameInstanceId(gameInstanceId));
  }

  private ResolvedScopedSettings resolveInternal(
      long tenantId, Long gameInstanceId, boolean forceRefresh) {
    if (tenantId <= 0L) {
      return new ResolvedScopedSettings(
          ScopedSettingsOverrides.empty(),
          ScopedSettingsOverrides.empty(),
          ScopedSettingsOverrides.empty());
    }

    Long normalizedGameInstanceId = normalizeGameInstanceId(gameInstanceId);
    ScopedSettingsSnapshot snapshot =
        forceRefresh
            ? settingsAuthorityReader.refreshOverrides(tenantId, normalizedGameInstanceId)
            : settingsAuthorityReader.readOverrides(tenantId, normalizedGameInstanceId);

    ScopedSettingsOverrides effective = snapshot.tenantOverrides();
    if (!snapshot.gameInstanceOverrides().isEmpty()) {
      effective = merge(effective, snapshot.gameInstanceOverrides());
    }
    return new ResolvedScopedSettings(
        effective, snapshot.tenantOverrides(), snapshot.gameInstanceOverrides());
  }

  private ScopedSettingsOverrides merge(
      ScopedSettingsOverrides base, ScopedSettingsOverrides overrideLayer) {
    if (overrideLayer == null || overrideLayer.isEmpty()) {
      return base == null ? ScopedSettingsOverrides.empty() : base;
    }
    ScopedSettingsOverrides normalizedBase = base == null ? ScopedSettingsOverrides.empty() : base;
    return new ScopedSettingsOverrides(
        merge(normalizedBase.reconnection(), overrideLayer.reconnection()),
        merge(normalizedBase.communication(), overrideLayer.communication()),
        merge(normalizedBase.presentation(), overrideLayer.presentation()),
        merge(normalizedBase.movement(), overrideLayer.movement()),
        merge(normalizedBase.worldTopology(), overrideLayer.worldTopology()),
        merge(normalizedBase.commandHistory(), overrideLayer.commandHistory()),
        merge(normalizedBase.commandCapabilities(), overrideLayer.commandCapabilities()));
  }

  private ScopedSettingsOverrides.ReconnectionOverride merge(
      ScopedSettingsOverrides.ReconnectionOverride base,
      ScopedSettingsOverrides.ReconnectionOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.ReconnectionOverride(
        merge(base.policy(), override.policy()), merge(base.buffer(), override.buffer()));
  }

  private ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride merge(
      ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride base,
      ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride(
        override.resumeWindowMs() != null ? override.resumeWindowMs() : base.resumeWindowMs(),
        override.staleResumeFallsThroughToFreshEntry() != null
            ? override.staleResumeFallsThroughToFreshEntry()
            : base.staleResumeFallsThroughToFreshEntry());
  }

  private ScopedSettingsOverrides.ReconnectionOverride.BufferOverride merge(
      ScopedSettingsOverrides.ReconnectionOverride.BufferOverride base,
      ScopedSettingsOverrides.ReconnectionOverride.BufferOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
        override.ttlMs() != null ? override.ttlMs() : base.ttlMs(),
        override.minMessages() != null ? override.minMessages() : base.minMessages(),
        override.minLines() != null ? override.minLines() : base.minLines(),
        override.softMaxBytes() != null ? override.softMaxBytes() : base.softMaxBytes(),
        override.hardMaxBytes() != null ? override.hardMaxBytes() : base.hardMaxBytes());
  }

  private ScopedSettingsOverrides.CommunicationOverride merge(
      ScopedSettingsOverrides.CommunicationOverride base,
      ScopedSettingsOverrides.CommunicationOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.CommunicationOverride(
        override.maxMessageLength() != null ? override.maxMessageLength() : base.maxMessageLength(),
        override.whisperObserverMetadataEnabled() != null
            ? override.whisperObserverMetadataEnabled()
            : base.whisperObserverMetadataEnabled());
  }

  private ScopedSettingsOverrides.PresentationOverride merge(
      ScopedSettingsOverrides.PresentationOverride base,
      ScopedSettingsOverrides.PresentationOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.PresentationOverride(
        StringUtils.hasText(override.defaultLocaleTag())
            ? override.defaultLocaleTag()
            : base.defaultLocaleTag(),
        override.defaultColorMode() != null ? override.defaultColorMode() : base.defaultColorMode(),
        override.briefEnabledByDefault() != null
            ? override.briefEnabledByDefault()
            : base.briefEnabledByDefault(),
        merge(base.prompt(), override.prompt()));
  }

  private ScopedSettingsOverrides.PresentationOverride.PromptOverride merge(
      ScopedSettingsOverrides.PresentationOverride.PromptOverride base,
      ScopedSettingsOverrides.PresentationOverride.PromptOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.PresentationOverride.PromptOverride(
        override.enabled() != null ? override.enabled() : base.enabled(),
        override.emitAfterReconnectRestore() != null
            ? override.emitAfterReconnectRestore()
            : base.emitAfterReconnectRestore(),
        override.coalesceWindowMs() != null
            ? override.coalesceWindowMs()
            : base.coalesceWindowMs());
  }

  private ScopedSettingsOverrides.MovementOverride merge(
      ScopedSettingsOverrides.MovementOverride base,
      ScopedSettingsOverrides.MovementOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.MovementOverride(
        override.postMoveLookEnabled() != null
            ? override.postMoveLookEnabled()
            : base.postMoveLookEnabled());
  }

  private ScopedSettingsOverrides.WorldTopologyOverride merge(
      ScopedSettingsOverrides.WorldTopologyOverride base,
      ScopedSettingsOverrides.WorldTopologyOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.WorldTopologyOverride(
        override.scopeModel() != null ? override.scopeModel() : base.scopeModel(),
        override.regionsEnabled() != null ? override.regionsEnabled() : base.regionsEnabled());
  }

  private ScopedSettingsOverrides.CommandHistoryOverride merge(
      ScopedSettingsOverrides.CommandHistoryOverride base,
      ScopedSettingsOverrides.CommandHistoryOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.CommandHistoryOverride(
        override.maxEntries() != null ? override.maxEntries() : base.maxEntries());
  }

  private ScopedSettingsOverrides.CommandCapabilitiesOverride merge(
      ScopedSettingsOverrides.CommandCapabilitiesOverride base,
      ScopedSettingsOverrides.CommandCapabilitiesOverride override) {
    if (override == null) {
      return base;
    }
    if (base == null) {
      return override;
    }
    return new ScopedSettingsOverrides.CommandCapabilitiesOverride(
        override.socialEnabled() != null ? override.socialEnabled() : base.socialEnabled(),
        override.presenceEnabled() != null ? override.presenceEnabled() : base.presenceEnabled(),
        override.inventoryEnabled() != null ? override.inventoryEnabled() : base.inventoryEnabled(),
        override.commandHistoryEnabled() != null
            ? override.commandHistoryEnabled()
            : base.commandHistoryEnabled());
  }

  private static Long normalizeGameInstanceId(Long gameInstanceId) {
    return gameInstanceId != null && gameInstanceId > 0L ? gameInstanceId : null;
  }

  public record ResolvedScopedSettings(
      ScopedSettingsOverrides effectiveOverrides,
      ScopedSettingsOverrides tenantOverrides,
      ScopedSettingsOverrides gameInstanceOverrides) {
    public ResolvedScopedSettings {
      effectiveOverrides =
          effectiveOverrides == null ? ScopedSettingsOverrides.empty() : effectiveOverrides;
      tenantOverrides = tenantOverrides == null ? ScopedSettingsOverrides.empty() : tenantOverrides;
      gameInstanceOverrides =
          gameInstanceOverrides == null ? ScopedSettingsOverrides.empty() : gameInstanceOverrides;
    }

    public List<String> sourcesFor(
        ScopedSettingsOverrides.SettingsDomain domain, long tenantId, Long gameInstanceId) {
      List<String> sources = new ArrayList<>();
      if (hasOverride(tenantOverrides, domain)) {
        sources.add("tenantPersistedOverride:" + tenantId);
      }
      if (gameInstanceId != null && hasOverride(gameInstanceOverrides, domain)) {
        sources.add("gameInstancePersistedOverride:" + gameInstanceId);
      }
      return List.copyOf(sources);
    }

    private boolean hasOverride(
        ScopedSettingsOverrides overrides, ScopedSettingsOverrides.SettingsDomain domain) {
      return switch (domain) {
        case RECONNECTION -> overrides.reconnection() != null;
        case COMMUNICATION -> overrides.communication() != null;
        case PRESENTATION -> overrides.presentation() != null;
        case MOVEMENT -> overrides.movement() != null;
        case WORLD_TOPOLOGY -> overrides.worldTopology() != null;
        case COMMAND_HISTORY -> overrides.commandHistory() != null;
        case COMMAND_CAPABILITIES -> overrides.commandCapabilities() != null;
      };
    }
  }
}
