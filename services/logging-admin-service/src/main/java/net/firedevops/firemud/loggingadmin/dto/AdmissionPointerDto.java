package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;
import java.util.UUID;

public record AdmissionPointerDto(
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    Long tenantId,
    Long gameInstanceId,
    long pointerVersion,
    Long catalogRevision,
    UUID realmId,
    UUID playableStateNamespaceId,
    boolean visible,
    boolean publicProductionRealm,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    String actorPrincipal,
    String reason,
    String controlPlaneRequestId,
    String preparedVersionUpgradeId,
    Instant occurredAt) {}
