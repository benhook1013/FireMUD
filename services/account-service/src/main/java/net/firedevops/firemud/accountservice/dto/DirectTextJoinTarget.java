package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/**
 * Game Session's complete selected-target evidence, re-resolved by Account before scope issuance.
 */
public record DirectTextJoinTarget(
    UUID tenantId,
    UUID realmId,
    String worldSlug,
    String realmSlug,
    UUID playableStateNamespaceId,
    String playableStateScope,
    UUID gameInstanceId,
    long catalogRevision,
    long pointerVersion) {}
