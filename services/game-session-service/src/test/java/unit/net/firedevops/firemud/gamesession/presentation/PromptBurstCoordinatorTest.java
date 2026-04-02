package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import org.junit.jupiter.api.Test;

class PromptBurstCoordinatorTest {

  @Test
  void suppressesPromptWithinCoalesceWindow() {
    PromptBurstCoordinator coordinator =
        new PromptBurstCoordinator(
            new PresentationProperties(
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, false, true, 150L)),
            Clock.fixed(Instant.parse("2026-04-02T23:00:00Z"), ZoneOffset.UTC));

    List<PlayerOutput> outputs =
        List.of(PlayerOutput.message("You say, \"hello\""), PlayerOutput.prompt("demo> "));

    coordinator.recordPromptEmission("1", outputs);

    assertThat(coordinator.applyPromptWindow("1", outputs, false))
        .containsExactly(PlayerOutput.message("You say, \"hello\""));
  }

  @Test
  void keepsPromptWhenForced() {
    PromptBurstCoordinator coordinator =
        new PromptBurstCoordinator(
            new PresentationProperties(
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, false, true, 150L)),
            Clock.fixed(Instant.parse("2026-04-02T23:00:00Z"), ZoneOffset.UTC));

    List<PlayerOutput> outputs =
        List.of(PlayerOutput.view("OK LOOK constructed"), PlayerOutput.prompt("demo> "));

    coordinator.recordPromptEmission("1", outputs);

    assertThat(coordinator.applyPromptWindow("1", outputs, true)).isEqualTo(outputs);
  }

  @Test
  void evictClearsPromptWindowState() {
    PromptBurstCoordinator coordinator =
        new PromptBurstCoordinator(
            new PresentationProperties(
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, false, true, 150L)),
            Clock.fixed(Instant.parse("2026-04-02T23:00:00Z"), ZoneOffset.UTC));

    List<PlayerOutput> outputs = List.of(PlayerOutput.prompt("demo> "));

    coordinator.recordPromptEmission("1", outputs);
    coordinator.evict("1");

    assertThat(coordinator.applyPromptWindow("1", outputs, false)).isEqualTo(outputs);
  }
}
