package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class PromptBurstCoordinatorTest {

  @Test
  void suppressesPromptWithinCoalesceWindow() {
    PromptBurstCoordinator coordinator =
        new PromptBurstCoordinator(
            resolverWithDefaultPromptWindow(150L),
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
            resolverWithDefaultPromptWindow(150L),
            Clock.fixed(Instant.parse("2026-04-02T23:00:00Z"), ZoneOffset.UTC));

    List<PlayerOutput> outputs =
        List.of(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-100",
                    "Constructed Hall",
                    "OK LOOK constructed",
                    "Detailed constructed hall",
                    true,
                    List.of(),
                    List.of())),
            PlayerOutput.prompt("demo> "));

    coordinator.recordPromptEmission("1", outputs);

    assertThat(coordinator.applyPromptWindow("1", outputs, true)).isEqualTo(outputs);
  }

  @Test
  void evictClearsPromptWindowState() {
    PromptBurstCoordinator coordinator =
        new PromptBurstCoordinator(
            resolverWithDefaultPromptWindow(150L),
            Clock.fixed(Instant.parse("2026-04-02T23:00:00Z"), ZoneOffset.UTC));

    List<PlayerOutput> outputs = List.of(PlayerOutput.prompt("demo> "));

    coordinator.recordPromptEmission("1", outputs);
    coordinator.evict("1");

    assertThat(coordinator.applyPromptWindow("1", outputs, false)).isEqualTo(outputs);
  }

  @Test
  void scopedOverrideControlsPromptWindowWhenContextKnown() {
    PromptBurstCoordinator coordinator =
        new PromptBurstCoordinator(
            new EffectiveSettingsResolver(
                new PresentationProperties(
                    "en-NZ",
                    PresentationProperties.ColorMode.NONE,
                    false,
                    new PresentationProperties.Prompt(true, true, 150L)),
                new net.firedevops.firemud.gamesession.config.MovementProperties(true),
                new net.firedevops.firemud.gamesession.config.WorldTopologyProperties(),
                (tenantId, gameInstanceId) ->
                    new ScopedSettingsSnapshot(
                        ScopedSettingsOverrides.empty(),
                        new ScopedSettingsOverrides(
                            null,
                            null,
                            new ScopedSettingsOverrides.PresentationOverride(
                                null,
                                null,
                                null,
                                new ScopedSettingsOverrides.PresentationOverride.PromptOverride(
                                    null, null, 500L)),
                            null,
                            null))),
            Clock.fixed(Instant.parse("2026-04-02T23:00:00Z"), ZoneOffset.UTC));

    List<PlayerOutput> outputs =
        List.of(PlayerOutput.message("You say, \"hello\""), PlayerOutput.prompt("demo> "));
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 911L, "Sora", 7L, "R-1", null);

    coordinator.recordPromptEmission("1", outputs);

    assertThat(coordinator.applyPromptWindow("1", context, outputs, false))
        .containsExactly(PlayerOutput.message("You say, \"hello\""));
  }

  private EffectiveSettingsResolver resolverWithDefaultPromptWindow(long coalesceWindowMs) {
    return new EffectiveSettingsResolver(
        new PresentationProperties(
            "en-NZ",
            PresentationProperties.ColorMode.NONE,
            false,
            new PresentationProperties.Prompt(true, true, coalesceWindowMs)),
        new net.firedevops.firemud.gamesession.config.MovementProperties(true),
        new net.firedevops.firemud.gamesession.config.WorldTopologyProperties(),
        (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty());
  }
}
