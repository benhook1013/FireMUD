package unit.net.firedevops.firemud.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.command.text.TextCommandInterpreter;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.service.CommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TextCommandInterpreterTest {
  private final CommandService commandService = Mockito.mock(CommandService.class);
  private TextCommandInterpreter interpreter;

  @BeforeEach
  void setUp() {
    interpreter = new TextCommandInterpreter(commandService);
  }

  @Test
  void enqueuesKnownCommand() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("123", "LOOK", false)).thenReturn(success);

    CommandEnqueueResult result = interpreter.interpret("123", "LOOK", false);

    assertTrue(result.accepted());
    verify(commandService).enqueue("123", "LOOK", false);
  }

  @Test
  void unknownCommandReturnsFailureAndDoesNotEnqueue() {
    CommandEnqueueResult result = interpreter.interpret("123", "dance wildly", false);

    assertFalse(result.accepted());
    assertEquals("UNKNOWN_COMMAND", result.errorCode());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }
}
