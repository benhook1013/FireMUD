package net.firedevops.firemud.gamedesign.dto;

import java.time.Instant;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

public record PluginVersionStatusEventDto(
    String eventId,
    String tenantId,
    String pluginId,
    String pluginVersionId,
    VersionLifecycleState previousPublicationState,
    VersionLifecycleState newPublicationState,
    String statusReason,
    Instant observedAt) {}
