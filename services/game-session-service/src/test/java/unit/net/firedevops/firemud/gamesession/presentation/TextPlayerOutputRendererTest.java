package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionCategory;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionTag;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandPayload;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import org.junit.jupiter.api.Test;

class TextPlayerOutputRendererTest {
  private static final String STRIDE_COMMAND_ID = "stride";

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
  void rendersActorStateViewWithoutInternalEffectProvenance() {
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
                new ActorStateViewOutput(
                    List.of(new ActorStateViewOutput.Resource("health", 12, 20L, 10L)),
                    List.of(
                        new ActorStateViewOutput.Condition(
                            "blocking", 1, "2026-07-11T00:00:00Z")))));

    assertThat(rendered)
        .isEqualTo(
            "Status:\n- health: 12/20\nConditions:\n- blocking (until 2026-07-11T00:00:00Z)");
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
                    new InventoryViewOutput(
                        InventoryViewOutput.Source.INVENTORY,
                        "Inventory:",
                        List.of("- Torch x2 (A small torch)"))),
                PlayerOutput.prompt("demo> ")));

    assertThat(rendered)
        .isEqualTo("OK INVENTORY\n" + "Inventory:\n" + "- Torch x2 (A small torch)\n\n" + "demo> ");
  }

  @Test
  void renderAllUsesCanonicalCommandIdForAcceptedAuthoredCommandWithoutOutputs() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(
                "wave-salute",
                TextCommandType.AUTHORED,
                List.of("captain"),
                "WAVE-SALUTE captain",
                "WAVE-SALUTE",
                new TextCommandPayload.AuthoredActionInvocation("wave-salute", List.of("captain"))),
            CommandEnqueueResult.success(),
            List.of());

    assertThat(rendered).isEqualTo("OK WAVE-SALUTE");
  }

  @Test
  void renderAllFormatsEquipmentViewThroughNormalRendererPath() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.EQUIPMENT, List.of(), "EQUIPMENT"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view(
                    new InventoryViewOutput(
                        InventoryViewOutput.Source.EQUIPMENT,
                        "Equipment:",
                        List.of("- HEAD: Leather Cap (A small cap)"))),
                PlayerOutput.prompt("demo> ")));

    assertThat(rendered)
        .isEqualTo(
            "OK EQUIPMENT\n" + "Equipment:\n" + "- HEAD: Leather Cap (A small cap)\n\n" + "demo> ");
  }

  @Test
  void renderAllFormatsContainerViewThroughNormalRendererPath() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.CONTAINER, List.of("satchel"), "CONTAINER satchel"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view(
                    new InventoryViewOutput(
                        InventoryViewOutput.Source.CONTAINER,
                        "Container: Satchel",
                        List.of("- Trail Ration x2 (A dry ration)"))),
                PlayerOutput.prompt("demo> ")));

    assertThat(rendered)
        .isEqualTo(
            "OK CONTAINER\n"
                + "Container: Satchel\n"
                + "- Trail Ration x2 (A dry ration)\n\n"
                + "demo> ");
  }

  @Test
  void renderAllFormatsPutResultWithContainerRefresh() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(
                TextCommandType.PUT, List.of("torch", "into", "satchel"), "PUT torch INTO satchel"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.notice(
                    new ItemMutationResultOutput(
                        "PUT",
                        new InventoryViewOutput.ItemEntry(
                            "torch", "torch-1", "", "torch#1", "Torch", "", 1, ""),
                        new ItemMutationResultOutput.HolderContext(
                            InventoryViewOutput.Source.INVENTORY, "", "", "", ""),
                        new ItemMutationResultOutput.HolderContext(
                            InventoryViewOutput.Source.CONTAINER,
                            "Satchel",
                            "satchel-1",
                            "satchel#1",
                            ""))),
                PlayerOutput.view(
                    new InventoryViewOutput(
                        InventoryViewOutput.Source.CONTAINER,
                        "Container: Satchel",
                        List.of("It is empty."))),
                PlayerOutput.prompt("demo> ")));

    assertThat(rendered)
        .isEqualTo(
            "OK PUT\n"
                + "You put Torch into Satchel.\n"
                + "Container: Satchel\n"
                + "It is empty.\n\n"
                + "demo> ");
  }

  @Test
  void renderAllFormatsTakeResultWithContainerRefresh() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(
                TextCommandType.TAKE,
                List.of("torch", "from", "satchel"),
                "TAKE torch FROM satchel"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.notice(
                    new ItemMutationResultOutput(
                        "TAKE",
                        new InventoryViewOutput.ItemEntry(
                            "torch", "torch-1", "", "torch#1", "Torch", "", 1, ""),
                        new ItemMutationResultOutput.HolderContext(
                            InventoryViewOutput.Source.CONTAINER,
                            "Satchel",
                            "satchel-1",
                            "satchel#1",
                            ""),
                        new ItemMutationResultOutput.HolderContext(
                            InventoryViewOutput.Source.INVENTORY, "", "", "", ""))),
                PlayerOutput.view(
                    new InventoryViewOutput(
                        InventoryViewOutput.Source.CONTAINER,
                        "Container: Satchel",
                        List.of("It is empty."))),
                PlayerOutput.prompt("demo> ")));

    assertThat(rendered)
        .isEqualTo(
            "OK TAKE\n"
                + "You take Torch from Satchel.\n"
                + "Container: Satchel\n"
                + "It is empty.\n\n"
                + "demo> ");
  }

  @Test
  void renderAllUsesCommunicationTagForInlineAuthoredProse() {
    TextCommandMetadataResolver metadataResolver =
        commandId ->
            "wave".equals(commandId)
                ? java.util.Optional.of(
                    new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
                        net.firedevops.firemud.gamesession.command.text.TextCommandDispatchGroup
                            .AUTHORED,
                        TextCommandActionCategory.SOCIAL,
                        List.of(TextCommandActionTag.COMMUNICATION)))
                : java.util.Optional.empty();
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)),
            new PresentationMessageCatalog(),
            new TextCommandPresentationPolicy(metadataResolver));

    String rendered =
        renderer.renderAll(
            new TextCommand(
                "wave",
                TextCommandType.AUTHORED,
                List.of("hello"),
                "wave hello",
                "wave",
                new TextCommandPayload.AuthoredActionInvocation("wave", List.of("hello"))),
            CommandEnqueueResult.success(),
            List.of(PlayerOutput.message("You wave hello."), PlayerOutput.prompt("demo> ")));

    assertThat(rendered).isEqualTo("You wave hello.\ndemo> ");
  }

  @Test
  void renderAllUsesBuiltInCommunicationFallbackMetadataForInlineSayProse() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.SAY, List.of("hello"), "SAY hello"),
            CommandEnqueueResult.success(),
            List.of(PlayerOutput.message("You say, \"Hello.\""), PlayerOutput.prompt("demo> ")));

    assertThat(rendered).isEqualTo("You say, \"Hello.\"\ndemo> ");
  }

  @Test
  void renderAllKeepsCommandEnvelopeForNonCommunicationAuthoredMessages() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)),
            new PresentationMessageCatalog(),
            new TextCommandPresentationPolicy(commandId -> java.util.Optional.empty()));

    String rendered =
        renderer.renderAll(
            new TextCommand(
                "wave",
                TextCommandType.AUTHORED,
                List.of("hello"),
                "wave hello",
                "wave",
                new TextCommandPayload.AuthoredActionInvocation("wave", List.of("hello"))),
            CommandEnqueueResult.success(),
            List.of(PlayerOutput.message("You wave hello."), PlayerOutput.prompt("demo> ")));

    assertThat(rendered).isEqualTo("OK WAVE\nYou wave hello.\n\ndemo> ");
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
  void lookRenderingHighlightsRoomGroundItems() {
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
                    "R-305",
                    "Grounded Hall",
                    "A torchlit hall with a loose key on the floor.",
                    "A torchlit hall with a loose key on the floor and dust shifting in the corners.",
                    true,
                    List.of(),
                    List.of(
                        new LookViewEntity(
                            "ITEM", "Rough Iron Key", "", List.of("room-ground"))))));

    assertThat(rendered).contains("ITEM \"Rough Iron Key\" [room-ground]");
  }

  @Test
  void lookRenderingSurfacesContainerAndWearableAffordancesClearly() {
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
                    "R-306",
                    "Tagged Hall",
                    "A hall with a useful pack on the floor.",
                    "A hall with a useful pack on the floor and a spare hook on the wall.",
                    true,
                    List.of(),
                    List.of(
                        new LookViewEntity(
                            "ITEM",
                            "Backpack",
                            "",
                            List.of("room-ground", "container", "wearable:BACK"))))));

    assertThat(rendered)
        .contains("ITEM \"Backpack\" [room-ground; affordances: container, wearable BACK]");
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
  void renderAllUsesMovementTagForAuthoredViewLabel() {
    TextCommandMetadataResolver metadataResolver =
        commandId ->
            STRIDE_COMMAND_ID.equals(commandId)
                ? java.util.Optional.of(
                    new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
                        net.firedevops.firemud.gamesession.command.text.TextCommandDispatchGroup
                            .AUTHORED,
                        TextCommandActionCategory.GAMEPLAY,
                        List.of(TextCommandActionTag.MOVEMENT)))
                : java.util.Optional.empty();
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)),
            new PresentationMessageCatalog(),
            new TextCommandPresentationPolicy(metadataResolver));

    String rendered =
        renderer.renderAll(
            new TextCommand(
                STRIDE_COMMAND_ID,
                TextCommandType.AUTHORED,
                List.of("north"),
                "stride north",
                STRIDE_COMMAND_ID,
                new TextCommandPayload.AuthoredActionInvocation(
                    STRIDE_COMMAND_ID, List.of("north"))),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view(
                    new LookViewOutput(
                        "R-205",
                        "North Hall",
                        "North Hall text",
                        "Detailed north hall text",
                        true,
                        List.of(),
                        List.of()))));

    assertThat(rendered)
        .isEqualTo(
            "OK LOOK\n"
                + "Room: North Hall (ID: R-205)\n"
                + "Short: North Hall text\n"
                + "Long: Detailed north hall text\n"
                + "Exits: \n"
                + "Entities:\n\n");
  }

  @Test
  void renderAllDoesNotInferMovementFromAnUnclassifiedCommandType() {
    TextCommandMetadataResolver metadataResolver = commandId -> java.util.Optional.empty();
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)),
            new PresentationMessageCatalog(),
            new TextCommandPresentationPolicy(metadataResolver));

    String rendered =
        renderer.renderAll(
            new TextCommand(
                "custom-move",
                TextCommandType.MOVE,
                List.of("north"),
                "CUSTOM-MOVE north",
                "custom-move",
                new TextCommandPayload.Directional("north")),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view(
                    new LookViewOutput(
                        "R-205",
                        "North Hall",
                        "North Hall text",
                        "Detailed north hall text",
                        true,
                        List.of(),
                        List.of()))));

    assertThat(rendered).startsWith("OK CUSTOM-MOVE\n");
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
  void localizedEmailLoginChallengeMessageUsesConfiguredLocaleTemplate() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "fr",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));

    String rendered =
        renderer.render(
            PlayerOutput.message("fallback", "message.login.code-sent", java.util.Map.of()));

    assertThat(rendered)
        .isEqualTo(
            "Si ce compte est autorise a se connecter, un code a ete envoye. Utilisez LOGIN <email> <code>.");
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

    assertThat(rendered)
        .isEqualTo("ERROR LOGIN_REQUIRED You must LOGIN first. Use LOGIN <email> [secret].");
  }

  @Test
  void localizedUnknownHelpTopicUsesConfiguredLocaleTemplate() {
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
                "HELP_UNKNOWN_TOPIC",
                "fallback",
                "error.help.unknown-topic",
                java.util.Map.of("topic", "banane")));

    assertThat(rendered).isEqualTo("ERROR HELP_UNKNOWN_TOPIC Sujet d’aide inconnu : banane");
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
  void localizedMessagePreservesUtf8AccentedCharacters() {
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
                "LOGIN_RETRY_LATER", "fallback", "error.login.retry-later", java.util.Map.of()));

    assertThat(rendered)
        .isEqualTo("ERROR LOGIN_RETRY_LATER Trop de tentatives échouées ; réessayez plus tard.");
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
  void renderAllFormatsWhoViewWithActivityTags() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.renderAll(
            new TextCommand(TextCommandType.WHO, List.of(), "WHO"),
            CommandEnqueueResult.success(),
            List.of(
                PlayerOutput.view(
                    new WhoViewOutput(
                        List.of(new WhoViewOutput.Entry(1, "Aster", "ACTIVE")),
                        List.of(
                            new WhoViewOutput.Entry(1, "Ben", "AUTO_AFK"),
                            new WhoViewOutput.Entry(2, "Cara", "EXPLICIT_AFK"))))));

    assertThat(rendered)
        .isEqualTo("OK WHO\nGods [1]: Aster\nPlayers [2]: Ben (idle), Cara (AFK)\n\n");
  }

  @Test
  void renderSuccessfulForOutputFormatsFriendDetailWithFriendsEnvelope() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.renderSuccessfulForOutput(
            PlayerOutput.view(
                new FriendDetailViewOutput(
                    new FriendPresenceViewOutput.Entry(
                        1,
                        77L,
                        41L,
                        "ONLINE",
                        null,
                        "Sora",
                        true,
                        "demo",
                        "Demo World",
                        "ember",
                        "Ember Realm",
                        "Sora",
                        "GLOBAL",
                        1L,
                        "ACTIVE",
                        null,
                        "FRIEND",
                        "SHARED"))),
            "en-NZ",
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    assertThat(rendered)
        .isEqualTo(
            "OK FRIENDS\n"
                + "Friend Sora [acct #41]\n"
                + "Link: #77\n"
                + "Status: online\n"
                + "Presence: online in Demo World / Ember Realm (active)\n"
                + "Visibility: SHARED\n"
                + "Location: Demo World / Ember Realm\n"
                + "Character: Sora\n"
                + "Activity: active\n"
                + "State scope: global\n"
                + "Pointer version: 1\n"
                + "Roster entry: #1\n\n");
  }

  @Test
  void renderSuccessfulForOutputFormatsQuickLookWithQuickLookEnvelope() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.renderSuccessfulForOutput(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-1021",
                    "Quick Hall",
                    "Quick hall short",
                    "Quick hall long",
                    false,
                    LookViewOutput.RefreshReason.QUICKLOOK,
                    List.of(),
                    List.of())),
            "en-NZ",
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    assertThat(rendered)
        .isEqualTo(
            "OK QUICKLOOK\n"
                + "Room: Quick Hall (ID: R-1021)\n"
                + "Short: Quick hall short\n"
                + "Exits: \n"
                + "Entities:\n\n");
  }

  @Test
  void renderSuccessfulForOutputFormatsEquipmentViewWithEquipmentEnvelope() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.renderSuccessfulForOutput(
            PlayerOutput.view(
                new InventoryViewOutput(
                    InventoryViewOutput.Source.EQUIPMENT,
                    "Equipment:",
                    List.of("- HEAD: Leather Cap (A small cap)"))),
            "en-NZ",
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    assertThat(rendered)
        .isEqualTo("OK EQUIPMENT\n" + "Equipment:\n" + "- HEAD: Leather Cap (A small cap)\n\n");
  }

  @Test
  void renderSuccessfulForOutputUsesInventorySourceInsteadOfTitlePrefix() {
    TextPlayerOutputRenderer renderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    String rendered =
        renderer.renderSuccessfulForOutput(
            PlayerOutput.view(
                new InventoryViewOutput(
                    InventoryViewOutput.Source.EQUIPMENT,
                    "Inventory:",
                    List.of("- HEAD: Leather Cap (A small cap)"))),
            "en-NZ",
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(false, true, 150L)));

    assertThat(rendered)
        .isEqualTo("OK EQUIPMENT\n" + "Inventory:\n" + "- HEAD: Leather Cap (A small cap)\n\n");
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
