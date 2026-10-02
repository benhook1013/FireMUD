package net.firedevops.firemud.gamesession.service;

import java.time.Instant;
import java.util.UUID;

public record GameplayAdmissionPointerAuditEntry(
    String worldSlug,
    String realmSlug,
    String worldDisplayName,
    String realmDisplayName,
    long tenantId,
    long gameInstanceId,
    long pointerVersion,
    boolean visible,
    boolean publicProductionRealm,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    String actorPrincipal,
    String reason,
    String controlPlaneRequestId,
    String preparedVersionUpgradeId,
    Long catalogRevision,
    UUID realmId,
    UUID playableStateNamespaceId,
    Instant occurredAt) {}
