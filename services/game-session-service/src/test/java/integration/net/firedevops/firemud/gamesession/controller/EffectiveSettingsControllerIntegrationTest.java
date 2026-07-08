package net.firedevops.firemud.gamesession.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.settings.SharedEffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.command.text.CommunicationCommandHandler;
import net.firedevops.firemud.gamesession.command.text.LoginCommandHandler;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.MoveCommandHandler;
import net.firedevops.firemud.gamesession.config.EffectiveReconnectionSettingsResolver;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@GameSessionIntegrationTest
class EffectiveSettingsControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private EffectiveSettingsResolver settingsResolver;
  @MockitoBean private EffectiveReconnectionSettingsResolver reconnectionSettingsResolver;
  @MockitoBean private SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver;
  @MockitoBean private SessionAuthenticationService sessionAuthenticationService;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private LoginCommandHandler loginCommandHandler;
  @MockitoBean private LookCommandHandler lookCommandHandler;
  @MockitoBean private MoveCommandHandler moveCommandHandler;
  @MockitoBean private CommunicationCommandHandler communicationCommandHandler;
  @MockitoBean private GameInstanceRepository gameInstanceRepository;

  @Test
  void effectiveSettingsRejectsMalformedSessionIdBeforeLookup() throws Exception {
    mockMvc
        .perform(
            get("/actuator/settings/effective")
                .param("sessionId", "not-a-number")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("sessionId must be numeric"));

    verifyNoInteractions(sessionAuthenticationService);
    verifyNoInteractions(settingsResolver);
  }

  @Test
  void effectiveSettingsRejectsZeroSessionIdBeforeLookup() throws Exception {
    mockMvc
        .perform(
            get("/actuator/settings/effective")
                .param("sessionId", "0")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("sessionId must be positive"));

    verifyNoInteractions(sessionAuthenticationService);
    verifyNoInteractions(settingsResolver);
  }

  @Test
  void effectiveSettingsRejectsMalformedTenantIdBeforeSyntheticResolution() throws Exception {
    mockMvc
        .perform(
            get("/actuator/settings/effective")
                .param("tenantId", "not-a-number")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be numeric"));

    verifyNoInteractions(sessionAuthenticationService);
    verifyNoInteractions(settingsResolver);
  }

  @Test
  void effectiveSettingsRejectsZeroGameInstanceIdBeforeSyntheticResolution() throws Exception {
    mockMvc
        .perform(
            get("/actuator/settings/effective")
                .param("gameInstanceId", "0")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("gameInstanceId must be positive"));

    verifyNoInteractions(sessionAuthenticationService);
    verifyNoInteractions(settingsResolver);
  }
}
