package net.firedevops.firemud.gamesession.service;

public record GameplayAdmissionPointerMutation(
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    long tenantId,
    long gameInstanceId,
    boolean visible,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    String actorPrincipal,
    String reason,
    String controlPlaneRequestId,
    Long expectedPointerVersion) {}
