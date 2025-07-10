package net.firedevops.firemud.controller;

import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.logic.service.CommandService;
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
    String result = commandService.handleCommand(body);
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
