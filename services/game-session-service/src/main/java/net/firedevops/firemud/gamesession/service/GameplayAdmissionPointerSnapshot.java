package net.firedevops.firemud.gamesession.service;

import java.util.UUID;

public record GameplayAdmissionPointerSnapshot(
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    long tenantId,
    long gameInstanceId,
    long pointerVersion,
    boolean visible,
    boolean publicProductionRealm,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    long catalogRevision,
    UUID realmId,
    UUID playableStateNamespaceId,
    Integer representationVersion) {
  public static final int ORDINARY_REPRESENTATION_VERSION = 1;

  /** Creates a synthetic snapshot without persisted pointer representation evidence. */
  public GameplayAdmissionPointerSnapshot(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId,
      long pointerVersion,
      boolean visible,
      boolean publicProductionRealm,
      boolean requiresCharacterSelection,
      String stateScope,
      String characterCreationPolicy,
      long catalogRevision,
      UUID realmId,
      UUID playableStateNamespaceId) {
    this(
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        tenantId,
        gameInstanceId,
        pointerVersion,
        visible,
        publicProductionRealm,
        requiresCharacterSelection,
        stateScope,
        characterCreationPolicy,
        catalogRevision,
        realmId,
        playableStateNamespaceId,
        null);
  }

  /** Creates a synthetic snapshot without catalog identity/revision authority. */
  public GameplayAdmissionPointerSnapshot(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId,
      long pointerVersion,
      boolean visible,
      boolean publicProductionRealm,
      boolean requiresCharacterSelection,
      String stateScope,
      String characterCreationPolicy,
      long catalogRevision) {
    this(
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        tenantId,
        gameInstanceId,
        pointerVersion,
        visible,
        publicProductionRealm,
        requiresCharacterSelection,
        stateScope,
        characterCreationPolicy,
        catalogRevision,
        null,
        null);
  }

  /** Creates a synthetic snapshot without catalog authority for legacy test fixtures. */
  public GameplayAdmissionPointerSnapshot(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId,
      long pointerVersion,
      boolean visible,
      boolean publicProductionRealm,
      boolean requiresCharacterSelection,
      String stateScope,
      String characterCreationPolicy) {
    this(
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        tenantId,
        gameInstanceId,
        pointerVersion,
        visible,
        publicProductionRealm,
        requiresCharacterSelection,
        stateScope,
        characterCreationPolicy,
        0L);
  }
}
