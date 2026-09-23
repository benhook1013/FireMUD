package net.firedevops.firemud.accountservice.dto;

/**
 * Game Session's complete selected-target evidence, re-resolved by Account before scope issuance.
 */
public record DirectTextJoinTarget(
    long tenantId,
    long realmId,
    String worldSlug,
    String realmSlug,
    String playableStateNamespaceId,
    String playableStateScope,
    long gameInstanceId,
    long catalogRevision,
    long pointerVersion) {}
