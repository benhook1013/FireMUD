package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;

public record AdmissionPointerDto(
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    Long tenantId,
    Long gameInstanceId,
    long pointerVersion,
    boolean visible,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    String actorPrincipal,
    String reason,
    String controlPlaneRequestId,
    String preparedVersionUpgradeId,
    Instant occurredAt) {}
