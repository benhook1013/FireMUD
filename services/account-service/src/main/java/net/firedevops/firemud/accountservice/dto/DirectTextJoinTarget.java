package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/**
 * Game Session's complete selected-target evidence, re-resolved by Account before scope issuance.
 */
public record DirectTextJoinTarget(
    long tenantId,
    UUID realmId,
    String worldSlug,
    String realmSlug,
    String playableStateNamespaceId,
    String playableStateScope,
    long gameInstanceId,
    long catalogRevision,
    long pointerVersion) {}
