package net.firedevops.firemud.gamedesign.entity;

import java.util.UUID;

/** Insert-only storage record for frozen published realm-entry policy evidence. */
public record PublishedRealmEntryPolicy(
    UUID policyId,
    UUID canonicalTenantId,
    String tenantIdentityProvenanceKind,
    long sourceGameRowId,
    String sourceGameTenantKey,
    long versionId,
    int versionNumber,
    long releaseBundleId,
    long sourceRevisionId,
    String releaseBundleIdentity,
    String publishWorkflowId,
    String manifestHash,
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    boolean visible,
    boolean publicProduction,
    String stateScope,
    String entryPolicy,
    String policyJson,
    String policyDigest) {}
