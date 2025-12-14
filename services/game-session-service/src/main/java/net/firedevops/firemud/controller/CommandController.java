package net.firedevops.firemud.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.command.text.TextCommandInterpreter;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.dto.EnqueueCommandRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST shim for enqueuing player commands when forwarded via HTTP. */
@RestController
@RequestMapping("/sessions")
public class CommandController {
  private final TextCommandInterpreter interpreter;

  public CommandController(TextCommandInterpreter interpreter) {
    this.interpreter = interpreter;
  }

  @PostMapping("/{sessionId}/commands")
  public ResponseEntity<ApiResponse<CommandEnqueueResult>> enqueueCommand(
      @PathVariable String sessionId, @Valid @RequestBody EnqueueCommandRequest request) {
    TextCommandInterpretationResult interpretation =
        interpreter.interpret(sessionId, request.command(), request.requiresSoloTick());
    CommandEnqueueResult result = interpretation.commandResult();
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
