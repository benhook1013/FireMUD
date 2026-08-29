package net.firedevops.firemud.common.settings;

/** Shared persisted tenant/game override payloads for surfaced pre-06 settings domains. */
public record ScopedSettingsOverrides(
    ReconnectionOverride reconnection,
    CommunicationOverride communication,
    PresentationOverride presentation,
    MovementOverride movement,
    WorldTopologyOverride worldTopology,
    CommandHistoryOverride commandHistory,
    CommandCapabilitiesOverride commandCapabilities) {

  public static final long MAX_PROMPT_COALESCE_WINDOW_MS = 1_000L;

  public ScopedSettingsOverrides(
      ReconnectionOverride reconnection,
      CommunicationOverride communication,
      PresentationOverride presentation,
      MovementOverride movement,
      WorldTopologyOverride worldTopology,
      CommandHistoryOverride commandHistory) {
    this(reconnection, communication, presentation, movement, worldTopology, commandHistory, null);
  }

  public ScopedSettingsOverrides(
      ReconnectionOverride reconnection,
      CommunicationOverride communication,
      PresentationOverride presentation,
      MovementOverride movement,
      WorldTopologyOverride worldTopology) {
    this(reconnection, communication, presentation, movement, worldTopology, null, null);
  }

  public static ScopedSettingsOverrides empty() {
    return new ScopedSettingsOverrides(null, null, null, null, null, null, null);
  }

  public boolean isEmpty() {
    return reconnection == null
        && communication == null
        && presentation == null
        && movement == null
        && worldTopology == null
        && commandHistory == null
        && commandCapabilities == null;
  }

  public enum SettingsDomain {
    RECONNECTION,
    COMMUNICATION,
    PRESENTATION,
    MOVEMENT,
    WORLD_TOPOLOGY,
    COMMAND_HISTORY,
    COMMAND_CAPABILITIES
  }

  public record ReconnectionOverride(PolicyOverride policy, BufferOverride buffer) {
    public boolean isEmpty() {
      return (policy == null || policy.isEmpty()) && (buffer == null || buffer.isEmpty());
    }

    public record PolicyOverride(Long resumeWindowMs, Boolean staleResumeFallsThroughToFreshEntry) {
      public boolean isEmpty() {
        return resumeWindowMs == null && staleResumeFallsThroughToFreshEntry == null;
      }
    }

    public record BufferOverride(
        Long ttlMs,
        Integer maxEntries,
        Integer minMessages,
        Integer minLines,
        Integer softMaxBytes,
        Integer hardMaxBytes) {
      public boolean isEmpty() {
        return ttlMs == null
            && maxEntries == null
            && minMessages == null
            && minLines == null
            && softMaxBytes == null
            && hardMaxBytes == null;
      }
    }
  }

  public record CommunicationOverride(
      Integer maxMessageLength, Boolean whisperObserverMetadataEnabled) {
    public boolean isEmpty() {
      return maxMessageLength == null && whisperObserverMetadataEnabled == null;
    }
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

  public record CommandHistoryOverride(Integer maxEntries) {
    public boolean isEmpty() {
      return maxEntries == null;
    }
  }

  public record CommandCapabilitiesOverride(
      Boolean socialEnabled,
      Boolean presenceEnabled,
      Boolean inventoryEnabled,
      Boolean commandHistoryEnabled) {
    public boolean isEmpty() {
      return socialEnabled == null
          && presenceEnabled == null
          && inventoryEnabled == null
          && commandHistoryEnabled == null;
    }
  }
}
