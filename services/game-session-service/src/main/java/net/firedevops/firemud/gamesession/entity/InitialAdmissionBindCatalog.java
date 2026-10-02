package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import java.util.UUID;

/** Persisted, immutable revision-one public catalog identity used by an initial pointer bind. */
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
