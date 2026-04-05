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
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                true,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-1021",
                    "Candle-lit Antechamber",
                    "You stand in a basalt chamber warmed by the brazier near the western wall.",
                    "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels.",
                    true,
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
                "en-NZ",
                PresentationProperties.ColorMode.BASIC,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    assertThat(renderer.render(PlayerOutput.prompt("HP: 10/10 >"))).isEmpty();
  }

  @Test
  void briefPolicySuppressesTaggedMessageOutput() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                true,
                new PresentationProperties.Prompt(false, true, 150L)));

    PlayerOutput suppressed =
        new PlayerOutput(
            PlayerOutputKind.MESSAGE,
            new TextMessageOutput("ambient dust drifts from the rafters"),
            ReplayPolicy.BUFFERABLE,
            BriefRenderPolicy.SUPPRESS_IN_BRIEF);

    assertThat(renderer.render(suppressed)).isEmpty();
  }

  @Test
  void basicColorModeStylesPromptText() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.BASIC,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    assertThat(renderer.render(PlayerOutput.prompt("HP: 10/10 >")))
        .isEqualTo("\u001B[1;32mHP: 10/10 >\u001B[0m");
  }

  @Test
  void richColorModeStylesLookLabels() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.RICH,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-1021",
                    "Candle-lit Antechamber",
                    "You stand in a basalt chamber warmed by the brazier near the western wall.",
                    "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels.",
                    true,
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
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.LOOK, List.of(), "LOOK"),
            CommandEnqueueResult.success(),
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
                PlayerOutput.prompt("old> "),
                PlayerOutput.prompt("new> ")));

    assertThat(rendered)
        .isEqualTo(
            "OK LOOK\n"
                + "Room: Constructed Hall (ID: R-100)\n"
                + "Short: OK LOOK constructed\n"
                + "Long: Detailed constructed hall\n"
                + "Exits: \n"
                + "Entities:\n\nnew> ");
  }

  @Test
  void renderAllFormatsInventoryViewThroughNormalRendererPath() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.INVENTORY, List.of(), "INVENTORY"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view(
                    new InventoryViewOutput("Inventory:", List.of("- Torch x2 (A small torch)"))),
                PlayerOutput.prompt("demo> ")));

    assertThat(rendered)
        .isEqualTo("OK INVENTORY\n" + "Inventory:\n" + "- Torch x2 (A small torch)\n\n" + "demo> ");
  }

  @Test
  void quickLookRenderingOmitsLongDescriptionWithoutGlobalBriefMode() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-2045",
                    "Crafting Hall",
                    "A broad workshop glows with banked coals.",
                    "Hammer racks line the walls while a forge crackles beneath soot-black beams.",
                    false,
                    List.of(new LookViewExit("SOUTH", "stone arch")),
                    List.of(new LookViewEntity("PLAYER", "Sora", "smith", List.of("busy"))))));

    assertThat(rendered).contains("Short: A broad workshop glows with banked coals.");
    assertThat(rendered).doesNotContain("Long:");
    assertThat(rendered).contains("Exits: SOUTH (stone arch)");
    assertThat(rendered).contains("PLAYER \"Sora\" (smith) [busy]");
  }

  @Test
  void renderAllPrefersStructuredErrorOutputForFailedCommands() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

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
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.MOVE, List.of("north"), "MOVE north"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view(
                    new LookViewOutput(
                        "R-205",
                        "North Hall",
                        "North Hall text",
                        "Detailed north hall text",
                        true,
                        LookViewOutput.RefreshReason.MOVE_REFRESH,
                        List.of(),
                        List.of())),
                PlayerOutput.prompt("demo> ")));

    assertThat(rendered)
        .isEqualTo(
            "OK LOOK\n"
                + "Room: North Hall (ID: R-205)\n"
                + "Short: North Hall text\n"
                + "Exits: \n"
                + "Entities:\n\n"
                + "demo> ");
  }

  @Test
  void renderAllMapsMoveViewToLookLabel() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.MOVE, List.of("north"), "MOVE north"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view(
                    new LookViewOutput(
                        "R-205",
                        "North Hall",
                        "North Hall text",
                        "Detailed north hall text",
                        true,
                        LookViewOutput.RefreshReason.MOVE_REFRESH,
                        List.of(),
                        List.of()))));

    assertThat(rendered)
        .isEqualTo(
            "OK LOOK\n"
                + "Room: North Hall (ID: R-205)\n"
                + "Short: North Hall text\n"
                + "Exits: \n"
                + "Entities:\n\n");
  }

  @Test
  void moveRefreshUsesBriefStyleLongDescriptionSuppressionByDefault() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-301",
                    "South Hall",
                    "A narrow corridor bends south.",
                    "The corridor runs beneath low stone arches lined with guttering lamps.",
                    true,
                    LookViewOutput.RefreshReason.MOVE_REFRESH,
                    List.of(new LookViewExit("NORTH", "stairs")),
                    List.of())));

    assertThat(rendered).contains("Short: A narrow corridor bends south.");
    assertThat(rendered).doesNotContain("Long:");
  }

  @Test
  void moveRefreshCanRenderFullLookWhenBriefHintDoesNotPreferBrief() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-302",
                    "Gallery Walk",
                    "A gallery opens to the east.",
                    "Portraits and weathered banners line the length of the gallery.",
                    true,
                    LookViewOutput.RefreshReason.MOVE_REFRESH,
                    LookViewOutput.BriefRenderingHint.FOLLOW_DEFAULT,
                    List.of(new LookViewExit("EAST", "archway")),
                    List.of())));

    assertThat(rendered).contains("Short: A gallery opens to the east.");
    assertThat(rendered).contains("Long: Portraits and weathered banners line the length");
  }

  @Test
  void renderAllFormatsSingleLineNoticeAsInlineCommandSuccess() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"),
            CommandEnqueueResult.success(),
            List.of(PlayerOutput.notice("Entered world: demo"), PlayerOutput.prompt("demo> ")));

    assertThat(rendered).isEqualTo("OK PLAY Entered world: demo\ndemo> ");
  }

  @Test
  void localizedNoticeUsesConfiguredLocaleTemplate() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.notice(
                "fallback",
                "notice.world.entered",
                java.util.Map.of("worldName", "demo", "characterSuffix", "")));

    assertThat(rendered).isEqualTo("Entered world: demo");
  }

  @Test
  void localizedErrorUsesConfiguredLocaleTemplate() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.error(
                "LOGIN_REQUIRED", "fallback", "error.login-required", java.util.Map.of()));

    assertThat(rendered).isEqualTo("ERROR LOGIN_REQUIRED You must LOGIN before gameplay commands.");
  }

  @Test
  void localizedErrorAppliesStructuredArgumentsForAlternateLocale() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "fr",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.error(
                "INVALID_EXIT",
                "fallback",
                "error.move.invalid-exit",
                java.util.Map.of("direction", "OUEST", "roomId", "R-1021")));

    assertThat(rendered)
        .isEqualTo("ERROR INVALID_EXIT Aucune sortie OUEST depuis la salle R-1021.");
  }

  @Test
  void localizedLookLabelsUseConfiguredLocaleTemplate() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "fr",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-707",
                    "Galerie",
                    "Un couloir etroit file vers le sud.",
                    "Des lampes fument sous les arches de pierre.",
                    true,
                    List.of(new LookViewExit("SUD", "porte etroite")),
                    List.of())));

    assertThat(rendered).contains("Salle : Galerie (ID: R-707)");
    assertThat(rendered).contains("Court : Un couloir etroit file vers le sud.");
    assertThat(rendered).contains("Long : Des lampes fument sous les arches de pierre.");
    assertThat(rendered).contains("Sorties : SUD (porte etroite)");
    assertThat(rendered).contains("Entites:");
  }

  @Test
  void explicitLocaleOverrideWinsOverConfiguredDefault() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-808",
                    "Galerie",
                    "Un couloir etroit file vers le sud.",
                    "Des lampes fument sous les arches de pierre.",
                    true,
                    List.of(new LookViewExit("SUD", "porte etroite")),
                    List.of())),
            "fr");

    assertThat(rendered).contains("Salle : Galerie (ID: R-808)");
    assertThat(rendered).doesNotContain("Room:");
  }

  @Test
  void renderAllFormatsMultilineNoticeAsCommandBody() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
            CommandEnqueueResult.success(),
            List.of(PlayerOutput.notice("1) Demo World (demo)\n2) Builder Sandbox (sandbox)")));

    assertThat(rendered)
        .isEqualTo("OK WORLDS\n1) Demo World (demo)\n2) Builder Sandbox (sandbox)\n\n");
  }

  @Test
  void renderAllFormatsWorldsViewAsCommandBody() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view(
                    new WorldsViewOutput(
                        List.of(
                            new WorldsViewOutput.WorldEntry(1, "demo", "Demo World", 1L, false),
                            new WorldsViewOutput.WorldEntry(
                                2, "sandbox", "Builder Sandbox", 2L, true))))));

    assertThat(rendered)
        .isEqualTo("OK WORLDS\n1) Demo World (demo)\n2) Builder Sandbox (sandbox)\n\n");
  }

  @Test
  void explicitEffectivePresentationCanOverrideBaseBriefPolicy() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-909",
                    "Observatory",
                    "A cold dome opens above you.",
                    "Star charts and brass lenses crowd the observation deck.",
                    true,
                    List.of(new LookViewExit("DOWN", "spiral stair")),
                    List.of())),
            "en-NZ",
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                true,
                new PresentationProperties.Prompt(false, true, 150L)));

    assertThat(rendered).contains("Short: A cold dome opens above you.");
    assertThat(rendered).doesNotContain("Long:");
  }
}
