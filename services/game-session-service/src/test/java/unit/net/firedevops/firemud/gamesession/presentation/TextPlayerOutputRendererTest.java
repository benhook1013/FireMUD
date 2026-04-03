package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import org.junit.jupiter.api.Test;

class TextPlayerOutputRendererTest {

  @Test
  void briefModeSuppressesLongLookDescription() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                PresentationProperties.ColorMode.NONE,
                true,
                new PresentationProperties.Prompt(false, false, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-1021",
                    "Candle-lit Antechamber",
                    "You stand in a basalt chamber warmed by the brazier near the western wall.",
                    "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels.",
                    List.of(new LookViewExit("NORTH", "arched passage")),
                    List.of())));

    assertThat(rendered)
        .contains(
            "Short: You stand in a basalt chamber warmed by the brazier near the western wall.");
    assertThat(rendered).doesNotContain("Long:");
  }

  @Test
  void disabledPromptsRenderAsEmptyText() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                PresentationProperties.ColorMode.BASIC,
                false,
                new PresentationProperties.Prompt(false, false, true, 150L)));

    assertThat(renderer.render(PlayerOutput.prompt("HP: 10/10 >"))).isEmpty();
  }

  @Test
  void briefPolicySuppressesTaggedMessageOutput() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                PresentationProperties.ColorMode.NONE,
                true,
                new PresentationProperties.Prompt(false, false, true, 150L)));

    PlayerOutput suppressed =
        new PlayerOutput(
            PlayerOutputKind.MESSAGE,
            new TextMessageOutput("ambient dust drifts from the rafters"),
            ReplayPolicy.BUFFERABLE,
            BriefRenderPolicy.SUPPRESS_IN_BRIEF,
            false);

    assertThat(renderer.render(suppressed)).isEmpty();
  }

  @Test
  void basicColorModeStylesPromptText() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                PresentationProperties.ColorMode.BASIC,
                false,
                new PresentationProperties.Prompt(true, false, true, 150L)));

    assertThat(renderer.render(PlayerOutput.prompt("HP: 10/10 >")))
        .isEqualTo("\u001B[1;32mHP: 10/10 >\u001B[0m");
  }

  @Test
  void richColorModeStylesLookLabels() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                PresentationProperties.ColorMode.RICH,
                false,
                new PresentationProperties.Prompt(false, false, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-1021",
                    "Candle-lit Antechamber",
                    "You stand in a basalt chamber warmed by the brazier near the western wall.",
                    "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels.",
                    List.of(new LookViewExit("NORTH", "arched passage")),
                    List.of())));

    assertThat(rendered).contains("Candle-lit Antechamber");
    assertThat(rendered).contains("\u001B[");
  }

  @Test
  void renderAllCoalescesMultiplePromptsToOneTrailingPrompt() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, false, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.LOOK, List.of(), "LOOK"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view("OK LOOK constructed"),
                PlayerOutput.prompt("old> "),
                PlayerOutput.prompt("new> ")));

    assertThat(rendered).isEqualTo("OK LOOK\nOK LOOK constructed\n\nnew> ");
  }

  @Test
  void renderAllPrefersStructuredErrorOutputForFailedCommands() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, false, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.LOOK, List.of(), "LOOK"),
            CommandEnqueueResult.failure("LOGIN_REQUIRED", "fallback"),
            List.of(
                PlayerOutput.error("LOGIN_REQUIRED", "You must LOGIN before gameplay commands.")));

    assertThat(rendered).isEqualTo("ERROR LOGIN_REQUIRED You must LOGIN before gameplay commands.");
  }

  @Test
  void renderAllFormatsMoveViewThroughNormalRendererPath() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, false, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.MOVE, List.of("north"), "MOVE north"),
            CommandEnqueueResult.success(),
            List.of(PlayerOutput.view("North Hall text"), PlayerOutput.prompt("demo> ")));

    assertThat(rendered).isEqualTo("OK MOVE\nNorth Hall text\n\ndemo> ");
  }
}
