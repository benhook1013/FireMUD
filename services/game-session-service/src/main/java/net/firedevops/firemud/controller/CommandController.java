package net.firedevops.firemud.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.dto.EnqueueCommandRequest;
import net.firedevops.firemud.service.CommandService;
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
  private final CommandService commandService;

  public CommandController(CommandService commandService) {
    this.commandService = commandService;
  }

  @PostMapping("/{sessionId}/commands")
  public ResponseEntity<ApiResponse<CommandEnqueueResult>> enqueueCommand(
      @PathVariable String sessionId, @Valid @RequestBody EnqueueCommandRequest request) {
    CommandEnqueueResult result =
        commandService.enqueue(sessionId, request.command(), request.requiresSoloTick());
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
