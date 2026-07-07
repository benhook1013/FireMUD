package net.firedevops.firemud.worldmanagement.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.dto.RegionDto;
import net.firedevops.firemud.worldmanagement.service.RegionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RegionController.class)
class RegionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private RegionService regionService;

  @BeforeEach
  void setUpSecurityContext() {
    installTenantContext(Map.of("1", List.of("tenantAdmin")));
  }

  @AfterEach
  void clearSecurityContext() {
    SessionContext.clear();
  }

  @Test
  void listUsesTenantAccessCheck() throws Exception {
    when(regionService.listRegions(1L))
        .thenReturn(List.of(new RegionDto(4L, 1L, "North", "clear", 2, 9L, "grid", "{}", 1.0)));

    mockMvc
        .perform(get("/regions").param("tenantId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].name").value("North"));
  }

  @Test
  void moveRejectsCallerWithoutTenantAccess() throws Exception {
    installTenantContext(Map.of("9", List.of("tenantAdmin")));

    mockMvc
        .perform(post("/regions/4/move").param("tenantId", "1").param("shardId", "3"))
        .andExpect(status().isForbidden());
  }

  @Test
  void listRejectsMalformedTenantIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(get("/regions").param("tenantId", "bad-tenant"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be numeric"));

    verifyNoInteractions(regionService);
  }

  @Test
  void moveRejectsZeroTenantIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(post("/regions/4/move").param("tenantId", "0").param("shardId", "3"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(regionService);
  }

  private void installTenantContext(Map<String, List<String>> scopedRoles) {
    SessionContext.setContext("test-account", List.of(), scopedRoles);
  }
}
