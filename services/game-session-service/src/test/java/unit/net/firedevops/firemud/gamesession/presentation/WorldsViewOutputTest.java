package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldsViewOutputTest {
  @Test
  void worldsAreCopiedDefensively() {
    List<WorldsViewOutput.WorldEntry> worlds =
        new java.util.ArrayList<>(
            List.of(new WorldsViewOutput.WorldEntry("123", "Demo World", 1L, false)));

    WorldsViewOutput output = new WorldsViewOutput(worlds);
    worlds.clear();

    assertThat(output.worlds()).hasSize(1);
    assertThat(output.worlds().getFirst().slug()).isEqualTo("123");
  }

  @Test
  void worldEntryCarriesNoListOrdinal() {
    assertThat(
            Arrays.stream(WorldsViewOutput.WorldEntry.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList())
        .containsExactly("slug", "displayName", "gameInstanceId", "requiresCharacterSelection");
  }
}
