package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldsViewOutputTest {
  @Test
  void worldsAreCopiedDefensively() {
    List<WorldsViewOutput.WorldEntry> worlds =
        new java.util.ArrayList<>(
            List.of(new WorldsViewOutput.WorldEntry(1, "demo", "Demo World", 1L, false)));

    WorldsViewOutput output = new WorldsViewOutput(worlds);
    worlds.clear();

    assertThat(output.worlds()).hasSize(1);
  }

  @Test
  void ordinalMustBePositive() {
    assertThatThrownBy(() -> new WorldsViewOutput.WorldEntry(0, "demo", "Demo World", 1L, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ordinal");
  }
}
