package net.firedevops.firemud.accountservice.dto;

/** Realm visible to a bootstrap-authenticated first-party client. */
public record BootstrapRealmDto(
    String worldSlug,
    String realmSlug,
    String realmId,
    String displayName,
    long tenantId,
    long gameInstanceId,
    long pointerVersion,
    long catalogRevision,
    String playableStateNamespaceId,
    String playableStateScope,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    String evaluatedAt,
    String connectScopeExpiresAt,
    String connectScopeId) {}
