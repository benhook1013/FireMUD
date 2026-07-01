package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.BuiltInCommandAliasValidationDto;
import net.firedevops.firemud.loggingadmin.service.CommandRegistryService;
import net.firedevops.firemud.test.WithFiremudJwtTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommandRegistryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudJwtTestProperties
class CommandRegistryControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private CommandRegistryService commandRegistryService;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void validateBuiltInCommandAliasReturnsCanonicalResolution() throws Exception {
    when(commandRegistryService.validateBuiltInCommandAlias("LoGoFf"))
        .thenReturn(new BuiltInCommandAliasValidationDto(true, "logout"));
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/command-registry/built-in-aliases/LoGoFf")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.supported").value(true))
        .andExpect(jsonPath("$.data.normalizedAlias").value("logout"));
  }

  @Test
  void validateBuiltInCommandAliasRejectsTenantOnlyCaller() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("2", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("2", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/command-registry/built-in-aliases/LoGoFf")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }
}
