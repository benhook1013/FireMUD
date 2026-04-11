package net.firedevops.firemud.worldmanagement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.dto.GenerationRuleDto;
import net.firedevops.firemud.worldmanagement.security.JwtAuthInterceptor;
import net.firedevops.firemud.worldmanagement.service.GenerationRuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GenerationRuleController.class)
class GenerationRuleControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private GenerationRuleService generationRuleService;
  @MockitoBean private JwtAuthInterceptor jwtAuthInterceptor;

  @BeforeEach
  void setUpSecurityContext() throws Exception {
    installTenantContext(Map.of("1", List.of("admin")));
  }

  @AfterEach
  void clearSecurityContext() {
    SessionContext.clear();
  }

  @Test
  void listEnforcesTenantScopedAccess() throws Exception {
    when(generationRuleService.listRules(eq(1L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(new GenerationRuleDto(7L, 1L, "room", "{}"))));

    mockMvc
        .perform(get("/generation/rules").param("tenantId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content[0].tenantId").value(1));
  }

  @Test
  void saveRejectsCallerWithoutTenantAccess() throws Exception {
    installTenantContext(Map.of("9", List.of("admin")));

    mockMvc
        .perform(
            post("/generation/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":7,\"tenantId\":1,\"name\":\"room\",\"value\":\"{}\"}"))
        .andExpect(status().isForbidden());
  }

  private void installTenantContext(Map<String, List<String>> scopedRoles) throws Exception {
    doAnswer(
            invocation -> {
              SessionContext.setContext("test-account", List.of(), scopedRoles);
              return true;
            })
        .when(jwtAuthInterceptor)
        .preHandle(any(), any(), any());
  }
}
