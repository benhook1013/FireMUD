package net.firedevops.firemud.socialgroups.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.GlobalExceptionHandler;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.socialgroups.dto.AddGuildMemberRequest;
import net.firedevops.firemud.socialgroups.dto.CreateGuildRequest;
import net.firedevops.firemud.socialgroups.dto.GuildDto;
import net.firedevops.firemud.socialgroups.service.GuildService;
import net.firedevops.firemud.test.WithFiremudHttpAuthTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(GuildController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
  GlobalExceptionHandler.class,
  CommonSecurityAutoConfiguration.class,
  CommonSecurityServletAutoConfiguration.class
})
@WithFiremudHttpAuthTestProperties
class GuildControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private GuildService guildService;
  @Autowired private JwtUtil jwtUtil;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void createGuildAllowsScopedTenantAdmin() throws Exception {
    CreateGuildRequest request = new CreateGuildRequest(1L, 2L, "guild");
    when(guildService.createGuild(request))
        .thenReturn(new GuildDto(1L, 1L, "guild", 2L, Instant.now()));
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/guilds")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.name").value("guild"));
  }

  @Test
  void createGuildRejectsCrossTenantScopedAdmin() throws Exception {
    CreateGuildRequest request = new CreateGuildRequest(1L, 2L, "guild");
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/guilds")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void createGuildRejectsZeroTenantIdBeforeDispatch() throws Exception {
    CreateGuildRequest request = new CreateGuildRequest(0L, 2L, "guild");
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/guilds")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be greater than 0"));

    verifyNoInteractions(guildService);
  }

  @Test
  void addMemberRejectsZeroGuildIdBeforeDispatch() throws Exception {
    AddGuildMemberRequest request = new AddGuildMemberRequest(1L, 0L, 2L, "member");
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/guilds/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("guildId must be greater than 0"));

    verifyNoInteractions(guildService);
  }
}
