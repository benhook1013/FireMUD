package unit.net.firedevops.firemud.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.command.text.LookCommandHandler;
import net.firedevops.firemud.command.text.TextCommand;
import net.firedevops.firemud.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.command.text.TextCommandInterpreter;
import net.firedevops.firemud.command.text.TextCommandType;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.service.CommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TextCommandInterpreterTest {
  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final LookCommandHandler lookHandler = new LookCommandHandler();
  private TextCommandInterpreter interpreter;

  @BeforeEach
  void setUp() {
    interpreter = new TextCommandInterpreter(commandService, lookHandler);
  }

  @Test
  void enqueuesKnownCommand() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("123", "LOOK", false)).thenReturn(success);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("123", "LOOK", false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService).enqueue("123", "LOOK", false);
  }

  @Test
  void enqueuesKnownTextCommand() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("123", "LOOK", false)).thenReturn(success);

    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    TextCommandInterpretationResult interpretation = interpreter.interpret("123", command, false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService).enqueue("123", "LOOK", false);
  }

  @Test
  void lookCommandReturnsDescription() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("123", "LOOK", false)).thenReturn(success);

    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    TextCommandInterpretationResult interpretation = interpreter.interpret("123", command, false);

    assertEquals(LookCommandHandler.DEFAULT_ROOM_DESCRIPTION, interpretation.responseText());
  }

  @Test
  void unknownCommandReturnsFailureAndDoesNotEnqueue() {
    TextCommandInterpretationResult interpretation =
        interpreter.interpret("123", "dance wildly", false);
    CommandEnqueueResult result = interpretation.commandResult();

    assertFalse(result.accepted());
    assertEquals("UNKNOWN_COMMAND", result.errorCode());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void blankCommandIsIgnored() {
    TextCommand noOp = new TextCommand(TextCommandType.NOOP, List.of(), "   ");

    TextCommandInterpretationResult interpretation = interpreter.interpret("123", noOp, false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }
}
