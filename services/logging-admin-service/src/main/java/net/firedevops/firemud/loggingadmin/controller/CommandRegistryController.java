package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.BuiltInCommandAliasValidationDto;
import net.firedevops.firemud.loggingadmin.service.CommandRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/command-registry")
public class CommandRegistryController {
  private final CommandRegistryService commandRegistryService;

  public CommandRegistryController(CommandRegistryService commandRegistryService) {
    this.commandRegistryService = commandRegistryService;
  }

  @GetMapping("/built-in-aliases/{alias}")
  @Timed(
      value = "validateBuiltInCommandAlias",
      description =
          "Validate one built-in command alias against the canonical Game Session registry")
  public ResponseEntity<ApiResponse<BuiltInCommandAliasValidationDto>> validateBuiltInCommandAlias(
      @PathVariable String alias) {
    SessionContext.requireGlobalPrivilegedRole();
    return ResponseEntity.ok(
        ApiResponse.success(commandRegistryService.validateBuiltInCommandAlias(alias)));
  }
}
