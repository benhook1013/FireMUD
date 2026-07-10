package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ItemCommandHandlerTest {
  private final InventoryCommandHandler inventoryHandler =
      Mockito.mock(InventoryCommandHandler.class);
  private final EquipmentCommandHandler equipmentHandler =
      Mockito.mock(EquipmentCommandHandler.class);
  private final ContainerCommandHandler containerHandler =
      Mockito.mock(ContainerCommandHandler.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ItemCommandHandler handler =
      new ItemCommandHandler(
          inventoryHandler,
          equipmentHandler,
          containerHandler,
          meterRegistry,
          scriptEventPublisher);
  private final SessionContext context =
      new SessionContext(
          1L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 77L, "R-7", "jwt-token");

  @Test
  void recordsInvocationForSuccessfulItemCommand() {
    TextCommand command = new TextCommand(TextCommandType.GET, List.of("Torch"), "GET Torch");
    when(inventoryHandler.handle(context, command, "effect-1"))
        .thenReturn(new InventoryCommandHandlingResult(CommandEnqueueResult.success(), List.of()));

    TextCommandInterpretationResult result = handler.handle(context, command, "effect-1");

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(
            meterRegistry
                .get("gamesession.command.item.invocations")
                .tag("type", "get")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.find("gamesession.command.item.failures").counter()).isNull();
  }

  @Test
  void publishesScriptEventForSuccessfulDirectInventoryRead() {
    TextCommand command = new TextCommand(TextCommandType.INVENTORY, List.of(), "INVENTORY");
    when(inventoryHandler.handle(context, command, null))
        .thenReturn(new InventoryCommandHandlingResult(CommandEnqueueResult.success(), List.of()));

    TextCommandInterpretationResult result = handler.handle(context, command);

    assertThat(result.commandResult().accepted()).isTrue();
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.eq(context),
            Mockito.argThat(
                gameplayCommand ->
                    "INVENTORY".equals(gameplayCommand.getCommandName())
                        && "INVENTORY".equals(gameplayCommand.getCommandText())
                        && gameplayCommand.getCommandId() != null
                        && gameplayCommand.getCommandId().startsWith("item-")));
  }

  @Test
  void recordsTaggedFailureForRejectedItemCommand() {
    TextCommand command =
        new TextCommand(TextCommandType.WEAR, List.of("Iron Boots"), "WEAR Iron Boots");
    when(equipmentHandler.handle(context, command, "effect-2"))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.failure(
                    "SLOT_INCOMPATIBLE", "Iron Boots cannot be worn by this body layout"),
                List.of()));

    TextCommandInterpretationResult result = handler.handle(context, command, "effect-2");

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(
            meterRegistry
                .get("gamesession.command.item.invocations")
                .tag("type", "wear")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get("gamesession.command.item.failures")
                .tag("type", "wear")
                .tag("error", "SLOT_INCOMPATIBLE")
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
