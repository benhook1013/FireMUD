package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/** Immutable Account-owned target snapshot retained for an explicit public-production JOIN. */
public record VerifiedJoinScope(
    String connectScopeId,
    long accountId,
    long tenantId,
    UUID realmId,
    String worldSlug,
    String realmSlug,
    String playableStateNamespaceId,
    String playableStateScope,
    long gameInstanceId,
    long catalogRevision,
    long pointerVersion,
    String evaluatedAt,
    String connectScopeExpiresAt,
    String snapshotDigest) {}
