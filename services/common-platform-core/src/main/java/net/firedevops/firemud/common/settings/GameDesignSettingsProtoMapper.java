package net.firedevops.firemud.common.settings;

import net.firedevops.firemud.gamedesign.v1.CommandCapabilitiesSettingsOverride;
import net.firedevops.firemud.gamedesign.v1.CommandHistorySettingsOverride;
import net.firedevops.firemud.gamedesign.v1.CommunicationSettingsOverride;
import net.firedevops.firemud.gamedesign.v1.GetScopedSettingsOverridesResponse;
import net.firedevops.firemud.gamedesign.v1.MovementSettingsOverride;
import net.firedevops.firemud.gamedesign.v1.PresentationColorMode;
import net.firedevops.firemud.gamedesign.v1.PresentationPromptOverride;
import net.firedevops.firemud.gamedesign.v1.PresentationSettingsOverride;
import net.firedevops.firemud.gamedesign.v1.ReconnectionBufferOverride;
import net.firedevops.firemud.gamedesign.v1.ReconnectionPolicyOverride;
import net.firedevops.firemud.gamedesign.v1.ReconnectionSettingsOverride;
import net.firedevops.firemud.gamedesign.v1.SettingsDomain;
import net.firedevops.firemud.gamedesign.v1.SettingsOverrides;
import net.firedevops.firemud.gamedesign.v1.WorldTopologyScopeModel;
import net.firedevops.firemud.gamedesign.v1.WorldTopologySettingsOverride;

/** Maps the shared persisted settings model to and from Game Design gRPC messages. */
public final class GameDesignSettingsProtoMapper {
  private GameDesignSettingsProtoMapper() {}

  public static ScopedSettingsSnapshot fromProto(GetScopedSettingsOverridesResponse response) {
    return new ScopedSettingsSnapshot(
        response.hasTenantOverrides()
            ? fromProto(response.getTenantOverrides())
            : ScopedSettingsOverrides.empty(),
        response.hasGameInstanceOverrides()
            ? fromProto(response.getGameInstanceOverrides())
            : ScopedSettingsOverrides.empty());
  }

  public static ScopedSettingsOverrides fromProto(SettingsOverrides source) {
    if (source == null) {
      return ScopedSettingsOverrides.empty();
    }
    return new ScopedSettingsOverrides(
        source.hasReconnection() ? fromProto(source.getReconnection()) : null,
        source.hasCommunication() ? fromProto(source.getCommunication()) : null,
        source.hasPresentation() ? fromProto(source.getPresentation()) : null,
        source.hasMovement() ? fromProto(source.getMovement()) : null,
        source.hasWorldTopology() ? fromProto(source.getWorldTopology()) : null,
        source.hasCommandHistory() ? fromProto(source.getCommandHistory()) : null,
        source.hasCommandCapabilities() ? fromProto(source.getCommandCapabilities()) : null);
  }

  public static SettingsOverrides toProto(ScopedSettingsOverrides source) {
    SettingsOverrides.Builder builder = SettingsOverrides.newBuilder();
    if (source == null || source.isEmpty()) {
      return builder.build();
    }
    if (source.reconnection() != null && !source.reconnection().isEmpty()) {
      builder.setReconnection(toProto(source.reconnection()));
    }
    if (source.communication() != null && !source.communication().isEmpty()) {
      builder.setCommunication(toProto(source.communication()));
    }
    if (source.presentation() != null && !source.presentation().isEmpty()) {
      builder.setPresentation(toProto(source.presentation()));
    }
    if (source.movement() != null && !source.movement().isEmpty()) {
      builder.setMovement(toProto(source.movement()));
    }
    if (source.worldTopology() != null && !source.worldTopology().isEmpty()) {
      builder.setWorldTopology(toProto(source.worldTopology()));
    }
    if (source.commandHistory() != null && !source.commandHistory().isEmpty()) {
      builder.setCommandHistory(toProto(source.commandHistory()));
    }
    if (source.commandCapabilities() != null && !source.commandCapabilities().isEmpty()) {
      builder.setCommandCapabilities(toProto(source.commandCapabilities()));
    }
    return builder.build();
  }

  public static SettingsDomain toProto(ScopedSettingsOverrides.SettingsDomain domain) {
    return switch (domain) {
      case RECONNECTION -> SettingsDomain.SETTINGS_DOMAIN_RECONNECTION;
      case COMMUNICATION -> SettingsDomain.SETTINGS_DOMAIN_COMMUNICATION;
      case PRESENTATION -> SettingsDomain.SETTINGS_DOMAIN_PRESENTATION;
      case MOVEMENT -> SettingsDomain.SETTINGS_DOMAIN_MOVEMENT;
      case WORLD_TOPOLOGY -> SettingsDomain.SETTINGS_DOMAIN_WORLD_TOPOLOGY;
      case COMMAND_HISTORY -> SettingsDomain.SETTINGS_DOMAIN_COMMAND_HISTORY;
      case COMMAND_CAPABILITIES -> SettingsDomain.SETTINGS_DOMAIN_COMMAND_CAPABILITIES;
    };
  }

  public static ScopedSettingsOverrides.SettingsDomain fromProto(SettingsDomain domain) {
    return switch (domain) {
      case SETTINGS_DOMAIN_RECONNECTION -> ScopedSettingsOverrides.SettingsDomain.RECONNECTION;
      case SETTINGS_DOMAIN_COMMUNICATION -> ScopedSettingsOverrides.SettingsDomain.COMMUNICATION;
      case SETTINGS_DOMAIN_PRESENTATION -> ScopedSettingsOverrides.SettingsDomain.PRESENTATION;
      case SETTINGS_DOMAIN_MOVEMENT -> ScopedSettingsOverrides.SettingsDomain.MOVEMENT;
      case SETTINGS_DOMAIN_WORLD_TOPOLOGY -> ScopedSettingsOverrides.SettingsDomain.WORLD_TOPOLOGY;
      case SETTINGS_DOMAIN_COMMAND_HISTORY ->
          ScopedSettingsOverrides.SettingsDomain.COMMAND_HISTORY;
      case SETTINGS_DOMAIN_COMMAND_CAPABILITIES ->
          ScopedSettingsOverrides.SettingsDomain.COMMAND_CAPABILITIES;
      default -> throw new IllegalArgumentException("Unsupported settings domain: " + domain);
    };
  }

  private static ScopedSettingsOverrides.ReconnectionOverride fromProto(
      ReconnectionSettingsOverride source) {
    return new ScopedSettingsOverrides.ReconnectionOverride(
        source.hasPolicy() ? fromProto(source.getPolicy()) : null,
        source.hasBuffer() ? fromProto(source.getBuffer()) : null);
  }

  private static ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride fromProto(
      ReconnectionPolicyOverride source) {
    return new ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride(
        source.hasResumeWindowMs() ? source.getResumeWindowMs() : null,
        source.hasStaleResumeFallsThroughToFreshEntry()
            ? source.getStaleResumeFallsThroughToFreshEntry()
            : null);
  }

  private static ScopedSettingsOverrides.ReconnectionOverride.BufferOverride fromProto(
      ReconnectionBufferOverride source) {
    return new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
        source.hasTtlMs() ? source.getTtlMs() : null,
        source.hasMinMessages() ? source.getMinMessages() : null,
        source.hasMinLines() ? source.getMinLines() : null,
        source.hasSoftMaxBytes() ? source.getSoftMaxBytes() : null,
        source.hasHardMaxBytes() ? source.getHardMaxBytes() : null);
  }

  private static ScopedSettingsOverrides.CommunicationOverride fromProto(
      CommunicationSettingsOverride source) {
    return new ScopedSettingsOverrides.CommunicationOverride(
        source.hasMaxMessageLength() ? source.getMaxMessageLength() : null,
        source.hasWhisperObserverMetadataEnabled()
            ? source.getWhisperObserverMetadataEnabled()
            : null);
  }

  private static ScopedSettingsOverrides.PresentationOverride fromProto(
      PresentationSettingsOverride source) {
    return new ScopedSettingsOverrides.PresentationOverride(
        source.hasDefaultLocaleTag() ? source.getDefaultLocaleTag() : null,
        source.hasDefaultColorMode() ? fromProto(source.getDefaultColorMode()) : null,
        source.hasBriefEnabledByDefault() ? source.getBriefEnabledByDefault() : null,
        source.hasPrompt() ? fromProto(source.getPrompt()) : null);
  }

  private static ScopedSettingsOverrides.PresentationOverride.PromptOverride fromProto(
      PresentationPromptOverride source) {
    return new ScopedSettingsOverrides.PresentationOverride.PromptOverride(
        source.hasEnabled() ? source.getEnabled() : null,
        source.hasEmitAfterReconnectRestore() ? source.getEmitAfterReconnectRestore() : null,
        source.hasCoalesceWindowMs() ? source.getCoalesceWindowMs() : null);
  }

  private static ScopedSettingsOverrides.MovementOverride fromProto(
      MovementSettingsOverride source) {
    return new ScopedSettingsOverrides.MovementOverride(
        source.hasPostMoveLookEnabled() ? source.getPostMoveLookEnabled() : null);
  }

  private static ScopedSettingsOverrides.WorldTopologyOverride fromProto(
      WorldTopologySettingsOverride source) {
    return new ScopedSettingsOverrides.WorldTopologyOverride(
        source.hasScopeModel() ? fromProto(source.getScopeModel()) : null,
        source.hasRegionsEnabled() ? source.getRegionsEnabled() : null);
  }

  private static ScopedSettingsOverrides.CommandHistoryOverride fromProto(
      CommandHistorySettingsOverride source) {
    return new ScopedSettingsOverrides.CommandHistoryOverride(
        source.hasMaxEntries() ? source.getMaxEntries() : null);
  }

  private static ScopedSettingsOverrides.CommandCapabilitiesOverride fromProto(
      CommandCapabilitiesSettingsOverride source) {
    return new ScopedSettingsOverrides.CommandCapabilitiesOverride(
        source.hasSocialEnabled() ? source.getSocialEnabled() : null,
        source.hasPresenceEnabled() ? source.getPresenceEnabled() : null,
        source.hasInventoryEnabled() ? source.getInventoryEnabled() : null,
        source.hasCommandHistoryEnabled() ? source.getCommandHistoryEnabled() : null);
  }

  private static ReconnectionSettingsOverride toProto(
      ScopedSettingsOverrides.ReconnectionOverride source) {
    ReconnectionSettingsOverride.Builder builder = ReconnectionSettingsOverride.newBuilder();
    if (source.policy() != null) {
      builder.setPolicy(toProto(source.policy()));
    }
    if (source.buffer() != null) {
      builder.setBuffer(toProto(source.buffer()));
    }
    return builder.build();
  }

  private static CommandHistorySettingsOverride toProto(
      ScopedSettingsOverrides.CommandHistoryOverride source) {
    CommandHistorySettingsOverride.Builder builder = CommandHistorySettingsOverride.newBuilder();
    if (source.maxEntries() != null) {
      builder.setMaxEntries(source.maxEntries());
    }
    return builder.build();
  }

  private static CommandCapabilitiesSettingsOverride toProto(
      ScopedSettingsOverrides.CommandCapabilitiesOverride source) {
    CommandCapabilitiesSettingsOverride.Builder builder =
        CommandCapabilitiesSettingsOverride.newBuilder();
    if (source.socialEnabled() != null) {
      builder.setSocialEnabled(source.socialEnabled());
    }
    if (source.presenceEnabled() != null) {
      builder.setPresenceEnabled(source.presenceEnabled());
    }
    if (source.inventoryEnabled() != null) {
      builder.setInventoryEnabled(source.inventoryEnabled());
    }
    if (source.commandHistoryEnabled() != null) {
      builder.setCommandHistoryEnabled(source.commandHistoryEnabled());
    }
    return builder.build();
  }

  private static ReconnectionPolicyOverride toProto(
      ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride source) {
    ReconnectionPolicyOverride.Builder builder = ReconnectionPolicyOverride.newBuilder();
    if (source.resumeWindowMs() != null) {
      builder.setResumeWindowMs(source.resumeWindowMs());
    }
    if (source.staleResumeFallsThroughToFreshEntry() != null) {
      builder.setStaleResumeFallsThroughToFreshEntry(source.staleResumeFallsThroughToFreshEntry());
    }
    return builder.build();
  }

  private static ReconnectionBufferOverride toProto(
      ScopedSettingsOverrides.ReconnectionOverride.BufferOverride source) {
    ReconnectionBufferOverride.Builder builder = ReconnectionBufferOverride.newBuilder();
    if (source.ttlMs() != null) {
      builder.setTtlMs(source.ttlMs());
    }
    if (source.minMessages() != null) {
      builder.setMinMessages(source.minMessages());
    }
    if (source.minLines() != null) {
      builder.setMinLines(source.minLines());
    }
    if (source.softMaxBytes() != null) {
      builder.setSoftMaxBytes(source.softMaxBytes());
    }
    if (source.hardMaxBytes() != null) {
      builder.setHardMaxBytes(source.hardMaxBytes());
    }
    return builder.build();
  }

  private static CommunicationSettingsOverride toProto(
      ScopedSettingsOverrides.CommunicationOverride source) {
    CommunicationSettingsOverride.Builder builder = CommunicationSettingsOverride.newBuilder();
    if (source.maxMessageLength() != null) {
      builder.setMaxMessageLength(source.maxMessageLength());
    }
    if (source.whisperObserverMetadataEnabled() != null) {
      builder.setWhisperObserverMetadataEnabled(source.whisperObserverMetadataEnabled());
    }
    return builder.build();
  }

  private static PresentationSettingsOverride toProto(
      ScopedSettingsOverrides.PresentationOverride source) {
    PresentationSettingsOverride.Builder builder = PresentationSettingsOverride.newBuilder();
    if (source.defaultLocaleTag() != null && !source.defaultLocaleTag().isBlank()) {
      builder.setDefaultLocaleTag(source.defaultLocaleTag());
    }
    if (source.defaultColorMode() != null) {
      builder.setDefaultColorMode(toProto(source.defaultColorMode()));
    }
    if (source.briefEnabledByDefault() != null) {
      builder.setBriefEnabledByDefault(source.briefEnabledByDefault());
    }
    if (source.prompt() != null) {
      builder.setPrompt(toProto(source.prompt()));
    }
    return builder.build();
  }

  private static PresentationPromptOverride toProto(
      ScopedSettingsOverrides.PresentationOverride.PromptOverride source) {
    PresentationPromptOverride.Builder builder = PresentationPromptOverride.newBuilder();
    if (source.enabled() != null) {
      builder.setEnabled(source.enabled());
    }
    if (source.emitAfterReconnectRestore() != null) {
      builder.setEmitAfterReconnectRestore(source.emitAfterReconnectRestore());
    }
    if (source.coalesceWindowMs() != null) {
      builder.setCoalesceWindowMs(source.coalesceWindowMs());
    }
    return builder.build();
  }

  private static MovementSettingsOverride toProto(ScopedSettingsOverrides.MovementOverride source) {
    MovementSettingsOverride.Builder builder = MovementSettingsOverride.newBuilder();
    if (source.postMoveLookEnabled() != null) {
      builder.setPostMoveLookEnabled(source.postMoveLookEnabled());
    }
    return builder.build();
  }

  private static WorldTopologySettingsOverride toProto(
      ScopedSettingsOverrides.WorldTopologyOverride source) {
    WorldTopologySettingsOverride.Builder builder = WorldTopologySettingsOverride.newBuilder();
    if (source.scopeModel() != null) {
      builder.setScopeModel(toProto(source.scopeModel()));
    }
    if (source.regionsEnabled() != null) {
      builder.setRegionsEnabled(source.regionsEnabled());
    }
    return builder.build();
  }

  private static ScopedSettingsOverrides.PresentationOverride.ColorMode fromProto(
      PresentationColorMode value) {
    return switch (value) {
      case PRESENTATION_COLOR_MODE_NONE ->
          ScopedSettingsOverrides.PresentationOverride.ColorMode.NONE;
      case PRESENTATION_COLOR_MODE_BASIC ->
          ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC;
      case PRESENTATION_COLOR_MODE_RICH ->
          ScopedSettingsOverrides.PresentationOverride.ColorMode.RICH;
      default ->
          throw new IllegalArgumentException("Unsupported presentation color mode: " + value);
    };
  }

  private static PresentationColorMode toProto(
      ScopedSettingsOverrides.PresentationOverride.ColorMode value) {
    return switch (value) {
      case NONE -> PresentationColorMode.PRESENTATION_COLOR_MODE_NONE;
      case BASIC -> PresentationColorMode.PRESENTATION_COLOR_MODE_BASIC;
      case RICH -> PresentationColorMode.PRESENTATION_COLOR_MODE_RICH;
    };
  }

  private static ScopedSettingsOverrides.WorldTopologyOverride.ScopeModel fromProto(
      WorldTopologyScopeModel value) {
    return switch (value) {
      case WORLD_TOPOLOGY_SCOPE_MODEL_MAP_ONLY ->
          ScopedSettingsOverrides.WorldTopologyOverride.ScopeModel.MAP_ONLY;
      case WORLD_TOPOLOGY_SCOPE_MODEL_AREA_AND_MAP ->
          ScopedSettingsOverrides.WorldTopologyOverride.ScopeModel.AREA_AND_MAP;
      case WORLD_TOPOLOGY_SCOPE_MODEL_REGION_AREA_AND_MAP ->
          ScopedSettingsOverrides.WorldTopologyOverride.ScopeModel.REGION_AREA_AND_MAP;
      default ->
          throw new IllegalArgumentException("Unsupported world topology scope model: " + value);
    };
  }

  private static WorldTopologyScopeModel toProto(
      ScopedSettingsOverrides.WorldTopologyOverride.ScopeModel value) {
    return switch (value) {
      case MAP_ONLY -> WorldTopologyScopeModel.WORLD_TOPOLOGY_SCOPE_MODEL_MAP_ONLY;
      case AREA_AND_MAP -> WorldTopologyScopeModel.WORLD_TOPOLOGY_SCOPE_MODEL_AREA_AND_MAP;
      case REGION_AREA_AND_MAP ->
          WorldTopologyScopeModel.WORLD_TOPOLOGY_SCOPE_MODEL_REGION_AREA_AND_MAP;
    };
  }
}
