package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

/** Structured REALMS browse payload for late rendering. */
public record RealmBrowseViewOutput(String worldSlug, List<RealmEntry> realms)
    implements PlayerOutputPayload {
  public RealmBrowseViewOutput {
    Objects.requireNonNull(worldSlug, "worldSlug must not be null");
    realms = List.copyOf(Objects.requireNonNull(realms, "realms must not be null"));
  }

  public record RealmEntry(
      String realmSlug,
      String displayName,
      long gameInstanceId,
      boolean requiresCharacterSelection,
      String stateScope,
      String characterCreationPolicy) {
    public RealmEntry {
      Objects.requireNonNull(realmSlug, "realmSlug must not be null");
      Objects.requireNonNull(displayName, "displayName must not be null");
      Objects.requireNonNull(stateScope, "stateScope must not be null");
      Objects.requireNonNull(characterCreationPolicy, "characterCreationPolicy must not be null");
    }
  }
}
