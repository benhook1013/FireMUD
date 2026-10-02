package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;

/** Compares the catalog-policy fields that determine an admission pointer's catalog revision. */
public final class GameplayAdmissionCatalogPolicy {
  private GameplayAdmissionCatalogPolicy() {}

  public static boolean matches(
      GameplayAdmissionPointer current, GameplayAdmissionPointer requested) {
    return fields(current).equals(fields(requested));
  }

  public static boolean matches(
      GameplayAdmissionPointer current, GameplayAdmissionPointerMutation requested) {
    return fields(current).equals(fields(requested));
  }

  private static CatalogFields fields(GameplayAdmissionPointer pointer) {
    return new CatalogFields(
        pointer.getWorldSlug(),
        pointer.getWorldDisplayName(),
        pointer.getRealmSlug(),
        pointer.getRealmDisplayName(),
        pointer.isVisible(),
        pointer.isPublicProductionRealm(),
        pointer.isRequiresCharacterSelection(),
        pointer.getStateScope(),
        pointer.getCharacterCreationPolicy());
  }

  private static CatalogFields fields(GameplayAdmissionPointerMutation mutation) {
    return new CatalogFields(
        mutation.worldSlug(),
        mutation.worldDisplayName(),
        mutation.realmSlug(),
        mutation.realmDisplayName(),
        mutation.visible(),
        mutation.publicProductionRealm(),
        mutation.requiresCharacterSelection(),
        mutation.stateScope(),
        mutation.characterCreationPolicy());
  }

  private record CatalogFields(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      boolean visible,
      boolean publicProductionRealm,
      boolean requiresCharacterSelection,
      String stateScope,
      String characterCreationPolicy) {}
}
