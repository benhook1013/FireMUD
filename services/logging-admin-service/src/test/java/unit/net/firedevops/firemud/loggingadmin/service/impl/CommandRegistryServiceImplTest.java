package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.BuiltInCommandAliasValidationDto;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

class CommandRegistryServiceImplTest {
  @Mock private GameSessionControlPlaneClient gameSessionControlPlaneClient;

  @InjectMocks private CommandRegistryServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void validateBuiltInCommandAliasReturnsCanonicalRegistryResult() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.validateBuiltInCommandAlias("LoGoFf"))
        .thenReturn(
            ValidateBuiltInCommandAliasResponse.newBuilder()
                .setSupported(true)
                .setNormalizedAlias("logout")
                .build());

    BuiltInCommandAliasValidationDto result = service.validateBuiltInCommandAlias("LoGoFf");

    assertEquals(true, result.supported());
    assertEquals("logout", result.normalizedAlias());
  }

  @Test
  void validateBuiltInCommandAliasPreservesUnsupportedResult() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.validateBuiltInCommandAlias("mystery"))
        .thenReturn(ValidateBuiltInCommandAliasResponse.newBuilder().build());

    BuiltInCommandAliasValidationDto result = service.validateBuiltInCommandAlias("mystery");

    assertEquals(false, result.supported());
    assertNull(result.normalizedAlias());
  }

  @Test
  void validateBuiltInCommandAliasRequiresGlobalPrivilegedRole() {
    SessionContext.setContext("42", List.of(), Map.of("2", List.of("tenantAdmin")));

    assertThrows(ResponseStatusException.class, () -> service.validateBuiltInCommandAlias("LOOK"));
  }

  @Test
  void validateBuiltInCommandAliasMapsInvalidArgumentToBadRequest() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.validateBuiltInCommandAlias(""))
        .thenReturn(
            ValidateBuiltInCommandAliasResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_ARGUMENT")
                        .setMessage("alias is required")
                        .build())
                .build());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.validateBuiltInCommandAlias(""));

    assertEquals(400, ex.getStatusCode().value());
  }
}
