package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Legacy run-owned fixture catalog evidence used by the initial-bind fixture path.
 *
 * <p>Published Game Design authority is stored and read through {@link
 * PublishedRealmCatalogSnapshot}; this row is never promoted into that authority.
 */
public record InitialAdmissionBindCatalog(
    UUID realmId,
    long tenantId,
    long gameTemplateId,
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    long catalogRevision,
    UUID playableStateNamespaceId,
    boolean visible,
    boolean publicProductionRealm,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    Instant createdAt) {}
