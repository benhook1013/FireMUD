package net.firedevops.firemud.accountservice.dto;

/** Realm visible to a bootstrap-authenticated first-party client. */
public record BootstrapRealmDto(
    String worldSlug,
    String realmSlug,
    String displayName,
    long tenantId,
    long gameInstanceId,
    long pointerVersion,
    boolean requiresCharacterSelection,
    String evaluatedAt,
    String connectScopeExpiresAt,
    String connectScopeId) {}
