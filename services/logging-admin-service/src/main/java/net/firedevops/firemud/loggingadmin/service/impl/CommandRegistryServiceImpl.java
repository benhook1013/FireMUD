package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.BuiltInCommandAliasValidationDto;
import net.firedevops.firemud.loggingadmin.service.CommandRegistryService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed gRPC client dependency is stored and not exposed")
public class CommandRegistryServiceImpl implements CommandRegistryService {
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public CommandRegistryServiceImpl(GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
  }

  @Override
  @Timed(value = "loggingadmin.commandRegistry.validateBuiltInCommandAlias")
  public BuiltInCommandAliasValidationDto validateBuiltInCommandAlias(String alias) {
    SessionContext.requireGlobalPrivilegedRole();
    ValidateBuiltInCommandAliasResponse response =
        gameSessionControlPlaneClient.validateBuiltInCommandAlias(alias);
    requireNoError(response.getError());
    return new BuiltInCommandAliasValidationDto(
        response.getSupported(),
        response.getNormalizedAlias().isBlank() ? null : response.getNormalizedAlias());
  }

  private void requireNoError(ErrorDetail error) {
    if (error == null || error.getCode().isBlank()) {
      return;
    }
    HttpStatus status =
        switch (error.getCode()) {
          case "INVALID_ARGUMENT" -> HttpStatus.BAD_REQUEST;
          case "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
          default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    throw new ResponseStatusException(status, error.getMessage());
  }
}
