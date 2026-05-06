package net.firedevops.firemud.common.security;

public record GameplaySessionAttestationClaims(
    String attestationType,
    String tenantId,
    String sessionId,
    String accountId,
    String characterId,
    String gameInstanceId,
    String roomInstanceId,
    String worldSlug,
    String realmSlug,
    String pointerVersion,
    String playableStateScope) {}
