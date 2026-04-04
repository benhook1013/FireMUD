package net.firedevops.firemud.gamesession.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.dto.EnqueueCommandRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

class CommandControllerTest {
  private final TextCommandInterpreter interpreter = Mockito.mock(TextCommandInterpreter.class);
  private final CommandController controller = new CommandController(interpreter);

  @Test
  void enqueueCommand_usesInterpreterAndReturnsResult() {
    EnqueueCommandRequest request = new EnqueueCommandRequest("LOOK", false);
    CommandEnqueueResult expected = CommandEnqueueResult.success();
    TextCommandInterpretationResult interpretation = new TextCommandInterpretationResult(expected);
    when(interpreter.interpret("42", "LOOK", false)).thenReturn(interpretation);

    ResponseEntity<ApiResponse<CommandEnqueueResult>> response =
        controller.enqueueCommand("42", request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(expected, response.getBody().data());
    verify(interpreter).interpret("42", "LOOK", false);
  }
}
