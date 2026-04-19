package net.firedevops.firemud.gamesession.service;

public record GameplayAdmissionPointerSnapshot(
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    long tenantId,
    long gameInstanceId,
    long pointerVersion,
    boolean visible,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy) {}
