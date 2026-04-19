package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

/** Structured CHARS browse payload for late rendering. */
public record CharacterBrowseViewOutput(
    String worldSlug,
    String realmSlug,
    String stateScope,
    String characterCreationPolicy,
    List<CharacterEntry> characters)
    implements PlayerOutputPayload {
  public CharacterBrowseViewOutput {
    Objects.requireNonNull(worldSlug, "worldSlug must not be null");
    Objects.requireNonNull(realmSlug, "realmSlug must not be null");
    Objects.requireNonNull(stateScope, "stateScope must not be null");
    Objects.requireNonNull(characterCreationPolicy, "characterCreationPolicy must not be null");
    characters = List.copyOf(Objects.requireNonNull(characters, "characters must not be null"));
  }

  public record CharacterEntry(int ordinal, String characterId, String characterName, int level) {
    public CharacterEntry {
      if (ordinal < 1) {
        throw new IllegalArgumentException("ordinal must be at least 1");
      }
      Objects.requireNonNull(characterId, "characterId must not be null");
      Objects.requireNonNull(characterName, "characterName must not be null");
    }
  }
}
