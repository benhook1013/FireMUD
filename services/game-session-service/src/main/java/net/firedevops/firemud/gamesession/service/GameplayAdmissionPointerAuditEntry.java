package net.firedevops.firemud.gamesession.service;

import java.time.Instant;

public record GameplayAdmissionPointerAuditEntry(
    String worldSlug,
    String realmSlug,
    String worldDisplayName,
    String realmDisplayName,
    long tenantId,
    long gameInstanceId,
    long pointerVersion,
    boolean visible,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    String actorPrincipal,
    String reason,
    String controlPlaneRequestId,
    Instant occurredAt) {}
