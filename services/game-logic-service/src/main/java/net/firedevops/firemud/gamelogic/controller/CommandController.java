package net.firedevops.firemud.gamelogic.controller;

import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.gamelogic.logic.dto.CommandResult;
import net.firedevops.firemud.gamelogic.logic.service.CommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint for submitting gameplay commands. */
@RestController
@RequestMapping("/command")
public class CommandController {
  private final CommandService commandService;

  public CommandController(CommandService commandService) {
    this.commandService = commandService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<String>> execute(@RequestBody String body) {
    CommandResult result = commandService.handleCommand(body);
    if (result.error() != null) {
      return ResponseEntity.badRequest().body(ApiResponse.error(result.error()));
    }
    return ResponseEntity.ok(ApiResponse.success(result.result()));
  }
}
