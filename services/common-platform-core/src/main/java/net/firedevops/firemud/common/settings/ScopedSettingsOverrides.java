package net.firedevops.firemud.common.settings;

/** Shared persisted tenant/game override payloads for surfaced pre-06 settings domains. */
public record ScopedSettingsOverrides(
    ReconnectionOverride reconnection,
    CommunicationOverride communication,
    PresentationOverride presentation,
    MovementOverride movement,
    WorldTopologyOverride worldTopology) {

  public static ScopedSettingsOverrides empty() {
    return new ScopedSettingsOverrides(null, null, null, null, null);
  }

  public boolean isEmpty() {
    return reconnection == null
        && communication == null
        && presentation == null
        && movement == null
        && worldTopology == null;
  }

  public enum SettingsDomain {
    RECONNECTION,
    COMMUNICATION,
    PRESENTATION,
    MOVEMENT,
    WORLD_TOPOLOGY
  }

  public record ReconnectionOverride(PolicyOverride policy, BufferOverride buffer) {
    public boolean isEmpty() {
      return policy == null && buffer == null;
    }

    public record PolicyOverride(
        Long resumeWindowMs, Boolean staleResumeFallsThroughToFreshEntry) {}

    public record BufferOverride(
        Long ttlMs,
        Integer minMessages,
        Integer minLines,
        Integer softMaxBytes,
        Integer hardMaxBytes) {}
  }

  public record CommunicationOverride(Integer maxMessageLength, DefaultsOverride defaults) {
    public boolean isEmpty() {
      return maxMessageLength == null && defaults == null;
    }

    public record DefaultsOverride(
        Boolean sayEnabled,
        Boolean whisperEnabled,
        Boolean tellEnabled,
        Boolean whisperObserverMetadataEnabled) {}
  }

  public record PresentationOverride(
      String defaultLocaleTag,
      ColorMode defaultColorMode,
      Boolean briefEnabledByDefault,
      PromptOverride prompt) {
    public boolean isEmpty() {
      return (defaultLocaleTag == null || defaultLocaleTag.isBlank())
          && defaultColorMode == null
          && briefEnabledByDefault == null
          && prompt == null;
    }

    public enum ColorMode {
      NONE,
      BASIC,
      RICH
    }

    public record PromptOverride(
        Boolean enabled, Boolean emitAfterReconnectRestore, Long coalesceWindowMs) {}
  }

  public record MovementOverride(Boolean postMoveLookEnabled) {
    public boolean isEmpty() {
      return postMoveLookEnabled == null;
    }
  }

  public record WorldTopologyOverride(ScopeModel scopeModel, Boolean regionsEnabled) {
    public boolean isEmpty() {
      return scopeModel == null && regionsEnabled == null;
    }

    public enum ScopeModel {
      MAP_ONLY,
      AREA_AND_MAP,
      REGION_AREA_AND_MAP
    }
  }
}
